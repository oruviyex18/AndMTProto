package io.github.oruviyex18.andmtproto

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit

/**
 * Keeps the proxy up when the phone's firmware unloads it. Many firmwares (MIUI,
 * HyperOS, ColorOS…) kill background apps on their own, and then Telegram has no
 * connection until the user notices. So AndMTProto remembers that the user wants
 * the proxy running ([setWanted]), finds out on the next start of the app why its
 * last process ended (Android 11+, [checkLastExit]), and starts the proxy again:
 * when the app is opened, and from a job every 15 minutes ([revive]). Where
 * Android doesn't let a background app start it, a notification offers to.
 */
object ProxyWatch {
    private const val TAG = "ProxyWatch"
    private const val PREFS = "proxy_watch"
    private const val KEY_WANTED = "wanted"
    private const val KEY_WANTED_BOOT = "wanted_boot"
    private const val KEY_SEEN_EXIT = "seen_exit_at"
    private const val KEY_STOP_KIND = "stop_kind"
    private const val KEY_STOP_AT = "stop_at"
    private const val KEY_STOP_DETAIL = "stop_detail"
    private const val KEY_STOP_LOGGED = "stop_logged"
    private const val KEY_STOP_DISMISSED = "stop_dismissed"
    private const val JOB_ID = 1002
    private const val REVIVE_PERIOD_MS = 15 * 60_000L
    /** After a start was asked for, the service gets this long to come up before a revive. */
    const val START_GRACE_MS = 10_000L
    const val NOTIFICATION_ID = 3

    /** How the app's last process ended, in the kinds the app explains. */
    enum class StopKind {
        /** The firmware or Android unloaded it: low memory, a background-app killer. */
        SYSTEM,

        /** It crashed, or hung (ANR). */
        CRASH,

        /** The user force-stopped it (or cleared it from recents, on some firmwares). */
        USER,

        /** An update or a permission change; Android restarts nothing, but nothing's wrong. */
        UPDATE,

        /** It ended itself, or something else that needs no explaining. */
        OTHER,
    }

    data class Stop(val kind: StopKind, val atMillis: Long, val detail: String)

    /**
     * [ApplicationExitInfo]'s reason code (and description) as a [StopKind]; plain
     * values, for tests. Installing an update is "user requested" too, described as
     * "stop <package> due to installPackageLI" (Android 13, Redmi, 25 Nov 2026).
     */
    fun kindOf(reason: Int, description: String? = null): StopKind = when (reason) {
        0, 2, 3, 9, 13, 14 -> StopKind.SYSTEM // unknown, signaled, low memory, excessive use, other, freezer
        4, 5, 6, 7 -> StopKind.CRASH // crash, native crash, ANR, initialization failure
        10, 11 -> if (description?.contains("installPackage") == true) StopKind.UPDATE else StopKind.USER
        8, 15, 16 -> StopKind.UPDATE // permission change, package state change, package updated
        else -> StopKind.OTHER // exit self, dependency died
    }

    /** The main screen explains these stops; the others need nothing from the user. */
    fun worthACard(kind: StopKind): Boolean = kind == StopKind.SYSTEM || kind == StopKind.CRASH

    /** The want counts only in the boot it was set in; after a reboot it's "Start on boot"'s business. */
    fun wantedNow(flag: Boolean, wantedBoot: Int, boot: Int): Boolean = flag && wantedBoot == boot

    /**
     * Whether to start the proxy again: wanted, not running, and no start asked for
     * in the last [START_GRACE_MS] (the service may not exist yet: the tile's start
     * through the app and the app's own revive would otherwise both start it).
     */
    fun shouldRevive(wanted: Boolean, running: Boolean, sinceStartRequestMs: Long): Boolean =
        wanted && !running && sinceStartRequestMs >= START_GRACE_MS

    /** One process exit, as [ApplicationExitInfo] gives it; plain values, for tests. */
    data class Exit(val timestamp: Long, val processName: String, val reason: Int, val description: String?)

    /** What [checkLastExit] records: the stop (null if the proxy wasn't wanted) and whether to drop the want. */
    data class ExitDecision(val seenAt: Long, val stop: Stop?, val clearWant: Boolean)

    /**
     * The newest exit of the app's main process ([packageName]) after [seenAt], if
     * any: always marked seen; a stop to record when the proxy was [wanted]; and a
     * force-stop (the user's) drops the want. Null when there's nothing new.
     */
    fun decideExit(exits: List<Exit>, packageName: String, seenAt: Long, wanted: Boolean): ExitDecision? {
        val last = exits.filter { it.processName == packageName }.maxByOrNull { it.timestamp } ?: return null
        if (last.timestamp <= seenAt) return null
        if (!wanted) return ExitDecision(last.timestamp, null, clearWant = false)
        val kind = kindOf(last.reason, last.description)
        val name = reasonName(last.reason)
        // Android's description often just repeats the reason ("crash").
        val description = last.description?.take(120)?.takeIf { it.isNotBlank() && it != name }
        val detail = listOfNotNull(name, description).joinToString(": ")
        return ExitDecision(last.timestamp, Stop(kind, last.timestamp, detail), clearWant = kind == StopKind.USER)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** This boot's number; a want from an earlier boot is "Start on boot"'s business. */
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /**
     * The user started the proxy ([wanted] true) or stopped it (false), or it
     * stopped on an error the user must fix. Schedules or cancels the revive job.
     */
    fun setWanted(context: Context, wanted: Boolean) {
        prefs(context).edit {
            putBoolean(KEY_WANTED, wanted)
            putInt(KEY_WANTED_BOOT, bootCount(context))
        }
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        try {
            if (wanted) {
                jobs.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, ProxyReviveJob::class.java))
                        .setPeriodic(REVIVE_PERIOD_MS)
                        .build()
                )
            } else {
                jobs.cancel(JOB_ID)
                context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot schedule the revive job", e)
        }
    }

    /** The user wants the proxy running in this boot. */
    fun wanted(context: Context): Boolean {
        val p = prefs(context)
        return wantedNow(p.getBoolean(KEY_WANTED, false), p.getInt(KEY_WANTED_BOOT, -2), bootCount(context))
    }

    /**
     * At each start of the app's process: if the last one ended while the proxy
     * was wanted, keeps why, for the log, the main screen and the report.
     */
    fun checkLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot read exit reasons", e)
            return
        }
        val p = prefs(context)
        val decision = decideExit(
            exits.map { Exit(it.timestamp, it.processName, it.reason, it.description) },
            context.packageName, p.getLong(KEY_SEEN_EXIT, 0), wanted(context),
        ) ?: return
        p.edit { putLong(KEY_SEEN_EXIT, decision.seenAt) }
        val stop = decision.stop ?: return
        p.edit {
            putString(KEY_STOP_KIND, stop.kind.name)
            putLong(KEY_STOP_AT, stop.atMillis)
            putString(KEY_STOP_DETAIL, stop.detail)
            putBoolean(KEY_STOP_LOGGED, false)
            putBoolean(KEY_STOP_DISMISSED, !worthACard(stop.kind))
        }
        // A force-stop in Android's settings is the user's stop: don't start the proxy again.
        if (decision.clearWant) setWanted(context, false)
    }

    /** Why the proxy last stopped without the user, if it did; null otherwise. */
    fun lastStop(context: Context): Stop? {
        val p = prefs(context)
        val kind = p.getString(KEY_STOP_KIND, null)?.let { runCatching { StopKind.valueOf(it) }.getOrNull() } ?: return null
        return Stop(kind, p.getLong(KEY_STOP_AT, 0), p.getString(KEY_STOP_DETAIL, null).orEmpty())
    }

    /** [lastStop] while the main screen should still explain it. */
    fun stopToExplain(context: Context): Stop? =
        lastStop(context)?.takeIf { !prefs(context).getBoolean(KEY_STOP_DISMISSED, true) }

    fun dismissStop(context: Context) = prefs(context).edit { putBoolean(KEY_STOP_DISMISSED, true) }

    /** A log line about [lastStop] the first time the core starts after it; null after that. */
    fun takeStopNote(context: Context): String? {
        val p = prefs(context)
        if (p.getBoolean(KEY_STOP_LOGGED, true)) return null
        val stop = lastStop(context) ?: return null
        p.edit { putBoolean(KEY_STOP_LOGGED, true) }
        val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(stop.atMillis))
        return "The app's last process ended at $at while the proxy was on: ${stop.kind.name.lowercase()} (${stop.detail})"
    }

    /**
     * Starts the proxy again if the user wants it and it isn't running ([from]:
     * what asked, for the log). A background app may not start it on Android 12+
     * unless battery optimisation is off for it; then a notification offers to.
     */
    fun revive(context: Context, from: String) {
        val sinceStart = android.os.SystemClock.elapsedRealtime() - ProxyService.startRequestedAt
        if (!shouldRevive(wanted(context), ProxyService.running, sinceStart)) return
        Log.i(TAG, "The proxy should be running but isn't; starting it ($from)")
        try {
            ProxyService.start(context)
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException, IllegalStateException
            Log.w(TAG, "Cannot start the proxy from the background", e)
            notifyStopped(context)
        }
    }

    private fun notifyStopped(context: Context) {
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, App.STOPPED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.revive_title))
            .setContentText(context.getString(R.string.revive_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.revive_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot notify", e) // notifications denied
        }
    }

    internal fun reasonName(reason: Int): String? = when (reason) {
        0 -> "unknown"
        1 -> "exit self"
        2 -> "signaled"
        3 -> "low memory"
        4 -> "crash"
        5 -> "native crash"
        6 -> "ANR"
        7 -> "initialization failure"
        8 -> "permission change"
        9 -> "excessive resource usage"
        10 -> "user requested"
        11 -> "user stopped"
        12 -> "dependency died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package state change"
        16 -> "package updated"
        else -> null
    }

    /** The recent exits, newest first, for the report; empty before Android 11. */
    fun recentExits(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
        return try {
            am.getHistoricalProcessExitReasons(context.packageName, 0, 5).map { e: ApplicationExitInfo ->
                "${fmt.format(java.util.Date(e.timestamp))}  ${reasonName(e.reason) ?: e.reason}" +
                    (e.description?.let { ": ${it.take(120)}" } ?: "")
            }
        } catch (e: RuntimeException) {
            emptyList()
        }
    }
}

/** The revive job ([ProxyWatch.revive]); runs every 15 minutes while the proxy is wanted. */
class ProxyReviveJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        ProxyWatch.revive(this, "revive job")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

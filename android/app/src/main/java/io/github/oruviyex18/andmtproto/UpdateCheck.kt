package io.github.oruviyex18.andmtproto

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Daily check for a newer GitHub release. Runs as a periodic [JobScheduler] job
 * (persisted across reboots, only with network) and notifies once per new version.
 */
object UpdateCheck {
    private const val TAG = "UpdateCheck"
    private const val JOB_ID = 1001
    private const val NOTIFICATION_ID = 2
    private const val LATEST_URL =
        "https://api.github.com/repos/oruviyex18/AndMTProto/releases/latest"
    private const val TAG_PREFIX = "android-v"
    private const val PREFS = "update_check"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_LATEST_URL = "latest_url"
    private const val KEY_DISMISSED = "dismissed_version"

    /** A newer release found by the last check, for the main screen. */
    data class Available(val version: String, val url: String)

    /** The newer release from the last check, unless installed meanwhile or dismissed. */
    fun available(context: Context): Available? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(KEY_LATEST, null) ?: return null
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        if (prefs.getString(KEY_DISMISSED, null) == version) return null
        return Available(version, prefs.getString(KEY_LATEST_URL, null).orEmpty())
    }

    fun dismiss(context: Context, version: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_DISMISSED, version) }
    }

    /**
     * Starts the daily job, or cancels it when [enabled] is false. Called from
     * App.onCreate, so a JobScheduler failure is logged instead of crashing the app.
     */
    fun schedule(context: Context, enabled: Boolean) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (!enabled) {
                scheduler.cancel(JOB_ID)
                return
            }
            // Rescheduling would restart the period, so keep an existing job.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        } catch (e: RuntimeException) {
            // E.g. SecurityException on Android 16 without ACCESS_NETWORK_STATE.
            Log.w(TAG, "Could not schedule the update check", e)
        }
    }

    /** Fetches the latest release and notifies if it is newer; throws [IOException] on network errors. */
    fun run(context: Context) {
        val (version, url) = fetchLatest() ?: return
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_LATEST, version).putString(KEY_LATEST_URL, url) }
        if (prefs.getString(KEY_NOTIFIED, null) == version) return
        Log.i(TAG, "New version $version available")
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(context, version, url))
        prefs.edit { putString(KEY_NOTIFIED, version) }
    }

    /** (versionName, release page URL) of the latest release, or null if the tag isn't ours. */
    private fun fetchLatest(): Pair<String, String>? {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "AndMTProto/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            if (!tag.startsWith(TAG_PREFIX)) return null
            return tag.removePrefix(TAG_PREFIX) to o.optString("html_url")
        } finally {
            conn.disconnect()
        }
    }

    /** True if [candidate] is a later "1.11.4-android.3"-style version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** "1.11.4-android.3" -> [1, 11, 4, 3]; the android revision sorts after the core version. */
    private fun parse(version: String): List<Int>? {
        val m = Regex("""^(\d+(?:\.\d+)*)-android\.(\d+)$""").matchEntire(version.trim()) ?: return null
        val core = m.groupValues[1].split('.').map { it.toInt() }
        // Pad the core to a fixed length so "1.12" and "1.11.4" compare by position.
        return core + List(maxOf(0, 4 - core.size)) { 0 } + m.groupValues[2].toInt()
    }

    private fun buildNotification(context: Context, version: String, url: String): Notification {
        val page = url.ifBlank { context.getString(R.string.latest_release_url) }
        val open = PendingIntent.getActivity(
            context, 3, Intent(Intent.ACTION_VIEW, page.toUri()), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, App.UPDATES_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available_title, version))
            .setContentText(context.getString(R.string.update_available_text, BuildConfig.VERSION_NAME))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }
}

class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            val retry = try {
                UpdateCheck.run(applicationContext)
                false
            } catch (e: Exception) {
                // Network errors and unexpected replies; the scheduler retries with backoff.
                Log.w("UpdateCheck", "Update check failed", e)
                e is IOException
            }
            jobFinished(params, retry)
        }, "update-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

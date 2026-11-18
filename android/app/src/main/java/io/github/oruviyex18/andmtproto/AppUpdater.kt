package io.github.oruviyex18.andmtproto

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/**
 * "Update the app": finds the latest GitHub release, downloads its APK, checks it is
 * this app, newer and signed with the same key, and hands it to the system installer
 * (PackageInstaller), which asks the user to confirm. The proxy stops while Android
 * replaces the app; [UpdatedReceiver] starts it again if it was running.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val DIR = "updates"
    private const val PREFS = "app_updater"
    /** The versionCode being installed while the proxy ran; that version restarts it. */
    private const val KEY_RESTART_FOR = "restart_proxy_for"
    private const val ACTION_STATUS = "io.github.oruviyex18.andmtproto.action.INSTALL_STATUS"

    enum class Problem {
        /** No connection to GitHub, or the download broke off. */
        NETWORK,
        /** The latest release has no APK attached. */
        NO_APK,
        /** The downloaded file doesn't match GitHub's checksum. */
        CHECKSUM,
        /** Not this app, not newer, or signed with another key (e.g. a debug build). */
        NOT_OURS,
        /** The system installer refused or failed; [State.Failed.detail] has its message. */
        INSTALL,
        /** The user cancelled the system installer. */
        CANCELLED,
    }

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** The installed version is the latest; shown once, then back to [Idle]. */
        data class UpToDate(val version: String) : State
        /** Android 8+ needs "Install unknown apps" for AndMTProto first. */
        data class NeedsPermission(val release: UpdateCheck.Release) : State
        data class Downloading(val release: UpdateCheck.Release, val done: Long, val total: Long) : State
        /**
         * Handed to the system installer, which asks the user to confirm. [confirm] is
         * its confirmation screen, kept so the user can open it again: Android may not
         * show it while AndMTProto is in the background.
         */
        data class Installing(val release: UpdateCheck.Release, val confirm: Intent? = null) : State
        data class Failed(val problem: Problem, val release: UpdateCheck.Release?, val detail: String? = null) : State
    }

    private class BadChecksum : IOException("checksum mismatch")
    private class NotOurs(reason: String) : Exception(reason)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    /** The current run; only its state changes are shown (see [publish]). */
    private var job: Job? = null
    /** The latest run, which may still be finishing a blocking read after [cancel]. */
    private var last: Job? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Checks for a newer release and, if there is one, downloads and installs it. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (job?.isActive == true) return
            launchRun {
                publish(State.Checking)
                val release = try {
                    UpdateCheck.fetchLatest()
                } catch (e: IOException) {
                    Log.w(TAG, "Update check failed", e)
                    publish(State.Failed(Problem.NETWORK, null, e.message))
                    return@launchRun
                }
                if (release == null || !UpdateCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    publish(State.UpToDate(BuildConfig.VERSION_NAME))
                    return@launchRun
                }
                UpdateCheck.remember(app, release)
                continueWith(app, release)
            }
        }
    }

    /** After the user came back from the "Install unknown apps" screen. */
    fun resume(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val waiting = _state.value as? State.NeedsPermission ?: return
            if (job?.isActive == true || !canInstall(app)) return
            launchRun { continueWith(app, waiting.release) }
        }
    }

    /** Stops the download, or forgets an install the user didn't confirm. */
    fun cancel(context: Context) {
        val was = synchronized(lock) {
            job?.cancel()
            job = null
            _state.value.also { _state.value = State.Idle }
        }
        if (was is State.Installing) clearRestart(context)
    }

    /** Opens the installer's confirmation screen again (see [State.Installing.confirm]). */
    fun confirm(context: Context) {
        val confirm = (_state.value as? State.Installing)?.confirm ?: return
        showConfirm(context, confirm)
    }

    /** Back to [State.Idle] after the UI showed a one-off state such as [State.UpToDate]. */
    fun acknowledge() = synchronized(lock) {
        val s = _state.value
        if (s is State.UpToDate || s is State.Failed) _state.value = State.Idle
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Starts [block] as the current run, after the previous one has really ended. Call under [lock]. */
    private fun launchRun(block: suspend () -> Unit) {
        val previous = last
        val next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin() // a cancelled download may still be writing its file
            block()
        }
        job = next
        last = next
        next.start()
    }

    /** Shows [s] unless this run was cancelled or replaced meanwhile. */
    private suspend fun publish(s: State) {
        val me = currentCoroutineContext()[Job]
        synchronized(lock) { if (me === job) _state.value = s }
    }

    private suspend fun continueWith(app: Context, release: UpdateCheck.Release) {
        if (release.apkUrl.isBlank()) {
            publish(State.Failed(Problem.NO_APK, release))
            return
        }
        if (!canInstall(app)) {
            publish(State.NeedsPermission(release))
            return
        }
        val apk = try {
            download(app, release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadChecksum) {
            publish(State.Failed(Problem.CHECKSUM, release))
            return
        } catch (e: IOException) {
            Log.w(TAG, "Update download failed", e)
            publish(State.Failed(Problem.NETWORK, release, e.message))
            return
        }
        val versionCode = try {
            checkApk(app, apk)
        } catch (e: NotOurs) {
            apk.delete()
            publish(State.Failed(Problem.NOT_OURS, release, e.message))
            return
        }
        currentCoroutineContext().ensureActive()
        publish(State.Installing(release))
        try {
            install(app, apk, versionCode)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the installer", e)
            clearRestart(app)
            publish(State.Failed(Problem.INSTALL, release, e.message))
        }
    }

    /** The release APK, downloaded or kept from before, matching GitHub's checksum. */
    private suspend fun download(app: Context, release: UpdateCheck.Release): File {
        val dir = File(app.cacheDir, DIR).apply { mkdirs() }
        val name = "AndMTProto-${release.version}.apk"
        val done = File(dir, name)
        dir.listFiles()?.filter { it.name != name }?.forEach { it.delete() } // older downloads
        if (done.length() > 0 && done.length() == release.apkSize &&
            (release.sha256 == null || sha256(done) == release.sha256)
        ) {
            return done // downloaded earlier and not installed yet
        }
        val part = File(dir, "$name.part")
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        // GitHub redirects release assets to another host; follow a few redirects by hand.
        var hops = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "AndMTProto/${BuildConfig.VERSION_NAME}")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next == null) throw IOException("HTTP $code without Location")
                url = URL(url, next)
                if (url.protocol != "https") throw IOException("Redirect to ${url.protocol}")
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            break
        }
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var got = 0L
            var shown = -1L
            publish(State.Downloading(release, 0, total))
            conn.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        val step = if (total > 0) got * 100 / total else got / (1 shl 20)
                        if (step != shown) {
                            shown = step
                            publish(State.Downloading(release, got, total))
                        }
                    }
                }
            }
            if (total > 0 && got != total) throw IOException("Got $got of $total bytes")
        } finally {
            conn.disconnect()
        }
        currentCoroutineContext().ensureActive()
        if (release.sha256 != null && sha256(part) != release.sha256) {
            part.delete()
            throw BadChecksum()
        }
        if (!part.renameTo(done)) throw IOException("Cannot keep the download")
        return done
    }

    /** The versionCode of [apk] if it can update this app; throws [NotOurs] with the reason if not. */
    private fun checkApk(context: Context, apk: File): Long {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw NotOurs("not an APK")
        if (archive.packageName != context.packageName) throw NotOurs("package ${archive.packageName}")
        val installed = pm.getPackageInfo(context.packageName, flags)
        val versionCode = PackageInfoCompat.getLongVersionCode(archive)
        if (versionCode <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw NotOurs("version ${archive.versionName} is not newer")
        }
        val theirs = signers(archive)
        if (theirs.isEmpty()) throw NotOurs("signature unreadable")
        if (theirs != signers(installed)) throw NotOurs("signed with another key")
        return versionCode
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else {
            info.signatures
        }
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun install(context: Context, apk: File, versionCode: Long) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("AndMTProto.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The proxy stops while Android replaces the app; start it again afterwards,
            // but only once this very version is installed.
            prefs(context).edit {
                if (ProxyCore.state.value.running) putLong(KEY_RESTART_FOR, versionCode)
                else remove(KEY_RESTART_FOR)
            }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(status.intentSender)
        }
    }

    private fun showConfirm(context: Context, confirm: Intent) {
        try {
            context.startActivity(confirm)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot show the installer", e)
            clearRestart(context)
            val release = (_state.value as? State.Installing)?.release
            synchronized(lock) { _state.value = State.Failed(Problem.INSTALL, release, e.message) }
        }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val release = (_state.value as? State.Installing)?.release
        fun fail(problem: Problem, detail: String? = null) {
            clearRestart(context)
            synchronized(lock) { _state.value = State.Failed(problem, release, detail) }
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm == null) {
                    fail(Problem.INSTALL, "no confirmation screen")
                    return
                }
                // Kept for the card's button: from the background Android may not show it.
                if (release != null) synchronized(lock) { _state.value = State.Installing(release, confirm) }
                showConfirm(context, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> synchronized(lock) { _state.value = State.Idle } // normally the app is replaced first
            PackageInstaller.STATUS_FAILURE_ABORTED -> fail(Problem.CANCELLED)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Install failed: $status $message")
                fail(Problem.INSTALL, message ?: "status $status")
            }
        }
    }

    /** After Android replaced the app: drop the download and restart the proxy if it ran. */
    internal fun onUpdated(context: Context) {
        File(context.cacheDir, DIR).deleteRecursively()
        val prefs = prefs(context)
        val restartFor = prefs.getLong(KEY_RESTART_FOR, -1)
        val restart = restartFor == BuildConfig.VERSION_CODE.toLong()
        clearRestart(context)
        if (restart) {
            try {
                ProxyService.start(context)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot restart the proxy after the update", e)
            }
        }
    }

    private fun clearRestart(context: Context) =
        prefs(context).edit { remove(KEY_RESTART_FOR) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** Receives the system installer's progress for [AppUpdater]. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallStatus(context, intent)
}

/** Android sends this to the app itself after an update replaced it. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AppUpdater.onUpdated(context)
    }
}

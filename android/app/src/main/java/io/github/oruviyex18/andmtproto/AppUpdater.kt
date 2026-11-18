package io.github.oruviyex18.andmtproto

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * "Update the app": asks GitHub for the latest release. A newer one shows the
 * update card, whose "Download" opens the APK in the browser; the user then
 * opens the file and Android's own installer updates the app.
 *
 * AndMTProto doesn't download or install APKs itself: 1.11.4-android.7 did
 * (REQUEST_INSTALL_PACKAGES + PackageInstaller), and Play Protect blocked that
 * APK as harmful, since apps that fetch and install other APKs look like malware
 * droppers to it.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** The installed version is the latest; shown once, then back to [Idle]. */
        data class UpToDate(val version: String) : State
        /** No connection to GitHub; [detail] is the error. */
        data class Failed(val detail: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Checks for a newer release and keeps it for the update card (see
     * [UpdateCheck.available]); a version the user dismissed earlier shows again.
     */
    suspend fun check(context: Context) {
        if (_state.value == State.Checking) return
        _state.value = State.Checking
        val release = try {
            withContext(Dispatchers.IO) { UpdateCheck.fetchLatest() }
        } catch (e: IOException) {
            Log.w(TAG, "Update check failed", e)
            _state.value = State.Failed(e.message)
            return
        }
        if (release == null || !UpdateCheck.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            _state.value = State.UpToDate(BuildConfig.VERSION_NAME)
            return
        }
        UpdateCheck.remember(context, release, dismissed = false)
        _state.value = State.Idle
    }

    /** Back to [State.Idle] after the UI showed [State.UpToDate] or [State.Failed]. */
    fun acknowledge() {
        val s = _state.value
        if (s is State.UpToDate || s is State.Failed) _state.value = State.Idle
    }

    /** 1.11.4-android.7 downloaded update APKs into cache/updates; drop what is left. */
    fun cleanUp(context: Context) {
        File(context.cacheDir, "updates").takeIf { it.exists() }?.deleteRecursively()
    }
}

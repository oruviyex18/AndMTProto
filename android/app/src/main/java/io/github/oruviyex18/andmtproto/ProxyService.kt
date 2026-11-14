package io.github.oruviyex18.andmtproto

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service that keeps the Python proxy running and shows its notification. */
class ProxyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null
    private var settings = ProxySettings()

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var network: Network? = null
    private var networkLost = false
    private var pendingRestart: Job? = null

    /** When the watchdog last restarted the core (elapsedRealtime); at most once per cooldown. */
    private var autoRestartAt = NEVER

    /**
     * Follows the system's default network. Connections the core opened on the old
     * network (its WebSocket and CF pools) die silently when it changes, so the core
     * is restarted on the new one; see [onDefaultNetwork].
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            scope.launch { onDefaultNetwork(n) }
        }

        override fun onLost(n: Network) {
            scope.launch { onNetworkLost(n) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot follow network changes", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        settings = SettingsStore(this).load()
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val restart = intent?.action == ACTION_RESTART
        // One session at a time: a restart replaces the running poll loop.
        session?.cancel()
        session = scope.launch { runSession(settings, restart) }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot stop following network changes", e)
        }
        scope.cancel()
        ProxyCore.stopInBackground()
        super.onDestroy()
    }

    private suspend fun runSession(current: ProxySettings, restart: Boolean) {
        if (restart) ProxyCore.stop()
        if (ProxyCore.start(this, current) != null) {
            stopSelf()  // config error; it stays in ProxyCore.state for the UI
            return
        }
        ProxyCore.note("Network: ${connectivity.describe(network ?: connectivity.activeNetwork)}")
        delay(STARTUP_POLL_MS)
        var shown: ProxyStatus? = null
        var listened = false
        val stalls = StallDetector()
        while (true) {
            val status = ProxyCore.poll()
            if (!status.running) {
                val free = firstRunFreePort(status)
                if (free != null) {
                    // First start after install and the default port is taken: move on.
                    Log.i(TAG, "Port ${current.port} is in use on first start; using $free")
                    settings = current.copy(port = free)
                    SettingsStore(this).save(settings)
                    runSession(settings, restart = false)
                } else {
                    stopSelf()  // bind error or crash; the error stays in ProxyCore.state
                }
                return
            }
            if (status.listening && !listened) {
                listened = true
                SettingsStore(this).autoPort = false // the port works; never change it again
            }
            if (status.listening && watchStall(stalls, status)) return
            if (status.active != shown?.active || status.listening != shown.listening) {
                notify(buildNotification(status))
                shown = status
            }
            delay(POLL_MS)
        }
    }

    /**
     * When nothing has got through for a minute although Telegram keeps trying,
     * restarts the core once (fresh pools, DNS and CF domain choice); if that
     * doesn't help either, tells the UI. Returns true when this session was replaced.
     */
    private fun watchStall(stalls: StallDetector, status: ProxyStatus): Boolean {
        val now = SystemClock.elapsedRealtime()
        val stuck = stalls.update(status, now)
        if (stalls.progressed && ProxyCore.stalled.value) ProxyCore.setStalled(false)
        if (!stuck) return false
        if (now - autoRestartAt >= AUTO_RESTART_COOLDOWN_MS) {
            autoRestartAt = now
            ProxyCore.note(
                "Nothing got through for a minute although Telegram keeps connecting; restarting the proxy core"
            )
            restartSession()
            return true
        }
        if (!ProxyCore.stalled.value) {
            ProxyCore.note("Still nothing gets through after the restart, over ${connectivity.describe(network)}")
            ProxyCore.setStalled(true)
        }
        return false
    }

    /** Replaces the running session with a fresh core; safe to call from that session. */
    private fun restartSession() {
        val old = session
        session = scope.launch {
            old?.cancel()
            runSession(settings, restart = true)
        }
    }

    private fun onDefaultNetwork(n: Network) {
        val previous = network
        network = n
        if (previous == n) return
        if (previous == null && !networkLost) return // the first report after the service starts
        val name = connectivity.describe(n)
        ProxyCore.note(
            if (networkLost) "Network is back ($name); restarting the proxy core"
            else "Network changed to $name; restarting the proxy core"
        )
        networkLost = false
        // Networks flap while switching (Wi-Fi reconnected 3 times in 20 s once):
        // restart once things have settled.
        pendingRestart?.cancel()
        pendingRestart = scope.launch {
            delay(NETWORK_SETTLE_MS)
            autoRestartAt = NEVER // a new network gets its own watchdog restart
            restartSession()
        }
    }

    private fun onNetworkLost(n: Network) {
        if (n != network) return
        network = null
        networkLost = true
        pendingRestart?.cancel()
        ProxyCore.note("Network lost; waiting for it to come back")
    }

    /** The free port the core suggested, if the port was busy and may still be changed. */
    private fun firstRunFreePort(status: ProxyStatus): Int? {
        val error = status.error?.takeIf { it.code == "port_in_use" } ?: return null
        val store = SettingsStore(this)
        if (!store.autoPort) return null
        store.autoPort = false // once: a port that is busy again stays an error for the user
        return error.suggestedPort
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(null),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        true
    } catch (e: Exception) {
        // E.g. a sticky restart that the system no longer allows to go foreground.
        Log.w(TAG, "Cannot enter foreground", e)
        false
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(status: ProxyStatus?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (status?.running == true && status.listening) {
            getString(R.string.notif_running, settings.host, settings.port, status.active)
        } else {
            getString(R.string.notif_starting)
        }
        val builder = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)

        ProxyCore.link(settings)?.let { link ->
            val tg = PendingIntent.getActivity(
                this, 2, Intent(Intent.ACTION_VIEW, link.toUri()), PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, getString(R.string.action_open_telegram), tg)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "ProxyService"
        private const val NOTIFICATION_ID = 1
        private const val POLL_MS = 2_000L
        private const val STARTUP_POLL_MS = 500L
        private const val NETWORK_SETTLE_MS = 3_000L
        private const val AUTO_RESTART_COOLDOWN_MS = 10 * 60_000L
        private const val NEVER = Long.MIN_VALUE / 2
        private const val ACTION_START = "start"
        private const val ACTION_RESTART = "restart"
        private const val ACTION_STOP = "stop"

        /** Starts the proxy, or restarts it with the saved settings when [restart] is true. */
        fun start(context: Context, restart: Boolean = false) {
            val intent = Intent(context, ProxyService::class.java)
                .setAction(if (restart) ACTION_RESTART else ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }
    }
}

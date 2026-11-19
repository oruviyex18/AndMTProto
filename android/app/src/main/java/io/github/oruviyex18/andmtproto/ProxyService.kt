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

    /** The network the running core started on, and whether it was lost since. */
    private var coreNetwork: Network? = null
    private var lostSinceStart = false

    /** The network "Choose automatically" last checked once the core listened (see [checkCf]). */
    private var startCheckedOn: Network? = null

    /** The network it last checked because nothing got through (see [watchStall]). */
    private var stallCheckedOn: Network? = null

    /** The log already says why the watchdog doesn't restart on a network that cuts Cloudflare off. */
    private var cutOffNoted = false

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
        // A start or a restart by the user (e.g. new settings) checks the CF connection again.
        startCheckedOn = null
        stallCheckedOn = null
        // One session at a time: a restart replaces the running poll loop.
        session?.cancel()
        if (restart) ProxyCore.setStalled(false) // the user asked; the card returns only if still stuck
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
        ProxyCore.setCfCheck(null)
        ProxyCore.stopInBackground()
        super.onDestroy()
    }

    private suspend fun runSession(current: ProxySettings, restart: Boolean) {
        if (restart) ProxyCore.stop(keepStalled = true)
        if (ProxyCore.start(this, current) != null) {
            stopSelf()  // config error; it stays in ProxyCore.state for the UI
            return
        }
        coreNetwork = network ?: connectivity.activeNetwork
        lostSinceStart = false
        ProxyCore.note(deviceSummary())
        ProxyCore.note(settingsSummary(current))
        ProxyCore.note("Network: ${describeNetwork(coreNetwork)}")
        ProxyCore.note(networkDetails(coreNetwork))
        delay(STARTUP_POLL_MS)
        var shown: ProxyStatus? = null
        var listened = false
        var checkCfNow = false
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
                checkCfNow = true
            }
            if (status.listening && watchStall(stalls, status)) return
            if (status.active != shown?.active || status.listening != shown.listening) {
                notify(buildNotification(status))
                shown = status
            }
            if (checkCfNow) {
                checkCfNow = false
                // After the notification: the check takes seconds. Once per network, so a
                // restart it causes doesn't check again.
                val on = network ?: connectivity.activeNetwork
                if (on != null && on != startCheckedOn) {
                    startCheckedOn = on
                    if (checkCf(on)) return
                }
            }
            delay(POLL_MS)
        }
    }

    /**
     * When nothing has got through for a minute although Telegram keeps trying,
     * first lets "Choose automatically" check the CF connection again ([checkCf],
     * once per network: blocking can start mid-session), then restarts the core once (fresh pools, DNS and CF domain choice); if that
     * doesn't help either, tells the UI. Returns true when this session was replaced.
     */
    private suspend fun watchStall(stalls: StallDetector, status: ProxyStatus): Boolean {
        val now = SystemClock.elapsedRealtime()
        val stuck = stalls.update(status, now)
        if (stalls.progressed && ProxyCore.stalled.value) ProxyCore.setStalled(false)
        if (!stuck) return false
        val on = network ?: connectivity.activeNetwork
        if (on != null && on != stallCheckedOn) {
            stallCheckedOn = on
            if (checkCf(on)) return true
        }
        val check = ProxyCore.cfCheck.value
        if (on != null && check?.network == on && check.cutOff) {
            // A fresh core can't get past that (an MTS network restarted it 6 times in an
            // hour on 18 Nov 2026, dropping what did work); the main screen says why.
            if (!cutOffNoted) {
                cutOffNoted = true
                ProxyCore.note("Not restarting the proxy core: ${describeNetwork(on)} cuts connections to Cloudflare off after about 16 KB")
            }
            return false
        }
        if (now - autoRestartAt >= AUTO_RESTART_COOLDOWN_MS) {
            autoRestartAt = now
            ProxyCore.note(
                "Nothing got through for a minute although Telegram keeps connecting; restarting the proxy core"
            )
            restartSession()
            return true
        }
        if (!ProxyCore.stalled.value) {
            ProxyCore.note("Still nothing gets through after the restart, over ${describeNetwork(network)}")
            ProxyCore.setStalled(true)
        }
        return false
    }

    /**
     * "Choose automatically" for the CF connection: checks TLS with the bypass,
     * plain TLS and no TLS on [on] and moves to the first that works, in
     * [CF_AUTO_ORDER]. Since 16 Nov 2026 Russian DPI cuts TLS to the shared CF proxy
     * domains by name; the bypass usually gets past that, and plain TLS covers
     * networks that break its split packets. A way the user picked themselves is
     * never changed. Returns true when this session was replaced.
     */
    private suspend fun checkCf(on: Network): Boolean {
        if (!settings.cfproxy || !settings.cfAuto) return false
        val probe = ProxyCore.probeCf() ?: return false
        noteProbe(on, probe)
        // A way that also carries large data; else one where Telegram at least answers.
        val full = CF_AUTO_ORDER.firstOrNull { probe.worksFully(it) }
        val target = full ?: CF_AUTO_ORDER.firstOrNull { probe.works(it) }
        val cutOff = full == null && target != null
        ProxyCore.setCfCheck(
            ProxyCore.CfCheck(on, describeNetwork(on), System.currentTimeMillis(), target, cutOff)
        )
        if (target == null) {
            ProxyCore.note("No way to reach the CF proxy works on ${describeNetwork(on)}; keeping ${label(settings.cfTransport)}")
            return false
        }
        if (cutOff) {
            ProxyCore.note(
                "${describeNetwork(on)} cuts every way to the CF proxy off after about 16 KB; only another network or a VPN helps"
            )
        } else {
            cutOffNoted = false
        }
        if (target == settings.cfTransport) return false
        ProxyCore.note("Choosing the CF connection automatically: ${label(target)} (was ${label(settings.cfTransport)})")
        settings = settings.withCurrentCfTransport(target)
        SettingsStore(this).save(settings)
        restartSession()
        return true
    }

    private fun label(t: CfTransport) = when (t) {
        CfTransport.BYPASS -> "TLS with the bypass"
        CfTransport.TLS -> "plain TLS"
        CfTransport.PLAIN -> "no TLS"
    }

    private fun noteProbe(on: Network, probe: ProxyCore.CfProbe) {
        fun word(t: CfTransport) = when {
            probe.worksFully(t) -> "works"
            probe.works(t) -> "answers, but cut off after a few KB"
            else -> "blocked"
        }
        ProxyCore.note(
            "CF proxy check over ${describeNetwork(on)}: TLS ${word(CfTransport.TLS)}, " +
                "TLS with the bypass ${word(CfTransport.BYPASS)}, without TLS ${word(CfTransport.PLAIN)}"
        )
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
        val back = networkLost
        ProxyCore.note(
            if (back) "Network is back (${describeNetwork(n)})"
            else "Network changed to ${describeNetwork(n)}"
        )
        networkLost = false
        // Networks flap while switching: Wi-Fi reconnected 3 times in 20 s once, and
        // weak Wi-Fi swapped with mobile data every ~12 s on 16 Nov. A return after a
        // loss restarts soon; a switch waits longer, and is dropped if the phone is
        // back on the network the core runs on by then.
        pendingRestart?.cancel()
        pendingRestart = scope.launch {
            delay(if (back) NETWORK_BACK_SETTLE_MS else NETWORK_SWITCH_SETTLE_MS)
            val now = network
            if (now == coreNetwork && !lostSinceStart) {
                ProxyCore.note("Back on ${describeNetwork(now)}, where the proxy core runs; not restarting")
                return@launch
            }
            ProxyCore.note("Restarting the proxy core on ${describeNetwork(now)}")
            autoRestartAt = NEVER // a new network gets its own watchdog restart
            restartSession()
        }
    }

    private fun onNetworkLost(n: Network) {
        if (n != network) return
        network = null
        networkLost = true
        lostSinceStart = true
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
        private const val NETWORK_BACK_SETTLE_MS = 3_000L
        private const val NETWORK_SWITCH_SETTLE_MS = 15_000L
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

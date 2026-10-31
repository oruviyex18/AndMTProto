package io.github.oruviyex18.andmtproto

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

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
        delay(STARTUP_POLL_MS)
        var shown: ProxyStatus? = null
        while (true) {
            val status = ProxyCore.poll()
            if (!status.running) {
                stopSelf()  // bind error or crash; the error stays in ProxyCore.state
                return
            }
            if (status.active != shown?.active || status.listening != shown.listening) {
                notify(buildNotification(status))
                shown = status
            }
            delay(POLL_MS)
        }
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

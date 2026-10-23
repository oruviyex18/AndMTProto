package io.github.oruviyex18.andmtproto

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Foreground service that owns the Python proxy thread. */
class ProxyService : Service() {

    private lateinit var worker: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var settings = ProxySettings()
    private var lastNotified: ProxyStatus? = null
    private var destroyed = false

    private val poll = object : Runnable {
        override fun run() {
            if (destroyed) return
            worker.execute {
                val st = ProxyCore.status()
                main.post {
                    if (destroyed) return@post
                    _status.value = st
                    if (!st.running) {
                        // Proxy thread exited (bind error, crash): leave the error for the UI.
                        stopSelf()
                        return@post
                    }
                    if (st.active != lastNotified?.active || lastNotified?.running != true) {
                        notify(buildNotification(st))
                        lastNotified = st
                    }
                    main.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = Executors.newSingleThreadExecutor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        settings = SettingsStore(this).load()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(null),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (e: Exception) {
            // E.g. a sticky restart that the system no longer allows to go foreground.
            Log.w(TAG, "Cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val restart = intent?.action == ACTION_RESTART
        val current = settings
        main.removeCallbacks(poll)
        worker.execute {
            if (restart) ProxyCore.stop()
            val error = ProxyCore.start(this, current)
            main.post {
                if (destroyed) return@post
                if (error != null) {
                    _status.value = ProxyStatus(error = error)
                    stopSelf()
                } else {
                    lastNotified = null
                    main.postDelayed(poll, STARTUP_POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(poll)
        worker.execute {
            ProxyCore.stop()
            val st = ProxyCore.status()
            main.post { _status.value = st }
        }
        worker.shutdown()
        super.onDestroy()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(st: ProxyStatus?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (st?.running == true) {
            getString(R.string.notif_running, settings.host, settings.port, st.active)
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

        val link = runCatching { ProxyCore.link(settings) }.getOrNull()
        if (link != null) {
            val tg = PendingIntent.getActivity(
                this, 2, Intent(Intent.ACTION_VIEW, Uri.parse(link)), PendingIntent.FLAG_IMMUTABLE
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
        const val ACTION_START = "start"
        const val ACTION_RESTART = "restart"
        const val ACTION_STOP = "stop"

        private val _status = MutableStateFlow(ProxyStatus())
        val status: StateFlow<ProxyStatus> = _status.asStateFlow()

        fun start(context: Context, restart: Boolean = false) {
            val intent = Intent(context, ProxyService::class.java)
                .setAction(if (restart) ACTION_RESTART else ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyService::class.java))
        }

        /** Refreshes [status] from Python, e.g. when the UI opens while the service is idle. */
        fun refresh() {
            Thread { _status.value = ProxyCore.status() }.start()
        }
    }
}

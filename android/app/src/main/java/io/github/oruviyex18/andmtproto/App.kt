package io.github.oruviyex18.andmtproto

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))
        // Versions before android.5 kept the boot receiver always enabled; after an
        // update it stays off until the app runs once, so re-apply the setting here.
        val settings = SettingsStore(this).load()
        BootReceiver.setEnabled(this, settings.startOnBoot)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ))
            manager.createNotificationChannel(NotificationChannel(
                UPDATES_CHANNEL_ID, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT
            ))
        }
        UpdateCheck.schedule(this, settings.checkUpdates)
    }

    companion object {
        const val CHANNEL_ID = "proxy"
        const val UPDATES_CHANNEL_ID = "updates"
    }
}

package io.github.oruviyex18.andmtproto

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/** Starts the proxy after boot when "Start on boot" is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SettingsStore(context).load().startOnBoot) return
        try {
            // specialUse foreground services may be started from BOOT_COMPLETED.
            ProxyService.start(context)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Cannot start proxy after boot", e)
        }
    }

    companion object {
        /**
         * The receiver is disabled in the manifest, so the app doesn't wake up at boot
         * unless the user asked for it; [SettingsStore.save] keeps it in step.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java), state, PackageManager.DONT_KILL_APP
            )
        }
    }
}

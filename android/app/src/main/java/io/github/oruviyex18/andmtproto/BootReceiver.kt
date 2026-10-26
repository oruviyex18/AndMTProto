package io.github.oruviyex18.andmtproto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
}

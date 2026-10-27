package io.github.oruviyex18.andmtproto

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile that shows and toggles the proxy. */
class ProxyTileService : TileService() {

    private var listening: Job? = null

    override fun onStartListening() {
        listening = CoroutineScope(Dispatchers.Main).launch {
            ProxyCore.state.collect { update(it) }
        }
        ProxyCore.refresh()
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        if (ProxyCore.state.value.running) {
            ProxyService.stop(this)
            return
        }
        try {
            ProxyService.start(this)
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a foreground-service start from here;
            // the activity is allowed to, so let it start the proxy.
            Log.i(TAG, "Starting proxy via activity", e)
            openAppAndStart()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppAndStart() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(status: ProxyStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            val settings = SettingsStore(this).load()
            tile.subtitle = if (status.running) "${settings.host}:${settings.port}" else null
        }
        tile.updateTile()
    }

    private companion object {
        const val TAG = "ProxyTileService"
    }
}

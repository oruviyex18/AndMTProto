package io.github.oruviyex18.andmtproto.ui

import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.oruviyex18.andmtproto.R

/** Asks to exempt the app from battery optimisation until it is. */
@Composable
fun BatteryHint() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    // Re-check when the user comes back from the system settings screen.
    LifecycleResumeEffect(Unit) {
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { context.openBatteryOptimizationSettings() }) {
        Text(stringResource(R.string.battery_button))
    }
}

/**
 * MIUI / HyperOS stop background apps on top of Android's battery optimisation,
 * and their Autostart setting can't be read, so the hint stays on Xiaomi devices.
 */
@Composable
fun MiuiHint() {
    if (!isXiaomiDevice) return
    val context = LocalContext.current
    Text(stringResource(R.string.miui_hint), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { context.openMiuiAutostart() }) {
        Text(stringResource(R.string.miui_button))
    }
}

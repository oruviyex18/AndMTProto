package io.github.oruviyex18.andmtproto.ui

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.oruviyex18.andmtproto.R

/**
 * Asks to exempt the app from battery optimisation until it is. Not on Xiaomi:
 * MIUI replaces the system list with its own screen, which never grants the
 * Android exemption, so the card would never go away; [MiuiCard] covers it there.
 */
@Composable
fun BatteryCard() {
    if (isXiaomiDevice) return
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    // Re-check when the user comes back from the system settings screen.
    LifecycleResumeEffect(Unit) {
        exempt = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (exempt) return
    AttentionCard(
        title = stringResource(R.string.card_background_title),
        body = stringResource(R.string.battery_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        TextButton(onClick = { context.openBatteryOptimizationSettings() }) {
            Text(stringResource(R.string.battery_button))
        }
    }
}

/**
 * MIUI / HyperOS stop background apps on their own, and neither their activity
 * control nor Autostart setting can be read, so the user closes this card with
 * [onDone] once both are set. The battery button opens MIUI's activity control.
 */
@Composable
fun MiuiCard(onDone: () -> Unit) {
    if (!isXiaomiDevice) return
    val context = LocalContext.current
    AttentionCard(
        title = stringResource(R.string.card_background_title),
        body = stringResource(R.string.miui_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        actionsBelow = true,
    ) {
        TextButton(onClick = { context.openBatteryOptimizationSettings() }) {
            Text(stringResource(R.string.battery_button))
        }
        TextButton(onClick = { context.openMiuiAutostart() }) {
            Text(stringResource(R.string.miui_button))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.card_done)) }
        }
    }
}

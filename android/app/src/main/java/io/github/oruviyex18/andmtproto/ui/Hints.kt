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
 * Asks to exempt the app from battery optimisation until it is. On Xiaomi the
 * button opens MIUI / HyperOS's own list, where "No restrictions" in the app's
 * background activity control grants this same Android exemption (checked on
 * HyperOS 3: the default mode and "Restrict" both take it away again).
 */
@Composable
fun BatteryCard() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val exempt = rememberOnResume { pm.isIgnoringBatteryOptimizations(context.packageName) }
    if (exempt) return
    AttentionCard(
        title = stringResource(R.string.card_background_title),
        body = stringResource(if (isXiaomiDevice) R.string.miui_background_hint else R.string.battery_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        TextButton(onClick = { context.openBatteryOptimizationSettings() }) {
            Text(stringResource(R.string.battery_button))
        }
    }
}

/**
 * MIUI / HyperOS's Autostart switch, read again on every return to the app and
 * shown only while it is off. Where the ROM doesn't let us read it, the user
 * closes the card with [onDone] instead ([confirmed]).
 */
@Composable
fun MiuiAutostartCard(confirmed: Boolean, onDone: () -> Unit) {
    if (!isXiaomiDevice) return
    val context = LocalContext.current
    val enabled = rememberOnResume { context.miuiAutostartEnabled() }
    if (enabled == true || (enabled == null && confirmed)) return
    AttentionCard(
        title = stringResource(R.string.card_autostart_title),
        body = stringResource(if (enabled == null) R.string.miui_autostart_unknown else R.string.miui_autostart_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
        actionsBelow = true,
    ) {
        TextButton(onClick = { context.openMiuiAutostart() }) {
            Text(stringResource(R.string.miui_button))
        }
        if (enabled == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.card_done)) }
            }
        }
    }
}

/** [read] now and again each time the screen resumes, e.g. back from system settings. */
@Composable
private fun <T> rememberOnResume(read: () -> T): T {
    var value by remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        value = read()
        onPauseOrDispose { }
    }
    return value
}

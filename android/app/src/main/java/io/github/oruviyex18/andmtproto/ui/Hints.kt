package io.github.oruviyex18.andmtproto.ui

import android.os.PowerManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.oruviyex18.andmtproto.R
import kotlinx.coroutines.delay

/**
 * Asks to exempt the app from battery optimisation until it is. On MIUI / HyperOS the
 * button opens MIUI's own list, where "No restrictions" in the app's background
 * activity control grants this same Android exemption (checked on MIUI 14 and
 * HyperOS 3; every other mode takes it away again). Other MIUI builds may not do
 * that, so there the user can also close the card with [onDismiss].
 */
@Composable
fun BatteryCard(dismissed: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    val exempt = rememberOnResume { pm.isIgnoringBatteryOptimizations(context.packageName) }
    if (exempt || (isMiui && dismissed)) return
    AttentionCard(
        title = stringResource(R.string.card_background_title),
        body = stringResource(if (isMiui) R.string.miui_background_hint else R.string.battery_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        if (isMiui) TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_done)) }
        TextButton(onClick = { context.openBatteryOptimizationSettings() }) {
            Text(stringResource(R.string.battery_button))
        }
    }
}

/**
 * MIUI / HyperOS's Autostart switch, shown only while it reads as off or can't be
 * read. [onDismiss] closes it for good, e.g. where the read doesn't follow the switch
 * or the user keeps Autostart off on purpose.
 */
@Composable
fun MiuiAutostartCard(dismissed: Boolean, onDismiss: () -> Unit) {
    if (!isMiui) return
    val context = LocalContext.current
    val enabled = rememberOnResume { context.miuiAutostartEnabled() }
    if (enabled == true || dismissed) return
    AttentionCard(
        title = stringResource(R.string.card_autostart_title),
        body = stringResource(if (enabled == null) R.string.miui_autostart_unknown else R.string.miui_autostart_hint),
        container = MaterialTheme.colorScheme.tertiaryContainer,
        content = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.card_done)) }
        TextButton(onClick = { context.openMiuiAutostart() }) {
            Text(stringResource(R.string.miui_button))
        }
    }
}

/** Reads after each resume: MIUI applies a background-activity choice about 1 s after its screen closes. */
private const val RECHECKS = 4
private const val RECHECK_INTERVAL_MS = 1_000L

/**
 * [read] now, then again on every resume (e.g. back from system settings) and a few
 * times in the seconds after it, so a setting the system applies late still shows.
 */
@Composable
private fun <T> rememberOnResume(read: () -> T): T {
    val currentRead by rememberUpdatedState(read)
    var value by remember { mutableStateOf(read()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        var fresh = true // read during composition a moment ago
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            repeat(RECHECKS) { i ->
                if (i > 0) delay(RECHECK_INTERVAL_MS)
                if (!fresh) value = currentRead()
                fresh = false
            }
        }
    }
    return value
}

package io.github.oruviyex18.andmtproto.ui

import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.ProxyError
import io.github.oruviyex18.andmtproto.R
import kotlinx.coroutines.delay

/**
 * A card that appears only while something needs doing. Actions go in a row at the
 * end, or stacked full width with [actionsBelow].
 */
@Composable
internal fun AttentionCard(
    title: String,
    body: String,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actionsBelow: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (actionsBelow) {
                Spacer(Modifier.height(4.dp))
                actions()
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
            }
        }
    }
}

/** The start error, with "Use port N" when the core found a free port. */
@Composable
internal fun ErrorCard(error: ProxyError, running: Boolean, onUsePort: (Int) -> Unit) {
    val context = LocalContext.current
    AttentionCard(
        title = stringResource(R.string.card_error_title),
        body = error.message(context),
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        val port = error.suggestedPort
        if (port != null && !running) {
            TextButton(onClick = { onUsePort(port) }) { Text(stringResource(R.string.use_port, port)) }
        }
    }
}

/**
 * Telegram keeps connecting but nothing gets through even after the automatic
 * restart: the network itself is the likely cause (e.g. a stuck mobile-data
 * connection, or one that blocks both Telegram and the shared CF domains).
 */
@Composable
internal fun StalledCard(onRestart: () -> Unit) {
    AttentionCard(
        title = stringResource(R.string.card_stalled_title),
        body = stringResource(R.string.card_stalled_body),
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        TextButton(onClick = onRestart) { Text(stringResource(R.string.restart_proxy)) }
    }
}

/**
 * "Choose automatically" found that Telegram answers through the CF proxy, but
 * the network cuts every connection to it off after about 16 KB.
 */
@Composable
internal fun CfCutOffCard() {
    AttentionCard(
        title = stringResource(R.string.card_cf_cut_off_title),
        body = stringResource(R.string.card_cf_cut_off_body),
        container = MaterialTheme.colorScheme.errorContainer,
        content = MaterialTheme.colorScheme.onErrorContainer,
    )
}

/** "Choose automatically" found no way to reach the CF proxy on this network. */
@Composable
internal fun CfNothingWorksCard() {
    AttentionCard(
        title = stringResource(R.string.card_cf_none_title),
        body = stringResource(R.string.card_cf_none_body),
    )
}

/** What the Telegram card shows; see [telegramCardState]. */
internal enum class TelegramCardState { CONNECT, LINKED, EXTRA_ENTRIES, OLD_KEY }

/**
 * Rejected handshakes in the last 10 minutes before the card warns. Telegram checks a
 * stale entry twice each time its proxy list opens, and every ~5 s with auto-switch on;
 * a client with the right secret is never rejected.
 */
internal const val STALE_PROXY_HINT_MIN_BAD = 2

/**
 * [linked]: Telegram has used the proxy with the current port and secret. Rejected
 * handshakes then mean extra or old entries next to the working one; without it, the
 * entry Telegram uses has an old key (after «Новый» or a reinstall, seen 20 Nov 2026
 * with a single entry), and only re-adding the proxy helps. Once it is re-added, the old
 * entry is still rejected until the user deletes it: EXTRA_ENTRIES, rightly.
 */
internal fun telegramCardState(linked: Boolean, badRecent: Int): TelegramCardState = when {
    badRecent >= STALE_PROXY_HINT_MIN_BAD ->
        if (linked) TelegramCardState.EXTRA_ENTRIES else TelegramCardState.OLD_KEY
    linked -> TelegramCardState.LINKED
    else -> TelegramCardState.CONNECT
}

/**
 * Adding the proxy to Telegram: two big buttons until Telegram has used it with the
 * current port and secret, then a collapsed card with both actions in its menu.
 * Rejected handshakes expand it again: with extra entries its text asks to delete them
 * and restart Telegram; with an old key, to add the proxy again and delete the old
 * entry. The buttons re-add the current entry either way (Telegram's list shows all
 * entries as the same address).
 */
@Composable
internal fun TelegramCard(state: TelegramCardState, onOpen: () -> Unit, onCopy: () -> Unit) {
    if (state == TelegramCardState.LINKED) {
        var menu by remember { mutableStateOf(false) }
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(stringResource(R.string.card_linked_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.card_linked_body), style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_open_telegram)) },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.copy_link)) },
                            onClick = { menu = false; onCopy() },
                        )
                    }
                }
            }
        }
        return
    }
    val (title, body) = when (state) {
        TelegramCardState.OLD_KEY -> R.string.card_old_key_title to R.string.card_old_key_body
        TelegramCardState.EXTRA_ENTRIES -> R.string.card_stale_title to R.string.stale_proxy_hint
        else -> R.string.card_connect_title to R.string.card_connect_body
    }
    val warn = state != TelegramCardState.CONNECT
    AttentionCard(
        title = stringResource(title),
        body = stringResource(body),
        container = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        content = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        actionsBelow = true,
    ) {
        Button(onClick = onOpen, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_telegram)) }
        OutlinedButton(onClick = onCopy, Modifier.fillMaxWidth()) { Text(stringResource(R.string.copy_link)) }
    }
}

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

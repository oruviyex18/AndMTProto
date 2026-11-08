package io.github.oruviyex18.andmtproto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.ProxyService
import io.github.oruviyex18.andmtproto.ProxyStatus
import io.github.oruviyex18.andmtproto.R

/**
 * Rejected handshakes in the last 10 minutes before the stale-proxy hint shows. Telegram
 * checks a stale entry twice each time its proxy list opens, and every ~5 s with
 * auto-switch on; a client with the right secret is never rejected.
 */
private const val STALE_PROXY_HINT_MIN_BAD = 2

/** The two screens: everyday status on Main, set-once options in Settings. */
@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    var inSettings by rememberSaveable { mutableStateOf(false) }
    if (inSettings) {
        SettingsScreen(vm, onBack = { inSettings = false })
    } else {
        MainScreen(vm, onOpenSettings = { inSettings = true })
    }
}

private enum class ProxyState { STOPPED, STARTING, RUNNING }

private val ProxyStatus.state
    get() = when {
        !running -> ProxyState.STOPPED
        listening -> ProxyState.RUNNING
        else -> ProxyState.STARTING
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val status by vm.status.collectAsStateWithLifecycle()
    val linked by vm.telegramLinked.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val miuiBackgroundDone by vm.miuiBackgroundDone.collectAsStateWithLifecycle()
    val miuiAutostartDone by vm.miuiAutostartDone.collectAsStateWithLifecycle()
    val startProxy = rememberProxyStarter()
    var logShown by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    TextButton(onClick = { logShown = true }) { Text(stringResource(R.string.logs)) }
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ConnectControl(
                status.state,
                onClick = { if (status.running) ProxyService.stop(context) else startProxy() },
            )
            StatusLine(status, vm.savedAddress())
            if (status.state == ProxyState.RUNNING) {
                RouteChips(status)
                StatTiles(status)
            }
            status.error?.let { error ->
                AttentionCard(
                    title = stringResource(R.string.card_error_title),
                    body = error.message(context),
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    val port = error.suggestedPort
                    if (port != null && !status.running) {
                        TextButton(onClick = {
                            vm.usePort(port)
                            startProxy()
                        }) { Text(stringResource(R.string.use_port, port)) }
                    }
                }
            }
            if (status.state == ProxyState.RUNNING) {
                TelegramCard(
                    linked = linked,
                    stale = status.badRecent >= STALE_PROXY_HINT_MIN_BAD,
                    onOpen = { vm.link()?.let(context::openInTelegram) },
                    onCopy = { vm.link()?.let(context::copyToClipboard) },
                )
            }
            BatteryCard(dismissed = miuiBackgroundDone, onDismiss = vm::markMiuiBackgroundDone)
            MiuiAutostartCard(dismissed = miuiAutostartDone, onDismiss = vm::markMiuiAutostartDone)
            update?.let { u ->
                val releasesUrl = stringResource(R.string.latest_release_url)
                AttentionCard(
                    title = stringResource(R.string.update_available_title, u.version),
                    body = stringResource(R.string.card_update_body, BuildConfig.VERSION_NAME),
                ) {
                    TextButton(onClick = vm::dismissUpdate) { Text(stringResource(R.string.card_later)) }
                    TextButton(onClick = {
                        context.openUrl(u.url.ifBlank { releasesUrl })
                    }) { Text(stringResource(R.string.card_open)) }
                }
            }
            VersionFooter(status.coreVersion)
        }
    }
    if (logShown) LogSheet(onDismiss = { logShown = false })
}

/** Starts the proxy, asking for the notification permission first on Android 13+. */
@Composable
private fun rememberProxyStarter(): () -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ProxyService.start(context)  // the proxy works without the notification too
    }
    return remember(permission) {
        {
            val needsPermission = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ProxyService.start(context)
            }
        }
    }
}

/**
 * The big start / stop control. Its shape morphs from a circle (stopped, running)
 * to a rounded square while the core prepares, so the three states differ by
 * shape and colour, not by colour alone.
 */
@Composable
private fun ConnectControl(state: ProxyState, onClick: () -> Unit) {
    val good = StateColors.good
    val container by animateColorAsState(
        when (state) {
            ProxyState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
            ProxyState.STARTING -> MaterialTheme.colorScheme.primaryContainer
            ProxyState.RUNNING -> good
        }, label = "container",
    )
    val iconColor by animateColorAsState(
        when (state) {
            ProxyState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            ProxyState.STARTING -> MaterialTheme.colorScheme.onPrimaryContainer
            ProxyState.RUNNING -> StateColors.onGood
        }, label = "icon",
    )
    val corner by animateIntAsState(if (state == ProxyState.STARTING) 30 else 50, label = "corner")
    val description = stringResource(R.string.connect_control)
    val stateText = stringResource(
        when (state) {
            ProxyState.STOPPED -> R.string.status_stopped
            ProxyState.STARTING -> R.string.notif_starting
            ProxyState.RUNNING -> R.string.status_running
        }
    )
    Box(Modifier.padding(top = 16.dp).size(176.dp), contentAlignment = Alignment.Center) {
        if (state == ProxyState.RUNNING) {
            Surface(Modifier.size(176.dp), shape = RoundedCornerShape(50), color = StateColors.goodContainer) {}
        }
        if (state == ProxyState.STARTING) {
            CircularProgressIndicator(Modifier.size(168.dp), strokeWidth = 4.dp)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(148.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = stateText
                    role = Role.Button
                },
            shape = RoundedCornerShape(corner),
            color = container,
            border = if (state == ProxyState.STOPPED) {
                androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            } else null,
        ) {
            Box(contentAlignment = Alignment.Center) { PowerIcon(iconColor) }
        }
    }
}

/** The power symbol: an open circle with a bar, drawn so no icon library is needed. */
@Composable
private fun PowerIcon(color: Color) {
    Canvas(Modifier.size(56.dp)) {
        val stroke = size.minDimension * 0.09f
        val inset = size.minDimension * 0.15f
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color, Offset(size.width / 2, size.height * 0.08f), Offset(size.width / 2, size.height * 0.5f),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun StatusLine(status: ProxyStatus, address: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(
                when (status.state) {
                    ProxyState.STOPPED -> R.string.status_stopped
                    ProxyState.STARTING -> R.string.notif_starting
                    ProxyState.RUNNING -> R.string.status_running
                }
            ),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (status.state == ProxyState.RUNNING) StateColors.good else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (status.state == ProxyState.STOPPED) stringResource(R.string.tap_to_start) else address,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where connections went since start: straight to Telegram, or through Cloudflare. */
@Composable
private fun RouteChips(status: ProxyStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteChip(
            stringResource(R.string.route_ws, status.ws), active = status.ws > 0,
            container = StateColors.goodContainer, content = StateColors.onGoodContainer,
        )
        RouteChip(
            stringResource(R.string.route_cf, status.cf), active = status.cf > 0,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun RouteChip(label: String, active: Boolean, container: Color, content: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) content else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StatTiles(status: ProxyStatus) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(status.active.toString(), stringResource(R.string.stat_active), Modifier.weight(1f))
        StatTile(status.lastHour.toString(), stringResource(R.string.stat_last_hour), Modifier.weight(1f))
        StatTile(
            "↓ " + Formatter.formatShortFileSize(context, status.bytesDown),
            stringResource(R.string.stat_traffic, Formatter.formatShortFileSize(context, status.bytesUp)),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Adding the proxy to Telegram: two big buttons until Telegram has used it with the
 * current port and secret, then a collapsed card with both actions in its menu. The
 * stale-proxy hint expands it again: its text asks to delete the extra entries and
 * restart Telegram, and the buttons re-add the current entry in case the one kept
 * was an old one (Telegram's list shows them all as the same address).
 */
@Composable
private fun TelegramCard(linked: Boolean, stale: Boolean, onOpen: () -> Unit, onCopy: () -> Unit) {
    if (linked && !stale) {
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
    AttentionCard(
        title = stringResource(if (stale) R.string.card_stale_title else R.string.card_connect_title),
        body = stringResource(if (stale) R.string.stale_proxy_hint else R.string.card_connect_body),
        container = if (stale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        content = if (stale) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        actionsBelow = true,
    ) {
        Button(onClick = onOpen, Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_open_telegram)) }
        OutlinedButton(onClick = onCopy, Modifier.fillMaxWidth()) { Text(stringResource(R.string.copy_link)) }
    }
}

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

@Composable
private fun VersionFooter(coreVersion: String) {
    val context = LocalContext.current
    val releasesUrl = stringResource(R.string.latest_release_url)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (coreVersion.isNotEmpty()) {
            Text(
                stringResource(R.string.core_version, coreVersion, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The release page shows the newest APK and its notes; the user compares versions there.
        TextButton(onClick = { context.openUrl(releasesUrl) }) { Text(stringResource(R.string.check_updates)) }
    }
}

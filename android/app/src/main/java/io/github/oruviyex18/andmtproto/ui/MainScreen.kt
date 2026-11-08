package io.github.oruviyex18.andmtproto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.ProxyService
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
                ErrorCard(error, status.running, onUsePort = { port ->
                    vm.usePort(port)
                    startProxy()
                })
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
            update?.let { UpdateCard(it, onLater = vm::dismissUpdate) }
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

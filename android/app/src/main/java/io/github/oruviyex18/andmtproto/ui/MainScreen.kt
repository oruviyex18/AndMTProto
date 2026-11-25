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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oruviyex18.andmtproto.AppUpdater
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.ProxyService
import io.github.oruviyex18.andmtproto.R

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
    val stop by vm.stop.collectAsStateWithLifecycle()
    val updater by vm.updater.collectAsStateWithLifecycle()
    val stalled by ProxyCore.stalled.collectAsStateWithLifecycle()
    val cfCheck by ProxyCore.cfCheck.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val miuiBackgroundDone by vm.miuiBackgroundDone.collectAsStateWithLifecycle()
    val miuiAutostartDone by vm.miuiAutostartDone.collectAsStateWithLifecycle()
    val oemAutostartDone by vm.oemAutostartDone.collectAsStateWithLifecycle()
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
            if (status.state == ProxyState.RUNNING) CfConnectionLine(saved)
            if (status.state == ProxyState.RUNNING) CfTroubleLine(status)
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
            stop?.let { StoppedCard(it, running = status.running, onDismiss = vm::dismissStop) }
            if (status.state == ProxyState.RUNNING && stalled) {
                StalledCard(onRestart = { ProxyService.start(context, restart = true) })
            }
            if (status.state == ProxyState.RUNNING && saved.cfproxy && saved.cfAuto) {
                cfCheck?.let { check ->
                    if (check.chosen == null) CfNothingWorksCard() else if (check.cutOff) CfCutOffCard()
                }
            }
            if (status.state == ProxyState.RUNNING) {
                TelegramCard(
                    state = telegramCardState(linked, status.badRecent),
                    onOpen = { vm.link()?.let(context::openInTelegram) },
                    onCopy = { vm.link()?.let(context::copyToClipboard) },
                )
            }
            BatteryCard(dismissed = miuiBackgroundDone, onDismiss = vm::markMiuiBackgroundDone)
            MiuiAutostartCard(dismissed = miuiAutostartDone, onDismiss = vm::markMiuiAutostartDone)
            OemAutostartCard(dismissed = oemAutostartDone, onDismiss = vm::markOemAutostartDone)
            UpdateSection(
                state = updater,
                available = update,
                onUpdate = vm::startUpdate,
                onLater = vm::dismissUpdate,
                onClose = vm::closeUpdate,
            )
            VersionFooter(status.coreVersion, updating = updater is AppUpdater.State.Checking,
                onUpdate = vm::startUpdate)
        }
    }
    if (logShown) LogSheet(onDismiss = { logShown = false })

    (updater as? AppUpdater.State.UpToDate)?.let { upToDate ->
        LaunchedEffect(upToDate) {
            context.toast(upToDateText(context, upToDate))
            vm.closeUpdate()
        }
    }
    // Back from the update notification: show the release it found.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.onScreenResumed() }
    }
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
private fun VersionFooter(coreVersion: String, updating: Boolean, onUpdate: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (coreVersion.isNotEmpty()) {
            Text(
                stringResource(R.string.core_version, coreVersion, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Checks GitHub; a newer release shows the update card (AppUpdater).
        TextButton(onClick = onUpdate, enabled = !updating) { Text(stringResource(R.string.check_updates)) }
        // The project's releases on GitHub: all versions and their notes.
        val context = LocalContext.current
        val projectUrl = stringResource(R.string.project_url)
        TextButton(onClick = { context.openUrl(projectUrl) }) { Text(stringResource(R.string.open_project)) }
    }
}

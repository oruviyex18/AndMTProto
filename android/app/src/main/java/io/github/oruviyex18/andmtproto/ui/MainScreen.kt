package io.github.oruviyex18.andmtproto.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.ProxyService
import io.github.oruviyex18.andmtproto.ProxyStatus
import io.github.oruviyex18.andmtproto.R

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val status by vm.status.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val startProxy = rememberProxyStarter()

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(
            status,
            onStart = startProxy,
            onStop = { ProxyService.stop(context) },
            onUsePort = { port ->
                vm.usePort(port)
                startProxy()
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.link()?.let(context::openInTelegram) }) {
                Text(stringResource(R.string.action_open_telegram))
            }
            OutlinedButton(onClick = { vm.link()?.let(context::copyToClipboard) }) {
                Text(stringResource(R.string.copy_link))
            }
        }

        BatteryHint()
        MiuiHint()

        HorizontalDivider()
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
        SettingsForm(draft, onChange = vm::edit)

        Button(
            onClick = {
                val badField = vm.save()
                if (badField != null) {
                    context.toast(resources.getString(R.string.invalid_number, resources.getString(badField)))
                } else {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    context.toast(R.string.saved)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (status.running) R.string.save_restart else R.string.save)) }

        HorizontalDivider()
        LogsSection()
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
private fun StatusCard(
    status: ProxyStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUsePort: (Int) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (status.running) R.string.status_running else R.string.status_stopped),
                style = MaterialTheme.typography.titleLarge,
            )
            if (status.running) {
                Text(
                    stringResource(
                        R.string.status_stats, status.active, status.total,
                        Formatter.formatShortFileSize(context, status.bytesUp),
                        Formatter.formatShortFileSize(context, status.bytesDown),
                    )
                )
            }
            status.error?.let { error ->
                Text(error.message(context), color = MaterialTheme.colorScheme.error)
                val port = error.suggestedPort
                if (port != null && !status.running) {
                    TextButton(onClick = { onUsePort(port) }) {
                        Text(stringResource(R.string.use_port, port))
                    }
                }
            }
            if (status.coreVersion.isNotEmpty()) {
                Text(
                    stringResource(R.string.core_version, status.coreVersion, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val releasesUrl = stringResource(R.string.latest_release_url)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (status.running) {
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
                } else {
                    Button(onClick = onStart) { Text(stringResource(R.string.start)) }
                }
                Spacer(Modifier.weight(1f))
                // The release page shows the newest APK and its notes; the user compares versions there.
                TextButton(onClick = { context.openUrl(releasesUrl) }) {
                    Text(stringResource(R.string.check_updates))
                }
            }
        }
    }
}

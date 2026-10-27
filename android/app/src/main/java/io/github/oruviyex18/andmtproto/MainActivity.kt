package io.github.oruviyex18.andmtproto

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ProxyService.refresh()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) { MainScreen() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The Quick Settings tile opens the app with [ACTION_START] when it can't start the service itself. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ACTION_START) ProxyService.start(this)
    }

    companion object {
        const val ACTION_START = "io.github.oruviyex18.andmtproto.action.START"
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Text-field state for the settings form; numbers stay strings until saved. */
private data class Form(
    val host: String, val port: String, val secret: String, val dcIp: String,
    val poolSize: String, val bufKb: String, val cfproxy: Boolean, val h2: Boolean,
    val cfUserEnabled: Boolean, val cfUserDomains: String,
    val cfWorkerEnabled: Boolean, val cfWorkerDomains: String,
    val fakeTls: String, val forceTestDc: Boolean, val noSecure: Boolean, val verbose: Boolean,
    val startOnBoot: Boolean,
) {
    constructor(s: ProxySettings) : this(
        s.host, s.port.toString(), s.secret, s.dcIp.joinToString("\n"),
        s.poolSize.toString(), s.bufKb.toString(), s.cfproxy, s.h2,
        s.cfUserDomainEnabled, s.cfUserDomains, s.cfWorkerEnabled, s.cfWorkerDomains,
        s.fakeTlsDomain, s.forceTestDc, s.noSecure, s.verbose, s.startOnBoot,
    )
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var saved by remember { mutableStateOf(store.load()) }
    var form by remember { mutableStateOf(Form(saved)) }
    val status by ProxyService.status.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ProxyService.start(context) }

    fun startProxy() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            ProxyService.start(context)
        }
    }

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
            onStart = ::startProxy,
            onStop = { ProxyService.stop(context) },
            onUsePort = { port ->
                val updated = saved.copy(port = port)
                store.save(updated)
                saved = updated
                form = form.copy(port = port.toString())
                startProxy()
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openInTelegram(context, ProxyCore.link(saved)) }) {
                Text(stringResource(R.string.action_open_telegram))
            }
            OutlinedButton(onClick = { copyLink(context, ProxyCore.link(saved)) }) {
                Text(stringResource(R.string.copy_link))
            }
        }

        BatteryHint()

        HorizontalDivider()
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)
        SettingsForm(form, onChange = { form = it })

        val saveLabel = stringResource(if (status.running) R.string.save_restart else R.string.save)
        Button(
            onClick = save@{
                val parsed = parseForm(context, form) ?: return@save
                focusManager.clearFocus()
                keyboard?.hide()
                store.save(parsed)
                saved = parsed
                if (status.running) ProxyService.start(context, restart = true)
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(saveLabel) }

        HorizontalDivider()
        LogsSection()
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
                Text(errorText(context, error), color = MaterialTheme.colorScheme.error)
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
            if (status.running) {
                OutlinedButton(onClick = onStop) { Text(stringResource(R.string.stop)) }
            } else {
                Button(onClick = onStart) { Text(stringResource(R.string.start)) }
            }
        }
    }
}

/**
 * The shared CF proxy domains are often overloaded, so DCs without a direct
 * WebSocket route (1, 3, 5) time out. Point to upstream's setup guides.
 */
@Composable
private fun CfHint() {
    val context = LocalContext.current
    Text(stringResource(R.string.cf_hint), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = { openUrl(context, context.getString(R.string.cf_worker_guide_url)) }) {
            Text(stringResource(R.string.cf_worker_guide))
        }
        TextButton(onClick = { openUrl(context, context.getString(R.string.cf_proxy_guide_url)) }) {
            Text(stringResource(R.string.cf_proxy_guide))
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        copyLink(context, url)
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryHint() {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var ignoring by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    // Re-check when the user comes back from the system settings screen.
    LifecycleResumeEffect(Unit) {
        ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (!ignoring) {
        Text(stringResource(R.string.battery_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        }) { Text(stringResource(R.string.battery_button)) }
    }
    // MIUI / HyperOS stop background apps on top of Android's battery optimisation,
    // and their settings can't be read, so the hint stays visible on Xiaomi devices.
    if (isXiaomi()) {
        Text(stringResource(R.string.miui_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { openMiuiAutostart(context) }) {
            Text(stringResource(R.string.miui_button))
        }
    }
}

private fun isXiaomi(): Boolean =
    listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("Xiaomi", true) || it.equals("Redmi", true) || it.equals("POCO", true)
    }

/** MIUI's Autostart screen, or the app's system settings page where it doesn't exist. */
private fun openMiuiAutostart(context: Context) {
    val autostart = Intent().setComponent(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
    )
    try {
        context.startActivity(autostart)
    } catch (_: Exception) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
}

/** User-facing text for an error from the Python core. */
private fun errorText(context: Context, e: ProxyError): String = when (e.code) {
    "port_in_use" -> context.getString(R.string.error_port_in_use, e.detail.orEmpty())
    "permission" -> context.getString(R.string.error_permission, e.detail.orEmpty())
    "address" -> context.getString(R.string.error_address, e.detail.orEmpty())
    "bad_secret" -> context.getString(R.string.error_bad_secret)
    "bad_port" -> context.getString(R.string.error_bad_port, e.detail.orEmpty())
    "bad_dc_ip" -> context.getString(R.string.error_bad_dc_ip, e.detail.orEmpty())
    else -> context.getString(R.string.error_unknown, e.detail ?: e.code)
}

@Composable
private fun SettingsForm(f: Form, onChange: (Form) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(f.host, R.string.host, Modifier.weight(2f)) { onChange(f.copy(host = it)) }
        Spacer(Modifier.width(8.dp))
        Field(f.port, R.string.port, Modifier.weight(1f), number = true) { onChange(f.copy(port = it)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(f.secret, R.string.secret, Modifier.weight(1f)) { onChange(f.copy(secret = it.trim())) }
        TextButton(onClick = { onChange(f.copy(secret = ProxySettings.newSecret())) }) {
            Text(stringResource(R.string.new_secret))
        }
    }
    OutlinedTextField(
        value = f.dcIp, onValueChange = { onChange(f.copy(dcIp = it)) },
        label = { Text(stringResource(R.string.dc_ip)) },
        supportingText = { Text(stringResource(R.string.dc_ip_hint)) },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Row {
        Field(f.poolSize, R.string.pool_size, Modifier.weight(1f), number = true) { onChange(f.copy(poolSize = it)) }
        Spacer(Modifier.width(8.dp))
        Field(f.bufKb, R.string.buf_kb, Modifier.weight(1f), number = true) { onChange(f.copy(bufKb = it)) }
    }
    Toggle(f.cfproxy, R.string.cfproxy) { onChange(f.copy(cfproxy = it)) }
    Toggle(f.h2, R.string.h2) { onChange(f.copy(h2 = it)) }
    Toggle(f.cfUserEnabled, R.string.cf_user_enabled) { onChange(f.copy(cfUserEnabled = it)) }
    if (f.cfUserEnabled) {
        Field(f.cfUserDomains, R.string.cf_user_domains) { onChange(f.copy(cfUserDomains = it)) }
    }
    Toggle(f.cfWorkerEnabled, R.string.cf_worker_enabled) { onChange(f.copy(cfWorkerEnabled = it)) }
    if (f.cfWorkerEnabled) {
        Field(f.cfWorkerDomains, R.string.cf_worker_domains) { onChange(f.copy(cfWorkerDomains = it)) }
    }
    if (f.cfproxy && !f.cfUserEnabled && !f.cfWorkerEnabled) CfHint()
    Field(f.fakeTls, R.string.fake_tls) { onChange(f.copy(fakeTls = it.trim())) }
    Toggle(f.noSecure, R.string.no_secure) { onChange(f.copy(noSecure = it)) }
    Toggle(f.forceTestDc, R.string.force_test_dc) { onChange(f.copy(forceTestDc = it)) }
    Toggle(f.verbose, R.string.verbose) { onChange(f.copy(verbose = it)) }
    Toggle(f.startOnBoot, R.string.start_on_boot) { onChange(f.copy(startOnBoot = it)) }
}

@Composable
private fun Field(
    value: String, label: Int, modifier: Modifier = Modifier.fillMaxWidth(),
    number: Boolean = false, onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { Text(stringResource(label)) }, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
private fun Toggle(checked: Boolean, label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LogsSection() {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(shown, tick) {
        if (shown) text = withContext(Dispatchers.IO) { ProxyCore.readLogTail(context) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.logs), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (shown) TextButton(onClick = { tick++ }) { Text(stringResource(R.string.refresh)) }
        Switch(checked = shown, onCheckedChange = { shown = it })
    }
    if (shown) {
        Text(
            text.ifEmpty { stringResource(R.string.logs_empty) },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
        )
    }
}

private fun parseForm(context: Context, f: Form): ProxySettings? {
    fun num(value: String, label: Int): Int? = value.trim().toIntOrNull().also {
        if (it == null) {
            val msg = context.getString(R.string.invalid_number, context.getString(label))
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
    return ProxySettings(
        host = f.host.trim(),
        port = num(f.port, R.string.port) ?: return null,
        secret = f.secret,
        dcIp = f.dcIp.lines().map { it.trim() }.filter { it.isNotEmpty() },
        poolSize = num(f.poolSize, R.string.pool_size) ?: return null,
        bufKb = num(f.bufKb, R.string.buf_kb) ?: return null,
        cfproxy = f.cfproxy, h2 = f.h2,
        cfUserDomainEnabled = f.cfUserEnabled, cfUserDomains = f.cfUserDomains,
        cfWorkerEnabled = f.cfWorkerEnabled, cfWorkerDomains = f.cfWorkerDomains,
        fakeTlsDomain = f.fakeTls, forceTestDc = f.forceTestDc,
        noSecure = f.noSecure, verbose = f.verbose, startOnBoot = f.startOnBoot,
    )
}

private fun openInTelegram(context: Context, link: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.telegram_not_found, Toast.LENGTH_LONG).show()
    }
}

private fun copyLink(context: Context, link: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("tg proxy", link))
    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
}

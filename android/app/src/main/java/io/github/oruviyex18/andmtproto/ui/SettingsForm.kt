package io.github.oruviyex18.andmtproto.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.oruviyex18.andmtproto.ProxySettings
import io.github.oruviyex18.andmtproto.R

/**
 * Settings being edited. Fields typed as text that must become numbers or a
 * list stay strings until [parse], so half-typed values don't get lost.
 */
data class SettingsDraft(
    val settings: ProxySettings,
    val port: String = settings.port.toString(),
    val poolSize: String = settings.poolSize.toString(),
    val bufKb: String = settings.bufKb.toString(),
    val dcIp: String = settings.dcIp.joinToString("\n"),
) {
    fun edit(change: ProxySettings.() -> ProxySettings) = copy(settings = settings.change())

    sealed interface Parsed {
        data class Valid(val settings: ProxySettings) : Parsed
        /** [field] is the label of the first field that isn't a whole number. */
        data class NotANumber(@StringRes val field: Int) : Parsed
    }

    fun parse(): Parsed {
        val port = port.trim().toIntOrNull() ?: return Parsed.NotANumber(R.string.port)
        val poolSize = poolSize.trim().toIntOrNull() ?: return Parsed.NotANumber(R.string.pool_size)
        val bufKb = bufKb.trim().toIntOrNull() ?: return Parsed.NotANumber(R.string.buf_kb)
        return Parsed.Valid(
            settings.copy(
                host = settings.host.trim(),
                port = port,
                secret = settings.secret.trim(),
                dcIp = dcIp.lines().map { it.trim() }.filter { it.isNotEmpty() },
                poolSize = poolSize,
                bufKb = bufKb,
                fakeTlsDomain = settings.fakeTlsDomain.trim(),
            )
        )
    }
}

@Composable
fun SettingsForm(draft: SettingsDraft, onChange: (SettingsDraft) -> Unit) {
    val s = draft.settings
    fun edit(change: ProxySettings.() -> ProxySettings) = onChange(draft.edit(change))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(s.host, R.string.host, Modifier.weight(2f)) { v -> edit { copy(host = v) } }
        Spacer(Modifier.width(8.dp))
        Field(draft.port, R.string.port, Modifier.weight(1f), number = true) { onChange(draft.copy(port = it)) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(s.secret, R.string.secret, Modifier.weight(1f)) { v -> edit { copy(secret = v) } }
        TextButton(onClick = { edit { copy(secret = ProxySettings.newSecret()) } }) {
            Text(stringResource(R.string.new_secret))
        }
    }
    OutlinedTextField(
        value = draft.dcIp, onValueChange = { onChange(draft.copy(dcIp = it)) },
        label = { Text(stringResource(R.string.dc_ip)) },
        supportingText = { Text(stringResource(R.string.dc_ip_hint)) },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Row {
        Field(draft.poolSize, R.string.pool_size, Modifier.weight(1f), number = true) {
            onChange(draft.copy(poolSize = it))
        }
        Spacer(Modifier.width(8.dp))
        Field(draft.bufKb, R.string.buf_kb, Modifier.weight(1f), number = true) {
            onChange(draft.copy(bufKb = it))
        }
    }
    Toggle(s.cfproxy, R.string.cfproxy) { v -> edit { copy(cfproxy = v) } }
    Toggle(s.h2, R.string.h2) { v -> edit { copy(h2 = v) } }
    Toggle(s.cfUserDomainEnabled, R.string.cf_user_enabled) { v -> edit { copy(cfUserDomainEnabled = v) } }
    if (s.cfUserDomainEnabled) {
        Field(s.cfUserDomains, R.string.cf_user_domains, Modifier.fillMaxWidth()) { v -> edit { copy(cfUserDomains = v) } }
    }
    Toggle(s.cfWorkerEnabled, R.string.cf_worker_enabled) { v -> edit { copy(cfWorkerEnabled = v) } }
    if (s.cfWorkerEnabled) {
        Field(s.cfWorkerDomains, R.string.cf_worker_domains, Modifier.fillMaxWidth()) { v -> edit { copy(cfWorkerDomains = v) } }
    }
    if (s.cfproxy && !s.cfUserDomainEnabled && !s.cfWorkerEnabled) CfHint()
    Field(s.fakeTlsDomain, R.string.fake_tls, Modifier.fillMaxWidth()) { v -> edit { copy(fakeTlsDomain = v) } }
    Toggle(s.noSecure, R.string.no_secure) { v -> edit { copy(noSecure = v) } }
    Toggle(s.forceTestDc, R.string.force_test_dc) { v -> edit { copy(forceTestDc = v) } }
    Toggle(s.verbose, R.string.verbose) { v -> edit { copy(verbose = v) } }
    Toggle(s.startOnBoot, R.string.start_on_boot) { v -> edit { copy(startOnBoot = v) } }
}

/**
 * The shared CF proxy domains are often overloaded, so DCs without a direct
 * WebSocket route (1, 3, 5) time out. Point to upstream's setup guides.
 */
@Composable
private fun CfHint() {
    val context = LocalContext.current
    val workerGuide = stringResource(R.string.cf_worker_guide_url)
    val domainGuide = stringResource(R.string.cf_proxy_guide_url)
    Text(stringResource(R.string.cf_hint), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(onClick = { context.openUrl(workerGuide) }) {
            Text(stringResource(R.string.cf_worker_guide))
        }
        TextButton(onClick = { context.openUrl(domainGuide) }) {
            Text(stringResource(R.string.cf_proxy_guide))
        }
    }
}

@Composable
private fun Field(
    value: String,
    @StringRes label: Int,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = true,
        label = { Text(stringResource(label)) }, modifier = modifier,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    )
}

@Composable
private fun Toggle(checked: Boolean, @StringRes label: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

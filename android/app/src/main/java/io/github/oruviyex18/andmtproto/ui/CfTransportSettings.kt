package io.github.oruviyex18.andmtproto.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Date
import io.github.oruviyex18.andmtproto.CfTransport
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.ProxySettings
import io.github.oruviyex18.andmtproto.R
import io.github.oruviyex18.andmtproto.TlsStrategies

private data class Choice(@StringRes val title: Int, @StringRes val body: Int)

private val AUTO_CHOICE = Choice(R.string.cf_transport_auto, R.string.cf_transport_auto_body)

private val TRANSPORTS = listOf(
    CfTransport.BYPASS to Choice(R.string.cf_transport_bypass, R.string.cf_transport_bypass_body),
    CfTransport.TLS to Choice(R.string.cf_transport_tls, R.string.cf_transport_tls_body),
    CfTransport.PLAIN to Choice(R.string.cf_transport_plain, R.string.cf_transport_plain_body),
)

/** Same order as [TlsStrategies.PRESETS]. */
private val PRESET_CHOICES = listOf(
    Choice(R.string.tls_strategy_auto, R.string.tls_strategy_auto_body),
    Choice(R.string.tls_strategy_default, R.string.tls_strategy_default_body),
    Choice(R.string.tls_strategy_records_oob, R.string.tls_strategy_records_oob_body),
    Choice(R.string.tls_strategy_oob, R.string.tls_strategy_oob_body),
    Choice(R.string.tls_strategy_parent, R.string.tls_strategy_parent_body),
)
private val CUSTOM_CHOICE = Choice(R.string.tls_strategy_custom, R.string.tls_strategy_custom_body)

/** The name of [t] as the settings list it. */
@StringRes
internal fun cfTransportName(t: CfTransport): Int = TRANSPORTS.first { it.first == t }.second.title

/**
 * How the core connects to the CF proxy: "Choose automatically" (the default),
 * or always TLS with the blocking bypass, plain TLS or no TLS; with the bypass
 * (also when chosen automatically), which strategy, from presets or typed
 * ByeDPI-style.
 */
@Composable
internal fun CfTransportSettings(s: ProxySettings, edit: (ProxySettings.() -> ProxySettings) -> Unit) {
    Text(stringResource(R.string.cf_transport), style = MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        ChoiceRow(AUTO_CHOICE, selected = s.cfAuto, onSelect = { if (!s.cfAuto) edit { withCfAuto() } }) {
            if (s.cfAuto && s.cfproxy) CfAutoStatus(s.cfTransport)
        }
        TRANSPORTS.forEach { (transport, choice) ->
            ChoiceRow(choice, selected = !s.cfAuto && s.cfTransport == transport, onSelect = { edit { withCfTransport(transport) } })
        }
    }
    if (s.cfAuto || s.cfTransport == CfTransport.BYPASS) {
        StrategyPicker(s.tlsStrategy) { v -> edit { copy(tlsStrategy = v) } }
    }
}

/**
 * Under "Choose automatically" while the proxy runs: "Now: TLS with the bypass",
 * then the network and time of the last check, "Checking…" before it, or that no
 * way worked.
 */
@Composable
private fun CfAutoStatus(current: CfTransport) {
    val status by ProxyCore.state.collectAsStateWithLifecycle()
    val check by ProxyCore.cfCheck.collectAsStateWithLifecycle()
    if (!status.running) return
    val c = check
    val now = stringResource(R.string.cf_auto_now, stringResource(cfTransportName(c?.chosen ?: current)))
    val detail = when {
        c == null -> stringResource(R.string.cf_auto_checking)
        else -> stringResource(
            R.string.cf_auto_checked, c.networkLabel,
            DateFormat.getTimeFormat(LocalContext.current).format(Date(c.atMillis)),
        )
    }
    Column(Modifier.padding(top = 6.dp)) {
        Text(
            if (c != null && c.chosen == null) stringResource(R.string.cf_auto_none) else now,
            style = MaterialTheme.typography.bodyMedium,
            color = if (c != null && c.chosen == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(
    choice: Choice,
    selected: Boolean,
    onSelect: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column {
            Text(stringResource(choice.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(choice.body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            extra()
        }
    }
}

@Composable
private fun StrategyPicker(strategy: String, onChange: (String) -> Unit) {
    val preset = TlsStrategies.PRESETS.indexOf(strategy)
    // "Custom" stays open while the typed text happens to equal a preset.
    var custom by rememberSaveable { mutableStateOf(preset < 0) }
    val shown = if (custom || preset < 0) CUSTOM_CHOICE else PRESET_CHOICES[preset]
    var open by remember { mutableStateOf(false) }

    Box {
        OutlinedTextField(
            value = stringResource(shown.title), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(stringResource(R.string.tls_strategy)) },
            trailingIcon = { Text(if (open) "▴" else "▾") },
            supportingText = { Text(stringResource(shown.body)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // A read-only field doesn't take clicks itself; this covers it (not its supporting text).
        Box(Modifier.matchParentSize().padding(bottom = 24.dp).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PRESET_CHOICES.forEachIndexed { i, choice ->
                DropdownMenuItem(
                    text = { Text(stringResource(choice.title)) },
                    onClick = {
                        open = false
                        custom = false
                        onChange(TlsStrategies.PRESETS[i])
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(CUSTOM_CHOICE.title)) },
                onClick = {
                    open = false
                    custom = true
                },
            )
        }
    }
    if (custom || preset < 0) {
        val bad = remember(strategy) { if (strategy.isBlank()) null else ProxyCore.badTlsStrategyPart(strategy) }
        OutlinedTextField(
            value = strategy, onValueChange = onChange, singleLine = false, minLines = 1,
            label = { Text(stringResource(R.string.tls_strategy_field)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            isError = bad != null,
            supportingText = {
                Text(
                    if (bad != null) stringResource(R.string.error_bad_tls_strategy, bad)
                    else stringResource(R.string.tls_strategy_help)
                )
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

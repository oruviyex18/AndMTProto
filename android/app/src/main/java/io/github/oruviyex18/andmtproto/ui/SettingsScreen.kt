package io.github.oruviyex18.andmtproto.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.oruviyex18.andmtproto.R

/**
 * Set-once options. Changes are saved when the user leaves the screen (top bar or
 * system back); a running proxy restarts with them. A field that isn't a number
 * keeps the user here with a message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val draft by vm.draft.collectAsStateWithLifecycle()
    var linkShown by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val leave = {
        focusManager.clearFocus() // hides the keyboard before the screen changes
        when (val result = vm.save()) {
            is MainViewModel.SaveResult.Invalid ->
                context.toast(resources.getString(R.string.invalid_number, resources.getString(result.field)))
            is MainViewModel.SaveResult.Saved -> {
                context.toast(if (result.restarted) R.string.saved_restarted else R.string.saved)
                onBack()
            }
            MainViewModel.SaveResult.Unchanged -> onBack()
        }
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    TextButton(onClick = leave) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Edge-to-edge on Android 15+ ignores adjustResize, so make room for the
                // keyboard here; the focused field then scrolls into view above it.
                .consumeWindowInsets(padding)
                .imePadding()
                // A tap outside the fields closes the keyboard.
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_saved_on_leave),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsForm(draft, onChange = vm::edit, onShowLink = { linkShown = true })
        }
    }
    if (linkShown) LinkSheet(vm, onDismiss = { linkShown = false })
}

/** The Telegram link with both actions; the secret is masked on screen, not in the link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.link_row_title), style = MaterialTheme.typography.titleLarge)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    vm.maskedLink().orEmpty(),
                    Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.link_sheet_body), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.link()?.let(context::openInTelegram) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_open_telegram))
            }
            OutlinedButton(onClick = { vm.link()?.let { context.copyToClipboard(it) } }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.copy_link))
            }
        }
    }
}

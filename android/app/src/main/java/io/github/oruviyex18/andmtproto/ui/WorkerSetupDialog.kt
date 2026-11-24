package io.github.oruviyex18.andmtproto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.oruviyex18.andmtproto.R
import io.github.oruviyex18.andmtproto.WorkerSetup
import kotlinx.coroutines.launch

/**
 * "Create a Worker automatically": the user makes a Cloudflare API token from the
 * "Edit Cloudflare Workers" template and pastes it; [WorkerSetup] creates the Worker
 * and [onCreated] puts its address into the CF Worker setting. The token lives only
 * in this dialog's state: it isn't saved, survives no restart and isn't logged.
 */
@Composable
internal fun WorkerSetupDialog(onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WorkerSetup.Result?>(null) }
    val created = result as? WorkerSetup.Result.Created

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.worker_setup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (created != null) {
                    Text(stringResource(R.string.worker_setup_done, created.domain))
                    created.account?.let { Text(stringResource(R.string.worker_setup_account, it), style = MaterialTheme.typography.bodySmall) }
                    return@Column
                }
                Text(stringResource(R.string.worker_setup_steps), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { context.openUrl(WorkerSetup.TOKENS_PAGE) }) {
                    Text(stringResource(R.string.worker_setup_open_tokens))
                }
                OutlinedTextField(
                    value = token, onValueChange = { token = it; result = null }, singleLine = true,
                    enabled = !working,
                    label = { Text(stringResource(R.string.worker_setup_token)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                (result as? WorkerSetup.Result.Failed)?.let { failed ->
                    Text(
                        stringResource(failureText(failed.error)) + (failed.detail?.let { " ($it)" } ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.worker_setup_working), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (created != null) {
                TextButton(onClick = { onCreated(created.domain); onDismiss() }) { Text(stringResource(R.string.worker_setup_use)) }
            } else {
                TextButton(
                    enabled = !working && token.isNotBlank(),
                    onClick = {
                        if (!WorkerSetup.isWellFormedToken(token)) {
                            result = WorkerSetup.Result.Failed(WorkerSetup.Error.BAD_TOKEN)
                            return@TextButton
                        }
                        working = true
                        scope.launch {
                            result = WorkerSetup.create(WorkerSetup.api(token.trim()), WorkerSetup.workerScript(context))
                            if (result is WorkerSetup.Result.Created) token = ""
                            working = false
                        }
                    },
                ) { Text(stringResource(R.string.worker_setup_create)) }
            }
        },
        dismissButton = {
            if (created == null) {
                TextButton(enabled = !working, onClick = onDismiss) { Text(stringResource(R.string.worker_setup_cancel)) }
            }
        },
    )
}

private fun failureText(error: WorkerSetup.Error): Int = when (error) {
    WorkerSetup.Error.BAD_TOKEN -> R.string.worker_setup_bad_token
    WorkerSetup.Error.NO_ACCOUNT -> R.string.worker_setup_no_account
    WorkerSetup.Error.NO_PERMISSION -> R.string.worker_setup_no_permission
    WorkerSetup.Error.NETWORK -> R.string.worker_setup_network
    WorkerSetup.Error.API -> R.string.worker_setup_api
}

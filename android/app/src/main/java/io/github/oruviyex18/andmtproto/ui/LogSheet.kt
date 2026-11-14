package io.github.oruviyex18.andmtproto.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LogFilter(val label: Int, val levels: Set<String>?) {
    ALL(R.string.log_all, null),
    WARNINGS(R.string.log_warnings, setOf("WARNING", "ERROR")),
    ERRORS(R.string.log_errors, setOf("ERROR")),
}

/** "2026-11-14 12:34:56  WARNING ..." (older logs: no date) → WARNING: an entry's level. */
private val LOG_LEVEL = Regex("""^(?:\d{4}-\d\d-\d\d )?\d\d:\d\d:\d\d\s+(\w+)""")

/** Lines of [text] at one of [levels]; continuation lines (tracebacks) follow their entry. */
private fun filterLog(text: String, levels: Set<String>?): String {
    if (levels == null) return text
    var keep = false
    return text.lines().filter { line ->
        val level = LOG_LEVEL.find(line)?.groupValues?.get(1)
        if (level != null) keep = level in levels
        keep
    }.joinToString("\n")
}

/**
 * Sends the whole log (both rotated files) as one text file, so it arrives intact
 * instead of split into chat messages. The secret is already redacted in it.
 */
private suspend fun shareFullLog(context: Context) {
    val file = withContext(Dispatchers.IO) { ProxyCore.fullLogSnapshot(context) }
    if (file == null) {
        context.toast(R.string.logs_empty)
        return
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "AndMTProto log")
        clipData = ClipData.newRawUri(null, uri) // lets the chooser preview and pass on the grant
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivitySafely(Intent.createChooser(send, null))
}

/** The tail of the proxy log in a bottom sheet. The core's log has the secret redacted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var refreshes by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(refreshes) {
        text = withContext(Dispatchers.IO) { ProxyCore.readLogTail(context) }
    }
    val shown = filterLog(text, filter.levels)
    val scroll = rememberScrollState()
    // Newest lines are at the end; scroll there once the new text has been laid out.
    LaunchedEffect(shown) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.logs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { refreshes++ }) { Text(stringResource(R.string.refresh)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f, onClick = { filter = f },
                        label = { Text(stringResource(f.label)) },
                    )
                }
            }
            Text(
                shown.ifEmpty { stringResource(R.string.logs_empty) },
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(scroll),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.copyToClipboard(shown, R.string.log_copied) }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.copy))
                }
                Button(onClick = { scope.launch { shareFullLog(context) } }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_full_log))
                }
            }
        }
    }
}

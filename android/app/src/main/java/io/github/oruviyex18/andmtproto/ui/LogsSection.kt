package io.github.oruviyex18.andmtproto.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collapsible tail of the proxy log. */
@Composable
fun LogsSection() {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(false) }
    var refreshes by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    LaunchedEffect(shown, refreshes) {
        if (shown) text = withContext(Dispatchers.IO) { ProxyCore.readLogTail(context) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.logs),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (shown) TextButton(onClick = { refreshes++ }) { Text(stringResource(R.string.refresh)) }
        Switch(checked = shown, onCheckedChange = { shown = it })
    }
    if (shown) {
        Text(
            text.ifEmpty { stringResource(R.string.logs_empty) },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
        )
    }
}

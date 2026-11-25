package io.github.oruviyex18.andmtproto.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.oruviyex18.andmtproto.ProxyCore
import io.github.oruviyex18.andmtproto.R

/** The route's name for the user. */
internal fun routeLabel(context: Context, route: String): String = when {
    route == "direct" -> context.getString(R.string.route_direct)
    route == "cf_bypass" -> context.getString(R.string.route_cf_bypass)
    route == "cf_tls" -> context.getString(R.string.route_cf_tls)
    route == "cf_plain" -> context.getString(R.string.route_cf_plain)
    route.startsWith("worker") -> context.getString(R.string.route_worker, route.removePrefix("worker"))
    else -> route
}

/** How the route did, for the user. */
internal fun routeResult(context: Context, r: ProxyCore.RouteCheck): String = when {
    !r.ok -> context.getString(R.string.route_no_answer)
    r.full == false -> context.getString(R.string.route_cut_off, r.ms ?: 0)
    else -> context.getString(R.string.route_works, r.ms ?: 0)
}

/**
 * "Check routes": every way to Telegram (direct WebSocket, the CF proxy by
 * each way, the user's Workers) with a real request, and how long the answer
 * took. Runs when opened and again on request; needs the proxy running.
 */
@Composable
internal fun RouteCheckDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var runs by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ProxyCore.RouteCheck>?>(null) }
    LaunchedEffect(runs) {
        results = null
        results = ProxyCore.checkRoutes()
    }
    val done = results
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.route_check_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    done == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.route_checking), style = MaterialTheme.typography.bodyMedium)
                    }
                    done.isEmpty() -> Text(stringResource(R.string.route_not_running))
                    else -> {
                        done.forEach { r ->
                            Column {
                                Text(routeLabel(context, r.route), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    routeResult(context, r),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (r.ok && r.full != false) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Text(stringResource(R.string.route_check_note), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.route_close)) } },
        dismissButton = {
            TextButton(enabled = done != null, onClick = { runs++ }) { Text(stringResource(R.string.route_again)) }
        },
    )
}

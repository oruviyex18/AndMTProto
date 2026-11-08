package io.github.oruviyex18.andmtproto.ui

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.oruviyex18.andmtproto.ProxyStatus
import io.github.oruviyex18.andmtproto.R

/** What the big control and the status line show. */
internal enum class ProxyState(@StringRes val label: Int) {
    STOPPED(R.string.status_stopped),
    STARTING(R.string.notif_starting),
    RUNNING(R.string.status_running),
}

internal val ProxyStatus.state
    get() = when {
        !running -> ProxyState.STOPPED
        listening -> ProxyState.RUNNING
        else -> ProxyState.STARTING
    }

/**
 * The big start / stop control. Its shape morphs from a circle (stopped, running)
 * to a rounded square while the core prepares, so the three states differ by
 * shape and colour, not by colour alone.
 */
@Composable
internal fun ConnectControl(state: ProxyState, onClick: () -> Unit) {
    val good = StateColors.good
    val container by animateColorAsState(
        when (state) {
            ProxyState.STOPPED -> MaterialTheme.colorScheme.surfaceVariant
            ProxyState.STARTING -> MaterialTheme.colorScheme.primaryContainer
            ProxyState.RUNNING -> good
        }, label = "container",
    )
    val iconColor by animateColorAsState(
        when (state) {
            ProxyState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            ProxyState.STARTING -> MaterialTheme.colorScheme.onPrimaryContainer
            ProxyState.RUNNING -> StateColors.onGood
        }, label = "icon",
    )
    val corner by animateIntAsState(if (state == ProxyState.STARTING) 30 else 50, label = "corner")
    val description = stringResource(R.string.connect_control)
    val stateText = stringResource(state.label)
    Box(Modifier.padding(top = 16.dp).size(176.dp), contentAlignment = Alignment.Center) {
        if (state == ProxyState.RUNNING) {
            Surface(Modifier.size(176.dp), shape = RoundedCornerShape(50), color = StateColors.goodContainer) {}
        }
        if (state == ProxyState.STARTING) {
            CircularProgressIndicator(Modifier.size(168.dp), strokeWidth = 4.dp)
        }
        Surface(
            onClick = onClick,
            modifier = Modifier
                .size(148.dp)
                .semantics {
                    contentDescription = description
                    stateDescription = stateText
                    role = Role.Button
                },
            shape = RoundedCornerShape(corner),
            color = container,
            border = if (state == ProxyState.STOPPED) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            } else null,
        ) {
            Box(contentAlignment = Alignment.Center) { PowerIcon(iconColor) }
        }
    }
}

/** The power symbol: an open circle with a bar, drawn so no icon library is needed. */
@Composable
private fun PowerIcon(color: Color) {
    Canvas(Modifier.size(56.dp)) {
        val stroke = size.minDimension * 0.09f
        val inset = size.minDimension * 0.15f
        drawArc(
            color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2 * inset, size.height - 2 * inset),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color, Offset(size.width / 2, size.height * 0.08f), Offset(size.width / 2, size.height * 0.5f),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun StatusLine(status: ProxyStatus, address: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(status.state.label),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (status.state == ProxyState.RUNNING) StateColors.good else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (status.state == ProxyState.STOPPED) stringResource(R.string.tap_to_start) else address,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Where connections went since start: straight to Telegram, or through Cloudflare. */
@Composable
internal fun RouteChips(status: ProxyStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RouteChip(
            stringResource(R.string.route_ws, status.ws), active = status.ws > 0,
            container = StateColors.goodContainer, content = StateColors.onGoodContainer,
        )
        RouteChip(
            stringResource(R.string.route_cf, status.cf), active = status.cf > 0,
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun RouteChip(label: String, active: Boolean, container: Color, content: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) container else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) content else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun StatTiles(status: ProxyStatus) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(status.active.toString(), stringResource(R.string.stat_active), Modifier.weight(1f))
        StatTile(status.lastHour.toString(), stringResource(R.string.stat_last_hour), Modifier.weight(1f))
        StatTile(
            "↓ " + Formatter.formatShortFileSize(context, status.bytesDown),
            stringResource(R.string.stat_traffic, Formatter.formatShortFileSize(context, status.bytesUp)),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

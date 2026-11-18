package io.github.oruviyex18.andmtproto.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.oruviyex18.andmtproto.AppUpdater
import io.github.oruviyex18.andmtproto.AppUpdater.Problem
import io.github.oruviyex18.andmtproto.AppUpdater.State
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.R
import io.github.oruviyex18.andmtproto.UpdateCheck

/**
 * The update card: a newer release found by the daily check ([available]), or the
 * progress of "Update the app" ([state]), which takes over while it runs.
 */
@Composable
internal fun UpdateSection(
    state: State,
    available: UpdateCheck.Available?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val releasesUrl = stringResource(R.string.latest_release_url)
    fun open(url: String?) = context.openUrl(url?.takeIf { it.isNotBlank() } ?: releasesUrl)
    when (state) {
        State.Idle, is State.UpToDate -> available?.let {
            AttentionCard(
                title = stringResource(R.string.update_available_title, it.version),
                body = stringResource(R.string.card_update_body, BuildConfig.VERSION_NAME),
            ) {
                TextButton(onClick = onLater) { Text(stringResource(R.string.card_later)) }
                TextButton(onClick = { open(it.url) }) { Text(stringResource(R.string.update_whats_new)) }
                TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_now)) }
            }
        }
        State.Checking -> AttentionCard(title = stringResource(R.string.update_checking), body = "") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is State.NeedsPermission -> AttentionCard(
            title = stringResource(R.string.update_permission_title),
            body = stringResource(R.string.update_permission_body),
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel)) }
            TextButton(onClick = { context.openInstallPermission() }) {
                Text(stringResource(R.string.update_permission_button))
            }
        }
        is State.Downloading -> AttentionCard(
            title = stringResource(R.string.update_downloading_title, state.release.version),
            body = stringResource(
                R.string.update_downloading_progress,
                Formatter.formatShortFileSize(context, state.done),
                Formatter.formatShortFileSize(context, state.total),
            ),
            actionsBelow = true,
        ) {
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel)) }
        }
        is State.Installing -> AttentionCard(
            title = stringResource(R.string.update_installing_title, state.release.version),
            body = stringResource(R.string.update_installing_body),
        )
        is State.Failed -> {
            val reason = stringResource(
                when (state.problem) {
                    Problem.NETWORK -> R.string.update_failed_network
                    Problem.NO_APK -> R.string.update_failed_no_apk
                    Problem.CHECKSUM -> R.string.update_failed_checksum
                    Problem.NOT_OURS -> R.string.update_failed_not_ours
                    Problem.INSTALL -> R.string.update_failed_install
                    Problem.CANCELLED -> R.string.update_failed_cancelled
                }
            )
            val body = state.detail?.takeIf { it.isNotBlank() && state.problem != Problem.CANCELLED }
                ?.let { stringResource(R.string.update_failed_detail, reason, it) } ?: reason
            AttentionCard(
                title = stringResource(R.string.update_failed_title),
                body = body,
                container = if (state.problem == Problem.CANCELLED) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.errorContainer,
                content = if (state.problem == Problem.CANCELLED) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onErrorContainer,
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.update_close)) }
                TextButton(onClick = { open(state.release?.pageUrl) }) { Text(stringResource(R.string.update_in_browser)) }
                TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_retry)) }
            }
        }
    }
}

/** Shown once after "Update the app" found nothing newer. */
internal fun upToDateText(context: android.content.Context, state: AppUpdater.State.UpToDate) =
    context.getString(R.string.update_up_to_date, state.version)

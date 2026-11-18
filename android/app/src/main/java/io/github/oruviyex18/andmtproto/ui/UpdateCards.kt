package io.github.oruviyex18.andmtproto.ui

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
import io.github.oruviyex18.andmtproto.AppUpdater.State
import io.github.oruviyex18.andmtproto.BuildConfig
import io.github.oruviyex18.andmtproto.R
import io.github.oruviyex18.andmtproto.UpdateCheck

/**
 * The update card: a newer release found by the daily check or by "Update the
 * app" ([available]), or that check while it runs ([state]). "Download" opens
 * the APK in the browser; opening the downloaded file installs it with
 * Android's own installer (see [AppUpdater] for why the app doesn't do it).
 */
@Composable
internal fun UpdateSection(
    state: State,
    available: UpdateCheck.Available?,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
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
                TextButton(onClick = { open(it.apkUrl.ifBlank { it.url }) }) {
                    Text(stringResource(R.string.update_now))
                }
            }
        }
        State.Checking -> AttentionCard(title = stringResource(R.string.update_checking), body = "") {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is State.Failed -> {
            val reason = stringResource(R.string.update_failed_network)
            AttentionCard(
                title = stringResource(R.string.update_failed_title),
                body = state.detail?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.update_failed_detail, reason, it) } ?: reason,
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.update_close)) }
                TextButton(onClick = { open(null) }) { Text(stringResource(R.string.update_in_browser)) }
                TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_retry)) }
            }
        }
    }
}

/** Shown once after "Update the app" found nothing newer. */
internal fun upToDateText(context: android.content.Context, state: AppUpdater.State.UpToDate) =
    context.getString(R.string.update_up_to_date, state.version)

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.ConnectionBanner
import de.pyryco.mobile.ui.conversations.components.ThinkingIndicator

// #777: the oldest-end loading row, sized to ThinkingIndicator's shipped spinner-and-label idiom and
// inset on the same 20dp content gutter as the rest of the thread.
private val HistoryLoadingGutter = ComposerGutter
private val HistoryLoadingVerticalPadding = 12.dp
private val HistoryLoadingSpinnerSize = 16.dp
private val HistoryLoadingSpinnerStroke = 2.dp
private val HistoryLoadingLabelGap = 8.dp

// #778: the failure rows' own inset, one step tighter than the loading row's so the tinted surface does
// not read as a message bubble.
private val HistoryTailRowPadding = 12.dp

/**
 * The oldest-end "a history page is in flight" affordance (#777) — the one row in the thread
 * [LazyColumn] that is not a [ThreadItem].
 *
 * The Figma thread frame (16:8) carries no history-loading element, so this follows the app's shipped
 * Material 3 progress idiom instead: [de.pyryco.mobile.ui.conversations.components.ThinkingIndicator]'s
 * small indeterminate spinner beside a `bodySmall` / `onSurfaceVariant` label, itself the stand-in for a
 * frame the design has not yet drawn. Both strings are local resources with no interpolation — nothing
 * daemon-authored reaches the screen through this row.
 */
@Composable
internal fun HistoryLoadingRow() {
    val description = stringResource(R.string.cd_thread_history_loading)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HistoryLoadingGutter, vertical = HistoryLoadingVerticalPadding)
                .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HistoryLoadingLabelGap, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(HistoryLoadingSpinnerSize),
            strokeWidth = HistoryLoadingSpinnerStroke,
        )
        Text(
            text = stringResource(R.string.thread_history_loading_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The oldest-end "that page failed, ask again" affordance (#778) — the retry state of the same single
 * slot [HistoryLoadingRow] occupies.
 *
 * The Figma thread frame (16:8) draws no history element, but it does draw one error-plus-action
 * affordance: the status-area "Pairing error - Re-pair" chip, an error-toned container with an emphasized
 * small label on a 6dp radius. This is that shape through its shipped Compose equivalent,
 * [de.pyryco.mobile.ui.conversations.components.ConnectionBanner]'s `errorContainer` /
 * `onErrorContainer` clickable surface, so the new state reads as the same family as the error
 * affordance the design already drew.
 *
 * Both strings are local resources with no interpolation. In particular the server-authored
 * `RelayErrorException.message` is never surfaced here — the reader is told the page failed, not what the
 * daemon called the failure.
 */
@Composable
internal fun HistoryRetryRow(onRetry: () -> Unit) {
    val description = stringResource(R.string.cd_thread_history_retry)
    HistoryTailSurface {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // Fully qualified: a bare `Role` here is the message-author Role already imported.
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onRetry)
                    .padding(horizontal = HistoryLoadingGutter, vertical = HistoryTailRowPadding)
                    .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HistoryLoadingLabelGap),
        ) {
            Text(
                text = stringResource(R.string.thread_history_retry_label),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.thread_history_retry_action),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The oldest-end "earlier messages are out of reach" affordance (#778) — the same slot and the same
 * surface as [HistoryRetryRow] with nothing to press.
 *
 * A permanent failure is the one the contract marks non-retryable: the remaining `history.*` codes, an
 * unknown conversation id, a closed session, a malformed page. The reader sees that the log ends here
 * rather than silently believing they have reached the start of it, which is why this state is visible at
 * all; a button would be an affordance that cannot work.
 */
@Composable
internal fun HistoryDeadEndRow() {
    val description = stringResource(R.string.cd_thread_history_dead_end)
    HistoryTailSurface {
        Text(
            text = stringResource(R.string.thread_history_dead_end_label),
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HistoryLoadingGutter, vertical = HistoryTailRowPadding)
                    .semantics(mergeDescendants = true) { contentDescription = description },
        )
    }
}

/** The shared error-toned surface behind both oldest-end failure rows (#778). */
@Composable
private fun HistoryTailSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}

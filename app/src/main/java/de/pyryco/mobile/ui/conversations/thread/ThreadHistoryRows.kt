package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
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
 * How close to the oldest end a pull must start to ask for older history (#1352), desktop's
 * `HISTORY_ASK_BAND_PX` in `threadScrollPosition.ts`.
 */
internal val HistoryAskBand = 200.dp

/**
 * The reader's pull toward older messages (#1352), desktop's `demandHistory`: a touch drag toward older
 * content that **starts** with the thread at, or within [HistoryAskBand] of, its oldest end calls
 * [onDemand] once. A drag that starts further away only scrolls. Apply it with [olderHistoryPull].
 *
 * The start is read on the gesture's first pointer down, and the drag is seen as a nested scroll before
 * the list consumes it, so only a user's drag asks: a page arriving, a programmatic or semantics scroll,
 * or the oldest row coming into view never does. It consumes nothing, so a list that cannot scroll — a
 * short thread — still reports the pull, as desktop's wheel does.
 *
 * The thread's list uses `reverseLayout = true`, so older content lies at the top and a pull toward it moves
 * the finger down: a positive `y` delta.
 */
internal class OlderHistoryGesture(
    private val nearOldestEnd: () -> Boolean,
    private val onDemand: () -> Unit,
) : NestedScrollConnection {
    // Whether the current touch gesture started near the oldest end and has not asked yet. Set on the
    // first pointer down; cleared by the ask and by the fling that ends every drag.
    private var armed = false

    fun onGestureStart() {
        armed = nearOldestEnd()
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (armed && source == NestedScrollSource.UserInput && available.y > 0f) {
            armed = false
            onDemand()
        }
        return Offset.Zero
    }

    // A drag's fling dispatch, at zero velocity too, follows its last delta, so this ends the gesture.
    override suspend fun onPreFling(available: Velocity): Velocity {
        armed = false
        return Velocity.Zero
    }
}

/**
 * Attach [gesture] to a scrollable thread surface (#1352): observe each gesture's first pointer down without
 * consuming it, and receive the surface's drag deltas as its nested-scroll parent.
 */
internal fun Modifier.olderHistoryPull(gesture: OlderHistoryGesture): Modifier =
    pointerInput(gesture) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gesture.onGestureStart()
        }
    }.nestedScroll(gesture)

/**
 * Whether the reversed thread list sits at, or within [bandPx] of, its oldest end (#1352). [oldestIndex] is
 * the oldest thread row's list index, or negative when the thread has no rows, which counts as at the end.
 *
 * Under `reverseLayout` an item's offset runs from the viewport's bottom, so the part of the oldest row
 * still hidden above the viewport is `offset + size - viewportEndOffset`. A row not laid out yet counts as
 * further away than the band.
 */
internal fun LazyListLayoutInfo.isNearOldestEnd(
    oldestIndex: Int,
    bandPx: Float,
): Boolean {
    if (oldestIndex < 0) return true
    val oldest = visibleItemsInfo.firstOrNull { it.index == oldestIndex } ?: return false
    return oldest.offset + oldest.size - viewportEndOffset <= bandPx
}

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
 * small label on a 6dp radius. This row uses an `errorContainer` / `onErrorContainer` clickable
 * surface, so it reads as the same family as the error affordance the design already drew.
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
    HistoryNoticeRow(
        label = stringResource(R.string.thread_history_dead_end_label),
        description = stringResource(R.string.cd_thread_history_dead_end),
    )
}

/**
 * The oldest-end "older messages require a connection" notice (#1352), desktop's offline notice: the
 * same slot and treatment as [HistoryDeadEndRow], shown while the host is not connected unless the walk
 * has reached the start of history. Nothing to press; the next pull after the reconnect asks.
 */
@Composable
internal fun HistoryOfflineRow() {
    val label = stringResource(R.string.thread_history_offline_label)
    HistoryNoticeRow(label = label, description = label)
}

/** The body both pressless oldest-end notices share. Both strings are local resources. */
@Composable
private fun HistoryNoticeRow(
    label: String,
    description: String,
) {
    HistoryTailSurface {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HistoryLoadingGutter, vertical = HistoryTailRowPadding)
                    .semantics(mergeDescendants = true) { contentDescription = description },
        )
    }
}

/** The shared error-toned surface behind the oldest-end failure rows (#778) and the offline notice (#1352). */
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

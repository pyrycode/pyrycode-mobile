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
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
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
 * Observe real touch movement toward older content, including that touch's continuing fling.
 * Position is checked after each movement; layout and page arrival never initiate demand.
 * The reversed thread's older direction is a positive nested-scroll y delta.
 */
internal class OlderHistoryGesture(
    private val nearOldestEnd: () -> Boolean,
    private val onDemand: () -> Unit,
    private val onStart: () -> Unit = {},
) : NestedScrollConnection {
    private var touching = false
    private var flingEligible = false
    private var flinging = false

    fun onGestureStart() {
        touching = true
        flingEligible = false
        flinging = false
        onStart()
    }

    fun onGestureEnd() {
        touching = false
    }

    fun onGestureCancel() {
        touching = false
        flingEligible = false
        flinging = false
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        val touchMovement = touching && source == NestedScrollSource.UserInput
        if (touchMovement) flingEligible = true
        val readerMovement = touchMovement || flinging && source == NestedScrollSource.SideEffect
        if (readerMovement && consumed.y + available.y > 0f && nearOldestEnd()) onDemand()
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        flinging = flingEligible && available.y > 0f
        touching = false
        flingEligible = false
        return Velocity.Zero
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        flinging = false
        return Velocity.Zero
    }
}

/** Observe pointer lifetime without consuming the list's input. */
internal fun Modifier.olderHistoryPull(gesture: OlderHistoryGesture): Modifier =
    pointerInput(gesture) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type == PointerType.Touch) {
                    gesture.onGestureStart()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    gesture.onGestureEnd()
                }
            }
        } finally {
            gesture.onGestureCancel()
        }
    }.nestedScroll(gesture)

internal fun historyMarkersFor(
    row: ThreadRow,
    markers: List<ThreadHistoryMarker>,
): List<ThreadHistoryMarker> {
    val keys = (row as? ThreadRow.Delivered)?.item?.historyKeys().orEmpty()
    return markers.filter { it.beforeRow in keys }
}

/** A marker's first newer tool stays visible; unrelated runs keep their existing collapse policy. */
internal fun foldHistoryToolRuns(
    rows: List<ThreadRow>,
    expanded: Set<String>,
    markers: List<ThreadHistoryMarker>,
): List<ThreadRow> =
    buildList {
        var start = 0
        rows.forEachIndexed { index, row ->
            if (historyMarkersFor(row, markers).isNotEmpty()) {
                addAll(foldToolRuns(rows.subList(start, index), expanded))
                add(row)
                start = index + 1
            }
        }
        addAll(foldToolRuns(rows.subList(start, rows.size), expanded))
    }

internal const val HISTORY_NEWEST_GAPS_KEY = "history-newest-gaps"

internal fun visibleHistoryMarker(
    layout: LazyListLayoutInfo,
    rows: List<ThreadRow>,
    promptRows: Int,
    markers: List<ThreadHistoryMarker>,
    heights: Map<Long, Int> = emptyMap(),
): ThreadHistoryMarker? =
    layout.visibleItemsInfo.sortedBy { it.index }.firstNotNullOfOrNull { item ->
        val row = rows.getOrNull(rows.lastIndex - (item.index - promptRows))
        var edge = item.offset + item.size
        val attached =
            if (item.key == HISTORY_NEWEST_GAPS_KEY) {
                markers.filter { it.beforeRow.isEmpty() }
            } else {
                row?.let { historyMarkersFor(it, markers) }.orEmpty()
            }
        attached
            .mapNotNull { marker ->
                val height = heights[marker.anchor] ?: 0
                val visible =
                    edge - height < layout.viewportEndOffset &&
                        edge > layout.viewportStartOffset ||
                        height == 0 &&
                        edge <= layout.viewportEndOffset
                edge -= height
                marker.takeIf { visible }
            }.lastOrNull()
    }

@Composable
internal fun HistoryGapRow(
    anchor: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = HistoryLoadingGutter, vertical = HistoryLoadingVerticalPadding)
                .testTag("history-gap:$anchor"),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.thread_history_gap_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Estimate loaded distance above the reversed viewport, even when its oldest row is not laid out.
 * Measured history rows supply an average height for unseen rows. Prompt and tail rows are excluded.
 * The visible oldest edge is exact; after-content padding remains part of the loaded distance.
 */
internal fun LazyListLayoutInfo.isNearOldestEnd(
    oldestIndex: Int,
    bandPx: Float,
    firstHistoryIndex: Int = 0,
): Boolean {
    if (oldestIndex < 0) return true
    val history = visibleItemsInfo.filter { it.index in firstHistoryIndex..oldestIndex }
    val edge = history.maxByOrNull { it.index } ?: return false
    val unseenRows = oldestIndex - edge.index
    val averageHeight = history.sumOf { it.size.toLong() }.toFloat() / history.size
    val distance =
        edge.offset.toFloat() + edge.size + afterContentPadding - viewportEndOffset +
            unseenRows.toFloat() * (averageHeight + mainAxisItemSpacing)
    return distance <= bandPx
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

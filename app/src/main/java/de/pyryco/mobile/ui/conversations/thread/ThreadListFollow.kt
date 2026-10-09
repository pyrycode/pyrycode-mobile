package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

/** Desktop's `AT_BOTTOM_TOLERANCE_PX`: this close to the newest end still counts as following. */
private val AtNewestEndTolerance = 4.dp

/**
 * One layout frame of the thread list as the follow rule reads it (#1314): the first visible row's key,
 * index and scroll offset, a [content] signature whose change means the newest rows grew, whether a
 * scroll, such as a finger on the list, is in progress, and how many prompt rows lead the list (#1449).
 * [compensatedScroll] accounts for geometry displacement, which must not count as reader movement.
 */
internal data class ListFrame(
    val anchorKey: Any?,
    val anchorIndex: Int,
    val anchorOffset: Int,
    val content: Any?,
    val scrolling: Boolean = false,
    val promptRows: Int = 0,
    val compensatedScroll: Int = 0,
    val relocationVersion: Int = 0,
)

internal data class FollowStep(
    val following: Boolean,
    val pin: Boolean,
)

/**
 * Desktop's `useThreadScrollPin` as one step per frame (#1314). A change to the anchor's key or offset is a
 * scroll after excluding geometry compensation, and recomputes following from position. Under reverseLayout an insert at index 0
 * moves only the anchor's index, a history page moves nothing, and an overscroll at the end moves nothing,
 * so none of them reads as a scroll. Growth with no scroll pins while following.
 *
 * A pin refused under a resting finger leaves the list past the newest end, at index 1 or more, while still
 * following, and a reply streaming below the viewport changes no visible row. So any frame that is not a
 * scroll retries the pin while following and off index 0: the newest row's content changing, or the finger
 * lifting.
 *
 * A reader whose anchor is a prompt row is reading the newest content, so when the prompt leaves, the reader is
 * at the newest end and follows (#1449). The keyed position cannot find the vanished row and keeps its index,
 * which then names an older row, so without this the departure would read as a scroll away from the end.
 */
internal fun followStep(
    previous: ListFrame?,
    current: ListFrame,
    following: Boolean,
    tolerancePx: Int,
): FollowStep {
    val atEnd = current.anchorIndex == 0 && current.anchorOffset <= tolerancePx
    if (previous == null) return FollowStep(following = atEnd, pin = false)
    if (previous.anchorIndex < previous.promptRows && current.promptRows == 0) return FollowStep(following = true, pin = true)
    if (previous.relocationVersion != current.relocationVersion) return FollowStep(following = following, pin = false)
    if (previous.anchorKey != current.anchorKey ||
        current.anchorOffset - previous.anchorOffset != current.compensatedScroll - previous.compensatedScroll
    ) {
        return FollowStep(following = atEnd, pin = false)
    }
    val grew = previous.anchorIndex != current.anchorIndex || previous.content != current.content
    return FollowStep(following = following, pin = following && (grew || current.anchorIndex != 0))
}

/**
 * Keeps [listState] at its newest end while following, and follows again after every accepted send (#1314).
 * [newestRow] is the newest row's content, so a streamed delta is growth even while that row is below the
 * viewport. While a prompt is mounted row content and sizes are left out of the growth signature, so editing
 * a question or permission card never pulls the list (#1304); a new prompt or row still pins a reader who is
 * following. [promptRows] counts the prompt rows ahead of the message rows, so a reader on a prompt follows
 * again when it leaves (#1449).
 */
@Composable
internal fun FollowNewestEnd(
    listState: LazyListState,
    viewport: ThreadListViewport,
    newestRowKey: Any?,
    newestRow: Any?,
    promptIdentity: Any?,
    promptPresent: Boolean,
    promptRows: Int,
    sentMessages: Flow<Unit>,
) {
    val tolerancePx = with(LocalDensity.current) { AtNewestEndTolerance.roundToPx() }
    val newestKey by rememberUpdatedState(newestRowKey)
    val newest by rememberUpdatedState(newestRow)
    val prompt by rememberUpdatedState(promptIdentity)
    val maskSizes by rememberUpdatedState(promptPresent)
    val promptRowCount by rememberUpdatedState(promptRows)
    // Following is plain bookkeeping: changing it does not invalidate layout or drive a frame.
    val follow = viewport
    LaunchedEffect(listState, tolerancePx) {
        var previous: ListFrame? = null
        snapshotFlow {
            val index = listState.firstVisibleItemIndex
            val anchor = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            ListFrame(
                anchorKey = anchor?.key,
                anchorIndex = index,
                anchorOffset = listState.firstVisibleItemScrollOffset,
                content = listOf(newestKey, prompt, newest.takeUnless { maskSizes }, anchor?.size.takeUnless { maskSizes }),
                scrolling = listState.isScrollInProgress,
                promptRows = promptRowCount,
                compensatedScroll = viewport.compensatedScroll,
                relocationVersion = viewport.relocationVersion,
            )
        }.distinctUntilChanged()
            .collect { frame ->
                if (viewport.relocationPending) return@collect
                val step = followStep(previous, frame, follow.following, tolerancePx)
                previous = frame
                follow.following = step.following
                if (step.pin) pinToNewest(listState)
            }
    }
    // Desktop's followBottom: an accepted send follows again, and scrolls now rather than on the next growth.
    LaunchedEffect(listState, sentMessages) {
        sentMessages.collect {
            follow.following = true
            pinToNewest(listState)
        }
    }
}

/**
 * A finger resting on the list holds it at UserInput priority, which refuses this scroll with a
 * CancellationException. The refusal costs this one scroll; a real cancellation of the effect still ends it.
 */
private suspend fun pinToNewest(listState: LazyListState) {
    try {
        listState.scrollToItem(0)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

internal data class ThreadAnchorTransfer(
    val index: Int,
    val offset: Int,
    val key: Any? = null,
    val height: Int = 0,
    val boundaryRows: List<Pair<ThreadRow, ThreadRow?>> = emptyList(),
)

/**
 * Reverse layout holds an item's bottom; a history reader needs its top held instead. Capture the
 * size delta during item measurement, before a shrink can move the anchor to another item. Apply
 * only geometry displacement after placement, without taking a scroll mutation from a finger/fling.
 */
internal class ThreadListViewport(
    private val listState: LazyListState,
) {
    var following = true
    var compensatedScroll = 0
        private set
    var relocationVersion by mutableIntStateOf(0)
        private set
    private var previousRows = emptyList<ThreadRow>()
    private var previousBlocks = emptyList<ThreadRow>()

    // Reuse actual geometry, including a tool's local expansion, only for unchanged old rows.
    private val rowMeasurements = mutableMapOf<Any, Triple<ThreadRow, ThreadRow?, Int>>()
    var relocationPending = false
        private set
    private var relocationAnchor: ThreadAnchorTransfer? = null
    private var boundaryMeasured = false
    val boundaryRows: List<Pair<ThreadRow, ThreadRow?>>
        get() = if (boundaryMeasured) emptyList() else relocationAnchor?.boundaryRows.orEmpty()
    private var sizeDelta = 0
    private var beforePadding: Int? = null
    private var correcting = false

    /** Capture old layout without observing it in composition; apply the transfer before the next measure. */
    fun relocationFor(
        rows: List<ThreadRow>,
        blocks: List<ThreadRow>,
        promptRows: Int,
    ): ThreadAnchorTransfer? {
        if (rows === previousRows && blocks === previousBlocks) return null
        val oldRows = previousRows
        val oldBlocks = previousBlocks
        // Controls can finish a roster before the terminal receipt supplies the final position.
        // Common roots also encode movement across an adjacent still-running block. New rows and
        // children are excluded, so inserts and unchanged terminal replays cannot transfer an anchor.
        val placementKeys =
            oldBlocks
                .filter { it !is ThreadRow.Delivered || it.agentBlockId == null || it.listKey(0) == "msg:${it.agentBlockId}" }
                .mapTo(HashSet()) { it.listKey(0) }
                .intersect(blocks.mapTo(HashSet()) { it.listKey(0) })

        fun placements(source: List<ThreadRow>): Map<String, Any?> {
            var olderKey: Any? = null
            val positions = mutableMapOf<String, Any?>()
            source.forEach { row ->
                val key = row.listKey(0)
                val agent = (row as? ThreadRow.Delivered)?.agentBlockId
                if (agent != null && key == "msg:$agent") positions[agent] = olderKey
                if (key in placementKeys) olderKey = key
            }
            return positions
        }
        val oldPlacements = placements(oldBlocks)
        val newPlacements = placements(blocks)
        val finished =
            blocks
                .filterIsInstance<ThreadRow.AgentStartMarker>()
                .filter { it.finished && it.agentId in oldPlacements && oldPlacements[it.agentId] != newPlacements[it.agentId] }
                .mapTo(HashSet()) { it.agentId }
        if (finished.isEmpty()) return null
        val movedMessages =
            oldBlocks
                .filterIsInstance<ThreadRow.Delivered>()
                .filter { it.agentBlockId in finished }
                .mapTo(HashSet()) { it.listKey(0) }
        val movingKeys =
            oldRows
                .filter { row ->
                    row.listKey(0) in movedMessages || row is ThreadRow.ToolRun && "msg:${row.runId}" in movedMessages
                }.mapTo(HashSet()) { it.listKey(0) }
        if (movingKeys.isEmpty()) return null
        val info = Snapshot.withoutReadObservation { listState.layoutInfo }
        if (info.visibleItemsInfo.isEmpty()) return null
        val currentIndices: Map<Any, Int> = rows.asReversed().mapIndexed { index, row -> row.listKey(0) to index + promptRows }.toMap()
        // Offsets run toward the older end even in reverse layout; rows wholly below zero are behind the composer.
        val stationary = info.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 && it.key !in movingKeys && it.key in currentIndices }
        val index: Int
        val offset: Int
        var boundaryRows = emptyList<Pair<ThreadRow, ThreadRow?>>()
        if (following) {
            index = 0
            offset = 0
        } else if (stationary != null) {
            index = currentIndices.getValue(stationary.key)
            // requestScrollToItem uses the negation of the lazy item's logical offset.
            offset = -stationary.offset
        } else {
            // The viewport is inside the vacated block: its older boundary belongs to the next stationary row.
            val oldReversed = oldRows.asReversed()
            val oldestMoving = info.visibleItemsInfo.filter { it.key in movingKeys }.maxByOrNull { it.index }
            val oldIndex = oldReversed.indexOfFirst { it.listKey(0) == oldestMoving?.key }
            val intervening =
                oldReversed
                    .drop(oldIndex + 1)
                    .takeWhile { it.listKey(0) in movingKeys || it.listKey(0) !in currentIndices }
            val olderKey = oldReversed.getOrNull(oldIndex + 1 + intervening.size)?.listKey(0)
            index = olderKey?.let { currentIndices[it] } ?: 0
            offset =
                if (olderKey == null || oldestMoving == null) {
                    0
                } else {
                    // Measure old offscreen rows before the new list, even after a direct jump or restore.
                    // Their old neighbour controls joined-tool spacing; a cached height is insufficient.
                    val olderRows = intervening.mapIndexed { i, row -> row to oldReversed.getOrNull(oldIndex + i) }
                    val cached =
                        olderRows.mapNotNull { (row, next) ->
                            rowMeasurements[row.listKey(0)]?.takeIf { it.first == row && it.second == next }
                        }
                    val cachedKeys = cached.mapTo(HashSet()) { it.first.listKey(0) }
                    boundaryRows = olderRows.filter { it.first.listKey(0) !in cachedKeys }
                    -oldestMoving.offset - oldestMoving.size - cached.sumOf { it.third }
                }
        }
        return if (!following &&
            stationary != null
        ) {
            ThreadAnchorTransfer(index, offset, stationary.key, stationary.size)
        } else {
            ThreadAnchorTransfer(index, offset, boundaryRows = boundaryRows)
        }
    }

    fun onRowsChanged(
        rows: List<ThreadRow>,
        blocks: List<ThreadRow>,
        transfer: ThreadAnchorTransfer?,
    ) {
        previousRows = rows
        previousBlocks = blocks
        rowMeasurements.keys.retainAll(rows.mapTo(HashSet()) { it.listKey(0) })
        if (transfer == null) return
        sizeDelta = 0
        relocationPending = true
        relocationAnchor = transfer
        boundaryMeasured = false
        if (transfer.boundaryRows.isEmpty()) listState.requestScrollToItem(transfer.index, transfer.offset)
        RelayLog.d { "event=thread_agent_relocation following=$following" }
    }

    fun onBoundaryMeasured(height: Int) {
        val transfer = relocationAnchor ?: return
        if (boundaryMeasured || transfer.boundaryRows.isEmpty()) return
        boundaryMeasured = true
        listState.requestScrollToItem(transfer.index, transfer.offset - height)
    }

    fun onRowMeasured(
        row: ThreadRow,
        nextRow: ThreadRow?,
        height: Int,
    ) {
        val key = row.listKey(0)
        rowMeasurements[key] = Triple(row, nextRow, height)
        if (following || correcting) return
        if (relocationPending) {
            relocationAnchor?.takeIf { it.key == key }?.let { sizeDelta = height - it.height }
            return
        }
        val anchor =
            Snapshot.withoutReadObservation {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
            }
        if (anchor?.key == key) sizeDelta = height - anchor.size
    }

    fun onPositioned() {
        if (correcting) return
        if (relocationPending) {
            relocationPending = false
            relocationAnchor = null
            relocationVersion++
        }
        val padding = listState.layoutInfo.beforeContentPadding
        val delta = if (following) 0 else sizeDelta + padding - (beforePadding ?: padding)
        sizeDelta = 0
        beforePadding = padding
        if (delta == 0) return
        correcting = true
        try {
            val consumed = listState.dispatchRawDelta(delta.toFloat()).roundToInt()
            compensatedScroll += consumed
            RelayLog.d { "event=thread_reader_compensation delta_px=$delta consumed_px=$consumed" }
        } finally {
            correcting = false
        }
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Desktop's `AT_BOTTOM_TOLERANCE_PX`: this close to the newest end still counts as following. */
private val AtNewestEndTolerance = 4.dp

/**
 * One layout frame of the thread list as the follow rule reads it (#1314): the first visible row's key,
 * index and scroll offset, a [content] signature whose change means the newest rows grew, and whether a
 * scroll, such as a finger on the list, is in progress.
 */
internal data class ListFrame(
    val anchorKey: Any?,
    val anchorIndex: Int,
    val anchorOffset: Int,
    val content: Any?,
    val scrolling: Boolean = false,
)

internal data class FollowStep(
    val following: Boolean,
    val pin: Boolean,
)

/**
 * Desktop's `useThreadScrollPin` as one step per frame (#1314). A change to the anchor's key or offset is a
 * scroll, of any source, and recomputes following from position. Under reverseLayout an insert at index 0
 * moves only the anchor's index, a history page moves nothing, and an overscroll at the end moves nothing,
 * so none of them reads as a scroll. Growth with no scroll pins while following.
 *
 * A pin refused under a resting finger leaves the list past the newest end, at index 1 or more, while still
 * following, and a reply streaming below the viewport changes no visible row. So any frame that is not a
 * scroll retries the pin while following and off index 0: the newest row's content changing, or the finger
 * lifting.
 */
internal fun followStep(
    previous: ListFrame?,
    current: ListFrame,
    following: Boolean,
    tolerancePx: Int,
): FollowStep {
    val atEnd = current.anchorIndex == 0 && current.anchorOffset <= tolerancePx
    if (previous == null) return FollowStep(following = atEnd, pin = false)
    if (previous.anchorKey != current.anchorKey || previous.anchorOffset != current.anchorOffset) {
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
 * following.
 */
@Composable
internal fun FollowNewestEnd(
    listState: LazyListState,
    newestRowKey: Any?,
    newestRow: Any?,
    promptIdentity: Any?,
    promptPresent: Boolean,
    sentMessages: Flow<Unit>,
) {
    val tolerancePx = with(LocalDensity.current) { AtNewestEndTolerance.roundToPx() }
    val newestKey by rememberUpdatedState(newestRowKey)
    val newest by rememberUpdatedState(newestRow)
    val prompt by rememberUpdatedState(promptIdentity)
    val maskSizes by rememberUpdatedState(promptPresent)
    // Read only inside the collectors below, never inside the snapshotFlow, so it cannot drive a frame.
    val follow = remember(listState) { Following() }
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
            )
        }.distinctUntilChanged()
            .collect { frame ->
                val step = followStep(previous, frame, follow.value, tolerancePx)
                previous = frame
                follow.value = step.following
                if (step.pin) pinToNewest(listState)
            }
    }
    // Desktop's followBottom: an accepted send follows again, and scrolls now rather than on the next growth.
    LaunchedEffect(listState, sentMessages) {
        sentMessages.collect {
            follow.value = true
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

private class Following(
    var value: Boolean = true,
)

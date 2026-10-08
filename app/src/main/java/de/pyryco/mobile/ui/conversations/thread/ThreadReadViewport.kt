package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.pyryco.mobile.data.repository.ThreadItem

@Composable
internal fun ThreadReadViewport(
    state: ThreadUiState,
    listState: LazyListState,
    rowKey: Any?,
    row: ThreadItem?,
    laidOut: Boolean,
    trailingEdge: Float?,
    viewport: Rect?,
    revealed: Boolean,
    headerHeight: Dp,
    composerHeight: Dp,
    visible: Boolean,
    onEvent: (ThreadEvent) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed by rememberUpdatedState(lifecycleState.isAtLeast(Lifecycle.State.RESUMED))
    val density = LocalDensity.current
    val header = with(density) { headerHeight.toPx() }
    val composer = with(density) { composerHeight.toPx() }
    val currentState by rememberUpdatedState(state)
    val currentRow by rememberUpdatedState(row)
    val currentKey by rememberUpdatedState(rowKey)
    val edge by rememberUpdatedState(trailingEdge)
    val viewportBounds by rememberUpdatedState(viewport)
    val ready by rememberUpdatedState(laidOut && revealed && visible)
    val chrome by rememberUpdatedState(header to composer)
    val dispatch by rememberUpdatedState(onEvent)
    LaunchedEffect(listState, lifecycle, state.conversationId) {
        var sent = 0uL
        snapshotFlow {
            val content = currentRow
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == currentKey }
            val checkpoint = currentState.readUpTo?.let { currentState.readEvidence?.checkpoint(content ?: return@snapshotFlow null, it) }
            val bounds = viewportBounds
            val bottom = edge
            if (resumed &&
                ready &&
                item != null &&
                chrome.first > 0 &&
                chrome.second > 0 &&
                bottom != null &&
                bounds != null &&
                qualifiesReadEdge(bottom, bounds.top, bounds.bottom, chrome.first, chrome.second)
            ) {
                content?.let { it to checkpoint }
            } else {
                null
            }
        }.collect { qualified ->
            val (content, checkpoint) = qualified ?: return@collect
            if (checkpoint != null && checkpoint > sent) {
                sent = checkpoint
                dispatch(ThreadEvent.NewestContentPresented(content, checkpoint))
            }
        }
    }
}

internal fun qualifiesReadEdge(
    trailing: Float,
    viewportStart: Float,
    viewportEnd: Float,
    header: Float,
    composer: Float,
): Boolean = trailing >= viewportStart + header && trailing <= viewportEnd - composer

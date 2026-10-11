package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.pyryco.mobile.data.repository.ThreadItem

/** One exact rendered version; retired callbacks can update only their retired instance. */
@Stable
internal class ThreadReadCandidate(
    val key: Any,
    val row: ThreadItem,
) {
    var laidOut by mutableStateOf(false)
    var trailingEdge by mutableStateOf<Float?>(null)
    var revealed by mutableStateOf(row !is ThreadItem.MessageItem)
}

@Composable
internal fun ThreadReadViewport(
    state: ThreadUiState,
    listState: LazyListState,
    candidate: ThreadReadCandidate?,
    viewport: State<Rect?>,
    headerHeight: Dp,
    composerHeight: Dp,
    visible: Boolean,
    otherCandidates: List<ThreadReadCandidate> = emptyList(),
    onEvent: (ThreadEvent) -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val resumed by rememberUpdatedState(lifecycleState.isAtLeast(Lifecycle.State.RESUMED))
    val density = LocalDensity.current
    val header = with(density) { headerHeight.toPx() }
    val composer = with(density) { composerHeight.toPx() }
    val currentState by rememberUpdatedState(state)
    val currentCandidate by rememberUpdatedState(candidate)
    val currentCandidates by rememberUpdatedState(otherCandidates)
    val viewportBounds by rememberUpdatedState(viewport)
    val unobscured by rememberUpdatedState(visible)
    val chrome by rememberUpdatedState(header to composer)
    val dispatch by rememberUpdatedState(onEvent)
    LaunchedEffect(listState, lifecycle, state.conversationId, state.authoritativeThread?.scope) {
        var sent = 0uL
        val seen = mutableSetOf<ThreadItem>()
        var seenPresentation: AuthoritativeThreadPresentation? = null
        snapshotFlow {
            val tracked = currentCandidate ?: return@snapshotFlow null
            val info = listState.layoutInfo
            val bounds = viewportBounds.value ?: return@snapshotFlow null
            if (!resumed || !unobscured || !currentState.hostAvailable || chrome.first <= 0 || chrome.second <= 0) return@snapshotFlow null

            fun ready(row: ThreadReadCandidate): Boolean {
                val bottom = row.trailingEdge ?: return false
                return row.laidOut &&
                    row.revealed &&
                    info.visibleItemsInfo.any { it.key == row.key } &&
                    qualifiesReadEdge(bottom, bounds.top, bounds.bottom, chrome.first, chrome.second)
            }
            val visibleRows = (currentCandidates + tracked).filter(::ready).mapTo(HashSet()) { it.row }
            Triple(currentState, tracked.row.takeIf { ready(tracked) }, visibleRows)
        }.collect { sight ->
            val (current, newest, visibleRows) = sight ?: return@collect
            if (seenPresentation !== current.authoritativeThread) {
                seen.clear()
                seenPresentation = current.authoritativeThread
            }
            seen.addAll(visibleRows)
            val content = newest ?: return@collect
            val presentation = current.authoritativeThread
            val checkpoint =
                if (presentation != null) {
                    presentation.readCheckpoint(content, current.readMarks, seen)
                } else {
                    current.readUpTo?.let { current.readEvidence?.checkpoint(content, it) }
                }
            if (checkpoint != null && checkpoint > sent) {
                sent = checkpoint
                dispatch(ThreadEvent.NewestContentPresented(content, checkpoint, presentation))
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

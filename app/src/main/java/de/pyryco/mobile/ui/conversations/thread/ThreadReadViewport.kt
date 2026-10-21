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
    val revision: Long? = null,
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
        val seen = mutableMapOf<String, Long>()
        var seenPresentation: AuthoritativeThreadPresentation? = null
        snapshotFlow {
            val tracked = currentCandidate ?: return@snapshotFlow null
            val info = listState.layoutInfo
            val bounds = viewportBounds.value ?: return@snapshotFlow null
            val authoritative = currentState.authoritativeThread != null || currentState.readMarks?.threadSupported == true
            if (!resumed || !unobscured || authoritative && !currentState.hostAvailable || chrome.first <= 0 || chrome.second <= 0) {
                return@snapshotFlow null
            }

            fun ready(row: ThreadReadCandidate): Boolean {
                val bottom = row.trailingEdge ?: return false
                return row.laidOut &&
                    row.revealed &&
                    info.visibleItemsInfo.any { it.key == row.key } &&
                    qualifiesReadEdge(bottom, bounds.top, bounds.bottom, chrome.first, chrome.second)
            }
            val visibleRows =
                (currentCandidates + tracked)
                    .filter(::ready)
                    .mapNotNull { row ->
                        val key = row.key as? String ?: return@mapNotNull null
                        row.revision?.let { key to it }
                    }.toMap()
            Triple(currentState, tracked.takeIf { ready(tracked) }, visibleRows)
        }.collect { sight ->
            val (current, newest, visibleRows) = sight ?: return@collect
            if (seenPresentation !== current.authoritativeThread) {
                seen.clear()
                seenPresentation = current.authoritativeThread
            }
            seen.putAll(visibleRows)
            val content = newest ?: return@collect
            val presentation = current.authoritativeThread
            val checkpoint = presentation?.readCheckpoint(content.key as? String, current.readMarks, seen)
            if (checkpoint != null && checkpoint > sent) {
                sent = checkpoint
                dispatch(
                    ThreadEvent.NewestContentPresented(
                        content.row,
                        checkpoint,
                        presentation,
                        rowKey = if (presentation != null) content.key as? String else null,
                        seenRowRevisions = if (presentation != null) seen.toMap() else emptyMap(),
                    ),
                )
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

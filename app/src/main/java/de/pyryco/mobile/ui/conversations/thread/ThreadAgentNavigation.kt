package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

/** Main-thread, mounted-destination ownership of scroll-only marker intent. */
internal class ThreadAgentNavigation : NestedScrollConnection {
    // Reference identity distinguishes repeated taps on the same root.
    class Request(
        val agentId: String,
    )

    var pending by mutableStateOf<Request?>(null)
        private set
    private var navigationJob: Job? = null

    fun request(agentId: String) {
        cancel("replaced")
        pending = Request(agentId)
        RelayLog.d { "event=thread_agent_navigation code=requested" }
    }

    fun cancel(code: String) {
        if (pending == null) return
        pending = null
        navigationJob?.cancel()
        navigationJob = null
        RelayLog.d { "event=thread_agent_navigation code=$code" }
    }

    override fun onPreScroll(
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (source == NestedScrollSource.UserInput && available.y != 0f) cancel("reader_scroll")
        return Offset.Zero
    }

    suspend fun scrollToRoot(
        listState: LazyListState,
        index: Int,
        request: Request,
    ) {
        if (pending !== request) return
        val job = currentCoroutineContext().job
        navigationJob = job
        try {
            listState.scrollToItem(index)
            if (pending === request) {
                pending = null
                RelayLog.d { "event=thread_agent_navigation code=completed" }
            }
        } catch (cancelled: CancellationException) {
            // A rows-effect restart retains waiting intent. A scroll mutation refused/interrupted
            // by reader input leaves this effect active, so retire that request instead of retrying.
            currentCoroutineContext().ensureActive()
            if (pending === request) cancel("reader_interrupt")
        } finally {
            if (navigationJob === job) navigationJob = null
        }
    }
}

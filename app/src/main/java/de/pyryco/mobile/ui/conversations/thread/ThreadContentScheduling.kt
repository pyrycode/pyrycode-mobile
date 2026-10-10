package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.AndroidUiDispatcher
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Production destination scheduling; tests can control worker progress and actual frame arrivals. */
class ThreadContentScheduling(
    val worker: CoroutineDispatcher = Dispatchers.Default,
    val awaitFrame: suspend () -> Long = { withContext(AndroidUiDispatcher.Main) { withFrameNanos { it } } },
)

/** Only complete accumulated values may enter this operator, never incremental live events. */
internal fun <T : Any> Flow<T>.paceThreadContent(scheduling: ThreadContentScheduling?): Flow<T> {
    if (scheduling == null) return this
    return flow {
        coroutineScope {
            val pending = Channel<T>(Channel.CONFLATED)
            val upstream = this@paceThreadContent
            launch {
                try {
                    upstream.collect { pending.send(it) }
                } finally {
                    pending.close()
                }
            }
            RelayLog.d { "event=thread_frame_pacing_started" }
            try {
                while (true) {
                    var latest = pending.receiveCatching().getOrNull() ?: break
                    scheduling.awaitFrame()
                    while (true) {
                        latest = pending.tryReceive().getOrNull() ?: break
                    }
                    emit(latest)
                    RelayLog.d { "event=thread_frame_content_published" }
                }
            } finally {
                pending.cancel()
                RelayLog.d { "event=thread_frame_pacing_stopped" }
            }
        }
    }
}

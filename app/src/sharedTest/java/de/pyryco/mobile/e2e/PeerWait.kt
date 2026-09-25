package de.pyryco.mobile.e2e

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Run one of a live peer's waits (#1059): [block] for up to [timeoutMs], failing at once, and naming [what] it
 * awaited, when [closed] turns true under it rather than waiting out its timeout on a peer that can no longer
 * answer. Otherwise it behaves as a plain [withTimeout]: the value, [block]'s own failure, or the
 * `TimeoutCancellationException` that scenarios catch to name their step.
 *
 * A single dropped link is not a close: the peer redials it (#1036) and the wait carries across. [what] names a
 * frame type or a request, never an id or payload text.
 */
internal suspend fun <T> awaitPeer(
    what: String,
    timeoutMs: Long,
    closed: StateFlow<Boolean>,
    block: suspend () -> T,
): T =
    withTimeout(timeoutMs) {
        coroutineScope {
            val watcher =
                launch {
                    closed.first { it }
                    throw AssertionError("peer session closed while awaiting $what")
                }
            block().also { watcher.cancel() }
        }
    }

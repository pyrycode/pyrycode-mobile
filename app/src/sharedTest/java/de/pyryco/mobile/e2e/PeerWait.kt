package de.pyryco.mobile.e2e

import kotlinx.coroutines.TimeoutCancellationException
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
                    throw PeerSessionClosedError(what)
                }
            block().also { watcher.cancel() }
        }
    }

/** [awaitPeer]'s failure when the peer closes under a wait, typed so [requirePeerAnswer] can tell it apart. */
internal class PeerSessionClosedError(
    what: String,
) : AssertionError("peer session closed while awaiting $what")

/**
 * Run [request] on a peer whose open session must answer (#1063), and fail as a relay or daemon fault when it
 * does not: the peer's session closed under the request, or [timeoutMs] ran out. Any other failure, such as a
 * refusal naming its code, passes through unchanged.
 */
internal suspend fun <T> requirePeerAnswer(
    timeoutMs: Long,
    request: suspend () -> T,
): T =
    try {
        request()
    } catch (e: PeerSessionClosedError) {
        throw AssertionError("the peer's session closed before it answered a request: a relay or daemon fault", e)
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("the peer's open session answered no request within $timeoutMs ms: a relay or daemon fault", e)
    }

/** Fixed labels for the background-progress scenario's setup operations; never daemon-authored text. */
internal enum class LiveSetupStage(
    val label: String,
) {
    ConnectionReadiness("phone connection readiness"),
    ChatCreation("chat creation"),
    PeerOpening("peer opening"),
}

/**
 * Name a background-progress setup timeout without changing the blocking operation's own deadline.
 * [linkState] is the peer's content-free status, read only on failure. Other failures pass through.
 */
internal inline fun <T> liveSetupStep(
    stage: LiveSetupStage,
    linkState: () -> String = { "" },
    block: () -> T,
): T =
    try {
        block()
    } catch (e: TimeoutCancellationException) {
        val state = linkState()
        val detail = if (state.isEmpty()) "" else "; $state"
        throw AssertionError("background progress setup '${stage.label}' timed out$detail", e)
    }

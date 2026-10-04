package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.RelayLinkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

/** Create and rename on the live repository, naming the stalled operation with content-free state. */
internal suspend fun <R : Any, T> StateFlow<R?>.createLiveChatWithDiagnostics(
    timeoutMs: Long,
    replacementWaitMs: Long,
    relayStatus: StateFlow<RelayLinkStatus>,
    create: suspend (R) -> T,
    rename: suspend (R, T) -> T,
): T {
    var step = "await live repository"
    try {
        return withTimeout(timeoutMs) {
            callOnLive(replacementWaitMs) { source ->
                try {
                    step = "create discussion"
                    val chat = create(source)
                    step = "rename discussion"
                    rename(source, chat)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    step = "await replacement repository"
                    throw e
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        // The caller's own deadline/cancellation must remain cancellation, not a setup assertion.
        currentCoroutineContext().ensureActive()
        throw AssertionError(
            "chat setup step '$step' timed out; repository present=${value != null}; relay=${relayStatus.value::class.simpleName}",
            e,
        )
    }
}

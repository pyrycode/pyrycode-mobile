package de.pyryco.mobile.e2e

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Live-e2e reads that follow a host's redial (#1029). Each connection gets its own repository, and on the
 * live relay a fresh connection often ends within a second of its handshake (#1039); the supervisor then
 * redials and the coordinator publishes a new repository. The app's screens follow it through
 * `StableConversationRepository`. A scenario that keeps one repository reads a torn-down connection
 * until its timeout. These helpers read from whichever connection is current instead. Generic in the
 * source type so the JVM test needs no repository fake.
 */

/**
 * The first non-null value [select] yields that [accept] takes, read from the source this holder has
 * when the value arrives. A replaced source's flow is cancelled and the new one read; a value is taken
 * only while its source is still current, so nothing comes from a torn-down connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <R : Any, T : Any> StateFlow<R?>.firstOnLive(
    select: (R) -> Flow<T?>,
    accept: (T) -> Boolean,
): T =
    flatMapLatest { source -> source?.let { live -> select(live).filterNotNull().filter(accept).map { live to it } } ?: emptyFlow() }
        .first { (source, _) -> source === value }
        .second

/**
 * [block] on the current source. When it fails and this holder moves to a different source within
 * [replacementWaitMs], it runs again on that one; otherwise the original failure is rethrown, so a
 * failure on a connection that stayed up keeps its own cause. It never retries on the same source.
 * Retries are bounded by the caller's timeout, whose cancellation always propagates.
 */
suspend fun <R : Any, T> StateFlow<R?>.callOnLive(
    replacementWaitMs: Long,
    block: suspend (R) -> T,
): T {
    while (true) {
        val source = filterNotNull().first()
        try {
            return block(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withTimeoutOrNull(replacementWaitMs) { first { it != null && it !== source } } ?: throw e
        }
    }
}

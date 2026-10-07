package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.RelayBackoff
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

internal object OfflineRetryWindow {
    suspend fun awaitDeadline(supervisor: RelayConnectionSupervisor): TimeMark {
        val backoff =
            withTimeoutOrNull(90_000) {
                supervisor.backoffState.filterNotNull().first { it.attempt >= 6 }
            }
        checkNotNull(backoff) {
            "Offline Retry backoff observation timed out: attempt=${supervisor.backoffState.value?.attempt}, " +
                "status=${supervisor.relayStatus.value::class.simpleName}"
        }
        return deadlineFor(backoff)
    }

    fun deadlineFor(backoff: RelayBackoff): TimeMark {
        check(backoff.attempt >= 6) { "Offline Retry requires a capped backoff" }
        // The cap waits at least 24 s. Observer lag, daemon startup and the tap share this 20 s budget.
        return backoff.startedAt + 20.seconds
    }

    fun remainingMs(deadline: TimeMark): Long =
        (-deadline.elapsedNow()).inWholeMilliseconds.also {
            check(it > 0) { "the passive reconnect window elapsed before Retry recovery" }
        }
}

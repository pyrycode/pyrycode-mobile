package de.pyryco.mobile.e2e

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Bounded test drive; the Android caller supplies only content-free snapshots. */
internal suspend fun awaitHostLinkChange(
    up: Boolean,
    timeoutMs: Long,
    drive: () -> Unit,
    available: Flow<Boolean>,
    snapshot: () -> String,
    record: (String) -> Unit,
) {
    val action = if (up) "reconnect" else "close"
    var stage = "drive"

    fun evidence(outcome: String) = record("event=host_link action=$action stage=$stage outcome=$outcome; ${snapshot()}")
    withTimeoutDiagnostic({
        evidence("timeout")
        "host link $action timed out; stage=$stage; ${snapshot()}"
    }) {
        withTimeout(timeoutMs) {
            evidence("start")
            drive()
            stage = "repository"
            evidence("start")
            available.first { it == up }
            evidence("complete")
        }
    }
}

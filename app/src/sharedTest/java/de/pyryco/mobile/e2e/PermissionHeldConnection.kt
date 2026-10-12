package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.di.RelayConnectionRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Readiness for the host whose permission-held chat the phone will create. */
internal suspend fun awaitPermissionHeldConnection(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
) {
    withTimeout(timeoutMs) {
        registry.observe().first { it is ConnectionState.Connected }
    }
}

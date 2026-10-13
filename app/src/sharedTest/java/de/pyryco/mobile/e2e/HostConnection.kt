package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.RelayConnectionRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Readiness belongs to the addressed host, independent of compatibility selection. */
internal suspend fun awaitHostConnection(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
    missingHostMessage: String = "Live harness host not registered",
) {
    val bundle = checkNotNull(registry.connectionFor(serverId)) { missingHostMessage }
    withTimeout(timeoutMs) {
        bundle.coordinator.connectionStatus.first {
            it.relay is RelayLinkStatus.Connected && it.pyrycode is PyrycodeLinkStatus.Connected
        }
    }
}

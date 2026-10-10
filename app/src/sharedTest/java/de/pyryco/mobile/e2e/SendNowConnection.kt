package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.RelayConnectionRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Send now is addressed to its harness host, independent of an earlier scenario's selection. */
internal suspend fun awaitSendNowConnection(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
) {
    val bundle = checkNotNull(registry.connectionFor(serverId)) { "Send now harness host not registered" }
    withTimeout(timeoutMs) {
        bundle.coordinator.connectionStatus.first {
            it.relay is RelayLinkStatus.Connected && it.pyrycode is PyrycodeLinkStatus.Connected
        }
    }
}

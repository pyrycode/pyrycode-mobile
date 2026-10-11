package de.pyryco.mobile.e2e

import de.pyryco.mobile.di.RelayConnectionBundle
import de.pyryco.mobile.di.RelayConnectionRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Await the outage host, including asynchronous registration after an application graph rebuild. */
internal suspend fun awaitOfflineRetryHost(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
): RelayConnectionBundle =
    withTimeout(timeoutMs) {
        registry.hostConnections.first { registry.connectionFor(serverId) != null }
        val bundle = checkNotNull(registry.connectionFor(serverId)) { "Offline Retry host removed during setup" }
        bundle.coordinator.currentRepository
            .filterNotNull()
            .first { it === bundle.coordinator.currentRepository.value }
        bundle
    }

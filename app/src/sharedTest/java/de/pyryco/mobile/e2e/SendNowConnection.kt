package de.pyryco.mobile.e2e

import de.pyryco.mobile.di.RelayConnectionRegistry

/** Send now is addressed to its harness host, independent of an earlier scenario's selection. */
internal suspend fun awaitSendNowConnection(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
) {
    awaitHostConnection(registry, serverId, timeoutMs, "Send now harness host not registered")
}

package de.pyryco.mobile.e2e

import de.pyryco.mobile.di.RelayConnectionRegistry

/** Readiness for the host whose permission-held chat the phone will create. */
internal suspend fun awaitPermissionHeldConnection(
    registry: RelayConnectionRegistry,
    serverId: String,
    timeoutMs: Long,
) = awaitHostConnection(registry, serverId, timeoutMs, "Permission-held tool harness host not registered")

package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Fresh offscreen conversations must finish their attention query before the scenario acquires them. */
internal suspend fun awaitInitialAttentionQuery(snapshots: Flow<AuthoritativeThreadSnapshot>) {
    snapshots.first {
        !it.epoch.isNullOrEmpty() && it.checkpoint > 0 && it.coverage != null && !it.repairNeeded && !it.resetNeeded
    }
}

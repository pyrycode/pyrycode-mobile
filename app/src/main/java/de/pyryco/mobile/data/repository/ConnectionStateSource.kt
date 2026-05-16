package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConnectionState
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2 data-layer contract for "is the client connected?". The fake (Phase 2)
 * and Ktor-backed remote (Phase 4+) implementations both satisfy this surface.
 *
 * The interface is the load-bearing contract for the connection banner in #197;
 * the Phase 4 walk-back swaps the Koin binding only — the interface does not change.
 */
interface ConnectionStateSource {
    /**
     * Cold [Flow] of the current connection state. Collectors receive the current
     * value on subscription and every subsequent change.
     */
    fun observe(): Flow<ConnectionState>

    /**
     * Triggers a reconnect attempt. Phase 2 fake is a no-op. The Phase 4 real
     * implementation is expected to transition the observed flow through
     * [ConnectionState.Connecting] → [ConnectionState.Connected] or
     * [ConnectionState.Offline]; network failures surface as state transitions,
     * not by throwing from this method.
     */
    suspend fun retry()
}

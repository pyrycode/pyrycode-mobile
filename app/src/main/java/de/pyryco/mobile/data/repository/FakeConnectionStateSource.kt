package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 2 in-memory implementation. Always reports [ConnectionState.Connected]
 * by default; tests and previews can push any state via [emit]. Phase 4 replaces
 * this with a Ktor/WebSocket-backed implementation behind the same interface.
 */
class FakeConnectionStateSource : ConnectionStateSource {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)

    override fun observe(): Flow<ConnectionState> = state.asStateFlow()

    override suspend fun retry() {
        // Phase 2: no-op stub. Phase 4 will trigger a reconnect attempt.
    }

    /** Test/preview seam: push a state into the observed flow. Not part of [ConnectionStateSource]. */
    fun emit(state: ConnectionState) {
        this.state.value = state
    }
}

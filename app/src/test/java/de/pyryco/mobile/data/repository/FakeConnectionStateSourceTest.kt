package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeConnectionStateSourceTest {
    @Test
    fun observe_emits_Connected_by_default() =
        runBlocking {
            val source = FakeConnectionStateSource()
            assertEquals(ConnectionState.Connected, source.observe().first())
        }

    // MutableStateFlow conflates rapid emissions, so we can't reliably collect
    // an in-order list of intermediate values across a single subscription.
    // Per the AC ("surfaces each in order via observe()"), asserting a fresh
    // `.first()` after each emit is sufficient and deterministic.
    @Test
    fun emit_drivesAllFourStates_inOrder() =
        runBlocking {
            val source = FakeConnectionStateSource()
            val states =
                listOf(
                    ConnectionState.Connecting,
                    ConnectionState.Reconnecting(5),
                    ConnectionState.Offline,
                    ConnectionState.Connected,
                )
            for (state in states) {
                source.emit(state)
                assertEquals(state, source.observe().first())
            }
        }

    @Test
    fun retry_is_callable_without_throwing() =
        runBlocking {
            val source = FakeConnectionStateSource()
            source.retry()
        }
}

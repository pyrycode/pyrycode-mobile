package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ClaudeUsageOwnershipTest {
    @Test
    fun replacementCannotOvertakeAnInFlightAllowanceCommit() =
        runTest {
            val previousSink = RelayLog.sink
            RelayLog.sink = { _, _, _ -> }
            val connections = MutableStateFlow<RelayTransport?>(null)
            val created = Channel<Pump>(Channel.UNLIMITED)
            val resume = CountDownLatch(1)
            val coordinator =
                RelayRepositoryCoordinator(
                    connections,
                    MutableStateFlow(RelayLinkStatus.Connected),
                    { Pump().also { created.trySend(it) } },
                    Dispatchers.Default,
                )
            try {
                coordinator.start()
                connections.value = Transport()
                val first = created.receive()
                val oldRepository = coordinator.currentRepository.first { it != null }
                first.requests.receive()
                val checked = CompletableDeferred<Boolean>()
                first.pauseFinalCheck = {
                    // The getter has captured Open: all ownership operands have already passed.
                    checked.complete(Thread.holdsLock(coordinator))
                    check(resume.await(10, TimeUnit.SECONDS)) { "Allowance handler was not released" }
                }
                first.push("""{"state":"observed","observed_at":"2026-10-20T00:00:00Z","five_hour":{"utilization":0.8}}""")
                val commitHoldsRetirementLock = checked.await()
                connections.value = Transport()

                suspend fun publishReplacement() {
                    val second = created.receive()
                    coordinator.currentRepository.first { it != null && it !== oldRepository }
                    second.requests.receive()
                    second.push("""{"state":"none"}""")
                    second.handled.receive()
                    assertEquals(ClaudeUsageReading.None, coordinator.hostReadings.claudeUsage.value)
                }

                // Without the lock, force B's None to land before A resumes its non-suspending write.
                // With the lock, retirement must wait for A; then B is the final accepted arrival.
                if (!commitHoldsRetirementLock) publishReplacement()
                resume.countDown()
                first.handled.receive()
                if (commitHoldsRetirementLock) publishReplacement()
                assertEquals(ClaudeUsageReading.None, coordinator.hostReadings.claudeUsage.value)
                assertTrue("Final ownership validation and replacement must hold the retirement lock", commitHoldsRetirementLock)
            } finally {
                resume.countDown()
                coordinator.close()
                RelayLog.sink = previousSink
            }
        }

    private class Pump : ManagedSessionPump {
        private val incoming = Channel<Envelope>(Channel.UNLIMITED)
        private val states = MutableStateFlow<PumpState>(PumpState.Open("reused", emptySet()))
        private val ownershipReads = ThreadLocal<Int>()
        val requests = Channel<Envelope>(Channel.UNLIMITED)
        val handled = Channel<Unit>(Channel.UNLIMITED)
        var pauseFinalCheck: (() -> Unit)? = null

        override val state: StateFlow<PumpState> =
            object : StateFlow<PumpState> by states {
                override val value: PumpState
                    get() {
                        val captured = states.value
                        ownershipReads.get()?.let { count ->
                            ownershipReads.set(count + 1)
                            if (count == 1) pauseFinalCheck?.invoke()
                        }
                        return captured
                    }
            }

        override val inbound: Flow<Envelope> =
            flow {
                for (envelope in incoming) {
                    ownershipReads.set(0)
                    try {
                        emit(envelope)
                    } finally {
                        ownershipReads.remove()
                        handled.trySend(Unit)
                    }
                }
            }

        override fun start() = Unit

        override fun close() = Unit

        override fun send(envelope: Envelope): Boolean {
            requests.trySend(envelope)
            return true
        }

        fun push(payload: String) {
            incoming.trySend(Envelope(1, "claude_usage", "2026-10-20T00:00:00Z", MobileJson.parseToJsonElement(payload)))
        }
    }

    private class Transport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }
}

package de.pyryco.mobile.di

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.diagnostics.MessageTrail
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadMissingHostRetryTest {
    private val oldSink = RelayLog.sink

    @Before fun quietLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = oldSink
    }

    @Test fun connectedMissingHostRedialsOnlyItselfAndRecoversThroughHostSnapshots() =
        runTest {
            val f = Fixture(this)
            try {
                f.registry.connect()
                runCurrent()
                assertTrue(f.missing("a"))
                assertFalse(f.missing("b"))
                val old = f.a.transports.single()
                f.a.capable = true
                f.source.retryHost("a")
                runCurrent()
                assertTrue(old.closed)
                assertEquals(2, f.a.transports.size)
                assertEquals(1, f.b.transports.size)
                assertFalse(f.missing("a"))
                assertFalse(
                    f.b.transports
                        .single()
                        .closed,
                )
                // Recovery removes the special redial path; retry on a healthy connection is a no-op.
                f.source.retryHost("a")
                runCurrent()
                assertEquals(2, f.a.transports.size)
                // Ordinary supervision still handles the next failure, and a named retry collapses backoff.
                f.a.transports
                    .last()
                    .down()
                runCurrent()
                assertFalse(f.missing("a"))
                f.source.retryHost("a")
                runCurrent()
                assertEquals(3, f.a.transports.size)
                assertEquals(1, f.b.transports.size)
            } finally {
                f.close()
            }
        }

    @Test fun backgroundRemovalReplacementAndDisposalRejectStaleHostRetriesAndVerdicts() =
        runTest {
            val f = Fixture(this)
            try {
                f.registry.connect()
                runCurrent()
                val old = requireNotNull(f.registry.connectionFor("a"))
                f.registry.close()
                runCurrent()
                assertTrue(f.missing("a"))
                f.registry.retryHost("a", old)
                runCurrent()
                assertEquals(1, f.a.transports.size)
                f.store.remove("a")
                runCurrent()
                assertEquals(
                    listOf("b"),
                    f.source.snapshots.value
                        .map { it.serverId },
                )
                val replacement = f.owner("a", capable = true)
                f.current["a"] = replacement
                f.store.save(replacement.record)
                runCurrent()
                f.registry.connect()
                runCurrent()
                assertFalse(f.missing("a"))
                // Even a late ack from the old pump cannot change the replacement's host row.
                f.a.pumps
                    .last()
                    .state.value = PumpState.Open("late", emptySet())
                runCurrent()
                assertFalse(f.missing("a"))
                f.registry.retryHost("a", old)
                runCurrent()
                assertEquals(1, replacement.transports.size)
                f.registry.dispose()
                f.registry.retryHost("a", replacement.bundle)
                runCurrent()
                assertEquals(1, replacement.transports.size)
                assertTrue(
                    f.source.snapshots.value
                        .isEmpty(),
                )
            } finally {
                f.close()
            }
        }

    private class Fixture(
        val scope: TestScope,
    ) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val a = owner("a", capable = false)
        val b = owner("b", capable = true)
        val current = mutableMapOf("a" to a, "b" to b)
        private val records = mutableListOf(PairedServerEntry(a.record), PairedServerEntry(b.record))
        val store =
            ObservablePairedServerStore(
                object : PairedServerCollectionStore {
                    override suspend fun load() = records.lastOrNull()?.record

                    override suspend fun list() = records.toList()

                    override suspend fun loadById(serverId: String) = records.firstOrNull { it.record.serverId == serverId }

                    override suspend fun save(record: PairedServer) {
                        records.removeAll { it.record.serverId == record.serverId }
                        records += PairedServerEntry(record)
                    }

                    override suspend fun remove(serverId: String) {
                        records.removeAll { it.record.serverId == serverId }
                    }

                    override suspend fun setDisplayName(
                        serverId: String,
                        displayName: String?,
                    ) = Unit
                },
            ) {}
        val registry = RelayConnectionRegistry(store, { requireNotNull(current[it.serverId]).bundle }, dispatcher)
        val source = HostConversationSource.relay(registry, dispatcher)

        fun owner(
            id: String,
            capable: Boolean,
        ) = Owner(id, capable, dispatcher)

        fun missing(id: String) =
            source.snapshots.value
                .single { it.serverId == id }
                .threadMissing

        fun close() {
            registry.dispose()
            source.dispose()
        }
    }

    private class Owner(
        id: String,
        var capable: Boolean,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ) {
        val record = PairedServer(id, "token", "wss://example.test", "key")
        val transports = mutableListOf<Transport>()
        val pumps = mutableListOf<Pump>()
        val bundle =
            RelayConnectionBundle(
                deviceStaticKeyStore = object : DeviceStaticKeyStore {
                    override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair = error("fake pump uses no keys")
                    override suspend fun publicKey(serverId: String): ByteArray? = null
                },
                transportFactory = RelayTransportFactory { Transport().also { transports += it } },
                clientInfo = NoiseClientInfo("test", "test"),
                pairedServerStore = object : PairedServerStore {
                    override suspend fun load() = record
                    override suspend fun save(record: PairedServer) = Unit
                },
                pushTokens = flowOf(null),
                dispatcher = dispatcher,
                ioDispatcher = dispatcher,
                messageTrail = MessageTrail(),
                pumpFactory = RelaySessionPumpFactory.noise,
                createCoordinator = { supervisor ->
                    RelayRepositoryCoordinator(
                        supervisor.currentConnection,
                        supervisor.relayStatus,
                        {
                            val capabilities =
                                if (capable) setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) else setOf(CAPABILITY_INTERACTIVE)
                            Pump(capabilities).also { pumps += it }
                        },
                        dispatcher,
                        authoritativeHostId = id,
                    )
                },
            )
    }

    private class Pump(
        capabilities: Set<String>,
    ) : ManagedSessionPump {
        override val state = MutableStateFlow<PumpState>(PumpState.Open("conn", capabilities))
        private val channel = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound: Flow<Envelope> = channel.receiveAsFlow()

        override fun start() = Unit

        override fun send(envelope: Envelope) = state.value is PumpState.Open

        override fun close() {
            channel.close()
        }
    }

    private class Transport : RelayTransport {
        private val links = Channel<TransportEvent>(Channel.UNLIMITED)
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events = links.receiveAsFlow()
        var closed = false

        override fun connect() {
            links.trySend(TransportEvent.Up)
        }

        override fun send(frame: InnerFrameV2) = !closed

        fun down() {
            links.trySend(TransportEvent.Down(1006, null, null))
            links.close()
        }

        override fun close() {
            closed = true
            links.close()
        }
    }
}

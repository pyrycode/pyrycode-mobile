package de.pyryco.mobile.di

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.network.base64StdDecode
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.KoinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectionFactoryTest {
    private val previousSink = RelayLog.sink
    private val previousEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = previousSink
        RelayLog.enabled = previousEnabled
    }

    @Test
    fun explicitRecordsKeepDialHandshakeAndRekeyIdentityAfterLatestSaveChanges() =
        runTest {
            val f = Fixture(this)
            val a = f.factory.create(f.a.record)
            val b = f.factory.create(f.b.record)
            try {
                a.supervisor.connect()
                b.supervisor.connect()
                runCurrent()
                val ta = f.transports[0]
                val tb = f.transports[1]
                assertEquals(listOf(f.a.record, f.b.record), f.transports.map { it.record })
                assertEquals(f.a.record.token, ta.helloToken())
                assertEquals(f.b.record.token, tb.helloToken())
                assertEquals(listOf("A", "B"), f.keys.reads)
                assertFalse(
                    ta.hello!!
                        .payload.jsonObject
                        .containsKey("last_event_id"),
                )
                assertFalse(
                    tb.hello!!
                        .payload.jsonObject
                        .containsKey("last_event_id"),
                )

                f.store.save(f.b.record.copy(serverId = "replacement", token = "replacement-secret"))
                ta.emit(envelope("rekey_request", "{}"))
                tb.emit(envelope("rekey_request", "{}"))
                runCurrent()
                assertEquals(listOf("A", "B", "A", "B"), f.keys.reads)
                assertEquals(2, ta.handshakes)
                assertEquals(2, tb.handshakes)
                assertTrue(f.keys.buffers.all { bytes -> bytes.all { it == 0.toByte() } })
                ta.emit(turn(31))
                tb.emit(turn(72))
                runCurrent()
                assertEquals(31L, a.coordinator.replayCursor.latest)
                assertEquals(72L, b.coordinator.replayCursor.latest)
                a.supervisor.close()
                b.supervisor.close()
                runCurrent()
                a.supervisor.connect()
                b.supervisor.connect()
                runCurrent()
                assertEquals(listOf(f.a.record, f.b.record), f.transports.takeLast(2).map { it.record })
                assertEquals(listOf("31", "72"), f.transports.takeLast(2).map { it.cursor() })
                assertEquals(0, f.store.loads)
            } finally {
                a.close()
                b.close()
                runCurrent()
            }
            assertEquals(
                listOf(
                    "event=relay_bundle_created",
                    "event=relay_bundle_created",
                    "event=relay_bundle_disposed",
                    "event=relay_bundle_disposed",
                ),
                logs,
            )
        }

    @Test
    fun reconnectResetAndDisposalStayInsideOwningBundle() =
        runTest {
            val f = Fixture(this)
            val a = f.factory.create(f.a.record)
            val b = f.factory.create(f.b.record)
            try {
                a.supervisor.connect()
                b.supervisor.connect()
                runCurrent()
                val firstA = f.transports[0]
                val firstB = f.transports[1]
                val repoA = a.coordinator.currentRepository.value
                val repoB = b.coordinator.currentRepository.value
                val statusB = b.coordinator.connectionStatus.value
                assertNotNull(repoA)
                assertNotNull(repoB)
                firstA.emit(turn(101))
                firstB.emit(turn(202))
                runCurrent()
                firstA.close()
                runCurrent()
                assertNull(a.coordinator.currentRepository.value)
                assertEquals(0, firstA.collectors)
                assertSame(repoB, b.coordinator.currentRepository.value)
                assertEquals(statusB, b.coordinator.connectionStatus.value)
                a.supervisor.retry()
                runCurrent()
                val secondA = f.transports.last()
                assertNotSame(firstA, secondA)
                assertNotSame(repoA, a.coordinator.currentRepository.value)
                assertFalse(firstA.initialFrame.contentEquals(secondA.initialFrame))
                assertEquals("101", secondA.cursor())
                secondA.emit(envelope("resync", "{}"))
                runCurrent()
                assertNull(a.coordinator.replayCursor.latest)
                assertEquals(202L, b.coordinator.replayCursor.latest)
                a.supervisor.close()
                runCurrent()
                a.supervisor.connect()
                runCurrent()
                val thirdA = f.transports.last()
                assertNull(thirdA.cursor())
                a.close()
                a.close()
                runCurrent()
                assertTrue(thirdA.closed)
                assertEquals(0, thirdA.collectors)
                assertNull(a.supervisor.currentConnection.value)
                assertSame(repoB, b.coordinator.currentRepository.value)
                assertEquals(statusB, b.coordinator.connectionStatus.value)
                assertFalse(firstB.closed)
                repoB!!.observeConversations(ConversationFilter.Channels).launchIn(backgroundScope)
                runCurrent()
                assertEquals("list_conversations", firstB.outbound.single().type)
                firstB.emit(turn(303))
                runCurrent()
                assertEquals(303L, b.coordinator.replayCursor.latest)
                assertEquals(2, firstB.collectors)
            } finally {
                a.close()
                b.close()
                runCurrent()
            }
            assertTrue(f.transports.all { it.collectors == 0 && it.closed })
        }

    @Test
    fun compatibilityWaitsForPairingAndReadsLatestOnForegroundReconnect() =
        runTest {
            val f = Fixture(this)
            val bundle = f.factory.createCompatibility(f.store)
            val owner =
                object : LifecycleOwner {
                    override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
                }
            val driver = LifecycleConnectionDriver(bundle.supervisor, owner.lifecycle)
            try {
                driver.onStart(owner)
                runCurrent()
                assertTrue(f.transports.isEmpty())
                assertEquals(RelayLinkStatus.Idle, bundle.supervisor.relayStatus.value)
                f.store.save(f.a.record)
                bundle.supervisor.connect()
                runCurrent()
                assertEquals(f.a.record, f.transports.single().record)
                f.transports.single().emit(turn(42))
                runCurrent()
                driver.onStop(owner)
                runCurrent()
                assertNull(bundle.coordinator.currentRepository.value)
                assertTrue(f.transports.single().closed)
                driver.onStart(owner)
                runCurrent()
                assertEquals("42", f.transports.last().cursor())
                f.store.save(f.b.record)
                f.transports.last().close()
                runCurrent()
                bundle.supervisor.retry()
                runCurrent()
                assertEquals(f.b.record, f.transports.last().record)
                assertEquals(f.b.record.token, f.transports.last().helloToken())
                assertEquals(listOf("A", "A", "B"), f.keys.reads)
            } finally {
                bundle.close()
                runCurrent()
            }
        }

    @Test
    fun appModuleAliasesOneCompatibilityBundleAndPreservesBothSelectors() =
        runTest {
            for (useRelay in listOf(false, true)) {
                val f = Fixture(this)
                val overrides =
                    module {
                        single { f.factory }
                        single<PairedServerStore> { f.store }
                    }
                // Load definitions without Android's eager ProcessLifecycleOwner initialization.
                val app = KoinApplication.init().modules(appModule, overrides, conversationRepositoryModule(useRelay))
                try {
                    val koin = app.koin
                    val bundle = koin.get<RelayConnectionBundle>()
                    assertSame(bundle.supervisor, koin.get<RelayConnectionSupervisor>())
                    assertSame(bundle.supervisor, koin.get<RelayConnectionController>())
                    assertSame(bundle.supervisor, koin.get<ConnectionStateSource>())
                    assertSame(bundle.sessionFactory, koin.get<NoiseSessionFactory>())
                    assertSame(bundle.coordinator, koin.get<RelayRepositoryCoordinator>())
                    val selected = koin.get<ConversationRepository>()
                    assertSame(if (useRelay) koin.get<StableConversationRepository>() else koin.get<FakeConversationRepository>(), selected)
                    bundle.supervisor.connect()
                    runCurrent()
                    assertTrue(f.transports.isEmpty())
                    f.store.save(f.a.record)
                    koin.get<RelayConnectionController>().connect()
                    runCurrent()
                    assertNotNull(bundle.coordinator.currentRepository.value)
                } finally {
                    app.close()
                    runCurrent()
                }
                assertTrue(f.transports.all { it.closed && it.collectors == 0 })
            }
        }

    private class Fixture(
        scope: TestScope,
    ) {
        val a = Host("A")
        val b = Host("B")
        val store = LatestStore()
        val keys = Keys()
        val transports = mutableListOf<PeerTransport>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val factory =
            RelayConnectionFactory(
                keys,
                RelayTransportFactory { record ->
                    PeerTransport(record, if (record.serverId == "A") a else b, keys).also { transports += it }
                },
                NoiseClientInfo("test-device", "test-version"),
                dispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
    }

    private class LatestStore : PairedServerStore {
        private var latest: PairedServer? = null
        var loads = 0

        override suspend fun load(): PairedServer? {
            loads++
            return latest
        }

        override suspend fun save(record: PairedServer) {
            latest = record
        }
    }

    private class Host(
        id: String,
    ) {
        val key = newKey()
        val record = PairedServer(id, "secret-$id", "wss://shared.example", base64StdEncode(key.publicKey))
    }

    private class Keys : DeviceStaticKeyStore {
        private val stored = mapOf("A" to newKey(), "B" to newKey())
        val reads = mutableListOf<String>()
        val buffers = mutableListOf<ByteArray>()

        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair {
            reads += serverId
            val key = stored.getValue(serverId)
            return DeviceStaticKeyPair(key.publicKey.copyOf(), key.privateKey.copyOf().also { buffers += it })
        }

        override suspend fun publicKey(serverId: String): ByteArray = stored.getValue(serverId).publicKey.copyOf()

        fun expectedPublicKey(serverId: String): ByteArray = stored.getValue(serverId).publicKey
    }

    private class PeerTransport(
        val record: PairedServer,
        private val host: Host,
        private val keys: Keys,
    ) : RelayTransport {
        private val frames = Channel<InnerFrameV2>(Channel.UNLIMITED)
        private val links = Channel<TransportEvent>(Channel.UNLIMITED)
        private var pair: CipherStatePair? = null
        var collectors = 0
        var closed = false
        var handshakes = 0
        var hello: Envelope? = null
        val outbound = mutableListOf<Envelope>()
        var initialFrame = ByteArray(0)
        override val inbound = tracked(frames)
        override val events = tracked(links)

        private fun <T> tracked(channel: Channel<T>): Flow<T> =
            flow {
                collectors++
                try {
                    channel.receiveAsFlow().collect { emit(it) }
                } finally {
                    collectors--
                }
            }

        override fun connect() {
            links.trySend(TransportEvent.Up)
        }

        override fun close() {
            if (closed) return
            closed = true
            links.trySend(TransportEvent.Down(1000, null, null))
            links.close()
            frames.close()
            pair?.destroy()
        }

        override fun send(frame: InnerFrameV2): Boolean {
            check(!closed)
            if (frame.type == "noise_msg") {
                val ciphertext = base64StdDecode(frame.data)
                val plaintext = ByteArray(ciphertext.size)
                val n = pair!!.receiver.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)
                outbound += MobileJson.decodeFromString<Envelope>(plaintext.copyOf(n).decodeToString())
                return true
            }
            assertEquals("noise_init", frame.type)
            val handshake = HandshakeState(PROTO, HandshakeState.RESPONDER)
            handshake.localKeyPair.setPrivateKey(host.key.privateKey, 0)
            handshake.start()
            val init = base64StdDecode(frame.data)
            val plaintext = ByteArray(init.size)
            val count = handshake.readMessage(init, 0, init.size, plaintext, 0)
            val remote = ByteArray(32).also { handshake.remotePublicKey.getPublicKey(it, 0) }
            assertArrayEquals(keys.expectedPublicKey(record.serverId), remote)
            val ack =
                if (handshakes++ == 0) {
                    initialFrame = init
                    hello = MobileJson.decodeFromString<Envelope>(plaintext.copyOf(count).decodeToString())
                    MobileJson
                        .encodeToString(
                            envelope(
                                "hello_ack",
                                """{"protocol_version":"v2","server_id":"${record.serverId}","conn_id":"connection","capabilities":["interactive"]}""",
                            ),
                        ).encodeToByteArray()
                } else {
                    assertEquals(0, count)
                    ByteArray(0)
                }
            val out = ByteArray(ack.size + 96)
            val n = handshake.writeMessage(out, 0, ack, 0, ack.size)
            pair?.destroy()
            pair = handshake.split()
            handshake.destroy()
            frames.trySend(InnerFrameV2(type = "noise_resp", data = base64StdEncode(out.copyOf(n))))
            return true
        }

        fun emit(env: Envelope) {
            val plaintext = MobileJson.encodeToString(env).encodeToByteArray()
            val ciphertext = ByteArray(plaintext.size + 16)
            val n = pair!!.sender.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
            frames.trySend(InnerFrameV2(type = "noise_msg", data = base64StdEncode(ciphertext.copyOf(n))))
        }

        fun helloToken() =
            hello!!
                .payload.jsonObject
                .getValue("token")
                .jsonPrimitive.content

        fun cursor() =
            hello!!
                .payload.jsonObject["last_event_id"]
                ?.jsonPrimitive
                ?.content
    }

    private companion object {
        const val PROTO = "Noise_IK_25519_ChaChaPoly_BLAKE2s"

        fun newKey(): DeviceStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            return DeviceStaticKeyPair(
                ByteArray(32).also {
                    dh.getPublicKey(it, 0)
                },
                ByteArray(32).also { dh.getPrivateKey(it, 0) },
            ).also { dh.destroy() }
        }

        fun envelope(
            type: String,
            payload: String,
        ) = Envelope(1, type, "2026-09-20T00:00:00Z", MobileJson.parseToJsonElement(payload))

        fun turn(id: Long) = envelope("turn_state", """{"conversation_id":"c","state":"thinking"}""").copy(eventId = id)
    }
}

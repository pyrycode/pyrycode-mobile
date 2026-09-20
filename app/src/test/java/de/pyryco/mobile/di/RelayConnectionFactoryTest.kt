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
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
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
import de.pyryco.mobile.data.repository.DebugBundleStatus
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.koin.dsl.binds
import org.koin.dsl.module
import java.io.ByteArrayOutputStream

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
                assertEquals(0, f.rawStore.loads)
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
    fun appModuleTracksLatestSurvivorWithoutReplacingStableConsumers() =
        runTest {
            for (useRelay in listOf(false, true)) {
                val f = Fixture(this)
                val registry = f.registry()
                val overrides =
                    module {
                        single { f.store } binds arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
                        single { registry }
                    }
                val app = KoinApplication.init().modules(appModule, overrides, conversationRepositoryModule(useRelay))
                try {
                    val koin = app.koin
                    val stable = koin.get<StableConversationRepository>()
                    val selected = koin.get<ConversationRepository>()
                    assertSame(if (useRelay) stable else koin.get<FakeConversationRepository>(), selected)
                    assertSame(registry, koin.get<RelayConnectionController>())
                    assertSame(registry, koin.get<ConnectionStateSource>())
                    assertSame(f.store, koin.get<PairedServerCollectionStore>())
                    registry.connect()
                    runCurrent()
                    assertTrue(f.transports.isEmpty())
                    assertTrue(stable.observeConversations(ConversationFilter.Channels).first().isEmpty())
                    assertEquals(ModalUiState.Hidden, registry.currentModal.value)
                    f.store.save(f.a.record)
                    runCurrent()
                    val a = registry.connectionFor("A")!!
                    assertSame(a, koin.get<RelayConnectionBundle>())
                    assertSame(a.supervisor, koin.get<RelayConnectionSupervisor>())
                    assertSame(a.sessionFactory, koin.get<NoiseSessionFactory>())
                    assertSame(a.coordinator, koin.get<RelayRepositoryCoordinator>())
                    val events = mutableListOf<LiveSessionEvent>()
                    registry.liveSessionEvents.onEach { events += it }.launchIn(backgroundScope)
                    f.store.save(f.b.record)
                    runCurrent()
                    val b = registry.connectionFor("B")!!
                    assertSame(b.coordinator.currentRepository.value, registry.currentRepository.value)
                    assertSame(b.coordinator, koin.get<RelayRepositoryCoordinator>())
                    assertSame(stable, koin.get<StableConversationRepository>())
                    val ta = f.transports[0]
                    val tb = f.transports[1]
                    ta.emit(turn(11))
                    tb.emit(turn(22))
                    ta.emit(modal("A"))
                    tb.emit(modal("B"))
                    runCurrent()
                    assertEquals(1, events.size)
                    assertEquals(b.coordinator.currentModal.value, registry.currentModal.value)
                    assertEquals(b.coordinator.connectionStatus.value, registry.connectionStatus.value)
                    stable.startNewSession("c")
                    registry.interrupt("c")
                    val cancel = async { registry.cancelModal("same") }
                    runCurrent()
                    assertEquals(listOf("new_session", "interrupt", "modal_cancel"), tb.outbound.map { it.type })
                    tb.emit(envelope("ack", "{}").copy(inReplyTo = tb.outbound.last().id))
                    runCurrent()
                    cancel.await()
                    assertTrue(ta.outbound.isEmpty())
                    f.store.remove("B")
                    runCurrent()
                    assertSame(a, registry.selected.value)
                    assertEquals(a.coordinator.currentModal.value, registry.currentModal.value)
                    assertEquals(2, f.transports.size)
                    f.store.remove("A")
                    runCurrent()
                    assertNull(registry.selected.value)
                    assertNull(registry.currentRepository.value)
                    assertEquals(ModalUiState.Hidden, registry.currentModal.value)
                    assertEquals(RelayLinkStatus.Idle, registry.connectionStatus.value.relay)
                    assertTrue(runCatching { registry.interrupt("c") }.exceptionOrNull() is IllegalStateException)
                } finally {
                    app.close()
                    registry.dispose()
                    runCurrent()
                }
                assertTrue(f.transports.all { it.closed && it.collectors == 0 })
            }
        }

    @Test
    fun registryReconcilesCredentialsButRetainsNamesAndIdenticalRecords() =
        runTest {
            val f = Fixture(this)
            f.store.save(f.a.record)
            f.store.save(f.b.record)
            val registry = f.registry()
            try {
                runCurrent()
                assertNotNull(registry.connectionFor("A"))
                assertTrue(f.transports.isEmpty())
                registry.connect()
                registry.connect()
                runCurrent()
                val a = registry.connectionFor("A")!!
                val b = registry.connectionFor("B")!!
                assertEquals(2, f.transports.size)
                assertNull(registry.connectionFor("a"))
                assertNull(registry.connectionFor("missing"))
                f.store.setDisplayName("A", "renamed")
                runCurrent()
                assertSame(b, registry.selected.value)
                val selectionCheck =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        registry.selected.drop(1).take(1).collect { selected ->
                            // A one-shot can run on the selection edge before switched collectors catch up.
                            assertSame(selected!!.coordinator.currentRepository.value, registry.currentRepository.value)
                        }
                    }
                f.store.save(f.a.record)
                runCurrent()
                selectionCheck.join()
                assertSame(a, registry.selected.value)
                assertSame(a, registry.connectionFor("A"))
                assertSame(b, registry.connectionFor("B"))
                assertEquals(2, f.transports.size)
                val old = f.transports[0]
                f.beforeDial = { record -> if (record.serverId == "A") assertTrue(old.closed) }
                f.store.save(f.a.record.copy(token = "rotated"))
                runCurrent()
                assertNotSame(a, registry.connectionFor("A"))
                assertSame(b, registry.connectionFor("B"))
                assertEquals("rotated", f.transports.last().helloToken())
                assertEquals(0, old.collectors)
                f.store.remove("A")
                runCurrent()
                assertNull(registry.connectionFor("A"))
                assertSame(b, registry.selected.value)
                assertFalse(f.transports[1].closed)
            } finally {
                registry.dispose()
                runCurrent()
            }
        }

    @Test
    fun registryKeepsPeerEventsModalCursorAndPendingReplyThroughOtherHostFailure() =
        runTest {
            val f = Fixture(this)
            f.store.save(f.a.record)
            f.store.save(f.b.record)
            val registry = f.registry()
            try {
                registry.connect()
                runCurrent()
                val a = registry.connectionFor("A")!!
                val b = registry.connectionFor("B")!!
                val ta = f.transports[0]
                val tb = f.transports[1]
                val repoB = b.coordinator.currentRepository.value
                val eventsA = mutableListOf<LiveSessionEvent>()
                val eventsB = mutableListOf<LiveSessionEvent>()
                a.coordinator.liveSessionEvents
                    .onEach { eventsA += it }
                    .launchIn(backgroundScope)
                b.coordinator.liveSessionEvents
                    .onEach { eventsB += it }
                    .launchIn(backgroundScope)
                runCurrent()
                ta.emit(turn(7))
                tb.emit(turn(7))
                ta.emit(modal("A"))
                tb.emit(modal("B"))
                runCurrent()
                val modalB = b.coordinator.currentModal.value
                assertEquals("B", (modalB as ModalUiState.Open).title)
                assertEquals("A", (a.coordinator.currentModal.value as ModalUiState.Open).title)
                assertEquals(1, eventsA.size)
                assertEquals(1, eventsB.size)
                val pending = async { registry.answerModal("same", "deny") }
                runCurrent()
                assertFalse(pending.isCompleted)
                ta.close()
                runCurrent()
                assertNull(a.coordinator.currentRepository.value)
                assertSame(repoB, b.coordinator.currentRepository.value)
                assertEquals(modalB, b.coordinator.currentModal.value)
                a.supervisor.retry()
                runCurrent()
                assertEquals("7", f.transports.last().cursor())
                f.store.remove("A")
                runCurrent()
                assertSame(repoB, b.coordinator.currentRepository.value)
                assertEquals(modalB, b.coordinator.currentModal.value)
                assertEquals(7L, b.coordinator.replayCursor.latest)
                assertFalse(tb.closed)
                tb.emit(envelope("ack", "{}").copy(inReplyTo = tb.outbound.single().id))
                runCurrent()
                pending.await()
                tb.emit(turn(8))
                runCurrent()
                assertEquals(2, eventsB.size)
                assertEquals(1, eventsA.size)
            } finally {
                registry.dispose()
                runCurrent()
            }
        }

    @Test
    fun registryBackgroundAndDisposalFencePendingReadsAndPreserveResumeCursor() =
        runTest {
            val f = Fixture(this)
            val gate = CompletableDeferred<Unit>()
            f.rawStore.readGate = gate
            f.store.save(f.a.record)
            val registry = f.registry()
            registry.connect()
            runCurrent()
            registry.close()
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.transports.isEmpty())
            val a = registry.connectionFor("A")!!
            registry.connect()
            runCurrent()
            f.transports.single().emit(turn(41))
            runCurrent()
            registry.close()
            registry.close()
            runCurrent()
            assertTrue(f.transports.single().closed)
            assertEquals(0, f.transports.single().collectors)
            f.store.save(f.b.record)
            runCurrent()
            assertEquals(1, f.transports.size)
            registry.connect()
            registry.connect()
            runCurrent()
            assertSame(a, registry.connectionFor("A"))
            assertEquals("41", f.transports[1].cursor())
            assertFalse(f.transports[0].initialFrame.contentEquals(f.transports[1].initialFrame))
            assertEquals(3, f.transports.size)
            val pending = CompletableDeferred<Unit>()
            f.rawStore.readGate = pending
            f.store.setDisplayName("A", "new")
            runCurrent()
            registry.dispose()
            registry.dispose()
            registry.connect()
            pending.complete(Unit)
            runCurrent()
            f.store.save(f.a.record)
            runCurrent()
            assertNull(registry.connectionFor("A"))
            assertNull(registry.selected.value)
            assertTrue(f.transports.all { it.closed && it.collectors == 0 })
            assertEquals(3, f.transports.size)
            assertEquals(0, f.rawStore.activeReads)
        }

    @Test
    fun unavailableHostDoesNotDelayOtherHostAndFailedSaveDoesNotNotify() =
        runTest {
            val f = Fixture(this)
            f.unavailable = "A"
            f.store.save(f.a.record)
            f.store.save(f.b.record)
            val registry = f.registry()
            try {
                registry.connect()
                runCurrent()
                assertNull(
                    registry
                        .connectionFor("A")!!
                        .coordinator.currentRepository.value,
                )
                assertNotNull(
                    registry
                        .connectionFor("B")!!
                        .coordinator.currentRepository.value,
                )
                val revision = f.store.revision.value
                f.rawStore.failSave = true
                assertTrue(runCatching { f.store.save(f.a.record.copy(token = "bad")) }.isFailure)
                runCurrent()
                assertEquals(revision, f.store.revision.value)
                assertEquals(f.b.record, f.store.load())
                assertEquals(2, f.transports.size)
            } finally {
                registry.dispose()
                runCurrent()
            }
            assertTrue(f.transports.all { it.closed && it.collectors == 0 })
        }

    @Test
    fun debugBundlesStayWithExactHostsAcrossSelectionRemovalAndReconnect() =
        runTest {
            val f = Fixture(this)
            f.interactive = false
            f.store.save(f.a.record)
            f.store.save(f.b.record)
            val registry = f.registry()
            try {
                runCurrent()
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    registry
                        .requestDebugBundle("A")
                        .state.value.status,
                )
                registry.connect()
                runCurrent()
                val a = registry.requestDebugBundle("A")
                val b = registry.requestDebugBundle("B")
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    registry
                        .requestDebugBundle("a")
                        .state.value.status,
                )
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    registry
                        .requestDebugBundle("unknown")
                        .state.value.status,
                )
                assertEquals(
                    DebugBundleStatus.BUSY,
                    registry
                        .requestDebugBundle("A")
                        .state.value.status,
                )
                for (transport in f.transports) {
                    val wire = transport.outboundJson.single().jsonObject
                    assertEquals("request_debug_bundle", wire["type"]!!.jsonPrimitive.content)
                    assertFalse(wire.containsKey("payload"))
                    assertFalse(wire.containsKey("conversation_id"))
                }
                f.store.save(f.a.record)
                runCurrent()
                f.transports[1].emit(envelope("debug_bundle_chunk", """{"seq":0,"data":"Yg=="}"""))
                f.transports[0].emit(envelope("debug_bundle_chunk", """{"seq":0,"data":"YQ=="}"""))
                runCurrent()
                assertEquals(1, a.state.value.acceptedChunks)
                assertEquals(1, b.state.value.acceptedChunks)
                f.store.remove("A")
                runCurrent()
                assertEquals(DebugBundleStatus.DISCONNECTED, a.state.value.status)
                assertNull(a.takeArchive())
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    registry
                        .requestDebugBundle("A")
                        .state.value.status,
                )
                f.transports[1].emit(envelope("debug_bundle_done", """{"total":1}"""))
                runCurrent()
                val output = ByteArrayOutputStream()
                b.takeArchive()!!.writeTo(output)
                assertArrayEquals(byteArrayOf(98), output.toByteArray())
                assertEquals(
                    DebugBundleStatus.RECONNECT_REQUIRED,
                    registry
                        .requestDebugBundle("B")
                        .state.value.status,
                )
                registry.close()
                assertEquals(
                    DebugBundleStatus.UNAVAILABLE,
                    registry
                        .requestDebugBundle("B")
                        .state.value.status,
                )
                runCurrent()
                registry.connect()
                runCurrent()
                val next = f.transports.last()
                assertTrue(next.outbound.isEmpty())
                val retry = registry.requestDebugBundle("B")
                next.emit(envelope("debug_bundle_done", """{"total":0}"""))
                runCurrent()
                assertEquals(DebugBundleStatus.COMPLETE, retry.state.value.status)
                assertEquals(0, retry.takeArchive()!!.sizeBytes)
            } finally {
                registry.dispose()
                runCurrent()
            }
        }

    @Test
    fun hostSnapshotsFollowSavedBundlesAcrossBackgroundRenameAndCredentialRotation() =
        runTest {
            val f = Fixture(this)
            val registry = f.registry()
            val source = HostConversationSource.relay(registry, StandardTestDispatcher(testScheduler))
            try {
                runCurrent()
                assertTrue(source.snapshots.value.isEmpty())
                f.store.save(f.a.record)
                f.store.save(f.b.record)
                runCurrent()
                assertEquals(listOf("A", "B"), source.snapshots.value.map { it.serverId })
                assertNull(source.repositoryFor("A"))
                registry.connect()
                runCurrent()
                val a = registry.connectionFor("A")!!
                val b = registry.connectionFor("B")!!
                assertSame(a.coordinator.currentRepository.value, source.repositoryFor("A"))
                assertSame(b.coordinator.currentRepository.value, source.repositoryFor("B"))
                assertNull(source.repositoryFor("a"))
                assertNull(source.repositoryFor("unknown"))
                assertEquals(listOf("list_conversations"), f.transports[0].outbound.map { it.type })
                assertEquals(listOf("list_conversations"), f.transports[1].outbound.map { it.type })
                val rows =
                    envelope(
                        "conversations",
                        """{"conversations":[{"id":"same","name":"A","is_promoted":true,"cwd":"/same","last_message_ts":"2026-09-01T00:00:00Z","last_used_at":"2026-09-01T00:00:00Z"}]}""",
                    )
                f.transports[0].emit(rows)
                runCurrent()
                val cached = source.snapshots.value[0].channels
                assertEquals("same", cached.single().id)
                assertTrue(
                    source.snapshots.value[1]
                        .channels
                        .isEmpty(),
                )
                f.store.setDisplayName("A", "Local A")
                runCurrent()
                assertEquals("Local A", source.snapshots.value[0].displayName)
                assertSame(a, registry.connectionFor("A"))
                assertEquals(cached, source.snapshots.value[0].channels)
                registry.close()
                assertNull(source.repositoryFor("A"))
                runCurrent()
                assertEquals(cached, source.snapshots.value[0].channels)
                registry.connect()
                runCurrent()
                assertEquals(cached, source.snapshots.value[0].channels)
                f.store.save(f.a.record.copy(token = "rotated-secret"))
                runCurrent()
                assertNotSame(a, registry.connectionFor("A"))
                assertSame(b, registry.connectionFor("B"))
                assertTrue(
                    source.snapshots.value
                        .first { it.serverId == "A" }
                        .channels
                        .isEmpty(),
                )
                f.store.remove("A")
                runCurrent()
                assertNull(source.repositoryFor("A"))
                assertEquals(listOf("B"), source.snapshots.value.map { it.serverId })
                registry.dispose()
                runCurrent()
                assertTrue(source.snapshots.value.isEmpty())
                assertTrue(f.transports.all { it.collectors == 0 })
            } finally {
                source.dispose()
                registry.dispose()
                runCurrent()
            }
        }

    @Test
    fun selectorSharesHostSourceAndKeepsDemoLookupSeparateFromSavedRelayHosts() =
        runTest {
            for (useRelay in listOf(false, true)) {
                val f = Fixture(this)
                f.store.save(f.a.record)
                val registry = f.registry()
                val app =
                    KoinApplication.init().modules(
                        appModule,
                        module { single { registry } },
                        conversationRepositoryModule(useRelay),
                    )
                val source = app.koin.get<HostConversationSource>()
                try {
                    assertSame(source, app.koin.get<HostConversationSource>())
                    registry.connect()
                    runCurrent()
                    if (useRelay) {
                        assertSame(
                            registry
                                .connectionFor("A")!!
                                .coordinator.currentRepository.value,
                            source.repositoryFor("A"),
                        )
                        assertNull(source.repositoryFor("demo"))
                    } else {
                        assertSame(app.koin.get<FakeConversationRepository>(), source.repositoryFor("demo"))
                        assertNull(source.repositoryFor("A"))
                    }
                } finally {
                    app.close()
                    registry.dispose()
                    runCurrent()
                }
                assertTrue(source.snapshots.value.isEmpty())
                assertNull(source.repositoryFor(if (useRelay) "A" else "demo"))
            }
        }

    @Test
    fun handshakingHostHasStatusButNoExactRepositoryWhileAnotherHostIsLive() =
        runTest {
            val f = Fixture(this)
            f.handshaking = "A"
            f.store.save(f.a.record)
            f.store.save(f.b.record)
            val registry = f.registry()
            val source = HostConversationSource.relay(registry, StandardTestDispatcher(testScheduler))
            try {
                registry.connect()
                runCurrent()
                val a = source.snapshots.value.first { it.serverId == "A" }
                assertEquals(RelayLinkStatus.Connected, a.connectionStatus.relay)
                assertEquals(de.pyryco.mobile.data.model.PyrycodeLinkStatus.Handshaking, a.connectionStatus.pyrycode)
                assertTrue(a.channels.isEmpty())
                assertNull(source.repositoryFor("A"))
                assertNotNull(source.repositoryFor("B"))
            } finally {
                source.dispose()
                registry.dispose()
                runCurrent()
            }
        }

    @Test
    fun repositoryForRejectsRetiredRepositoryAtReconnectTransportEdge() =
        runTest {
            val f = Fixture(this)
            f.store.save(f.a.record)
            val registry = f.registry()
            val source = HostConversationSource.relay(registry, StandardTestDispatcher(testScheduler))
            try {
                registry.connect()
                runCurrent()
                val bundle = registry.connectionFor("A")!!
                val oldRepository = source.repositoryFor("A")
                assertNotNull(oldRepository)
                var reconnectEdges = 0
                val edgeCheck =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        bundle.supervisor.currentConnection.drop(1).collect { transport ->
                            if (transport != null) {
                                reconnectEdges++
                                assertNull(source.repositoryFor("A"))
                            }
                        }
                    }
                f.handshaking = "A"
                registry.close()
                assertNull(source.repositoryFor("A"))
                registry.connect()
                runCurrent()
                assertEquals(1, reconnectEdges)
                assertNull(source.repositoryFor("A"))
                f.transports.last().completeHandshake()
                runCurrent()
                val replacement = source.repositoryFor("A")
                assertNotNull(replacement)
                assertNotSame(oldRepository, replacement)
                assertSame(bundle.coordinator.currentRepository.value, replacement)
                edgeCheck.cancel()
            } finally {
                source.dispose()
                registry.dispose()
                runCurrent()
            }
        }

    private class Fixture(
        scope: TestScope,
    ) {
        val a = Host("A")
        val b = Host("B")
        val rawStore = LatestStore()
        val store = ObservablePairedServerStore(rawStore)
        var beforeDial: (PairedServer) -> Unit = {}
        var unavailable: String? = null
        var handshaking: String? = null
        var interactive = true
        val keys = Keys()
        val transports = mutableListOf<PeerTransport>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val factory =
            RelayConnectionFactory(
                keys,
                RelayTransportFactory { record ->
                    beforeDial(record)
                    PeerTransport(
                        record,
                        if (record.serverId ==
                            "A"
                        ) {
                            a
                        } else {
                            b
                        },
                        keys,
                        record.serverId == unavailable,
                        interactive,
                        record.serverId == handshaking,
                    ).also { transports += it }
                },
                NoiseClientInfo("test-device", "test-version"),
                dispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )

        fun registry() = RelayConnectionRegistry(store, factory, dispatcher)
    }

    private class LatestStore : PairedServerCollectionStore {
        private var entries = emptyList<PairedServerEntry>()
        var loads = 0
        var readGate: CompletableDeferred<Unit>? = null
        var activeReads = 0
        var failSave = false

        override suspend fun load(): PairedServer? {
            loads++
            return entries.lastOrNull()?.record
        }

        override suspend fun list(): List<PairedServerEntry> {
            activeReads++
            try {
                readGate?.await()
                return entries.toList()
            } finally {
                activeReads--
            }
        }

        override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

        override suspend fun save(record: PairedServer) {
            if (failSave) throw PairedServerStoreException("test failure")
            val name = loadById(record.serverId)?.displayName
            entries = entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, name)
        }

        override suspend fun remove(serverId: String) {
            entries = entries.filterNot { it.record.serverId == serverId }
        }

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) {
            entries = entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
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
        private val unavailable: Boolean = false,
        private val interactive: Boolean = true,
        private var holdHandshake: Boolean = false,
    ) : RelayTransport {
        private val frames = Channel<InnerFrameV2>(Channel.UNLIMITED)
        private val links = Channel<TransportEvent>(Channel.UNLIMITED)
        private var pair: CipherStatePair? = null
        var collectors = 0
        var closed = false
        var handshakes = 0
        var hello: Envelope? = null
        val outbound = mutableListOf<Envelope>()
        val outboundJson = mutableListOf<kotlinx.serialization.json.JsonElement>()
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
            if (!unavailable) links.trySend(TransportEvent.Up)
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
                outboundJson += MobileJson.parseToJsonElement(plaintext.copyOf(n).decodeToString())
                outbound += MobileJson.decodeFromString<Envelope>(plaintext.copyOf(n).decodeToString())
                return true
            }
            assertEquals("noise_init", frame.type)
            if (holdHandshake) {
                initialFrame = base64StdDecode(frame.data)
                return true
            }
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
                                """{"protocol_version":"v2","server_id":"${record.serverId}","conn_id":"connection","capabilities":${if (interactive) "[\"interactive\"]" else "[]"}}""",
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

        fun completeHandshake() {
            holdHandshake = false
            send(InnerFrameV2(type = "noise_init", data = base64StdEncode(initialFrame)))
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

        fun modal(title: String) =
            envelope(
                "modal_shown",
                """{"modal_id":"same","class":"permission","title":"$title","prompt":"Allow?","options":[{"id":"deny","label":"Deny"}],"default_option_id":"deny"}""",
            )

        fun turn(id: Long) = envelope("turn_state", """{"conversation_id":"c","state":"thinking"}""").copy(eventId = id)
    }
}

package de.pyryco.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.hostConversationConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Hold coordinator repository publication while its independent live-event switch serves R2. */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AttentionNotifierCoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test
    fun reconnectCompletionPostsWhileRepositoryPublicationStillHoldsTheCoveredPreviousRepository() = reconnect(false)

    @Test
    fun reconnectCompletionPostsWhileRepositoryPublicationIsNullAndPreviousReadFactsAreRetained() = reconnect(true)

    private fun reconnect(disconnectFirst: Boolean) =
        runTest {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connections = MutableStateFlow<RelayTransport?>(null)
            val pumps = mutableListOf<Pump>()
            val coordinator =
                RelayRepositoryCoordinator(
                    connections,
                    MutableStateFlow(RelayLinkStatus.Connected),
                    { Pump(holdPublication = pumps.isNotEmpty()).also { pumps += it } },
                    dispatcher,
                )
            coordinator.start()
            val connection = coordinator.hostConversationConnection("a", null)
            val source = HostConversationSource(MutableStateFlow(listOf(connection)), { null }, dispatcher)
            var publishedRepository: ConversationRepository? = null
            backgroundScope.launch(dispatcher) { coordinator.currentRepository.collect { publishedRepository = it } }
            val triggers = MutableStateFlow<Map<String, Map<String, ConversationReadMarks>>>(emptyMap())
            val notifier =
                AttentionNotifier(
                    app,
                    source.alerts,
                    MutableStateFlow(true),
                    { _, _ -> false },
                    { _, _ -> null },
                    { _, _ -> null },
                    { false },
                    File(folder.root, "alerts"),
                    dispatcher,
                    triggers,
                    source::currentReadMarks,
                )
            try {
                connections.value = Transport()
                runCurrent()
                val first = pumps.single()
                first.open()
                runCurrent()
                first.list(5u, 5u)
                runCurrent()
                val previous = publishedRepository
                assertTrue(previous != null)
                assertEquals(ConversationReadMarks(5u, 5u), source.currentReadMarks("a", "same"))
                if (disconnectFirst) {
                    connections.value = null
                    runCurrent()
                    assertEquals(null, publishedRepository)
                }
                connections.value = Transport()
                runCurrent()
                val second = pumps.last()
                second.open()
                second.list(0u, 6u)
                second.end("unread-on-reconnect", 6u)
                runCurrent()
                // Actual stateIn/flatMapLatest publication is still held, not a replaced repository flow.
                assertSame(if (disconnectFirst) null else previous, publishedRepository)
                assertEquals(1, shadowOf(manager).allNotifications.size)
                assertEquals(ConversationReadMarks(0u, 6u), source.currentReadMarks("a", "same"))
                assertNotSame(previous, coordinator.currentRepository.value)

                // A covered trigger from R1 must recheck R2, even before R2's facts can publish.
                triggers.value = mapOf("a" to mapOf("same" to ConversationReadMarks(5u, 5u)))
                runCurrent()
                assertEquals(1, shadowOf(manager).allNotifications.size)
                second.publication.complete(Unit)
                runCurrent()
                assertSame(coordinator.currentRepository.value, publishedRepository)
                second.list(6u, 6u)
                runCurrent()
                triggers.value = source.readMarks.value
                runCurrent()
                assertTrue(shadowOf(manager).allNotifications.isEmpty())
            } finally {
                notifier.dispose()
                source.dispose()
                coordinator.close()
            }
        }

    private class Pump(
        holdPublication: Boolean,
    ) : ManagedSessionPump {
        private val input = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = input.receiveAsFlow()
        private val mutableState = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val publication = CompletableDeferred<Unit>().apply { if (!holdPublication) complete(Unit) }
        override val state: StateFlow<PumpState> =
            object : StateFlow<PumpState> by mutableState {
                override suspend fun collect(collector: FlowCollector<PumpState>): Nothing {
                    publication.await()
                    mutableState.collect(collector)
                }
            }
        private var id = 1L

        override fun start() = Unit

        override fun close() {
            input.close()
        }

        override fun send(envelope: Envelope): Boolean {
            if (mutableState.value !is PumpState.Open) return false
            if (envelope.type == "request_history") {
                input.trySend(
                    Envelope(
                        id++,
                        "history_page",
                        TS,
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"same","entries":[],"cursor":"","at_start":true}""",
                        ),
                        inReplyTo = envelope.id,
                    ),
                )
            }
            return true
        }

        fun open() {
            mutableState.value = PumpState.Open("test", setOf(CAPABILITY_INTERACTIVE))
        }

        fun list(
            read: ULong,
            latest: ULong,
        ) = push(
            "conversations",
            """{"conversations":[{"id":"same","name":"test","is_promoted":true,"cwd":"/test","last_message_ts":"$TS","last_used_at":"$TS","read_up_to":$read,"latest_entry_id":$latest}]}""",
        )

        fun end(
            turn: String,
            entry: ULong,
        ) = push(
            "turn_end",
            """{"conversation_id":"same","turn_id":"$turn","stop_reason":"end_turn"}""",
            entry,
        )

        private fun push(
            type: String,
            payload: String,
            entry: ULong? = null,
        ) {
            input.trySend(Envelope(id++, type, TS, MobileJson.parseToJsonElement(payload), historyEntryId = entry))
        }
    }

    private class Transport : RelayTransport {
        override val inbound = emptyFlow<InnerFrameV2>()
        override val events = emptyFlow<TransportEvent>()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2) = true

        override fun close() = Unit
    }

    private companion object {
        const val TS = "2026-10-08T00:00:00Z"
    }
}

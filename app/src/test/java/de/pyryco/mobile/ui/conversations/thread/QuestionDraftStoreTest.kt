package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class QuestionDraftStoreTest {
    private val enabled = RelayLog.enabled

    @Before fun silenceLogs() {
        RelayLog.enabled = false
    }

    @After fun restoreLogs() {
        RelayLog.enabled = enabled
    }

    private val batch =
        QuestionBatch("chat", "request", listOf(Question("Question", "Header", listOf(QuestionOption("Choice", "Detail")), false)))

    @Test
    fun navigation_keeps_picks_and_owners_are_isolated() {
        val store = QuestionDraftStore(Dispatchers.Unconfined)
        store.reconcileHost("host", listOf(batch, batch.copy(conversationId = "other")))
        store.reconcileHost("peer", listOf(batch))
        val held = checkNotNull(store.current("host", "chat"))
        store.update("host", "chat", held.generation) {
            it.copy(selections = listOf(QuestionSelection(otherTicked = true, otherText = " verbatim ")))
        }
        store.reconcileHost("host", listOf(batch, batch.copy(conversationId = "other")))
        assertEquals(
            " verbatim ",
            store
                .current("host", "chat")
                ?.selections
                ?.single()
                ?.otherText,
        )
        assertEquals(
            "",
            store
                .current("peer", "chat")
                ?.selections
                ?.single()
                ?.otherText,
        )
        assertEquals(
            "",
            store
                .current("host", "other")
                ?.selections
                ?.single()
                ?.otherText,
        )
        store.dispose()
    }

    @Test
    fun dismissal_replacement_and_reconnect_invalidate_stale_callbacks_even_with_same_id() {
        val store = QuestionDraftStore(Dispatchers.Unconfined)
        store.reconcileHost("host", listOf(batch))
        val old = checkNotNull(store.current("host", "chat"))
        store.reconcileHost("host", emptyList())
        assertNull(store.current("host", "chat"))
        store.reconcileHost("host", listOf(batch))
        val rebuilt = checkNotNull(store.current("host", "chat"))
        assertNotEquals(old.generation, rebuilt.generation)
        store.update("host", "chat", old.generation) { it.copy(phase = QuestionSendPhase.Sent) }
        assertEquals(QuestionSendPhase.Idle, store.current("host", "chat")?.phase)
        store.reconcileHost("host", listOf(batch.copy(questions = batch.questions.map { it.copy(question = "Replacement") })))
        assertNotEquals(rebuilt.generation, store.current("host", "chat")?.generation)
        store.clearHost("host")
        store.reconcileHost("host", listOf(batch))
        assertNotEquals(rebuilt.generation, store.current("host", "chat")?.generation)
        store.dispose()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun host_observation_keeps_invalidating_without_a_screen_and_reconnect_does_not_need_a_null_tick() =
        runTest {
            val store = QuestionDraftStore(StandardTestDispatcher(testScheduler))
            val inbound =
                Channel<Envelope>(
                    Channel.UNLIMITED,
                )
            val pump =
                object : SessionPump {
                    override val inbound = inbound.receiveAsFlow()

                    override fun send(envelope: Envelope) = true
                }
            val first =
                RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = {
                    setOf("interactive")
                })
            val repositories = MutableStateFlow<ConversationRepository?>(first)
            val owner = Any()
            store.bind("host", owner, repositories)
            val shown =
                Envelope(
                    1,
                    "question_shown",
                    "2026-09-30T00:00:00Z",
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"chat","question_batch_id":"request","questions":[{"question":"Q","header":"H","options":[{"label":"A","description":"B"}],"multi_select":false}]}""",
                    ),
                )
            inbound.send(shown)
            runCurrent()
            val held = checkNotNull(store.current("host", "chat"))
            store.update("host", "chat", held.generation) { it.copy(selections = listOf(QuestionSelection(otherText = "retained"))) }
            inbound.send(
                Envelope(
                    2,
                    "question_dismissed",
                    "2026-09-30T00:00:00Z",
                    MobileJson.parseToJsonElement(
                        """{"question_batch_id":"request","outcome":"answered","source":"remote"}""",
                    ),
                ),
            )
            runCurrent()
            assertNull(store.current("host", "chat"))
            inbound.send(shown)
            runCurrent()
            val rebuilt = checkNotNull(store.current("host", "chat"))
            assertEquals("", rebuilt.selections.single().otherText)
            store.update(
                "host",
                "chat",
                rebuilt.generation,
            ) { it.copy(selections = listOf(QuestionSelection(otherText = "must not revive"))) }
            val nextInbound =
                Channel<Envelope>(
                    Channel.UNLIMITED,
                )
            val nextPump =
                object : SessionPump {
                    override val inbound = nextInbound.receiveAsFlow()

                    override fun send(envelope: Envelope) = true
                }
            val next =
                RemoteConversationRepository(nextPump, backgroundScope, negotiatedCapabilities = {
                    setOf("interactive")
                })
            nextInbound.send(shown)
            runCurrent()
            repositories.value = next
            runCurrent()
            val nextHeld = checkNotNull(store.current("host", "chat"))
            assertEquals(rebuilt.batch, nextHeld.batch)
            assertNotEquals(rebuilt.generation, nextHeld.generation)
            assertEquals(QuestionSelection(), nextHeld.selections.single())
            store.dispose()
        }
}

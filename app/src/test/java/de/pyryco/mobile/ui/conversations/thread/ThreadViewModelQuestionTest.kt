package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #661: the clarification-question modal's view-model half. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelQuestionTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    private val batches = MutableStateFlow<QuestionBatch?>(null)
    private val answers = mutableListOf<Pair<String, List<QuestionAnswer>>>()
    private val refusals = mutableListOf<String>()
    private val modalAnswers = mutableListOf<String>()
    private var gate: CompletableDeferred<Unit>? = null
    private var failure: Throwable? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    private fun vm(
        repository: ConversationRepository = FakeConversationRepository(),
        questions: QuestionDraftStore? = null,
    ): ThreadViewModel =
        ThreadViewModel(
            SavedStateHandle(mapOf("conversationId" to CONV)),
            repository,
            FakeConnectionStateSource(),
            ComposerDraftStore(),
            questionDraftStore = questions,
            answerModal = { _, option, _ -> modalAnswers += option },
            questionBatch = { id -> if (id == CONV) batches else MutableStateFlow(null) },
            answerQuestionBatch = { id, values ->
                gate?.await()
                failure?.let { throw it }
                answers += id to values
            },
            refuseQuestionBatch = { id ->
                gate?.await()
                failure?.let { throw it }
                refusals += id
            },
        )

    private fun batch(
        id: String = "batch-1",
        conversationId: String = CONV,
    ) = QuestionBatch(
        conversationId = conversationId,
        questionBatchId = id,
        questions =
            listOf(
                Question("Which language?", "Language", listOf(QuestionOption("Kotlin", "JVM"), QuestionOption("Rust", "Native")), false),
                Question(
                    "Which targets?",
                    "Targets",
                    listOf(QuestionOption("Android", "Phone"), QuestionOption("Desktop", "Laptop")),
                    true,
                ),
            ),
    )

    private fun ThreadViewModel.state() = checkNotNull(questionModal.value)

    private fun ThreadViewModel.answerBoth() {
        onQuestionEvent(QuestionModalEvent.OptionToggled(0, 1))
        onQuestionEvent(QuestionModalEvent.OptionToggled(1, 0))
    }

    @Test
    fun continue_is_enabled_only_when_every_question_has_an_answer() =
        runTest {
            val vm = vm()
            batches.value = batch()
            assertFalse(vm.state().canContinue)
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            assertFalse(vm.state().canContinue)
            vm.onQuestionEvent(QuestionModalEvent.OtherToggled(1))
            assertFalse("ticked blank Other is no answer", vm.state().canContinue)
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(1, "   "))
            assertFalse(vm.state().canContinue)
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(1, " Web "))
            assertTrue(vm.state().canContinue)
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(listOf("batch-1" to listOf(QuestionAnswer(0, listOf("Kotlin")), QuestionAnswer(1, listOf(" Web ")))), answers)
        }

    @Test
    fun single_choice_other_clears_the_pick_and_multi_choice_keeps_both() =
        runTest {
            val vm = vm()
            batches.value = batch()
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(0, "Go"))
            assertEquals(QuestionSelection(emptySet(), otherTicked = true, otherText = "Go"), vm.state().selections[0])
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 1))
            assertEquals(QuestionSelection(setOf(1), otherTicked = false, otherText = "Go"), vm.state().selections[0])
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(1, 1))
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(1, 0))
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(1, "Web"))
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(
                listOf(QuestionAnswer(0, listOf("Rust")), QuestionAnswer(1, listOf("Android", "Desktop", "Web"))),
                answers.single().second,
            )
            assertTrue("a question answer never answers the permission modal", modalAnswers.isEmpty())
        }

    @Test
    fun one_send_while_in_flight_and_after_success() =
        runTest {
            gate = CompletableDeferred()
            val vm = vm()
            batches.value = batch()
            vm.answerBoth()
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(QuestionSendPhase.Sending, vm.state().phase)
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            checkNotNull(gate).complete(Unit)
            assertEquals(QuestionSendPhase.Sent, vm.state().phase)
            assertEquals(setOf(1), vm.state().selections[0].optionIndices)
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            assertEquals(1, answers.size)
            assertTrue(refusals.isEmpty())
            assertTrue(logs.contains("event=question_send kind=answer outcome=sent"))
        }

    @Test
    fun failed_send_keeps_selections_and_allows_retry() =
        runTest {
            failure = IllegalStateException("no active connection")
            val vm = vm()
            batches.value = batch()
            vm.answerBoth()
            val before = vm.state().selections
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(QuestionSendPhase.Failed, vm.state().phase)
            assertEquals(before, vm.state().selections)
            assertTrue(vm.state().canContinue)
            failure = null
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(QuestionSendPhase.Sent, vm.state().phase)
            assertEquals(1, answers.size)
            assertTrue(logs.none { "no active connection" in it })
        }

    @Test
    fun cancel_refuses_once_and_a_failure_can_retry() =
        runTest {
            failure = IllegalArgumentException("x")
            val vm = vm()
            batches.value = batch()
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            assertEquals(QuestionSendPhase.Failed, vm.state().phase)
            failure = null
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            assertEquals(listOf("batch-1"), refusals)
            assertTrue(answers.isEmpty())
        }

    @Test
    fun dismissal_and_replacement_discard_selections() =
        runTest {
            val vm = vm()
            batches.value = batch()
            vm.answerBoth()
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            batches.value = null
            assertNull(vm.questionModal.value)
            // A reconnect re-sends the same batch after the empty reconcile: it starts fresh.
            batches.value = batch()
            assertEquals(QuestionSendPhase.Idle, vm.state().phase)
            assertTrue(vm.state().selections.all { it == QuestionSelection() })
            vm.answerBoth()
            batches.value = batch(id = "batch-2")
            assertEquals("batch-2", vm.state().batch.questionBatchId)
            assertTrue(vm.state().selections.all { it == QuestionSelection() })
        }

    @Test
    fun late_completion_never_touches_a_newer_batch() =
        runTest {
            gate = CompletableDeferred()
            val vm = vm()
            batches.value = batch()
            vm.answerBoth()
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            batches.value = batch(id = "batch-2")
            checkNotNull(gate).complete(Unit)
            assertEquals(QuestionSendPhase.Idle, vm.state().phase)
            assertEquals(listOf("batch-1"), answers.map { it.first })
        }

    @Test
    fun another_conversations_batch_never_shows_or_receives_answers() =
        runTest {
            val vm = vm()
            batches.value = batch(conversationId = "other")
            assertNull(vm.questionModal.value)
            vm.onQuestionEvent(QuestionModalEvent.Cancel)
            assertTrue(refusals.isEmpty())
        }

    @Test
    fun a_codex_conversations_batch_names_codex_and_keeps_its_picks_when_the_list_arrives_late() =
        runTest {
            // Like the live projection, the list says nothing until its first response arrives.
            val rows = MutableStateFlow<List<Conversation>?>(null)
            val vm = vm(ListedRepository(rows.filterNotNull()))
            batches.value = batch()
            assertEquals("a cold list reads as today's Claude title", ConversationAgent.Claude, vm.state().agent)
            vm.answerBoth()
            rows.value = listOf(conversation(CONV, ConversationAgent.Codex), conversation("other", ConversationAgent.Claude))
            assertEquals(ConversationAgent.Codex, vm.state().agent)
            assertTrue(vm.state().canContinue)
        }

    @Test
    fun a_claude_or_unlisted_conversations_batch_names_claude() =
        runTest {
            val rows = MutableStateFlow(listOf(conversation("other", ConversationAgent.Codex)))
            val vm = vm(ListedRepository(rows))
            batches.value = batch()
            assertEquals(ConversationAgent.Claude, vm.state().agent)
            rows.value = listOf(conversation(CONV, ConversationAgent.Claude))
            assertEquals(ConversationAgent.Claude, vm.state().agent)
        }

    @Test
    fun edits_share_the_agent_subscription_without_reseeding_codex() =
        runTest {
            var subscriptions = 0
            val rows = MutableStateFlow(listOf(conversation(CONV, ConversationAgent.Codex)))
            val vm = vm(ListedRepository(rows.onStart { subscriptions++ }))
            batches.value = batch()
            val initialSubscriptions = subscriptions
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(0, "draft"))
            vm.answerBoth()
            vm.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(initialSubscriptions, subscriptions)
            assertEquals(ConversationAgent.Codex, vm.state().agent)
        }

    @Test
    fun queued_collector_cannot_send_or_edit_a_rebuilt_equal_request() =
        runTest {
            val outbound = mutableListOf<Envelope>()

            fun source(): RemoteConversationRepository {
                val shown =
                    Envelope(
                        1,
                        "question_shown",
                        "2026-09-30T00:00:00Z",
                        MobileJson.parseToJsonElement(
                            """{"conversation_id":"conv-1","question_batch_id":"request","questions":[{"question":"Q","header":"H","options":[{"label":"A","description":"B"}],"multi_select":false}]}""",
                        ),
                    )
                val pump =
                    object : SessionPump {
                        override val inbound = flowOf(shown)

                        override fun send(envelope: Envelope): Boolean {
                            outbound += envelope
                            return true
                        }
                    }
                return RemoteConversationRepository(
                    pump,
                    backgroundScope +
                        UnconfinedTestDispatcher(
                            testScheduler,
                        ),
                    negotiatedCapabilities = {
                        setOf("interactive")
                    },
                )
            }
            val first = source()
            val second = source()
            val repositories = MutableStateFlow<ConversationRepository?>(first)
            val questions = QuestionDraftStore(StandardTestDispatcher(testScheduler))
            questions.bind("", Any(), repositories, submit = { origin, request, values ->
                assertSame(repositories.value, origin)
                if (values ==
                    null
                ) {
                    origin.refuseQuestionBatch(request.questionBatchId)
                } else {
                    origin.answerQuestionBatch(request.questionBatchId, values)
                }
            })
            val vm = vm(questions = questions)
            runCurrent()
            vm.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            val old = vm.state()
            repositories.value = second
            // The observer has not run: this is the vulnerable interval, not the settled reconnect.
            assertEquals(old, vm.state())
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(0, "stale"), old.generation)
            vm.onQuestionEvent(QuestionModalEvent.Continue, old.generation)
            vm.onQuestionEvent(QuestionModalEvent.Cancel, old.generation)
            assertTrue(answers.isEmpty())
            assertTrue(refusals.isEmpty())
            assertTrue(outbound.isEmpty())
            runCurrent()
            assertEquals(QuestionSelection(), vm.state().selections.single())
            assertTrue(old.generation != vm.state().generation)
            // A send can also be queued after its synchronous lock, while observation is up to date.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val queued = vm(questions = questions)
            runCurrent()
            queued.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            queued.onQuestionEvent(QuestionModalEvent.Continue)
            assertEquals(QuestionSendPhase.Sending, questions.current("", CONV)?.phase)
            repositories.value = source()
            runCurrent()
            assertTrue(answers.isEmpty())
            assertTrue(refusals.isEmpty())
            assertTrue(outbound.isEmpty())
            assertEquals(QuestionSendPhase.Idle, queued.state().phase)
            queued.onQuestionEvent(QuestionModalEvent.OptionToggled(0, 0))
            queued.onQuestionEvent(QuestionModalEvent.Continue)
            runCurrent()
            assertEquals(listOf("question_answer"), outbound.map { it.type })
            repositories.value = source()
            runCurrent()
            queued.onQuestionEvent(QuestionModalEvent.Cancel)
            runCurrent()
            assertEquals(listOf("question_answer", "question_refused"), outbound.map { it.type })
            assertTrue("bound sends must not use the destination's redirecting fallback", answers.isEmpty() && refusals.isEmpty())
            questions.dispose()
        }

    private class ListedRepository(
        private val rows: Flow<List<Conversation>>,
    ) : ConversationRepository by FakeConversationRepository() {
        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = rows
    }

    @Test
    fun popping_and_reopening_retains_only_the_current_process_draft() =
        runTest {
            val questions = QuestionDraftStore()
            questions.reconcileHost("", listOf(batch()))
            val first = vm(questions = questions)
            first.onQuestionEvent(QuestionModalEvent.OtherTextChanged(0, " untouched "))
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("thread", first)
            owner.clear()
            val reopened = vm(questions = questions)
            assertEquals(" untouched ", reopened.state().selections[0].otherText)
            questions.reconcileHost("", emptyList())
            questions.reconcileHost("", listOf(batch()))
            assertEquals("", reopened.state().selections[0].otherText)
            questions.dispose()
        }

    @Test
    fun stale_edits_submissions_and_completions_cannot_touch_a_rebuilt_same_id_request() =
        runTest {
            val vm = vm()
            batches.value = batch()
            vm.answerBoth()
            val old = vm.state().generation
            gate = CompletableDeferred()
            vm.onQuestionEvent(QuestionModalEvent.Continue, old)
            batches.value = null
            batches.value = batch()
            vm.onQuestionEvent(QuestionModalEvent.OtherTextChanged(0, "stale"), old)
            vm.onQuestionEvent(QuestionModalEvent.Continue, old)
            vm.onQuestionEvent(QuestionModalEvent.Cancel, old)
            checkNotNull(gate).complete(Unit)
            assertEquals(QuestionSendPhase.Idle, vm.state().phase)
            assertEquals(QuestionSelection(), vm.state().selections[0])
            assertEquals(1, answers.size)
            assertTrue(refusals.isEmpty())
        }

    @Test
    fun navigation_cancels_a_send_without_stranding_the_retained_draft_locked() =
        runTest {
            val questions = QuestionDraftStore()
            questions.reconcileHost("", listOf(batch()))
            val first = vm(questions = questions)
            first.answerBoth()
            gate = CompletableDeferred()
            first.onQuestionEvent(QuestionModalEvent.Continue)
            val owner = androidx.lifecycle.ViewModelStore()
            owner.put("thread", first)
            owner.clear()
            val reopened = vm(questions = questions)
            assertEquals(QuestionSendPhase.Failed, reopened.state().phase)
            assertTrue(reopened.state().canContinue)
            questions.dispose()
        }

    private companion object {
        const val CONV = "conv-1"

        fun conversation(
            id: String,
            agent: ConversationAgent,
        ) = Conversation(id, null, "~", "s", emptyList(), isPromoted = true, lastUsedAt = Instant.fromEpochSeconds(0), agent = agent)
    }
}

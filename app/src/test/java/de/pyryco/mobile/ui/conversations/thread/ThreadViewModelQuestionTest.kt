package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun vm(): ThreadViewModel =
        ThreadViewModel(
            SavedStateHandle(mapOf("conversationId" to CONV)),
            FakeConversationRepository(),
            FakeConnectionStateSource(),
            ComposerDraftStore(),
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

    private companion object {
        const val CONV = "conv-1"
    }
}

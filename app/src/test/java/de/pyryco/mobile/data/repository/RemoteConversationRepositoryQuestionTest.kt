package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.QuestionPayloadsTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-connection question-batch fold (#822): `question_shown` / `question_dismissed` decoded in
 * [RemoteConversationRepository.onInbound] behind the `interactive` gate and held in
 * [RemoteConversationRepository.questionBatches]. Behaviour mirrors desktop's `reduceQuestionBatches`.
 */
class RemoteConversationRepositoryQuestionTest {
    @Test
    fun questionShown_holdsTheBatch() =
        runTest {
            val (pump, repo) = interactiveRepo()

            pump.push(fixtureEnvelope(QuestionPayloadsTest.QUESTION_SHOWN))
            runCurrent()

            val held = repo.questionBatches.value.single()
            assertEquals("conv-1", held.conversationId)
            assertEquals("qb-7f3a", held.questionBatchId)
            assertEquals(listOf("Write strategy", "Eviction"), held.questions.map { it.header })
        }

    @Test
    fun emptyQuestions_holdsNothingAndDoesNotReplaceAHeldBatch() =
        runTest {
            val (pump, repo) = interactiveRepo()

            pump.push(fixtureEnvelope(QuestionPayloadsTest.QUESTION_SHOWN_EMPTY))
            runCurrent()
            assertTrue(repo.questionBatches.value.isEmpty())

            pump.push(shown("conv-1", "qb-1", header = "Kept"))
            pump.push(shownRaw("""{"conversation_id":"conv-1","question_batch_id":"qb-1","questions":[]}"""))
            runCurrent()

            assertEquals(listOf("Kept"), headers(repo.questionBatches.value))
        }

    @Test
    fun repeatedBatchId_replacesInPlace() =
        runTest {
            val (pump, repo) = interactiveRepo()

            pump.push(shown("conv-1", "qb-1", header = "First"))
            pump.push(shown("conv-2", "qb-2", header = "Other"))
            pump.push(shown("conv-1", "qb-1", header = "Re-sent"))
            runCurrent()

            assertEquals(listOf("qb-1", "qb-2"), repo.questionBatches.value.map { it.questionBatchId })
            assertEquals(listOf("Re-sent", "Other"), headers(repo.questionBatches.value))
        }

    @Test
    fun dismissed_removesMatchingBatchExactlyOnceWhateverSourceAndOutcome() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1"))
            pump.push(shown("conv-2", "qb-2"))
            pump.push(shown("conv-3", "qb-3"))
            runCurrent()

            pump.push(dismissed("qb-1", outcome = "answered", source = "remote"))
            pump.push(dismissed("qb-2", outcome = "something_new", source = "another_device"))
            runCurrent()
            assertEquals(listOf("qb-3"), repo.questionBatches.value.map { it.questionBatchId })

            pump.push(dismissed("qb-1", outcome = "answered", source = "remote"))
            pump.push(dismissed("qb-unknown", outcome = "unanswered", source = "no_answer"))
            runCurrent()
            assertEquals(listOf("qb-3"), repo.questionBatches.value.map { it.questionBatchId })
        }

    @Test
    fun malformedFrame_holdsNothingAndDisturbsNeitherHeldBatchesNorLaterFrames() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1", header = "Held"))
            runCurrent()

            pump.push(
                shownRaw(
                    """{"conversation_id":"conv-1","question_batch_id":"qb-1","questions":[{"question":"Q","header":"Bad","options":[],"multi_select":"true"}]}""",
                ),
            )
            pump.push(shownRaw("""{"conversation_id":"conv-2","questions":[]}"""))
            pump.push(dismissedRaw("""{"question_batch_id":"qb-1"}"""))
            runCurrent()
            assertEquals(listOf("Held"), headers(repo.questionBatches.value))

            pump.push(shown("conv-2", "qb-2", header = "Later"))
            runCurrent()
            assertEquals(listOf("Held", "Later"), headers(repo.questionBatches.value))
        }

    @Test
    fun withoutInteractive_questionFramesHoldNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)

            pump.push(fixtureEnvelope(QuestionPayloadsTest.QUESTION_SHOWN))
            runCurrent()

            assertTrue(repo.questionBatches.value.isEmpty())
        }

    private fun TestScope.interactiveRepo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to newRepo(pump, backgroundScope)
    }

    private fun newRepo(
        pump: FakeSessionPump,
        scope: CoroutineScope,
    ) = RemoteConversationRepository(pump, scope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })

    private fun headers(batches: List<QuestionBatch>): List<String> = batches.map { it.questions.single().header }

    private fun fixtureEnvelope(fixture: String): Envelope = MobileJson.decodeFromString(Envelope.serializer(), fixture)

    private fun shown(
        conversationId: String,
        batchId: String,
        header: String = "H",
    ): Envelope =
        shownRaw(
            """{"conversation_id":"$conversationId","question_batch_id":"$batchId","questions":[{"question":"Q?","header":"$header","options":[{"label":"A","description":"a"},{"label":"B","description":"b"}],"multi_select":false}]}""",
        )

    private fun shownRaw(payload: String): Envelope =
        Envelope(id = 1L, type = "question_shown", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    private fun dismissed(
        batchId: String,
        outcome: String,
        source: String,
    ): Envelope = dismissedRaw("""{"question_batch_id":"$batchId","outcome":"$outcome","source":"$source"}""")

    private fun dismissedRaw(payload: String): Envelope =
        Envelope(id = 1L, type = "question_dismissed", ts = TS, payload = MobileJson.parseToJsonElement(payload))

    /** Channel-backed fake of the inbound surface: unlimited buffer so pushes pre-subscription survive. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        override fun send(envelope: Envelope): Boolean = true

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-01T10:00:00Z"
    }
}

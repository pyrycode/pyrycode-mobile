package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.QuestionAnswer
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- #825: answer / refuse sends ----------------------------------------------------------------

    @Test
    fun answer_sendsOneFrameInBatchOrderWithValuesVerbatim() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(twoQuestionBatch("qb-1"))
            runCurrent()

            repo.answerQuestionBatch(
                "qb-1",
                listOf(
                    QuestionAnswer(1, listOf("Add a benchmark", "Add a fuzz target")),
                    QuestionAnswer(0, listOf("my own words, not a label")),
                ),
            )

            val sent = pump.sent.single()
            assertEquals("question_answer", sent.type)
            assertEquals(
                MobileJson.parseToJsonElement(
                    """{"question_batch_id":"qb-1","answer_token":"answer:qb-1","answers":[""" +
                        """{"question_index":0,"values":["my own words, not a label"]},""" +
                        """{"question_index":1,"values":["Add a benchmark","Add a fuzz target"]}]}""",
                ),
                sent.payload,
            )
        }

    @Test
    fun refuse_sendsOneFrameWithBatchIdAndTokenOnly() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1"))
            runCurrent()

            repo.refuseQuestionBatch("qb-1")

            val sent = pump.sent.single()
            assertEquals("question_refused", sent.type)
            assertEquals(
                MobileJson.parseToJsonElement("""{"question_batch_id":"qb-1","answer_token":"refuse:qb-1"}"""),
                sent.payload,
            )
        }

    @Test
    fun sends_failWithNothingSentForABatchNeverShownOrAlreadyDismissed() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-gone"))
            pump.push(dismissed("qb-gone", outcome = "unanswered", source = "no_answer"))
            runCurrent()

            for (id in listOf("qb-never", "qb-gone")) {
                assertTrue(
                    runCatching {
                        repo.answerQuestionBatch(
                            id,
                            listOf(QuestionAnswer(0, listOf("A"))),
                        )
                    }.exceptionOrNull() is IllegalStateException,
                )
                assertTrue(runCatching { repo.refuseQuestionBatch(id) }.exceptionOrNull() is IllegalStateException)
            }
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun answer_failsWithNothingSentUnlessEveryQuestionIsCoveredExactlyOnce() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(twoQuestionBatch("qb-1"))
            runCurrent()

            val invalid =
                listOf(
                    emptyList(),
                    listOf(QuestionAnswer(0, listOf("A"))),
                    listOf(QuestionAnswer(0, listOf("A")), QuestionAnswer(1, listOf("B")), QuestionAnswer(1, listOf("C"))),
                    listOf(QuestionAnswer(0, listOf("A")), QuestionAnswer(0, listOf("B"))),
                    listOf(QuestionAnswer(-1, listOf("A")), QuestionAnswer(1, listOf("B"))),
                    listOf(QuestionAnswer(0, listOf("A")), QuestionAnswer(2, listOf("B"))),
                )
            for (answers in invalid) {
                val outcome = runCatching { repo.answerQuestionBatch("qb-1", answers) }
                assertTrue(outcome.exceptionOrNull() is IllegalArgumentException)
            }
            assertTrue(pump.sent.isEmpty())
            assertEquals(listOf("qb-1"), repo.questionBatches.value.map { it.questionBatchId })
        }

    @Test
    fun sends_failToTheCallerWhenTheConnectionCannotSend() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1"))
            runCurrent()
            pump.accepts = false

            val answer = runCatching { repo.answerQuestionBatch("qb-1", listOf(QuestionAnswer(0, listOf("A")))) }
            val refusal = runCatching { repo.refuseQuestionBatch("qb-1") }

            assertTrue(answer.exceptionOrNull() is IllegalStateException)
            assertTrue(refusal.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("qb-1"), repo.questionBatches.value.map { it.questionBatchId })
        }

    @Test
    fun sends_leaveTheBatchHeldUntilQuestionDismissed() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1"))
            runCurrent()

            repo.answerQuestionBatch("qb-1", listOf(QuestionAnswer(0, listOf("A"))))
            repo.refuseQuestionBatch("qb-1")
            runCurrent()
            assertEquals(listOf("qb-1"), repo.questionBatches.value.map { it.questionBatchId })

            pump.push(dismissed("qb-1", outcome = "answered", source = "remote"))
            runCurrent()
            assertTrue(repo.questionBatches.value.isEmpty())
        }

    @Test
    fun tokens_areStablePerSendAndDistinctAcrossVerbsAndBatches() =
        runTest {
            val (pump, repo) = interactiveRepo()
            pump.push(shown("conv-1", "qb-1"))
            pump.push(shown("conv-2", "qb-2"))
            runCurrent()

            repo.answerQuestionBatch("qb-1", listOf(QuestionAnswer(0, listOf("secret answer"))))
            repo.answerQuestionBatch("qb-1", listOf(QuestionAnswer(0, listOf("secret answer"))))
            repo.refuseQuestionBatch("qb-1")
            repo.answerQuestionBatch("qb-2", listOf(QuestionAnswer(0, listOf("A"))))
            repo.refuseQuestionBatch("qb-2")

            val tokens =
                pump.sent.map {
                    it.payload.jsonObject
                        .getValue("answer_token")
                        .jsonPrimitive.content
                }
            assertEquals(tokens[0], tokens[1])
            assertEquals(4, tokens.drop(1).toSet().size)
            for (token in tokens) {
                assertFalse(token.contains("secret answer"))
                assertFalse(token.contains("Q?"))
                assertFalse(token.contains("H"))
            }
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

    private fun twoQuestionBatch(batchId: String): Envelope =
        shownRaw(
            """{"conversation_id":"conv-1","question_batch_id":"$batchId","questions":[""" +
                """{"question":"Q1?","header":"H1","options":[{"label":"A","description":"a"}],"multi_select":false},""" +
                """{"question":"Q2?","header":"H2","options":[{"label":"B","description":"b"}],"multi_select":true}]}""",
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

        val sent = mutableListOf<Envelope>()

        var accepts = true

        override fun send(envelope: Envelope): Boolean {
            if (accepts) sent += envelope
            return accepts
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-09-01T10:00:00Z"
    }
}

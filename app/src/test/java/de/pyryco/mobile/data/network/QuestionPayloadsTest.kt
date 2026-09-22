package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 question frames (#822). Wire SSOT: `../pyrycode/docs/protocol-mobile.md`
 * § Question (v2). The fixtures below are copied verbatim from the daemon's
 * `internal/protocol/testdata/question_*.json` at `d278c264`; the decode is strict like desktop's
 * `parseQuestionShownPayload`: every field required and typed.
 */
class QuestionPayloadsTest {
    @Test
    fun questionShownFixture_decodesEveryFieldInWireOrder() {
        val batch = decodeShown(QUESTION_SHOWN).toBatch()

        assertEquals(
            QuestionBatch(
                conversationId = "conv-1",
                questionBatchId = "qb-7f3a",
                questions =
                    listOf(
                        Question(
                            question = "Which write strategy should the cache use?",
                            header = "Write strategy",
                            options =
                                listOf(
                                    QuestionOption("Write-through", "Writes reach the cache and the store together."),
                                    QuestionOption("Write-behind", "Writes reach the cache first, the store later."),
                                ),
                            multiSelect = false,
                        ),
                        Question(
                            question = "Which eviction policies should it support?",
                            header = "Eviction",
                            options =
                                listOf(
                                    QuestionOption("LRU", "Evict the least recently used entry."),
                                    QuestionOption("LFU", "Evict the least frequently used entry."),
                                    QuestionOption("TTL", "Evict entries after a fixed time to live."),
                                ),
                            multiSelect = true,
                        ),
                    ),
            ),
            batch,
        )
    }

    @Test
    fun questionShownEmptyFixture_decodesWithNoQuestions() {
        val batch = decodeShown(QUESTION_SHOWN_EMPTY).toBatch()

        assertEquals("conv-1", batch.conversationId)
        assertEquals("qb-0e21", batch.questionBatchId)
        assertTrue(batch.questions.isEmpty())
    }

    @Test
    fun questionShownZeroFixture_keepsEmptyStringsAndFalse() {
        val batch = decodeShown(QUESTION_SHOWN_ZERO).toBatch()

        assertEquals(
            QuestionBatch("", "", listOf(Question("", "", listOf(QuestionOption("", "")), multiSelect = false))),
            batch,
        )
    }

    @Test
    fun questionDismissedFixture_decodesAllThreeFieldsVerbatim() {
        val dto = decodeDismissed(QUESTION_DISMISSED)

        assertEquals(QuestionDismissedPayloadDto("qb-4c19", outcome = "unanswered", source = "timeout"), dto)
    }

    @Test
    fun questionShown_rejectsMissingOrWrongTypedFields() {
        val malformed =
            listOf(
                """{"conversation_id":"c","questions":[]}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":null}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":[${question(header = "7")}]}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":[${question(multiSelect = "\"false\"")}]}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":[${question(multiSelect = "null")}]}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":[${question(options = """[{"label":"A"}]""")}]}""",
                """{"conversation_id":"c","question_batch_id":"b","questions":[${question(options = "null")}]}""",
            )

        malformed.forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { decodeShown(payload).toBatch() }
        }
    }

    @Test
    fun questionDismissed_rejectsMissingOrWrongTypedFields() {
        listOf(
            """{"outcome":"unanswered","source":"no_answer"}""",
            """{"question_batch_id":"b","source":"no_answer"}""",
            """{"question_batch_id":"b","outcome":"unanswered","source":3}""",
        ).forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { decodeDismissed(payload) }
        }
    }

    private fun question(
        header: String = "\"H\"",
        options: String = """[{"label":"A","description":"a"},{"label":"B","description":"b"}]""",
        multiSelect: String = "false",
    ): String = """{"question":"Q?","header":$header,"options":$options,"multi_select":$multiSelect}"""

    private fun decodeShown(payload: String): QuestionShownPayloadDto = MobileJson.decodeFromJsonElement(payloadOf(payload))

    private fun decodeDismissed(payload: String): QuestionDismissedPayloadDto = MobileJson.decodeFromJsonElement(payloadOf(payload))

    /** Accepts either a whole fixture envelope or a bare payload object. */
    private fun payloadOf(json: String) = MobileJson.parseToJsonElement(json).jsonObject.let { it["payload"] ?: it }

    internal companion object {
        const val QUESTION_SHOWN =
            """{"id":901,"type":"question_shown","ts":"2026-09-01T10:00:00Z","payload":{"conversation_id":"conv-1","question_batch_id":"qb-7f3a","questions":[{"question":"Which write strategy should the cache use?","header":"Write strategy","options":[{"label":"Write-through","description":"Writes reach the cache and the store together."},{"label":"Write-behind","description":"Writes reach the cache first, the store later."}],"multi_select":false},{"question":"Which eviction policies should it support?","header":"Eviction","options":[{"label":"LRU","description":"Evict the least recently used entry."},{"label":"LFU","description":"Evict the least frequently used entry."},{"label":"TTL","description":"Evict entries after a fixed time to live."}],"multi_select":true}]}}"""
        const val QUESTION_SHOWN_EMPTY =
            """{"id":903,"type":"question_shown","ts":"2026-09-01T10:00:02Z","payload":{"conversation_id":"conv-1","question_batch_id":"qb-0e21","questions":[]}}"""
        const val QUESTION_SHOWN_ZERO =
            """{"id":902,"type":"question_shown","ts":"2026-09-01T10:00:01Z","payload":{"conversation_id":"","question_batch_id":"","questions":[{"question":"","header":"","options":[{"label":"","description":""}],"multi_select":false}]}}"""
        const val QUESTION_DISMISSED =
            """{"id":904,"type":"question_dismissed","ts":"2026-09-01T10:00:03Z","payload":{"question_batch_id":"qb-4c19","outcome":"unanswered","source":"timeout"}}"""
    }
}

package de.pyryco.mobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Codec tests for the three background-task frames (#677). Wire SSOT: `../pyrycode/docs/protocol-mobile.md`
 * § `background_task_started`, § `background_task_updated`, § `background_task_roster`. The fixtures below are
 * copied verbatim from the daemon's `internal/protocol/testdata/background_task_*.json`. Every key is always
 * present on the wire (no `omitempty` upstream), so each is required here.
 */
class BackgroundTaskPayloadsTest {
    @Test
    fun startedFixture_decodesEveryField() {
        val dto = decode<BackgroundTaskStartedPayloadDto>(STARTED)

        assertEquals(
            BackgroundTaskStartedPayloadDto(
                conversationId = "c1",
                taskId = "task_01ABC",
                toolCallId = "toolu_01XYZ",
                description = "grep -rn 'a<b&c' . > /tmp/out.txt &",
                taskType = "local_bash",
                truncatedFields = listOf("description"),
            ),
            dto,
        )
    }

    @Test
    fun midLifeUpdatedFixture_keepsTheTruncatedPatchAsAString() {
        val dto = decode<BackgroundTaskUpdatedPayloadDto>(UPDATED)

        assertEquals("{\"is_backgrounded\":tr", dto.patch)
        assertEquals("", dto.status)
        assertEquals("", dto.summary)
        assertEquals(listOf("patch"), dto.truncatedFields)
    }

    @Test
    fun terminalUpdatedFixture_carriesStatusAndSummary() {
        val dto = decode<BackgroundTaskUpdatedPayloadDto>(UPDATED_TERMINAL)

        assertEquals("completed", dto.status)
        assertEquals("cat /tmp/pyry-fifo", dto.summary)
        assertEquals("", dto.patch)
        assertNull(dto.truncatedFields)
    }

    @Test
    fun rosterFixture_keepsRowsInWireOrderWithTheirOwnTruncation() {
        val dto = decode<BackgroundTaskRosterPayloadDto>(ROSTER)

        assertEquals("c1", dto.conversationId)
        assertEquals(3, dto.droppedTasks)
        assertEquals(
            listOf(
                BackgroundTaskRowDto("task_01ABC", "local_bash", "grep -rn 'a<b&c' .", listOf("description")),
                BackgroundTaskRowDto("task_02DEF", "local_bash", "sleep 300", null),
            ),
            dto.tasks,
        )
    }

    @Test
    fun emptyRosterFixture_decodesToAnEmptyList() {
        val dto = decode<BackgroundTaskRosterPayloadDto>(ROSTER_EMPTY)

        assertEquals(emptyList<BackgroundTaskRowDto>(), dto.tasks)
        assertEquals(0, dto.droppedTasks)
    }

    @Test
    fun missingRequiredKey_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<BackgroundTaskUpdatedPayloadDto>(
                MobileJson.parseToJsonElement("""{"conversation_id":"c1","task_id":"t","patch":"","summary":"","truncated_fields":null}"""),
            )
        }
    }

    @Test
    fun nullTasks_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<BackgroundTaskRosterPayloadDto>(
                MobileJson.parseToJsonElement("""{"conversation_id":"c1","tasks":null,"dropped_tasks":0}"""),
            )
        }
    }

    private inline fun <reified T> decode(fixture: String): T =
        MobileJson.decodeFromJsonElement<T>(MobileJson.decodeFromString(Envelope.serializer(), fixture).payload)

    companion object {
        const val STARTED =
            """{"id":705,"type":"background_task_started","ts":"2026-05-08T10:33:18Z","payload":{"conversation_id":"c1","task_id":"task_01ABC","tool_call_id":"toolu_01XYZ","description":"grep -rn 'a<b&c' . > /tmp/out.txt &","task_type":"local_bash","truncated_fields":["description"]}}"""
        const val UPDATED =
            """{"id":706,"type":"background_task_updated","ts":"2026-05-08T10:33:19Z","payload":{"conversation_id":"c1","task_id":"task_01ABC","patch":"{\"is_backgrounded\":tr","status":"","summary":"","truncated_fields":["patch"]}}"""
        const val UPDATED_TERMINAL =
            """{"id":707,"type":"background_task_updated","ts":"2026-05-08T10:33:24Z","payload":{"conversation_id":"c1","task_id":"task_01ABC","patch":"","status":"completed","summary":"cat /tmp/pyry-fifo","truncated_fields":null}}"""
        const val ROSTER =
            """{"id":707,"type":"background_task_roster","ts":"2026-05-08T10:33:20Z","payload":{"conversation_id":"c1","tasks":[{"task_id":"task_01ABC","task_type":"local_bash","description":"grep -rn 'a<b&c' .","truncated_fields":["description"]},{"task_id":"task_02DEF","task_type":"local_bash","description":"sleep 300","truncated_fields":null}],"dropped_tasks":3}}"""
        const val ROSTER_EMPTY =
            """{"id":708,"type":"background_task_roster","ts":"2026-05-08T10:33:21Z","payload":{"conversation_id":"c1","tasks":[],"dropped_tasks":0}}"""
    }
}

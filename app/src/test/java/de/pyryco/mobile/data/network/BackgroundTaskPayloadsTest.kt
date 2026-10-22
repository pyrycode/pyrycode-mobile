package de.pyryco.mobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Codec coverage for the supported session-local background-task progress reading. */
class BackgroundTaskPayloadsTest {
    @Test
    fun progressFixture_decodesEveryField() {
        val dto = decode<BackgroundTaskProgressPayloadDto>(PROGRESS)

        assertEquals(
            BackgroundTaskProgressPayloadDto(
                conversationId = "c1",
                taskId = "a8eec1cd5e109aa38",
                description = "Reading beta.txt",
                subagentType = "general-purpose",
                lastToolName = "Read",
                totalTokens = 16246,
                toolUses = 2,
                durationMs = 4546,
                truncatedFields = null,
            ),
            dto,
        )
    }

    @Test
    fun progressMissingCounter_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<BackgroundTaskProgressPayloadDto>(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c1","task_id":"t","description":"d","subagent_type":"s","last_tool_name":"Read",""" +
                        """"total_tokens":1,"duration_ms":1,"truncated_fields":null}""",
                ),
            )
        }
    }

    @Test
    fun progressStringCounter_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<BackgroundTaskProgressPayloadDto>(
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"c1","task_id":"t","description":"d","subagent_type":"s","last_tool_name":"Read",""" +
                        """"total_tokens":1,"tool_uses":"two","duration_ms":1,"truncated_fields":null}""",
                ),
            )
        }
    }

    private inline fun <reified T> decode(fixture: String): T =
        MobileJson.decodeFromJsonElement<T>(MobileJson.decodeFromString(Envelope.serializer(), fixture).payload)

    companion object {
        const val PROGRESS =
            """{"id":812,"type":"background_task_progress","ts":"2026-09-10T09:14:07Z","payload":{"conversation_id":"c1","task_id":"a8eec1cd5e109aa38","description":"Reading beta.txt","subagent_type":"general-purpose","last_tool_name":"Read","total_tokens":16246,"tool_uses":2,"duration_ms":4546,"truncated_fields":null}}"""
    }
}

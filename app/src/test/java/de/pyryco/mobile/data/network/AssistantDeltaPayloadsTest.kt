package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistantDeltaPayloadsTest {
    @Test
    fun parent_isMappedVerbatimAsInertData() {
        val parent = "  ../Agent <x>\n$(echo nope) ä 🚀  "
        val dto =
            MobileJson.decodeFromString<AssistantDeltaPayloadDto>(
                """{"conversation_id":"c","turn_id":"child","seq":0,"text":"reply","parent_tool_use_id":"  ../Agent <x>\n${'$'}(echo nope) ä 🚀  "}""",
            )
        assertEquals(parent, dto.parentToolUseId)
        assertEquals(LiveSessionEvent.AssistantDelta("c", "child", 0, "reply", parent), dto.toEvent())
    }

    @Test
    fun absentAndEmptyParents_defaultToMainLane() {
        for (suffix in listOf("", """, "parent_tool_use_id":""""")) {
            val dto =
                MobileJson.decodeFromString<AssistantDeltaPayloadDto>(
                    """{"conversation_id":"c","turn_id":"t","seq":0,"text":"reply"$suffix}""",
                )
            assertEquals("", dto.parentToolUseId)
            assertEquals(LiveSessionEvent.AssistantDelta("c", "t", 0, "reply"), dto.toEvent())
        }
    }

    @Test
    fun explicitNullParent_isRejectedRatherThanDiscarded() {
        assertThrows(SerializationException::class.java) {
            MobileJson
                .decodeFromJsonElement<AssistantDeltaPayloadDto>(
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c","turn_id":"t","seq":0,"text":"reply","parent_tool_use_id":null}""",
                    ),
                ).toEvent()
        }
    }
}

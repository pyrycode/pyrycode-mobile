package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the `turn_end` decode seam's four optional string-and-bool fields (#805): `outcome`,
 * `is_error`, `terminal_reason`, `error_category`. Fixtures are wire JSON decoded through [MobileJson], so
 * the boundary under test is the real one.
 */
class TurnEndPayloadsTest {
    @Test
    fun absentFields_decodeToTheirEmptyValues() {
        val event = decodeTurnEnd("")

        assertEquals(LiveSessionEvent.TurnEnd("c1", "t1", "end_turn"), event)
        assertEquals("", event.outcome)
        assertEquals(false, event.isError)
        assertEquals("", event.terminalReason)
        assertEquals("", event.errorCategory)
    }

    @Test
    fun presentFields_areCarriedVerbatim() {
        val event =
            decodeTurnEnd(
                """"outcome":"success","is_error":true,"terminal_reason":"prompt_too_long","error_category":"invalid_request"""",
            )

        assertEquals(
            LiveSessionEvent.TurnEnd(
                conversationId = "c1",
                turnId = "t1",
                stopReason = "end_turn",
                outcome = "success",
                isError = true,
                terminalReason = "prompt_too_long",
                errorCategory = "invalid_request",
            ),
            event,
        )
    }

    @Test
    fun unrecognisedTokens_survive() {
        val event =
            decodeTurnEnd(
                """"outcome":"error_from_a_newer_claude","terminal_reason":"some_new_reason","error_category":"quota_frozen"""",
            )

        assertEquals("error_from_a_newer_claude", event.outcome)
        assertEquals("some_new_reason", event.terminalReason)
        assertEquals("quota_frozen", event.errorCategory)
    }

    // Sanitization is the render boundary's job; decode must not quietly rewrite what claude sent.
    @Test
    fun controlCharacters_surviveDecodeVerbatim() {
        val event = decodeTurnEnd(""""outcome":"a\u001b[31mb\nc‮d"""")

        assertEquals("a\u001b[31mb\nc‮d", event.outcome)
    }

    @Test
    fun existingFields_areUnchanged() {
        val event = decodeTurnEnd(""""outcome":"error_max_turns"""", stopReason = "cancelled")

        assertEquals("c1", event.conversationId)
        assertEquals("t1", event.turnId)
        assertEquals("cancelled", event.stopReason)
    }

    private fun decodeTurnEnd(
        extra: String,
        stopReason: String = "end_turn",
    ): LiveSessionEvent.TurnEnd {
        val base = """"conversation_id":"c1","turn_id":"t1","stop_reason":"$stopReason""""
        val body = if (extra.isEmpty()) base else "$base,$extra"
        val dto = MobileJson.decodeFromJsonElement<TurnEndPayloadDto>(MobileJson.parseToJsonElement("{$body}"))
        return dto.toEvent() as LiveSessionEvent.TurnEnd
    }
}

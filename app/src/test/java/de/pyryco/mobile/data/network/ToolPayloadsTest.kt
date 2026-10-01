package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.LiveSessionEvent
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the `tool_use` / `tool_result` decode seam's two #810 fields: `tool_use.input`
 * (the tool input's own top-level fields) and `parent_tool_use_id` on both frames. Fixtures are wire
 * JSON decoded through [MobileJson], never a DTO, so the boundary under test is the real one.
 */
class ToolPayloadsTest {
    // ---- AC #1: the input fields arrive verbatim ---------------------------------------------------

    @Test
    fun toolUse_inputFields_areCarriedVerbatimInWireOrder() {
        val event =
            decodeToolUse(
                """"input":{"command":"rm -rf ./x && echo \"hi\"","file_path":"../../etc/passwd","new_string":"line1\nline2","old_string":"cut short…","z":"äö 🚀"}""",
            )

        assertEquals(
            listOf(
                "command" to "rm -rf ./x && echo \"hi\"",
                "file_path" to "../../etc/passwd",
                "new_string" to "line1\nline2",
                "old_string" to "cut short…",
                "z" to "äö 🚀",
            ),
            event.input.toList(),
        )
    }

    @Test
    fun toolUse_valuesAreNotTrimmedOrNormalised() {
        val event = decodeToolUse(""""input":{"path":"  ./a/../b/  ","empty":""}""")

        assertEquals(mapOf("path" to "  ./a/../b/  ", "empty" to ""), event.input)
    }

    @Test
    fun toolUse_absentEmptyOrNonObjectInput_decodesToEmptyFields() {
        listOf(
            "",
            """"input":{}""",
            """"input":null""",
            """"input":["a","b"]""",
            """"input":"file.kt"""",
            """"input":42""",
        ).forEach { input ->
            val event = decodeToolUse(input)
            assertEquals("input shape <$input>", emptyMap<String, String>(), event.input)
            assertEquals("the rest of the frame survives <$input>", "Read", event.name)
        }
    }

    @Test
    fun toolUse_nonStringValue_isSkippedAndItsSiblingsSurvive() {
        val event = decodeToolUse(""""input":{"a":"kept","b":42,"c":null,"d":{"x":1},"e":"also kept"}""")

        assertEquals(mapOf("a" to "kept", "e" to "also kept"), event.input)
    }

    // ---- AC #2: parent_tool_use_id on both frames --------------------------------------------------

    @Test
    fun toolUse_parentToolUseId_isCarriedVerbatim() {
        val event = decodeToolUse(""""parent_tool_use_id":" parent <x> """")

        assertEquals(" parent <x> ", event.parentToolUseId)
    }

    @Test
    fun toolResult_parentToolUseId_isCarriedVerbatim() {
        val event = decodeToolResult(""""parent_tool_use_id":"toolu_parent"""")

        assertEquals("toolu_parent", event.parentToolUseId)
    }

    @Test
    fun bothFrames_absentOrEmptyParent_decodeToMainThread() {
        assertEquals("", decodeToolUse("").parentToolUseId)
        assertEquals("", decodeToolUse(""""parent_tool_use_id":""""").parentToolUseId)
        assertEquals("", decodeToolResult("").parentToolUseId)
        assertEquals("", decodeToolResult(""""parent_tool_use_id":""""").parentToolUseId)
    }

    // ---- #1316: tool_result's result_detail -----------------------------------------------------------

    @Test
    fun toolResult_absentResultDetail_decodesToEmpty() {
        assertEquals("", decodeToolResult("").resultDetail)
    }

    @Test
    fun toolResult_emptyResultDetail_decodesToEmpty() {
        assertEquals("", decodeToolResult(""""result_detail":""""").resultDetail)
    }

    @Test
    fun toolResult_resultDetail_isCarriedVerbatim() {
        assertEquals("110 of 1676 lines", decodeToolResult(""""result_detail":"110 of 1676 lines"""").resultDetail)
        assertEquals(" 265 lines <b> ", decodeToolResult(""""result_detail":" 265 lines <b> """").resultDetail)
    }

    // ---- Fixtures ---------------------------------------------------------------------------------

    /** Decode a `tool_use` whose base fields are fixed and [extra] (a `"key":value` fragment) is appended. */
    private fun decodeToolUse(extra: String): LiveSessionEvent.ToolUse {
        val base = """"conversation_id":"c1","turn_id":"t1","tool_use_id":"tu1","name":"Read","input_summary":"s""""
        return decode<ToolUsePayloadDto>(base, extra).toEvent() as LiveSessionEvent.ToolUse
    }

    private fun decodeToolResult(extra: String): LiveSessionEvent.ToolResult {
        val base = """"conversation_id":"c1","turn_id":"t1","tool_use_id":"tu1","is_error":false,"result_summary":"ok""""
        return decode<ToolResultPayloadDto>(base, extra).toEvent() as LiveSessionEvent.ToolResult
    }

    private inline fun <reified T> decode(
        base: String,
        extra: String,
    ): T {
        val body = if (extra.isEmpty()) base else "$base,$extra"
        return MobileJson.decodeFromJsonElement<T>(MobileJson.parseToJsonElement("{$body}"))
    }
}

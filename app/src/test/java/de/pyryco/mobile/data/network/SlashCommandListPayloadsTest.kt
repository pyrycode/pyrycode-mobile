package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 `slash_command_list` frame (#882). JUnit4, mirroring [ModelListPayloadsTest].
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `slash_command_list`; the three fixtures below are the
 * daemon's `internal/protocol/testdata/slash_command_list*.json` verbatim at `8591b0b1`.
 *
 * This class owns the **payload-shape** coverage. RemoteConversationRepositorySlashCommandTest owns the
 * inbound arm, the routing and the retention, and deliberately does not re-assert these shapes.
 */
class SlashCommandListPayloadsTest {
    // The populated fixture decodes every row, in the daemon's order, with every field carried across.
    @Test
    fun populatedFixture_decodesEveryRowInDaemonOrder() {
        val menu = decodeEnvelope(SLASH_COMMAND_LIST)

        assertEquals(listOf("claude-api", "clear", "config", "model", "usage"), menu.rows.map { it.name })
        assertEquals(listOf("", "[name]", "key=value", "<model>", ""), menu.rows.map { it.argumentHint })
        assertEquals(
            SlashCommandMenuRow(
                name = "clear",
                argumentHint = "[name]",
                description = "Start a new session with empty context; previous session stays on disk (resumable with /resume)",
                aliases = listOf("reset", "new"),
                truncatedFields = null,
            ),
            menu.rows[1],
        )
    }

    // Aliases are per row and in wire order; `[]` is a present, empty list rather than a missing one.
    @Test
    fun populatedFixture_carriesAliasesPerRow() {
        val menu = decodeEnvelope(SLASH_COMMAND_LIST)

        assertEquals(
            listOf(emptyList(), listOf("reset", "new"), listOf("settings"), emptyList(), listOf("cost", "stats")),
            menu.rows.map { it.aliases },
        )
    }

    // `truncated_fields` is each row's own report: the cut `claude-api` row names its field, and every other
    // row's explicit `null` reads as "nothing was cut".
    @Test
    fun populatedFixture_truncatedFieldsArePerRow() {
        val menu = decodeEnvelope(SLASH_COMMAND_LIST)

        assertEquals(listOf(listOf("description"), null, null, null, null), menu.rows.map { it.truncatedFields })
    }

    // `dropped_commands` is carried verbatim, never recomputed from the five rows the frame kept.
    @Test
    fun populatedFixture_droppedCommandsCarriedVerbatim() {
        val menu = decodeEnvelope(SLASH_COMMAND_LIST)

        assertEquals(5, menu.rows.size)
        assertEquals(2, menu.droppedCommands)
    }

    // The workspace-authored description survives byte for byte: its embedded newlines, its non-ASCII em
    // dash and its quotes. A trim, a one-line fold or a re-encode would show up here.
    @Test
    fun populatedFixture_descriptionSurvivesVerbatim() {
        val description = decodeEnvelope(SLASH_COMMAND_LIST).rows.first().description

        assertTrue(description.startsWith("Reference for the Claude API / Anthropic SDK — model ids"))
        assertEquals(2, description.count { it == '\n' })
        assertTrue(description.contains("don't skip because it \"looks like a one-liner\""))
        assertTrue(description.endsWith("(run this grep FIRST if no provider named — don't Read the file)."))
    }

    // An empty `commands` is a present menu with no rows, which a consumer can tell from an absent one.
    @Test
    fun emptyFixture_decodesToPresentMenuWithNoRows() {
        assertEquals(SlashCommandMenu(rows = emptyList(), droppedCommands = 0), decodeEnvelope(SLASH_COMMAND_LIST_EMPTY))
    }

    // The zero-value fixture decodes: an empty id and an all-empty row are well formed on the wire. Whether an
    // empty id is usable is the repository's decision, not the codec's.
    @Test
    fun zeroFixture_decodesWithEmptyIdAndEmptyRow() {
        val dto = decodeDto(payloadOf(SLASH_COMMAND_LIST_ZERO))

        assertEquals("", dto.conversationId)
        assertEquals(
            SlashCommandMenu(rows = listOf(SlashCommandMenuRow("", "", "", emptyList(), null)), droppedCommands = 0),
            dto.toMenu(),
        )
    }

    // The routing key reaches the caller verbatim.
    @Test
    fun conversationId_reachesTheCallerVerbatim() {
        assertEquals("c1", decodeDto(payloadOf(SLASH_COMMAND_LIST)).conversationId)
    }

    // `name` is not an identifier: a real one is `__remote-workflow`, and padding or case must not be touched
    // either. Nothing validates, trims or folds it.
    @Test
    fun name_isNotValidatedOrNormalised() {
        val rows =
            decode(
                """{"conversation_id":"c1","commands":[""" +
                    """{"name":"__remote-workflow","argument_hint":"","description":"","aliases":[]},""" +
                    """{"name":"  Mixed Case\u001B[31m  ","argument_hint":" <x> ","description":"","aliases":["  Al  "]}""" +
                    """],"dropped_commands":0}""",
            ).rows

        assertEquals("__remote-workflow", rows[0].name)
        assertEquals("  Mixed Case\u001B[31m  ", rows[1].name)
        assertEquals(" <x> ", rows[1].argumentHint)
        assertEquals(listOf("  Al  "), rows[1].aliases)
    }

    // An omitted `truncated_fields` means the same as an explicit `null`: nothing was cut.
    @Test
    fun truncatedFields_absent_readsAsNothingCut() {
        val row =
            decode(
                """{"conversation_id":"c1","commands":[{"name":"n","argument_hint":"","description":"","aliases":[]}],"dropped_commands":0}""",
            ).rows.single()

        assertNull(row.truncatedFields)
    }

    // A non-zero count beside a short or empty list is legal: the byte bound can fire before the entry cap.
    @Test
    fun droppedCommands_nonZeroBesideEmptyList_decodes() {
        val menu = decode("""{"conversation_id":"c1","commands":[],"dropped_commands":51}""")

        assertEquals(SlashCommandMenu(emptyList(), 51), menu)
    }

    // `commands` and `aliases` are never `null` on the wire, so an explicit null fails the frame rather than
    // standing in for an empty list.
    @Test
    fun nullArrays_failTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"c1","commands":null,"dropped_commands":0}""")
        }
        assertThrows(SerializationException::class.java) {
            decode(
                """{"conversation_id":"c1","commands":[{"name":"n","argument_hint":"","description":"","aliases":null}],"dropped_commands":0}""",
            )
        }
    }

    // Every frame-level key is required with no default.
    @Test
    fun missingFrameLevelKeys_failTheFrame() {
        assertThrows(SerializationException::class.java) { decode("""{"commands":[],"dropped_commands":0}""") }
        assertThrows(SerializationException::class.java) { decode("""{"conversation_id":"c1","dropped_commands":0}""") }
        assertThrows(SerializationException::class.java) { decode("""{"conversation_id":"c1","commands":[]}""") }
    }

    // A row missing a required field fails the WHOLE frame; there is no partial menu.
    @Test
    fun rowMissingRequiredField_failsTheWholeFrame() {
        for (missing in listOf("name", "argument_hint", "description", "aliases")) {
            val fields =
                linkedMapOf("name" to "\"n\"", "argument_hint" to "\"\"", "description" to "\"\"", "aliases" to "[]")
                    .filterKeys { it != missing }
                    .entries
                    .joinToString(",") { (key, value) -> "\"$key\":$value" }
            assertThrows(missing, SerializationException::class.java) {
                decode(
                    """{"conversation_id":"c1","commands":[{"name":"ok","argument_hint":"","description":"","aliases":[]},{$fields}],"dropped_commands":0}""",
                )
            }
        }
    }

    // A wrong-typed field fails structurally rather than degrading.
    @Test
    fun wrongTypedFields_failTheFrame() {
        assertThrows(SerializationException::class.java) { decode("""{"conversation_id":"c1","commands":{},"dropped_commands":0}""") }
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"c1","commands":[],"dropped_commands":"many"}""")
        }
        assertThrows(SerializationException::class.java) {
            decode(
                """{"conversation_id":"c1","commands":[{"name":5,"argument_hint":"","description":"","aliases":[]}],"dropped_commands":0}""",
            )
        }
    }

    // Forward compatibility: an unknown key from a newer daemon is ignored rather than failing the frame.
    @Test
    fun unknownKeys_areIgnored() {
        val menu =
            decode(
                """{"conversation_id":"c1","future":1,"commands":[{"name":"n","argument_hint":"","description":"","aliases":[],"extra":true}],"dropped_commands":0}""",
            )

        assertEquals(listOf("n"), menu.rows.map { it.name })
    }

    private fun decode(payload: String): SlashCommandMenu = decodeDto(payload).toMenu()

    private fun decodeDto(payload: String): SlashCommandListPayloadDto =
        MobileJson.decodeFromJsonElement(SlashCommandListPayloadDto.serializer(), MobileJson.parseToJsonElement(payload))

    private fun decodeEnvelope(envelope: String): SlashCommandMenu = decode(payloadOf(envelope))

    private fun payloadOf(envelope: String): String = MobileJson.decodeFromString(Envelope.serializer(), envelope).payload.toString()

    internal companion object {
        const val SLASH_COMMAND_LIST =
            """{"id":717,"type":"slash_command_list","ts":"2026-08-24T11:20:00Z","payload":{"conversation_id":"c1","commands":[{"name":"claude-api","argument_hint":"","description":"Reference for the Claude API / Anthropic SDK — model ids, pricing, params, streaming, tool use, MCP, agents, caching, token counting, model migration.\nTRIGGER — read BEFORE opening the target file; don't skip because it \"looks like a one-liner\" — whenever: the prompt names Claude/Anthropic in any form (Claude, Anthropic, Fable, Opus, Sonnet, Haiku, `anthropic`, `@anthropic-ai`, `claude-*`, `us.anthropic.*`, `[1m]`); the user asks about an LLM (pricing/model choice/limits/caching) — never answer from memory; OR the task is LLM-shaped with provider unstated (agent/MCP/tool-definition/multi-agent/RAG/LLM-judge/computer-use; generate/summarize/extract/classify/rewrite/converse over NL; debugging refusals/cutoffs/streaming/tool-calls/tokens).\nSKIP only when another provider is being worked on (overrides all triggers): OpenAI/GPT/Gemini/Llama/Mistral/Cohere/Ollama named in the query; OR `grep -rE 'openai|langchain_openai|google.generativeai|genai|mistralai|cohere|ollama'` over the project hits (run this grep FIRST if no provider named — don't Read the file).","aliases":[],"truncated_fields":["description"]},{"name":"clear","argument_hint":"[name]","description":"Start a new session with empty context; previous session stays on disk (resumable with /resume)","aliases":["reset","new"],"truncated_fields":null},{"name":"config","argument_hint":"key=value","description":"Set a setting by key","aliases":["settings"],"truncated_fields":null},{"name":"model","argument_hint":"<model>","description":"Set the AI model for Claude Code","aliases":[],"truncated_fields":null},{"name":"usage","argument_hint":"","description":"Show session cost, plan usage, and what's contributing to your limits","aliases":["cost","stats"],"truncated_fields":null}],"dropped_commands":2}}"""
        const val SLASH_COMMAND_LIST_EMPTY =
            """{"id":718,"type":"slash_command_list","ts":"2026-08-24T11:20:01Z","payload":{"conversation_id":"c1","commands":[],"dropped_commands":0}}"""
        const val SLASH_COMMAND_LIST_ZERO =
            """{"id":719,"type":"slash_command_list","ts":"2026-08-24T11:20:02Z","payload":{"conversation_id":"","commands":[{"name":"","argument_hint":"","description":"","aliases":[],"truncated_fields":null}],"dropped_commands":0}}"""
    }
}

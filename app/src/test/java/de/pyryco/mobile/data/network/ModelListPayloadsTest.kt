package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 `model_list` frame (#791). JUnit4, mirroring [SessionSettingsPayloadsTest].
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § `model_list` (daemon `main` at `43a52426`).
 *
 * This class owns the **payload-shape** coverage for the ticket — the three optionality shapes, the
 * verbatim-string obligation and the malformed branches. RemoteConversationRepositoryTest owns the
 * inbound arm, the routing and the retention, and deliberately does not re-assert these shapes.
 */
class ModelListPayloadsTest {
    // AC #1: every published string survives the decode byte for byte. The fixture is deliberately
    // hostile — padding, mixed case, non-ASCII and a control character — because the daemon bounds
    // these strings but sanitizes nothing, and a trim/fold/re-encode anywhere on this path would be
    // invisible against a tidy fixture.
    @Test
    fun row_stringsSurviveVerbatim() {
        val menu = decode(HOSTILE_FRAME)
        val row = menu.rows.single()

        assertEquals("  claude-Opus-5_20260101  ", row.resolvedModel)
        assertEquals("opus[1m]", row.value)
        assertEquals("Opus\u00A05 \u001B[31m— Pöytä", row.displayName)
        assertEquals(listOf("  hIgH  "), row.effortLevels)
    }

    // AC #1: the row's identifier, selectable value and label are three independent fields; a decode
    // that punned one onto another (or derived a "family" by splitting `value`) would not survive a
    // fixture where all three disagree.
    @Test
    fun row_identifierValueAndLabelAreIndependent() {
        val row = decode(POPULATED_FRAME).rows.first()

        assertEquals("claude-sonnet-5", row.resolvedModel)
        assertEquals("sonnet", row.value)
        assertEquals("Sonnet 5", row.displayName)
    }

    // AC #1: claude's own array order is the display order, so the decode preserves it.
    @Test
    fun models_preserveWireOrder() {
        val menu = decode(POPULATED_FRAME)

        assertEquals(listOf("sonnet", "opus", "default"), menu.rows.map { it.value })
    }

    // AC #1: `models` is always an array and never `null`, so an empty menu is a positive statement
    // that claude offered nothing — it decodes into a present menu with zero rows, no null branch.
    @Test
    fun models_empty_decodesToPresentMenuWithNoRows() {
        val menu = decode("""{"conversation_id":"$CONVERSATION_ID","models":[],"dropped_models":0}""")

        assertEquals(emptyList<ModelMenuRow>(), menu.rows)
        assertEquals(0, menu.droppedModels)
    }

    // AC #2: effort choices come from the row's own published levels, verbatim and in wire order —
    // never the five `Effort` entries.
    @Test
    fun effortLevels_arePerRowAndVerbatim() {
        val rows = decode(POPULATED_FRAME).rows

        assertEquals(listOf("low", "medium", "high"), rows[0].effortLevels)
        assertEquals(listOf("high", "  MAX  "), rows[1].effortLevels)
    }

    // AC #2: `[]` is a positive statement that the row exposes no effort control. It must decode to an
    // empty list and never be read as a cue to substitute a default set.
    @Test
    fun effortLevels_empty_decodesToEmptyList() {
        val rows = decode(POPULATED_FRAME).rows

        assertEquals(emptyList<String>(), rows[2].effortLevels)
    }

    // The wire states `effort_levels` is always present and never `null`, so an explicit null is a
    // malformed row rather than a third state — fail the frame instead of punning it onto `[]`.
    @Test
    fun effortLevels_null_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode(
                """{"conversation_id":"$CONVERSATION_ID","models":[{"resolved_model":"r","value":"v",
                   "display_name":"d","effort_levels":null,"supports_auto_mode":true}],"dropped_models":0}""",
            )
        }
    }

    // Both explicit values are honoured — the field is a real per-model refusal, not a formality.
    @Test
    fun supportsAutoMode_explicitValuesAreHonoured() {
        val rows = decode(POPULATED_FRAME).rows

        assertTrue(rows[0].supportsAutoMode)
        assertFalse(rows[1].supportsAutoMode)
    }

    // Absent in claude's reply decodes to `false`, which the wire states is the CORRECT reading — so
    // the default here states what the wire states rather than manufacturing a posture, and an absent
    // key must not fail the frame.
    @Test
    fun supportsAutoMode_absent_readsAsFalseAndDoesNotFailTheFrame() {
        val row =
            decode(
                """{"conversation_id":"$CONVERSATION_ID","models":[{"resolved_model":"r","value":"v",
                   "display_name":"d","effort_levels":[]}],"dropped_models":0}""",
            ).rows.single()

        assertFalse(row.supportsAutoMode)
    }

    // AC #5: `truncated_fields` is the one NULLABLE array on this frame, and `null` means "nothing was
    // cut". Omitted and explicit-null mean the same thing here, so `explicitNulls = false` collapsing
    // them is correct — the collapse `effective_effort` (#590) had to avoid, because its three states
    // mean three different things.
    @Test
    fun truncatedFields_absentAndExplicitNull_bothReadAsNothingCut() {
        assertNull(decode(truncatedFrame(null)).rows.single().truncatedFields)
        assertNull(decode(truncatedFrame("null")).rows.single().truncatedFields)
    }

    // AC #5: a populated list is the row's OWN report, in producer order, carried verbatim.
    @Test
    fun truncatedFields_populated_carriedVerbatimInProducerOrder() {
        val row = decode(truncatedFrame("""["display_name","effort_levels"]""")).rows.single()

        assertEquals(listOf("display_name", "effort_levels"), row.truncatedFields)
    }

    // `[]` is out of contract (the wire says `null` when nothing was cut) but is retained as the empty
    // list it arrived as rather than punned to `null` — what arrived is what is held.
    @Test
    fun truncatedFields_emptyArray_isRetainedAsEmptyNotNull() {
        val row = decode(truncatedFrame("[]")).rows.single()

        assertEquals(emptyList<String>(), row.truncatedFields)
    }

    // AC #5: each row reports its own cuts; there is no hoisted or flattened list, so a cut on one row
    // leaves its neighbours' readings untouched.
    @Test
    fun truncatedFields_areStrictlyPerRow() {
        val rows = decode(POPULATED_FRAME).rows

        assertNull(rows[0].truncatedFields)
        assertEquals(listOf("value"), rows[1].truncatedFields)
        assertNull(rows[2].truncatedFields)
    }

    // AC #5: `dropped_models` is carried VERBATIM, never recomputed from the retained row count. The
    // fixture's count contradicts its row count on purpose — a decode that derived it would report 0.
    @Test
    fun droppedModels_carriedVerbatimNotDerivedFromRowCount() {
        val menu = decode(POPULATED_FRAME)

        assertEquals(3, menu.rows.size)
        assertEquals(37, menu.droppedModels)
    }

    // A non-zero count beside an EMPTY list is legal on the wire and must decode: the client renders
    // "0 of N", it does not treat the empty list as evidence the count is wrong.
    @Test
    fun droppedModels_nonZeroBesideEmptyList_decodes() {
        val menu = decode("""{"conversation_id":"$CONVERSATION_ID","models":[],"dropped_models":40}""")

        assertEquals(emptyList<ModelMenuRow>(), menu.rows)
        assertEquals(40, menu.droppedModels)
    }

    // The routing key reaches the caller verbatim — it is the ONLY thing a retention may key on.
    @Test
    fun conversationId_reachesTheCallerVerbatim() {
        val dto = MobileJson.decodeFromJsonElement(ModelListPayloadDto.serializer(), MobileJson.parseToJsonElement(POPULATED_FRAME))

        assertEquals(CONVERSATION_ID, dto.conversationId)
    }

    // `models` is never `null` on the wire, so an explicit null is malformed — fail the frame rather
    // than decoding it as an empty (and therefore falsely "published") menu.
    @Test
    fun models_null_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"$CONVERSATION_ID","models":null,"dropped_models":0}""")
        }
    }

    // Every frame-level field is strict-required with no default, the #590 posture: a missing key is a
    // malformed frame, not a silently-defaulted one.
    @Test
    fun missingFrameLevelKeys_failTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode("""{"models":[],"dropped_models":0}""")
        }
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"$CONVERSATION_ID","dropped_models":0}""")
        }
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"$CONVERSATION_ID","models":[]}""")
        }
    }

    // A wrong-typed field fails structurally rather than degrading: `models` as an object instead of
    // an array, non-numeric text where the count is declared, and an unquoted number where a string
    // field is declared (`MobileJson` leaves `isLenient` off, so a bare number is not a string).
    @Test
    fun wrongTypedFields_failTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"$CONVERSATION_ID","models":{},"dropped_models":0}""")
        }
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":"$CONVERSATION_ID","models":[],"dropped_models":"many"}""")
        }
        assertThrows(SerializationException::class.java) {
            decode("""{"conversation_id":$CONVERSATION_ID_NUMERIC,"models":[],"dropped_models":0}""")
        }
        assertThrows(SerializationException::class.java) {
            decode(
                """{"conversation_id":"$CONVERSATION_ID","models":[{"resolved_model":5,"value":"v",
                   "display_name":"d","effort_levels":[],"supports_auto_mode":false}],"dropped_models":0}""",
            )
        }
    }

    // A **quoted** number where the `Int` count is declared is coerced rather than rejected: the tree
    // decoder reads a primitive's content and parses it. This is a property of the shared `MobileJson`
    // codec, not a `model_list` decision — every sibling payload decoded this way behaves the same — so
    // pinning it documents the boundary's real strictness rather than implying one it does not have. It
    // grants a hostile daemon nothing (it could send the bare number just as easily) and touches only
    // the count; the claude-authored strings are unaffected, and a bare number in their place still
    // fails the frame, which is the case directly above.
    @Test
    fun quotedNumberForDroppedModels_coercesRatherThanFailing() {
        val menu = decode("""{"conversation_id":"$CONVERSATION_ID","models":[],"dropped_models":"40"}""")

        assertEquals(40, menu.droppedModels)
    }

    // A row missing one of its three required strings is malformed and fails the WHOLE frame — there is
    // no partial menu, the all-or-nothing posture `toSessionSettings` takes.
    @Test
    fun rowMissingRequiredString_failsTheWholeFrame() {
        assertThrows(SerializationException::class.java) {
            decode(
                """{"conversation_id":"$CONVERSATION_ID","models":[{"resolved_model":"r","value":"v",
                   "effort_levels":[],"supports_auto_mode":false}],"dropped_models":0}""",
            )
        }
    }

    // Forward compatibility: an unknown key added by a newer daemon is ignored rather than failing, the
    // `MobileJson` posture every sibling payload relies on.
    @Test
    fun unknownKeys_areIgnored() {
        val menu =
            decode(
                """{"conversation_id":"$CONVERSATION_ID","models":[{"resolved_model":"r","value":"v",
                   "display_name":"d","effort_levels":[],"supports_auto_mode":false,"future":1}],
                   "dropped_models":0,"future_frame_field":{"nested":true}}""",
            )

        assertEquals("r", menu.rows.single().resolvedModel)
    }

    // ---- #792: the on-demand request payload ---------------------------------------------------

    // The ask names one conversation and nothing else. No request-id key: correlation rides the
    // envelope's `in_reply_to`, so a key here would be a second, disagreeable identity.
    @Test
    fun requestPayload_isExactlyTheOneConversationIdKey() {
        val encoded = MobileJson.encodeToJsonElement(RequestModelListPayloadDto(conversationId = CONVERSATION_ID))

        assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"$CONVERSATION_ID"}"""), encoded)
    }

    // `conversation_id` is always present on the wire (no `omitempty`), so an empty id still encodes
    // the key rather than being elided by MobileJson's `explicitNulls = false`. The daemon refuses it;
    // the sender's own guard is what keeps such a frame off the wire (RemoteConversationRepositoryTest).
    @Test
    fun requestPayload_emptyConversationId_stillEncodesTheKey() {
        val encoded = MobileJson.encodeToJsonElement(RequestModelListPayloadDto(conversationId = ""))

        assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":""}"""), encoded)
    }

    // #1110: a `multi_agent` frame tags every row with its agent and family; both decode per row, in
    // wire order, and the family is carried verbatim.
    @Test
    fun taggedRows_decodeAgentAndFamily() {
        val rows = decode(TAGGED_FRAME).rows

        assertEquals(
            listOf(ConversationAgent.Claude, ConversationAgent.Codex, null),
            rows.map { it.agent },
        )
        assertEquals(listOf("sonnet", "GPT-6 Luna", "x"), rows.map { it.family })
    }

    // #1110: a row without the tags is Claude's, which is what every frame to a client without
    // `multi_agent` holds, so an untagged frame decodes to the same rows as before the tags existed.
    @Test
    fun untaggedRows_readClaude_withNoFamily() {
        val rows = decode(POPULATED_FRAME).rows

        assertTrue(rows.all { it.agent == ConversationAgent.Claude })
        assertTrue(rows.all { it.family == null })
    }

    // #1110: only the two documented strings name an agent; any other value — a case variant
    // included — belongs to neither conversation.
    @Test
    fun rowAgent_mapsOnlyTheTwoDocumentedValues() {
        assertEquals(ConversationAgent.Claude, modelRowAgentOf(null))
        assertEquals(ConversationAgent.Claude, modelRowAgentOf("claude"))
        assertEquals(ConversationAgent.Codex, modelRowAgentOf("codex"))
        assertNull(modelRowAgentOf("Codex"))
        assertNull(modelRowAgentOf("gemini"))
        assertNull(modelRowAgentOf(""))
    }

    private fun decode(raw: String): ModelMenu =
        MobileJson.decodeFromJsonElement(ModelListPayloadDto.serializer(), MobileJson.parseToJsonElement(raw)).toMenu()

    private companion object {
        const val CONVERSATION_ID = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
        const val CONVERSATION_ID_NUMERIC = "17"

        /**
         * A three-row menu shaped on the protocol document's own description: a plain row, a bracketed
         * variant that reports its own cut, and the `default` alias exposing no effort control.
         * `dropped_models` deliberately disagrees with the row count.
         */
        val POPULATED_FRAME =
            """
            {"conversation_id":"$CONVERSATION_ID","dropped_models":37,"models":[
              {"resolved_model":"claude-sonnet-5","value":"sonnet","display_name":"Sonnet 5",
               "effort_levels":["low","medium","high"],"supports_auto_mode":true},
              {"resolved_model":"claude-opus-5","value":"opus","display_name":"Opus 5",
               "effort_levels":["high","  MAX  "],"supports_auto_mode":false,"truncated_fields":["value"]},
              {"resolved_model":"claude-sonnet-5","value":"default","display_name":"Default",
               "effort_levels":[],"supports_auto_mode":true}
            ]}
            """.trimIndent()

        /** A merged `multi_agent` frame (#1110): a Claude row, a Codex row and a row naming neither agent. */
        val TAGGED_FRAME =
            """
            {"conversation_id":"$CONVERSATION_ID","dropped_models":3,"models":[
              {"resolved_model":"claude-sonnet-5","value":"sonnet","display_name":"Sonnet 5",
               "effort_levels":["low","high"],"agent":"claude","family":"sonnet"},
              {"resolved_model":"gpt-6-luna-2026","value":"gpt-6-luna","display_name":"GPT-6 Luna",
               "effort_levels":["low","ultra"],"agent":"codex","family":"GPT-6 Luna"},
              {"resolved_model":"x","value":"x","display_name":"x",
               "effort_levels":[],"agent":"gemini","family":"x"}
            ]}
            """.trimIndent()

        /**
         * One row whose strings carry padding, mixed case, a non-breaking space, an ESC byte and
         * non-ASCII — the daemon bounds these but strips nothing, so the decode must not either.
         */
        val HOSTILE_FRAME =
            """
            {"conversation_id":"$CONVERSATION_ID","dropped_models":0,"models":[
              {"resolved_model":"  claude-Opus-5_20260101  ","value":"opus[1m]",
               "display_name":"Opus\u00a05 \u001b[31m— Pöytä","effort_levels":["  hIgH  "],
               "supports_auto_mode":false}
            ]}
            """.trimIndent()

        /** A single-row frame whose `truncated_fields` is [raw] verbatim, or omitted when [raw] is null. */
        fun truncatedFrame(raw: String?): String {
            val key = if (raw == null) "" else ""","truncated_fields":$raw"""
            return """
                {"conversation_id":"$CONVERSATION_ID","dropped_models":0,"models":[
                  {"resolved_model":"r","value":"v","display_name":"d","effort_levels":[],
                   "supports_auto_mode":false$key}
                ]}
                """.trimIndent()
        }
    }
}

package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec tests for the v2 session-settings read frames (#590). JUnit4, mirroring
 * [HistoryPayloadsTest]. Wire SSOT: `../pyrycode/docs/protocol-mobile.md`
 * § `request_session_settings` / § `session_settings` (daemon `main` at `43a52426`).
 *
 * This class owns the **payload-shape** coverage for the ticket — the three `effective_effort` states,
 * the retained zeros and the malformed branches. RemoteConversationRepositoryTest owns the wire round
 * trip and the refresh triggers and deliberately does not re-assert these shapes.
 */
class SessionSettingsPayloadsTest {
    // AC #1: the request names the conversation and nothing else, under the wire's snake_case key.
    @Test
    fun request_encodesConversationIdOnly() {
        val element = MobileJson.encodeToJsonElement(RequestSessionSettingsPayloadDto(conversationId = CONVERSATION_ID))

        assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"$CONVERSATION_ID"}"""), element)
    }

    // AC #1: every original field of a populated reply survives verbatim, including the session id the
    // caller must address its write to.
    @Test
    fun reply_populated_retainsEveryOriginalFieldVerbatim() {
        val settings = decode(POPULATED_REPLY)

        assertEquals("sess-a", settings.sessionId)
        assertEquals("opus", settings.model)
        assertEquals("high", settings.effort)
        assertEquals("default", settings.permissionMode)
        assertFalse(settings.yolo)
        assertEquals(12480L, settings.usedTokens)
        assertEquals(200000L, settings.windowTokens)
    }

    // AC #2, state ①: an OMITTED `effective_effort` is "unavailable or unsupported". This is also the
    // older-daemon reply — a producer that cannot report an applied value omits the key — so the decode
    // must SUCCEED rather than fail the frame.
    @Test
    fun effectiveEffort_omittedKey_decodesAsUnavailable() {
        val settings = decode(effectiveEffortReply(null))

        assertEquals(EffectiveEffort.Unavailable, settings.effectiveEffort)
    }

    // AC #2, state ②: an EXPLICIT null means Claude reported no effort parameter. Distinct from state ①
    // — the distinction `MobileJson`'s `explicitNulls = false` would collapse on a plain `String?`.
    @Test
    fun effectiveEffort_explicitNull_decodesAsNotReported() {
        val settings = decode(effectiveEffortReply("null"))

        assertEquals(EffectiveEffort.NotReported, settings.effectiveEffort)
    }

    // AC #2, state ③: a string is a confirmed applied level, retained verbatim.
    @Test
    fun effectiveEffort_string_decodesAsAppliedVerbatim() {
        val settings = decode(effectiveEffortReply(""""medium""""))

        assertEquals(EffectiveEffort.Applied("medium"), settings.effectiveEffort)
    }

    // AC #2: the three states are mutually distinguishable to a consumer — the property that matters is
    // that no two of them are equal, not just that each decodes.
    @Test
    fun effectiveEffort_threeStates_areAllDistinct() {
        val omitted = decode(effectiveEffortReply(null)).effectiveEffort
        val explicitNull = decode(effectiveEffortReply("null")).effectiveEffort
        val applied = decode(effectiveEffortReply(""""low"""")).effectiveEffort

        assertEquals(3, setOf(omitted, explicitNull, applied).size)
    }

    // AC #2: `""` is a VALUE ("run at claude's own default"), not an absence — it must not collapse into
    // Unavailable. Same for a level this build has never heard of: the wire carries arbitrary strings.
    @Test
    fun effectiveEffort_emptyAndUnrecognizedStrings_areRetainedAsApplied() {
        assertEquals(EffectiveEffort.Applied(""), decode(effectiveEffortReply("""""""")).effectiveEffort)
        assertEquals(EffectiveEffort.Applied("ludicrous"), decode(effectiveEffortReply(""""ludicrous"""")).effectiveEffort)
    }

    // AC #2: the saved choice and the applied reading are independent — a disagreement is retained as a
    // disagreement, with neither field overwriting the other.
    @Test
    fun effort_and_effectiveEffort_disagreeing_areRetainedIndependently() {
        val settings =
            decode(
                """
                {"session_id":"sess-a","model":"opus","effort":"high","effective_effort":"low",
                 "yolo":false,"permission_mode":"default","used_tokens":10,"window_tokens":200000}
                """.trimIndent(),
            )

        assertEquals("high", settings.effort)
        assertEquals(EffectiveEffort.Applied("low"), settings.effectiveEffort)
    }

    // AC #2: a PRESENT `effective_effort` of the wrong JSON type fails the whole frame rather than
    // degrading to one of the three states — a number, a boolean, an object and an array each.
    @Test
    fun effectiveEffort_wrongJsonType_failsTheFrame() {
        for (raw in listOf("5", "true", "{}", """["high"]""")) {
            assertThrows(SerializationException::class.java) { decode(effectiveEffortReply(raw)) }
        }
    }

    // AC #2: the failure logs no payload content — and the exception message is the one place content
    // could escape into a crash report, so the thrown message names the key and nothing else.
    @Test
    fun effectiveEffort_wrongJsonType_messageCarriesNoPayloadContent() {
        val thrown =
            assertThrows(SerializationException::class.java) {
                decode(effectiveEffortReply("""{"leaked":"secret-applied-value"}"""))
            }

        val message = thrown.message.orEmpty()
        assertFalse(message.contains("secret-applied-value"))
        assertFalse(message.contains("leaked"))
        assertFalse(message.contains("sess-a"))
        assertTrue(message.contains("effective_effort"))
    }

    // AC #3: `permission_mode` and `yolo` are retained exactly as reported. A reported
    // `bypassPermissions` is accepted here even though the write half refuses that spelling, and a mode
    // this build does not recognise survives too (the wire's closed set can grow).
    @Test
    fun permissionMode_bypassAndUnknownModes_areRetainedVerbatim() {
        assertEquals("bypassPermissions", decode(permissionReply("bypassPermissions", yolo = true)).permissionMode)
        assertTrue(decode(permissionReply("bypassPermissions", yolo = true)).yolo)
        assertEquals("someFutureMode", decode(permissionReply("someFutureMode", yolo = false)).permissionMode)
    }

    // AC #3: an empty `permission_mode` beside a NON-EMPTY session id and `yolo:false` — a live
    // unconfirmed or dormant session — is retained as unavailable. Nothing manufactures "default", and
    // `yolo:false` is not turned into evidence that approvals are enforced.
    @Test
    fun permissionMode_emptyBesideLiveSession_isRetainedAsUnavailable() {
        val settings = decode(permissionReply("", yolo = false))

        assertEquals("", settings.permissionMode)
        assertFalse(settings.yolo)
        assertEquals("sess-a", settings.sessionId)
    }

    // AC #3/#5: the dormant reply — a real session id, no current child, no transcript to read.
    @Test
    fun reply_dormantSession_retainsStoredSettingsWithZeroCountsAndNoPosture() {
        val settings =
            decode(
                """
                {"session_id":"sess-dormant","model":"sonnet","effort":"medium","yolo":false,
                 "permission_mode":"","used_tokens":0,"window_tokens":0}
                """.trimIndent(),
            )

        assertEquals("sess-dormant", settings.sessionId)
        assertEquals("sonnet", settings.model)
        assertEquals("medium", settings.effort)
        assertEquals("", settings.permissionMode)
        assertEquals(EffectiveEffort.Unavailable, settings.effectiveEffort)
        assertEquals(0L, settings.usedTokens)
        assertEquals(0L, settings.windowTokens)
    }

    // AC #5: the all-zero reply — the one answer for an unhosted, unbound or unnamed conversation. Every
    // zero is a READ value, not a manufactured default, and the decode succeeds rather than erroring.
    @Test
    fun reply_allZero_decodesToRetainedZeros() {
        val settings = decode(ALL_ZERO_REPLY)

        assertEquals("", settings.sessionId)
        assertEquals("", settings.model)
        assertEquals("", settings.effort)
        assertEquals("", settings.permissionMode)
        assertFalse(settings.yolo)
        assertEquals(EffectiveEffort.Unavailable, settings.effectiveEffort)
        assertEquals(0L, settings.usedTokens)
        assertEquals(0L, settings.windowTokens)
    }

    // AC #1: an arbitrary model string rides through untouched — never mapped through `Model`, never
    // trimmed or normalised, `""` included (which means "inherited default", not "absent").
    @Test
    fun model_arbitraryStrings_areRetainedVerbatim() {
        assertEquals("claude-opus-4-1-20250805[1m]", decode(modelReply("claude-opus-4-1-20250805[1m]")).model)
        assertEquals("", decode(modelReply("")).model)
    }

    // AC #2: every original field is required with no default — the wire emits all seven, so an absent
    // one is a malformed frame rather than a silently-defaulted zero. `permission_mode` in particular
    // must never default, since its zero carries meaning.
    @Test
    fun reply_missingAnyOriginalField_failsTheFrame() {
        val keys = listOf("session_id", "model", "effort", "yolo", "permission_mode", "used_tokens", "window_tokens")
        val full = MobileJson.parseToJsonElement(POPULATED_REPLY).jsonObject
        for (key in keys) {
            val withoutKey = JsonObject(full - key)
            assertThrows("omitting $key must fail the frame", SerializationException::class.java) {
                withoutKey.toSessionSettings()
            }
        }
    }

    // AC #2: a wrong-typed ORIGINAL field fails the frame too, at the same single decode boundary.
    @Test
    fun reply_wrongTypedOriginalField_failsTheFrame() {
        assertThrows(SerializationException::class.java) {
            decode("""{"session_id":5,"model":"o","effort":"h","yolo":false,"permission_mode":"","used_tokens":0,"window_tokens":0}""")
        }
    }

    // AC #2: a payload that is not an object at all fails before any presence read runs.
    @Test
    fun reply_nonObjectPayload_failsTheFrame() {
        assertThrows(SerializationException::class.java) { decode("""["session_settings"]""") }
    }

    // The wire is forward-compatible: an unknown key the daemon grows later is ignored, not fatal.
    @Test
    fun reply_unknownKey_isIgnored() {
        val settings =
            decode(
                """
                {"session_id":"sess-a","model":"opus","effort":"high","yolo":false,"permission_mode":"default",
                 "used_tokens":1,"window_tokens":2,"future_field":{"nested":true}}
                """.trimIndent(),
            )

        assertEquals("sess-a", settings.sessionId)
    }

    private fun decode(raw: String): SessionSettings = MobileJson.parseToJsonElement(raw).toSessionSettings()

    private companion object {
        const val CONVERSATION_ID = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"

        /** The protocol document's own populated example, `effective_effort` omitted here on purpose. */
        val POPULATED_REPLY =
            """
            {"session_id":"sess-a","model":"opus","effort":"high","yolo":false,
             "permission_mode":"default","used_tokens":12480,"window_tokens":200000}
            """.trimIndent()

        /** The one answer for an unhosted, unbound, unnamed or unwired conversation. */
        val ALL_ZERO_REPLY =
            """
            {"session_id":"","model":"","effort":"","yolo":false,
             "permission_mode":"","used_tokens":0,"window_tokens":0}
            """.trimIndent()

        /** A populated reply whose `effective_effort` is [raw] verbatim, or omitted when [raw] is null. */
        fun effectiveEffortReply(raw: String?): String {
            val key = if (raw == null) "" else ""","effective_effort":$raw"""
            return """
                {"session_id":"sess-a","model":"opus","effort":"high","yolo":false,
                 "permission_mode":"default","used_tokens":1,"window_tokens":2$key}
                """.trimIndent()
        }

        fun permissionReply(
            mode: String,
            yolo: Boolean,
        ): String =
            """
            {"session_id":"sess-a","model":"opus","effort":"high","yolo":$yolo,
             "permission_mode":"$mode","used_tokens":1,"window_tokens":2}
            """.trimIndent()

        fun modelReply(model: String): String =
            """
            {"session_id":"sess-a","model":"$model","effort":"","yolo":false,
             "permission_mode":"","used_tokens":0,"window_tokens":0}
            """.trimIndent()
    }
}

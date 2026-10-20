package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionProvenance
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ModelCatalogAdmissionTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLogs() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    @Test fun catalogSourceDoesNotReplaceCurrentSessionOrOtherReadings() {
        for (tag in listOf(null, "null", "\"predecessor\"", "\"borrowed\"", "\"current\"")) {
            val bindings = mutableMapOf<String, SessionProvenance>()
            val clears = mutableListOf<Pair<String, String?>>()
            val admission = SessionLiveAdmission(bindings, mutableMapOf()) { c, family -> clears += c to family }
            admission.summary("c", "current")
            val before = clears.toList()
            admission.admit(frame(tag = tag, clear = true))
            assertNotNull("catalog source $tag", admission.admit(frame(tag = tag)))
            assertEquals("current", admission.currentBinding("c"))
            assertEquals(SessionProvenance(JsonPrimitive("current")), bindings["c"])
            assertEquals(before + ("c" to "model_list"), clears)
            assertNull(admission.admit(frame(type = "stall", tag = "\"foreign\"")))
        }
    }

    @Test fun unresolvedCatalogBeforeFirstTurnDoesNotEstablishASession() {
        val bindings = mutableMapOf<String, SessionProvenance>()
        val admission = SessionLiveAdmission(bindings, mutableMapOf()) { _, _ -> }
        admission.admit(frame(clear = true))
        assertNotNull(admission.admit(frame()))
        assertNull(admission.currentBinding("c"))
        assertEquals(emptyMap<String, SessionProvenance>(), bindings)
        assertNull(admission.admit(frame(type = "stall")))
    }

    @Test fun catalogReplacementAndDuplicateClearAreConversationScoped() {
        val clears = mutableListOf<Pair<String, String?>>()
        val admission = SessionLiveAdmission(mutableMapOf(), mutableMapOf()) { c, family -> clears += c to family }
        for (c in listOf("c", "other")) {
            admission.admit(frame(c = c, tag = "\"old\"", clear = true))
            assertNotNull(admission.admit(frame(c = c, tag = "\"old\"")))
        }
        val count = clears.size
        admission.admit(frame(tag = "\"old\"", clear = true))
        assertEquals(count, clears.size)
        admission.admit(frame(tag = "\"new\"", clear = true))
        assertEquals(count + 1, clears.size)
        assertNull(admission.admit(frame(tag = "\"old\"")))
        assertNotNull(admission.admit(frame(tag = "\"new\"")))
        assertNotNull(admission.admit(frame(c = "other", tag = "\"old\"")))
    }

    @Test fun malformedCatalogEnvelopeCannotEstablishOrClearAnything() {
        val clears = mutableListOf<Pair<String, String?>>()
        val admission = SessionLiveAdmission(mutableMapOf(), mutableMapOf()) { c, family -> clears += c to family }
        for (bad in listOf(
            frame().copy(conversationId = JsonPrimitive(7)),
            frame().copy(sessionStateCleared = JsonPrimitive("false")),
            frame().copy(eventId = 1),
            frame().copy(historyEntryId = 1u),
            frame(c = "other").copy(conversationId = JsonPrimitive("c")),
            frame(clear = true).copy(inReplyTo = 1),
        )) {
            assertNull(admission.admit(bad))
        }
        assertEquals(emptyList<Pair<String, String?>>(), clears)
    }

    private fun frame(
        type: String = "model_list",
        c: String = "c",
        tag: String? = null,
        clear: Boolean = false,
    ): Envelope {
        val payload = if (clear) "{}" else """{"conversation_id":"$c","models":[],"dropped_models":0}"""
        val source = tag?.let { ",\"session_id\":$it" }.orEmpty()
        return MobileJson.decodeFromString(
            """{"id":1,"type":"$type","ts":"2026-10-20T00:00:00Z","conversation_id":"$c","session_state_cleared":$clear,"payload":$payload$source}""",
        )
    }
}

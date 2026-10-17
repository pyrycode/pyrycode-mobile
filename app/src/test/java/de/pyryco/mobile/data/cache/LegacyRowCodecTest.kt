package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyRowCodecTest {
    @Test
    fun canonicalRowSeamRoundTripsAndKeepsProofInputs() {
        val row = ThreadItem.MessageItem(Message("identity", "session", Role.User, "saved text", Instant.fromEpochSeconds(1), false))
        assertEquals(listOf(row), decodeLegacyRows(listOf(encodeLegacyRow(row))))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assertEquals(cachedThreadRowProof(row), digest.digest(encodeLegacyRow(row).toByteArray()).toHexString())
    }

    /** #2221: a subagent's tool and prose rows keep the Agent call they belong to across a reopen. */
    @Test
    fun subagentParentIdsRoundTripOnToolAndProseRows() {
        val tool =
            ThreadItem.MessageItem(
                Message(
                    "toolu_child",
                    "session",
                    Role.Tool,
                    "",
                    Instant.fromEpochSeconds(1),
                    false,
                    toolCall = ToolCall("Bash", "printf ok", "ok", parentToolUseId = "toolu_agent"),
                ),
            )
        val prose =
            ThreadItem.MessageItem(
                Message(
                    "prose",
                    "session",
                    Role.Assistant,
                    "child prose",
                    Instant.fromEpochSeconds(2),
                    false,
                    parentToolUseId = "toolu_agent",
                ),
            )
        assertEquals(listOf(tool, prose), decodeLegacyRows(listOf(encodeLegacyRow(tool), encodeLegacyRow(prose))))
    }

    /** A document saved before parents were kept still reads, with its rows at top level. */
    @Test
    fun rowsSavedBeforeParentsWereKept_readBackWithNoParent() {
        val saved =
            """{"message":{"id":"toolu_child","sessionId":"session","role":"Tool","content":"","timestamp":"1970-01-01T00:00:01Z",""" +
                """"tool":{"toolName":"Bash","input":"printf ok","output":"ok","status":"Done"}}}"""
        val message = (decodeLegacyRows(listOf(saved)).single() as ThreadItem.MessageItem).message
        assertEquals("", message.parentToolUseId)
        assertEquals("", message.toolCall?.parentToolUseId)
    }

    /** A main-thread row encodes exactly as before, so its stored row proof stays valid. */
    @Test
    fun mainThreadRows_encodeWithNoParentKey() {
        val tool =
            ThreadItem.MessageItem(
                Message("toolu_main", "session", Role.Tool, "", Instant.fromEpochSeconds(1), false, toolCall = ToolCall("Bash", "", "")),
            )
        val prose = ThreadItem.MessageItem(Message("prose", "session", Role.Assistant, "reply", Instant.fromEpochSeconds(2), false))
        assertFalse(encodeLegacyRow(tool).contains("parentToolUseId"))
        assertFalse(encodeLegacyRow(prose).contains("parentToolUseId"))
    }
}

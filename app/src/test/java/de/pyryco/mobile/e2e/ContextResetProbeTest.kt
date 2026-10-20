package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextResetProbeTest {
    @Test fun unsolicitedOldPush_cannotSatisfyResetProof_evenWithTheSamePercentage() =
        runTest {
            val rig = Rig()
            rig.probe.delivered(rig.connection, rig.context(30_031))
            assertFalse(rig.probe.reply.isCompleted)
            rig.reading = ContextUsage(26_181, 1_000_000, 3, null)
            rig.probe.delivered(rig.connection, rig.context(26_181, replyTo = 23))
            assertEquals(rig.reading, rig.probe.reply.await())
            rig.probe.assertValid()
        }

    @Test fun correlatedReply_mustBeTheExactAdmittedReading() =
        runTest {
            val rig = Rig()
            rig.probe.delivered(rig.connection, rig.context(26_181, replyTo = 23))
            val failure = runCatching { rig.probe.assertValid() }.exceptionOrNull()
            assertEquals("reset reply was not the exact admitted repository reading", failure?.message)
            assertTrue(rig.probe.reply.isCancelled)
        }

    @Test fun oldProducerOrConnection_cannotSatisfyResetProof() =
        runTest {
            val rig = Rig()
            rig.probe.delivered(Any(), rig.context(30_031, replyTo = 23))
            assertFalse(rig.probe.reply.isCompleted)
            val failure =
                runCatching {
                    rig.probe.delivered(
                        rig.connection,
                        rig
                            .context(
                                30_031,
                                replyTo = 23,
                            ).copy(
                                sessionProvenance = SessionProvenance(JsonPrimitive("old")),
                            ),
                    )
                }.exceptionOrNull()
            assertEquals(null, failure)
            assertEquals("reset context reply has a different producer", runCatching { rig.probe.assertValid() }.exceptionOrNull()?.message)
        }

    @Test fun anotherMessageOrRequest_isNotAResetOnlyProof() {
        val rig = Rig()
        assertNull(rig.probe.sending(rig.connection, frame("send_message", 24, """{"conversation_id":"c"}""")))
        assertThrows(IllegalStateException::class.java) { rig.probe.assertValid() }
        val duplicate = Rig()
        assertNull(duplicate.probe.sending(duplicate.connection, frame("request_context_usage", 25, """{"conversation_id":"c"}""")))
        assertThrows(IllegalStateException::class.java) { duplicate.probe.assertValid() }
    }

    private class Rig {
        val connection = Any()
        var reading: ContextUsage? = ContextUsage(30_031, 1_000_000, 3, null)
        private val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeContextUsage(conversationId: String) = flowOf(reading)
            }
        val probe = ContextResetProbe.Reading("host", "c", repository)

        init {
            probe.before(
                connection,
                frame(
                    "session_transition",
                    20,
                    """{"conversation_id":"c","previous_session_id":"old","new_session_id":"new","reason":"clear","occurred_at":"$TS","workspace_cwd":null}""",
                ),
            )
            checkNotNull(probe.sending(connection, frame("request_context_usage", 23, """{"conversation_id":"c"}"""))).complete(true)
        }

        fun context(
            used: Long,
            replyTo: Long? = null,
        ) = frame(
            "context_usage",
            30,
            """{"conversation_id":"c","model":"","total_tokens":$used,"max_tokens":1000000,"percentage":3,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0}""",
            replyTo,
        )
    }

    private companion object {
        const val TS = "2026-10-19T18:46:30.203976727Z"

        fun frame(
            type: String,
            id: Long,
            payload: String,
            replyTo: Long? = null,
        ) = Envelope(id, type, TS, MobileJson.parseToJsonElement(payload), inReplyTo = replyTo)
    }
}

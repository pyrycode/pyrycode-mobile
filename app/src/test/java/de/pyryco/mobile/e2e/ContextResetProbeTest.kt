package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.SessionProvenance
import de.pyryco.mobile.data.network.SessionTransitionPayloadDto
import de.pyryco.mobile.data.network.toBoundary
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
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

    @Test fun canonicalResetReplyWaitsForTheAdmittedBoundaryInEitherDeliveryOrder() =
        runTest {
            for (boundaryFirst in listOf(false, true)) {
                val rig = Rig(legacy = false)
                rig.reading = ContextUsage(26_181, 1_000_000, 3, null)
                if (boundaryFirst) rig.probe.replaced(requireNotNull(rig.boundary))
                rig.probe.delivered(rig.connection, rig.context(26_181, replyTo = 23))
                if (!boundaryFirst) {
                    assertFalse(rig.probe.reply.isCompleted)
                    rig.probe.replaced(requireNotNull(rig.boundary))
                }
                val message = "an accepted reset and its admitted boundary must certify the exact reply"
                assertTrue(message, rig.probe.reply.isCompleted)
                assertEquals(rig.reading, rig.probe.reply.await())
                rig.probe.assertValid()
            }
        }

    @Test fun canonicalResetCannotBorrowAnOldProducer() =
        runTest {
            val rig = Rig(legacy = false)
            rig.reading = ContextUsage(26_181, 1_000_000, 3, null)
            rig.probe.delivered(
                rig.connection,
                rig.context(26_181, replyTo = 23).copy(
                    sessionProvenance = SessionProvenance(JsonPrimitive("old")),
                ),
            )
            rig.probe.replaced(requireNotNull(rig.boundary))
            assertThrows(IllegalStateException::class.java) { rig.probe.assertValid() }
        }

    @Test fun refusedResetCannotCertifyAnOtherwiseMatchingContextReply() =
        runTest {
            val rig = Rig(legacy = false, resetAccepted = false)
            rig.reading = ContextUsage(26_181, 1_000_000, 3, null)
            rig.probe.replaced(requireNotNull(rig.boundary))
            rig.probe.delivered(rig.connection, rig.context(26_181, replyTo = 23))
            assertThrows(IllegalStateException::class.java) { rig.probe.assertValid() }
        }

    private class Rig(
        legacy: Boolean = true,
        resetAccepted: Boolean = true,
    ) {
        val connection = Any()
        var reading: ContextUsage? = ContextUsage(30_031, 1_000_000, 3, null)
        private val repository =
            object : ConversationRepository by FakeConversationRepository() {
                override fun observeContextUsage(conversationId: String) = flowOf(reading)
            }
        val probe = ContextResetProbe.Reading("host", "c", repository)

        private val transition =
            frame(
                "session_transition",
                20,
                """{"conversation_id":"c",
"previous_session_id":"old",
"new_session_id":"new",
"reason":"clear",
"occurred_at":"$TS",
"workspace_cwd":null}""",
            )
        private val transitionPayload =
            MobileJson.decodeFromJsonElement<SessionTransitionPayloadDto>(transition.payload)
        val boundary = transitionPayload.toBoundary()

        init {
            if (legacy) {
                probe.before(connection, transition)
            } else {
                probe
                    .sending(connection, frame("new_session", 19, """{"conversation_id":"c"}"""))
                    ?.complete(resetAccepted)
            }
            val contextRequest = frame("request_context_usage", 23, """{"conversation_id":"c"}""")
            val receipt = probe.sending(connection, contextRequest)
            if (legacy) checkNotNull(receipt)
            receipt?.complete(true)
        }

        fun context(
            used: Long,
            replyTo: Long? = null,
        ) = frame(
            "context_usage",
            30,
            """{"conversation_id":"c","model":"","total_tokens":$used,"max_tokens":1000000,"percentage":3,"categories":[],"dropped_categories":0}""",
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

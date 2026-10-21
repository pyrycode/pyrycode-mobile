package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordedPhoneTeardownTest {
    private val owner = "phone/host/1"
    private val query = frame(13, "thread_catch_up", """{"conversation_id":"chat","epoch":"epoch","version":28}""")
    private val reply =
        frame(
            48,
            "thread_items",
            """{"conversation_id":"chat","epoch":"epoch","version":28,"items":[],"final":true,"from_version":28}""",
            13,
        )
    private val end = SuiteStep("client", "control", frame(4, "test-control:test-end", """{"body":""}"""))

    @Test fun missingWorkAndAnEarlyCloseStillFail() {
        val tape = tape()
        assertFalse(tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        assertThrows(IllegalStateException::class.java) { tape.lifecycle("close", owner) }
        assertThrows(IllegalStateException::class.java) { tape.phoneWorkBeforeTestEndReady() }
    }

    @Test fun foreignWorkCannotReleaseTheRequiredQuery() {
        val variants =
            listOf(
                "phone/other-host/1" to query,
                "phone/host/0" to query,
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"other","epoch":"epoch","version":28}"""),
                    ),
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","epoch":"other","version":28}"""),
                    ),
                owner to
                    query.copy(
                        payload = MobileJson.parseToJsonElement("""{"conversation_id":"chat","epoch":"epoch","version":27}"""),
                    ),
            )
        for ((actualOwner, actual) in variants) {
            val tape = tape()
            assertFalse(tape.phoneWorkBeforeTestEndReady())
            assertThrows(IllegalStateException::class.java) { tape.send(actualOwner, actual.copy(id = 90)) }
            assertThrows(IllegalStateException::class.java) { tape.phoneWorkBeforeTestEndReady() }
        }
    }

    @Test fun aRequiredRepeatedQueryCannotBeOmittedOrSatisfiedByADuplicateRequestId() {
        val tape =
            tape(
                listOf(
                    SuiteStep("client", owner, query.copy(id = 14)),
                    SuiteStep("daemon", owner, reply.copy(id = 49, inReplyTo = 14)),
                ),
            )
        tape.send(owner, query.copy(id = 90))
        assertFalse("An identical later captured query remains required", tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.send(owner, query.copy(id = 90)) }
    }

    @Test fun readinessDoesNotConsumeTestEnd() {
        val tape = tape()
        val response = tape.send(owner, query.copy(id = 90)).deliveries.single()
        assertEquals(reply.copy(inReplyTo = 90), response.message)
        assertTrue(tape.phoneWorkBeforeTestEndReady())
        assertThrows(IllegalStateException::class.java) { tape.assertComplete() }
        tape.send("control", checkNotNull(end.message))
        tape.lifecycle("close", owner)
        tape.assertComplete()
    }

    @Test fun anObservationCheckpointBoundsTheForegroundWait() {
        val observation = SuiteStep("client", "control", frame(1, "test-control:checkpoint", """{"body":"reply-observed"}"""))
        val tape = RecordedSuiteTape(listOf(observation, SuiteStep("client", owner, query), end), literalProducerIdentities = true)
        assertTrue(tape.phoneWorkBeforeTestEndReady())
        tape.send("control", checkNotNull(observation.message))
        assertFalse(tape.phoneWorkBeforeTestEndReady())
    }

    private fun tape(extra: List<SuiteStep> = emptyList()): RecordedSuiteTape =
        RecordedSuiteTape(
            listOf(SuiteStep("client", owner, query), SuiteStep("daemon", owner, reply)) + extra + end + SuiteStep("close", owner),
            literalProducerIdentities = true,
        )

    private fun frame(
        id: Long,
        type: String,
        body: String,
        reply: Long? = null,
    ) = Envelope(id, type, "2026-10-21T00:00:00Z", MobileJson.parseToJsonElement(body), inReplyTo = reply)
}

package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StopHoldEvidenceTest {
    private val conversation = "55cb9ba3-7182-4b87-aee8-a8ddb72999cd"

    @Test
    fun observationsDistinguishStartupThinkingToolsAndEnd() {
        assertStage("no_observed_activity", emptyList())
        assertStage("user_echo_only", listOf(frame("message", "role" to "user")))
        assertStage("turn_activity_without_permission", listOf(frame("turn_state", "state" to "thinking")))
        assertStage("turn_activity_without_permission", listOf(frame("thinking_progress")))
        assertStage("tool_activity_without_permission", listOf(frame("tool_use")))
        assertStage("tool_activity_without_permission", listOf(frame("tool_result")))
        assertStage("turn_ended_before_permission", listOf(frame("turn_end", "stop_reason" to "refusal")))
        assertStage("permission_at_peer", listOf(frame("modal_shown", "class" to "permission")))
        // An idle initial snapshot and an unrelated choice prompt prove no running turn.
        assertStage("no_observed_activity", listOf(frame("turn_state", "state" to "idle"), frame("modal_shown", "class" to "choice")))
    }

    @Test
    fun phonePermissionSeparatesObserverGap() {
        assertTrue(evidence(emptyList(), true).contains("stage=permission_at_phone_only"))
        assertTrue(evidence(emptyList(), null).contains("phone_permission=unknown"))
        assertTrue(evidence(emptyList(), false).contains("phone_permission=false"))
        assertTrue(evidence(listOf(frame("modal_shown", "class" to "permission")), true).contains("stage=permission_at_peer"))
    }

    @Test
    fun otherConversationsAndUnknownStringsDoNotLeak() {
        val secret = "private instructions and pairing material\n".repeat(1000)
        val foreign = frame("modal_shown", "class" to "permission", "conversation_id" to "another")
        val frames =
            listOf(
                foreign,
                frame("turn_state", "state" to secret),
                frame("turn_end", "stop_reason" to secret, "outcome" to secret),
                frame("message", "role" to "assistant", "text" to secret),
                frame("tool_use", "tool_name" to secret, "input" to secret),
                frame("session_error", "code" to secret, "message" to secret),
                frame(secret, "text" to secret),
            )
        val summary = evidence(frames)
        assertTrue(summary.contains("permission_shown=0"))
        assertTrue(summary.contains("last_state=unknown"))
        assertTrue(summary.contains("last_stop=unknown"))
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("another"))
        assertTrue(summary.length < 700)
    }

    @Test
    fun malformedLabelsAreUnknownRatherThanAbsent() {
        val malformed =
            frame("turn_state").copy(
                payload = JsonObject(mapOf("conversation_id" to JsonPrimitive(conversation), "state" to JsonArray(emptyList()))),
            )
        val summary = evidence(listOf(malformed, frame("turn_end")))
        assertTrue(summary.contains("last_state=unknown"))
        assertTrue(summary.contains("last_stop=unknown"))
        assertTrue(evidence(emptyList()).contains("last_state=none"))
    }

    @Test
    fun assertionDuringSnapshotDoesNotHideWaitFailure() {
        val original = AssertionError("wait failed")
        val error =
            runCatching {
                withStopHoldDiagnostics(conversation, { 0L }, { throw AssertionError("private snapshot") }, {}) { throw original }
            }.exceptionOrNull()
        assertSame(original, error?.cause)
        assertTrue(error?.message.orEmpty().contains("evidence=unavailable"))
        assertFalse(error?.message.orEmpty().contains("private"))
    }

    @Test
    fun countsAndOutputStayBounded() {
        val summary = evidence(List(2000) { frame("thinking_progress") } + frame("turn_end", "stop_reason" to "cancelled"))
        assertTrue(summary.contains("thinking_progress=999"))
        assertTrue(summary.contains("turn_end=1"))
        assertTrue(summary.contains("last_stop=cancelled"))
        assertTrue(summary.contains("count_cap=999"))
        assertTrue(summary.length < 700)
    }

    @Test
    fun successEmitsStagesAndReturnsWithoutRetry() {
        val events = mutableListOf<String>()
        var waits = 0
        var snapshots = 0
        val result =
            withStopHoldDiagnostics(
                conversation,
                { 123L },
                {
                    snapshots++
                    "stage=permission_at_peer"
                },
                events::add,
            ) {
                waits++
                assertEquals(1, events.size)
                assertTrue(events.single().contains("event=stop_hold_submit"))
                "modal"
            }
        assertEquals("modal", result)
        assertEquals(1, waits)
        assertEquals(1, snapshots)
        assertEquals(2, events.size)
        assertTrue(events.last().contains("event=stop_hold_permission_ready"))
        assertTrue(events.all { it.contains("at_ms=123 conversation=$conversation") })
    }

    @Test
    fun failureRetainsEvidenceAndOriginalCause() {
        val events = mutableListOf<String>()
        val original = AssertionError("permission wait timed out")
        val error =
            runCatching {
                try {
                    withStopHoldDiagnostics(
                        conversation,
                        { 123L },
                        { "stage=turn_activity_without_permission" },
                        events::add,
                    ) { throw original }
                } finally {
                    events.add("cleanup")
                }
            }.exceptionOrNull()
        assertTrue(error is AssertionError)
        assertSame(original, error?.cause)
        assertTrue(error?.message.orEmpty().contains("event=stop_hold_permission_failed"))
        assertTrue(error?.message.orEmpty().contains("stage=turn_activity_without_permission"))
        assertEquals("cleanup", events.last())
        assertTrue(events[events.lastIndex - 1].contains("event=stop_hold_permission_failed"))
    }

    @Test
    fun cancellationPassesThrough() {
        val original = CancellationException("cancel")
        var snapshots = 0
        val error =
            runCatching {
                withStopHoldDiagnostics(conversation, { 0L }, {
                    snapshots++
                    "unused"
                }, {}) { throw original }
            }.exceptionOrNull()
        assertSame(original, error)
        assertEquals(0, snapshots)
    }

    @Test
    fun cancellationDuringSnapshotPassesThrough() {
        val original = CancellationException("cancel snapshot")
        val error =
            runCatching {
                withStopHoldDiagnostics(conversation, { 0L }, { throw original }, {}) { Unit }
            }.exceptionOrNull()
        assertSame(original, error)
    }

    @Test
    fun diagnosticFailureDoesNotHideWaitFailure() {
        val original = AssertionError("wait failed")
        val error =
            runCatching {
                withStopHoldDiagnostics(conversation, { 0L }, { error("secret diagnostic error") }, {}) { throw original }
            }.exceptionOrNull()
        assertSame(original, error?.cause)
        assertTrue(error?.message.orEmpty().contains("evidence=unavailable"))
        assertFalse(error?.message.orEmpty().contains("secret"))
    }

    @Test
    fun eachInvocationHasIndependentStages() {
        val events = mutableListOf<String>()
        for (time in listOf(1L, 2L)) {
            withStopHoldDiagnostics(conversation, { time }, { "stage=permission_at_peer" }, events::add) { Unit }
        }
        assertEquals(4, events.size)
        assertTrue(events.take(2).all { it.contains("at_ms=1 ") })
        assertTrue(events.drop(2).all { it.contains("at_ms=2 ") })
    }

    @Test
    fun correlationAcceptsOnlyCanonicalUuids() {
        val events = mutableListOf<String>()
        for (id in listOf("secret\n".repeat(1000), "1-1-1-1-1", conversation.uppercase())) {
            withStopHoldDiagnostics(id, { 0L }, { "stage=permission_at_peer" }, events::add) { Unit }
        }
        assertEquals(6, events.size)
        assertTrue(events.take(4).all { it.contains("conversation=unknown") })
        assertTrue(events.takeLast(2).all { it.contains("conversation=$conversation") })
        assertTrue(events.all { it.length < 800 && !it.contains("secret") })
    }

    private fun assertStage(
        stage: String,
        frames: List<Envelope>,
    ) {
        val summary = evidence(frames)
        assertTrue(summary, summary.contains("stage=$stage "))
    }

    private fun evidence(
        frames: List<Envelope>,
        phone: Boolean? = false,
    ): String = stopHoldEvidence(conversation, frames, phone)

    private fun frame(
        type: String,
        vararg fields: Pair<String, String>,
    ): Envelope =
        Envelope(
            id = 1,
            type = type,
            ts = "untrusted timestamp",
            payload = JsonObject((mapOf("conversation_id" to conversation) + fields).mapValues { JsonPrimitive(it.value) }),
        )
}

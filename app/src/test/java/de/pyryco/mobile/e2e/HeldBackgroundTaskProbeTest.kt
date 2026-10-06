package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldBackgroundTaskProbeTest {
    private val probe = HeldBackgroundTaskProbe("c", "held command")
    private val tool =
        frame(
            "tool_use",
            """"turn_id":"turn","tool_use_id":"tool","name":"Bash","input_summary":"","input":{"command":"held command","run_in_background":"true"}""",
        )
    private val start =
        frame(
            "background_task_started",
            """"task_id":"task","tool_call_id":"tool","description":"summary without command","task_type":"local_bash","truncated_fields":null""",
        )
    private val roster =
        frame(
            "background_task_roster",
            """"tasks":[{"task_id":"task","task_type":"local_bash","description":"unrelated summary","truncated_fields":null}],"dropped_tasks":0""",
        )

    @Test
    fun rosterBeforeStartAndToolCompletesJoin_withoutMatchingDescription() {
        assertNull(probe.taskId(listOf(roster, tool)))
        assertEquals("task", probe.taskId(listOf(roster, start, tool)))
        assertEquals("task", probe.taskId(listOf(tool, start, roster)))
    }

    @Test
    fun descriptionContainingCommandIsNotIdentity_andTruncatedKeysCannotJoin() {
        val prose =
            roster.copy(
                payload = MobileJson.parseToJsonElement(roster.payload.toString().replace("unrelated summary", "held command")),
            )
        assertNull(probe.taskId(listOf(prose)))
        val cut = start.copy(payload = MobileJson.parseToJsonElement(start.payload.toString().replace("null", "[\"tool_call_id\"]")))
        assertNull(probe.taskId(listOf(tool, cut, roster)))
        val other = tool.copy(payload = MobileJson.parseToJsonElement(tool.payload.toString().replace("\"c\"", "\"other\"")))
        assertNull(probe.taskId(listOf(other, start, roster)))
    }

    @Test
    fun onlyStoppedOrUncappedOmissionCompletes_notAcceptanceSilenceOrAnotherTask() {
        val empty = frame("background_task_roster", """"tasks":[],"dropped_tasks":0""")
        assertTrue(probe.completed(empty, "task"))
        assertFalse(probe.completed(roster, "task"))
        assertFalse(probe.completed(frame("background_task_roster", """"tasks":[],"dropped_tasks":1"""), "task"))
        assertTrue(probe.completed(frame("background_task_updated", """"task_id":"task","status":"stopped""""), "task"))
        assertFalse(probe.completed(frame("background_task_updated", """"task_id":"other","status":"stopped""""), "task"))
        assertFalse(probe.completed(frame("background_task_updated", """"task_id":"task","status":"completed""""), "task"))
        assertFalse(probe.completed(frame("ack", """"status":"accepted""""), "task"))
        assertNull(probe.taskId(listOf(tool, start, roster, empty)))
    }

    private fun frame(
        type: String,
        fields: String,
    ) = Envelope(1, type, "", MobileJson.parseToJsonElement("{\"conversation_id\":\"c\",$fields}"))
}

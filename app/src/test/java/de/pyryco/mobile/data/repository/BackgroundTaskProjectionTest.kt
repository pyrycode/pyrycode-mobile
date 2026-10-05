package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-connection background-task fold (#677, progress #1042) and the host-lifetime finished set it shares across
 * connections. Driven directly with envelopes: [BackgroundTaskProjection.apply] is synchronous.
 */
class BackgroundTaskProjectionTest {
    private val finished = FinishedBackgroundTasks()
    private val projection = BackgroundTaskProjection(finished)

    private fun roster(conversationId: String = "c1"): BackgroundTaskRoster? = projection.rosters.value[conversationId]

    private fun task(
        taskId: String,
        conversationId: String = "c1",
    ): BackgroundTask = checkNotNull(roster(conversationId)).tasks.single { it.taskId == taskId }

    @Test
    fun startBeforeEnrichedRoster_keepsItsJoinDescriptionAndCutReport() {
        projection.apply(started("t1", description = "full", truncated = listOf("description")))
        val enriched = row("t1", description = "short").replace("\"task_type\"", "\"tool_call_id\":\"other\",\"task_type\"")
        projection.apply(rosterFrame(rows = listOf(enriched)))
        assertEquals("toolu_t1", task("t1").toolCallId)
        assertEquals("full", task("t1").description)
        assertEquals(listOf("description"), task("t1").truncatedFields)
        projection.apply(rosterFrame(rows = listOf(row("t1"))))
        assertEquals("toolu_t1", task("t1").toolCallId)
    }

    @Test
    fun startWithUnknownJoin_keepsStartedDescriptionAndAcceptsRosterJoin() {
        val start =
            started("t1", description = "full").copy(
                payload =
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c1","task_id":"t1","tool_call_id":"","description":"full","task_type":"local_bash","truncated_fields":null}""",
                    ),
            )
        projection.apply(start)
        assertNull(task("t1").toolCallId)
        val enriched = row("t1", description = "short").replace("\"task_type\"", "\"tool_call_id\":\"toolu_t1\",\"task_type\"")
        projection.apply(rosterFrame(rows = listOf(enriched)))
        assertEquals("toolu_t1", task("t1").toolCallId)
        assertEquals("full", task("t1").description)
    }

    @Test
    fun replacementRoster_prunesStartedOriginAndAcceptsANewRosterJoin() {
        projection.apply(started("t1", description = "previous lifetime"))
        projection.apply(rosterFrame(emptyList()))
        val enriched = row("t1", description = "new lifetime").replace("\"task_type\"", "\"tool_call_id\":\"new_tool\",\"task_type\"")
        projection.apply(rosterFrame(listOf(enriched)))
        assertEquals("new_tool", task("t1").toolCallId)
        assertEquals("new lifetime", task("t1").description)
        projection.apply(rosterFrame(listOf(enriched.replace("new_tool", "enriched_again"))))
        assertEquals("enriched_again", task("t1").toolCallId)
    }

    @Test
    fun enrichedRosterBeforeStart_populatesJoinAndKeepsStartedDescriptionAfterwards() {
        val enriched = row("t1", description = "short").replace("\"task_type\"", "\"tool_call_id\":\"toolu_t1\",\"task_type\"")
        projection.apply(rosterFrame(rows = listOf(enriched)))
        assertEquals("toolu_t1", task("t1").toolCallId)

        projection.apply(started("t1", description = "full description"))
        projection.apply(rosterFrame(rows = listOf(enriched)))
        assertEquals("full description", task("t1").description)
    }

    @Test
    fun enrichedRosterJoinSurvivesUnknownRowsWhileRosterDescriptionsStillReplace() {
        val enriched = row("t1").replace("\"task_type\"", "\"tool_call_id\":\"toolu_t1\",\"task_type\"")
        projection.apply(rosterFrame(rows = listOf(enriched)))
        projection.apply(rosterFrame(rows = listOf(row("t1", description = "new roster label"))))
        assertEquals("toolu_t1", task("t1").toolCallId)
        assertEquals("new roster label", task("t1").description)

        val unknown = row("t1").replace("\"task_type\"", "\"tool_call_id\":\"\",\"task_type\"")
        projection.apply(rosterFrame(rows = listOf(unknown)))
        assertEquals("toolu_t1", task("t1").toolCallId)
    }

    @Test
    fun startThenComplete_finishesTheTaskAndKeepsStatusAndSummary() {
        projection.apply(started("t1"))
        assertEquals(1, roster()?.liveCount)

        projection.apply(terminal("t1", "completed", summary = "sleep 300"))

        val t1 = task("t1")
        assertTrue(t1.isFinished)
        assertEquals(BackgroundTaskUpdate("", "completed", "sleep 300", null), t1.finish)
        assertEquals(0, roster()?.liveCount)
        assertEquals(listOf("t1"), roster()?.tasks?.map { it.taskId })
    }

    @Test
    fun startThenFail_finishesTheTask() {
        projection.apply(started("t1"))
        projection.apply(terminal("t1", "failed"))

        assertTrue(task("t1").isFinished)
        assertEquals(0, roster()?.liveCount)
    }

    @Test
    fun twoStartsThenOneComplete_countsOne() {
        projection.apply(started("t1"))
        projection.apply(started("t2"))
        projection.apply(terminal("t1", "completed"))

        assertEquals(1, roster()?.liveCount)
        assertFalse(task("t2").isFinished)
    }

    @Test
    fun unrecognisedNonEmptyStatus_countsAsFinished() {
        projection.apply(started("t1"))
        projection.apply(terminal("t1", "vanished"))

        assertTrue(task("t1").isFinished)
    }

    @Test
    fun rosterRelistingACompletedTask_keepsItFinished() {
        projection.apply(started("t1"))
        projection.apply(started("t2"))
        projection.apply(terminal("t1", "completed"))

        projection.apply(rosterFrame(rows = listOf(row("t1"), row("t2"))))

        assertTrue(task("t1").isFinished)
        assertEquals("completed", task("t1").finish?.status)
        assertEquals(1, roster()?.liveCount)
    }

    @Test
    fun reconnectRosterRelistingACompletedTask_keepsOnlyTheFinishedMark() {
        projection.apply(started("t1"))
        projection.apply(terminal("t1", "completed"))

        val next = BackgroundTaskProjection(finished)
        assertNull(next.rosters.value["c1"])
        next.apply(rosterFrame(rows = listOf(row("t1", description = "relisted"))))

        val t1 =
            next.rosters.value
                .getValue("c1")
                .tasks
                .single()
        assertTrue(t1.isFinished)
        assertNull(t1.finish)
        assertEquals("relisted", t1.description)
        assertEquals(
            0,
            next.rosters.value
                .getValue("c1")
                .liveCount,
        )
    }

    @Test
    fun roster_replacesTheSetAndDroppedTasks_andDropsOmittedTasksUnfinished() {
        projection.apply(started("t1"))
        projection.apply(rosterFrame(rows = listOf(row("t2")), droppedTasks = 4))

        assertEquals(listOf("t2"), roster()?.tasks?.map { it.taskId })
        assertEquals(4, roster()?.droppedTasks)
        assertEquals(5, roster()?.liveCount)
        assertFalse(finished.contains("c1", "t1"))

        projection.apply(rosterFrame(rows = listOf(row("t2")), droppedTasks = 0))
        assertEquals(0, roster()?.droppedTasks)
    }

    @Test
    fun rosterOmittingAFinishedTask_forgetsItsFinishedMark() {
        projection.apply(started("t1"))
        projection.apply(terminal("t1", "completed"))
        assertTrue(finished.contains("c1", "t1"))

        projection.apply(rosterFrame(rows = emptyList()))

        assertFalse(finished.contains("c1", "t1"))
        projection.apply(rosterFrame(rows = listOf(row("t1"))))
        assertFalse(task("t1").isFinished)
    }

    @Test
    fun rosterBeforeStart_startedFieldsWinAndSurviveALaterRoster() {
        projection.apply(rosterFrame(rows = listOf(row("t1", description = "short", truncated = listOf("description")))))
        assertNull(task("t1").toolCallId)
        assertEquals(listOf("description"), task("t1").truncatedFields)

        projection.apply(started("t1", description = "the full command line", truncated = null))
        projection.apply(rosterFrame(rows = listOf(row("t1", description = "short", truncated = listOf("description")))))

        val t1 = task("t1")
        assertEquals("toolu_t1", t1.toolCallId)
        assertEquals("the full command line", t1.description)
        assertNull(t1.truncatedFields)
    }

    @Test
    fun terminalUpdateBeforeStart_isJoinedWhenTheStartArrives() {
        projection.apply(terminal("t1", "completed", summary = "done"))
        assertNull(roster())

        projection.apply(started("t1"))

        val t1 = task("t1")
        assertTrue(t1.isFinished)
        assertEquals("done", t1.finish?.summary)
    }

    @Test
    fun midLifeUpdateBeforeRoster_isJoinedWhenTheRosterListsTheTask() {
        projection.apply(midLife("t1", "{\"is_backgrounded\":true}"))
        projection.apply(rosterFrame(rows = listOf(row("t1"))))

        assertEquals("{\"is_backgrounded\":true}", task("t1").latestUpdate?.patch)
        assertFalse(task("t1").isFinished)
    }

    @Test
    fun updates_keepTheirOwnTruncation_andAMidLifeFrameNeverErasesTheFinish() {
        projection.apply(started("t1", truncated = listOf("description")))
        projection.apply(midLife("t1", "{\"k\":", truncated = listOf("patch")))
        projection.apply(terminal("t1", "completed", summary = "s", truncated = listOf("summary")))
        projection.apply(midLife("t1", "{}", truncated = null))

        val t1 = task("t1")
        assertEquals(listOf("description"), t1.truncatedFields)
        assertEquals(BackgroundTaskUpdate("{}", "", "", null), t1.latestUpdate)
        assertEquals(BackgroundTaskUpdate("", "completed", "s", listOf("summary")), t1.finish)
        assertTrue(t1.isFinished)
    }

    @Test
    fun noFrame_readsAsNothingReported_distinctFromAnEmptyRoster() {
        assertNull(roster())

        projection.apply(rosterFrame(rows = emptyList()))

        assertEquals(BackgroundTaskRoster(emptyList(), 0), roster())
    }

    @Test
    fun updateAlone_createsNoConversationEntry() {
        projection.apply(midLife("t1", "{}"))

        assertNull(roster())
    }

    @Test
    fun conversations_areIsolated() {
        projection.apply(started("t1", conversationId = "c1"))
        projection.apply(started("t1", conversationId = "c2"))
        projection.apply(terminal("t1", "completed", conversationId = "c1"))

        assertTrue(task("t1", "c1").isFinished)
        assertFalse(task("t1", "c2").isFinished)
        assertEquals(1, roster("c2")?.liveCount)
    }

    @Test
    fun repeatedStart_upsertsInPlace() {
        projection.apply(started("t1"))
        projection.apply(started("t2"))
        projection.apply(started("t1", description = "again"))

        assertEquals(listOf("t1", "t2"), roster()?.tasks?.map { it.taskId })
        assertEquals("again", task("t1").description)
    }

    @Test
    fun duplicatedTaskIdInOneRoster_yieldsOneTask() {
        projection.apply(rosterFrame(rows = listOf(row("t1", description = "first"), row("t2"), row("t1", description = "second"))))

        assertEquals(listOf("t1", "t2"), roster()?.tasks?.map { it.taskId })
        assertEquals("first", task("t1").description)
    }

    @Test
    fun negativeDroppedTasks_dropsTheFrame() {
        projection.apply(rosterFrame(rows = listOf(row("t1"))))

        projection.apply(rosterFrame(rows = emptyList(), droppedTasks = -5))

        assertEquals(listOf("t1"), roster()?.tasks?.map { it.taskId })
    }

    @Test
    fun liveCount_saturatesInsteadOfOverflowing() {
        projection.apply(rosterFrame(rows = listOf(row("t1")), droppedTasks = Int.MAX_VALUE))

        assertEquals(Int.MAX_VALUE, roster()?.liveCount)
    }

    @Test
    fun malformedFrame_changesNothing() {
        projection.apply(started("t1"))

        projection.apply(envelope("background_task_roster", """{"conversation_id":"c1","tasks":null,"dropped_tasks":0}"""))
        projection.apply(envelope("background_task_started", """{"conversation_id":"c1","task_id":"t2"}"""))
        projection.apply(envelope("background_task_updated", """["not","an","object"]"""))

        assertEquals(listOf("t1"), roster()?.tasks?.map { it.taskId })
        assertFalse(task("t1").isFinished)
    }

    @Test
    fun progress_joinsTheHeldTaskAndKeepsItsOwnDescriptionAndTruncation() {
        projection.apply(started("t1", description = "the opening command", truncated = listOf("description")))

        projection.apply(progress("t1", activity = "Reading alpha.txt", truncated = listOf("last_tool_name")))

        val t1 = task("t1")
        assertEquals("the opening command", t1.description)
        assertEquals(listOf("description"), t1.truncatedFields)
        assertEquals(
            BackgroundTaskProgress("Reading alpha.txt", "general-purpose", "Read", 100, 2, 4000, listOf("last_tool_name")),
            t1.progress,
        )
    }

    @Test
    fun laterProgress_replacesTheEarlierWhole_evenWithLowerCounters() {
        projection.apply(started("t1"))
        projection.apply(
            progress(
                "t1",
                activity = "Reading alpha.txt",
                tokens = 900,
                toolUses = 6,
                durationMs = 9000,
                truncated = listOf("description"),
            ),
        )

        projection.apply(progress("t1", activity = "Reading beta.txt", tokens = 50, toolUses = 1, durationMs = 200))

        assertEquals(BackgroundTaskProgress("Reading beta.txt", "general-purpose", "Read", 50, 1, 200, null), task("t1").progress)
    }

    @Test
    fun progressBeforeStart_isJoinedWhenTheStartArrives_andCreatesNoRosterAlone() {
        projection.apply(progress("t1", activity = "Reading alpha.txt"))
        assertNull(roster())

        projection.apply(started("t1"))

        assertEquals("Reading alpha.txt", task("t1").progress?.description)
    }

    @Test
    fun progressBeforeRoster_isJoinedWhenTheRosterListsTheTask() {
        projection.apply(progress("t1", activity = "Reading alpha.txt"))

        projection.apply(rosterFrame(rows = listOf(row("t1"))))

        assertEquals("Reading alpha.txt", task("t1").progress?.description)
    }

    @Test
    fun progressForATaskTheRosterOmits_isDiscarded() {
        projection.apply(progress("t1", activity = "Reading alpha.txt"))

        projection.apply(rosterFrame(rows = listOf(row("t2"))))
        projection.apply(started("t1"))

        assertNull(task("t1").progress)
    }

    @Test
    fun terminalUpdate_clearsProgress_andLaterProgressIsIgnored() {
        projection.apply(started("t1"))
        projection.apply(progress("t1"))

        projection.apply(terminal("t1", "completed"))
        assertNull(task("t1").progress)

        projection.apply(progress("t1"))
        assertNull(task("t1").progress)
        assertTrue(task("t1").isFinished)
    }

    @Test
    fun pendingProgressThenPendingTerminal_joinsFinishedWithNoProgress() {
        projection.apply(progress("t1"))
        projection.apply(terminal("t1", "completed"))

        projection.apply(started("t1"))

        assertTrue(task("t1").isFinished)
        assertNull(task("t1").progress)
    }

    @Test
    fun progressForATaskFinishedOnAnEarlierConnection_isIgnored() {
        projection.apply(started("t1"))
        projection.apply(terminal("t1", "completed"))

        val next = BackgroundTaskProjection(finished)
        next.apply(progress("t1"))
        next.apply(rosterFrame(rows = listOf(row("t1"))))
        next.apply(progress("t1"))

        val t1 =
            next.rosters.value
                .getValue("c1")
                .tasks
                .single()
        assertTrue(t1.isFinished)
        assertNull(t1.progress)
    }

    @Test
    fun malformedProgress_changesNothing() {
        projection.apply(started("t1"))
        projection.apply(progress("t1", activity = "Reading alpha.txt"))

        projection.apply(envelope("background_task_progress", """{"conversation_id":"c1","task_id":"t1","description":"x"}"""))
        projection.apply(
            envelope(
                "background_task_progress",
                """{"conversation_id":"c1","task_id":"t1","description":null,""" +
                    """"subagent_type":"s","last_tool_name":"Read","total_tokens":1,"tool_uses":1,"duration_ms":1,"truncated_fields":null}""",
            ),
        )

        assertEquals("Reading alpha.txt", task("t1").progress?.description)
    }

    companion object {
        fun progress(
            taskId: String,
            conversationId: String = "c1",
            activity: String = "Reading alpha.txt",
            tokens: Long = 100,
            toolUses: Long = 2,
            durationMs: Long = 4000,
            truncated: List<String>? = null,
        ): Envelope =
            envelope(
                "background_task_progress",
                """{"conversation_id":"$conversationId","task_id":"$taskId","description":"$activity",""" +
                    """"subagent_type":"general-purpose","last_tool_name":"Read","total_tokens":$tokens,""" +
                    """"tool_uses":$toolUses,"duration_ms":$durationMs,"truncated_fields":${json(truncated)}}""",
            )

        fun started(
            taskId: String,
            conversationId: String = "c1",
            description: String = "sleep 300",
            truncated: List<String>? = null,
        ): Envelope =
            envelope(
                "background_task_started",
                """{"conversation_id":"$conversationId","task_id":"$taskId","tool_call_id":"toolu_$taskId",""" +
                    """"description":"$description","task_type":"local_bash","truncated_fields":${json(truncated)}}""",
            )

        fun midLife(
            taskId: String,
            patch: String,
            conversationId: String = "c1",
            truncated: List<String>? = null,
        ): Envelope =
            envelope(
                "background_task_updated",
                """{"conversation_id":"$conversationId","task_id":"$taskId","patch":${JsonPrimitive(patch)},""" +
                    """"status":"","summary":"","truncated_fields":${json(truncated)}}""",
            )

        fun terminal(
            taskId: String,
            status: String,
            conversationId: String = "c1",
            summary: String = "",
            truncated: List<String>? = null,
        ): Envelope =
            envelope(
                "background_task_updated",
                """{"conversation_id":"$conversationId","task_id":"$taskId","patch":"",""" +
                    """"status":"$status","summary":"$summary","truncated_fields":${json(truncated)}}""",
            )

        fun row(
            taskId: String,
            description: String = "sleep 300",
            truncated: List<String>? = null,
        ): String = """{"task_id":"$taskId","task_type":"local_bash","description":"$description","truncated_fields":${json(truncated)}}"""

        fun rosterFrame(
            rows: List<String>,
            conversationId: String = "c1",
            droppedTasks: Int = 0,
        ): Envelope =
            envelope(
                "background_task_roster",
                """{"conversation_id":"$conversationId","tasks":[${rows.joinToString(",")}],"dropped_tasks":$droppedTasks}""",
            )

        fun envelope(
            type: String,
            payload: String,
        ): Envelope = Envelope(id = 1L, type = type, ts = "2026-05-08T10:33:18Z", payload = MobileJson.parseToJsonElement(payload))

        private fun json(fields: List<String>?): String = fields?.joinToString(",", "[", "]") { "\"$it\"" } ?: "null"
    }
}

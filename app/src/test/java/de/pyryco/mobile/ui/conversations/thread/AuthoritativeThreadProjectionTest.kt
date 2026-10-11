package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthoritativeThreadProjectionTest {
    private fun project(vararg items: de.pyryco.mobile.data.network.ThreadItemDto) =
        projectAuthoritativeThread("host", "conversation", authoritativeSnapshot(*items))

    private fun message(row: ThreadRow) = ((row as ThreadRow.Delivered).item as ThreadItem.MessageItem).message

    private fun ids(rows: List<ThreadRow>) = rows.map { it.listKey(0) }

    @Test fun allEightKindsUseExistingPresentations() {
        val p =
            project(
                authoritativeItem(1, "user_message"),
                authoritativeItem(2),
                authoritativeItem(
                    3,
                    "tool_call",
                    content = """{"name":"Read","input_summary":"file","result":{"result_summary":"safe output"}}""",
                ),
                authoritativeAgent(4, active = false, task = null),
                authoritativeItem(
                    5,
                    "session_divider",
                    content = """{"cause":"operator_reset","previous_session_id":"old","new_session_id":"new"}""",
                ),
                authoritativeItem(6, "compaction", content = """{"trigger":"manual","pre_tokens":100,"post_tokens":20}"""),
                authoritativeItem(7, "turn_end", content = """{"stop_reason":"error","error_category":"api"}"""),
                authoritativeItem(8, "notice", subtype = "banner", content = """{"text":"warning","level":"warning"}"""),
            )
        assertEquals(8, p.rows.size)
        assertEquals(Role.User, message(p.rows[0]).role)
        assertEquals(Role.Assistant, message(p.rows[1]).role)
        assertEquals(ToolCallStatus.Done, message(p.rows[2]).toolCall?.status)
        assertEquals("Agent", message(p.rows[3]).toolCall?.toolName)
        assertTrue((p.rows[4] as ThreadRow.Delivered).item is ThreadItem.SessionBoundary)
        assertTrue((p.rows[5] as ThreadRow.Delivered).item is ThreadItem.CompactionBoundary)
        assertTrue((p.rows[6] as ThreadRow.Delivered).item is ThreadItem.StoppedTurn)
        assertTrue((p.rows[7] as ThreadRow.Delivered).item is ThreadItem.Banner)
    }

    @Test fun shownAndPositiveOrderAloneAdmitContentIncludingNormalEndings() {
        val p =
            project(
                authoritativeItem(1, shown = false),
                authoritativeItem(2, order = null),
                authoritativeItem(3, order = 0),
                authoritativeItem(4, "turn_end", content = """{"stop_reason":"end_turn"}"""),
                authoritativeItem(5, "notice", subtype = "banner", content = """{"level":"info","text":"shown info"}"""),
            )
        assertEquals(2, p.rows.size)
        assertEquals(listOf("summary-4", "summary-5"), p.rows.map { (it as ThreadRow.Delivered).summaryText })
    }

    @Test fun unknownKindStatusSubtypeAndMalformedContentAreInertSummary() {
        val p =
            project(
                authoritativeItem(1, "future"),
                authoritativeItem(2, status = "future", active = true),
                authoritativeItem(3, "notice", subtype = "future"),
                authoritativeItem(4, content = "[]"),
            )
        assertEquals(listOf("summary-1", "summary-2", "summary-3", "summary-4"), p.rows.map { (it as ThreadRow.Delivered).summaryText })
    }

    @Test fun permutationDuplicateAndReplayCannotChangeOrderingOrIdentity() {
        val a = authoritativeItem(1, order = 20)
        val b = authoritativeItem(2, order = 10)
        val expected = ids(project(a, b).rows)
        for (input in listOf(arrayOf(a, b), arrayOf(b, a), arrayOf(a, b, a), arrayOf(b, b, a))) {
            assertEquals(expected, ids(project(*input).rows))
        }
        assertEquals("text-2", message(project(a, b).rows.first()).content)
        assertTrue(project().rows.isEmpty())
        assertEquals(1, project(a).rows.size)
    }

    @Test fun revisionsStatusAndOrderDoNotChangeKeysButEpochAndOwnerDo() {
        val before = project(authoritativeItem(1)).rows.single().listKey(0)
        val snap = authoritativeSnapshot(authoritativeItem(1, rev = 5, order = 8, status = "running", active = true))
        assertEquals(before, projectAuthoritativeThread("host", "conversation", snap).rows.single().listKey(0))
        for ((host, conversation, epoch) in listOf(
            Triple("other", "conversation", "epoch"),
            Triple("host", "other", "epoch"),
            Triple("host", "conversation", "new"),
        )) {
            assertNotEquals(before, projectAuthoritativeThread(host, conversation, snap.copy(epoch = epoch)).rows.single().listKey(0))
        }
        assertEquals(
            "new",
            message(project(authoritativeItem(1), authoritativeItem(1, rev = 9, content = """{"text":"new"}""")).rows.single()).content,
        )
    }

    @Test fun completeHistoricalAndActiveParentsOwnEachChildOnce() {
        val p =
            project(
                authoritativeItem(30, parent = 10),
                authoritativeAgent(20),
                authoritativeItem(31, "tool_call", parent = 20, content = """{"name":"Read"}"""),
                authoritativeAgent(10, active = false, endedOrder = 15),
                authoritativeItem(32, parent = 20),
            )
        val roots = p.rows.filterIsInstance<ThreadRow.Delivered>().filter { it.agentBlockId == message(it).id }
        assertEquals(2, roots.size)
        val root10 = roots.first { message(it).toolCall?.input == "agent-10" }.agentBlockId
        val root20 = roots.first { message(it).toolCall?.input == "agent-20" }.agentBlockId
        assertEquals(
            root10,
            p.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .first { message(it).content == "text-30" }
                .agentBlockId,
        )
        assertEquals(
            root20,
            p.rows
                .filterIsInstance<ThreadRow.Delivered>()
                .first { message(it).content == "text-32" }
                .agentBlockId,
        )
        assertEquals(p.rows.size, ids(p.rows).toSet().size)
        assertEquals(
            1,
            p.depths[message(p.rows.filterIsInstance<ThreadRow.Delivered>().first { message(it).toolCall?.toolName == "Read" }).id],
        )
    }

    @Test fun hiddenFactsRetainParentAndTaskInterpretationWithoutContent() {
        val p = project(authoritativeAgent(1, shown = false), authoritativeItem(3, parent = 1), authoritativeItem(2))
        assertEquals(2, p.rows.size)
        assertEquals(1, p.tasks.liveCount)
        assertNotNull((p.rows.last() as ThreadRow.Delivered).agentBlockId)
        assertEquals("text-3", message(p.rows.last()).content)
    }

    @Test fun twoActiveBackgroundAgentsPinInLaunchOrderButForegroundAndShellDoNot() {
        val shell =
            authoritativeItem(
                3,
                "tool_call",
                active = true,
                status = "running",
                content = """{"name":"Bash","task_id":"shell","task_link":{"task_type":"local_bash"}}""",
            )
        val p = project(authoritativeAgent(1), authoritativeAgent(2, task = null), shell, authoritativeItem(4), authoritativeAgent(5))
        val delivered = p.rows.filterIsInstance<ThreadRow.Delivered>()
        assertEquals(listOf("Agent", "Bash", null, "Agent", "Agent"), delivered.map { message(it).toolCall?.toolName })
        assertEquals(3, p.tasks.liveCount)
        assertEquals(listOf("task-1", "shell", "task-5"), p.tasks.tasks.map { it.taskId })
        assertEquals(2, p.rows.filterIsInstance<ThreadRow.AgentStartMarker>().size)
    }

    @Test fun terminalAgentUsesEndedAnchorAndNeverLaunchResultOrNewSession() {
        val p =
            project(
                authoritativeAgent(1, active = false, status = "ended_with_session", endedOrder = 4),
                authoritativeItem(2),
                authoritativeItem(5),
                authoritativeAgent(6, session = "new-session"),
            )
        val delivered = p.rows.filterIsInstance<ThreadRow.Delivered>()
        assertEquals("text-2", message(delivered[0]).content)
        assertEquals(ToolCallStatus.Done, message(delivered[1]).toolCall?.status)
        assertTrue(
            message(delivered[1])
                .toolCall
                ?.output
                .orEmpty()
                .contains("idle_sleep"),
        )
        assertEquals("old-session", message(delivered[1]).sessionId)
        assertEquals(ToolCallStatus.Running, message(delivered.last()).toolCall?.status)
        assertEquals(listOf(true, false), p.rows.filterIsInstance<ThreadRow.AgentStartMarker>().map { it.finished })
        assertEquals(1, p.tasks.liveCount)
        assertTrue(
            p.tasks.tasks
                .first()
                .isFinished,
        )
    }

    @Test fun settlementWithoutEndedOrderUsesOwnOrderAndKnownTaskFactsDeduplicate() {
        val p = project(authoritativeAgent(1, active = false), authoritativeItem(2), authoritativeAgent(3, task = "task-1"))
        assertEquals("Agent", message(p.rows.filterIsInstance<ThreadRow.Delivered>().first()).toolCall?.toolName)
        assertEquals(1, p.tasks.tasks.size)
        assertFalse(
            p.tasks.tasks
                .single()
                .isFinished,
        )
        assertTrue(project(authoritativeAgent(1, task = null)).tasks.tasks.isEmpty())
    }

    @Test fun unknownActiveAgentStillPinsAndCountsWhileDisplayingSummary() {
        val p = project(authoritativeAgent(1, status = "future"), authoritativeItem(2))
        assertEquals("summary-1", (p.rows.last() as ThreadRow.Delivered).summaryText)
        assertEquals(1, p.tasks.liveCount)
        assertFalse(
            p.rows
                .filterIsInstance<ThreadRow.AgentStartMarker>()
                .single()
                .finished,
        )
    }

    @Test fun unsupportedAgentContentCannotOverrideRecordedBackgroundPlacement() {
        val agent = authoritativeItem(1, "agent", active = true, content = """{"task_id":"t","task_link":{}}""")
        val p = project(agent, authoritativeItem(2))
        assertEquals("summary-1", (p.rows.last() as ThreadRow.Delivered).summaryText)
        assertEquals(1, p.tasks.liveCount)
        assertEquals(1, p.rows.filterIsInstance<ThreadRow.AgentStartMarker>().size)
    }

    @Test fun missingAndCyclicParentsCannotInventOwnersOrDuplicateRows() {
        val p = project(authoritativeItem(1, parent = 999), authoritativeItem(2, parent = 3), authoritativeItem(3, parent = 2))
        assertEquals(3, p.rows.size)
        assertTrue(p.rows.all { (it as ThreadRow.Delivered).agentBlockId == null })
    }

    @Test fun recordedTaskLinkWithoutTypeStillProvesBackgroundAndRosterOriginLinkIsRecordedContent() {
        val a =
            authoritativeItem(
                1,
                "agent",
                active = true,
                status = "running",
                content = """{"tool":"Task","task_id":"t","task_link":{"task_id":"t","tool_call_id":"call"}}""",
            )
        val shell =
            authoritativeItem(
                2,
                "tool_call",
                active = true,
                status = "running",
                content =
                    """
                    {
                    "name":"Bash","task_id":"shell",
                    "task_link":{"tasks":[{"task_id":"shell","task_type":"local_bash","description":"shell work"}]}}
                    """.trimIndent(),
            )
        val p = project(a, shell, authoritativeItem(3))
        assertEquals(2, p.tasks.liveCount)
        assertEquals("Task", message(p.rows.last()).toolCall?.toolName)
        assertEquals(
            "shell work",
            p.tasks.tasks
                .last()
                .description,
        )
    }

    @Test fun nestedAgentsKeepTheirOwnChildrenInDaemonOrder() {
        val nested = authoritativeItem(2, "agent", parent = 1, content = """{"name":"Task"}""")
        val p =
            project(
                authoritativeAgent(1),
                nested,
                authoritativeItem(4, parent = 2),
                authoritativeItem(3, parent = 2),
                authoritativeItem(5, parent = 1),
            )
        val delivered = p.rows.filterIsInstance<ThreadRow.Delivered>()
        val nestedId = message(delivered[1]).id
        assertEquals(nestedId, delivered[2].agentBlockId)
        assertEquals(listOf("text-3", "text-4"), delivered.subList(2, 4).map { message(it).content })
        assertEquals(2, p.depths[message(delivered[2]).id])
        assertEquals(5, delivered.map { it.listKey(0) }.toSet().size)
    }

    @Test fun attachmentsDenialAndDisplayBoundsReuseSafeComponents() {
        val p =
            project(
                authoritativeItem(1, "user_message", content = """{"text":"hello","attachment_ids":["a","b"]}"""),
                authoritativeItem(
                    2,
                    "tool_call",
                    status = "denied",
                    content = """{"name":"Bash","denial":{"message":"refused","decision_reason":"policy"}}""",
                ),
                authoritativeItem(3, "future", summary = "x".repeat(70000)),
            )
        assertEquals(listOf("a", "b"), message(p.rows[0]).attachments.map { it.attachmentId })
        assertEquals("refused", message(p.rows[1]).toolCall?.denial?.message)
        assertTrue((p.rows[2] as ThreadRow.Delivered).summaryText.orEmpty().length <= 65536)
    }

    @Test fun hiddenNestedParentsCannotDiscardShownDescendants() {
        for (order in listOf(2L, null, 0L)) {
            val hidden = authoritativeItem(2, "agent", order = order, parent = 1, shown = false, content = """{"name":"Task"}""")
            val p = project(authoritativeAgent(1), hidden, authoritativeItem(3, parent = 2))
            val delivered = p.rows.filterIsInstance<ThreadRow.Delivered>()
            assertEquals(2, delivered.size)
            assertEquals("text-3", message(delivered.last()).content)
            assertEquals(2, p.depths[message(delivered.last()).id])
        }
        val root = authoritativeItem(1, "agent", order = null, shown = false)
        val p = project(root, authoritativeItem(2, "agent", order = null, parent = 1, shown = false), authoritativeItem(3, parent = 2))
        assertEquals(listOf("text-3"), p.rows.map { message(it).content })
    }

    @Test fun presentationCannotMutateHeldFactsRevisionsOrCheckpoint() {
        val snap = authoritativeSnapshot(authoritativeAgent(1), authoritativeItem(2, shown = false), authoritativeItem(3, order = null))
        val raw = snap.items.map { it.raw }
        projectAuthoritativeThread("host", "conversation", snap)
        assertEquals(raw, snap.items.map { it.raw })
        assertEquals(100, snap.completedVersion)
        assertEquals(90, snap.checkpoint)
    }
}

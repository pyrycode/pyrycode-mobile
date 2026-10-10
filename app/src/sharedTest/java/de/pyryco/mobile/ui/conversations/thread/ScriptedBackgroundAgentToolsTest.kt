package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScriptedBackgroundAgentToolsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var harness: ScriptedThreadHarness

    @Before fun start() {
        harness = ScriptedThreadHarness(compose)
        harness.start()
    }

    @After fun close() = harness.close()

    @Test fun lateToolsRemainOwnedAcrossMainCompletion() {
        launch("agent", "task")
        use("early", "agent")
        harness.pushAssistantDelta("main", 0, "main reply")
        harness.pushTurnEnd("main")
        result("early", "agent")
        use("late-one", "agent")
        result("late-one", "agent")
        harness.pushAssistantDelta("later-main", 0, "later reply")
        use("foreground", "", "later-main")
        result("foreground", "", "later-main")
        use("late-two", "agent")
        result("late-two", "agent", failed = true)
        harness.pushTurnEnd("later-main")
        awaitTools("early", "late-one", "foreground", "late-two")

        val children = listOf("early", "late-one", "late-two")
        assertOwnership(children, finished = false)
        assertRenderedChildren(children)
        compose.onNodeWithText("main reply").assertExists()
        compose.onNodeWithText("later reply").assertExists()
        compose.onNodeWithText("foreground").assertExists()
        compose.runOnIdle { harness.collapseToolUses = true }
        val run = hasTestTag("tool-run:early")
        compose.onNode(run).assertExists()
        assertMembership(children, expanded = false)
        compose.onAllNodesWithText("late-one").assertCountEquals(0)
        compose.onAllNodesWithText("late-two").assertCountEquals(0)
        compose.onNodeWithTag("background-agent:agent").assertExists()
        compose.onNode(hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(run)).performClick()
        assertMembership(children, expanded = true)
        assertRenderedChildren(children)
        // Task completion changes the separate root, without reopening or reidentifying its children.
        frame(
            "background_task_updated",
            "\"task_id\":\"task\",\"patch\":\"\",\"status\":\"completed\",\"summary\":\"done\",\"truncated_fields\":null",
        )
        compose.waitUntil(5_000) { projected().filterIsInstance<ThreadRow.AgentStartMarker>().single().finished }
        assertOwnership(children, finished = true)
        assertMembership(children, expanded = true)
        compose.onNode(hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(run)).performClick()
        assertMembership(children, expanded = false)
        compose.onNodeWithTag("background-agent:agent").assertExists()
    }

    @Test fun invariantReplayAndInterleavingPreserveExclusiveOwnership() {
        launch("agent", "task")
        use("early", "agent")
        harness.pushTurnEnd("main")
        launch("other-agent", "other-task")
        use("other-child", "other-agent")
        use("foreground", "")
        // The grandchild arrives before its parent is loaded. It joins only once the chain exists.
        use("grandchild", "late-parent")
        use("late-parent", "agent")
        use("late-sibling", "agent")
        listOf("early", "grandchild", "late-parent", "late-sibling").forEach {
            result(
                it,
                if (it ==
                    "grandchild"
                ) {
                    "late-parent"
                } else {
                    "agent"
                },
            )
        }
        awaitTools("early", "grandchild", "late-parent", "late-sibling")
        val children = listOf("early", "grandchild", "late-parent", "late-sibling")
        val before = items()
        // Replay under another turn id cannot change ownership, identity, status or sibling order.
        children.reversed().forEach {
            use(it, if (it == "grandchild") "late-parent" else "agent", "later-main")
            result(it, if (it == "grandchild") "late-parent" else "agent", "later-main")
        }
        harness.pushAssistantDelta("replay-barrier", 0, "replay barrier")
        harness.pushTurnEnd("replay-barrier")
        compose.waitUntil(5_000) {
            items().filterIsInstance<ThreadItem.MessageItem>().any { it.message.content == "replay barrier" && !it.message.isStreaming }
        }
        assertEquals(before, items().filterNot { it is ThreadItem.MessageItem && it.message.content == "replay barrier" })
        val rows = projected().filterIsInstance<ThreadRow.Delivered>()
        val owned = rows.filter { it.agentBlockId == "agent" }.map { (it.item as ThreadItem.MessageItem).message.id }
        assertEquals(listOf("agent") + children, owned)
        assertEquals("other-agent", rows.single { (it.item as ThreadItem.MessageItem).message.id == "other-child" }.agentBlockId)
        assertEquals(null, rows.single { (it.item as ThreadItem.MessageItem).message.id == "foreground" }.agentBlockId)
        assertEquals(items().size - 2, rows.size) // Only the two lifecycle entries are nonvisual.
        assertEquals(2, toolNestingDepths(items())["grandchild"])
        val run = foldToolRuns(projected(), emptySet()).filterIsInstance<ThreadRow.ToolRun>().single()
        assertEquals(children, run.tools.map { it.id })
        assertEquals("early", run.runId)
    }

    private fun items() = runBlocking { harness.observeMessages().first() }

    private fun projected(): List<ThreadRow> {
        val items = items()
        return foldBackgroundAgentBlocks(foldQueuedRows(items, emptyList()), items, null)
    }

    private fun assertOwnership(
        children: List<String>,
        finished: Boolean,
    ) {
        val rows = projected().filterIsInstance<ThreadRow.Delivered>()
        val messages = rows.filter { it.agentBlockId == "agent" }.map { (it.item as ThreadItem.MessageItem).message }
        assertEquals(listOf("agent") + children, messages.map { it.id })
        assertEquals(if (finished) ToolCallStatus.Done else ToolCallStatus.Running, messages.first().toolCall?.status)
        assertEquals(listOf(ToolCallStatus.Done, ToolCallStatus.Done, ToolCallStatus.Failed), messages.drop(1).map { it.toolCall?.status })
        val run = foldToolRuns(projected(), emptySet()).filterIsInstance<ThreadRow.ToolRun>().single()
        assertEquals(children, run.tools.map { it.id })
        assertEquals("early", run.runId)
        assertEquals(rows.size, rows.map { (it.item as ThreadItem.MessageItem).message.id }.toSet().size)
    }

    private fun assertMembership(
        children: List<String>,
        expanded: Boolean,
    ) {
        compose.waitUntil(5_000) {
            val index = compose.onNode(hasScrollToNodeAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
            children.all { (index("msg:$it") >= 0) == (expanded || it == children.first()) }
        }
    }

    private fun assertRenderedChildren(children: List<String>) {
        val index = compose.onNode(hasScrollToNodeAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        assertTrue(children.zipWithNext().all { (first, next) -> index("msg:$first") > index("msg:$next") })
        assertTrue(children.all { index("msg:agent") > index("msg:$it") })
        children.forEach {
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(it))
            compose.onNodeWithText(it).assertIsDisplayed()
            compose.onAllNodesWithText(it).assertCountEquals(1)
            compose
                .onNode(
                    hasText(it) and hasAnyAncestor(hasContentDescription("Subagent step, level 1")),
                    useUnmergedTree = true,
                ).assertExists()
        }
    }

    private fun awaitTools(vararg ids: String) {
        compose.waitUntil(5_000) {
            val messages = items().filterIsInstance<ThreadItem.MessageItem>().map { it.message }
            ids.all { id -> messages.any { it.id == id && it.toolCall?.status != ToolCallStatus.Running } }
        }
        compose.waitForIdle()
    }

    private fun launch(
        agent: String,
        task: String,
    ) {
        use(agent, "", name = "Agent", input = "\"run_in_background\":\"true\"")
        result(agent, "")
        frame(
            "background_task_started",
            "\"task_id\":\"$task\",\"tool_call_id\":\"$agent\",\"task_type\":\"local_agent\",\"description\":\"held\",\"truncated_fields\":null",
        )
    }

    private fun use(
        id: String,
        parent: String,
        turn: String = "main",
        name: String = "Read",
        input: String = "\"file_path\":\"$id\"",
    ) = frame(
        "tool_use",
        "\"turn_id\":\"$turn\",\"tool_use_id\":\"$id\",\"parent_tool_use_id\":\"$parent\",\"name\":\"$name\",\"input_summary\":\"$id\",\"input\":{$input}",
    )

    private fun result(
        id: String,
        parent: String,
        turn: String = "main",
        failed: Boolean = false,
    ) = frame(
        "tool_result",
        "\"turn_id\":\"$turn\",\"tool_use_id\":\"$id\",\"parent_tool_use_id\":\"$parent\",\"is_error\":$failed,\"result_summary\":\"done\"",
    )

    private fun frame(
        type: String,
        fields: String,
    ) = harness.pushEnvelope(
        Envelope(
            id = 1,
            type = type,
            ts = "2026-10-09T10:00:00Z",
            payload = MobileJson.parseToJsonElement("{\"conversation_id\":\"c1\",$fields}"),
        ),
    )
}

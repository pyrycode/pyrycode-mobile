package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoritativeAgentOwnershipTest {
    @get:Rule val compose = createComposeRule()
    private var snapshot by mutableStateOf(family(active = true))
    private var opened by mutableStateOf(true)

    @Test fun exactToolOwnerSurvivesCompletionAndThreadReopen() = verifyOwnership(collapse = false)

    @Test fun exactToolOwnerSurvivesCollapseReopenAndThreadReopen() = verifyOwnership(collapse = true)

    private fun verifyOwnership(collapse: Boolean) {
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                if (opened) {
                    ThreadScreen(
                        state = ThreadUiState("conversation", "Channel", authoritativeThread = presentation()),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        collapseToolUses = collapse,
                    )
                }
            }
        }
        val owner = messageId(1)
        val otherOwner = messageId(2)
        val childRun = "tool-run:${messageId(10)}"

        fun toggleRun() {
            val header = hasText("Using tools: 2", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag(childRun))
            list().performScrollToNode(header)
            compose.onNode(header).performClick()
        }

        fun assertOwnedTools() {
            for (id in listOf(10, 11)) {
                assertTrue(list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:${messageId(id)}") >= 0)
                list().performScrollToNode(hasText("file-$id", substring = true))
                compose.onNode(tool(id) and ownedBy(owner), useUnmergedTree = true).assertIsDisplayed()
                compose.onAllNodes(tool(id) and ownedBy(otherOwner), useUnmergedTree = true).assertCountEquals(0)
            }
            list().performScrollToNode(hasText("file-20", substring = true))
            compose.onNode(tool(20) and ownedBy(otherOwner), useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodes(tool(20) and ownedBy(owner), useUnmergedTree = true).assertCountEquals(0)
            list().performScrollToNode(hasText("file-5", substring = true))
            compose.onNode(tool(5), useUnmergedTree = true).assertIsDisplayed()
            compose.onAllNodes(tool(5) and ownedBy(owner), useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodes(tool(5) and ownedBy(otherOwner), useUnmergedTree = true).assertCountEquals(0)
        }

        for (active in listOf(true, false)) {
            compose.runOnIdle { snapshot = family(active) }
            assertEquals(owner, messageId(1))
            if (collapse && active) toggleRun()
            assertOwnedTools()
            if (collapse) {
                toggleRun()
                compose.onAllNodes(hasTestTag(TOOL_ROW_TAG) and ownedBy(owner), useUnmergedTree = true).assertCountEquals(0)
                // The collapsed header reuses the first tool's key; its sibling must leave the list.
                assertTrue(list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:${messageId(10)}") >= 0)
                assertTrue(list().fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:${messageId(11)}") < 0)
                toggleRun()
                assertOwnedTools()
            }
            compose.runOnIdle { opened = false }
            compose.waitForIdle()
            compose.onNodeWithText("file-10", substring = true).assertDoesNotExist()
            compose.runOnIdle { opened = true }
            assertEquals(owner, messageId(1))
            if (collapse) toggleRun()
            assertOwnedTools()
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun ownedBy(owner: String) = hasAnyAncestor(hasTestTag("background-agent-child:$owner"))

    private fun tool(id: Int) = hasTestTag(TOOL_ROW_TAG) and hasAnyDescendant(hasText("file-$id", substring = true))

    private fun presentation() = projectAuthoritativeThread("host", "conversation", snapshot)

    private fun messageId(id: Int) =
        presentation()
            .items
            .filterIsInstance<ThreadItem.MessageItem>()
            .single { it.message.id.endsWith(":$id") }
            .message.id

    private fun family(active: Boolean) =
        authoritativeSnapshot(
            authoritativeAgent(1, active = active, endedOrder = if (active) null else 30),
            authoritativeAgent(2),
            child(5),
            child(10, parent = 1, active = active),
            child(11, parent = 10),
            child(20, parent = 2),
        )

    private fun child(
        id: Long,
        parent: Long? = null,
        active: Boolean = false,
    ) = authoritativeItem(
        id,
        "tool_call",
        parent = parent,
        active = active,
        status = if (active) "running" else "done",
        rev = if (active) id else id + 40,
        content =
            """{"name":"Read","input_summary":"file-$id","input":{"file_path":"file-$id"}${
                if (active) "" else """, "result":{"result_summary":"output-$id"}"""
            }}""",
    )
}

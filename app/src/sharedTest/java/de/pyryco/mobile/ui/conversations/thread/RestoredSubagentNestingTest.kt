package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * #2221: a thread reopened from the production cache draws a subagent's rows under their Agent row, as it
 * did before the reopen. Before the fix the cache dropped both parent ids and every restored row drew top level.
 */
@RunWith(AndroidJUnit4::class)
class RestoredSubagentNestingTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    private val ts = Instant.parse("2026-10-17T10:00:00Z")

    private fun subagentStep(level: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_tool_subagent_step, level)

    private fun tool(
        id: String,
        name: String,
        parent: String = "",
        fields: Map<String, String> = emptyMap(),
    ) = ThreadItem.MessageItem(
        Message(
            id,
            "s",
            Role.Tool,
            "",
            ts,
            false,
            toolCall = ToolCall(name, "", "", inputFields = fields, parentToolUseId = parent),
        ),
    )

    private val written =
        listOf(
            tool("fg_agent", "Agent"),
            tool("fg_child", "Grep", parent = "fg_agent"),
            tool("bg_agent", "Agent", fields = mapOf("run_in_background" to "true")),
            tool("bg_child", "Glob", parent = "bg_agent"),
            ThreadItem.MessageItem(Message("bg_prose", "s", Role.Assistant, "Child paragraph", ts, false, parentToolUseId = "bg_agent")),
            ThreadItem.MessageItem(Message("main", "s", Role.Assistant, "Main reply", ts, false)),
        )

    private val roster =
        BackgroundTaskRoster(
            tasks = listOf(BackgroundTask("task", "bg_agent", "local_agent", "Background agent", null, null, null, isFinished = false)),
            droppedTasks = 0,
        )

    private fun reopen(): List<ThreadItem> =
        runBlocking {
            val cache = FileConversationCache(folder.root, Dispatchers.Unconfined)
            cache.writeThread("host", "c", written).getOrThrow()
            FileConversationCache(folder.root, Dispatchers.Unconfined).readThread("host", "c")
        }

    @Test
    fun reopenedThread_keepsSubagentRowsUnderTheirAgent() {
        val restored = reopen()
        assertEquals(written, restored)
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state = ThreadUiState("c", "Channel", items = restored, hasMessages = true, backgroundTasks = roster),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    collapseToolUses = false,
                )
            }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Grep"))
        compose.onNode(hasContentDescription(subagentStep(1)) and hasText("Grep")).assertExists()
        list.performScrollToNode(hasText("Glob"))
        compose.onNode(hasContentDescription(subagentStep(1)) and hasText("Glob")).assertExists()
        list.performScrollToNode(hasText("Child paragraph", substring = true))
        compose
            .onNode(hasText("Child paragraph", substring = true) and hasAnyAncestor(hasTestTag("background-agent-child:bg_agent")))
            .assertExists()
        compose.onNode(hasContentDescription(subagentStep(1)) and hasText("Agent")).assertDoesNotExist()
    }
}

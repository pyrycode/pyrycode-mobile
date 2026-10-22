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
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
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

    private fun subagentStep(level: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_tool_subagent_step, level)

    private val written =
        SavedAuthoritativeThread(
            "epoch",
            listOf(
                """{"id":1,"rev":1,"kind":"agent","status":"done","active":false,"shown":true,"order":1,"summary":"","content":{"name":"Agent"}}""",
                """{"id":2,"rev":2,"kind":"tool_call","parent":1,"status":"done","active":false,"shown":true,"order":2,"summary":"","content":{"name":"Grep"}}""",
                """{"id":3,"rev":3,"kind":"agent","status":"running","active":true,"shown":true,"order":3,"summary":"","content":{"name":"Agent","task_id":"task","task_link":{"description":"Background agent"},"input":{"run_in_background":true}}}""",
                """{"id":4,"rev":4,"kind":"tool_call","parent":3,"status":"done","active":false,"shown":true,"order":4,"summary":"","content":{"name":"Glob"}}""",
                """{"id":5,"rev":5,"kind":"assistant_message","parent":3,"status":"done","active":false,"shown":true,"order":5,"summary":"","content":{"text":"Child paragraph"}}""",
                """{"id":6,"rev":6,"kind":"assistant_message","status":"done","active":false,"shown":true,"order":6,"summary":"","content":{"text":"Main reply"}}""",
            ).map { MobileJson.parseToJsonElement(it).jsonObject },
            6,
            0,
        )

    private fun reopen(): SavedAuthoritativeThread =
        runBlocking {
            val cache = FileConversationCache(folder.root, Dispatchers.Unconfined)
            cache.writeAuthoritativeThread("host", "c", written).getOrThrow()
            requireNotNull(FileConversationCache(folder.root, Dispatchers.Unconfined).readAuthoritativeThread("host", "c"))
        }

    @Test
    fun reopenedThread_keepsSubagentRowsUnderTheirAgent() {
        val restored = reopen()
        assertEquals(written, restored)
        val projection = projectAuthoritativeThread("host", "c", restored.toSnapshot())
        compose.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            "c",
                            "Channel",
                            items = projection.items,
                            hasMessages = true,
                            backgroundTasks = projection.tasks,
                            displayRows = projection.rows,
                        ),
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
            .onNode(
                hasText("Child paragraph", substring = true) and hasAnyAncestor(hasTestTag("background-agent-child:${projection.scope}:3")),
            ).assertExists()
        compose.onNode(hasContentDescription(subagentStep(1)) and hasText("Agent")).assertDoesNotExist()
    }
}

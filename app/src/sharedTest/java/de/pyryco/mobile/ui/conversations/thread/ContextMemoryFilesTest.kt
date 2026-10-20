package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.network.ContextUsagePayloadDto
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toReading
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContextMemoryFilesTest {
    @get:Rule val rule = createComposeRule()
    private var screen by mutableStateOf(ThreadUiState("c1", "Thread"))

    @Test
    fun memoryAndMcpExpandIndependentlyInSheetOrder() {
        screen = screen.copy(runConfig = config(reading("[]", 3)), contextSheetOpen = true)
        mountThread()
        val memory = rule.onNodeWithTag("context_memory_toggle")
        val mcp = rule.onNodeWithTag("context_mcp_toggle")
        assertTrue(mcp.getUnclippedBoundsInRoot().top < memory.getUnclippedBoundsInRoot().top)
        rule.onNodeWithText("3 files").assertIsDisplayed()
        rule.onNodeWithText("5 tools").assertIsDisplayed()
        mcp.performSemanticsAction(SemanticsActions.Expand) { it() }
        memory.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        memory.performScrollTo().performSemanticsAction(SemanticsActions.Expand) { it() }
        mcp.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("+5 more").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("+3 more").performScrollTo().assertIsDisplayed()
        mcp.performScrollTo().performSemanticsAction(SemanticsActions.Collapse) { it() }
        memory.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("+3 more").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun memoryFilesExpandFromFooterWithOrderedInertRows() {
        val reading =
            reading(
                """[{"path":"/a/b/c/d/last.md","type":"file:///secret","tokens":12400},{"path":"duplicate","type":"unknown","tokens":2000},{"path":"duplicate","type":"other","tokens":3000}]""",
                7,
            )
        screen = screen.copy(runConfig = config(reading))
        mountThread()
        rule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG).performTouchInput { click() }
        val toggle = rule.onNodeWithTag("context_memory_toggle")
        toggle.assert(hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        toggle.assert(SemanticsMatcher("accessible expansion") { it.config.contains(SemanticsActions.Expand) })
        val bounds = toggle.getUnclippedBoundsInRoot()
        assertTrue((bounds.bottom - bounds.top).value >= 48f)
        assertTrue(rule.onNodeWithText("Categories").getUnclippedBoundsInRoot().top < bounds.top)
        rule.onNodeWithText("10 files").assertIsDisplayed()
        rule.onNodeWithText(".../b/c/d/last.md").assertDoesNotExist()
        toggle.performTouchInput { click() }
        rule.onNodeWithTag("context_memory_file_0").onChildAt(0).assertTextEquals(".../b/c/d/last.md")
        rule.onNodeWithTag("context_memory_file_1").onChildAt(0).assertTextEquals("duplicate")
        rule.onNodeWithTag("context_memory_file_1").onChildAt(1).assertTextEquals("2k")
        rule.onNodeWithTag("context_memory_file_2").onChildAt(0).assertTextEquals("duplicate")
        rule.onNodeWithTag("context_memory_file_2").onChildAt(1).assertTextEquals("3k")
        rule.onNodeWithText("12.4k").assertIsDisplayed()
        assertTrue(
            rule.onNodeWithTag("context_memory_file_0").getUnclippedBoundsInRoot().top <
                rule.onNodeWithTag("context_memory_file_1").getUnclippedBoundsInRoot().top,
        )
        rule.onNodeWithText("file:///secret").assertDoesNotExist()
        rule.onNodeWithTag("context_memory_file_0").onChildAt(0).assert(
            SemanticsMatcher("inert Text") {
                !it.config.contains(SemanticsActions.OnClick)
            },
        )
        rule.onNodeWithText("+7 more").performScrollTo().assertIsDisplayed()
        toggle.performScrollTo().performSemanticsAction(SemanticsActions.Collapse) { it() }
        rule.onNodeWithTag("context_memory_file_0").assertDoesNotExist()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG).performTouchInput { click() }
        rule.onNodeWithTag("context_memory_toggle").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test
    fun memoryFilesReplaceAndHideForRememberedCodexPendingAndEmpty() {
        val first = reading("""[{"path":"old.md","type":"memory","tokens":1}]""")
        screen = screen.copy(runConfig = config(first), contextSheetOpen = true)
        mountThread()
        rule.onNodeWithTag("context_memory_toggle").performClick()
        rule.onNodeWithText("old.md").assertIsDisplayed()
        val replacement = reading("""[{"path":"new.md","type":"memory","tokens":2}]""")
        update(replacement)
        rule.onNodeWithText("old.md").assertDoesNotExist()
        rule.onNodeWithText("new.md").assertIsDisplayed()
        update(replacement.copy(asOf = Instant.parse("2026-10-19T00:00:00Z")))
        rule.onNodeWithTag("context_memory_toggle").assertDoesNotExist()
        update(replacement)
        rule.onNodeWithText("new.md").assertDoesNotExist()
        rule.onNodeWithTag("context_memory_toggle").performClick()
        rule.runOnIdle { screen = screen.copy(agent = ConversationAgent.Codex) }
        rule.onNodeWithTag("context_memory_toggle").assertDoesNotExist()
        rule.runOnIdle { screen = screen.copy(agent = ConversationAgent.Claude, runConfig = ThreadRunConfig()) }
        rule.onNodeWithTag("context_memory_toggle").assertDoesNotExist()
        update(reading("[]"))
        rule.onNodeWithTag("context_memory_toggle").assertDoesNotExist()
    }

    @Test
    fun memoryFilesDroppedOnlyAndHugeCounts() {
        screen = screen.copy(runConfig = config(reading("[]", 3)), contextSheetOpen = true)
        mountThread()
        rule.onNodeWithText("3 files").assertIsDisplayed()
        rule.onNodeWithText("+3 more").assertDoesNotExist()
        rule.onNodeWithTag("context_memory_toggle").performSemanticsAction(SemanticsActions.Expand) { it() }
        rule.onNodeWithText("+3 more").assertIsDisplayed()
        update(reading("""[{"path":"one.md","type":"any","tokens":1}]""", Long.MAX_VALUE))
        rule.onNodeWithText("9223372036854775808 files").assertIsDisplayed()
        rule.onNodeWithText("+9223372036854775807 more").assertIsDisplayed()
        update(reading("""[{"path":"one.md","type":"any","tokens":1}]"""))
        rule.onNodeWithText("1 file").assertIsDisplayed()
    }

    @Test
    fun memoryFilesLongRowsEllipsizeWithoutLosingTokensAndScroll() {
        val path = "<a href='file:secret'>".repeat(20)
        val files =
            "[{\"path\":\"$path\",\"type\":\"any\",\"tokens\":12400}," +
                (1..40).joinToString(",") { "{\"path\":\"row-$it.md\",\"type\":\"any\",\"tokens\":$it}" } +
                "]"
        screen = screen.copy(runConfig = config(reading(files)), contextSheetOpen = true)
        mountThread()
        rule.onNodeWithTag("context_memory_toggle").performClick()
        val layouts = mutableListOf<TextLayoutResult>()
        val name = rule.onNodeWithTag("context_memory_file_0").onChildAt(0)
        name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(0))
        val tokens = rule.onNodeWithText("12.4k").assertIsDisplayed()
        assertTrue(name.getUnclippedBoundsInRoot().right < tokens.getUnclippedBoundsInRoot().left)
        rule.onNodeWithTag("context_memory_file_40").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("row-40.md").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }

    private fun update(reading: ContextUsage) {
        rule.runOnIdle { screen = screen.copy(runConfig = config(reading)) }
    }

    private fun config(reading: ContextUsage) =
        ThreadRunConfig(
            contextUsage = reading,
            contextUsedTokens = reading.totalTokens,
            contextMaxTokens = reading.maxTokens,
            contextPercent = 10,
        )

    private fun reading(
        files: String,
        dropped: Long = 0,
    ): ContextUsage =
        checkNotNull(
            MobileJson
                .decodeFromString<ContextUsagePayloadDto>(
                    """{"conversation_id":"c1","model":"claude","total_tokens":10,"max_tokens":100,"percentage":10,"categories":[],"dropped_categories":2,"mcp_tools":[],"dropped_mcp_tools":5,"memory_files":$files,"dropped_memory_files":$dropped}""",
                ).toReading(),
        )

    private fun mountThread() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ThreadScreen(screen, {}, {}, ConnectionState.Connected, {}, onOverflowEvent = {
                    when (it) {
                        ThreadEvent.ContextOpen -> screen = screen.copy(contextSheetOpen = true)
                        ThreadEvent.ContextDismiss -> screen = screen.copy(contextSheetOpen = false)
                        else -> Unit
                    }
                })
            }
        }
    }
}

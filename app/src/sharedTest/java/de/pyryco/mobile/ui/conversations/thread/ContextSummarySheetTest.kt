package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ContextCategory
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
class ContextSummarySheetTest {
    @get:Rule val rule = createComposeRule()
    private var screen by mutableStateOf(ThreadUiState("c1", "Thread"))

    @Test
    fun footerHasClientOwnedAccessibleOpenActionEvenWhenUnavailable() {
        rule.setContent {
            PyrycodeMobileTheme {
                ThreadComposerFooter(
                    runConfig = ThreadRunConfig(),
                    onOpen = {},
                    onStatusClick = {},
                    onAnchorChanged = { _, _ -> },
                )
            }
        }
        rule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG).assert(hasClickAction()).assert(
            SemanticsMatcher("client-owned action label") {
                it.config[SemanticsActions.OnClick].label == "Open context"
            },
        )
    }

    @Test
    fun footerOpensAndDismisses() {
        mountThread()
        for (route in listOf("Done", "Close", "Back")) {
            rule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG).performTouchInput { click() }
            rule.onNodeWithText("Context").assertIsDisplayed()
            rule.onNodeWithText("A context reading arrives after the next turn.").assertIsDisplayed()
            rule.onNodeWithText("Categories").assertDoesNotExist()
            when (route) {
                "Done" -> rule.onNodeWithText("Done").performClick()
                "Close" -> rule.onNodeWithContentDescription("Close").performClick()
                else -> Espresso.pressBack()
            }
            rule.onNodeWithText("Context").assertDoesNotExist()
        }
    }

    @Test
    fun accessibleTargetAndAdjacentActionsRoutePhysicalEdgeTaps() {
        mountThread()
        val target = rule.onNodeWithTag(CONTEXT_USAGE_TEST_TAG)
        val bounds = target.fetchSemanticsNode().touchBoundsInRoot
        val actions = rule.onNode(hasText("Actions") and hasClickAction())
        assertTrue(bounds.width >= 48f && bounds.height >= 48f)
        assertTrue(bounds.right <= actions.fetchSemanticsNode().touchBoundsInRoot.left)
        val local = target.fetchSemanticsNode().boundsInRoot
        for (point in listOf(
            Offset(1f, local.height / 2),
            Offset(local.width - 1f, local.height / 2),
            Offset(
                local.width / 2,
                bounds.top - local.top + 1f,
            ),
            Offset(local.width / 2, bounds.bottom - local.top - 1f),
        )) {
            target.performTouchInput { click(point) }
            rule.onNodeWithText("Context").assertIsDisplayed()
            rule.onNodeWithText("Done").performClick()
        }
        actions.performTouchInput { click(Offset(1f, centerY)) }
        rule.onNodeWithText("Reset session").assertIsDisplayed()
        rule.onNodeWithText("Context").assertDoesNotExist()
    }

    @Test
    fun populatedReadingUpdatesAndClampsBars() {
        val reading =
            ContextUsage(
                84_200,
                200_000,
                99,
                null,
                "claude-sonnet",
                listOf(
                    ContextCategory("Messages", 12_400),
                    ContextCategory("Huge", Long.MAX_VALUE),
                    ContextCategory("Negative", Long.MIN_VALUE),
                ),
                2,
            )
        screen = screen.copy(contextSheetOpen = true, runConfig = config(reading))
        mountSheet()
        rule.onNodeWithText("claude-sonnet").assertIsDisplayed()
        rule.onNodeWithText("84.2k of 200k tokens").assertIsDisplayed()
        rule.onNodeWithText("42%").assertIsDisplayed()
        rule.onNodeWithText("12.4k").assertIsDisplayed()
        rule.onNodeWithText("+2 more").assertIsDisplayed()
        assertBar(0, .062f)
        assertBar(1, 1f)
        assertBar(2, 0f)
        val first = rule.onNodeWithText("Messages").getUnclippedBoundsInRoot()
        assertTrue(first.top < rule.onNodeWithText("Huge").getUnclippedBoundsInRoot().top)
        rule.runOnIdle { screen = screen.copy(runConfig = config(reading.copy(totalTokens = 1000, maxTokens = 0))) }
        rule.onNodeWithText("1k of 0 tokens").assertIsDisplayed()
        rule.onNodeWithText("Not reported yet").assertIsDisplayed()
        assertBar(0, 0f)
        assertBar(1, 0f)
    }

    @Test
    fun rememberedReadingHidesCategories() {
        val fresh = ContextUsage(10, 100, 10, null, "model", listOf(ContextCategory("Private category", 10)), 4)
        screen = screen.copy(contextSheetOpen = true, runConfig = config(fresh.copy(asOf = Instant.parse("2026-10-19T00:00:00Z"))))
        mountSheet()
        rule.onNodeWithText("10 of 100 tokens").assertIsDisplayed()
        rule.onNodeWithText("Categories").assertDoesNotExist()
        rule.onNodeWithText("Private category").assertDoesNotExist()
        rule.runOnIdle { screen = screen.copy(runConfig = config(fresh)) }
        rule.onNodeWithText("Private category").assertIsDisplayed()
        rule.onNodeWithText("+4 more").assertIsDisplayed()
        rule.runOnIdle { screen = screen.copy(runConfig = config(fresh.copy(categories = emptyList(), droppedCategories = 0))) }
        rule.onNodeWithText("Categories").assertDoesNotExist()
        rule.runOnIdle {
            screen =
                screen.copy(
                    runConfig = config(fresh.copy(categories = emptyList(), droppedCategories = Long.MAX_VALUE)),
                )
        }
        rule.onNodeWithText("Categories").assertIsDisplayed()
        rule.onNodeWithText("+9223372036854775807 more").assertIsDisplayed()
    }

    @Test
    fun codexShowsOnlyTotalsOrPending() {
        screen =
            screen.copy(
                contextSheetOpen = true,
                agent = ConversationAgent.Codex,
                runConfig = ThreadRunConfig(contextUsedTokens = 84_200, contextMaxTokens = 272_000, contextPercent = 31),
            )
        mountSheet()
        rule.onNodeWithText("84.2k of 272k tokens").assertIsDisplayed()
        rule.onNodeWithText("31%").assertIsDisplayed()
        rule.onNodeWithText("Categories").assertDoesNotExist()
        rule.runOnIdle {
            screen =
                screen.copy(runConfig = config(ContextUsage(10, 100, 10, null, "hidden", listOf(ContextCategory("hidden category", 10)))))
        }
        rule.onNodeWithText("hidden").assertDoesNotExist()
        rule.onNodeWithText("hidden category").assertDoesNotExist()
        rule.runOnIdle { screen = screen.copy(runConfig = ThreadRunConfig(contextUsedTokens = 2, contextMaxTokens = 0)) }
        rule.onNodeWithText("A context reading arrives after the next turn.").assertIsDisplayed()
    }

    @Test
    fun claudeWithOnlySettingsStillWaitsForItsOwnReading() {
        screen =
            screen.copy(
                contextSheetOpen = true,
                runConfig = ThreadRunConfig(contextUsedTokens = 84_200, contextMaxTokens = 272_000, contextPercent = 31),
            )
        mountSheet()
        rule.onNodeWithText("A context reading arrives after the next turn.").assertIsDisplayed()
        rule.onNodeWithText("84.2k of 272k tokens").assertDoesNotExist()
        rule.onNodeWithText("31%").assertDoesNotExist()
    }

    @Test
    fun longInertNamesEllipsizeAndContentScrolls() {
        val model = "<a href='file:///secret'>model</a>".repeat(12)
        val name = "A category with a very long inert name ".repeat(8)
        val categories = listOf(ContextCategory(name, 12400)) + (1..30).map { ContextCategory("Category $it", it.toLong()) }
        screen = screen.copy(contextSheetOpen = true, runConfig = config(ContextUsage(12400, 200000, 6, null, model, categories)))
        mountSheet()
        for (text in listOf(model, name)) {
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
        rule.onNodeWithText("Category 30").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }

    private fun assertBar(
        index: Int,
        fraction: Float,
    ) {
        rule.onNodeWithTag("context_category_bar_$index").assertHeightIsEqualTo(4.dp).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(fraction, 0f..1f)),
        )
        val track = rule.onNodeWithTag("context_category_bar_$index").getUnclippedBoundsInRoot()
        val fill = rule.onNodeWithTag("context_category_fill_$index", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals((track.right - track.left).value * fraction, (fill.right - fill.left).value, .51f)
    }

    private fun config(reading: ContextUsage) =
        ThreadRunConfig(
            contextUsage = reading,
            contextUsedTokens = reading.totalTokens,
            contextMaxTokens = reading.maxTokens,
            contextPercent = contextPercent(reading, null),
        )

    private fun mountSheet() {
        rule.setContent { PyrycodeMobileTheme(darkTheme = true) { ContextSummarySheet(screen, {}) } }
    }

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

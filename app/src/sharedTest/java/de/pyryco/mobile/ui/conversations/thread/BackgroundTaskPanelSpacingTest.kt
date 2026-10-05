package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's vertical rhythm against Populated `568:877` and Capped `568:932` (#1534), in content-relative
 * dp, which are the frame's px. Every fixture field is one line, so each card height is the frame's formula
 * for its shape. A card's merged node sits inside its 12 px vertical padding.
 */
@Config(qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class BackgroundTaskPanelSpacingTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun populated_matchesFrameRhythm() {
        show(BackgroundTaskRoster(listOf(runningWithProgress, finishedWithSummary), droppedTasks = 0))

        assertBox("Running · 1", top = 0f, height = 19f)
        assertCard("Review the diff", top = 29f, height = 118f)
        // 147 + 10 gap + 4 spacer + 10 gap, as the frame's Spacer between the groups.
        assertBox("Finished · 1", top = 171f, height = 19f)
        assertCard("npm run build", top = 200f, height = 99f)
    }

    @Test
    fun capped_matchesFrameRhythm() {
        show(BackgroundTaskRoster(listOf(runningWithCutDescription, runningWithNoChange), droppedTasks = 3))

        // The banner's 10 px padding around its 19 px line makes the frame's 39 px banner.
        assertBox("Partial list (3 not shown)", top = 10f, height = 19f)
        assertBox("Running · 2 shown", top = 49f, height = 19f)
        assertCard("sleep 4", top = 78f, height = 100f)
        assertCard("Summarise the tickets", top = 188f, height = 136f)
    }

    private fun show(roster: BackgroundTaskRoster) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                BackgroundTaskPanel(roster, onDismiss = {})
            }
        }
    }

    private fun assertCard(
        text: String,
        top: Float,
        height: Float,
    ) {
        val bounds = contentBounds(text, useUnmergedTree = false)
        assertEquals("$text card top", top, bounds.top - CARD_PADDING, 1f)
        assertEquals("$text card height", height, bounds.height + 2 * CARD_PADDING, 1f)
    }

    private fun assertBox(
        text: String,
        top: Float,
        height: Float,
    ) {
        val bounds = contentBounds(text, useUnmergedTree = true)
        assertEquals("$text top", top, bounds.top, 1f)
        assertEquals("$text height", height, bounds.height, 1f)
    }

    /** [text]'s node bounds in dp relative to the top of the panel's scrolling content. */
    private fun contentBounds(
        text: String,
        useUnmergedTree: Boolean,
    ): Rect {
        val node = rule.onNodeWithText(text, useUnmergedTree = useUnmergedTree).fetchSemanticsNode()
        val viewport =
            generateSequence(node.parent) { it.parent }
                .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        val px = node.boundsInRoot.translate(0f, -viewport.boundsInRoot.top)
        val density = rule.density.density
        return Rect(px.left / density, px.top / density, px.right / density, px.bottom / density)
    }

    private companion object {
        const val CARD_PADDING = 12f

        val runningWithProgress =
            BackgroundTask(
                "t1",
                "toolu_1",
                "local_agent",
                "Review the diff",
                null,
                null,
                null,
                false,
                BackgroundTaskProgress("Reading conn.go", "general-purpose", "Read", 42_000, 7, 65_000, null),
            )
        val finishedWithSummary =
            BackgroundTask(
                "t2",
                "toolu_2",
                "local_bash",
                "npm run build",
                null,
                null,
                BackgroundTaskUpdate("", "completed", "Build finished.", null),
                true,
            )
        val runningWithCutDescription =
            BackgroundTask("t3", "toolu_3", "local_bash", "sleep 4", listOf("description"), null, null, false)
        val runningWithNoChange =
            BackgroundTask(
                "t4",
                "toolu_4",
                "local_agent",
                "Summarise the tickets",
                null,
                BackgroundTaskUpdate("", "", "", null),
                null,
                false,
            )
    }
}

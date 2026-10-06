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
import de.pyryco.mobile.ui.pixelDp
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

        assertRhythm(
            box("Running · 1", top = 0f, height = 19f),
            card("Review the diff", top = 29f, height = 118f),
            // 147 + 10 gap + 4 spacer + 10 gap, as the frame's Spacer between the groups.
            box("Finished · 1", top = 171f, height = 19f),
            card("npm run build", top = 200f, height = 99f),
        )
    }

    @Test
    fun capped_matchesFrameRhythm() {
        show(BackgroundTaskRoster(listOf(runningWithCutDescription, runningWithNoChange), droppedTasks = 3))

        assertRhythm(
            // The banner's 10 px padding around its 19 px line makes the frame's 39 px banner.
            box("Partial list (3 not shown)", top = 10f, height = 19f),
            box("Running · 2 shown", top = 49f, height = 19f),
            card("sleep 4", top = 78f, height = 100f),
            card("Summarise the tickets", top = 188f, height = 136f),
        )
    }

    /** A block's frame position and height, with its measured ones. */
    private class Block(
        val name: String,
        val top: Float,
        val height: Float,
        val measuredTop: Float,
        val measuredHeight: Float,
    )

    /**
     * Each block's height, and its top against the block above it, within the one device pixel of rounding.
     * Checking neighbours keeps the pixel each block rounds by on the emulator from adding up down the panel.
     */
    private fun assertRhythm(vararg blocks: Block) {
        assertEquals("${blocks[0].name} top", blocks[0].top, blocks[0].measuredTop, pixelDp())
        blocks.forEachIndexed { index, block ->
            assertEquals("${block.name} height", block.height, block.measuredHeight, pixelDp())
            if (index > 0) {
                val above = blocks[index - 1]
                assertEquals(
                    "${block.name} below ${above.name}",
                    block.top - above.top,
                    block.measuredTop - above.measuredTop,
                    pixelDp(),
                )
            }
        }
    }

    private fun show(roster: BackgroundTaskRoster) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                BackgroundTaskPanel(roster, onDismiss = {})
            }
        }
    }

    private fun card(
        text: String,
        top: Float,
        height: Float,
    ): Block {
        val bounds = contentBounds(text, useUnmergedTree = false)
        return Block("$text card", top, height, bounds.top - CARD_PADDING, bounds.height + 2 * CARD_PADDING)
    }

    private fun box(
        text: String,
        top: Float,
        height: Float,
    ): Block {
        val bounds = contentBounds(text, useUnmergedTree = true)
        return Block(text, top, height, bounds.top, bounds.height)
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

package de.pyryco.mobile.design

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * List-side surfaces of the assembled app (design-1220/list): the channel list, Settings, Archive, Edit channel, the thread overflow menu and Channel Info, at the frames' 412x892 viewport
 * and again at 320x700 with 150 % font scale.
 */
@RunWith(AndroidJUnit4::class)
class ListDesignCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    @Test fun listFramesAt412By892() = walk(suffix = "")

    @Viewport("320x700", fontScale = 1.5f)
    @Test
    fun listFramesAt320By700LargeText() = walk(suffix = "-compact")

    private fun walk(suffix: String) {
        design.paired = true
        relaunch()
        design.capture(FOLDER, "channel-list$suffix", "15:8")

        rule.onNodeWithContentDescription("Open settings").performClick()
        awaitText("Notification sound")
        design.capture(FOLDER, "settings$suffix", "17:2")
        relaunch()

        rule.onNodeWithContentDescription("Open archive").performClick()
        awaitText("Archived")
        design.capture(FOLDER, "archive$suffix", "18:2")
        rule.onNodeWithText("Discussions (", substring = true).performClick()
        rule.waitForIdle()
        design.capture(FOLDER, "archive-discussions$suffix", "none")
        relaunch()

        // Edit host (533:2369) is not captured: DesignCapture's startup store answers list() alone, so the editor's
        // loadById finds no demo host and rejects the open (host_editor_open_rejected code=unknown_host).

        rule
            .onAllNodesWithContentDescription("Edit channel", substring = true)
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        design.capture(FOLDER, "edit-channel$suffix", "none")
        relaunch()

        rule
            .onAllNodesWithTag(TREE_CHANNEL_ROW_TEST_TAG)
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(5_000) { design.inputs.thread.value != null }
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        design.capture(FOLDER, "thread-menu$suffix", "none")
        rule.onNodeWithText("Channel info").performClick()
        awaitText("About")
        design.capture(FOLDER, "channel-info$suffix", "20:48")
    }

    /** Each surface starts from a fresh channel list, so one surface's dismissal path cannot steer the next. */
    private fun relaunch() {
        design.launch()
        awaitText("Channels")
    }

    private fun awaitText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text)).onFirst().assertIsDisplayed()
    }

    private companion object {
        const val FOLDER = "list"
    }
}

package de.pyryco.mobile.design

import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

/**
 * List-side surfaces of the assembled app (design-1220/list): the channel list, Settings, Archive, Edit host,
 * Edit channel, the thread overflow menu and Channel Info, at the frames' 412x892 viewport and again at
 * 320x700 with 150 % font scale.
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
        assertNoWorkspaceText()
        design.capture(FOLDER, "channel-list$suffix", "15:8")

        rule.onNodeWithContentDescription("Open settings").performClick()
        awaitText("Notification sound")
        awaitModalFocus(rule.onNodeWithText("Notification sound"))
        assertNoWorkspaceText()
        design.capture(FOLDER, "settings$suffix", "17:2")

        archiveChannels {
            relaunch()
            rule.onNodeWithContentDescription("Open archive").performClick()
            awaitText("Archived")
            // The view model opens on Discussions whatever the counts; the frame shows Channels selected.
            tap(rule.onNodeWithText("Channels (", substring = true))
            awaitText("Archived", substring = true, count = it + 1)
            design.capture(FOLDER, "archive$suffix", "18:2")
            tap(rule.onNodeWithText("Discussions (", substring = true))
            awaitText("Untitled discussion")
            design.capture(FOLDER, "archive-discussions$suffix", "none")
        }

        // The startup store answers list() alone; the host editor also reads loadById, so it needs the demo entry.
        val startup = GlobalContext.get().get<PairedServerCollectionStore>()
        loadKoinModules(
            module {
                single<PairedServerCollectionStore> {
                    object : PairedServerCollectionStore by startup {
                        override suspend fun loadById(serverId: String) = DEMO_HOST.takeIf { serverId == it.record.serverId }
                    }
                }
            },
        )
        relaunch()
        rule.onNodeWithContentDescription("Edit host", substring = true).performClick()
        awaitText("Host name:")
        design.capture(FOLDER, "edit-host$suffix", "533:2369")
        val modal = awaitModalFocus(rule.onNode(hasSetTextAction()))
        rule.onNode(hasSetTextAction()).performClick()
        rule.runOnIdle { modal.windowInsetsController?.show(WindowInsets.Type.ime()) }
        awaitModalKeyboard(modal, visible = true)
        design.capture(FOLDER, "edit-host-keyboard$suffix", "533:2369")
        // At 320x700 the keyboard pushes Unpair under the action bar; it must stay reachable by scrolling.
        rule.onNodeWithText("Unpair host").performScrollTo().assertIsDisplayed()
        Espresso.pressBack()
        awaitModalKeyboard(modal, visible = false)
        rule.onNodeWithText("Unpair host").performScrollTo().performClick()
        awaitText("Unpair host?")
        design.capture(FOLDER, "edit-host-unpair$suffix", "none")
        relaunch()

        // No conversation row draws a pen (#1563): Edit channel opens from the thread's More actions, Edit (#1561).
        openFirstChannel()
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        design.capture(FOLDER, "thread-menu$suffix", "none")
        rule.onNodeWithText("Edit").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        design.capture(FOLDER, "edit-channel$suffix", "none")
        // At 320x700 the keyboard pushes Mute and Archive channel below the window; they must stay reachable.
        rule.onNodeWithText("Archive channel").performScrollTo().assertIsDisplayed()
        relaunch()

        openFirstChannel()
        design.openMenu(rule.onNodeWithContentDescription("More actions"))
        rule.onNodeWithText("Channel info").performClick()
        awaitText("About")
        design.capture(FOLDER, "channel-info$suffix", "20:48")
    }

    private fun openFirstChannel() {
        rule
            .onAllNodesWithTag(TREE_CHANNEL_ROW_TEST_TAG)
            .onFirst()
            .performScrollTo()
            .performClick()
        rule.waitUntil(5_000) { design.inputs.thread.value != null }
    }

    /** Each surface starts from a fresh channel list, so one surface's dismissal path cannot steer the next. */
    private fun relaunch() {
        design.launch()
        awaitText("Channels")
    }

    private fun awaitText(
        text: String,
        substring: Boolean = false,
        count: Int = 1,
    ) {
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring)).fetchSemanticsNodes().size >= count }
        rule.onAllNodes(hasText(text, substring)).onFirst().assertIsDisplayed()
    }

    /**
     * The modal's own dialog window, once it holds focus. [DesignCapture.openKeyboard] waits on the activity
     * window, which a modal's dialog keeps unfocused, so the modal's keyboard is driven here.
     */
    private fun awaitModalFocus(node: SemanticsNodeInteraction): View {
        val view = (checkNotNull(node.fetchSemanticsNode().root) as ViewRootForTest).view
        rule.waitUntil(10_000) {
            val focused = rule.runOnIdle { view.hasWindowFocus() }
            // A system crash dialog can steal window focus on the emulator, as DesignCapture.openKeyboard notes.
            if (!focused) shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            focused
        }
        return view
    }

    private fun awaitModalKeyboard(
        modal: View,
        visible: Boolean,
    ) {
        rule.waitUntil(5_000) {
            rule.runOnIdle {
                val insets = ViewCompat.getRootWindowInsets(modal)
                insets != null &&
                    insets.isVisible(WindowInsetsCompat.Type.ime()) == visible &&
                    (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0) == visible
            }
        }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 5_000)
        rule.waitForIdle()
    }

    /** Taps [node] through the device's input, as a finger would, so the capture shows the state a real tap leaves. */
    private fun tap(node: SemanticsNodeInteraction) {
        val center = node.fetchSemanticsNode().boundsInWindow.center
        shell("input tap ${center.x.toInt()} ${center.y.toInt()}")
        rule.waitForIdle()
    }

    /**
     * The retired workspace product leaves no grouping, label or control behind on the list or in Settings. A
     * returning workspace row would show only its folder name, so its controls' descriptions are checked too.
     */
    private fun assertNoWorkspaceText() {
        rule.onAllNodes(hasText("workspace", substring = true, ignoreCase = true)).assertCountEquals(0)
        rule.onAllNodes(hasContentDescription("workspace", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    /**
     * Archives the demo host's first [ARCHIVED_CHANNELS] channels for [block], which receives that count, and
     * restores them after, so Archive's Channels tab has rows like the frame and later surfaces keep the full list.
     */
    private fun archiveChannels(block: (Int) -> Unit) {
        val fake = GlobalContext.get().get<FakeConversationRepository>()
        val ids =
            runBlocking {
                fake
                    .observeConversations(ConversationFilter.Channels)
                    .first()
                    .take(ARCHIVED_CHANNELS)
                    .map { it.id }
            }
        try {
            runBlocking { ids.forEach { fake.archive(it) } }
            block(ids.size)
        } finally {
            runBlocking { ids.forEach { fake.unarchive(it) } }
        }
    }

    private companion object {
        const val FOLDER = "list"
        const val ARCHIVED_CHANNELS = 3
        val DEMO_HOST = PairedServerEntry(PairedServer("demo", "unused", "wss://demo.invalid", "unused"), "Demo")
    }
}

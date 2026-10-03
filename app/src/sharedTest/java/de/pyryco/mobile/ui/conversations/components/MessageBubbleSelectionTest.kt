package de.pyryco.mobile.ui.conversations.components

import android.widget.Magnifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import android.content.ClipboardManager as PlatformClipboardManager

/**
 * #1638: a finished bubble's body is selectable by long press, and the system Copy action takes only the
 * selection. The floating toolbar is system UI, so a recording toolbar provider stands in for it and the
 * test presses its Copy; the copied text is read back off the platform clipboard, the one sink both Compose
 * clipboard locals write through.
 *
 * Native graphics because the selection handles draw through a vector bitmap cache that the legacy
 * Robolectric canvas cannot allocate, and a no-op [Magnifier] because Robolectric gives the platform
 * magnifier's popup no surface to destroy when the long press ends. A device run ignores both.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [MessageBubbleSelectionTest.NoOpMagnifier::class])
class MessageBubbleSelectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Implements(Magnifier::class)
    class NoOpMagnifier {
        @Implementation
        fun show(
            sourceCenterX: Float,
            sourceCenterY: Float,
            magnifierCenterX: Float,
            magnifierCenterY: Float,
        ) = Unit

        @Implementation
        fun update() = Unit

        @Implementation
        fun dismiss() = Unit
    }

    /**
     * Stands in for the system floating toolbar. Compose shows it for as long as [showTextContextMenu]
     * suspends, so the open menu is the one whose call has not been cancelled yet.
     */
    private class RecordingToolbarProvider : TextContextMenuProvider {
        var shown: TextContextMenuDataProvider? = null
            private set

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            shown = dataProvider
            try {
                awaitCancellation()
            } finally {
                shown = null
            }
        }

        /** The open menu's Copy action, or `null` when no menu offers one. */
        fun copyAction(): (() -> Unit)? {
            val copy =
                shown
                    ?.data()
                    ?.components
                    ?.filterIsInstance<TextContextMenuItem>()
                    ?.firstOrNull { it.key == TextContextMenuKeys.CopyKey }
                    ?: return null
            return { copy.onClick(NoOpSession) }
        }
    }

    private object NoOpSession : TextContextMenuSession {
        override fun close() = Unit
    }

    private val toolbar = RecordingToolbarProvider()

    private fun message(
        role: Role,
        content: String,
        isStreaming: Boolean = false,
    ) = Message(
        id = "m-${role.name}",
        sessionId = "s1",
        role = role,
        content = content,
        timestamp = Instant.parse("2026-01-13T12:55:00Z"),
        isStreaming = isStreaming,
    )

    private fun setBubble(message: Message) {
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    Surface { MessageBubble(message) }
                }
            }
        }
    }

    private fun platformClipboardText(): String? =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getSystemService(PlatformClipboardManager::class.java)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()

    private fun longPressAndCopy(word: String): String? {
        composeTestRule
            .onNodeWithText(word, substring = true, useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()
        val copy = toolbar.copyAction()
        assertNotNull("a long press on '$word' must offer the system Copy action", copy)
        composeTestRule.runOnIdle { copy?.invoke() }
        composeTestRule.waitForIdle()
        return platformClipboardText()
    }

    // Prose and fenced code alike, and Copy takes only the selection, not the whole reply the meta row
    // would copy.
    @Test
    fun longPress_onAFinishedAssistantBubble_copiesOnlyTheSelection_codeBlockIncluded() {
        setBubble(message(Role.Assistant, ASSISTANT_BODY))

        assertEquals(PROSE_WORD, longPressAndCopy(PROSE_WORD))

        // A tap outside the bubble clears the selection before the code block is pressed.
        composeTestRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        composeTestRule.waitForIdle()
        assertEquals(CODE_WORD, longPressAndCopy(CODE_WORD))
    }

    // AC2: the code block's own copy button still takes the whole block from inside the selection area.
    @Test
    fun codeBlockCopyButton_insideAFinishedBubble_stillCopiesTheWholeBlock() {
        setBubble(message(Role.Assistant, ASSISTANT_BODY))
        val copyCode =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_copy_code)

        composeTestRule.onNodeWithContentDescription(copyCode).performClick()
        composeTestRule.waitForIdle()

        assertEquals(CODE_WORD, platformClipboardText())
    }

    @Test
    fun longPress_onAUserBubble_copiesOnlyTheSelection() {
        setBubble(message(Role.User, USER_BODY))

        assertEquals(USER_WORD, longPressAndCopy(USER_WORD))
    }

    // #1621 + #1638: exercise pointer gestures, rather than the bubble's accessibility click.
    @Test
    fun selectableBodies_keepTheMetaRowTap_andLongPressDoesNotToggleIt() {
        val messages = listOf(message(Role.Assistant, PROSE_WORD), message(Role.User, USER_WORD))
        val copyMessage =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_copy_message)
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                    var visibleMessageId by remember { mutableStateOf<String?>(null) }
                    Surface {
                        Column {
                            messages.forEach { message ->
                                MessageBubble(
                                    message = message,
                                    metaRowVisible = visibleMessageId == message.id,
                                    onToggleMetaRow = {
                                        visibleMessageId = if (visibleMessageId == message.id) null else message.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        messages.forEach { message ->
            composeTestRule.onNodeWithContentDescription(copyMessage).assertDoesNotExist()
            composeTestRule.mainClock.advanceTimeBy(500)
            composeTestRule.onNodeWithText(message.content).performTouchInput { click() }
            composeTestRule.onNodeWithContentDescription(copyMessage).assertExists()
            // Separate single taps from Compose's double-tap word-selection gesture.
            composeTestRule.mainClock.advanceTimeBy(500)
            composeTestRule.onNodeWithText(message.content).performTouchInput { click() }
            composeTestRule.onNodeWithContentDescription(copyMessage).assertDoesNotExist()

            assertEquals(message.content, longPressAndCopy(message.content))
            composeTestRule.onNodeWithContentDescription(copyMessage).assertDoesNotExist()
            composeTestRule.onRoot().performTouchInput { click(Offset(1f, 1f)) }
        }
    }

    // A streaming reply stays unselectable, so a selection never holds offsets into text still arriving.
    @Test
    fun longPress_onAStreamingBubble_offersNoSelection() {
        setBubble(message(Role.Assistant, STREAMING_BODY, isStreaming = true))
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule
                .onAllNodesWithText(STREAMING_BODY, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeTestRule
            .onNodeWithText(STREAMING_BODY, substring = true, useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertNull("a streaming bubble must not open the selection toolbar", toolbar.shown)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        // One word per paragraph, so the word under a long press is that paragraph's whole text and the
        // copied string is exact whatever the host's text measurement.
        const val PROSE_WORD = "zeta"
        const val CODE_WORD = "thetacode"
        const val ASSISTANT_BODY = "$PROSE_WORD\n\nsecond paragraph\n\n```\n$CODE_WORD\n```"
        const val USER_WORD = "sigma"
        const val USER_BODY = "$USER_WORD\n\nanother user paragraph"

        // Short so the 50-chars-per-second reveal finishes well inside the timeout.
        const val STREAMING_BODY = "kappa stream"
    }
}

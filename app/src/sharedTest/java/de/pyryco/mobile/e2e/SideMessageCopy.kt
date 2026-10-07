package de.pyryco.mobile.e2e

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.ui.conversations.components.formatShortDateTime
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import java.util.Locale

/** #1817: one pointer tap on the side action copies source without revealing a timestamp. */
internal fun ComposeTestRule.assertSideMessageCopy(message: Message) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val sourceRow =
        hasTestTag("message-row") and hasAnyDescendant(hasText(message.content.trimEnd(), substring = message.isStreaming))
    val timestamp =
        hasText(formatShortDateTime(message.timestamp, TimeZone.currentSystemDefault(), Locale.getDefault())) and
            hasAnyAncestor(sourceRow)
    onAllNodes(timestamp, useUnmergedTree = true).assertCountEquals(0)
    // A dismissible Top overlay notice can cover the target despite assertIsDisplayed succeeding.
    // Dismiss before measuring the clear band, so a removable notice does not count as chrome.
    val notices = onAllNodes(hasContentDescription(context.getString(R.string.thread_notice_dismiss)))
    repeat(notices.fetchSemanticsNodes().size) { notices[0].performClick() }
    // The divided targets may overflow a short first row; scroll its visual row, not the overflow.
    onNode(sourceRow).performScrollTo()
    onNode(
        hasContentDescription(context.getString(R.string.cd_thread_copy_message)) and
            hasAnyAncestor(sourceRow),
    ).assertIsDisplayed()
    runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("copy baseline", "unrelated baseline")) }
    val point = sideMessageActionTapPoint(sourceRow, "message-copy-glyph")
    onRoot().performTouchInput { click(point) }
    runOnIdle {
        assertEquals(
            message.content.take(100_000),
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }
    onAllNodes(timestamp, useUnmergedTree = true).assertCountEquals(0)
}

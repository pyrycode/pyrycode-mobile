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
    val copy =
        onNode(
            hasContentDescription(context.getString(R.string.cd_thread_copy_message)) and
                hasAnyAncestor(sourceRow),
        ).performScrollTo().assertIsDisplayed()
    onAllNodes(timestamp, useUnmergedTree = true).assertCountEquals(0)
    // A dismissible Top overlay notice can cover the target despite assertIsDisplayed succeeding.
    val notices = onAllNodes(hasContentDescription(context.getString(R.string.thread_notice_dismiss)))
    repeat(notices.fetchSemanticsNodes().size) { notices[0].performClick() }
    runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("copy baseline", "unrelated baseline")) }
    copy.performTouchInput { click(center) }
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

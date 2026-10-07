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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import org.junit.Assert.assertEquals

/** #1817: one pointer tap on the side action copies source without revealing a timestamp. */
internal fun ComposeTestRule.assertSideMessageCopy(message: Message) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val copy =
        onNode(
            hasContentDescription(context.getString(R.string.cd_thread_copy_message)) and
                hasAnyAncestor(
                    hasTestTag("message-row") and hasAnyDescendant(hasText(message.content.trimEnd(), substring = message.isStreaming)),
                ),
        ).performScrollTo().assertIsDisplayed()
    onAllNodesWithText(" - ", substring = true, useUnmergedTree = true).assertCountEquals(0)
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
    onAllNodesWithText(" - ", substring = true, useUnmergedTree = true).assertCountEquals(0)
}

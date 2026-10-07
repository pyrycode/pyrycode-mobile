package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.ui.conversations.components.formatShortDateTime
import kotlinx.datetime.TimeZone
import java.util.Locale
import org.junit.Assert.assertEquals

/** Pointer reply on one source row; assert exact editable text, end cursor and independent focus. */
internal fun ComposeTestRule.assertSideMessageReply(message: Message, expectedDraft: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val sourceRow = hasTestTag("message-row") and hasAnyDescendant(hasText(message.content.trimEnd(), substring = message.isStreaming))
    val notices = onAllNodes(hasContentDescription(context.getString(R.string.thread_notice_dismiss)))
    repeat(notices.fetchSemanticsNodes().size) { notices[0].performClick() }
    onNode(hasContentDescription(context.getString(R.string.cd_thread_reply_message)) and hasAnyAncestor(sourceRow))
        .performScrollTo().assertIsDisplayed().performTouchInput { click(center) }
    waitUntil(5_000) {
        onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == expectedDraft
    }
    val field = onNode(hasSetTextAction()).assertIsFocused().fetchSemanticsNode()
    assertEquals(expectedDraft, field.config[SemanticsProperties.EditableText].text)
    assertEquals(TextRange(expectedDraft.length), field.config[SemanticsProperties.TextSelectionRange])
    onAllNodes(hasText(formatShortDateTime(message.timestamp, TimeZone.currentSystemDefault(), Locale.getDefault())) and hasAnyAncestor(sourceRow), useUnmergedTree = true).assertCountEquals(0)
}

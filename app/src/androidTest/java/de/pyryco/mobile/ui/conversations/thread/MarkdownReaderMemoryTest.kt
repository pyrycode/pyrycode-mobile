package de.pyryco.mobile.ui.conversations.thread

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.MAX_CLIPBOARD_CHARS
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native text layout used to exhaust device memory for one whitespace-free word at the reader bound. */
@RunWith(AndroidJUnit4::class)
class MarkdownReaderMemoryTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun noteAtSupportedBound_scrollsCopiesAndReturnsWithoutTermination() {
        assertEquals(262_144, MAX_MARKDOWN_READER_BYTES)
        val document = MarkdownDocument("Big.md", "a".repeat(MAX_MARKDOWN_READER_BYTES))
        rule.setContent {
            var readerOpen by remember { mutableStateOf(true) }
            PyrycodeMobileTheme {
                if (readerOpen) {
                    MarkdownReaderScreen(document, onBack = { readerOpen = false })
                } else {
                    Text("Returned from reader")
                }
            }
        }

        rule.onNodeWithText("Big.md").assertIsDisplayed()
        val body = rule.onNode(hasScrollAction())
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val before = rule.runOnIdle { range.value() }
        body.performTouchInput { swipeUp() }
        val after = rule.runOnIdle { range.value() }
        assertTrue("The real reader body must scroll", after > before)
        rule.onNodeWithText("Big.md").assertIsDisplayed()

        choose(R.string.markdown_reader_copy_markdown)
        rule.runOnIdle {
            val clip = checkNotNull(clipboard.primaryClip)
            assertEquals(1, clip.itemCount)
            assertOriginalCharacters(clip.getItemAt(0).text)
        }

        choose(R.string.markdown_reader_copy_html)
        rule.runOnIdle {
            val clip = checkNotNull(clipboard.primaryClip)
            assertEquals(1, clip.itemCount)
            assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))
            val item = clip.getItemAt(0)
            assertOriginalCharacters(item.text)
            val html = checkNotNull(item.htmlText)
            assertTrue("HTML must be present and bounded", html.isNotEmpty() && html.length <= MAX_CLIPBOARD_CHARS)
        }

        rule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        rule.onNodeWithText("Returned from reader").assertIsDisplayed()
        rule.onNodeWithText("Big.md").assertDoesNotExist()
    }

    private fun choose(item: Int) {
        rule.onNodeWithContentDescription(context.getString(R.string.cd_more_actions)).performClick()
        rule.onNodeWithTag("markdown-reader-menu").assertIsDisplayed()
        rule.onNodeWithText(context.getString(item)).performClick()
        rule.onNodeWithTag("markdown-reader-menu").assertDoesNotExist()
    }

    private fun assertOriginalCharacters(text: CharSequence?) {
        val original = checkNotNull(text)
        assertEquals(MAX_CLIPBOARD_CHARS, original.length)
        assertTrue("Display-only breaks must never enter copied text", original.all { it == 'a' })
    }
}

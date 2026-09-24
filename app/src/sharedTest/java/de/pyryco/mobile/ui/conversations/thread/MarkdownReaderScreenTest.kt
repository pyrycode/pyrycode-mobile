package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.AttachmentSource
import de.pyryco.mobile.ui.conversations.components.AttachmentTarget
import de.pyryco.mobile.ui.conversations.components.AttachmentViewState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** #1027: the in-app markdown reader — its name bar, its rendered body, its way back, and the tap that opens it. */
@RunWith(AndroidJUnit4::class)
class MarkdownReaderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val back = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_back)

    private fun show(
        document: MarkdownDocument,
        onBack: () -> Unit = {},
    ) = composeRule.setContent {
        PyrycodeMobileTheme {
            MarkdownReaderScreen(document = document, onBack = onBack)
        }
    }

    @Test
    fun showsTheNameInTheBar_andTheRenderedMarkdownBelow() {
        show(MarkdownDocument("Plan.md", "# Builder Plan\n\n- Pin the dispatcher\n\nSome **bold** prose."))

        composeRule.onNodeWithText("Plan.md").assertIsDisplayed()
        composeRule.onNodeWithText("Builder Plan").assertIsDisplayed()
        composeRule.onNodeWithText("Pin the dispatcher").assertIsDisplayed()
        // Rendered, not shown as source: the heading's `#` and the bold markers are gone.
        composeRule.onNodeWithText("# Builder Plan").assertDoesNotExist()
        composeRule.onNodeWithText("Some bold prose.").assertIsDisplayed()
    }

    @Test
    fun theBackArrow_goesBack() {
        var backs = 0
        show(MarkdownDocument("Plan.md", "text"), onBack = { backs++ })

        composeRule.onNodeWithContentDescription(back).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun longContent_scrollsUnderAFixedBar() {
        val body = (1..80).joinToString("\n\n") { "Paragraph $it" }
        show(MarkdownDocument("Long.md", body))

        composeRule.onNodeWithText("Paragraph 80").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("Long.md").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(back).assertIsDisplayed()
        composeRule.onNodeWithText("Paragraph 1").assertIsNotDisplayed()
    }

    @Test
    fun aReadyMarkdownTap_goesToTheReader_whateverItsSource_andOtherFilesDoNot() {
        val kept = AttachmentViewState.Ready(AttachmentSource.Kept(File("/kept/a")), null, null)
        val original = AttachmentViewState.Ready(AttachmentSource.Original("content://docs/b"), null, null)
        val states = mapOf("a" to kept, "b" to original, "c" to kept)
        val opened = mutableListOf<String>()
        val notices = mutableListOf<AttachmentNotice>()
        lateinit var actions: AttachmentActions
        composeRule.setContent {
            actions = rememberAttachmentActions(states, onOpenMarkdown = { opened += it }, onNotice = { notices += it })
        }

        composeRule.runOnIdle {
            actions.open(AttachmentTarget("a", "Plan.MD", "text/plain"))
            actions.open(AttachmentTarget("b", "notes.markdown", null))
            actions.open(AttachmentTarget("c", "notes.txt", "text/plain"))
            actions.open(AttachmentTarget("loading", "later.md", null))
        }

        assertEquals(listOf("a", "b"), opened)
        // The text file went the view-intent way: a kept file outside the store has no content URI.
        assertEquals(listOf(AttachmentNotice.OPEN_FAILED), notices)
    }
}

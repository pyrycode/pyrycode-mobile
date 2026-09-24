package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.components.CHANNEL_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveAsChannelDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissals = 0
    private val submitted = mutableListOf<Pair<String, String>>()
    private val nameEditable = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun show(initialName: String = "Release notes") {
        rule.setContent {
            PyrycodeMobileTheme {
                SaveAsChannelDialog(
                    conversationId = "conversation-1",
                    initialName = initialName,
                    onSubmit = { name, prompt -> submitted += name to prompt },
                    onDismissRequest = { dismissals++ },
                    nameEditable = nameEditable.value,
                    error = error.value,
                )
            }
        }
    }

    private fun name() = rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG)

    private fun prompt() = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG)

    private fun ok() = rule.onNodeWithText("OK")

    @Test
    fun rendersTheTitleBothLabelsAndTheFooter() {
        show()

        rule.onNodeWithText(string(R.string.save_as_channel_dialog_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.channel_form_name_label)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.channel_form_prompt_label)).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        ok().assertIsDisplayed()
        // No location choice survives from the old dialog.
        rule.onNodeWithText("Keep in scratch").assertDoesNotExist()
    }

    @Test
    fun prefillsTheNameAndOpensThePromptEmpty() {
        show()

        name().assertTextEquals("Release notes")
        prompt().assertTextEquals("")
    }

    /**
     * The live `interactiveTurn_saveAsChannel_promotesToChannelTier` scenario tells the modal's name field
     * from the thread composer behind it by focus, with this predicate verbatim; `onNode` also asserts the
     * match is unique now that the modal holds a second editable field.
     */
    @Test
    fun theNameFieldIsTheOneFocusedFieldOnceTheModalComposes() {
        show()

        rule.waitUntil(2_000L) {
            rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and isFocused()).assertTextEquals("Release notes")
    }

    @Test
    fun okIsDisabledForABlankOrWhitespaceName() {
        show()

        name().performTextClearance()
        ok().assertIsNotEnabled()
        name().performTextInput("   ")
        ok().assertIsNotEnabled()
        ok().performClick()
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun okFollowsThePromptsUtf8ByteLimit() {
        show()

        // Two bytes per "é": exactly at the limit fits, one more byte does not.
        val atLimit = "é".repeat(SystemPromptLimit.MAX_BYTES / 2)
        prompt().performTextReplacement(atLimit)
        ok().assertIsEnabled()
        rule.onNodeWithText(string(R.string.channel_form_prompt_too_long)).assertDoesNotExist()

        prompt().performTextReplacement(atLimit + "a")
        ok().assertIsNotEnabled()
        rule.onNodeWithText(string(R.string.channel_form_prompt_too_long)).assertExists()
    }

    @Test
    fun okSubmitsTheTrimmedNameAndTheVerbatimPrompt() {
        show()

        name().performTextReplacement("  Ops channel  ")
        prompt().performTextInput("  Be brief.\n")
        ok().performClick()

        assertEquals(listOf("Ops channel" to "  Be brief.\n"), submitted)
        assertEquals(0, dismissals)
    }

    @Test
    fun okWithAnEmptyPromptSubmitsAnEmptyPrompt() {
        show()

        ok().performClick()

        assertEquals(listOf("Release notes" to ""), submitted)
    }

    @Test
    fun cancelAndCloseDismissWithoutSubmitting() {
        show()

        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Close").performClick()

        assertEquals(2, dismissals)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun aLongDaemonNameIsClampedWithoutSplittingASurrogatePair() {
        // An emoji straddling the bound would leave half a pair; the clamp drops it whole.
        show(initialName = "a".repeat(MAX_WORKSPACE_LABEL_CHARS - 1) + "😀" + "tail")

        name().assertTextEquals("a".repeat(MAX_WORKSPACE_LABEL_CHARS - 1))
    }

    @Test
    fun anErrorIsShownAndTheTypedValuesSurviveIt() {
        show()
        prompt().performTextInput("Keep answers short.")

        error.value = string(R.string.save_as_channel_failed)

        rule.onNodeWithText(string(R.string.save_as_channel_failed)).assertIsDisplayed()
        prompt().assertTextEquals("Keep answers short.")
        ok().performClick()
        assertEquals(listOf("Release notes" to "Keep answers short."), submitted)
    }

    @Test
    fun aConfirmedPromoteLocksTheNameButLeavesThePromptEditable() {
        show()

        nameEditable.value = false

        name().assertIsNotEnabled()
        prompt().assertIsEnabled()
        ok().assertIsEnabled()
    }
}

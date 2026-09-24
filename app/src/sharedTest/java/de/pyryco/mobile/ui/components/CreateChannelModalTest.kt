package de.pyryco.mobile.ui.components

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
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateChannelModalTest {
    @get:Rule
    val rule = createComposeRule()

    private var dismissals = 0
    private val submitted = mutableListOf<Pair<String, String>>()
    private val hostAvailable = mutableStateOf(true)
    private val nameEditable = mutableStateOf(true)
    private val error = mutableStateOf<String?>(null)

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun show() {
        rule.setContent {
            PyrycodeMobileTheme {
                CreateChannelModal(
                    serverId = "pyrybox",
                    cwd = "/w/one",
                    onSubmit = { name, prompt -> submitted += name to prompt },
                    onDismissRequest = { dismissals++ },
                    hostAvailable = hostAvailable.value,
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

        rule.onNodeWithText(string(R.string.create_channel_title)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.channel_form_name_label)).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.channel_form_prompt_label)).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        ok().assertIsDisplayed()
    }

    @Test
    fun bothFieldsOpenEmptyAndTheNameIsTheOneFocusedField() {
        show()

        name().assertTextEquals("")
        prompt().assertTextEquals("")
        ok().assertIsNotEnabled()
        rule.waitUntil(2_000L) {
            rule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction() and isFocused()).assertTextEquals("")
        name().performTextInput("Typed")
        rule.onNode(hasSetTextAction() and isFocused()).assertTextEquals("Typed")
    }

    @Test
    fun okNeedsANonBlankNameAnAvailableHostAndAPromptWithinTheByteLimit() {
        show()

        name().performTextInput("   ")
        ok().assertIsNotEnabled()
        name().performTextReplacement("Ops")
        ok().assertIsEnabled()

        hostAvailable.value = false
        ok().assertIsNotEnabled()
        name().assertTextEquals("Ops")
        hostAvailable.value = true

        // Two bytes per "é": exactly at the limit fits, one more byte does not.
        val atLimit = "é".repeat(SystemPromptLimit.MAX_BYTES / 2)
        prompt().performTextReplacement(atLimit)
        ok().assertIsEnabled()
        prompt().performTextReplacement(atLimit + "a")
        ok().assertIsNotEnabled()
        ok().performClick()
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun okSubmitsTheTrimmedNameAndTheVerbatimPrompt() {
        show()

        name().performTextInput("  Ops channel  ")
        prompt().performTextInput("  Be brief.\n")
        ok().performClick()

        assertEquals(listOf("Ops channel" to "  Be brief.\n"), submitted)
        assertEquals(0, dismissals)
    }

    @Test
    fun cancelAndCloseDismissWithoutSubmitting() {
        show()
        name().performTextInput("Ops")

        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Close").performClick()

        assertEquals(2, dismissals)
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun anErrorKeepsTheTypedValuesAndAConfirmedCreateLocksOnlyTheName() {
        show()
        name().performTextInput("Ops")
        prompt().performTextInput("Keep answers short.")

        error.value = string(R.string.create_channel_prompt_failed)
        nameEditable.value = false

        rule.onNodeWithText(string(R.string.create_channel_prompt_failed)).assertIsDisplayed()
        name().assertTextEquals("Ops").assertIsNotEnabled()
        prompt().assertTextEquals("Keep answers short.").assertIsEnabled()
        ok().performClick()
        assertEquals(listOf("Ops" to "Keep answers short."), submitted)
    }
}

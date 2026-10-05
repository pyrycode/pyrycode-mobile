package de.pyryco.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ChannelFormFieldsTest {
    @get:Rule val rule = createComposeRule()

    private val name = mutableStateOf(TextFieldValue(""))
    private val prompt = mutableStateOf("")
    private val nameEnabled = mutableStateOf(true)
    private val promptEnabled = mutableStateOf(true)
    private val note = mutableStateOf<String?>(null)

    private fun show() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                ChannelFormFields(
                    name = name.value,
                    onNameChange = { name.value = it },
                    systemPrompt = prompt.value,
                    onSystemPromptChange = { prompt.value = it },
                    nameEnabled = nameEnabled.value,
                    promptEnabled = promptEnabled.value,
                    promptNote = note.value,
                )
            }
        }
    }

    @Test fun wellsHaveExactDesignHeightAndSpacingWithoutMaterialInsets() {
        show()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nameLabel = rule.onNodeWithText(context.getString(R.string.channel_form_name_label)).fetchSemanticsNode().boundsInRoot
        val promptLabel = rule.onNodeWithText(context.getString(R.string.channel_form_prompt_label)).fetchSemanticsNode().boundsInRoot
        val nameWell = rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val promptWell = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density

        fun close(
            actual: Float,
            expectedDp: Float,
        ) {
            assertTrue("expected ${expectedDp}dp, got ${actual / density}dp", abs(actual / density - expectedDp) < 1.5f)
        }
        close(nameWell.height, 52f)
        close(promptWell.height, 112f)
        close(nameWell.top - nameLabel.bottom, 8f)
        close(promptLabel.top - nameWell.bottom, 12f)
        close(promptWell.top - promptLabel.bottom, 8f)
        // The wells span the width they are given: Robolectric's 320 dp window, the emulator's 411 dp one.
        val width =
            rule
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot.width / density
        close(nameWell.width, width)
        close(promptWell.width, width)
    }

    @Test fun editingAndValidationKeepTheSharedContract() {
        show()
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsFocused().assertIsEnabled()
        assertEquals(
            listOf("Channel name:"),
            rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        assertEquals(
            listOf("Channel system prompt:"),
            rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription],
        )
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performImeAction()
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsFocused()
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement("Ops")
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement("  First\nSecond  ")
        assertEquals("Ops", name.value.text)
        assertEquals("  First\nSecond  ", prompt.value)

        note.value = "Applies next session"
        rule.onNodeWithText("Applies next session").assertIsDisplayed()
        prompt.value = "é".repeat(SystemPromptLimit.MAX_BYTES / 2) + "a"
        rule.onNodeWithText("This system prompt is too long.").assertExists()
        assertEquals(
            "This system prompt is too long.",
            rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).fetchSemanticsNode().config[SemanticsProperties.Error],
        )
        rule.onNodeWithText("Applies next session").assertDoesNotExist()

        nameEnabled.value = false
        promptEnabled.value = false
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsNotEnabled()
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsNotEnabled()
    }

    @Test fun compactWidthAndEnlargedTextKeepLabelsAndWellsSeparate() {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.5f)) {
                    Box(Modifier.width(240.dp)) {
                        ChannelFormFields(
                            name = name.value,
                            onNameChange = { name.value = it },
                            systemPrompt = "A paragraph\nwith another line",
                            onSystemPromptChange = {},
                        )
                    }
                }
            }
        }
        val nameLabel =
            rule
                .onNodeWithText("Channel name:")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val promptLabel =
            rule
                .onNodeWithText("Channel system prompt:")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val nameWell = rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val promptWell = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(nameLabel.right <= nameWell.right)
        assertTrue(promptLabel.right <= promptWell.right)
        assertTrue(nameLabel.bottom < nameWell.top)
        assertTrue(nameWell.bottom < promptLabel.top)
        assertTrue(promptLabel.bottom < promptWell.top)
        assertTrue("name height=${nameWell.height}", nameWell.height > 52f)
        assertTrue("prompt height=${promptWell.height}", promptWell.height > 112f)
    }
}

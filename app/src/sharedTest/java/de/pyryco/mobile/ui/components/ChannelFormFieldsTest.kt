package de.pyryco.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
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

    // #1651: Figma 671:5558 leaves 39dp from the name well's bottom to the prompt well's top — the 12dp
    // gap between the two fields, plus the prompt label's own full line box, plus the 8dp label-to-field
    // gap. The theme's default would trim that label to its glyphs and pull the two wells 3dp closer.
    @Test fun nameWellToPromptWellMatchesTheFrame() {
        show()
        val nameWell = rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val promptWell = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).fetchSemanticsNode().boundsInRoot
        val density =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.displayMetrics.density

        assertTrue(
            "expected 39dp, got ${(promptWell.top - nameWell.bottom) / density}dp",
            abs((promptWell.top - nameWell.bottom) / density - 39f) < 1.5f,
        )
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

    @Test fun promptWellKeepsBlankSpaceBelowWrappedAndExplicitLines() {
        var density = 0f
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(356.dp, 600.dp))) {
                density = LocalDensity.current.density
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    ChannelFormFields(
                        name = name.value,
                        onNameChange = { name.value = it },
                        systemPrompt = prompt.value,
                        onSystemPromptChange = { prompt.value = it },
                    )
                }
            }
        }
        val field = rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG)
        val cases =
            listOf(
                "" to 1,
                "One line" to 1,
                "Summarise each merged pull request in one plain sentence for the release notes." to 2,
                "First\nSecond" to 2,
                "First\nSecond\n" to 3,
                "One\nTwo\nThree\nFour\nFive" to 5,
                "One line again" to 1,
                "" to 1,
            )
        for ((text, lines) in cases) {
            field.performTextReplacement(text)
            field.assertIsFocused()
            val layouts = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val well = field.fetchSemanticsNode().boundsInRoot
            assertEquals(text, prompt.value)
            assertEquals("drawn lines for '$text'", lines, layout.lineCount)
            assertEquals("form width", 356f, well.width / density, 1.5f)
            assertEquals("well height for $lines lines", 92f + lines * 20f, well.height / density, 1.5f)
            assertEquals("blank space below drawn text", 76f, well.height / density - 16f - layout.size.height / density, 1.5f)
        }
        // The added blank surface belongs to the prompt, including its bottom edge.
        field.performTextReplacement("First\nSecond")
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTouchInput { click(Offset(center.x, height - 2f)) }.assertIsFocused()
        field.performTouchInput { click(Offset(center.x, height - 2f)) }.assertIsFocused()
    }
}

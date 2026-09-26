package de.pyryco.mobile.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalFieldContainer
import de.pyryco.mobile.ui.theme.modalFieldText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ModalFieldPaletteTest {
    @get:Rule
    val rule = createComposeRule()

    private var dialogView: View? = null
    private var expectedWell = Color.Unspecified
    private var expectedText = Color.Unspecified

    private fun show(darkTheme: Boolean) {
        val name = mutableStateOf(TextFieldValue("Pyrybox"))
        val prompt = mutableStateOf("Answer in short paragraphs.")
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = darkTheme) {
                val scheme = MaterialTheme.colorScheme
                expectedWell =
                    if (darkTheme) {
                        Color(0xFF003355).copy(alpha = 0.41f).compositeOver(Color(0xFF001D34))
                    } else {
                        scheme.onPrimaryContainer.copy(alpha = 0.12f).compositeOver(scheme.primaryContainer)
                    }
                expectedText = if (darkTheme) Color(0xFFE0E2E8) else scheme.onPrimaryContainer
                assertEquals(expectedText, scheme.modalFieldText)
                assertEquals(
                    if (darkTheme) scheme.onPrimary.copy(alpha = 0.41f) else scheme.onPrimaryContainer.copy(alpha = 0.12f),
                    scheme.modalFieldContainer,
                )
                MobileModal(title = "Edit channel", onDismissRequest = {}, onSubmit = {}) {
                    dialogView = LocalView.current
                    ChannelFormFields(
                        name = name.value,
                        onNameChange = { name.value = it },
                        systemPrompt = prompt.value,
                        onSystemPromptChange = { prompt.value = it },
                    )
                }
            }
        }
    }

    private fun assertPalette(tag: String) {
        val field = rule.onNodeWithTag(tag)
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            expectedText,
            layouts
                .single()
                .layoutInput.style.color,
        )

        val bounds = field.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            val view = checkNotNull(dialogView)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            // Sample inside the well, beyond the text and away from its rounded corners.
            val actual = Color(bitmap.getPixel((bounds.right - 12).toInt(), (bounds.top + 16).toInt()))
            assertEquals(expectedWell.red, actual.red, 1f / 255f)
            assertEquals(expectedWell.green, actual.green, 1f / 255f)
            assertEquals(expectedWell.blue, actual.blue, 1f / 255f)
            bitmap.recycle()
        }
    }

    private fun assertBothFocusStates() {
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsFocused()
        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsNotFocused()
        assertPalette(CHANNEL_NAME_FIELD_TAG)
        assertPalette(CHANNEL_PROMPT_FIELD_TAG)

        rule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performClick().assertIsFocused()
        rule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsNotFocused()
        assertPalette(CHANNEL_NAME_FIELD_TAG)
        assertPalette(CHANNEL_PROMPT_FIELD_TAG)
    }

    @Test
    fun darkFieldsMatchTheReferenceInBothFocusStates() {
        show(darkTheme = true)
        assertBothFocusStates()
    }

    @Test
    fun lightFieldsKeepTheirExistingPaletteInBothFocusStates() {
        show(darkTheme = false)
        assertBothFocusStates()
    }
}

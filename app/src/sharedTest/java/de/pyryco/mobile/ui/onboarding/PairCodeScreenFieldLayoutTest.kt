package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The Pair Code States frames' error inset (`663:3191`, `663:3331`) and long-code ellipsis (`663:2887`, `663:3331`) (#1506). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PairCodeScreenFieldLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(PairCodeState(name = "Pyrybox", code = "code", error = INVALID_CODE_ERROR))

    @Test
    fun errorTextIsInsetFromTheFieldStartAndBelowItsUnderline() {
        show()
        for (error in listOf(INVALID_CODE_ERROR, WRONG_HOST_ERROR)) {
            rule.runOnIdle {
                state =
                    if (error == WRONG_HOST_ERROR) {
                        PairCodeState(targetName = "Pyrybox", code = "code", error = error)
                    } else {
                        state.copy(error = error)
                    }
            }
            val field = rule.onNodeWithContentDescription(PAIRING_CODE).fetchSemanticsNode().boundsInRoot
            val text = rule.onNodeWithText(error, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            with(rule.density) {
                assertEquals("$error start inset", 16.dp.toPx(), text.left - field.left, 0.5f)
                assertEquals("$error gap below the underline", 4.dp.toPx(), text.top - field.bottom, 0.5f)
            }
        }
    }

    @Test
    fun unfocusedLongCodeEndsWithAnEllipsisInsideTheEndInset() {
        state = PairCodeState(name = "Pyrybox", code = LONG_CODE)
        show()
        assertEllipsizedBefore(
            rule
                .onNodeWithContentDescription("Clear pairing code")
                .fetchSemanticsNode()
                .boundsInRoot.left,
        )

        rule.runOnIdle { state = PairCodeState(targetName = "Pyrybox", code = LONG_CODE, error = WRONG_HOST_ERROR) }
        assertEllipsizedBefore(
            rule
                .onNodeWithContentDescription("Clear pairing code")
                .fetchSemanticsNode()
                .boundsInRoot.left,
        )

        rule.runOnIdle { state = PairCodeState(name = "Pyrybox", code = LONG_CODE, phase = PairCodePhase.Connecting) }
        val field = rule.onNodeWithContentDescription(PAIRING_CODE).fetchSemanticsNode().boundsInRoot
        assertEllipsizedBefore(field.right - with(rule.density) { 16.dp.toPx() })
    }

    @Test
    fun focusedFieldEditsTheFullValueWithoutTheEllipsis() {
        state = PairCodeState(name = "Pyrybox", code = LONG_CODE)
        show()
        rule.onNodeWithContentDescription(PAIRING_CODE).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithTag(VALUE_TAG, useUnmergedTree = true).assertDoesNotExist()
        val node = rule.onNodeWithContentDescription(PAIRING_CODE).fetchSemanticsNode()
        assertTrue(node.config[SemanticsProperties.Focused])
        assertEquals(LONG_CODE, node.config[SemanticsProperties.EditableText].text)
    }

    private fun assertEllipsizedBefore(end: Float) {
        val layouts = mutableListOf<TextLayoutResult>()
        val value = rule.onNodeWithTag(VALUE_TAG, useUnmergedTree = true)
        value.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("value should end with an ellipsis", layouts.single().isLineEllipsized(0))
        val bounds = value.fetchSemanticsNode().boundsInRoot
        assertTrue("value $bounds should end by $end", bounds.right <= end + 0.5f)
    }

    private fun show() = rule.setContent { PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(state, {}) } }

    private companion object {
        const val PAIRING_CODE = "Pairing code"
        const val VALUE_TAG = "$PAIRING_CODE value"
        const val LONG_CODE = "eyJzZXJ2ZXIiOiJob21lLmxhbiIsImtleSI6IkFBQUFDM056YUMxbFpESTFOVEU1QUFBQUlL"
    }
}

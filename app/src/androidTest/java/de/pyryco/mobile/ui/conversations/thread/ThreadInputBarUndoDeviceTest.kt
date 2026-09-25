package de.pyryco.mobile.ui.conversations.thread

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #934 rework: a hardware-keyboard undo changes the composer's field without running its input
 * transformation, and the draft — what Send sends — must still follow it.
 *
 * Device-only: Robolectric's key character map ignores the Ctrl meta state, so there Ctrl+Z types a "z"
 * instead of reaching the field's undo command.
 */
@RunWith(AndroidJUnit4::class)
class ThreadInputBarUndoDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun ctrlZ(action: Int) =
        androidx.compose.ui.input.key.KeyEvent(
            KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        )

    private fun shownText(): String =
        composeRule
            .onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    @Test
    fun undo_reachesTheDraft() {
        var draft by mutableStateOf("")
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(text = draft, onTextChange = { draft = it }, onSend = {})
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("hello")
        composeRule.waitForIdle()
        assertEquals("hello", draft)

        composeRule.onNode(hasSetTextAction()).performKeyPress(ctrlZ(KeyEvent.ACTION_DOWN))
        composeRule.onNode(hasSetTextAction()).performKeyPress(ctrlZ(KeyEvent.ACTION_UP))
        composeRule.waitForIdle()

        assertNotEquals("the undo must change what the field shows", "hello", shownText())
        assertEquals(shownText(), draft)
    }
}

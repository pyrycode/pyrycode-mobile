package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.input.ImeAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1565: Enter in the composer writes a line break; only the send button sends. */
@RunWith(AndroidJUnit4::class)
class ThreadInputBarEnterKeyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var draft by mutableStateOf("")
    private var sends = 0

    private fun setComposer() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(
                    text = draft,
                    onTextChange = { draft = it },
                    onSend = {
                        sends++
                        draft = ""
                    },
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hardwareEnter_insertsNewline_andDoesNotSend() {
        setComposer()
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("first")
        field.performKeyInput { pressKey(Key.Enter) }
        composeRule.waitForIdle()

        assertEquals("first\n", draft)
        assertEquals(0, sends)
    }

    @Test
    fun field_declaresTheDefaultImeAction_notSend() {
        setComposer()

        composeRule
            .onNode(hasSetTextAction())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
    }

    @Test
    fun sendButton_sendsAndClearsTheField() {
        setComposer()
        composeRule.onNode(hasSetTextAction()).performTextInput("hello")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.waitForIdle()

        assertEquals(1, sends)
        assertEquals("", draft)
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #934 rework: the composer's `TextFieldState` stays heap-only, as the draft it mirrors does (#789). */
@RunWith(AndroidJUnit4::class)
class ThreadInputBarDraftBindingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun typedText_neverReachesSavedInstanceState() {
        val registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        var draft by mutableStateOf("")
        composeRule.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                PyrycodeMobileTheme {
                    ThreadInputBar(text = draft, onTextChange = { draft = it }, onSend = {})
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("secret draft")
        composeRule.waitForIdle()
        assertEquals("secret draft", draft)

        val saved = composeRule.runOnIdle { registry.performSave() }

        assertFalse("saved state holds the draft: $saved", saved.toString().contains("secret draft"))
    }
}

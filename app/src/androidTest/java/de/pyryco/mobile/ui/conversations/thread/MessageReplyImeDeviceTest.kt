package de.pyryco.mobile.ui.conversations.thread

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.TestImeRule
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only: actual keyboard visibility and input after the message reply pointer gesture. */
@RunWith(AndroidJUnit4::class)
class MessageReplyImeDeviceTest {
    @get:Rule(order = 0)
    val ime = TestImeRule(selectBeforeTest = true)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun pointerReplyOpensImeAndTypingContinuesAtEndWithoutSending() {
        var draft by mutableStateOf("draft")
        lateinit var view: View
        var sends = 0
        rule.setContent {
            view = LocalView.current
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c",
                            displayName = "Chat",
                            isPromoted = true,
                            hasMessages = true,
                            items =
                                listOf(
                                    ThreadItem.MessageItem(
                                        Message(
                                            "m",
                                            "s",
                                            Role.Assistant,
                                            "hello",
                                            Instant.parse("2026-10-07T00:00:00Z"),
                                            false,
                                        ),
                                    ),
                                ),
                        ),
                    onBack = {},
                    onSendMessage = { sends++ },
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    draft = draft,
                    onDraftChange = { draft = it },
                    onReplyToMessage = { (draft + "\nAssistant:\n\"${it.content}\"\n").also { draft = it } },
                )
            }
        }
        rule.onNodeWithContentDescription("Reply to this message").performTouchInput { click(center) }
        val quote = "draft\nAssistant:\n\"hello\"\n"
        rule.onNode(hasSetTextAction()).assertIsFocused().assertTextEquals(quote)
        assertEquals(
            TextRange(quote.length),
            rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
        rule.waitUntil(5_000) { rule.runOnIdle { ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true } }
        rule.onNode(hasSetTextAction()).performTextInput("answer")
        assertEquals(quote + "answer", draft)
        assertEquals(0, sends)
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.e2e.assertSideMessageCopy
import de.pyryco.mobile.e2e.assertSideMessageReply
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadInputBarMessageReplyTest {
    @get:Rule val rule = createComposeRule()

    private class Keyboard : SoftwareKeyboardController {
        var shows = 0

        override fun show() {
            shows++
        }

        override fun hide() {}
    }

    private fun assertField(expected: String) {
        val field = rule.onNode(hasSetTextAction()).assertIsFocused().fetchSemanticsNode()
        assertEquals(expected, field.config[SemanticsProperties.EditableText].text)
        assertEquals(TextRange(expected.length), field.config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun delayedDraftEchoAdoptsPlainQuoteAndTypingWithoutReplayingFocus() {
        val keyboard = Keyboard()
        var draft by mutableStateOf("draft")
        var request by mutableStateOf<String?>(null)
        var mounted by mutableStateOf(true)
        var busy by mutableStateOf(false)
        val otherFocus = FocusRequester()
        lateinit var focus: FocusManager
        var sends = 0
        rule.setContent {
            focus = LocalFocusManager.current
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    Box(Modifier.size(1.dp).focusRequester(otherFocus).focusable())
                    if (mounted) {
                        ThreadInputBar(
                            text = draft,
                            onTextChange = { draft = it },
                            onSend = { sends++ },
                            isBusy = busy,
                            replyDraft = request,
                            onReplyConsumed = { request = null },
                        )
                    }
                }
            }
        }
        val quote = "draft\nAssistant:\n\"**bold**\n<not markup> \"quote\"\"\n"
        rule.runOnIdle { request = quote } // Hoisted draft intentionally remains stale.
        assertField(quote)
        rule.runOnIdle { draft = quote }
        rule.onNode(hasSetTextAction()).performTextInput("answer")
        assertField(quote + "answer")
        assertEquals(quote + "answer", draft)
        rule.runOnIdle {
            focus.clearFocus()
            busy = true
        }
        rule.onNode(hasSetTextAction()).assertIsNotFocused()
        rule.runOnIdle { mounted = false }
        rule.runOnIdle { otherFocus.requestFocus() }
        rule.runOnIdle { mounted = true }
        rule.onNode(hasSetTextAction()).assertIsNotFocused().assertTextEquals(quote + "answer")
        assertEquals(1, keyboard.shows)
        assertEquals(0, sends)
        rule.runOnIdle { request = draft + "\nUser:\n\"again\"\n" }
        assertField(quote + "answer\nUser:\n\"again\"\n")
        assertEquals(2, keyboard.shows)
    }

    @Test fun threadReplyRoutesSnapshotIntoComposerWithoutTimestampOrSend() {
        val keyboard = Keyboard()
        var draft by mutableStateOf("existing")
        var content by mutableStateOf("arrived")
        var tapped: Message? = null
        var sends = 0
        rule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
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
                                            Message("u", "s", Role.User, "hello", Instant.parse("2026-10-07T00:00:00Z"), false),
                                        ),
                                        ThreadItem.MessageItem(
                                            Message(
                                                "m",
                                                "s",
                                                Role.Assistant,
                                                content,
                                                Instant.parse("2026-10-07T00:00:00Z"),
                                                isStreaming = true,
                                            ),
                                        ),
                                    ),
                            ),
                        onBack = {},
                        onSendMessage = { sends++ },
                        connectionState = de.pyryco.mobile.data.model.ConnectionState.Connected,
                        onRetry = {},
                        draft = draft,
                        onDraftChange = { draft = it },
                        onReplyToMessage = { message ->
                            tapped = message
                            (draft + "\nAssistant:\n\"" + message.content + "\"\n").also { draft = it }
                        },
                    )
                }
            }
        }
        rule.assertSideMessageCopy(Message("u", "s", Role.User, "hello", Instant.parse("2026-10-07T00:00:00Z"), false))
        rule.assertSideMessageReply(
            Message(
                "m",
                "s",
                Role.Assistant,
                "arrived",
                Instant.parse("2026-10-07T00:00:00Z"),
                true,
            ),
            "existing\nAssistant:\n\"arrived\"\n",
        )
        assertField("existing\nAssistant:\n\"arrived\"\n")
        rule.runOnIdle { content = "arrived later" }
        assertField("existing\nAssistant:\n\"arrived\"\n")
        assertEquals("arrived", tapped?.content)
        assertEquals(1, keyboard.shows)
        assertEquals(0, sends)
        rule.onAllNodesWithText(" - ", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }
}

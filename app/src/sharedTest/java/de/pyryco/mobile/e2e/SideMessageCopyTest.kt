package de.pyryco.mobile.e2e

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.MessageBubble
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.UsageLimitDismissals
import de.pyryco.mobile.ui.conversations.thread.dismissalKey
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Exercises the same copy assertion as the live and scripted scenarios, without daemon traffic. */
@RunWith(AndroidJUnit4::class)
class SideMessageCopyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val clipboard =
        InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
    private val message =
        Message("copied", "session", Role.Assistant, "reply - source", Instant.parse("2026-10-07T04:00:00Z"), isStreaming = false)

    private fun showMessages(
        source: Message = message,
        banner: Boolean = false,
        otherTimestamp: Boolean = false,
        timestampVisible: Boolean = false,
        revealOnCopy: Boolean = false,
        wrongCopy: Boolean = false,
    ) {
        var visible by mutableStateOf(timestampVisible)
        val platformCopy =
            object : androidx.compose.ui.platform.ClipboardManager {
                override fun setText(annotatedString: AnnotatedString) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("message", if (wrongCopy) "wrong source" else annotatedString.text))
                    if (revealOnCopy) visible = true
                }

                override fun getText(): AnnotatedString? =
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.text
                        ?.let { AnnotatedString(it.toString()) }
            }
        composeRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalClipboardManager provides platformCopy) {
                    LazyColumn {
                        if (banner) {
                            item { Text("Nearly at usage limit - 7-day window, resets 12.10.2026 at 09:00") }
                        }
                        if (otherTimestamp) {
                            item { MessageBubble(message.copy(id = "other", content = "another reply"), metaRowVisible = true) }
                        }
                        item { MessageBubble(source, metaRowVisible = visible, onToggleMetaRow = { visible = !visible }) }
                    }
                }
            }
        }
        if (source.isStreaming) {
            // Standalone streaming bubbles start at zero; live callers await the displayed prefix.
            composeRule.mainClock.advanceTimeBy(600)
            composeRule.waitForIdle()
        }
    }

    @Test
    fun separatorsInBannerAndMessageBody_doNotCountAsTimestamp_andCopyExactSource() {
        showMessages(banner = true)
        composeRule.assertSideMessageCopy(message)
        composeRule.runOnIdle {
            assertEquals(
                message.content,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
    }

    @Test
    fun anotherRowsTimestamp_doesNotCountAsCopiedMessagesTimestamp() {
        showMessages(otherTimestamp = true)
        composeRule.assertSideMessageCopy(message)
    }

    @OptIn(ExperimentalTestApi::class)
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun realThreadWarningOverlay_isDismissedBeforeThePointerCopy() {
        val sent = message.copy(role = Role.User, content = "Reply with exactly the word: ping (nothing else).")
        val warning = UsageLimitReading("allowed_warning", "seven_day", 0L, 0.8, null)
        var dismissed by mutableStateOf(emptySet<UsageLimitDismissals.Key>())
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 732.dp))) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                conversationId = "conversation",
                                displayName = "Ping",
                                hasMessages = true,
                                items = listOf(sent, message.copy(id = "reply", content = "ping")).map { ThreadItem.MessageItem(it) },
                            ),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        usageLimit = warning,
                        dismissedUsageLimits = dismissed,
                        onDismissUsageLimit = { dismissed = dismissed + it.dismissalKey() },
                    )
                }
            }
        }
        composeRule.assertSideMessageCopy(sent)
        composeRule.runOnIdle { assertEquals(setOf(warning.dismissalKey()), dismissed) }
    }

    @Test
    fun visibleTimestampBeforeCopy_isRejectedBeforeClipboardWrite() {
        showMessages(timestampVisible = true)
        composeRule.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("control", "before assertion")) }
        val failure = assertThrows(AssertionError::class.java) { composeRule.assertSideMessageCopy(message) }
        assertTrue(failure.message.orEmpty().contains("count"))
        composeRule.runOnIdle {
            assertEquals(
                "before assertion",
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
    }

    @Test
    fun timestampRevealedByCopy_isRejectedAfterExactClipboardCheck() {
        showMessages(revealOnCopy = true)
        val failure = assertThrows(AssertionError::class.java) { composeRule.assertSideMessageCopy(message) }
        assertTrue(failure.message.orEmpty().contains("count"))
        composeRule.runOnIdle {
            assertEquals(
                message.content,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
    }

    @Test
    fun incorrectClipboardSource_isRejected() {
        showMessages(wrongCopy = true)
        val failure = assertThrows(org.junit.ComparisonFailure::class.java) { composeRule.assertSideMessageCopy(message) }
        assertEquals(message.content, failure.expected)
        assertEquals("wrong source", failure.actual)
    }

    @Test
    fun streamingSource_copiesTrailingWhitespaceVerbatim() {
        val streaming = message.copy(content = "reply - source   \n", isStreaming = true)
        showMessages(source = streaming, banner = true)
        composeRule.assertSideMessageCopy(streaming)
        composeRule.runOnIdle {
            assertEquals(
                streaming.content,
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString(),
            )
        }
    }

    @Test
    fun sourceBeyondClipboardCap_isCheckedAtExactly100000Characters() {
        val longMessage = message.copy(content = "bounded reply" + " ".repeat(100_000), isStreaming = true)
        showMessages(source = longMessage)
        composeRule.assertSideMessageCopy(longMessage)
        composeRule.runOnIdle {
            val copied =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    .toString()
            assertEquals(100_000, copied.length)
            assertEquals(longMessage.content.take(100_000), copied)
        }
    }
}

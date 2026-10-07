package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ReplySuggestion
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadInputBarSuggestionTest {
    @get:Rule val rule = createComposeRule()
    private var draft by mutableStateOf("")
    private var offer by mutableStateOf<SuggestedReply?>(suggestion())
    private var busy by mutableStateOf(false)
    private var connected by mutableStateOf(true)
    private var sending by mutableStateOf(false)
    private var mounted by mutableStateOf(true)
    private val sent = mutableListOf<String>()
    private var typedSends = 0
    private var stops = 0
    private var haptics = 0

    private fun suggestion(revision: ULong = 1uL) =
        SuggestedReply("  suggested reply  ", ReplySuggestion("c", "s", revision, "  suggested reply  "))

    private fun render() {
        rule.setContent {
            CompositionLocalProvider(
                LocalHapticFeedback provides
                    object : HapticFeedback {
                        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                            haptics++
                        }
                    },
            ) {
                PyrycodeMobileTheme {
                    if (mounted) {
                        ThreadInputBar(
                            text = draft,
                            onTextChange = {
                                draft = it
                                offer = suggestion()
                            },
                            onSend = { typedSends++ },
                            isBusy = busy,
                            onInterrupt = { stops++ },
                            enabled = connected,
                            sending = sending,
                            suggestedReply = offer,
                            onSendSuggestedReply = { armed ->
                                val allowed = offer === armed && draft.isEmpty() && !busy && connected && !sending
                                if (allowed) {
                                    sent += armed.text
                                    offer = null
                                }
                                allowed
                            },
                        )
                    }
                }
            }
        }
    }

    private fun send() = rule.onNodeWithContentDescription("Send message")

    private fun arm() {
        send().performTouchInput {
            down(center)
            advanceEventTime(600)
            moveTo(center)
        }
        rule.waitForIdle()
    }

    @Test fun longPressPulsesOnce_sendsOnInsideReleaseOnce_withoutChangingDraft() {
        render()
        arm()
        assertEquals(1, haptics)
        assertTrue(sent.isEmpty())
        send().performTouchInput {
            advanceEventTime(600)
            moveTo(center)
            up()
        }
        assertEquals(listOf("  suggested reply  "), sent)
        assertEquals(1, haptics)
        assertEquals("", draft)
        rule.onNodeWithText("Message").assertExists()
    }

    @Test fun shortTapNeverSendsSuggestion_andTypedSendAndEmptyStopKeepPrecedence() {
        render()
        send().performTouchInput {
            down(center)
            advanceEventTime(100)
            up()
        }
        assertTrue(sent.isEmpty())
        assertEquals(0, haptics)
        rule.onNode(hasSetTextAction()).performTextInput("typed")
        send().performClick()
        assertEquals(1, typedSends)
        rule.runOnIdle {
            draft = ""
            busy = true
        }
        rule.onNodeWithContentDescription("Stop the running turn").performClick()
        assertEquals(1, stops)
        assertTrue(sent.isEmpty())
    }

    @Test fun movingOutsideCancels_evenAfterFeedbackAndReentry() {
        render()
        arm()
        send().performTouchInput {
            moveTo(Offset(-2f, centerY))
            moveTo(center)
            up()
        }
        assertTrue(sent.isEmpty())
        assertEquals(1, haptics)
    }

    @Test fun leavingBeforeThresholdCancelsWithoutFeedback_evenAfterReentry() {
        render()
        send().performTouchInput {
            down(center)
            advanceEventTime(100)
            moveTo(Offset(width + 1f, centerY))
            advanceEventTime(600)
            moveTo(center)
            up()
        }
        assertEquals(0, haptics)
        assertTrue(sent.isEmpty())
    }

    @Test fun pointerCancellationAndDestinationExitNeverSend() {
        render()
        arm()
        send().performTouchInput { cancel() }
        assertTrue(sent.isEmpty())
        arm()
        rule.runOnIdle { mounted = false }
        // Injection state survives node disposal; release using the root surface.
        rule
            .onNode(
                androidx.compose.ui.test
                    .isRoot(),
            ).performTouchInput { up() }
        assertTrue(sent.isEmpty())
    }

    @Test fun revisionChangeClearDraftEditBusyAndConnectionLossRevokeAnArmedGesture() {
        render()
        val changes: List<() -> Unit> =
            listOf(
                { offer = suggestion(2u) },
                { offer = SuggestedReply("new session", ReplySuggestion("c", "s2", 1u, "new session")) },
                { offer = null },
                { draft = " " },
                { busy = true },
                { connected = false },
                { sending = true },
            )
        for (change in changes) {
            rule.runOnIdle {
                offer = suggestion()
                draft = ""
                busy = false
                connected = true
                sending = false
            }
            arm()
            rule.runOnIdle { change() }
            rule
                .onNode(
                    androidx.compose.ui.test
                        .isRoot(),
                ).performTouchInput { up() }
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun accessibilityActionIsNamed_explicitAndSingleUse_andBlockedWhileUploading() {
        render()
        val action = send().fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Send suggested reply", action.label)
        rule.runOnIdle { assertTrue(action.action()) }
        assertEquals(1, haptics)
        assertEquals(listOf("  suggested reply  "), sent)
        rule.runOnIdle {
            assertTrue(!action.action())
            offer = suggestion(2u)
            sending = true
        }
        assertTrue(!send().fetchSemanticsNode().config.contains(SemanticsActions.CustomActions))
        send().performTouchInput {
            down(center)
            advanceEventTime(600)
            up()
        }
        assertEquals(1, sent.size)
    }

    @Test fun whitespaceIsTyping_andErasureRevealsTheInertSuggestion() {
        render()
        rule.onNodeWithText("  suggested reply  ").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput(" ")
        rule.onNodeWithText("  suggested reply  ").assertDoesNotExist()
        rule.runOnIdle { draft = "" }
        rule.onNodeWithText("  suggested reply  ").assertExists()
        assertTrue(sent.isEmpty())
    }
}

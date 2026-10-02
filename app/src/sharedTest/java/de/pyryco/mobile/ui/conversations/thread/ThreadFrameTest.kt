package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The Figma `16:8` frame (#643): the hand-rolled header, and the composer that now carries the
 * status area, the input field's send/stop button and the relocated model/effort footer.
 *
 * Covers what the relocation itself can break. The interrupt's own lifecycle across `thinking` /
 * `responding` / `turn_end` stays proven by [ScriptedThreadRenderTest] against the real fold; these
 * cases pin the header's truncation contract, the button's two-action precedence, and that the
 * standalone foot-of-list stop control is gone rather than merely duplicated.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadFrameTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

    private fun state(displayName: String = "Test channel"): ThreadUiState =
        ThreadUiState(
            conversationId = "c1",
            displayName = displayName,
            isPromoted = true,
            hasMessages = false,
        )

    private fun setThread(
        displayName: String = "Test channel",
        isBusy: Boolean = false,
        isThinking: Boolean = false,
        onBack: () -> Unit = {},
        onTitleClick: () -> Unit = {},
        onInterrupt: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state(displayName),
                    onBack = onBack,
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = isThinking,
                    isBusy = isBusy,
                    onInterrupt = onInterrupt,
                    onTitleClick = onTitleClick,
                )
            }
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun referenceFrame_placesHeaderAndMessageRegionAtFigmaAnchors() {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state("pyrycode discord integration"),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }

        val title = composeTestRule.onNodeWithText("pyrycode discord integration").getUnclippedBoundsInRoot()
        val back = composeTestRule.onNodeWithContentDescription(string(R.string.cd_back)).getUnclippedBoundsInRoot()
        val overflow = composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).getUnclippedBoundsInRoot()
        assertEquals(56f, title.left.value, 1f)
        assertEquals(8f, back.left.value, 1f)
        assertEquals(16f, back.top.value, 1f)
        assertEquals(404f, overflow.right.value, 1f)
        val messages = composeTestRule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        // #1562: Figma 621:3571 starts the message area at the rule's bottom edge.
        assertEquals(69f, messages.top.value, 2f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun referenceFrame_footerTouchTargetsClearTheSendButton() {
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state(),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }

        val send =
            composeTestRule
                .onNodeWithContentDescription(
                    string(R.string.cd_send_message),
                    useUnmergedTree = true,
                ).onParent()
                .getUnclippedBoundsInRoot()
        val attach =
            composeTestRule
                .onNodeWithContentDescription(
                    string(R.string.cd_attach_files),
                    useUnmergedTree = true,
                ).onParent()
                .getUnclippedBoundsInRoot()
        val status =
            composeTestRule
                .onNodeWithContentDescription(
                    string(R.string.cd_thread_status_expand),
                    useUnmergedTree = true,
                ).onParent()
                .getUnclippedBoundsInRoot()
        val field = composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true).onParent().getUnclippedBoundsInRoot()
        assertTrue("attachment touch target must clear send: send=$send attach=$attach", attach.top >= send.bottom)
        assertTrue("status touch target must clear send", status.top >= send.bottom)
        assertTrue("footer touch targets must clear the input surface", minOf(attach.top, status.top) >= field.bottom)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun referenceFrame_pointerAtInputFooterBoundaryGoesToInputControls() {
        var interrupts = 0
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = state(),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        isBusy = true,
                        onInterrupt = { interrupts++ },
                    )
                }
            }
        }

        val send =
            composeTestRule
                .onNodeWithContentDescription(string(R.string.cd_thread_interrupt), useUnmergedTree = true)
                .onParent()
        val field = composeTestRule.onNode(hasSetTextAction(), useUnmergedTree = true)
        send.performTouchInput {
            click(bottomCenter + Offset(0f, -2.dp.toPx()))
        }
        assertEquals("the stop control owns its lower edge", 1, interrupts)

        field.onParent().performTouchInput {
            click(bottomLeft + Offset(48.dp.toPx(), -2.dp.toPx()))
        }
        field.assertIsFocused()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.cd_thread_status_expand), useUnmergedTree = true)
            .onParent()
            .performTouchInput { click(center) }
        composeTestRule.onNodeWithText("Run configuration").assertIsDisplayed()
    }

    // AC#1: the title truncates inside its own slot. Both controls keep their full width and their
    // descriptions, and neither is overlapped by the title — asserted on unclipped bounds, which is
    // where an over-wide title would actually show up.
    @Test
    fun longTitle_truncatesAndLeavesBothControlsReachable() {
        setThread(displayName = LONG_TITLE)

        val back = composeTestRule.onNodeWithContentDescription(string(R.string.cd_back))
        val overflow = composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions))
        back.assertIsDisplayed()
        overflow.assertIsDisplayed()

        val titleBounds = composeTestRule.onNodeWithText(LONG_TITLE).getUnclippedBoundsInRoot()
        val backBounds = back.getUnclippedBoundsInRoot()
        val overflowBounds = overflow.getUnclippedBoundsInRoot()
        assertTrue(
            "title must start after the back control",
            titleBounds.left.value >= backBounds.right.value,
        )
        assertTrue(
            "title must end before the overflow control",
            titleBounds.right.value <= overflowBounds.left.value,
        )
    }

    // AC#1: the rewritten bar keeps both controls' actions — back pops, and the overflow still opens
    // its menu beneath its own glyph rather than anchoring against the bar.
    @Test
    fun header_backAndOverflowKeepTheirActions() {
        var backs = 0
        var titleTaps = 0
        setThread(onBack = { backs++ }, onTitleClick = { titleTaps++ })

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_back)).performClick()
        assertEquals(1, backs)

        composeTestRule.onNodeWithText("Test channel").performClick()
        assertEquals(1, titleTaps)

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_more_actions)).performClick()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_channel_info)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.thread_overflow_change_workspace)).assertDoesNotExist()
    }

    // AC#3: text present wins over the in-flight turn, so the tap that queues a message while the
    // agent is busy (#461) still exists. Send, not stop, under "Send message".
    @Test
    fun inputButton_sendsWhenTextPresent() {
        var sends = 0
        var interrupts = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(
                    text = "queued while busy",
                    onTextChange = {},
                    onSend = { sends++ },
                    isBusy = true,
                    onInterrupt = { interrupts++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_send_message)).performClick()
        assertEquals(1, sends)
        assertEquals(0, interrupts)
    }

    // AC#3: an empty composer during an in-flight turn carries the stop action through the existing
    // interrupt path, and reverts to a disabled send once the turn ends.
    @Test
    fun inputButton_stopsWhileBusyWithEmptyField() {
        var sends = 0
        var interrupts = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(
                    text = "",
                    onTextChange = {},
                    onSend = { sends++ },
                    isBusy = true,
                    onInterrupt = { interrupts++ },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_send_message)).assertDoesNotExist()
        val stop = composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt))
        stop.assertIsEnabled()
        stop.performClick()
        assertEquals(1, interrupts)
        assertEquals(0, sends)
    }

    @Test
    fun inputButton_isDisabledSendWhenIdleAndEmpty() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadInputBar(text = "", onTextChange = {}, onSend = {})
            }
        }

        composeTestRule.onNodeWithContentDescription(string(R.string.cd_thread_interrupt)).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(string(R.string.cd_send_message)).assertIsNotEnabled()
    }

    // AC#3: no standalone foot-of-list stop control remains, so the waiting signal and the stop
    // affordance no longer stack. Exactly one stop control exists, and it sits on the composer's
    // input row — below the status area that carries the waiting signal, not above it.
    @Test
    fun busyThread_hasExactlyOneStopControlAndItSitsInTheComposer() {
        setThread(isBusy = true, isThinking = true)

        assertEquals(
            1,
            composeTestRule
                .onAllNodesWithContentDescription(string(R.string.cd_thread_interrupt))
                .fetchSemanticsNodes()
                .size,
        )
        val thinkingBounds =
            composeTestRule
                .onNodeWithContentDescription(string(R.string.cd_thread_thinking))
                .getUnclippedBoundsInRoot()
        val stopBounds =
            composeTestRule
                .onNodeWithContentDescription(string(R.string.cd_thread_interrupt))
                .getUnclippedBoundsInRoot()
        val fieldBounds =
            composeTestRule
                .onNodeWithText(string(R.string.thread_input_placeholder))
                .getUnclippedBoundsInRoot()
        assertTrue(
            "the stop control must sit below the waiting signal",
            stopBounds.top.value >= thinkingBounds.bottom.value,
        )
        assertTrue(
            "the stop control must sit on the composer's input row, not above it",
            stopBounds.bottom.value >= fieldBounds.top.value,
        )
    }

    private companion object {
        const val LONG_TITLE =
            "a channel display name long enough to overrun the title slot several times over"
    }
}

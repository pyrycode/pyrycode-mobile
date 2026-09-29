package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.TurnOutcomeReport
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1002: notices draw as pills in the thread's Top overlay, and the status row keeps only live turn status.
 */
@RunWith(AndroidJUnit4::class)
class ThreadTopOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dismissDescription: String = context.getString(R.string.thread_notice_dismiss)

    private val warning =
        UsageLimitReading(
            status = "allowed_warning",
            limitType = "seven_day",
            resetsAt = 0L,
            utilization = 0.8,
            truncatedFields = null,
        )

    private var state by mutableStateOf(ThreadUiState(conversationId = "c1", displayName = "Overlay"))
    private var usageLimit by mutableStateOf<UsageLimitReading?>(null)
    private var dismissed by mutableStateOf(emptySet<UsageLimitDismissals.Key>())
    private var showRePair by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var resetting by mutableStateOf<ResetStatus?>(null)
    private var turnOutcome by mutableStateOf<TurnOutcomeReport?>(null)
    private var rePairTaps = 0

    private fun setScreen() {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    usageLimit = usageLimit,
                    dismissedUsageLimits = dismissed,
                    onDismissUsageLimit = { dismissed = dismissed + it.dismissalKey() },
                    showRePair = showRePair,
                    onRePair = { rePairTaps++ },
                    isBusy = isBusy,
                    resetting = resetting,
                    turnOutcome = turnOutcome,
                )
            }
        }
    }

    private fun label(
        status: String,
        agent: String = "Claude",
    ): String =
        context.getString(R.string.thread_usage_limit_label, agent, status) + context.getString(R.string.thread_usage_limit_spent, 80)

    @Test
    fun noNotices_drawNoPill() {
        setScreen()

        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
        composeRule.onNodeWithText(RE_PAIR_LABEL).assertDoesNotExist()
    }

    @Test
    fun aWarning_isADismissiblePill_thatReturnsWhenTheResetTimeChanges() {
        usageLimit = warning
        setScreen()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(dismissDescription).performClick()

        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()

        usageLimit = warning.copy(resetsAt = 1L)
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertIsDisplayed()
    }

    // #1115: a Claude conversation reads exactly as before; a Codex one names Codex.
    @Test
    fun theUsagePill_namesTheConversationsAgent() {
        usageLimit = warning.copy(status = "rejected")
        setScreen()
        composeRule.onNodeWithContentDescription("Claude reports usage-limit status: rejected · 80% spent").assertIsDisplayed()

        state = state.copy(agent = ConversationAgent.Codex)
        composeRule.onNodeWithContentDescription("Codex reports usage-limit status: rejected · 80% spent").assertIsDisplayed()

        usageLimit = warning.copy(status = "")
        composeRule.onNodeWithContentDescription("Codex reported a usage-limit update · 80% spent").assertIsDisplayed()
    }

    @Test
    fun anyOtherStatus_isAnErrorPill_withNoX() {
        usageLimit = warning.copy(status = "rejected")
        setScreen()
        composeRule.onNodeWithContentDescription(label("rejected")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()

        usageLimit = warning.copy(status = "something_new")
        composeRule.onNodeWithContentDescription(label("something_new")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
    }

    @Test
    fun theUsagePill_sitsAboveThePairingPill_whichStartsRePair() {
        usageLimit = warning
        showRePair = true
        setScreen()

        val usageBounds = composeRule.onNodeWithContentDescription(label("allowed_warning")).getUnclippedBoundsInRoot()
        val pairingTop = composeRule.onNodeWithContentDescription(RE_PAIR_LABEL).getUnclippedBoundsInRoot().top
        val usageTop = usageBounds.top
        assertTrue("usage pill at $usageTop should sit above the pairing pill at $pairingTop", usageTop < pairingTop)
        assertEquals(12f, (pairingTop - usageBounds.bottom).value, 0.5f)

        composeRule.onNodeWithText(RE_PAIR_LABEL).performClick()
        composeRule.runOnIdle { assertEquals(1, rePairTaps) }
    }

    // AC #4: a live usage reading no longer masks live turn status.
    @Test
    fun aLiveReading_leavesTheRunningTool_theWrapUp_andInterruptedInTheStatusRow() {
        usageLimit = warning
        isBusy = true
        state =
            state.copy(
                isPromoted = true,
                hasMessages = true,
                items = listOf(runningTool("Bash")),
            )
        setScreen()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
        composeRule.onNodeWithText("Running Bash…").assertIsDisplayed()

        resetting = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.thread_resetting_wrapping_up))
            .assertIsDisplayed()

        resetting = null
        isBusy = false
        turnOutcome = TurnOutcomeReport(TurnOutcomeReport.Kind.Interrupted, emptyList(), null)
        composeRule
            .onNodeWithText(context.getString(R.string.thread_turn_outcome_interrupted), substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
    }

    private fun runningTool(name: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = "t1",
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = Instant.parse("2026-09-24T10:00:00Z"),
                isStreaming = false,
                toolCall = ToolCall(toolName = name, input = "", output = "", status = ToolCallStatus.Running),
            ),
        )

    private companion object {
        const val RE_PAIR_LABEL = "Pairing error - Re-pair"
    }
}

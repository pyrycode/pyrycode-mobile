package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1357: the status area's recovery advice through [ThreadScreen]. The context notice carries a Compact pill
 * that sends `/compact` the Actions menu's way and has no click action while the published menu proves the
 * command absent; the billing and sign-in notices name the conversation's agent and offer no pill.
 */
@RunWith(AndroidJUnit4::class)
class ThreadRecoveryNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val commands = mutableListOf<ComposerAction>()

    private fun setScreen(
        notice: TurnRecoveryNotice,
        agent: ConversationAgent = ConversationAgent.Claude,
        absentActions: Set<ComposerAction> = emptySet(),
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "conversation",
                            displayName = "Recovery",
                            isPromoted = true,
                            agent = agent,
                            absentActions = absentActions,
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    turnOutcome = notice,
                    onComposerCommand = { commands += it },
                )
            }
        }
    }

    @Test
    fun theContextNotice_offersCompact_whichSendsTheCompactCommand() {
        setScreen(TurnRecoveryNotice.ContextTooLong)

        composeRule.onNodeWithText("Context too long. Compact or reset the session.").assertIsDisplayed()
        composeRule
            .onNodeWithText("Compact")
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()

        assertEquals(listOf(ComposerAction.CompactSession), commands)
    }

    @Test
    fun compact_hasNoClickAction_whileTheCommandIsAbsent() {
        setScreen(TurnRecoveryNotice.ContextTooLong, absentActions = setOf(ComposerAction.CompactSession))

        composeRule.onNodeWithText("Compact").assertIsDisplayed().assert(hasNoClickAction)
        composeRule.onNodeWithText("Compact").performClick()

        assertEquals(emptyList<ComposerAction>(), commands)
    }

    @Test
    fun theBillingNotice_namesTheAgent_withNoCompact() {
        setScreen(TurnRecoveryNotice.BillingError, ConversationAgent.Codex)

        composeRule.onNodeWithText("Codex reported a billing error. Check Codex billing on this server.").assertIsDisplayed()
        composeRule.onNodeWithText("Compact").assertDoesNotExist()
    }

    @Test
    fun theSignInNotice_namesTheAgent_withNoCompact() {
        setScreen(TurnRecoveryNotice.AuthenticationFailed, ConversationAgent.Claude)

        composeRule
            .onNodeWithText("Claude reported an authentication failure. Check Claude sign-in on this server.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Compact").assertDoesNotExist()
    }

    private val hasNoClickAction = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
}

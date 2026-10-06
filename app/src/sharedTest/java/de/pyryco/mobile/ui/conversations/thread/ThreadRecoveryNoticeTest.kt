package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.ui.conversations.components.STATUS_GLYPH_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.pixelPx
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * #1603: top-overlay recovery advice through [ThreadScreen]. The whole context pill sends `/compact`
 * through Actions, with no click action when absent. Agent-specific billing/sign-in pills are inert.
 */
@RunWith(AndroidJUnit4::class)
class ThreadRecoveryNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var rePairTaps = 0

    private val commands = mutableListOf<ComposerAction>()

    private fun setScreen(
        notice: TurnRecoveryNotice,
        agent: ConversationAgent = ConversationAgent.Claude,
        absentActions: Set<ComposerAction> = emptySet(),
        showRePair: Boolean = false,
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
                    showRePair = showRePair,
                    onRePair = { rePairTaps++ },
                    onComposerCommand = { commands += it },
                )
            }
        }
    }

    @Test
    fun theContextNotice_offersCompact_whichSendsTheCompactCommand() {
        setScreen(TurnRecoveryNotice.ContextTooLong)

        composeRule.onNodeWithText("Context too long - Compact").assertIsDisplayed()
        composeRule
            .onNodeWithText("Context too long - Compact")
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performTouchInput {
                click(centerLeft + Offset(2f, 0f))
            }

        composeRule.onNode(hasText("Context too long - Compact") and hasAnyAncestor(hasTestTag("thread-status-band"))).assertDoesNotExist()
        composeRule.onNodeWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf(ComposerAction.CompactSession), commands)
    }

    @Test
    fun everyPillEdge_dispatchesCompactOncePerPointerTap() {
        setScreen(TurnRecoveryNotice.ContextTooLong)
        val pill = composeRule.onNodeWithText("Context too long - Compact")
        pill.performTouchInput {
            click(centerLeft + Offset(2f, 0f))
        }
        assertEquals(1, commands.size)
        pill.performTouchInput {
            click(centerRight - Offset(2f, 0f))
        }
        assertEquals(2, commands.size)
        pill.performTouchInput {
            click(topCenter + Offset(0f, 2f))
        }
        assertEquals(3, commands.size)
        pill.performTouchInput {
            click(bottomCenter - Offset(0f, 2f))
        }
        assertEquals(List(4) { ComposerAction.CompactSession }, commands)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactTarget_extendsBelowTheVisiblePill_toAtLeast48Dp() {
        setScreen(TurnRecoveryNotice.ContextTooLong)
        assertCompactTargetAndTapBelowPill()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactTarget_besideRePair_routesOutsideSurfaceTapsToOnlyTheirOwnAction() {
        setScreen(TurnRecoveryNotice.ContextTooLong, showRePair = true)
        val compact = composeRule.onNodeWithText("Context too long - Compact").fetchSemanticsNode().touchBoundsInRoot
        val rePair = composeRule.onNodeWithContentDescription("Pairing error - Re-pair")
        val pairing = rePair.fetchSemanticsNode()
        assertTrue("Action targets must not overlap", pairing.touchBoundsInRoot.bottom <= compact.top)
        // Tap the neighbouring action near its lower drawn edge, then Compact below its surface.
        composeRule.onRoot().performTouchInput {
            click(Offset(pairing.boundsInRoot.center.x, pairing.boundsInRoot.bottom - 2f))
        }
        assertEquals(1, rePairTaps)
        assertEquals(emptyList<ComposerAction>(), commands)
        assertCompactTargetAndTapBelowPill()
        assertEquals(1, rePairTaps)
    }

    private fun assertCompactTargetAndTapBelowPill() {
        val target = composeRule.onNodeWithText("Context too long - Compact")
        val visible =
            composeRule
                .onNodeWithContentDescription(
                    "Context too long - Compact",
                    useUnmergedTree = true,
                ).fetchSemanticsNode()
                .boundsInRoot
        val touch = target.fetchSemanticsNode().touchBoundsInRoot
        val density = composeRule.density.density
        // The pill's height is the sum of its own top padding, its text line, and its bottom padding, each
        // rounded to a device pixel on its own (#1823), so the check allows one device pixel of drift.
        assertEquals(24f * density, visible.height, pixelPx())
        assertTrue("Compact needs at least 48dp touch height: $touch", touch.height >= 48f * density)
        assertEquals(visible.top, touch.top, 0.5f)
        // Both taps are outside the drawn pill and near the target's left/right bottom edges.
        target.performTouchInput { click(Offset(2f, height - 2f)) }
        assertEquals(listOf(ComposerAction.CompactSession), commands)
        target.performTouchInput { click(Offset(width - 2f, height - 2f)) }
        assertEquals(List(2) { ComposerAction.CompactSession }, commands)
    }

    @Test
    fun compact_hasNoClickAction_whileTheCommandIsAbsent() {
        setScreen(TurnRecoveryNotice.ContextTooLong, absentActions = setOf(ComposerAction.CompactSession))

        composeRule.onNodeWithText("Context too long - Compact").assertIsDisplayed().assert(hasNoClickAction)
        composeRule.onNodeWithText("Context too long - Compact").performClick()

        assertEquals(emptyList<ComposerAction>(), commands)
    }

    @Test
    fun theBillingNotice_namesTheAgent_withNoCompact() {
        setScreen(TurnRecoveryNotice.BillingError, ConversationAgent.Codex)

        composeRule
            .onNodeWithText(
                "Codex reported a billing error. Check Codex billing on this server.",
            ).assertIsDisplayed()
            .assert(hasNoClickAction)
        composeRule.onNodeWithText("Context too long - Compact").assertDoesNotExist()
    }

    @Test
    fun theSignInNotice_namesTheAgent_withNoCompact() {
        setScreen(TurnRecoveryNotice.AuthenticationFailed, ConversationAgent.Claude)

        composeRule
            .onNodeWithText("Claude reported an authentication failure. Check Claude sign-in on this server.")
            .assertIsDisplayed()
            .assert(hasNoClickAction)
        composeRule.onNodeWithText("Context too long - Compact").assertDoesNotExist()
    }

    private val hasNoClickAction = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)
}

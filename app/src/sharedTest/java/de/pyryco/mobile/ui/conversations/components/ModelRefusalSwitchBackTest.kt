package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The switch-back button on a refusal row (#1360) in Figma's three states, 646-2833 (offered), 646-4694
 * (pending) and 646-4700 (failed), and, through [ThreadScreen], only on the row that armed the offer.
 *
 * Fixtures are hand-written literals, never captured live payloads.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelRefusalSwitchBackTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val failedMessage = "Could not change the model — try again."

    private fun setRow(
        offer: SwitchBackOffer?,
        onSwitchBack: () -> Unit = {},
        knownModelLabel: (String) -> String? = { null },
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    ModelRefusalRow(
                        item = ROW,
                        agent = ConversationAgent.Claude,
                        switchBack = offer,
                        onSwitchBack = onSwitchBack,
                        knownModelLabel = knownModelLabel,
                    )
                }
            }
        }
    }

    @Test
    fun offered_showsAnEnabledButtonNamingTheStrippedOriginalModel_andATapCallsBackOnce() {
        var taps = 0
        setRow(OFFER.copy(originalModel = "claude-\u001b[31mopus\u001b[0m-5-5\n"), onSwitchBack = { taps++ })

        composeRule
            .onNodeWithText("Switch back to claude-opus-5-5")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, taps)
        composeRule.onNodeWithText(failedMessage).assertDoesNotExist()
        // The toggle is still the title block's own action.
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
    }

    @Test
    fun pending_disablesTheButton() {
        var taps = 0
        setRow(OFFER.copy(pending = true), onSwitchBack = { taps++ })

        composeRule
            .onNodeWithText("Switch back to claude-opus-5-5")
            .assertIsDisplayed()
            .assertIsNotEnabled()
            .performClick()

        assertEquals(0, taps)
        composeRule.onNodeWithText(failedMessage).assertDoesNotExist()
    }

    @Test
    fun failed_keepsTheButtonEnabled_withTheRetryMessageBelow() {
        setRow(OFFER.copy(failed = true))

        composeRule.onNodeWithText("Switch back to claude-opus-5-5").assertIsEnabled()
        composeRule.onNodeWithText(failedMessage).assertIsDisplayed()
    }

    @Test
    fun visibleOutline_matchesTheCollapsedDesign_andBothTouchExtensionsInvokeSwitchBack() {
        var taps = 0
        setRow(OFFER.copy(failed = true), onSwitchBack = { taps++ }, knownModelLabel = {
            if (it ==
                ROW.originalModel
            ) {
                "Opus"
            } else {
                "Sonnet"
            }
        })
        val outlineNode = composeRule.onNodeWithTag("refusal-switch-back-outline", useUnmergedTree = true)
        val outline = outlineNode.fetchSemanticsNode().boundsInRoot
        val visible = outlineNode.getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText("Refused on Opus, continued on Sonnet", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val target = composeRule.onNodeWithText("Switch back to Opus").fetchSemanticsNode().touchBoundsInRoot
        val failure = composeRule.onNodeWithText(failedMessage).getUnclippedBoundsInRoot()
        assertEquals(32f, (visible.bottom - visible.top).value, 0.5f)
        assertEquals("outline starts 56dp below row top (title starts at 8dp)", 48f, (visible.top - title.top).value, 0.5f)
        assertEquals(48f, target.height, 0.5f)
        assertEquals(4f, (failure.top - visible.bottom).value, 0.5f)
        for (y in listOf(outline.top - 4f, outline.bottom + 4f)) {
            composeRule.onRoot().performTouchInput { click(Offset(outline.center.x, y)) }
        }
        assertEquals(2, taps)
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
        composeRule.onNodeWithText("Hide details").assertDoesNotExist()
        // A physical tap on the adjacent details text still toggles only the explanation.
        composeRule.onNodeWithText("Show details").performTouchInput { click() }
        composeRule.onNodeWithText("Hide details").assertIsDisplayed()
        assertEquals(2, taps)
    }

    @Test
    fun pendingTouchExtension_doesNotInvokeEitherAction() {
        var taps = 0
        setRow(OFFER.copy(pending = true), onSwitchBack = { taps++ })
        val outline = composeRule.onNodeWithTag("refusal-switch-back-outline", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        composeRule.onRoot().performTouchInput { click(Offset(outline.center.x, outline.top - 4f)) }
        assertEquals(0, taps)
        composeRule.onNodeWithText("Show details").assertIsDisplayed()
    }

    @Test
    fun withoutAnOffer_thereIsNoButton() {
        setRow(offer = null)

        composeRule.onNodeWithText("Switch back", substring = true).assertDoesNotExist()
    }

    @Test
    fun inTheThread_onlyTheArmingRowShowsTheButton() {
        var taps = 0
        val later = ROW.copy(originalModel = "claude-haiku-4-5", occurredAt = Instant.parse("2026-09-23T12:00:05Z"))
        val noFallback = ROW.copy(fallbackModel = null)
        composeRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "conversation",
                            displayName = "Refusals",
                            isPromoted = true,
                            hasMessages = true,
                            items = listOf(ROW, noFallback, later),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    switchBackOffer = OFFER,
                    onSwitchBack = { taps++ },
                )
            }
        }

        composeRule.onAllNodesWithText("Switch back", substring = true).assertCountEquals(1)
        composeRule.onNode(hasText("Switch back to claude-opus-5-5") and hasClickAction()).performClick()
        assertEquals(1, taps)
        // The button names the arming row's model, not the later row's.
        composeRule.onNode(hasClickLabel("Show Claude's explanation") and hasText("claude-haiku-4-5", substring = true)).assertExists()
    }

    private fun hasClickLabel(label: String) =
        SemanticsMatcher("click label is \"$label\"") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    private companion object {
        val AT: Instant = Instant.parse("2026-09-23T12:00:00Z")
        val ROW = ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "Retried on Sonnet.", false, AT)
        val OFFER = SwitchBackOffer(AT, "claude-opus-5-5", pending = false, failed = false)
    }
}

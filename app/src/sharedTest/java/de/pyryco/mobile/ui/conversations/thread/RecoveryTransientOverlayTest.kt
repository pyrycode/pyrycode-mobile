package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.pixelPx
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecoveryTransientOverlayTest {
    @get:Rule val composeRule = createComposeRule()
    private var compactTaps = 0
    private var retryTaps = 0
    private var rePairTaps = 0

    @Test fun actionableRecovery_thenTransient_preservesVisibleGapAndRoutesPointerTaps() = verifyStack()

    @Test fun rePair_thenRecovery_thenTransient_preservesVisibleGapAndRoutesPointerTaps() = verifyStack(rePair = true)

    @Test fun offline_thenRecovery_thenTransient_preservesVisibleGapAndRoutesPointerTaps() = verifyStack(offline = true)

    @Test fun unavailableCompact_thenTransient_preservesVisibleGapAndRemainsInert() = verifyStack(available = false)

    @Test fun wrappedRecovery_thenTransient_measuresGapFromTheFullVisibleHeight() = verifyStack(overlayWidth = 140)

    @Test fun billing_thenTransient_preservesVisibleGapAndRemainsInert() = verifyStack(notice = TurnRecoveryNotice.BillingError)

    @Test fun signIn_thenTransient_preservesVisibleGapAndRemainsInert() = verifyStack(notice = TurnRecoveryNotice.AuthenticationFailed)

    private fun verifyStack(
        available: Boolean = true,
        rePair: Boolean = false,
        offline: Boolean = false,
        overlayWidth: Int = 300,
        notice: TurnRecoveryNotice = TurnRecoveryNotice.ContextTooLong,
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme(dynamicColor = false) {
                ThreadTopOverlay(
                    usageLimit = null,
                    usageLimitDismissed = false,
                    onDismissUsageLimit = {},
                    showRePair = rePair,
                    onRePair = { rePairTaps++ },
                    connectionState = if (offline) ConnectionState.Offline else ConnectionState.Connected,
                    onRetryConnection = { retryTaps++ },
                    turnOutcome = notice,
                    onCompact = if (available) ({ compactTaps++ }) else null,
                    transientError = "Attachment is too large",
                    modifier = Modifier.width(overlayWidth.dp),
                )
            }
        }
        val density = composeRule.density.density
        val label =
            when (notice) {
                TurnRecoveryNotice.ContextTooLong -> LABEL
                TurnRecoveryNotice.BillingError -> "Claude reported a billing error. Check Claude billing on this server."
                TurnRecoveryNotice.AuthenticationFailed -> "Claude reported an authentication failure. Check Claude sign-in on this server."
            }
        val visible = composeRule.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val error = composeRule.onNodeWithTag("transient_error_notice").assertHasNoClickAction()
        val errorBounds = error.fetchSemanticsNode().boundsInRoot
        if (notice == TurnRecoveryNotice.ContextTooLong && overlayWidth == 300) {
            // The pill's height is the sum of its own top padding, its text line, and its bottom padding, each
            // rounded to a device pixel on its own (#1823), so the check allows one device pixel of drift.
            assertEquals(24f * density, visible.height, pixelPx())
        } else {
            assertTrue("This case must exercise wrapped copy", visible.height > 24f * density)
        }
        assertEquals(12f * density, errorBounds.top - visible.bottom, 0.5f)
        assertEquals(visible.right, errorBounds.right, 0.5f)
        val compact = composeRule.onNodeWithText(label)
        if (available && notice == TurnRecoveryNotice.ContextTooLong) {
            val target = compact.fetchSemanticsNode().touchBoundsInRoot
            assertTrue(target.height >= 48f * density)
            // Both lower corners overlap the transient notice vertically. The notice owns its pixels.
            composeRule.onRoot().performTouchInput {
                click(Offset(errorBounds.left + 2f, errorBounds.top + 2f))
                click(Offset(errorBounds.right - 2f, errorBounds.top + 2f))
                click(errorBounds.center)
            }
            assertEquals(0, compactTaps)
            // Invisible target pixels in the 12dp gap still invoke Compact exactly once.
            composeRule.onRoot().performTouchInput {
                click(Offset(visible.left + 2f, visible.bottom + 6f * density))
                click(Offset(visible.right - 2f, visible.bottom + 6f * density))
            }
            assertEquals(2, compactTaps)
            compact.performTouchInput { click(Offset(width - 2f, 2f)) }
            assertEquals(3, compactTaps)
        } else {
            compact.assertHasNoClickAction()
            error.performTouchInput { click(center) }
            assertEquals(0, compactTaps)
        }
        assertEquals(0, retryTaps)
        assertEquals(0, rePairTaps)
    }

    private companion object {
        const val LABEL = "Context too long - Compact"
    }
}

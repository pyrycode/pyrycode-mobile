package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairCodeScreenVerificationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun heldVerificationFailureDecidesWhetherPairRetries() {
        val events = mutableListOf<PairCodeEvent>()
        var state by mutableStateOf(failed(PairingVerification.Failure.Rejected))
        rule.setContent { PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(state, { events += it }) } }

        rule.onNodeWithText(PairingVerification.Failure.Rejected.message).performScrollTo()
        rule.onNodeWithText("Retry").assertDoesNotExist()
        rule.onNodeWithText("Pair").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithContentDescription("Pairing code").assertIsNotEnabled()
        rule
            .onNodeWithText("Cancel")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.runOnIdle { assertEquals(listOf<PairCodeEvent>(PairCodeEvent.Back), events.toList()) }

        rule.runOnIdle {
            events.clear()
            state = failed(PairingVerification.Failure.Unavailable)
        }
        rule.onNodeWithText(PairingVerification.Failure.Unavailable.message).performScrollTo()
        rule.onNodeWithContentDescription("Pairing code").assertIsNotEnabled()
        rule
            .onNodeWithText("Retry")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        rule.runOnIdle { assertEquals(listOf<PairCodeEvent>(PairCodeEvent.Pair), events.toList()) }
    }

    private fun failed(failure: PairingVerification.Failure) = PairCodeState(code = "code", error = failure.message, failure = failure)
}

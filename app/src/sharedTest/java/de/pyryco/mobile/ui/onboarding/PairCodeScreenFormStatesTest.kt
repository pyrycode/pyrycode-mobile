package de.pyryco.mobile.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Pair Code States frames (`663:2886`): the busy ring and read-only fields without clear icons (#1464). */
@RunWith(AndroidJUnit4::class)
class PairCodeScreenFormStatesTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(PairCodeState(name = "Pyrybox", code = "code"))

    @Test
    fun busyPairButtonShowsTheProgressRingBeforeItsLabel() {
        show()
        assertEquals(emptyList<ProgressBarRangeInfo>(), rings())

        for ((phase, label) in listOf(PairCodePhase.Saving to "Saving…", PairCodePhase.Connecting to "Connecting…")) {
            rule.runOnIdle { state = state.copy(phase = phase) }
            rule.onNodeWithText(label).assertExists()
            assertEquals(listOf(ProgressBarRangeInfo.Indeterminate), rings())
            val ring = rule.onNode(indeterminate, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val text = rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assert(ring.right <= text.left) { "$phase ring $ring should sit before its label $text" }
        }
    }

    @Test
    fun readOnlyFieldsDropTheClearIcon() {
        show()
        val held = PairingVerification.Failure.Unavailable
        for (readOnly in listOf(
            state.copy(phase = PairCodePhase.Saving),
            state.copy(phase = PairCodePhase.Connecting),
            state.copy(error = held.message, failure = held),
        )) {
            rule.runOnIdle { state = readOnly }
            rule.onNodeWithContentDescription("Clear host name").assertDoesNotExist()
            rule.onNodeWithContentDescription("Clear pairing code").assertDoesNotExist()
        }

        rule.runOnIdle { state = PairCodeState(targetName = "Pyrybox", code = "code", error = WRONG_HOST_ERROR) }
        rule.onNodeWithContentDescription("Clear host name").assertDoesNotExist()
        rule.onNodeWithContentDescription("Clear pairing code").assertIsEnabled()
    }

    @Test
    fun editableFieldInErrorKeepsTheClearIcon() {
        state = state.copy(error = INVALID_CODE_ERROR)
        show()
        rule.onNodeWithText(INVALID_CODE_ERROR).assertExists()
        rule.onNodeWithContentDescription("Clear pairing code").assertIsEnabled()
        rule.onNodeWithContentDescription("Clear host name").assertIsEnabled()
    }

    private val indeterminate = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)

    private fun rings() =
        rule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.ProgressBarRangeInfo] }

    private fun show() = rule.setContent { PyrycodeMobileTheme(darkTheme = true) { PairCodeScreen(state, {}) } }
}

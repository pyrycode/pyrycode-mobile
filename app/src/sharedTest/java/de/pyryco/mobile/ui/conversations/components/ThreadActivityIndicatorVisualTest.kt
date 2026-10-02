package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadActivityIndicatorVisualTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun shortReadingsUseTheInputStatusBandHeight() {
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.width(372.dp)) {
                        Box(Modifier.testTag("thinking")) { ThinkingIndicator(isThinking = true) }
                        Box(Modifier.testTag("retry")) { ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown) }
                        Box(Modifier.testTag("compacting")) { CompactingIndicator(isCompacting = true) }
                        Box(Modifier.testTag("reset")) {
                            ResettingIndicator(status = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Pending))
                        }
                        Box(Modifier.testTag("outcome")) {
                            TurnOutcomeIndicator(
                                notice = TurnRecoveryNotice.ContextTooLong,
                                agent = ConversationAgent.Claude,
                                onCompact = {},
                            )
                        }
                    }
                }
            }
        }

        // #1312: the text readings no longer carry a 16dp glyph, so they fit inside the band, whose 24dp minimum
        // height ThreadScreen's band holds with the snowflake (see ThreadStatusBandTest); the pill sets its own.
        listOf("thinking", "retry", "compacting", "reset").forEach { tag ->
            val bounds = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag fits the band", bounds.bottom - bounds.top <= 24.dp)
        }
        // #1357: the recovery notice may wrap to its two lines; the Compact pill beside it holds the band height.
        composeRule.onNodeWithText("Compact").assertHeightIsEqualTo(24.dp)
    }
}

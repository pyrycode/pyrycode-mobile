package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performTouchInput

/** One physical Send now action, after revealing its center between the thread chrome. */
internal fun ComposeTestRule.tapSendNowOnce(
    matcher: SemanticsMatcher,
    evidence: (String) -> Unit = {},
) {
    questionAnswerTarget(matcher, evidence = evidence).assertIsDisplayed().performTouchInput { click(center) }
}

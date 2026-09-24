package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex

internal const val SESSION_BOUNDARY_EXPLANATION = "Claude doesn't remember messages above this line"

internal fun ComposeTestRule.awaitDisplayedSessionBoundary(timeoutMillis: Long) {
    val explanation = onNodeWithText(SESSION_BOUNDARY_EXPLANATION, substring = true)
    waitUntil(timeoutMillis) {
        // ThreadScreen reverses its list. A wrap-up reply can keep the new boundary offscreen. Only the
        // lazy list scrolls to an index: the overflow menu's scroll node lingers through its exit animation.
        onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        explanation.isDisplayed()
    }
    explanation.assertIsDisplayed()
}

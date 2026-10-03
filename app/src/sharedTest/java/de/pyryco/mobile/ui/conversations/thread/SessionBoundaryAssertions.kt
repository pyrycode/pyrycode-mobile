package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG

/** The retired boundary explanation's claim (#1578); tests only assert it is absent. */
internal const val SESSION_BOUNDARY_EXPLANATION_FRAGMENT = "doesn't remember"

internal fun ComposeTestRule.assertNoSessionBoundaryExplanation() {
    onAllNodesWithText(SESSION_BOUNDARY_EXPLANATION_FRAGMENT, substring = true).assertCountEquals(0)
}

internal fun ComposeTestRule.awaitDisplayedSessionBoundary(timeoutMillis: Long) {
    val boundary = onNodeWithTag(SESSION_BOUNDARY_TEST_TAG)
    waitUntil(timeoutMillis) {
        // ThreadScreen reverses its list. A wrap-up reply can keep the new boundary offscreen. Only the
        // lazy list scrolls to an index: the overflow menu's scroll node lingers through its exit animation.
        onNode(hasScrollToNodeAction()).performScrollToIndex(0)
        boundary.isDisplayed()
    }
    boundary.assertIsDisplayed()
    assertNoSessionBoundaryExplanation()
}

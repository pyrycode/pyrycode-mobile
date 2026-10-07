package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst

/** The active-list presence observation shared by the live archive drive and its regression proof. */
internal fun ComposeTestRule.awaitArchiveRoundTripChat(
    name: String,
    timeoutMillis: Long,
): SemanticsNodeInteraction {
    waitUntil(timeoutMillis) {
        onAllNodesWithText(name, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
    return onAllNodesWithText(name, substring = true).onFirst().assertIsDisplayed()
}

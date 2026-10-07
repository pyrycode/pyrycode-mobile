package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import de.pyryco.mobile.ui.conversations.list.TREE_CHAT_ROW_TEST_TAG

/** Locate the chat in the whole active tree, including rows LazyColumn has not composed yet. */
internal fun ComposeTestRule.awaitArchiveRoundTripChat(
    name: String,
    timeoutMillis: Long,
): SemanticsNodeInteraction {
    val row = hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText(name)
    val list = hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(CHANNEL_LIST_TEST_TAG))
    waitUntil(timeoutMillis) {
        if (onAllNodes(list).fetchSemanticsNodes().isEmpty()) {
            false
        } else {
            try {
                onNode(list).performScrollToNode(row)
                true
            } catch (missing: AssertionError) {
                // A pending projection may not hold the row yet. Other assertion failures stay failures.
                if (!missing.message.orEmpty().startsWith("No node found that matches ")) throw missing
                false
            }
        }
    }
    return onNode(row).assertIsDisplayed()
}

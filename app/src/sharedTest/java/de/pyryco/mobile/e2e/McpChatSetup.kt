package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG

/** Locate the MCP scenario's addressed Chats control before its single creation click. */
internal fun ComposeTestRule.awaitMcpChatAddControl(
    serverId: String,
    timeoutMillis: Long,
    evidence: (String) -> Unit,
): SemanticsNodeInteraction {
    val plus = hasTestTag(treeHostChatAddTestTag(serverId))
    val list = hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(CHANNEL_LIST_TEST_TAG))
    evidence("event=mcp_chat_setup plusComposed=${onAllNodes(plus).fetchSemanticsNodes().isNotEmpty()}")
    waitUntil(timeoutMillis) {
        if (onAllNodes(list).fetchSemanticsNodes().isEmpty()) {
            false
        } else {
            try {
                onNode(list).performScrollToNode(plus)
                true
            } catch (missing: AssertionError) {
                // A pending screen projection may not contain the control yet.
                if (!missing.message.orEmpty().startsWith("No node found that matches ")) throw missing
                false
            }
        }
    }
    val control = onNode(plus).assertIsDisplayed()
    evidence("event=mcp_chat_setup status=located plusComposed=true")
    return control
}

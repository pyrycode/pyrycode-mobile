package de.pyryco.mobile.e2e

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag

/** A global tier tag spans every host and only composed rows; use the list's loaded host snapshot. */
internal fun ComposeTestRule.createChannelFromEmptyHost(
    serverId: String,
    timeoutMillis: Long,
    snapshots: () -> List<HostConversationSnapshot>,
) {
    waitUntil(timeoutMillis) {
        val target = snapshots().singleOrNull { it.serverId == serverId }
        target != null && target.rowsLoaded && target.channels.isEmpty()
    }
    val plus = hasTestTag(treeHostChannelAddTestTag(serverId))
    waitUntil(timeoutMillis) {
        runCatching { onNode(hasScrollToNodeAction()).performScrollToNode(plus) }.isSuccess
    }
    onNode(plus).performClick()
}

package de.pyryco.mobile.e2e

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag
import kotlinx.coroutines.CancellationException

/** Locate the rename scenario's own host control, retaining content-free state before cleanup. */
internal fun ComposeTestRule.awaitRenameChatAddControl(
    serverId: String,
    timeoutMillis: Long,
    snapshots: () -> List<HostConversationSnapshot>,
    evidence: (String) -> Unit,
): SemanticsNodeInteraction {
    val plus = hasTestTag(treeHostChatAddTestTag(serverId))
    fun observation(stage: String): String {
        val host = snapshots().singleOrNull { it.serverId == serverId }
        return "stage=$stage time_ms=${System.currentTimeMillis()} target_present=${host != null} " +
            "relay_connected=${host?.connectionStatus?.relay == RelayLinkStatus.Connected} " +
            "daemon_connected=${host?.connectionStatus?.pyrycode == PyrycodeLinkStatus.Connected} " +
            "rows_loaded=${host?.rowsLoaded == true} channels=${host?.channels?.size?.coerceAtMost(999) ?: 0} " +
            "chats=${host?.chats?.size?.coerceAtMost(999) ?: 0} " +
            "plus_composed=${onAllNodes(plus).fetchSemanticsNodes().isNotEmpty()}"
    }
    try {
        evidence(observation("start"))
        waitUntil(timeoutMillis) { onAllNodes(plus).fetchSemanticsNodes().isNotEmpty() }
        evidence(observation("located"))
        return onNode(plus).assertIsDisplayed()
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        val state = runCatching { observation("failed") }.getOrDefault("stage=failed evidence=unavailable")
        runCatching { evidence(state) }
        throw AssertionError("rename setup failed before creation; $state", failure)
    }
}

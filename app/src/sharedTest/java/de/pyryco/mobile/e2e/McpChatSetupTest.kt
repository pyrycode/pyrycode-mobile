package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.HostChannelListEntry
import de.pyryco.mobile.ui.conversations.list.HostChannelListState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpChatSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedTargetBelowChannelsIsCreatedOnce() {
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        show(listOf(host("target").copy(channels = List(30) { channel(it) }), host("other")), events)
        compose.onAllNodes(hasTestTag(treeHostChatAddTestTag("target"))).assertCountEquals(0)

        compose.awaitMcpChatAddControl("target", 1_000, evidence::add).performClick()

        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
        assertTrue(evidence.first().contains("plusComposed=false"))
        assertEquals("event=mcp_chat_setup status=located plusComposed=true", evidence.last())
    }

    @Test fun visibleTargetIsCreatedOnceWithoutSelectingAnotherHost() {
        val events = mutableListOf<ChannelListEvent>()
        show(listOf(host("target"), host("other")), events)
        compose.awaitMcpChatAddControl("target", 1_000, {}).performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
    }

    @Test fun missingTargetTimesOutWithoutCreatingOnReadyPeer() {
        val events = mutableListOf<ChannelListEvent>()
        show(listOf(host("other")), events)
        assertThrows(ComposeTimeoutException::class.java) {
            compose.awaitMcpChatAddControl("missing", 100, {}).performClick()
        }
        assertTrue(events.isEmpty())
    }

    private fun show(
        hosts: List<HostConversationSnapshot>,
        events: MutableList<ChannelListEvent>,
    ) {
        compose.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(HostChannelListState(hosts.map { HostChannelListEntry(it, emptyList(), 0) }), onEvent = { events += it })
            }
        }
    }

    private fun host(id: String) =
        HostConversationSnapshot(
            serverId = id,
            displayName = id,
            connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
            rowsLoaded = true,
        )

    private fun channel(index: Int) =
        Conversation(
            id = "channel-$index",
            name = "Channel $index",
            cwd = "/fixture",
            currentSessionId = "session-$index",
            sessionHistory = emptyList(),
            isPromoted = true,
            lastUsedAt = Instant.fromEpochSeconds(0),
        )
}

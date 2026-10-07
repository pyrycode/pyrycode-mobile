package de.pyryco.mobile.e2e

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.HostChannelListEntry
import de.pyryco.mobile.ui.conversations.list.HostChannelListState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveRoundTripChatTest {
    @get:Rule val compose = createComposeRule()
    private val name = "e2e551-regression"
    private val preceding = List(24) { chat("a-suite-$it") }
    private val target = chat(name)

    @Test fun presenceFindsChatBeyondViewportBeforeAndAfterRestore() {
        val state = mutableStateOf(listState(preceding + target))
        compose.setContent { PyrycodeMobileTheme { ChannelListScreen(state.value, onEvent = {}) } }
        // The repository projection holds the renamed chat, but LazyColumn has not composed its row.
        compose.onAllNodesWithText(name).assertCountEquals(0)
        compose.awaitArchiveRoundTripChat(name, 1_000)

        compose.runOnIdle { state.value = listState(preceding) }
        compose.onAllNodesWithText(name).assertCountEquals(0)
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToIndex(0)
        compose.runOnIdle { state.value = listState(preceding + target) }
        compose.onAllNodesWithText(name).assertCountEquals(0)
        compose.awaitArchiveRoundTripChat(name, 1_000)
    }

    @Test fun absentChatStillFailsThePresenceObservation() {
        compose.setContent { PyrycodeMobileTheme { ChannelListScreen(listState(preceding), onEvent = {}) } }
        assertThrows(ComposeTimeoutException::class.java) { compose.awaitArchiveRoundTripChat(name, 500) }
    }

    private fun listState(chats: List<Conversation>): HostChannelListState {
        val host =
            HostConversationSnapshot(
                serverId = "host",
                displayName = "Host",
                connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                channels = emptyList(),
                chats = chats,
            )
        return HostChannelListState(listOf(HostChannelListEntry(host, chats.take(3), chats.size)))
    }

    private fun chat(name: String) =
        Conversation(
            id = name,
            name = name,
            cwd = "~/scratch",
            currentSessionId = "$name-session",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.fromEpochSeconds(0),
        )
}

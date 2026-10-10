package de.pyryco.mobile.e2e

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.ChannelListEvent
import de.pyryco.mobile.ui.conversations.list.ChannelListScreen
import de.pyryco.mobile.ui.conversations.list.HostChannelListEntry
import de.pyryco.mobile.ui.conversations.list.HostChannelListState
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.list.TREE_CHANNEL_ROW_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmptyHostChannelSetupTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyTargetCreatesWhileAnotherSelectedHostKeepsItsChannel() {
        val otherChannel = channel("shared-id", "Other host channel")
        val hosts = listOf(host("target"), host("other", listOf(otherChannel)))
        val events = mutableListOf<ChannelListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(
                    HostChannelListState(
                        hosts = hosts.map(::entry),
                        selected = HostConversationTarget("other", otherChannel.id),
                    ),
                    onEvent = { events += it },
                )
            }
        }
        composeTestRule.onAllNodes(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)).assertCountEquals(1)
        composeTestRule.onNode(hasText(otherChannel.name.orEmpty())).assertIsDisplayed()

        composeTestRule.createChannelFromEmptyHost("target", 1_000) { hosts }

        assertEquals(listOf(ChannelListEvent.TreeHostChannelAddTapped("target")), events)
        composeTestRule.onNode(hasText(otherChannel.name.orEmpty())).assertIsDisplayed()
        assertEquals(listOf(otherChannel), hosts.last().channels)
    }

    @Test
    fun missingTargetDoesNotBorrowAnotherHostsEmptyList() {
        assertHeldUntilLoadedEmptyTarget(null)
    }

    @Test
    fun unloadedEmptyTargetDoesNotEstablishChannelAbsence() {
        assertHeldUntilLoadedEmptyTarget(host("target").copy(rowsLoaded = false))
    }

    @Test
    fun staleTargetChannelsMustLeaveTheListSnapshotBeforeCreation() {
        assertHeldUntilLoadedEmptyTarget(host("target", listOf(channel("shared-id", "Target fixture"))))
    }

    private fun assertHeldUntilLoadedEmptyTarget(initialTarget: HostConversationSnapshot?) {
        val other = host("other")
        var hosts by mutableStateOf(listOfNotNull(initialTarget, other))
        val events = mutableListOf<ChannelListEvent>()
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(HostChannelListState(hosts.map(::entry)), onEvent = { events += it })
            }
        }

        assertThrows(ComposeTimeoutException::class.java) {
            composeTestRule.createChannelFromEmptyHost("target", 100) { hosts }
        }
        assertTrue("creation ran before the target's loaded empty snapshot", events.isEmpty())

        composeTestRule.runOnIdle { hosts = listOf(host("target"), other) }
        composeTestRule.createChannelFromEmptyHost("target", 1_000) { hosts }
        assertEquals(listOf(ChannelListEvent.TreeHostChannelAddTapped("target")), events)
        assertEquals(other, hosts.last())
    }

    private fun host(
        id: String,
        channels: List<Conversation> = emptyList(),
    ) = HostConversationSnapshot(
        serverId = id,
        displayName = id,
        connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
        channels = channels,
        rowsLoaded = true,
    )

    private fun entry(host: HostConversationSnapshot) = HostChannelListEntry(host, emptyList(), 0)

    private fun channel(
        id: String,
        name: String,
    ) = Conversation(
        id = id,
        name = name,
        cwd = "/fixture",
        currentSessionId = "$id-session",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = Instant.fromEpochSeconds(0),
    )
}

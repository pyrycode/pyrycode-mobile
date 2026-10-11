package de.pyryco.mobile.e2e

import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenameChatSetupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectedTargetBelowChannelsIsLocatedAndClickedOnce() {
        val target = host("target").copy(channels = List(30) { channel(it) })
        val hosts = listOf(target, host("other"))
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(hosts), onEvent = { events += it }) }
        }
        // The loaded, connected target owns the control, but lazy composition has not drawn it.
        compose.onAllNodes(hasTestTag(treeHostChatAddTestTag("target"))).assertCountEquals(0)

        compose.awaitRenameChatAddControl("target", 1_000, { hosts }, evidence::add).performClick()

        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
        assertTrue(evidence.first().contains("daemon_connected=true rows_loaded=true channels=30"))
        assertTrue(evidence.first().contains("plus_composed=false"))
        assertTrue(evidence.last().contains("stage=located"))
        assertTrue(evidence.last().contains("plus_composed=true"))
        assertEquals(listOf("start", "ready", "located"), evidence.map { it.substringAfter("stage=").substringBefore(' ') })
        // Retain the controlled before/after observations in the JUnit report, like the live Log sink.
        evidence.forEach { println("event=rename_setup $it") }
    }

    @Test fun anotherConnectedHostCannotSatisfyTargetReadiness() {
        val hosts = mutableStateOf(listOf(host("other"), host("target", connected = false)))
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(hosts.value), onEvent = { events += it }) }
        }
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.awaitRenameChatAddControl("target", 100, { hosts.value }, evidence::add).performClick()
            }
        assertTrue(failure.message.orEmpty().contains("daemon_connected=false"))
        assertTrue(events.isEmpty())
        compose.runOnIdle { hosts.value = listOf(host("other"), host("target")) }
        compose.awaitRenameChatAddControl("target", 1_000, { hosts.value }, evidence::add).performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
    }

    @Test fun unavailableTargetFailsBeforeCreationWithBoundedEvidence() {
        val secret = "private-host-label".repeat(1000)
        val hosts = listOf(host(secret))
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(hosts), onEvent = { events += it }) }
        }
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.awaitRenameChatAddControl("missing", 100, { hosts }, evidence::add).performClick()
            }
        assertTrue(failure.message.orEmpty().contains("target_present=false"))
        assertTrue(failure.cause is androidx.compose.ui.test.ComposeTimeoutException)
        assertTrue(evidence.last().startsWith("stage=failed"))
        assertTrue(evidence.all { it.length < 350 && !it.contains("private-host-label") })
        assertTrue(events.isEmpty())
    }

    @Test fun composedControlDoesNotEstablishLoadedTargetReadiness() {
        val hosts = mutableStateOf(listOf(host("target").copy(rowsLoaded = false)))
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(hosts.value), onEvent = { events += it }) }
        }
        compose.onAllNodes(hasTestTag(treeHostChatAddTestTag("target"))).assertCountEquals(1)
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.awaitRenameChatAddControl("target", 100, { hosts.value }, evidence::add).performClick()
            }
        assertTrue(failure.message.orEmpty().contains("rows_loaded=false"))
        assertTrue(events.isEmpty())
        compose.runOnIdle { hosts.value = listOf(host("target")) }
        compose.awaitRenameChatAddControl("target", 1_000, { hosts.value }, evidence::add).performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
    }

    @Test fun failureEvidenceCannotReplaceOriginalFailure() {
        val original = AssertionError("unrelated assertion")
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.awaitRenameChatAddControl("target", 100, { throw original }, { error("evidence sink") })
            }
        assertSame(original, failure.cause)
        assertTrue(failure.message.orEmpty().contains("evidence=unavailable"))
    }

    @Test fun pendingScreenProjectionKeepsMissingControlWithinBoundedWait() {
        val loaded = listOf(host("target"))
        val drawn = mutableStateOf(listOf(host("target", connected = false)))
        val events = mutableListOf<ChannelListEvent>()
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(drawn.value), onEvent = { events += it }) }
        }
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.awaitRenameChatAddControl("target", 100, { loaded }, evidence::add).performClick()
            }
        assertTrue(failure.cause is androidx.compose.ui.test.ComposeTimeoutException)
        assertEquals(1, evidence.count { it.startsWith("stage=ready") })
        assertTrue(events.isEmpty())
        compose.runOnIdle { drawn.value = loaded }
        compose.awaitRenameChatAddControl("target", 1_000, { loaded }, evidence::add).performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("target")), events)
    }

    @Test fun observationsCapCountsAndOmitHostContent() {
        val privateText = "private-host-and-title"
        val target =
            host(privateText).copy(
                rowsLoaded = false,
                channels = List(1_000) { channel(it).copy(name = privateText) },
                chats = List(1_000) { channel(it).copy(isPromoted = false, name = privateText) },
            )
        val evidence = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state(listOf(target)), onEvent = {}) }
        }
        assertThrows(AssertionError::class.java) {
            compose.awaitRenameChatAddControl(privateText, 100, { listOf(target) }, evidence::add)
        }
        assertEquals(2, evidence.size)
        assertTrue(evidence.all { it.contains("channels=999 chats=999") && !it.contains(privateText) && it.length < 350 })
    }

    @Test fun cancellationPassesThrough() {
        val original = CancellationException("cancelled")
        val failure =
            assertThrows(CancellationException::class.java) {
                compose.awaitRenameChatAddControl("target", 100, { throw original }, {})
            }
        assertSame(original, failure)
    }

    private fun state(hosts: List<HostConversationSnapshot>) = HostChannelListState(hosts.map { HostChannelListEntry(it, emptyList(), 0) })

    private fun host(
        id: String,
        connected: Boolean = true,
    ) = HostConversationSnapshot(
        serverId = id,
        displayName = id,
        connectionStatus =
            ConnectionStatus(
                if (connected) RelayLinkStatus.Connected else RelayLinkStatus.Idle,
                if (connected) PyrycodeLinkStatus.Connected else PyrycodeLinkStatus.Down,
            ),
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

package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatus
import de.pyryco.mobile.data.repository.McpStatusReport
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * #1344: Channel info asks for the MCP reading on every opening unless the session reports `mcp_servers`
 * false, sends each Reconnect and switch tap once for the thread's own conversation, and releases both waits
 * whenever the sheet closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelMcpServersTest {
    private val logs = mutableListOf<String>()
    private val previousSink = RelayLog.sink

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        RelayLog.sink = previousSink
        Dispatchers.resetMain()
    }

    @Test
    fun everyOpening_requestsOnceForTheThreadsConversation() =
        runTest {
            val (vm, repo) = rig()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            assertEquals(listOf("request:$CONV"), repo.calls)

            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            assertEquals("request:$CONV", repo.calls.last())
            assertEquals(2, repo.calls.count { it.startsWith("request:") })
        }

    @Test
    fun mcpServersTrue_orNoCapabilityList_stillRequests() =
        runTest {
            val (vm, repo) = rig()
            repo.fake.setSessionSettingsReading(CONV, settings(SessionCapabilities(emptyList(), emptyList(), mcpServers = true)))
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)

            assertEquals(listOf("request:$CONV"), repo.calls)
        }

    @Test
    fun mcpServersFalse_opensWithoutRequest() =
        runTest {
            val (vm, repo) = rig()
            repo.fake.setSessionSettingsReading(CONV, settings(SessionCapabilities(emptyList(), emptyList(), mcpServers = false)))
            advanceUntilIdle()
            assertFalse(vm.state.value.runConfig.mcpServersSupported)

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)

            assertEquals(true, vm.state.value.channelInfoOpen)
            assertEquals(emptyList<String>(), repo.calls)
        }

    @Test
    fun reconnectAndToggle_sendOnceEach_forTheThreadsConversation() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            repo.calls.clear()

            vm.onOverflowEvent(ThreadEvent.McpReconnect("github"))
            vm.onOverflowEvent(ThreadEvent.McpToggle("linear", enabled = false))
            vm.onOverflowEvent(ThreadEvent.McpToggle("github", enabled = true))

            assertEquals(
                listOf("reconnect:$CONV:github", "toggle:$CONV:linear:false", "toggle:$CONV:github:true"),
                repo.calls,
            )
        }

    @Test
    fun dismiss_releasesBothWaitsOnce() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            repo.calls.clear()

            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)

            assertEquals(listOf("endReconnect:$CONV", "endToggle:$CONV"), repo.calls)
            assertFalse(vm.state.value.channelInfoOpen)
        }

    @Test
    fun archiveFromTheOpenSheet_releasesBothWaits_butFromAClosedSheetReleasesNothing() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.Archive)
            assertEquals(emptyList<String>(), repo.calls.filter { it.startsWith("end") })

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.Archive)

            assertEquals(listOf("endReconnect:$CONV", "endToggle:$CONV"), repo.calls.filter { it.startsWith("end") })
        }

    @Test
    fun theRepositoryReading_reachesTheState() =
        runTest {
            val (vm, repo) = rig()
            val status =
                McpStatus(
                    report = McpStatusReport(listOf(McpServerStatus("github", "failed", "boom", "user", "1")), 2),
                    toggling = true,
                )

            repo.mcp.value = status

            assertEquals(status, vm.state.value.mcpStatus)
        }

    @Test
    fun logsAndEvents_carryNoServerNameOrConversationId() =
        runTest {
            val (vm, _) = rig()
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.McpReconnect("secret-server"))
            vm.onOverflowEvent(ThreadEvent.McpToggle("secret-server", enabled = false))
            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)

            val mcpLogs = logs.filter { "mcp_" in it }
            assertEquals(
                listOf(
                    "event=mcp_status_requested",
                    "event=mcp_reconnect_sent",
                    "event=mcp_toggle_sent enabled=false",
                    "event=mcp_wait_released",
                ),
                mcpLogs,
            )
            assertFalse("secret-server" in ThreadEvent.McpReconnect("secret-server").toString())
            assertFalse("secret-server" in ThreadEvent.McpToggle("secret-server", true).toString())
        }

    private fun TestScope.rig(): Pair<ThreadViewModel, RecordingRepo> {
        val repo = RecordingRepo()
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to repo
    }

    private class RecordingRepo(
        val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        val calls = mutableListOf<String>()
        val mcp = MutableStateFlow(McpStatus())

        override fun observeMcpStatus(conversationId: String): Flow<McpStatus> = mcp

        override fun requestMcpStatus(conversationId: String) {
            calls += "request:$conversationId"
        }

        override fun reconnectMcpServer(
            conversationId: String,
            serverName: String,
        ) {
            calls += "reconnect:$conversationId:$serverName"
        }

        override fun toggleMcpServer(
            conversationId: String,
            serverName: String,
            enabled: Boolean,
        ) {
            calls += "toggle:$conversationId:$serverName:$enabled"
        }

        override fun endMcpReconnectWait(conversationId: String) {
            calls += "endReconnect:$conversationId"
        }

        override fun endMcpToggleWait(conversationId: String) {
            calls += "endToggle:$conversationId"
        }
    }

    private fun settings(capabilities: SessionCapabilities?) =
        SessionSettings(
            sessionId = "sess-a",
            model = "",
            effort = "",
            effectiveEffort = EffectiveEffort.Unavailable,
            permissionMode = "default",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
            capabilities = capabilities,
        )

    private companion object {
        const val CONV = "seed-channel-personal"
    }
}

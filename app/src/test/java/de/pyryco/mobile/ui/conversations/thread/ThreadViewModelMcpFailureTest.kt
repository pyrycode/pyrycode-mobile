package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1345: a failed MCP server raises a notice in the thread's Top overlay. A tap acknowledges every failure the
 * report shows and opens Channel info; acknowledgements last the app run, per host and per conversation. The
 * thread asks for MCP status when it opens and on each reconnect of its host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelMcpFailureTest {
    private val logs = mutableListOf<String>()
    private val previousSink = RelayLog.sink
    private val repo = RecordingRepo()
    private val acks = McpFailureAcknowledgements()
    private val connection = FakeConnectionStateSource()
    private val available = MutableStateFlow(true)

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
    fun theFirstUnacknowledgedFailureInReportOrder_isTheNotice() =
        runTest {
            val vm = rig()
            assertNull(vm.mcpFailure.value)

            repo.report(CONV, server("ok", "connected"), server("pyry_approve", "failed"), server("github", "failed"))

            assertEquals("pyry_approve", vm.mcpFailure.value)
        }

    @Test
    fun aTap_acknowledgesEveryFailure_andOpensChannelInfo_askingOnce() =
        runTest {
            val vm = rig()
            repo.report(CONV, server("a", "failed"), server("b", "failed"), server("c", "connected"))
            repo.calls.clear()

            vm.onMcpFailureTapped()

            assertNull(vm.mcpFailure.value)
            assertEquals(setOf("a", "b"), acks.acknowledged.value[HOST]?.get(CONV))
            assertTrue(vm.state.value.channelInfoOpen)
            assertEquals(listOf("request:$CONV"), repo.calls)
        }

    @Test
    fun aRepeatReportStaysQuiet_aNewFailureRaisesItsOwn_aRecoveredServerStopsShowing() =
        runTest {
            val vm = rig()
            repo.report(CONV, server("a", "failed"))
            vm.onMcpFailureTapped()

            repo.report(CONV, server("a", "failed"))
            assertNull(vm.mcpFailure.value)

            repo.report(CONV, server("a", "failed"), server("b", "failed"))
            assertEquals("b", vm.mcpFailure.value)

            repo.report(CONV, server("a", "failed"), server("b", "connected"))
            assertNull(vm.mcpFailure.value)
        }

    @Test
    fun acknowledgements_surviveReopeningTheChat_andAReconnect() =
        runTest {
            val first = rig()
            repo.report(CONV, server("a", "failed"))
            first.onMcpFailureTapped()

            val reopened = rig()
            assertNull(reopened.mcpFailure.value)

            available.value = false
            repo.report(CONV)
            available.value = true
            repo.report(CONV, server("a", "failed"))
            assertNull(reopened.mcpFailure.value)
        }

    @Test
    fun acknowledgingInOneChat_leavesAnotherChatsNoticeShowing() =
        runTest {
            val here = rig()
            val there = rig(conversationId = OTHER)
            repo.report(CONV, server("a", "failed"))
            repo.report(OTHER, server("a", "failed"))

            here.onMcpFailureTapped()

            assertNull(here.mcpFailure.value)
            assertEquals("a", there.mcpFailure.value)
        }

    @Test
    fun theSameConversationOnAnotherHost_keepsItsNotice() =
        runTest {
            val here = rig()
            val otherHost = rig(serverId = "host-b")
            repo.report(CONV, server("a", "failed"))

            here.onMcpFailureTapped()

            assertEquals("a", otherHost.mcpFailure.value)
        }

    @Test
    fun theNotice_showsOnlyWhileTheHostIsConnected() =
        runTest {
            val vm = rig()
            repo.report(CONV, server("a", "failed"))

            connection.emit(ConnectionState.Offline)
            assertNull(vm.mcpFailure.value)

            connection.emit(ConnectionState.Connected)
            assertEquals("a", vm.mcpFailure.value)
        }

    @Test
    fun openingAndEachReconnect_askOnce() =
        runTest {
            available.value = false
            val vm = rig(clear = false)
            assertEquals(emptyList<String>(), repo.calls)

            available.value = true
            assertEquals(listOf("request:$CONV"), repo.calls)

            available.value = false
            available.value = true
            assertEquals(listOf("request:$CONV", "request:$CONV"), repo.calls)
            assertEquals(
                listOf("event=mcp_status_requested reason=reconnect"),
                logs.filter { "mcp_status_requested" in it },
            )
            assertFalse(vm.state.value.channelInfoOpen)
        }

    @Test
    fun aRepositoryAvailableAtOpening_asksOnce() =
        runTest {
            rig(clear = false)

            assertEquals(listOf("request:$CONV"), repo.calls)
        }

    @Test
    fun mcpServersFalse_reconnectsWithoutAsking() =
        runTest {
            rig()
            repo.fake.setSessionSettingsReading(CONV, settings(mcpServers = false))
            advanceUntilIdle()

            available.value = false
            available.value = true

            assertEquals(emptyList<String>(), repo.calls)
        }

    @Test
    fun logs_carryNoServerName() =
        runTest {
            val vm = rig(clear = false)
            repo.report(CONV, server("secret-server", "failed"))

            vm.onMcpFailureTapped()

            assertTrue(logs.contains("event=mcp_failure_acknowledged count=1"))
            assertTrue("a server name must never be logged: $logs", logs.none { "secret-server" in it })
        }

    private fun TestScope.rig(
        conversationId: String = CONV,
        serverId: String = HOST,
        clear: Boolean = true,
    ): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to serverId, "conversationId" to conversationId)),
                repo,
                connection,
                ComposerDraftStore(),
                repositoryAvailable = available,
                mcpFailureAcknowledgements = acks,
            )
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.mcpFailure.collect {} }
        runCurrent()
        if (clear) {
            repo.calls.clear()
            logs.clear()
        }
        return vm
    }

    private fun server(
        name: String,
        status: String,
    ) = McpServerStatus(name, status, "", "", "")

    private class RecordingRepo(
        val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        val calls = mutableListOf<String>()
        private val readings = mutableMapOf<String, MutableStateFlow<McpStatus>>()

        private fun reading(conversationId: String) = readings.getOrPut(conversationId) { MutableStateFlow(McpStatus()) }

        fun report(
            conversationId: String,
            vararg servers: McpServerStatus,
        ) {
            reading(conversationId).value = McpStatus(report = McpStatusReport(servers.toList(), 0))
        }

        override fun observeMcpStatus(conversationId: String): Flow<McpStatus> = reading(conversationId)

        override fun requestMcpStatus(conversationId: String) {
            calls += "request:$conversationId"
        }
    }

    private fun settings(mcpServers: Boolean) =
        SessionSettings(
            sessionId = "sess-a",
            model = "",
            effort = "",
            effectiveEffort = EffectiveEffort.Unavailable,
            permissionMode = "default",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
            capabilities = SessionCapabilities(emptyList(), emptyList(), mcpServers = mcpServers),
        )

    private companion object {
        const val HOST = "host-a"
        const val CONV = "seed-channel-personal"
        const val OTHER = "seed-discussion-scratch"
    }
}

package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP server status of each conversation (#1343): the `mcp_status` frame decoded behind the `interactive` gate,
 * the three request verbs, and the five flags set and cleared by correlated refusals and later reports, copied from
 * desktop's `mcpStatusStore`. Wire SSOT: pyrycode `docs/protocol-mobile.md` § `mcp_status`, § "Asking for MCP status
 * on demand" and § "Actuating MCP servers on demand".
 */
class RemoteConversationRepositoryMcpStatusTest {
    // ---- decode and projection --------------------------------------------------------------------

    @Test
    fun pushedReport_nullUntilAFrame_thenVerbatimInClaudesOrder() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            assertEquals(listOf(McpStatus()), readings)
            assertNull(readings.single().report)

            pump.push(mcpStatus("c1", SERVERS_TWO, dropped = 3, id = 1L))
            runCurrent()

            assertEquals(
                McpStatusReport(
                    servers =
                        listOf(
                            McpServerStatus("github", "connected", "", "user", "1.2.0"),
                            McpServerStatus("files", "failed", "spawn failed", "project", ""),
                        ),
                    droppedServers = 3,
                ),
                readings.last().report,
            )
        }

    @Test
    fun emptyServerList_isAReportOfNoServers_notNoReport() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()

            pump.push(mcpStatus("c1", "[]", dropped = 0, id = 1L))
            runCurrent()

            assertEquals(McpStatusReport(emptyList(), 0), readings.last().report)
        }

    @Test
    fun laterReport_replacesTheHeldOne_andACorrelatedReplyLandsTheSame() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            pump.push(mcpStatus("c1", SERVERS_TWO, dropped = 0, id = 1L))
            runCurrent()

            repo.requestMcpStatus("c1")
            val askId = pump.sent.single().id
            pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 2L, inReplyTo = askId))
            runCurrent()

            assertEquals(McpStatusReport(listOf(McpServerStatus("github", "pending", "", "user", "1.2.0")), 0), readings.last().report)
        }

    @Test
    fun aReportForAnotherConversation_leavesThisOneUnchangedAndUnemitted() =
        runTest {
            val (pump, repo) = repo()
            val c1 = collect(repo.observeMcpStatus("c1"))
            val c2 = collect(repo.observeMcpStatus("c2"))
            runCurrent()

            pump.push(mcpStatus("c2", SERVERS_ONE, dropped = 0, id = 1L))
            runCurrent()

            assertEquals(listOf(McpStatus()), c1)
            assertEquals(
                1,
                c2
                    .last()
                    .report
                    ?.servers
                    ?.size,
            )
        }

    @Test
    fun withoutInteractive_aFrameChangesNothing_andNoRequestIsSent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = RemoteConversationRepository(pump, backgroundScope)
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()

            pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 1L))
            runCurrent()
            repo.requestMcpStatus("c1")
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            runCurrent()

            assertEquals(listOf(McpStatus()), readings)
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun malformedFrames_areDropped_thePriorReportStands_andALaterValidFrameLands() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 1L))
            runCurrent()
            val held = readings.last()

            val malformed =
                listOf(
                    """{"conversation_id":"c1","dropped_servers":0}""",
                    """{"conversation_id":"c1","servers":null,"dropped_servers":0}""",
                    """{"conversation_id":"c1","servers":[]}""",
                    """{"conversation_id":"c1","servers":[],"dropped_servers":-1}""",
                    // Non-numeric text: a quoted number is coerced by the tree decoder (see ModelListPayloadsTest).
                    """{"conversation_id":"c1","servers":[],"dropped_servers":"many"}""",
                    """{"conversation_id":"c1","servers":[{"name":"a","status":"connected","error":"","scope":"user"}],""" +
                        """"dropped_servers":0}""",
                    """{"conversation_id":"c1","servers":[{"name":7,"status":"connected","error":"","scope":"user",""" +
                        """"version":""}],"dropped_servers":0}""",
                    """{"servers":[],"dropped_servers":0}""",
                )
            malformed.forEachIndexed { i, payload -> pump.push(probe("mcp_status", payload, id = 10L + i)) }
            runCurrent()
            assertEquals(held, readings.last())

            pump.push(mcpStatus("c1", "[]", dropped = 0, id = 30L))
            runCurrent()
            assertEquals(McpStatusReport(emptyList(), 0), readings.last().report)
        }

    // ---- request encoding -------------------------------------------------------------------------

    @Test
    fun theThreeVerbs_encodeTheirPayloads_withEnabledAlwaysPresent() =
        runTest {
            val (pump, repo) = repo()
            runCurrent()

            repo.requestMcpStatus("c1")
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = false)
            repo.toggleMcpServer("c1", "files", enabled = true)

            assertEquals(
                listOf("mcp_status_request", "mcp_reconnect", "mcp_toggle", "mcp_toggle"),
                pump.sent.map { it.type },
            )
            assertEquals(
                listOf(
                    json("""{"conversation_id":"c1"}"""),
                    json("""{"conversation_id":"c1","server_name":"github"}"""),
                    json("""{"conversation_id":"c1","server_name":"github","enabled":false}"""),
                    json("""{"conversation_id":"c1","server_name":"files","enabled":true}"""),
                ),
                pump.sent.map { it.payload },
            )
            assertEquals(
                4,
                pump.sent
                    .map { it.id }
                    .toSet()
                    .size,
            )
        }

    @Test
    fun aRefusedSend_setsNoFlag_andLeavesNoAskToCorrelate() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            pump.accept = false

            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            repo.requestMcpStatus("c1")
            runCurrent()
            assertEquals(listOf(McpStatus()), readings)

            pump.sent.forEachIndexed { i, ask -> pump.push(error(ask.id, MCP_ACTUATION_REFUSED, id = 10L + i)) }
            pump.push(error(pump.sent.last().id, "mcp_status.unavailable", id = 20L))
            runCurrent()
            assertEquals(listOf(McpStatus()), readings)
        }

    @Test
    fun aThrowingSend_setsNoFlag() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            pump.throwOnSend = true

            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            runCurrent()

            assertEquals(listOf(McpStatus()), readings)
        }

    @Test
    fun anEmptyConversationId_sendsNothing() =
        runTest {
            val (pump, repo) = repo()
            runCurrent()

            repo.requestMcpStatus("")
            repo.reconnectMcpServer("", "github")
            repo.toggleMcpServer("", "github", enabled = true)

            assertTrue(pump.sent.isEmpty())
        }

    // ---- flags ------------------------------------------------------------------------------------

    @Test
    fun reconnect_setsReconnecting_andAnyCorrelatedRefusal_endsItAsRefused_leavingTheReport() =
        runTest {
            for (code in listOf(MCP_ACTUATION_REFUSED, "protocol.malformed", "conversation.not_found")) {
                val (pump, repo) = repo()
                val readings = collect(repo.observeMcpStatus("c1"))
                runCurrent()
                pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 1L))
                runCurrent()
                val report = readings.last().report

                repo.reconnectMcpServer("c1", "github")
                runCurrent()
                assertEquals(McpStatus(report = report, reconnecting = true), readings.last())

                pump.push(error(pump.sent.single().id, code, id = 2L))
                runCurrent()
                assertEquals(McpStatus(report = report, reconnectRefused = true), readings.last())
            }
        }

    @Test
    fun toggle_setsToggling_andARefusal_endsItAsToggleRefused_neverAsReconnectRefused() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()

            repo.toggleMcpServer("c1", "github", enabled = true)
            runCurrent()
            assertEquals(McpStatus(toggling = true), readings.last())

            pump.push(error(pump.sent.single().id, MCP_ACTUATION_REFUSED, id = 1L))
            runCurrent()
            assertEquals(McpStatus(toggleRefused = true), readings.last())
        }

    @Test
    fun bothWaitsOutstanding_eachRefusalSettlesOnlyItsOwnVerb() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()

            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "files", enabled = false)
            val (reconnectId, toggleId) = pump.sent.map { it.id }
            runCurrent()

            pump.push(error(toggleId, MCP_ACTUATION_REFUSED, id = 1L))
            runCurrent()
            assertEquals(McpStatus(reconnecting = true, toggleRefused = true), readings.last())

            pump.push(error(reconnectId, MCP_ACTUATION_REFUSED, id = 2L))
            runCurrent()
            assertEquals(McpStatus(reconnectRefused = true, toggleRefused = true), readings.last())
        }

    @Test
    fun statusAsk_onlyUnavailableSetsUnavailable_andNoRefusalTouchesTheReport() =
        runTest {
            val cases =
                mapOf(
                    "mcp_status.unavailable" to true,
                    "protocol.malformed" to false,
                    "conversation.not_found" to false,
                )
            for ((code, expected) in cases) {
                val (pump, repo) = repo()
                val readings = collect(repo.observeMcpStatus("c1"))
                runCurrent()
                pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 1L))
                runCurrent()
                val report = readings.last().report

                repo.requestMcpStatus("c1")
                pump.push(error(pump.sent.single().id, code, id = 2L))
                runCurrent()

                assertEquals(McpStatus(report = report, unavailable = expected), readings.last())
            }
        }

    @Test
    fun aLaterReport_clearsAllFiveFlags() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()

            repo.requestMcpStatus("c1")
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            val (statusId, reconnectId, toggleId) = pump.sent.map { it.id }
            pump.push(error(statusId, "mcp_status.unavailable", id = 1L))
            pump.push(error(reconnectId, MCP_ACTUATION_REFUSED, id = 2L))
            pump.push(error(toggleId, MCP_ACTUATION_REFUSED, id = 3L))
            runCurrent()
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = false)
            runCurrent()
            assertEquals(
                McpStatus(unavailable = true, reconnecting = true, reconnectRefused = true, toggling = true, toggleRefused = true),
                readings.last(),
            )

            pump.push(mcpStatus("c1", SERVERS_ONE, dropped = 0, id = 4L))
            runCurrent()

            val last = readings.last()
            assertEquals(McpStatus(report = last.report), last)
            assertEquals(1, last.report?.servers?.size)
        }

    @Test
    fun endWaits_clearOnlyTheirWaitingFlag() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            pump.push(error(pump.sent[0].id, MCP_ACTUATION_REFUSED, id = 1L))
            pump.push(error(pump.sent[1].id, MCP_ACTUATION_REFUSED, id = 2L))
            runCurrent()
            repo.reconnectMcpServer("c1", "github")
            repo.toggleMcpServer("c1", "github", enabled = true)
            runCurrent()

            repo.endMcpReconnectWait("c1")
            runCurrent()
            assertEquals(McpStatus(reconnectRefused = true, toggling = true, toggleRefused = true), readings.last())

            repo.endMcpToggleWait("c1")
            runCurrent()
            assertEquals(McpStatus(reconnectRefused = true, toggleRefused = true), readings.last())
        }

    @Test
    fun anErrorMatchingNoAsk_changesNothing() =
        runTest {
            val (pump, repo) = repo()
            val readings = collect(repo.observeMcpStatus("c1"))
            runCurrent()
            repo.reconnectMcpServer("c1", "github")
            runCurrent()

            pump.push(error(pump.sent.single().id + 100, MCP_ACTUATION_REFUSED, id = 1L))
            runCurrent()

            assertEquals(McpStatus(reconnecting = true), readings.last())
        }

    @Test
    fun serverStatusToString_carriesNoClaudeText() {
        val text = McpServerStatus("secret-name", "weird-status", "error prose", "scope-x", "9.9").toString()
        for (field in listOf("secret-name", "weird-status", "error prose", "scope-x", "9.9")) {
            assertFalse(text.contains(field))
        }
        assertFalse(
            McpStatus(McpStatusReport(listOf(McpServerStatus("secret-name", "", "", "", "")), 0)).toString().contains("secret-name"),
        )
    }

    // ---- facade -----------------------------------------------------------------------------------

    @Test
    fun facade_delegatesTheReadingAndTheCommands_toTheLiveRepository() =
        runTest {
            val (pump, live) = repo()
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(live))
            val readings = collect(facade.observeMcpStatus("c1"))
            runCurrent()

            facade.requestMcpStatus("c1")
            facade.reconnectMcpServer("c1", "github")
            facade.toggleMcpServer("c1", "github", enabled = true)
            runCurrent()
            assertEquals(listOf("mcp_status_request", "mcp_reconnect", "mcp_toggle"), pump.sent.map { it.type })
            assertEquals(McpStatus(reconnecting = true, toggling = true), readings.last())

            facade.endMcpReconnectWait("c1")
            facade.endMcpToggleWait("c1")
            runCurrent()
            assertEquals(McpStatus(), readings.last())

            pump.push(mcpStatus("c1", "[]", dropped = 0, id = 1L))
            runCurrent()
            assertEquals(McpStatusReport(emptyList(), 0), readings.last().report)
        }

    @Test
    fun facade_withNoConnection_readsNoStatus_andTheCommandsNeitherThrowNorSend() =
        runTest {
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(null))
            val readings = collect(facade.observeMcpStatus("c1"))
            runCurrent()

            facade.requestMcpStatus("c1")
            facade.reconnectMcpServer("c1", "github")
            facade.toggleMcpServer("c1", "github", enabled = true)
            facade.endMcpReconnectWait("c1")
            facade.endMcpToggleWait("c1")
            runCurrent()

            assertEquals(listOf(McpStatus()), readings)
        }

    // ---- Fixtures ---------------------------------------------------------------------------------

    private fun TestScope.repo(): Pair<FakeSessionPump, RemoteConversationRepository> {
        val pump = FakeSessionPump()
        return pump to RemoteConversationRepository(pump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) })
    }

    private fun <T> TestScope.collect(flow: Flow<T>): MutableList<T> {
        val emissions = mutableListOf<T>()
        backgroundScope.launch { flow.collect { emissions += it } }
        return emissions
    }

    private fun mcpStatus(
        conversationId: String,
        servers: String,
        dropped: Int,
        id: Long,
        inReplyTo: Long? = null,
    ): Envelope =
        Envelope(
            id = id,
            type = "mcp_status",
            ts = TS,
            payload = json("""{"conversation_id":"$conversationId","servers":$servers,"dropped_servers":$dropped}"""),
            inReplyTo = inReplyTo,
        )

    private fun error(
        inReplyTo: Long,
        code: String,
        id: Long,
    ): Envelope =
        Envelope(
            id = id,
            type = "error",
            ts = TS,
            payload = json("""{"code":"$code","message":"refused","retryable":false}"""),
            inReplyTo = inReplyTo,
        )

    private fun probe(
        type: String,
        payload: String,
        id: Long,
    ): Envelope = Envelope(id = id, type = type, ts = TS, payload = json(payload))

    private fun json(text: String) = MobileJson.parseToJsonElement(text)

    /** Channel-backed fake of the inbound surface that records every send, and can refuse or throw on one. */
    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()
        var accept = true
        var throwOnSend = false

        override fun send(envelope: Envelope): Boolean {
            if (throwOnSend) throw IllegalStateException("pump closed")
            sent += envelope
            return accept
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    private companion object {
        const val TS = "2026-10-01T10:00:00Z"
        const val MCP_ACTUATION_REFUSED = "mcp_actuation.refused"
        const val SERVERS_ONE = """[{"name":"github","status":"pending","error":"","scope":"user","version":"1.2.0"}]"""
        const val SERVERS_TWO =
            """[{"name":"github","status":"connected","error":"","scope":"user","version":"1.2.0"},""" +
                """{"name":"files","status":"failed","error":"spawn failed","scope":"project","version":""}]"""
    }
}

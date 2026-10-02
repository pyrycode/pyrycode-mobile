package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatusReport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1345: which failed MCP server the thread's notice names, and the app-run memory of acknowledged ones. */
class McpFailureAcknowledgementsTest {
    private fun server(
        name: String,
        status: String,
    ) = McpServerStatus(name, status, "", "", "")

    private fun report(vararg servers: McpServerStatus) = McpStatusReport(servers.toList(), 0)

    @Test
    fun onlyExactlyFailed_isFailed() {
        assertTrue(isMcpServerFailed("failed"))
        listOf("Failed", "failed ", " failed", "pending", "connected", "needs-auth", "").forEach {
            assertFalse(it, isMcpServerFailed(it))
        }
    }

    @Test
    fun theFirstFailedServerInReportOrder_isSelected_builtInsIncluded() {
        val report = report(server("ok", "connected"), server("pyry_files", "failed"), server("github", "failed"))

        assertEquals("pyry_files", firstUnacknowledgedMcpFailure(report, emptySet()))
        assertEquals("github", firstUnacknowledgedMcpFailure(report, setOf("pyry_files")))
        assertNull(firstUnacknowledgedMcpFailure(report, setOf("pyry_files", "github")))
    }

    @Test
    fun noReport_orNoFailure_selectsNothing() {
        assertNull(firstUnacknowledgedMcpFailure(null, emptySet()))
        assertNull(firstUnacknowledgedMcpFailure(report(server("a", "Failed"), server("b", "pending")), emptySet()))
        assertEquals(emptySet<String>(), failedMcpServerNames(null))
    }

    @Test
    fun failedNames_areEveryExactlyFailedServer() {
        val report = report(server("a", "failed"), server("b", "connected"), server("c", "failed"))

        assertEquals(setOf("a", "c"), failedMcpServerNames(report))
    }

    @Test
    fun acknowledgements_areKeptPerHostAndPerConversation() =
        runTest {
            val acks = McpFailureAcknowledgements()

            acks.acknowledge("host-a", "c1", listOf("github"))

            assertEquals(setOf("github"), acks.observe("host-a", "c1").first())
            assertEquals(emptySet<String>(), acks.observe("host-a", "c2").first())
            assertEquals(emptySet<String>(), acks.observe("host-b", "c1").first())

            acks.acknowledge("host-a", "c1", listOf("linear"))
            assertEquals(setOf("github", "linear"), acks.observe("host-a", "c1").first())
        }

    @Test
    fun clearHost_dropsOnlyThatHost() =
        runTest {
            val acks = McpFailureAcknowledgements()
            acks.acknowledge("host-a", "c1", listOf("github"))
            acks.acknowledge("host-a", "c2", listOf("github"))
            acks.acknowledge("host-b", "c1", listOf("github"))

            acks.clearHost("host-a")

            assertEquals(emptySet<String>(), acks.observe("host-a", "c1").first())
            assertEquals(emptySet<String>(), acks.observe("host-a", "c2").first())
            assertEquals(setOf("github"), acks.observe("host-b", "c1").first())
        }

    @Test
    fun anEmptyAcknowledgement_changesNothing() {
        val acks = McpFailureAcknowledgements()

        acks.acknowledge("host-a", "c1", emptyList())

        assertEquals(emptyMap<String, Map<String, Set<String>>>(), acks.acknowledged.value)
    }

    @Test
    fun printingTheHolder_neverPrintsAName() {
        val acks = McpFailureAcknowledgements()
        acks.acknowledge("host-a", "c1", listOf("secret-server"))

        assertFalse("secret-server" in acks.toString())
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.McpServerStatus
import de.pyryco.mobile.data.repository.McpStatus
import de.pyryco.mobile.data.repository.McpStatusReport
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #1344: Channel info's MCP servers section against desktop's `McpServersSectionView` rules. */
@RunWith(AndroidJUnit4::class)
class ThreadScreenMcpServersTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun server(
        name: String,
        status: String = "connected",
        error: String = "",
    ) = McpServerStatus(name = name, status = status, error = error, scope = "user", version = "1")

    private fun reported(
        vararg servers: McpServerStatus,
        dropped: Int = 0,
    ) = McpStatus(report = McpStatusReport(servers.toList(), dropped))

    private fun state(
        mcp: McpStatus,
        capabilities: SessionCapabilities? = null,
        open: Boolean = true,
    ) = ThreadUiState(
        conversationId = "ch_abc123",
        displayName = "Test channel",
        isPromoted = true,
        channelInfoOpen = open,
        runConfig = ThreadRunConfig(capabilities = capabilities),
        mcpStatus = mcp,
    )

    private fun setContent(
        state: ThreadUiState,
        events: MutableList<ThreadEvent> = mutableListOf(),
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }
    }

    private fun text(value: String): SemanticsNodeInteraction = composeTestRule.onNodeWithText(value).performScrollTo()

    private fun switchFor(name: String): SemanticsNodeInteraction = composeTestRule.onNodeWithContentDescription(name).performScrollTo()

    private fun top(value: String): Float =
        composeTestRule
            .onNode(hasText(value) and hasAnyAncestor(isDialog()))
            .fetchSemanticsNode()
            .positionInRoot.y

    @Test
    fun section_showsAfterMemoryAndBeforeActions_whenTheCapabilityIsAbsentOrTrue() {
        setContent(state(reported(server("github")), SessionCapabilities(emptyList(), emptyList(), mcpServers = true)))

        text("MCP servers")
        assertTrue(top("Memory") < top("MCP servers"))
        assertTrue(top("MCP servers") < top("Actions"))
    }

    @Test
    fun section_isAbsent_whenTheCapabilityIsFalse() {
        setContent(state(reported(server("github")), SessionCapabilities(emptyList(), emptyList(), mcpServers = false)))

        composeTestRule.onNodeWithText("Memory").assertExists()
        composeTestRule.onNodeWithText("MCP servers").assertDoesNotExist()
        composeTestRule.onNodeWithText("github").assertDoesNotExist()
    }

    @Test
    fun rows_showNameAndStatus_reconnectOnlyWhenNotConnected_andSwitchOnUnlessDisabled() {
        setContent(state(reported(server("github"), server("linear", "failed"), server("sentry", "disabled"))))

        text("github")
        text("connected")
        text("failed")
        text("disabled")
        assertEquals(2, composeTestRule.onAllNodesWithText("Reconnect").fetchSemanticsNodes().size)
        switchFor("github").assertIsOn()
        switchFor("linear").assertIsOn()
        switchFor("sentry").assertIsOff()
    }

    @Test
    fun builtInServers_hiddenUntilShowBuiltIn_andTheTickResetsOnReopen() {
        var current by mutableStateOf(state(reported(server("github"), server("pyry_approve"), server("pyry_files"))))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = current,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = {},
                )
            }
        }

        text("github")
        composeTestRule.onNodeWithText("pyry_approve").assertDoesNotExist()
        switchFor("Show built-in").assertIsOff().performClick()
        text("pyry_approve")
        text("pyry_files")

        current = current.copy(channelInfoOpen = false)
        composeTestRule.waitForIdle()
        current = current.copy(channelInfoOpen = true)
        composeTestRule.waitForIdle()

        switchFor("Show built-in").assertIsOff()
        composeTestRule.onNodeWithText("pyry_approve").assertDoesNotExist()
    }

    @Test
    fun errorLine_showsOnlyWhenNotEmpty() {
        setContent(state(reported(server("github", "failed", "spawn ENOENT"), server("linear", "failed", ""))))

        text("spawn ENOENT")
        text("linear")
        assertEquals(1, composeTestRule.onAllNodesWithText("ENOENT", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun text_isCutAt256CodePoints_onlyWhenLonger() {
        val longName = "n".repeat(300)
        val exactStatus = "s".repeat(256)
        // 257 emoji, each a surrogate pair: the cut counts code points and never splits a pair.
        val emoji = "😀"
        setContent(state(reported(server(longName, exactStatus, emoji.repeat(257)))))

        text("n".repeat(256) + "…")
        text(exactStatus)
        text(emoji.repeat(256) + "…")
        composeTestRule.onNodeWithText(longName).assertDoesNotExist()
    }

    @Test
    fun partialList_namesTheDroppedCount() {
        setContent(state(reported(server("github"), dropped = 3)))

        text("Partial list: 3 more servers were left out by the daemon.")
    }

    @Test
    fun nullReport_saysNoReportYet_withoutShowBuiltIn() {
        setContent(state(McpStatus()))
        text("No MCP report has arrived yet.")
        composeTestRule.onNodeWithText("Show built-in").assertDoesNotExist()
    }

    @Test
    fun emptyReport_saysNoServers() {
        setContent(state(reported()))
        text("Claude reported no MCP servers.")
        text("Show built-in")
    }

    @Test
    fun onlyBuiltIns_saysSo_untilShown() {
        setContent(state(reported(server("pyry_approve"), server("pyry_files"))))
        text("Only built-in servers are reported.")

        switchFor("Show built-in").performClick()
        composeTestRule.onNodeWithText("Only built-in servers are reported.").assertDoesNotExist()
        text("pyry_approve")
    }

    @Test
    fun theThreeNotices_showTogether_withOrWithoutAReport() {
        val flags = McpStatus(unavailable = true, reconnectRefused = true, toggleRefused = true)
        setContent(state(flags))

        text("No MCP report has arrived yet.")
        text("The daemon could not report MCP status right now.")
        text("The daemon refused to reconnect the MCP server.")
        text("The daemon refused to change the MCP server.")
    }

    @Test
    fun aRefusalNotice_keepsTheHeldRows() {
        setContent(state(reported(server("github")).copy(toggleRefused = true)))

        text("github")
        text("The daemon refused to change the MCP server.")
        composeTestRule.onNodeWithText("The daemon refused to reconnect the MCP server.").assertDoesNotExist()
        composeTestRule.onNodeWithText("The daemon could not report MCP status right now.").assertDoesNotExist()
    }

    @Test
    fun reconnectAndSwitch_eachEmitOneEventWithTheRowsName() {
        val events = mutableListOf<ThreadEvent>()
        setContent(state(reported(server("github", "failed"), server("sentry", "disabled"))), events)

        // `disabled` is not `connected` either, so both rows offer Reconnect; the first is github's.
        composeTestRule.onAllNodesWithText("Reconnect")[0].performScrollTo().performClick()
        switchFor("github").performClick()
        switchFor("sentry").performClick()

        assertEquals(
            listOf(
                ThreadEvent.McpReconnect("github"),
                ThreadEvent.McpToggle("github", enabled = false),
                ThreadEvent.McpToggle("sentry", enabled = true),
            ),
            events.filter { it is ThreadEvent.McpReconnect || it is ThreadEvent.McpToggle },
        )
    }

    @Test
    fun whileReconnecting_everyControlIsDisabled() {
        assertAllControlsDisabled(reported(server("github", "failed"), server("linear", "pending")).copy(reconnecting = true))
    }

    @Test
    fun whileToggling_everyControlIsDisabled() {
        assertAllControlsDisabled(reported(server("github", "failed"), server("linear", "pending")).copy(toggling = true))
    }

    private fun assertAllControlsDisabled(mcp: McpStatus) {
        val events = mutableListOf<ThreadEvent>()
        setContent(state(mcp), events)

        val reconnects = composeTestRule.onAllNodesWithText("Reconnect")
        assertEquals(2, reconnects.fetchSemanticsNodes().size)
        reconnects[0].performScrollTo().assertIsNotEnabled()
        reconnects[1].performScrollTo().assertIsNotEnabled()
        switchFor("github").assertIsNotEnabled().performClick()
        switchFor("linear").assertIsNotEnabled()
        // The filter only changes what is shown, so it stays usable while a request is outstanding.
        switchFor("Show built-in").assertIsEnabled()
        assertEquals(emptyList<ThreadEvent>(), events.filter { it is ThreadEvent.McpReconnect || it is ThreadEvent.McpToggle })
    }
}

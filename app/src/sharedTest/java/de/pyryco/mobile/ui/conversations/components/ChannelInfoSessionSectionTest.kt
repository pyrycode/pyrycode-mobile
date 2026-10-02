package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1346: Channel info's Session section, desktop's: the agent's version and the permission mode Claude
 * reports, each cut at 256 code points and tagged when cut, and Claude's own cost estimate when it gave one.
 */
@RunWith(AndroidJUnit4::class)
class ChannelInfoSessionSectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun claudeChat_showsTheVersionAndReportedPermissionMode() {
        show(facts = SessionFacts("2.1.143", "acceptEdits", null))

        composeTestRule.onNodeWithText("Session").assertExists()
        composeTestRule.onNodeWithText("Claude version").assertExists()
        composeTestRule.onNodeWithText("2.1.143").assertExists()
        composeTestRule.onNodeWithText("Reported permission mode").assertExists()
        composeTestRule.onNodeWithText("acceptEdits").assertExists()
        composeTestRule.onAllNodesWithText("Truncated").assertCountEquals(0)
    }

    @Test
    fun codexChat_labelsTheVersionForCodex() {
        show(facts = SessionFacts("0.9.1", "", null), agent = ConversationAgent.Codex)

        composeTestRule.onNodeWithText("Codex version").assertExists()
        composeTestRule.onAllNodesWithText("Claude version").assertCountEquals(0)
    }

    @Test
    fun noFacts_readNotReported() {
        show(facts = null)

        composeTestRule.onAllNodesWithText("Not reported").assertCountEquals(2)
    }

    @Test
    fun anOnlyControlCharactersValue_readsNotReported() {
        show(facts = SessionFacts("\u001b\n\u202e", "default", null))

        composeTestRule.onAllNodesWithText("Not reported").assertCountEquals(1)
    }

    @Test
    fun daemonFlags_tagEachRowIndependently() {
        show(facts = SessionFacts("2.1", "plan", listOf("permission_mode")))
        composeTestRule.onAllNodesWithText("Truncated").assertCountEquals(1)

        show(facts = SessionFacts("2.1", "plan", listOf("claude_code_version", "permission_mode")))
        composeTestRule.onAllNodesWithText("Truncated").assertCountEquals(2)
    }

    @Test
    fun anOverlongValue_isCutAt256CodePointsAndTagged() {
        // 255 ASCII then an emoji: the 256th code point is a surrogate pair that must survive whole.
        val raw = "a".repeat(255) + "😀" + "b".repeat(40)
        show(facts = SessionFacts(raw, "default", null))

        val shown = textOf(CHANNEL_INFO_AGENT_VERSION_TAG)
        assertEquals("a".repeat(255) + "😀", shown)
        assertEquals(256, shown.codePointCount(0, shown.length))
        composeTestRule.onAllNodesWithText("Truncated").assertCountEquals(1)
    }

    @Test
    fun controlAndBidiCharacters_areRenderedInert() {
        show(facts = SessionFacts("2.1\u001b[31m\u202eevil", "default", null))

        val shown = textOf(CHANNEL_INFO_AGENT_VERSION_TAG)
        assertTrue(shown.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() })
        assertEquals("2.1 [31m evil", shown)
    }

    @Test
    fun aCost_showsClaudesEstimate() {
        show(facts = null, cost = 0.4236)

        composeTestRule.onNodeWithText("Cost (Claude's estimate)").assertExists()
        assertEquals("$0.42 est.", textOf(CHANNEL_INFO_SESSION_COST_TAG))
    }

    @Test
    fun noCost_leavesTheRowOut() {
        show(facts = SessionFacts("2.1", "default", null), cost = null)

        composeTestRule.onAllNodesWithText("Cost (Claude's estimate)").assertCountEquals(0)
    }

    @Test
    fun helpers_cutAndFormatLikeDesktop() {
        assertEquals(ReportedSessionValue(null, truncated = false), reportedSessionValue(null, flaggedTruncated = false))
        assertEquals(ReportedSessionValue(null, truncated = true), reportedSessionValue("  ", flaggedTruncated = true))
        assertEquals(ReportedSessionValue("x".repeat(256), truncated = true), reportedSessionValue("x".repeat(257), false))
        assertEquals(ReportedSessionValue("x".repeat(256), truncated = false), reportedSessionValue("x".repeat(256), false))
        assertEquals("$1234.50 est.", formatSessionCost(1234.5))
        assertEquals("$0.42 est.", formatSessionCost(0.42))
        assertNull(reportedSessionValue("\u0000", false).text)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private var model by mutableStateOf<ChannelInfoUiModel?>(null)

    private fun show(
        facts: SessionFacts?,
        agent: ConversationAgent = ConversationAgent.Claude,
        cost: Double? = null,
    ) {
        val next = baseModel.copy(agent = agent, sessionFacts = facts, sessionCostUsd = cost)
        if (model == null) {
            model = next
            composeTestRule.setContent {
                PyrycodeMobileTheme {
                    model?.let {
                        ChannelInfoSheetContent(
                            model = it,
                            onRename = {},
                            onArchive = {},
                            onDelete = {},
                            onInstallMemoryPlugin = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        } else {
            model = next
        }
        composeTestRule.waitForIdle()
    }

    private fun textOf(tag: String): String =
        composeTestRule
            .onNode(hasTestTag(tag))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }

    private val baseModel =
        ChannelInfoUiModel(
            conversationName = "kitchenclaw refactor",
            workspacePath = "~/Workspace/Projects/KitchenClaw",
            createdLabel = "3 weeks ago",
            lastActivityLabel = "2 hours ago",
            sessionCount = 1,
            messageCount = 2,
            memorySearch = MemorySearchReport.Unknown,
            channelId = "ch_1",
        )
}

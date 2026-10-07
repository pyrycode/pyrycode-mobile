package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MessageRowVerticalSpacing
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A background Agent run, scrolled to the newest end, must rest the same documented 12dp above the status
 * band as any other newest row (#1630's `ordinaryMessageRestAdjustment`). A still-running Agent call with no
 * visible children yet renders as a single [de.pyryco.mobile.ui.conversations.components.ToolCallRow] headline
 * ("Agent" plus the task's description, spinner while running) carrying a non-null `agentBlockId` — exactly
 * the row reported cut off under the composer's fade on release 4592. `ordinaryMessageRestAdjustment` gave
 * every such row an extra 12dp restAdjustment on top of its own 12dp trailing space
 * ([MessageRowVerticalSpacing]), double-subtracting from the list's reserved bottom padding and pulling the
 * row ~13dp past the band. A plain top-level tool row (no agent block) already rested correctly with zero
 * adjustment, because its own trailing space already equals the target gap — proving the "nested tool row"
 * branch, not a missing case, was the bug. Native graphics, so wrap width and row height measure for real.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class BackgroundAgentRestGapTest {
    @get:Rule val rule = createComposeRule()
    private val ts = Instant.parse("2026-10-07T10:00:00Z")
    private var taskCount by mutableIntStateOf(0)

    // Padding so the thread overflows the viewport and the newest row actually rests against the composer,
    // as a short thread's top-aligned stream (#1509) would not.
    private fun filler(count: Int): List<ThreadItem> =
        (1..count).map { n ->
            ThreadItem.MessageItem(Message("filler$n", "s", Role.Assistant, "Filler message number $n with enough text to wrap", ts, false))
        }

    private val agentCall =
        ThreadItem.MessageItem(
            Message(
                "a",
                "s",
                Role.Tool,
                "",
                ts,
                false,
                toolCall = ToolCall("Agent", "", "", inputFields = mapOf("run_in_background" to "true")),
            ),
        )
    private val lifecycle = ThreadItem.BackgroundTaskLifecycle("task", ts, "a", "Agent", "local_agent")
    private val child = ThreadItem.MessageItem(Message("child", "s", Role.Assistant, "child", ts, false, parentToolUseId = "a"))

    private fun mount(
        items: List<ThreadItem>,
        collapseToolUses: Boolean = true,
    ) {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    ThreadScreen(
                        state = ThreadUiState("c", "Thread", hasMessages = true, items = items, backgroundTaskCount = taskCount),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        collapseToolUses = collapseToolUses,
                    )
                }
            }
        }
    }

    /**
     * The documented #1630 rest gap: the newest row's visible surface to the status band's top edge.
     *
     * [ownTrailingSpaceIncluded] is the row's own trailing space the queried [row]'s bounds already fold
     * in. `ThreadScreen` tags a background-Agent block's root tool call ("background-agent:$id") on the
     * outer, unpadded modifier it hands into [de.pyryco.mobile.ui.conversations.components.ToolCallRow] —
     * before that row's own `fillMaxWidth()` and trailing [MessageRowVerticalSpacing] are chained on — so
     * that tag's reported bounds span the row's full outer box, padding included, unlike `message-bubble`
     * or `tool-run-row`, which tag the inner visible surface and exclude it. Either tag proves the same
     * 12dp visible gap; only the number this assertion is checked against shifts by the amount the tag's
     * own bounds already account for.
     */
    private fun assertRestGap(
        row: SemanticsNodeInteraction,
        ownTrailingSpaceIncluded: Dp = 0.dp,
    ) {
        rule.waitForIdle()
        val band = rule.onNodeWithTag("thread-status-band").getUnclippedBoundsInRoot()
        val bounds = row.getUnclippedBoundsInRoot()
        val target = 12.dp - ownTrailingSpaceIncluded
        assertEquals("visible row to status band", target.value, (band.top - bounds.bottom).value, 1.5f)
    }

    @Test fun ordinary_message_rests_the_documented_gap_above_the_band() {
        mount(filler(30))
        assertRestGap(rule.onNode(hasTestTag("message-bubble") and hasAnyDescendant(hasText("Filler message number 30", substring = true))))
    }

    @Test fun closed_running_agent_run_block_rests_the_documented_gap_above_the_band() {
        // The exact release-4592 repro: a running background Agent with no children loaded yet draws as one
        // un-folded "Agent" + description headline, not a multi-row run header.
        mount(filler(30) + agentCall + lifecycle)
        assertRestGap(rule.onNodeWithTag("background-agent:a"), ownTrailingSpaceIncluded = MessageRowVerticalSpacing)
    }

    @Test fun closed_running_agent_run_block_gap_is_unaffected_by_the_running_tasks_pill() {
        taskCount = 2
        mount(filler(30) + agentCall + lifecycle)
        rule.onNodeWithText("2 tasks running").assertExists()
        assertRestGap(rule.onNodeWithTag("background-agent:a"), ownTrailingSpaceIncluded = MessageRowVerticalSpacing)
    }

    @Test fun closed_agent_run_header_rests_the_documented_gap_above_the_band() {
        // Once a child row joins the block, the pair folds into one closed ThreadRow.ToolRun header.
        mount(filler(30) + agentCall + lifecycle + child)
        rule.onNodeWithText("Using tools: 1", substring = true).assertExists()
        assertRestGap(rule.onNodeWithTag("tool-run-row"))
    }

    @Test fun closed_agent_run_header_gap_is_unaffected_by_the_running_tasks_pill() {
        taskCount = 1
        mount(filler(30) + agentCall + lifecycle + child)
        rule.onNodeWithText("1 task running").assertExists()
        assertRestGap(rule.onNodeWithTag("tool-run-row"))
    }

    @Test fun open_agent_run_block_rests_the_documented_gap_above_the_band() {
        mount(filler(30) + agentCall + lifecycle + child)
        rule.onNodeWithTag("tool-run:a").performClick()
        assertRestGap(rule.onNode(hasTestTag("message-bubble") and hasAnyDescendant(hasText("child"))))
    }
}

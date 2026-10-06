package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTaskStopPanelTest {
    @get:Rule val rule = createComposeRule()
    private var expanded by mutableStateOf(emptySet<String>())
    private var pending by mutableStateOf(emptySet<String>())
    private val events = mutableListOf<ThreadEvent>()

    private fun setPanel(
        supported: Boolean = true,
        finished: Boolean = false,
    ) {
        val tasks = listOf(task("a", "first", finished), task("b", "second", finished))
        rule.setContent {
            PyrycodeMobileTheme {
                BackgroundTaskPanel(
                    roster = BackgroundTaskRoster(tasks, 0),
                    onDismiss = {},
                    stopSupported = supported,
                    expandedTaskIds = expanded,
                    pendingTaskIds = pending,
                    onEvent = { event ->
                        events += event
                        when (event) {
                            is ThreadEvent.BackgroundTaskToggle ->
                                expanded =
                                    if (event.taskId in expanded) expanded - event.taskId else expanded + event.taskId
                            is ThreadEvent.BackgroundTaskStop -> pending = pending + event.taskId
                            else -> Unit
                        }
                    },
                )
            }
        }
    }

    private fun row(text: String) = rule.onNode(hasText(text) and hasClickAction())

    @Test
    fun closedOpenRowsKeepAllText_andButtonDoesNotToggle() {
        setPanel()
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(0)
        row("first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        row("first").performTouchInput { click() }
        row("first").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        rule.onNodeWithText("Stop task").assertIsEnabled().performTouchInput { click() }
        rule.onNodeWithText("Stop task").assertIsNotEnabled()
        assertEquals(setOf("a"), expanded)
        assertEquals(listOf(ThreadEvent.BackgroundTaskToggle("a"), ThreadEvent.BackgroundTaskStop("a")), events)
        // A tap at the button's expanded lower edge must belong only to the button.
        rule.onNodeWithText("Stop task").performTouchInput { click(Offset(center.x, height.toFloat() + 4f)) }
        assertEquals(setOf("a"), expanded)
        row("first").performClick()
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(0)
        row("first").performClick()
        rule.onNodeWithText("Stop task").assertIsNotEnabled()
        assertEquals(setOf("a"), pending)
        val merged = row("first").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue(merged.contains("Command") && merged.contains("Running") && merged.contains("progress") && merged.contains("patch"))
        assertTrue("Stop task" !in merged)
    }

    @Test
    fun chevronHasCloseControlVisibleSizeAndTagGap_andCardPaddingTogglesOnly() {
        setPanel()
        val icon =
            rule
                .onAllNodes(hasTestTag("background-task-toggle"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first()
                .boundsInRoot
        val tag =
            rule
                .onAllNodes(hasText("Running"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .first()
                .boundsInRoot
        assertEquals(28f, icon.width, 0.1f)
        assertEquals(28f, icon.height, 0.1f)
        // The pill adds its own 10dp end padding after the label.
        assertEquals(18f, icon.left - tag.right, 0.1f)
        row("first").performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(setOf("a"), expanded)
        assertFalse(events.any { it is ThreadEvent.BackgroundTaskStop })
    }

    @Test
    fun rowsOpenIndependently() {
        setPanel()
        row("first").performClick()
        row("second").performClick()
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(2)
        row("first").performClick()
        assertEquals(setOf("b"), expanded)
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(1)
    }

    @Test
    fun unsupportedRowsHaveNoActionOrExpandedState() {
        expanded = setOf("a")
        setPanel(supported = false)
        rule.onNodeWithText("first").assertHasNoClickAction()
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(0)
        assertTrue(
            rule
                .onNodeWithText("first")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.StateDescription) == null,
        )
    }

    @Test
    fun finishedRowsKeepSummaryAndHaveNoAction() {
        expanded = setOf("a")
        setPanel(finished = true)
        rule.onNodeWithText("first").assertHasNoClickAction()
        rule.onAllNodes(hasText("Stop task")).assertCountEquals(0)
        rule.onAllNodes(hasText("Stopped")).assertCountEquals(2)
        rule.onNodeWithText("first").assertIsDisplayed()
    }

    private fun task(
        id: String,
        description: String,
        finished: Boolean,
    ) = BackgroundTask(
        id,
        "opaque-tool",
        "local_bash",
        description,
        null,
        BackgroundTaskUpdate("patch", "", "", null),
        if (finished) BackgroundTaskUpdate("", "stopped", "summary", null) else null,
        finished,
        de.pyryco.mobile.data.model
            .BackgroundTaskProgress("progress", "", "Bash", 1, 1, 1, null),
    )
}

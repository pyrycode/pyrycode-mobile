package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Actions menu's background-task row and its read-only panel (#678). The menu path is hosted on
 * [ThreadScreen]; the panel's readings are composed directly.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundTaskPanelTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val overflowEvents = mutableListOf<ThreadEvent>()
    private val composerCommands = mutableListOf<ComposerAction>()
    private var panelOpen by mutableStateOf(true)

    private fun setThread(
        roster: BackgroundTaskRoster?,
        count: Int,
    ) {
        val state =
            ThreadUiState(
                conversationId = "c1",
                displayName = "Test channel",
                isPromoted = true,
                hasMessages = false,
                backgroundTasks = roster,
                backgroundTaskCount = count,
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { overflowEvents += it },
                    onComposerCommand = { composerCommands += it },
                )
            }
        }
    }

    private fun setPanel(roster: BackgroundTaskRoster?) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                if (panelOpen) BackgroundTaskPanel(roster = roster, onDismiss = { panelOpen = false })
            }
        }
    }

    private fun button(label: String) = composeTestRule.onNode(hasText(label) and hasClickAction() and !isSelectable())

    private fun openPanelFromMenu(count: Int) {
        button("Actions").performClick()
        button("Background tasks ($count)").performClick()
    }

    private fun task(
        id: String = "t1",
        description: String = "sleep 300",
        taskType: String = "local_bash",
        truncatedFields: List<String>? = null,
        latestUpdate: BackgroundTaskUpdate? = null,
        finish: BackgroundTaskUpdate? = null,
        isFinished: Boolean = false,
    ) = BackgroundTask(id, "toolu_$id", taskType, description, truncatedFields, latestUpdate, finish, isFinished)

    private fun midLife(
        patch: String,
        truncatedFields: List<String>? = null,
    ) = BackgroundTaskUpdate(patch = patch, status = "", summary = "", truncatedFields = truncatedFields)

    private fun terminal(
        summary: String,
        truncatedFields: List<String>? = null,
    ) = BackgroundTaskUpdate(patch = "", status = "completed", summary = summary, truncatedFields = truncatedFields)

    private fun roster(
        vararg tasks: BackgroundTask,
        dropped: Int = 0,
    ) = BackgroundTaskRoster(tasks.toList(), dropped)

    // Unmerged: a task row merges its fields for a screen reader, so two markers share one merged node.
    private fun markers() = composeTestRule.onAllNodesWithText(TRUNCATED, useUnmergedTree = true)

    // AC#1: the row shows the state's live count, and opens the panel.
    @Test
    fun actionsRow_showsTheLiveCount_andOpensThePanel() {
        setThread(roster = roster(task(), task(id = "t2", isFinished = true)), count = 1)

        openPanelFromMenu(count = 1)

        composeTestRule.onNodeWithText("Background tasks").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("sleep 300").assertCountEquals(2)
    }

    // AC#1: a host with nothing reported shows 0.
    @Test
    fun actionsRow_withNoReport_showsZero() {
        setThread(roster = null, count = 0)

        openPanelFromMenu(count = 0)

        composeTestRule.onNodeWithText(UNREPORTED).assertIsDisplayed()
    }

    // AC#3: the footer's Close and the close glyph each dismiss, and neither sends anything.
    @Test
    fun closing_sendsNothing() {
        setThread(roster = roster(task()), count = 1)

        openPanelFromMenu(count = 1)
        button("Close").performClick()
        composeTestRule.onNodeWithText(UNREPORTED).assertDoesNotExist()
        composeTestRule.onNodeWithText("sleep 300").assertDoesNotExist()

        openPanelFromMenu(count = 1)
        composeTestRule.onNodeWithContentDescription("Close").performClick()
        composeTestRule.onNodeWithText("sleep 300").assertDoesNotExist()

        assertTrue(overflowEvents.isEmpty())
        assertTrue(composerCommands.isEmpty())
    }

    // AC#2: three readings, three sentences, never each other's.
    @Test
    fun noReport_andEmptyRoster_readDifferently() {
        setPanel(roster = null)
        composeTestRule.onNodeWithText(UNREPORTED).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY).assertDoesNotExist()
    }

    @Test
    fun emptyRoster_saysNothingIsAlive() {
        setPanel(roster = roster())
        composeTestRule.onNodeWithText(EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(UNREPORTED).assertDoesNotExist()
        composeTestRule.onNodeWithText(PARTIAL_PREFIX, substring = true).assertDoesNotExist()
    }

    @Test
    fun populatedRoster_listsEachTaskWithItsType() {
        setPanel(
            roster = roster(task(description = "npm run dev"), task(id = "t2", description = "tail -f log", taskType = "remote_agent")),
        )

        composeTestRule.onNodeWithText("npm run dev").assertIsDisplayed()
        composeTestRule.onNodeWithText("tail -f log").assertIsDisplayed()
        composeTestRule.onNodeWithText("remote_agent").assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY).assertDoesNotExist()
        composeTestRule.onNodeWithText(UNREPORTED).assertDoesNotExist()
        markers().assertCountEquals(0)
    }

    // AC#2: the dropped notice belongs to the roster, so it shows on an empty task list too.
    @Test
    fun droppedTasks_showThePartialNotice_onAPopulatedAndAnEmptyList() {
        setPanel(roster = roster(task(), dropped = 3))
        composeTestRule.onNodeWithText("Partial list (3 not shown)").assertIsDisplayed()
    }

    @Test
    fun droppedTasks_onAnEmptyList_stillShowTheNotice() {
        setPanel(roster = roster(dropped = 2))
        composeTestRule.onNodeWithText("Partial list (2 not shown)").assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY).assertIsDisplayed()
    }

    // AC#2: each cut field is marked from its own list, by its wire name.
    @Test
    fun truncatedTaskFields_areMarkedByWireName() {
        setPanel(roster = roster(task(truncatedFields = listOf("description", "task_type"))))
        markers().assertCountEquals(2)
    }

    @Test
    fun heldPropertyName_marksNothing() {
        setPanel(roster = roster(task(truncatedFields = listOf("taskType"))))
        markers().assertCountEquals(0)
    }

    @Test
    fun truncatedPatch_isMarkedFromTheUpdatesOwnList() {
        setPanel(roster = roster(task(latestUpdate = midLife("""{"is_backgrounded":true""", truncatedFields = listOf("patch")))))

        composeTestRule.onNodeWithText("""{"is_backgrounded":true""").assertIsDisplayed()
        markers().assertCountEquals(1)
    }

    @Test
    fun theTasksListNamingPatch_doesNotMarkThePatch() {
        setPanel(
            roster = roster(task(truncatedFields = listOf("patch", "summary"), latestUpdate = midLife("x"), finish = terminal("done"))),
        )
        markers().assertCountEquals(0)
    }

    @Test
    fun truncatedSummary_isMarkedFromTheFinishsOwnList() {
        setPanel(roster = roster(task(finish = terminal("sleep 300 && echo", truncatedFields = listOf("summary")), isFinished = true)))

        composeTestRule.onNodeWithText("sleep 300 && echo").assertIsDisplayed()
        markers().assertCountEquals(1)
    }

    // The latest reported update: an empty patch is a value, not an absence.
    @Test
    fun emptyPatch_readsNoChangeReported_andNoUpdateReadsNothing() {
        setPanel(roster = roster(task(latestUpdate = midLife("")), task(id = "t2", description = "other")))
        composeTestRule.onAllNodesWithText(NO_CHANGE).assertCountEquals(1)
    }

    // AC#2: a finished task stays listed and is labelled finished, with or without its terminal frame.
    @Test
    fun finishedTask_isLabelledFinished() {
        setPanel(
            roster =
                roster(
                    task(finish = terminal("sleep 300"), isFinished = true),
                    task(id = "t2", description = "reconnected", isFinished = true),
                    task(id = "t3", description = "running"),
                ),
        )
        composeTestRule.onAllNodesWithText(FINISHED).assertCountEquals(2)
    }

    // AC#3: daemon text is plain inert Text — nothing on it is clickable.
    @Test
    fun daemonText_isNotClickable() {
        setPanel(
            roster =
                roster(
                    task(description = "rm -rf /tmp/x", latestUpdate = midLife("{}"), finish = terminal("summary text"), isFinished = true),
                ),
        )

        listOf("rm -rf /tmp/x", "local_bash", "{}", "summary text").forEach {
            composeTestRule.onNodeWithText(it, useUnmergedTree = true).assertHasNoClickAction()
        }
    }

    // The render bound: control characters are dropped without claiming a cut; an over-long field is cut
    // and marked.
    @Test
    fun controlCharacters_areDropped_withoutAMarker() {
        setPanel(roster = roster(task(description = "echo \u001B[31mred")))

        composeTestRule.onNodeWithText("echo [31mred", useUnmergedTree = true).assertIsDisplayed()
        markers().assertCountEquals(0)
    }

    @Test
    fun overLongField_isBounded_andMarked() {
        setPanel(roster = roster(task(description = "x".repeat(5000))))

        composeTestRule.onNodeWithText("x".repeat(4096), useUnmergedTree = true).assertExists()
        markers().assertCountEquals(1)
    }

    private companion object {
        const val UNREPORTED = "No background-task report yet"
        const val EMPTY = "No background tasks"
        const val PARTIAL_PREFIX = "Partial list"
        const val TRUNCATED = "Truncated by the daemon"
        const val NO_CHANGE = "No change reported"
        const val FINISHED = "Finished"
    }
}

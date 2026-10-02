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
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
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
        progress: BackgroundTaskProgress? = null,
    ) = BackgroundTask(id, "toolu_$id", taskType, description, truncatedFields, latestUpdate, finish, isFinished, progress)

    private fun progress(
        activity: String = "Running go test with the race detector",
        lastToolName: String = "Bash",
        truncatedFields: List<String>? = null,
    ) = BackgroundTaskProgress(
        description = activity,
        subagentType = "general-purpose",
        lastToolName = lastToolName,
        totalTokens = 18_000,
        toolUses = 4,
        durationMs = 161_000,
        truncatedFields = truncatedFields,
    )

    private fun top(text: String) =
        composeTestRule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun midLife(
        patch: String,
        truncatedFields: List<String>? = null,
    ) = BackgroundTaskUpdate(patch = patch, status = "", summary = "", truncatedFields = truncatedFields)

    private fun terminal(
        summary: String,
        truncatedFields: List<String>? = null,
    ) = BackgroundTaskUpdate(patch = "", status = "completed", summary = summary, truncatedFields = truncatedFields)

    private fun terminalWith(status: String) = BackgroundTaskUpdate(patch = "", status = status, summary = "", truncatedFields = null)

    // Unmerged: a row merges its fields, so a tag's label is found as its own node.
    private fun tag(label: String) = composeTestRule.onAllNodesWithText(label, useUnmergedTree = true)

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

    // AC#3: Back and the close glyph each dismiss, and neither sends anything. #1496: no footer Close.
    @Test
    fun closing_sendsNothing() {
        setThread(roster = roster(task()), count = 1)

        openPanelFromMenu(count = 1)
        button("Close").assertDoesNotExist()
        Espresso.pressBack()
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
        composeTestRule.onNodeWithText(UNREPORTED_SUPPORT).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY).assertDoesNotExist()
        composeTestRule.onNodeWithText(EMPTY_SUPPORT).assertDoesNotExist()
    }

    @Test
    fun emptyRoster_saysNothingIsAlive() {
        setPanel(roster = roster())
        composeTestRule.onNodeWithText(EMPTY).assertIsDisplayed()
        composeTestRule.onNodeWithText(EMPTY_SUPPORT).assertIsDisplayed()
        composeTestRule.onNodeWithText(UNREPORTED).assertDoesNotExist()
        composeTestRule.onNodeWithText(UNREPORTED_SUPPORT).assertDoesNotExist()
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

    // #1041 AC#1: two groups in claude's order, each with its count; an empty group is not drawn.
    @Test
    fun tasks_splitIntoRunningAndFinishedGroups_withCounts() {
        setPanel(
            roster =
                roster(
                    task(id = "t1", description = "a"),
                    task(id = "t2", description = "b", finish = terminal("done"), isFinished = true),
                    task(id = "t3", description = "c"),
                ),
        )

        composeTestRule.onNodeWithText("Running · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finished · 1").assertIsDisplayed()
    }

    @Test
    fun anEmptyGroup_isNotDrawn() {
        setPanel(roster = roster(task()))
        composeTestRule.onNodeWithText("Running · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finished", substring = true).assertDoesNotExist()
    }

    @Test
    fun finishedOnly_drawsNoRunningGroup() {
        setPanel(roster = roster(task(isFinished = true)))
        composeTestRule.onNodeWithText("Finished · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Running", substring = true).assertDoesNotExist()
    }

    @Test
    fun droppedTasks_makeTheCountsReadShown() {
        setPanel(roster = roster(task(), task(id = "t2", isFinished = true), dropped = 3))
        composeTestRule.onNodeWithText("Running · 1 shown").assertIsDisplayed()
        composeTestRule.onNodeWithText("Finished · 1 shown").assertIsDisplayed()
    }

    // #1041 AC#2: each row's status tag.
    @Test
    fun statusTags_followTheMapping() {
        setPanel(
            roster =
                roster(
                    task(id = "t1", description = "running task"),
                    task(id = "t2", finish = terminalWith("completed"), isFinished = true),
                    task(id = "t3", finish = terminalWith("failed"), isFinished = true),
                    task(id = "t4", finish = terminalWith("stopped"), isFinished = true),
                ),
        )

        tag("Running").assertCountEquals(1)
        tag("Completed").assertCountEquals(1)
        tag("Failed").assertCountEquals(1)
        tag("Stopped").assertCountEquals(1)
    }

    @Test
    fun unknownStatus_readsItsRawWord_inertly() {
        setPanel(roster = roster(task(finish = terminalWith("cancelled"), isFinished = true)))

        tag("cancelled").assertCountEquals(1)
        composeTestRule.onNodeWithText("cancelled", useUnmergedTree = true).assertHasNoClickAction()
        tag("Running").assertCountEquals(0)
    }

    @Test
    fun unknownStatus_ofControlCharactersOnly_readsFinished() {
        setPanel(roster = roster(task(finish = terminalWith("\u0007\u001B"), isFinished = true)))
        tag(FINISHED).assertCountEquals(1)
    }

    // A terminal status never reads Running, even when claude's own word is "running".
    @Test
    fun unknownStatus_spellingRunning_readsFinished() {
        setPanel(roster = roster(task(finish = terminalWith(" Running "), isFinished = true)))

        tag(FINISHED).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Running", substring = true, ignoreCase = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun overLongUnknownStatus_isBoundedToOneLine() {
        setPanel(roster = roster(task(finish = terminalWith("w".repeat(5000)), isFinished = true)))
        tag("w".repeat(4096)).assertCountEquals(1)
        composeTestRule.onNodeWithText("sleep 300", useUnmergedTree = true).assertIsDisplayed()
    }

    // The reconnect case: finished, with no terminal frame carried over.
    @Test
    fun finishedTask_withoutItsTerminalFrame_readsFinished() {
        setPanel(
            roster =
                roster(
                    task(id = "t1", description = "reconnected", isFinished = true),
                    task(id = "t2", description = "running"),
                ),
        )
        tag(FINISHED).assertCountEquals(1)
        tag("Running").assertCountEquals(1)
    }

    // #1041 AC#3: the patch sits under "Latest update"; a never-updated task shows no block.
    @Test
    fun latestUpdate_isLabelled_andNeverUpdatedShowsNoBlock() {
        setPanel(roster = roster(task(latestUpdate = midLife("""{"x":1}""")), task(id = "t2", description = "other")))

        composeTestRule.onAllNodesWithText(LATEST_UPDATE, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithText("""{"x":1}""", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyPatch_readsNoChangeReported_underTheLabel() {
        setPanel(roster = roster(task(latestUpdate = midLife(""))))
        composeTestRule.onAllNodesWithText(LATEST_UPDATE, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(NO_CHANGE, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun neverUpdatedTask_showsNoLatestUpdate() {
        setPanel(roster = roster(task()))
        composeTestRule.onAllNodesWithText(LATEST_UPDATE, useUnmergedTree = true).assertCountEquals(0)
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

        listOf("rm -rf /tmp/x", "local_bash", "{}", "summary text", "Completed").forEach {
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

    // #1044 AC#1: activity then meta, under the description and above "Latest update".
    @Test
    fun runningTaskWithProgress_showsActivityThenMeta_underTheDescription() {
        setPanel(roster = roster(task(description = "go test ./...", latestUpdate = midLife("{}"), progress = progress())))

        composeTestRule.onNodeWithText(ACTIVITY, useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(META, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(top("go test ./...") < top(ACTIVITY))
        assertTrue(top(ACTIVITY) < top(META))
        assertTrue(top(META) < top(LATEST_UPDATE))
        composeTestRule.onAllNodesWithText("general-purpose", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun runningTaskWithoutProgress_showsNoProgressBlock() {
        setPanel(roster = roster(task()))
        composeTestRule.onAllNodesWithText("tokens", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // #1044 AC#2: a finished task never shows progress, even if the held frame is non-null.
    @Test
    fun finishedTask_showsNoProgressBlock_evenWithProgressHeld() {
        setPanel(roster = roster(task(finish = terminal("done"), isFinished = true, progress = progress())))

        composeTestRule.onNodeWithText(ACTIVITY, useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onAllNodesWithText("tokens", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    // #1044 AC#3: the progress frame's own list marks the activity.
    @Test
    fun truncatedActivity_isMarkedFromTheProgressFramesOwnList() {
        setPanel(roster = roster(task(progress = progress(truncatedFields = listOf("description")))))

        markers().assertCountEquals(1)
        assertTrue(top(ACTIVITY) < top(TRUNCATED))
        assertTrue(top(TRUNCATED) < top(META))
    }

    @Test
    fun truncatedToolName_isMarked() {
        setPanel(roster = roster(task(progress = progress(truncatedFields = listOf("last_tool_name")))))
        markers().assertCountEquals(1)
    }

    @Test
    fun theTasksListNamingDescription_doesNotMarkTheActivity() {
        setPanel(roster = roster(task(truncatedFields = listOf("description"), progress = progress())))
        markers().assertCountEquals(1)
        assertTrue(top(TRUNCATED) < top(ACTIVITY))
    }

    @Test
    fun emptyToolName_dropsItsSegment() {
        setPanel(roster = roster(task(progress = progress(lastToolName = ""))))
        composeTestRule.onNodeWithText("4 tools · 18k tokens · 2m 41s", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun progressText_isInert_andStrippedOfControlCharacters() {
        setPanel(roster = roster(task(progress = progress(activity = "Reading \u001B[31ma.go", lastToolName = "Ba\u0007sh"))))

        composeTestRule.onNodeWithText("Reading [31ma.go", useUnmergedTree = true).assertHasNoClickAction()
        composeTestRule.onNodeWithText(META, useUnmergedTree = true).assertHasNoClickAction()
        markers().assertCountEquals(0)
    }

    @Test
    fun overLongActivity_isBounded_andMarked() {
        setPanel(roster = roster(task(progress = progress(activity = "a".repeat(5000)))))

        composeTestRule.onNodeWithText("a".repeat(4096), useUnmergedTree = true).assertExists()
        markers().assertCountEquals(1)
    }

    private companion object {
        const val ACTIVITY = "Running go test with the race detector"
        const val META = "Bash · 4 tools · 18k tokens · 2m 41s"

        const val UNREPORTED = "No background-task report yet"
        const val EMPTY = "No background tasks"
        const val PARTIAL_PREFIX = "Partial list"
        const val TRUNCATED = "Truncated by the daemon"
        const val NO_CHANGE = "No change reported"
        const val FINISHED = "Finished"
        const val LATEST_UPDATE = "Latest update"
        const val EMPTY_SUPPORT = "Claude has nothing running in the background for this conversation."
        const val UNREPORTED_SUPPORT = "The daemon has not reported on this conversation since the app connected."
    }
}

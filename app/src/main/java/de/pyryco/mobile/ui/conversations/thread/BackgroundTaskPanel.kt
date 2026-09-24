package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.ui.components.MobileReadOnlyModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

// The wire names a `truncated_fields` list carries, matched and never rendered. The task's list names
// `description` / `task_type` (held as `taskType`); an update's own list names `patch` / `summary`. The
// two lists are never merged or read for each other.
private const val CUT_DESCRIPTION = "description"
private const val CUT_TASK_TYPE = "task_type"
private const val CUT_PATCH = "patch"
private const val CUT_SUMMARY = "summary"

/** The render bound on one daemon-authored field. The daemon's own caps are byte caps below this. */
private const val MAX_PANEL_TEXT_CHARS = 4096

private val TaskRowGap = 2.dp
private val TaskListGap = 16.dp

/**
 * The read-only background-task list (#678), after desktop's `BackgroundTaskPanelView`, in the shared
 * mobile modal shell with only a Close action. Closing sends nothing and changes nothing.
 *
 * [roster] is branched on before its tasks are read: `null` means nothing has been reported, an empty
 * roster is the daemon saying nothing is alive, and the two read as different sentences. The partial-list
 * notice belongs to the roster, so it shows whichever way the list branches.
 *
 * Every task field is claude-authored (a `local_bash` description and a terminal summary are literal
 * command lines). Each reaches a plain [Text], stripped of control characters and bounded, and nothing else: no link, click,
 * clipboard, parse, `key()`, test tag or log. A finished task stays listed and is labelled, so the list
 * agrees with the menu's live count, which excludes it.
 */
@Composable
internal fun BackgroundTaskPanel(
    roster: BackgroundTaskRoster?,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val reading =
            when {
                roster == null -> "unreported"
                roster.tasks.isEmpty() -> "empty"
                else -> "listed"
            }
        if (BuildConfig.DEBUG) {
            Log.d(
                "BackgroundTaskPanel",
                "event=background_tasks_panel_opened reading=$reading tasks=${roster?.tasks?.size ?: 0} " +
                    "dropped=${roster?.droppedTasks ?: 0}",
            )
        }
    }
    MobileReadOnlyModal(
        title = stringResource(R.string.background_tasks_title),
        closeLabel = stringResource(R.string.background_tasks_close),
        onDismissRequest = onDismiss,
    ) {
        // `> 0`, so a nonsense negative count shows no notice rather than a negative one.
        if (roster != null && roster.droppedTasks > 0) {
            Text(
                text = stringResource(R.string.background_tasks_partial, roster.droppedTasks),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when {
            roster == null -> PanelSentence(stringResource(R.string.background_tasks_unreported))
            roster.tasks.isEmpty() -> PanelSentence(stringResource(R.string.background_tasks_empty))
            else ->
                Column(verticalArrangement = Arrangement.spacedBy(TaskListGap)) {
                    roster.tasks.forEach { TaskRow(it) }
                }
        }
    }
}

@Composable
private fun PanelSentence(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

/**
 * One task: its description and type, then a finished label, the latest mid-life update and the terminal
 * summary when present. Each cut marker is its own [Text] directly after the field it describes, never
 * text joined onto the field, so daemon text ending in the marker's words cannot pass for the app's claim.
 * Not clickable; merged so a screen reader reads the task as one node.
 */
@Composable
private fun TaskRow(task: BackgroundTask) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(TaskRowGap),
    ) {
        TaskField(task.description, wasCut(task.truncatedFields, CUT_DESCRIPTION), emphasized = false)
        TaskField(task.taskType, wasCut(task.truncatedFields, CUT_TASK_TYPE), emphasized = true)
        if (task.isFinished) {
            Text(
                text = stringResource(R.string.background_tasks_finished),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        task.latestUpdate?.let { update ->
            // An empty patch is a value, "claude reported no change", not an absence.
            if (update.patch.isEmpty()) {
                PanelSentence(stringResource(R.string.background_tasks_no_change))
                if (wasCut(update.truncatedFields, CUT_PATCH)) CutMarker()
            } else {
                TaskField(update.patch, wasCut(update.truncatedFields, CUT_PATCH), emphasized = false)
            }
        }
        task.finish?.takeIf { it.summary.isNotEmpty() }?.let { finish ->
            TaskField(finish.summary, wasCut(finish.truncatedFields, CUT_SUMMARY), emphasized = false)
        }
    }
}

@Composable
private fun TaskField(
    raw: String,
    cutByDaemon: Boolean,
    emphasized: Boolean,
) {
    val printable = printableText(raw)
    val text = printable.take(MAX_PANEL_TEXT_CHARS)
    Text(
        text = text,
        style = if (emphasized) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
        fontWeight = if (emphasized) FontWeight.SemiBold else null,
    )
    // A field this client cut for display is marked as well: it was cut, whoever cut it.
    if (cutByDaemon || text.length < printable.length) CutMarker()
}

@Composable
private fun CutMarker() {
    Text(
        text = stringResource(R.string.background_tasks_truncated),
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Whether the daemon reports cutting [wireName]. An open vocabulary: an unknown name marks nothing. */
private fun wasCut(
    truncatedFields: List<String>?,
    wireName: String,
): Boolean = truncatedFields?.contains(wireName) == true

/** One daemon field without control characters, keeping line breaks and tabs. Bounded by the caller. */
private fun printableText(raw: String): String = raw.filterNot { it.isISOControl() && it != '\n' && it != '\t' }

@Preview(name = "Background tasks — Dark", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Background tasks — Light", widthDp = 412, heightDp = 892)
@Composable
private fun BackgroundTaskPanelPreview() {
    PyrycodeMobileTheme {
        BackgroundTaskPanel(
            roster =
                BackgroundTaskRoster(
                    tasks =
                        listOf(
                            BackgroundTask(
                                taskId = "t1",
                                toolCallId = "toolu_1",
                                taskType = "local_bash",
                                description = "npm run dev",
                                truncatedFields = null,
                                latestUpdate = BackgroundTaskUpdate("""{"is_backgrounded":true}""", "", "", null),
                                finish = null,
                                isFinished = false,
                            ),
                            BackgroundTask(
                                taskId = "t2",
                                toolCallId = "toolu_2",
                                taskType = "local_bash",
                                description = "sleep 300",
                                truncatedFields = null,
                                latestUpdate = null,
                                finish = BackgroundTaskUpdate("", "completed", "sleep 300", listOf("summary")),
                                isFinished = true,
                            ),
                        ),
                    droppedTasks = 1,
                ),
            onDismiss = {},
        )
    }
}

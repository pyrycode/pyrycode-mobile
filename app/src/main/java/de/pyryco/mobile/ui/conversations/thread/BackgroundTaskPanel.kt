package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskProgress
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.ui.components.MobileReadOnlyModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.modalControl

// The wire names a `truncated_fields` list carries, matched and never rendered. The task's list names
// `description` / `task_type` (held as `taskType`); an update's own list names `patch` / `summary`. The
// two lists are never merged or read for each other.
private const val CUT_DESCRIPTION = "description"
private const val CUT_TASK_TYPE = "task_type"
private const val CUT_PATCH = "patch"
private const val CUT_SUMMARY = "summary"

// A progress frame's own list (#1044) names `description` (the current activity) and `last_tool_name`.
private const val CUT_LAST_TOOL_NAME = "last_tool_name"

// The terminal statuses the wire names (protocol-mobile.md § `background_task_updated`). An open set:
// any other word is shown as itself in the Stopped style.
private const val STATUS_COMPLETED = "completed"
private const val STATUS_FAILED = "failed"
private const val STATUS_STOPPED = "stopped"

/** A description of this task type is a shell command line, drawn in monospace. */
private const val TYPE_LOCAL_BASH = "local_bash"

/** The render bound on one daemon-authored field. The daemon's own caps are byte caps below this. */
private const val MAX_PANEL_TEXT_CHARS = 4096

private const val RUNNING_CARD_ALPHA = 0.41f
private const val FINISHED_CARD_ALPHA = 0.22f

private val TaskListGap = 10.dp

private val TaskRowGap = 8.dp
private val TagMaxWidth = 160.dp
private val ProgressGap = 2.dp

private const val META_SEPARATOR = " · "

/**
 * The background-task list (#678), redrawn to its Figma frames (#1041), in the shared mobile
 * modal shell, closed from its header glyph or Back. Closing sends nothing and changes nothing.
 *
 * [roster] is branched on before its tasks are read: `null` means nothing has been reported, an empty
 * roster is the daemon saying nothing is alive, and the two read as different sentences. The partial-list
 * notice belongs to the roster, so it shows whichever way the list branches. A listed roster splits into a
 * Running and a Finished group, each in claude's order; a finished task stays listed, so the list agrees
 * with the menu's live count, which excludes it.
 *
 * Every task field is claude-authored (a `local_bash` description and a terminal summary are literal
 * command lines, and the terminal status is an open-set word). Each reaches a plain [Text], stripped of
 * control characters and bounded, and nothing else: no link, click, clipboard, parse, `key()`, test tag or
 * log.
 */
@Composable
internal fun BackgroundTaskPanel(
    roster: BackgroundTaskRoster?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    stopSupported: Boolean = false,
    expandedTaskIds: Set<String> = emptySet(),
    pendingTaskIds: Set<String> = emptySet(),
    onEvent: (ThreadEvent) -> Unit = {},
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
        onDismissRequest = onDismiss,
        modifier = modifier,
    ) {
        // `> 0`, so a nonsense negative count shows no notice rather than a negative one.
        val dropped = if (roster != null && roster.droppedTasks > 0) roster.droppedTasks else 0
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TaskListGap),
        ) {
            if (dropped > 0) PartialNotice(dropped)
            when {
                roster == null ->
                    EmptyReading(
                        dashedRing = true,
                        title = stringResource(R.string.background_tasks_unreported),
                        support = stringResource(R.string.background_tasks_unreported_support),
                    )
                roster.tasks.isEmpty() ->
                    EmptyReading(
                        dashedRing = false,
                        title = stringResource(R.string.background_tasks_empty),
                        support = stringResource(R.string.background_tasks_empty_support),
                    )
                else -> TaskGroups(roster.tasks, partial = dropped > 0, stopSupported, expandedTaskIds, pendingTaskIds, onEvent)
            }
        }
        // Keep every reading anchored to the content start; overflow remains in the shell's scroll area.
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun TaskGroups(
    tasks: List<BackgroundTask>,
    partial: Boolean,
    stopSupported: Boolean,
    expandedTaskIds: Set<String>,
    pendingTaskIds: Set<String>,
    onEvent: (ThreadEvent) -> Unit,
) {
    val running = tasks.filterNot { it.isFinished }
    val finished = tasks.filter { it.isFinished }
    if (running.isNotEmpty()) {
        GroupLabel(
            if (partial) {
                stringResource(R.string.background_tasks_group_running_shown, running.size)
            } else {
                stringResource(R.string.background_tasks_group_running, running.size)
            },
        )
        running.forEach { task ->
            key(task.taskId) {
                TaskRow(task, stopSupported, task.taskId in expandedTaskIds, task.taskId in pendingTaskIds, onEvent)
            }
        }
    }
    if (finished.isNotEmpty()) {
        if (running.isNotEmpty()) Spacer(Modifier.height(4.dp))
        GroupLabel(
            if (partial) {
                stringResource(R.string.background_tasks_group_finished_shown, finished.size)
            } else {
                stringResource(R.string.background_tasks_group_finished, finished.size)
            },
        )
        finished.forEach { task -> key(task.taskId) { TaskRow(task) } }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style =
            MaterialTheme.typography.labelLarge
                .copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.5.sp)
                .untrimmedLineBox(),
        color = MaterialTheme.colorScheme.secondary,
    )
}

/**
 * One task card: its type and status tag, then its description, a running task's progress, the terminal
 * summary and the latest mid-life update when present. Each cut marker is its own element directly after the field it describes,
 * never text joined onto the field, so daemon text ending in the marker's words cannot pass for the app's
 * claim. Eligible running text toggles expansion as one merged node; its stop button is independent.
 */
@Composable
private fun TaskRow(
    task: BackgroundTask,
    stopSupported: Boolean = false,
    expanded: Boolean = false,
    pending: Boolean = false,
    onEvent: (ThreadEvent) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val cardAlpha = if (task.isFinished) FINISHED_CARD_ALPHA else RUNNING_CARD_ALPHA
    val eligible = stopSupported && !task.isFinished
    val expansion = stringResource(if (expanded) R.string.background_tasks_expanded else R.string.background_tasks_collapsed)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.onPrimary.copy(alpha = cardAlpha), MaterialTheme.shapes.small),
        verticalArrangement = Arrangement.spacedBy(TaskRowGap),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (eligible) Modifier.clickable { onEvent(ThreadEvent.BackgroundTaskToggle(task.taskId)) } else Modifier)
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (eligible && expanded) 0.dp else 12.dp)
                    .semantics(mergeDescendants = true) { if (eligible) stateDescription = expansion },
            verticalArrangement = Arrangement.spacedBy(TaskRowGap),
        ) {
            val type = boundedText(taskTypeLabel(task.taskType))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = type.text,
                    modifier = Modifier.weight(1f),
                    style =
                        typography.bodySmall
                            .monospace()
                            .copy(lineHeight = 17.sp, letterSpacing = 0.sp)
                            .untrimmedLineBox(),
                    color = colors.primary,
                )
                TaskTag(task, Modifier.widthIn(max = TagMaxWidth))
                if (eligible) {
                    Icon(
                        painter = painterResource(R.drawable.ic_composer_send),
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(28.dp).testTag("background-task-toggle").rotate(if (expanded) 0f else 180f),
                    )
                }
            }
            if (wasCut(task.truncatedFields, CUT_TASK_TYPE) || type.cutForDisplay) CutMarker()
            TaskField(
                raw = task.description,
                cutByDaemon = wasCut(task.truncatedFields, CUT_DESCRIPTION),
                style =
                    typography.bodyMedium.copy(fontSize = 13.sp).let {
                        if (task.taskType == TYPE_LOCAL_BASH) it.monospace().copy(letterSpacing = 0.sp) else it
                    },
                color = if (task.isFinished) colors.onSurfaceVariant else colors.onSurface,
            )
            task.progress?.takeUnless { task.isFinished }?.let { TaskProgress(it) }
            task.finish?.takeIf { it.summary.isNotEmpty() }?.let { finish ->
                TaskField(
                    raw = finish.summary,
                    cutByDaemon = wasCut(finish.truncatedFields, CUT_SUMMARY),
                    style = typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.onSurfaceVariant,
                )
            }
            task.latestUpdate?.let { LatestUpdate(it) }
        }
        if (eligible && expanded) {
            // Separate from the toggle subtree, including the button's invisible touch extension.
            Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Surface(
                    modifier =
                        Modifier
                            .heightIn(min = 32.dp)
                            .clickable(enabled = !pending, role = Role.Button) { onEvent(ThreadEvent.BackgroundTaskStop(task.taskId)) }
                            .alpha(if (pending) 0.38f else 1f),
                    shape = MaterialTheme.shapes.modalControl,
                    color = colors.background,
                    contentColor = colors.primary,
                    border = BorderStroke(1.dp, colors.primary),
                ) {
                    Text(
                        text = stringResource(R.string.background_tasks_stop),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        style = typography.bodySmall.untrimmedLineBox(),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * A running task's progress (#1044): its current activity, then a meta line of the last tool and the
 * client-formatted counters. `subagentType` is not shown. The activity and the tool name are daemon text,
 * so each is printable, bounded and cut-marked from the progress frame's own list like every other field.
 */
@Composable
private fun TaskProgress(progress: BackgroundTaskProgress) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalContext.current.resources
    val tool = boundedText(progress.lastToolName)
    // An empty tool name drops its segment rather than leaving an empty one between separators.
    val meta =
        (
            listOfNotNull(tool.text.takeUnless { it.isBlank() }) +
                progressCounters(resources, progress.toolUses, progress.totalTokens, progress.durationMs)
        ).joinToString(META_SEPARATOR)
    Column(verticalArrangement = Arrangement.spacedBy(ProgressGap)) {
        TaskField(
            raw = progress.description,
            cutByDaemon = wasCut(progress.truncatedFields, CUT_DESCRIPTION),
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
            color = colors.onSurfaceVariant,
        )
        Text(
            text = meta,
            style =
                MaterialTheme.typography.bodySmall
                    .copy(lineHeight = 17.sp)
                    .untrimmedLineBox(),
            color = colors.outline,
        )
        if (wasCut(progress.truncatedFields, CUT_LAST_TOOL_NAME) || tool.cutForDisplay) CutMarker()
    }
}

/** The last mid-life update in a code block. An empty patch is a value, "claude reported no change". */
@Composable
private fun LatestUpdate(update: BackgroundTaskUpdate) {
    val colors = MaterialTheme.colorScheme
    val patch = boundedText(update.patch)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.background_tasks_latest_update),
            style =
                MaterialTheme.typography.labelMedium
                    .copy(
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        letterSpacing = 0.5.sp,
                    ).untrimmedLineBox(),
            color = colors.outline,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (update.patch.isEmpty()) {
                Text(
                    text = stringResource(R.string.background_tasks_no_change),
                    style =
                        MaterialTheme.typography.bodyMedium
                            .copy(fontSize = 13.sp, lineHeight = 19.sp)
                            .untrimmedLineBox(),
                    fontStyle = FontStyle.Italic,
                    color = colors.outline,
                )
            } else {
                Text(
                    text = patch.text,
                    style =
                        MaterialTheme.typography.bodySmall
                            .monospace()
                            .copy(lineHeight = 17.sp, letterSpacing = 0.sp)
                            .untrimmedLineBox(),
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (wasCut(update.truncatedFields, CUT_PATCH) || patch.cutForDisplay) CutMarker()
    }
}

/**
 * The task's state as its tag. Only the wire's three terminal words pick a style; any other word is
 * shown as itself, bounded, in the Stopped style. A terminal status never reads Running: a finished task
 * without its terminal frame (after a reconnect), or whose word is blank or "running", reads Finished.
 */
@Composable
private fun TaskTag(
    task: BackgroundTask,
    modifier: Modifier = Modifier,
) {
    val finish = task.finish
    val finishedLabel = stringResource(R.string.background_tasks_finished)
    val (style, label) =
        when {
            !task.isFinished -> TaskTagStyle.Running to stringResource(R.string.background_tasks_status_running)
            finish == null -> TaskTagStyle.Stopped to finishedLabel
            finish.status == STATUS_COMPLETED ->
                TaskTagStyle.Completed to stringResource(R.string.background_tasks_status_completed)
            finish.status == STATUS_FAILED -> TaskTagStyle.Failed to stringResource(R.string.background_tasks_status_failed)
            finish.status == STATUS_STOPPED -> TaskTagStyle.Stopped to stringResource(R.string.background_tasks_status_stopped)
            else -> {
                val word = boundedText(finish.status).text
                val readsRunning = word.isBlank() || word.trim().equals("running", ignoreCase = true)
                TaskTagStyle.Stopped to if (readsRunning) finishedLabel else word
            }
        }
    TaskStatusTag(style = style, label = label, modifier = modifier)
}

@Composable
private fun TaskField(
    raw: String,
    cutByDaemon: Boolean,
    style: TextStyle,
    color: Color,
) {
    val field = boundedText(raw)
    Text(text = field.text, style = style.untrimmedLineBox(), color = color)
    // A field this client cut for display is marked as well: it was cut, whoever cut it.
    if (cutByDaemon || field.cutForDisplay) CutMarker()
}

/** The app's own claim that a field was cut: a dashed chip, its own element, never joined to the field. */
@Composable
private fun CutMarker() {
    val color = MaterialTheme.colorScheme.tertiary
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .drawBehind {
                val width = 1.dp.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                drawOutline(shape.createOutline(size, layoutDirection, this), color, style = Stroke(width, pathEffect = dash))
            }
            // The frame's 1 px border sits inside its 20 px box, so it adds to the 1 px vertical padding.
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.background_tasks_truncated),
            style =
                MaterialTheme.typography.labelSmall
                    .copy(
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.3.sp,
                    ).untrimmedLineBox(),
            color = color,
        )
    }
}

@Composable
private fun PartialNotice(count: Int) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(content, CircleShape))
        Text(
            text = stringResource(R.string.background_tasks_partial, count),
            style =
                MaterialTheme.typography.labelLarge
                    .copy(
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        letterSpacing = 0.25.sp,
                    ).untrimmedLineBox(),
            color = content,
        )
    }
}

/** One of the two empty readings: a drawn ring (dashed while nothing has been reported), title and support. */
@Composable
private fun EmptyReading(
    dashedRing: Boolean,
    title: String,
    support: String,
) {
    val ringColor = MaterialTheme.colorScheme.outline
    Column(
        // Figma's content-relative inset below the shared modal's accessible header.
        modifier = Modifier.fillMaxWidth().padding(top = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (dashedRing) {
            Image(
                painter = painterResource(R.drawable.task_not_yet_reported),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
        } else {
            Canvas(Modifier.size(32.dp)) {
                val width = 2.dp.toPx()
                drawCircle(ringColor, radius = (size.minDimension - width) / 2, style = Stroke(width))
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 23.sp, letterSpacing = 0.25.sp),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = support,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Display-only names; raw task types still select description styling and remain in the roster. */
private fun taskTypeLabel(raw: String): String =
    when (raw) {
        "local_agent" -> "Agent"
        TYPE_LOCAL_BASH -> "Command"
        else -> raw.removePrefix("local_").replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }

private fun TextStyle.monospace(): TextStyle = copy(fontFamily = FontFamily.Monospace)

/**
 * The full `lineHeight × lines` box, as in the Figma frames' CSS line boxes. Compose's default trims the
 * half-leading above the first line and below the last, which left each panel text a few px short (#1534).
 */
internal fun TextStyle.untrimmedLineBox(): TextStyle =
    copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None))

/** A daemon field as displayed: printable and bounded, and whether the bound cut it. */
private class BoundedText(
    val text: String,
    val cutForDisplay: Boolean,
)

private fun boundedText(raw: String): BoundedText {
    val printable = printableText(raw)
    val text = printable.take(MAX_PANEL_TEXT_CHARS)
    return BoundedText(text, cutForDisplay = text.length < printable.length)
}

/** Whether the daemon reports cutting [wireName]. An open vocabulary: an unknown name marks nothing. */
private fun wasCut(
    truncatedFields: List<String>?,
    wireName: String,
): Boolean = truncatedFields?.contains(wireName) == true

/** One daemon field without control characters, keeping line breaks and tabs. Bounded by the caller. */
private fun printableText(raw: String): String = raw.filterNot { it.isISOControl() && it != '\n' && it != '\t' }

private val previewRoster =
    BackgroundTaskRoster(
        tasks =
            listOf(
                BackgroundTask(
                    taskId = "t1",
                    toolCallId = "toolu_1",
                    taskType = "local_bash",
                    description = "go test ./internal/relay/... -run TestReconnect -count=20 -race",
                    truncatedFields = null,
                    latestUpdate = BackgroundTaskUpdate("""{"output_tail":"--- PASS: TestReconnect (0.84s)"}""", "", "", null),
                    finish = null,
                    isFinished = false,
                    progress = BackgroundTaskProgress("Running go test with the race detector", "", "Bash", 18_000, 4, 161_000, null),
                ),
                BackgroundTask(
                    taskId = "t2",
                    toolCallId = "toolu_2",
                    taskType = "local_agent",
                    description = "Review the relay reconnect diff for data races",
                    truncatedFields = null,
                    latestUpdate = null,
                    finish = null,
                    isFinished = false,
                    progress = BackgroundTaskProgress("Reading internal/relay/conn.go", "general-purpose", "Read", 42_000, 7, 65_000, null),
                ),
                BackgroundTask(
                    taskId = "t3",
                    toolCallId = "toolu_3",
                    taskType = "local_bash",
                    description = "npm run build",
                    truncatedFields = null,
                    latestUpdate = null,
                    finish = BackgroundTaskUpdate("", "completed", "Build finished in 38s with no warnings.", null),
                    isFinished = true,
                ),
                BackgroundTask(
                    taskId = "t4",
                    toolCallId = "toolu_4",
                    taskType = "local_bash",
                    description = "docker compose up relay",
                    truncatedFields = null,
                    latestUpdate = null,
                    finish = BackgroundTaskUpdate("", "failed", "Exited with code 1: port 8443 is already in use.", null),
                    isFinished = true,
                ),
            ),
        droppedTasks = 0,
    )

@Preview(name = "Background tasks — populated", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackgroundTaskPanelPreview() {
    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
        BackgroundTaskPanel(roster = previewRoster, onDismiss = {}, stopSupported = true, expandedTaskIds = setOf("t1"))
    }
}

@Preview(name = "Background tasks — capped", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackgroundTaskPanelCappedPreview() {
    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
        BackgroundTaskPanel(roster = previewRoster.copy(droppedTasks = 3), onDismiss = {})
    }
}

@Preview(name = "Background tasks — empty", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackgroundTaskPanelEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
        BackgroundTaskPanel(roster = BackgroundTaskRoster(emptyList(), droppedTasks = 0), onDismiss = {})
    }
}

@Preview(name = "Background tasks — unreported", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BackgroundTaskPanelUnreportedPreview() {
    PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
        BackgroundTaskPanel(roster = null, onDismiss = {})
    }
}

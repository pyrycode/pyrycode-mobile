package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

internal val MessageRowVerticalSpacing = 12.dp
internal val ToolCallCornerRadius = 6.dp
internal val ToolCallBorderWidth = 1.dp
internal val ToolCallHorizontalPadding = 12.dp
internal val ToolCallTopPadding = 8.dp
internal val ToolCallHeaderMinHeight = 20.dp
internal val ToolCallCollapsedBottomPadding = 8.dp
private val ToolCallExpandedBottomPadding = 12.dp
internal val ToolCallGap = 12.dp
internal val ToolCallTrailingGap = 6.dp
private val ToolCallStatusIconSize = 16.dp
private val ToolCallSpinnerSize = 14.dp
private val ToolCallSpinnerStrokeWidth = 2.dp
internal val ToolCallChevronSlotWidth = 8.dp
internal val ToolCallChevronSlotHeight = 10.dp
private val ToolCallChevronRightWidth = 4.dp
private val ToolCallChevronRightHeight = 8.dp
internal val ToolCallChevronDownWidth = 8.dp
internal val ToolCallChevronDownHeight = 4.dp
private val ToolCallDescriptionGap = 8.dp
private val ToolCallSectionCaptionGap = 4.dp

// Keeps a long MCP tool name (`mcp__server__tool`) from taking the subject's whole width.
private val ToolNameMaxWidth = 160.dp

// The expanded content scrolls inside this bound rather than growing the thread row without limit.
private val ToolCallExpandedMaxHeight = 320.dp

private const val CODE_ISH_LENGTH_THRESHOLD = 80
private const val COMMAND_FIELD = "command"

/** Tags the row's outlined `Surface` (#1577), so tests can measure where adjacent outlines meet. */
internal const val TOOL_ROW_TAG = "tool-row"

/** Tags the running row's elapsed reading, so tests can assert its absence without guessing a value. */
internal const val TOOL_ELAPSED_TAG = "tool-row-elapsed"
internal const val TOOL_DESCRIPTION_CHEVRON_TAG = "tool-description-chevron"
internal const val TOOL_EXPANDED_BODY_TAG = "tool-expanded-body"

/** Tags a resolved row's result count (#1316), so tests can assert its absence and measure its bound. */
internal const val TOOL_RESULT_DETAIL_TAG = "tool-row-result-detail"

/**
 * One tool call in the thread: a `Bash` description takes the described Figma header, otherwise the
 * simple header draws [toolHeadline]'s lead and subject. Tapping expands the input, the output once
 * resolved and, on a denied row, claude's reason.
 *
 * Every string on the row — tool name, input fields, précis, output, denial — is claude's or the daemon's
 * and renders only as inert [Text] or [CodeBlock]: never a link, a path to open, markup or a log line.
 *
 * `expanded` survives an in-place update (a status flip, a new elapsed reading) because the caller's
 * keyed `LazyColumn` item keeps this call site's identity.
 *
 * [joinsNextToolRow] (#1577) is true when the thread's next row is another tool row: this row drops its
 * spacing below and the next row's outline overlaps this one's, as Figma `620:1792` draws a run of tools.
 */
@Composable
fun ToolCallRow(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
    subagentDepth: Int = 0,
    joinsNextToolRow: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToolCallRowContent(
        toolCall = toolCall,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
        subagentDepth = subagentDepth,
        joinsNextToolRow = joinsNextToolRow,
    )
}

/**
 * [subagentDepth] (#896) is how many `Agent`/`Task` calls deep a subagent's call sits; above 0 the row
 * says so in its content description. The description joins the clickable `Column`'s merged node, so a
 * screen reader announces it with the row rather than as a separate stop, and the level carries the
 * nesting that the caller's indent shows.
 */
@Composable
private fun ToolCallRowContent(
    toolCall: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    subagentDepth: Int = 0,
    joinsNextToolRow: Boolean = false,
) {
    val clickLabel = stringResource(if (expanded) R.string.tool_row_collapse else R.string.tool_row_expand)
    val subagentDescription =
        if (subagentDepth > 0) stringResource(R.string.cd_tool_subagent_step, subagentDepth) else null
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (joinsNextToolRow) Modifier.overlapNextByBorder() else Modifier.padding(bottom = MessageRowVerticalSpacing))
                .testTag(TOOL_ROW_TAG),
        shape = RoundedCornerShape(ToolCallCornerRadius),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(ToolCallBorderWidth, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier =
                Modifier
                    .then(
                        if (subagentDescription != null) {
                            Modifier.semantics { contentDescription = subagentDescription }
                        } else {
                            Modifier
                        },
                    ).clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
                    .padding(
                        start = ToolCallHorizontalPadding,
                        end = ToolCallHorizontalPadding,
                        top = ToolCallTopPadding,
                        bottom = if (expanded) ToolCallExpandedBottomPadding else ToolCallCollapsedBottomPadding,
                    ),
            verticalArrangement = Arrangement.spacedBy(ToolCallGap),
        ) {
            HeaderRow(toolCall, expanded)
            if (expanded) {
                ExpandedBody(toolCall)
            }
        }
    }
}

/**
 * Shared with the #1635 run header, which joins its first row the same way.
 *
 * Reports the row one outline width short while drawing it whole, so the next item's top outline lands
 * on this row's bottom outline and the two read as one line.
 */
internal fun Modifier.overlapNextByBorder(): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val overlap = ToolCallBorderWidth.roundToPx().coerceAtMost(placeable.height)
        layout(placeable.width, placeable.height - overlap) { placeable.place(0, 0) }
    }

/**
 * The trailing status sits outside the weighted headline: a `Row` measures it first, leaving the headline
 * to ellipsize within the thread gutter. The described chevron follows its text as in Figma `134:4904`.
 */
@Composable
private fun HeaderRow(
    toolCall: ToolCall,
    expanded: Boolean,
) {
    val headline = toolHeadline(toolCall.toolName, toolCall.inputFields, toolCall.input)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = ToolCallHeaderMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
    ) {
        when (headline) {
            is ToolHeadline.Described ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ToolCallDescriptionGap),
                ) {
                    Text(
                        text = headline.description,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        modifier = Modifier.size(ToolCallChevronSlotWidth, ToolCallChevronSlotHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter =
                                painterResource(
                                    if (expanded) R.drawable.tool_row_chevron_down else R.drawable.tool_row_chevron_right,
                                ),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(
                                        width = if (expanded) ToolCallChevronDownWidth else ToolCallChevronRightWidth,
                                        height = if (expanded) ToolCallChevronDownHeight else ToolCallChevronRightHeight,
                                    ).testTag(TOOL_DESCRIPTION_CHEVRON_TAG),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            is ToolHeadline.Simple ->
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
                ) {
                    val subject = headline.subject
                    // The cap leaves room for a subject; a lead alone, such as a shell command, takes the row.
                    Text(
                        text = headline.lead,
                        modifier = if (subject.isNotEmpty()) Modifier.widthIn(max = ToolNameMaxWidth) else Modifier,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subject.isNotEmpty()) {
                        Text(
                            text = subject,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
        }
        TrailingStatus(toolCall)
    }
}

/**
 * Each status has its own glyph and content description, so the four can be told apart without colour.
 * `cd_tool_running` and `cd_tool_failed` are matched by the scripted `tool` / `tool-failed` scenarios and
 * `ScriptedToolRowTest`. Only a running row shows claude's elapsed reading; the row runs no timer of its
 * own.
 *
 * A resolved row draws its result count (#1316, Figma's "184 lines") before the glyph when the daemon sent
 * one; an empty or missing count draws nothing and leaves no gap. The group is capped at half the width the
 * header offers it, as desktop caps `.tool-row__right`, and the count is its only shrinkable child, so an
 * unbounded value ellipsizes rather than pushing the glyph or the tool name off the row.
 */
@Composable
private fun TrailingStatus(toolCall: ToolCall) {
    Row(
        modifier = Modifier.maxHalfWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallTrailingGap),
    ) {
        val resultDetail = toolCall.resultDetail
        if (toolCall.status != ToolCallStatus.Running && !resultDetail.isNullOrEmpty()) {
            Text(
                text = resultDetail,
                modifier = Modifier.weight(1f, fill = false).testTag(TOOL_RESULT_DETAIL_TAG),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (toolCall.status) {
            ToolCallStatus.Running -> {
                toolCall.elapsedSeconds?.let { seconds ->
                    Text(
                        text = formatToolElapsed(seconds),
                        modifier = Modifier.testTag(TOOL_ELAPSED_TAG),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                ToolRunningSpinner()
            }
            ToolCallStatus.Done -> ToolDoneGlyph()
            ToolCallStatus.Failed -> ToolFailedGlyph()
            ToolCallStatus.Denied ->
                StatusGlyph(
                    imageVector = Icons.Outlined.Block,
                    description = stringResource(R.string.cd_tool_denied),
                    tint = MaterialTheme.colorScheme.error,
                )
        }
    }
}

/** Measures the content against half of the incoming maximum width, so it can take at most half the row. */
private fun Modifier.maxHalfWidth(): Modifier =
    layout { measurable, constraints ->
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth / 2 else constraints.maxWidth
        val placeable = measurable.measure(constraints.copy(minWidth = minOf(constraints.minWidth, maxWidth), maxWidth = maxWidth))
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/** The running spinner, also the #1635 run header's while any of its tools runs. */
@Composable
internal fun ToolRunningSpinner() {
    val description = stringResource(R.string.cd_tool_running)
    CircularProgressIndicator(
        modifier =
            Modifier
                .size(ToolCallSpinnerSize)
                .semantics { contentDescription = description },
        strokeWidth = ToolCallSpinnerStrokeWidth,
    )
}

/** The done check, also the #1635 run header's when every tool in its run is done. */
@Composable
internal fun ToolDoneGlyph() {
    StatusGlyph(
        imageVector = Icons.Outlined.Check,
        description = stringResource(R.string.cd_tool_done),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The error icon, also the #1635 run header's when any tool in its run failed or was denied. */
@Composable
internal fun ToolFailedGlyph() {
    StatusGlyph(
        imageVector = Icons.Outlined.ErrorOutline,
        description = stringResource(R.string.cd_tool_failed),
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun StatusGlyph(
    imageVector: ImageVector,
    description: String,
    tint: Color,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = description,
        modifier = Modifier.size(ToolCallStatusIconSize),
        tint = tint,
    )
}

@Composable
private fun ExpandedBody(toolCall: ToolCall) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = ToolCallExpandedMaxHeight)
                .verticalScroll(rememberScrollState())
                .testTag(TOOL_EXPANDED_BODY_TAG),
        verticalArrangement = Arrangement.spacedBy(ToolCallGap),
    ) {
        val suppliedFields = toolCall.inputFields.filterValues { it.isNotEmpty() }
        if (suppliedFields.isNotEmpty() || toolCall.input.isNotEmpty()) {
            ExpandedSection(label = stringResource(R.string.tool_row_input)) {
                if (suppliedFields.isEmpty()) {
                    ToolContent(toolCall.input, isCommand = false)
                } else {
                    suppliedFields.forEach { (key, value) -> InputField(key, value) }
                }
            }
        }
        // Output arrives on the correlated `tool_result`; a running or denied call has none.
        if ((toolCall.status == ToolCallStatus.Done || toolCall.status == ToolCallStatus.Failed) && toolCall.output.isNotEmpty()) {
            ExpandedSection(label = stringResource(R.string.tool_row_output)) {
                ToolContent(toolCall.output, isCommand = false, isResult = true)
            }
        }
        // A denied row restored from the disk cache has no denial and shows no reason.
        val denial = toolCall.denial
        if (toolCall.status == ToolCallStatus.Denied &&
            denial != null &&
            (denial.message.isNotEmpty() || denial.decisionReason.isNotEmpty())
        ) {
            ExpandedSection(label = stringResource(R.string.tool_row_denial)) {
                DenialContent(denial)
            }
        }
    }
}

@Composable
private fun ExpandedSection(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolCallSectionCaptionGap)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun InputField(
    key: String,
    value: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolCallSectionCaptionGap)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ToolContent(value, isCommand = key == COMMAND_FIELD)
    }
}

@Composable
private fun DenialContent(denial: ToolDenial) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolCallSectionCaptionGap)) {
        if (denial.message.isNotEmpty()) ToolContent(denial.message, isCommand = false)
        if (denial.decisionReason.isNotEmpty()) ToolContent(denial.decisionReason, isCommand = false)
    }
}

/** Code-like content takes the bordered 12sp code block; the rest wraps as 12sp monospace prose. */
@Composable
private fun ToolContent(
    content: String,
    isCommand: Boolean,
    isResult: Boolean = false,
) {
    val monoStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp, letterSpacing = 0.sp)
    if (isCommand || (if (isResult) content.contains('\n') else isCodeIsh(content))) {
        CodeBlock(content = content, language = null, textStyle = monoStyle)
    } else {
        Text(
            text = content,
            modifier = Modifier.fillMaxWidth(),
            style = monoStyle,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

private fun isCodeIsh(content: String): Boolean = content.contains('\n') || content.length > CODE_ISH_LENGTH_THRESHOLD

private val PreviewReadToolCall =
    ToolCall(
        toolName = "Read",
        input = "file_path=/Users/me/pyrycode-mobile/app/src/main/java/de/pyryco/mobile/MainActivity.kt",
        inputFields = mapOf("file_path" to "/Users/me/pyrycode-mobile/app/src/main/java/de/pyryco/mobile/MainActivity.kt"),
        output =
            """
            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                }
            }
            """.trimIndent(),
        resultDetail = "184 lines",
    )

private val PreviewBashToolCall =
    ToolCall(
        toolName = "Bash",
        input = "git status",
        inputFields = mapOf("command" to "git status", "description" to "Show working tree status"),
        output = "nothing to commit, working tree clean",
    )

private val PreviewRunningToolCall =
    ToolCall(
        toolName = "Bash",
        input = "./gradlew assembleDebug",
        output = "",
        status = ToolCallStatus.Running,
        inputFields = mapOf("command" to "./gradlew assembleDebug", "description" to "Build the debug APK"),
        elapsedSeconds = 65,
    )

private val PreviewFailedToolCall =
    ToolCall(
        toolName = "Bash",
        input = "./gradlew assembleDebug",
        output =
            """
            FAILURE: Build failed with an exception.
            > Task :app:compileDebugKotlin FAILED
            """.trimIndent(),
        status = ToolCallStatus.Failed,
        resultDetail = "2 lines",
    )

private val PreviewDeniedToolCall =
    ToolCall(
        toolName = "mcp__codegraph__codegraph_context",
        input = "task=restyle the tool row",
        output = "",
        status = ToolCallStatus.Denied,
        inputFields = mapOf("task" to "restyle the tool row"),
        denial =
            ToolDenial(
                toolName = "mcp__codegraph__codegraph_context",
                decisionReasonType = "user",
                decisionReason = "",
                message = "The user doesn't want to proceed with this tool use.",
                truncatedFields = null,
                droppedFields = null,
            ),
    )

@Composable
private fun ToolCallRowPreviewMatrix() {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        listOf(
            PreviewReadToolCall,
            PreviewBashToolCall,
            PreviewRunningToolCall,
            PreviewFailedToolCall,
            PreviewDeniedToolCall,
        ).forEach { toolCall ->
            ToolCallRowContent(toolCall = toolCall, expanded = false, onToggle = {})
        }
        listOf(PreviewBashToolCall, PreviewReadToolCall, PreviewRunningToolCall, PreviewDeniedToolCall).forEach { toolCall ->
            ToolCallRowContent(toolCall = toolCall, expanded = true, onToggle = {})
        }
    }
}

@Preview(name = "ToolCallRow — Light", showBackground = true, widthDp = 412, heightDp = 1100)
@Composable
private fun ToolCallRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ToolCallRowPreviewMatrix()
        }
    }
}

@Preview(
    name = "ToolCallRow — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 1100,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ToolCallRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ToolCallRowPreviewMatrix()
        }
    }
}

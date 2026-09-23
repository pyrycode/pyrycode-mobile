package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.model.ToolDenial
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val MessageRowVerticalSpacing = 12.dp
private val ToolCallCornerRadius = 6.dp
private val ToolCallBorderWidth = 1.dp
private val ToolCallHorizontalPadding = 12.dp
private val ToolCallTopPadding = 8.dp
private val ToolCallCollapsedBottomPadding = 8.dp
private val ToolCallExpandedBottomPadding = 12.dp
private val ToolCallGap = 12.dp
private val ToolCallTrailingGap = 6.dp
private val ToolCallStatusIconSize = 16.dp
private val ToolCallSpinnerSize = 14.dp
private val ToolCallSpinnerStrokeWidth = 2.dp
private val ToolCallChevronSize = 16.dp
private val ToolCallSectionCaptionGap = 4.dp

// Keeps a long MCP tool name (`mcp__server__tool`) from taking the subject's whole width.
private val ToolNameMaxWidth = 160.dp

// The expanded content scrolls inside this bound rather than growing the thread row without limit.
private val ToolCallExpandedMaxHeight = 320.dp

private const val CODE_ISH_LENGTH_THRESHOLD = 80
private const val COMMAND_FIELD = "command"

/** Tags the running row's elapsed reading, so tests can assert its absence without guessing a value. */
internal const val TOOL_ELAPSED_TAG = "tool-row-elapsed"

/**
 * One tool call in the thread (#895, Figma `134:4939` collapsed / `134:4941` expanded): the verbatim tool
 * name, the subject picked by [toolRowSubject], a trailing status and an expand chevron. Tapping expands the
 * input, the output once resolved and, on a denied row, claude's reason.
 *
 * Every string on the row — tool name, input fields, précis, output, denial — is claude's or the daemon's
 * and renders only as inert [Text] or [CodeBlock]: never a link, a path to open, markup or a log line.
 *
 * `expanded` survives an in-place update (a status flip, a new elapsed reading) because the caller's
 * keyed `LazyColumn` item keeps this call site's identity.
 */
@Composable
fun ToolCallRow(
    toolCall: ToolCall,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ToolCallRowContent(
        toolCall = toolCall,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    )
}

@Composable
private fun ToolCallRowContent(
    toolCall: ToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clickLabel = stringResource(if (expanded) R.string.tool_row_collapse else R.string.tool_row_expand)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = MessageRowVerticalSpacing),
        shape = RoundedCornerShape(ToolCallCornerRadius),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(ToolCallBorderWidth, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier =
                Modifier
                    .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
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
 * The trailing status and chevron sit outside the weighted name-and-subject group: a `Row` measures its
 * unweighted children first, so they always get their width and the subject ellipsizes into what is left.
 */
@Composable
private fun HeaderRow(
    toolCall: ToolCall,
    expanded: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
        ) {
            Text(
                text = toolCall.toolName,
                modifier = Modifier.widthIn(max = ToolNameMaxWidth),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = toolRowSubject(toolCall.toolName, toolCall.inputFields, toolCall.input),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TrailingStatus(toolCall)
        Icon(
            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(ToolCallChevronSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Each status has its own glyph and content description, so the four can be told apart without colour.
 * `cd_tool_running` and `cd_tool_failed` are matched by the scripted `tool` / `tool-failed` scenarios and
 * `ScriptedToolRowTest`. Only a running row shows claude's elapsed reading; the row runs no timer of its
 * own, and Figma's result-count slot stays empty because `tool_result` carries no count.
 */
@Composable
private fun TrailingStatus(toolCall: ToolCall) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallTrailingGap),
    ) {
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
                val description = stringResource(R.string.cd_tool_running)
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .size(ToolCallSpinnerSize)
                            .semantics { contentDescription = description },
                    strokeWidth = ToolCallSpinnerStrokeWidth,
                )
            }
            ToolCallStatus.Done ->
                StatusGlyph(
                    imageVector = Icons.Outlined.Check,
                    description = stringResource(R.string.cd_tool_done),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            ToolCallStatus.Failed ->
                StatusGlyph(
                    imageVector = Icons.Outlined.ErrorOutline,
                    description = stringResource(R.string.cd_tool_failed),
                    tint = MaterialTheme.colorScheme.error,
                )
            ToolCallStatus.Denied ->
                StatusGlyph(
                    imageVector = Icons.Outlined.Block,
                    description = stringResource(R.string.cd_tool_denied),
                    tint = MaterialTheme.colorScheme.error,
                )
        }
    }
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
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ToolCallGap),
    ) {
        ExpandedSection(label = stringResource(R.string.tool_row_input)) {
            if (toolCall.inputFields.isEmpty()) {
                ToolContent(toolCall.input, isCommand = false)
            } else {
                toolCall.inputFields.forEach { (key, value) -> InputField(key, value) }
            }
        }
        // Output arrives on the correlated `tool_result`; a running or denied call has none.
        if (toolCall.status == ToolCallStatus.Done || toolCall.status == ToolCallStatus.Failed) {
            ExpandedSection(label = stringResource(R.string.tool_row_output)) {
                ToolContent(toolCall.output, isCommand = false)
            }
        }
        // A denied row restored from the disk cache has no denial and shows no reason.
        val denial = toolCall.denial
        if (toolCall.status == ToolCallStatus.Denied && denial != null) {
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
) {
    if (isCommand || isCodeIsh(content)) {
        CodeBlock(content = content, language = null, textStyle = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            text = content,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
        listOf(PreviewReadToolCall, PreviewRunningToolCall, PreviewDeniedToolCall).forEach { toolCall ->
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

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/** Tags the run header's outlined `Surface` (#1635). */
internal const val TOOL_RUN_TAG = "tool-run-row"

/**
 * The header a run of adjacent tool rows folds into (#1635, Figma `726:5376`): "Using tools: N", the tool
 * row's chevron (down collapsed, up expanded) and the run's status. Drawn as a tool row — same outline, fill,
 * 36 dp height and padding — and, expanded, joined flush to the run's first row as #1577 joins tool rows.
 *
 * Status: "K failed" in the error colour whenever K of [toolCalls] failed or were denied; the glyph is the
 * running spinner while any tool runs, else the error icon when any failed, else the done check. Each glyph
 * carries the tool row's content description, which the scripted scenarios and the live tool scenario match.
 */
@Composable
fun ToolRunRow(
    toolCalls: List<ToolCall>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = toolCalls.any { it.status == ToolCallStatus.Running }
    val failed = toolCalls.count { it.status == ToolCallStatus.Failed || it.status == ToolCallStatus.Denied }
    val clickLabel = stringResource(if (expanded) R.string.tool_run_collapse else R.string.tool_run_expand)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .then(if (expanded) Modifier.overlapNextByBorder() else Modifier.padding(bottom = MessageRowVerticalSpacing))
                .testTag(TOOL_RUN_TAG),
        shape = ToolCallShape,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(ToolCallBorderWidth, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier =
                Modifier
                    .clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onToggle)
                    .padding(
                        start = ToolCallHorizontalPadding,
                        end = ToolCallHorizontalPadding,
                        top = ToolCallTopPadding,
                        bottom = ToolCallCollapsedBottomPadding,
                    ).heightIn(min = ToolCallHeaderMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
        ) {
            // Label and chevron share the weighted slot so the status sits against the end edge (Figma
            // `726:5573`), as `ToolCallRow`'s header does.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ToolCallGap),
            ) {
                Text(
                    text = stringResource(R.string.tool_run_label, toolCalls.size),
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
                    // Figma's up chevron is the down one turned over, so the tool row's glyph serves both.
                    Icon(
                        painter = painterResource(R.drawable.tool_row_chevron_down),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(ToolCallChevronDownWidth, ToolCallChevronDownHeight)
                                .rotate(if (expanded) 180f else 0f),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ToolCallTrailingGap),
            ) {
                if (failed > 0) {
                    Text(
                        text = stringResource(R.string.tool_run_failed, failed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
                when {
                    running -> ToolRunningSpinner()
                    failed > 0 -> ToolFailedGlyph()
                    else -> ToolDoneGlyph()
                }
            }
        }
    }
}

private fun previewCalls(vararg statuses: ToolCallStatus): List<ToolCall> =
    statuses.map { ToolCall(toolName = "Read", input = "", output = "", status = it) }

@Composable
private fun ToolRunRowPreviewMatrix() {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        ToolRunRow(previewCalls(*Array(5) { ToolCallStatus.Done }), expanded = false, onToggle = {})
        ToolRunRow(previewCalls(ToolCallStatus.Done, ToolCallStatus.Done, ToolCallStatus.Running), expanded = false, onToggle = {})
        ToolRunRow(
            previewCalls(ToolCallStatus.Done, ToolCallStatus.Failed, ToolCallStatus.Done, ToolCallStatus.Done),
            expanded = false,
            onToggle = {},
        )
        ToolRunRow(previewCalls(ToolCallStatus.Done, ToolCallStatus.Done), expanded = true, onToggle = {})
    }
}

@Preview(name = "ToolRunRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ToolRunRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) { ToolRunRowPreviewMatrix() }
    }
}

@Preview(name = "ToolRunRow — Dark", showBackground = true, widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ToolRunRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) { ToolRunRowPreviewMatrix() }
    }
}

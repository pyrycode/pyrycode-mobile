package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val MessageRowVerticalSpacing = 12.dp
private val ToolCallCornerRadius = 12.dp
private val ToolCallHorizontalPadding = 12.dp
private val ToolCallVerticalPadding = 8.dp
private val ToolCallHeaderGap = 8.dp
private val ToolCallIconSize = 18.dp
private val ToolCallExpandedTopPadding = 8.dp
private val ToolCallExpandedGap = 8.dp

private const val CODE_ISH_LENGTH_THRESHOLD = 80

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
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(bottom = MessageRowVerticalSpacing),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
            shape = RoundedCornerShape(ToolCallCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = ToolCallHorizontalPadding,
                        vertical = ToolCallVerticalPadding,
                    ),
            ) {
                CollapsedHeaderRow(toolCall)
                if (expanded) {
                    ExpandedBody(toolCall)
                }
            }
        }
    }
}

@Composable
private fun CollapsedHeaderRow(toolCall: ToolCall) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolCallHeaderGap),
    ) {
        Icon(
            imageVector = iconForTool(toolCall.toolName),
            contentDescription = null,
            modifier = Modifier.size(ToolCallIconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = buildSummaryAnnotated(toolCall),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExpandedBody(toolCall: ToolCall) {
    Column(
        modifier = Modifier.padding(top = ToolCallExpandedTopPadding),
        verticalArrangement = Arrangement.spacedBy(ToolCallExpandedGap),
    ) {
        ExpandedSection(label = "Input", content = toolCall.input)
        ExpandedSection(label = "Output", content = toolCall.output)
    }
}

@Composable
private fun ExpandedSection(
    label: String,
    content: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ToolCallExpandedGap / 2)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isCodeIsh(content)) {
            CodeBlock(content = content, language = null)
        } else {
            Text(
                text = content,
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun buildSummaryAnnotated(toolCall: ToolCall): AnnotatedString {
    val toolNameColor = MaterialTheme.colorScheme.tertiary
    val argColor = MaterialTheme.colorScheme.onSurfaceVariant
    return buildAnnotatedString {
        withStyle(SpanStyle(color = toolNameColor, fontFamily = FontFamily.Monospace)) {
            append(toolCall.toolName)
        }
        withStyle(SpanStyle(color = argColor)) {
            append(" · ")
            append(primaryArg(toolCall))
        }
    }
}

private fun primaryArg(toolCall: ToolCall): String = toolCall.input.trim()

private fun iconForTool(toolName: String): ImageVector =
    when (toolName.trim().lowercase()) {
        "read" -> Icons.Outlined.Description
        "edit" -> Icons.Outlined.Edit
        "bash" -> Icons.Outlined.Terminal
        else -> Icons.Outlined.Build
    }

private fun isCodeIsh(content: String): Boolean = content.contains('\n') || content.length > CODE_ISH_LENGTH_THRESHOLD

private val PreviewReadToolCall =
    ToolCall(
        toolName = "Read",
        input = "app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt",
        output =
            """
            @Composable
            fun MessageBubble(
                message: Message,
                modifier: Modifier = Modifier,
            ) {
                when (message.role) {
                    Role.User -> UserMessageBubble(message.content, modifier)
                    Role.Assistant -> AssistantMessage(message, modifier)
                    Role.Tool -> message.toolCall?.let { ToolCallRow(toolCall = it, modifier = modifier) }
                }
            }
            """.trimIndent(),
    )

private val PreviewEditToolCall =
    ToolCall(
        toolName = "Edit",
        input = "app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt",
        output =
            """
            @@ -42,3 +42,3 @@
            -val Foo = 1
            +val Foo = 2
            """.trimIndent(),
    )

private val PreviewBashToolCall =
    ToolCall(
        toolName = "Bash",
        input = "git status",
        output =
            """
            On branch feature/131
            nothing to commit, working tree clean
            """.trimIndent(),
    )

@Composable
private fun ToolCallRowPreviewMatrix() {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        ToolCallRowContent(
            toolCall = PreviewReadToolCall,
            expanded = false,
            onToggle = {},
        )
        ToolCallRowContent(
            toolCall = PreviewReadToolCall,
            expanded = true,
            onToggle = {},
        )
        ToolCallRowContent(
            toolCall = PreviewEditToolCall,
            expanded = false,
            onToggle = {},
        )
        ToolCallRowContent(
            toolCall = PreviewEditToolCall,
            expanded = true,
            onToggle = {},
        )
        ToolCallRowContent(
            toolCall = PreviewBashToolCall,
            expanded = false,
            onToggle = {},
        )
        ToolCallRowContent(
            toolCall = PreviewBashToolCall,
            expanded = true,
            onToggle = {},
        )
    }
}

@Preview(name = "ToolCallRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ToolCallRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ToolCallRowPreviewMatrix()
        }
    }
}

@Preview(
    name = "ToolCallRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ToolCallRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ToolCallRowPreviewMatrix()
        }
    }
}

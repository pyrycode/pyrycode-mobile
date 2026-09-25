package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.success

/** The four looks of the Figma "Task status tag" component (563-1054). */
internal enum class TaskTagStyle { Running, Completed, Failed, Stopped }

/**
 * A background task's state pill: a dot and a one-line [label] on the [style]'s colours. Visual only; the
 * caller picks the style and resolves the label, which may be a bounded daemon word drawn as plain text.
 */
@Composable
internal fun TaskStatusTag(
    style: TaskTagStyle,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) =
        when (style) {
            TaskTagStyle.Running -> colors.primaryContainer to colors.onPrimaryContainer
            TaskTagStyle.Completed -> colors.success.copy(alpha = COMPLETED_TINT_ALPHA) to colors.success
            TaskTagStyle.Failed -> colors.errorContainer to colors.onErrorContainer
            TaskTagStyle.Stopped -> colors.secondaryContainer to colors.onSecondaryContainer
        }
    Row(
        modifier =
            modifier
                .background(container, CircleShape)
                .padding(start = 8.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(content, CircleShape))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val COMPLETED_TINT_ALPHA = 0.16f

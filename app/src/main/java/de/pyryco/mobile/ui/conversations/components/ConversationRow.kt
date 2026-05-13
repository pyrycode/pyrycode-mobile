package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Composable
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayName = conversation.name?.takeIf { it.isNotBlank() }
        ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"

    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (conversation.isSleeping) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            ),
                    )
                }
                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val workspaceLabel = condenseWorkspace(conversation.cwd)
                if (workspaceLabel != null) {
                    Text(
                        text = workspaceLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        supportingContent = lastMessage?.let { msg ->
            {
                Text(
                    text = previewText(msg.content),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Text(text = formatRelativeTime(conversation.lastUsedAt))
        },
    )
}

private fun previewText(content: String): String =
    content.replace(Regex("\\s+"), " ").trim()

private fun condenseWorkspace(cwd: String): String? {
    if (cwd == DefaultScratchCwd) return null
    val trimmed = cwd.trimEnd('/')
    val segment = trimmed.substringAfterLast('/')
    return segment.ifBlank { trimmed.ifBlank { null } }
}

private val sameYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    dayOfMonth()
}

private val crossYearFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    dayOfMonth()
    chars(", ")
    year()
}

private fun formatRelativeTime(
    instant: Instant,
    now: Instant = Clock.System.now(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): String {
    val delta = now - instant
    if (delta.isNegative() || delta < 1.minutes) return "just now"
    if (delta < 1.hours) return "${delta.inWholeMinutes}m ago"
    if (delta < 24.hours) return "${delta.inWholeHours}h ago"
    if (delta < 48.hours) return "Yesterday"
    if (delta < 7.days) return "${delta.inWholeDays}d ago"

    val instantDate = instant.toLocalDateTime(timeZone).date
    val nowDate = now.toLocalDateTime(timeZone).date
    return if (instantDate.year == nowDate.year) {
        sameYearFormat.format(instantDate)
    } else {
        crossYearFormat.format(instantDate)
    }
}

@Preview(name = "With message — Light", showBackground = true, widthDp = 412)
@Composable
private fun ConversationRowWithMessagePreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ConversationRow(
            conversation = previewConversation(name = "pyrycode-mobile", isPromoted = true),
            lastMessage = previewMessage("Refactored the conversation repository to expose a Flow."),
            onClick = {},
        )
    }
}

@Preview(
    name = "Without message — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConversationRowWithoutMessagePreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ConversationRow(
            conversation = previewConversation(name = null, isPromoted = false, isSleeping = true),
            lastMessage = null,
            onClick = {},
        )
    }
}

private fun previewConversation(
    name: String?,
    isPromoted: Boolean,
    isSleeping: Boolean = false,
): Conversation =
    Conversation(
        id = "preview-1",
        name = name,
        cwd = if (isPromoted) "~/Workspace/Projects/pyrycode-mobile" else DefaultScratchCwd,
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = isPromoted,
        lastUsedAt = Clock.System.now() - 2.hours,
        isSleeping = isSleeping,
    )

private fun previewMessage(content: String): Message =
    Message(
        id = "msg-1",
        sessionId = "session-1",
        role = Role.User,
        content = content,
        timestamp = Clock.System.now() - 2.hours,
        isStreaming = false,
    )

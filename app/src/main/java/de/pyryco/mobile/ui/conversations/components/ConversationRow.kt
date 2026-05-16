package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val displayName =
        conversation.name?.takeIf { it.isNotBlank() }
            ?: if (conversation.isPromoted) "Untitled channel" else "Untitled discussion"

    val gestureModifier =
        if (onLongClick == null) {
            modifier.clickable(onClick = onClick)
        } else {
            modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        }

    ListItem(
        modifier = gestureModifier,
        leadingContent = { ConversationAvatar(conversation) },
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (conversation.isSleeping) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                    )
                }
            }
        },
        trailingContent = {
            Text(text = formatRelativeTime(conversation.lastUsedAt))
        },
    )
}

@Preview(name = "Promoted channel — Light", showBackground = true, widthDp = 412)
@Composable
private fun ConversationRowPromotedPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ConversationRow(
            conversation = previewConversation(name = "pyrycode-mobile", isPromoted = true),
            onClick = {},
        )
    }
}

@Preview(
    name = "Untitled sleeping discussion — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ConversationRowUntitledSleepingPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ConversationRow(
            conversation = previewConversation(name = null, isPromoted = false, isSleeping = true),
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
        cwd = if (isPromoted) "~/Workspace/Projects/pyrycode-mobile" else DEFAULT_SCRATCH_CWD,
        currentSessionId = "session-1",
        sessionHistory = emptyList(),
        isPromoted = isPromoted,
        lastUsedAt = Clock.System.now() - 2.hours,
        isSleeping = isSleeping,
    )

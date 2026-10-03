package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.archiveKey
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days

@Composable
fun ArchiveRow(
    conversation: Conversation,
    displayName: String,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        R.string.archived_relative_subtitle,
                        formatArchiveRelativeTime(conversation.archiveKey),
                    ),
                // AppTypography.bodySmall sets no lineHeightStyle, so the default trim would shrink this line to
                // its glyphs; 18:2 keeps the full 16 px line box, which makes the row 66 px (#1487).
                style = MaterialTheme.typography.bodySmall.copy(lineHeightStyle = SubtitleLineBox),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alpha(0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRestore,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_archive_restore),
                contentDescription = stringResource(R.string.cd_restore_archive, displayName),
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val SubtitleLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/**
 * The Archive reference uses elapsed weeks and months where the thread uses calendar dates. [instant] is the
 * row's [archiveKey], the same instant the screen orders by.
 */
internal fun formatArchiveRelativeTime(
    instant: kotlinx.datetime.Instant,
    now: kotlinx.datetime.Instant = Clock.System.now(),
): String {
    val age = now - instant
    return when {
        age < 7.days -> formatRelativeTime(instant, now)
        age < 30.days -> {
            val weeks = age.inWholeDays / 7
            "$weeks ${if (weeks == 1L) "week" else "weeks"} ago"
        }
        age < 365.days -> {
            val months = age.inWholeDays / 30
            "$months ${if (months == 1L) "month" else "months"} ago"
        }
        else -> formatRelativeTime(instant, now)
    }
}

@Preview(name = "ArchiveRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ArchiveRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ArchiveRow(
                conversation = previewArchivedConversation(),
                displayName = "old-project-experiments",
                onRestore = {},
            )
        }
    }
}

@Preview(
    name = "ArchiveRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ArchiveRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ArchiveRow(
                conversation = previewArchivedConversation(),
                displayName = "old-project-experiments",
                onRestore = {},
            )
        }
    }
}

private fun previewArchivedConversation(): Conversation =
    Conversation(
        id = "preview-archived",
        name = "old-project-experiments",
        cwd = DEFAULT_SCRATCH_CWD,
        currentSessionId = "session-archived",
        sessionHistory = emptyList(),
        isPromoted = true,
        lastUsedAt = Clock.System.now() - 14.days,
        archived = true,
    )

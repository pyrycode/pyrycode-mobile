package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

// Sit on the same gutter as the message rows above (MessageBubble.kt's MessageContentGutter).
private val BacklogVerticalPadding = 8.dp
private val BacklogRowSpacing = 8.dp

// The bubble geometry is now *consumed* from MessageBubble.kt (BubbleShape, BubbleHorizontalPadding,
// BubbleVerticalPadding, MessageRoleInset — `internal`, same package) rather than copied here. Those
// copies existed only because the originals were file-private; a queued row and a sent one are one
// bubble family and there is nothing left to drift.
//
// The row takes MessageRoleInset so it occupies the same user-side lane a sent bubble does. Its bubble
// then ends up narrower than a sent one, because the waiting glyph and the drop button share that lane
// with it — which is the honest reading. Dropping the old 320dp cap without taking the inset would have
// let a long queued bubble grow *wider* than a sent one, which is the one outcome that breaks family.
private val WaitingGlyphSize = 16.dp
private val WaitingGlyphGap = 8.dp

// De-emphasis that reads each row as not-yet-sent, distinct from full-opacity sent bubbles (in the spirit
// of ThreadScreen.ABOVE_DELIMITER_ALPHA).
private const val QUEUED_ALPHA = 0.6f

/**
 * Foot-of-list backlog of messages still waiting to send while the active conversation's agent is busy
 * (#460's [de.pyryco.mobile.data.repository.ConversationRepository.observeQueue], hoisted onto
 * [de.pyryco.mobile.ui.conversations.thread.ThreadUiState.queuedMessages]).
 *
 * Stateless and a pure function of the hoisted [queued] list — it holds no local state and emits nothing
 * when the backlog is empty, mirroring [ThinkingIndicator]'s early-return idiom. Each entry renders as a
 * de-emphasized user-side bubble (the same shape/colour family as [MessageBubble]'s sent user bubble) with
 * a leading "waiting" glyph, so a queued message reads as *not yet sent* and distinct from the full-opacity
 * sent / streamed messages. Entries render in [queued] order verbatim — no sort, no dedup (FIFO == wire
 * order). [QueuedMessage.text] is user-authored input rendered through plain [Text] (never `MarkdownText`),
 * the same content class the thread host already renders for sent messages.
 *
 * The design-owed Figma frame (16-8) has no backlog treatment drawn yet; the visual follows the app's
 * existing message-row idiom until it lands, exactly as [ThinkingIndicator] / [StallPromotionBanner]
 * shipped their Material 3 defaults. Each row carries a trailing drop affordance (#467) hoisted as
 * [onDrop], called with the row's [QueuedMessage.id]; the drop is a one-way trigger — this render mutates
 * nothing and the dropped row leaves only on the next `queue_state` snapshot (no optimistic removal).
 */
@Composable
fun QueuedBacklog(
    queued: List<QueuedMessage>,
    onDrop: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queued.isEmpty()) return
    val description = stringResource(R.string.cd_thread_queued_backlog)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MessageContentGutter,
                    vertical = BacklogVerticalPadding,
                ).semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(BacklogRowSpacing),
    ) {
        Text(
            text = stringResource(R.string.thread_queued_backlog_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        queued.forEach { entry ->
            QueuedMessageRow(id = entry.id, text = entry.text, onDrop = onDrop)
        }
    }
}

@Composable
private fun QueuedMessageRow(
    id: Long,
    text: String,
    onDrop: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = MessageRoleInset)
                .alpha(QUEUED_ALPHA),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            // Decorative: the row text + the section contentDescription carry the meaning for a11y.
            contentDescription = null,
            modifier =
                Modifier
                    .padding(end = WaitingGlyphGap)
                    .size(WaitingGlyphSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = BubbleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                text = text,
                modifier =
                    Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        // Trailing un-queue affordance. The clickable IconButton forms its own semantics node, so it stays
        // individually addressable despite the section's mergeDescendants group. `Close` (×) is the Material
        // "remove from a list" convention — a queued message is un-queued, not deleted.
        IconButton(onClick = { onDrop(id) }) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_thread_queued_drop),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun previewQueue(): List<QueuedMessage> {
    val t0 = Instant.parse("2026-06-23T10:00:00Z")
    val t1 = Instant.parse("2026-06-23T10:00:05Z")
    val t2 = Instant.parse("2026-06-23T10:00:10Z")
    return listOf(
        QueuedMessage(1L, "Can you also update the migration tests once you're done?", t0),
        QueuedMessage(2L, "And double-check the rollback path.", t1),
        QueuedMessage(3L, "Then push a draft PR.", t2),
    )
}

@Preview(name = "QueuedBacklog — Light", showBackground = true, widthDp = 412)
@Composable
private fun QueuedBacklogLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            QueuedBacklog(queued = previewQueue(), onDrop = {})
        }
    }
}

@Preview(
    name = "QueuedBacklog — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun QueuedBacklogDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            QueuedBacklog(queued = previewQueue(), onDrop = {})
        }
    }
}

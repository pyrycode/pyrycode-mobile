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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.userBubbleContainer

// Sit on the same gutter as the message rows around it (MessageBubble.kt's MessageContentGutter).
private val QueuedRowVerticalPadding = 8.dp

// The bubble geometry is *consumed* from MessageBubble.kt (BubbleShape, BubbleHorizontalPadding,
// BubbleVerticalPadding, MessageRoleInset — `internal`, same package) rather than copied here. A queued
// row and a sent one are one bubble family and there is nothing left to drift.
//
// The row takes MessageRoleInset so it occupies the same user-side lane a sent bubble does. Its bubble
// then ends up narrower than a sent one, because the waiting glyph and the drop button share that lane
// with it — which is the honest reading. Dropping the old 320dp cap without taking the inset would have
// let a long queued bubble grow *wider* than a sent one, which is the one outcome that breaks family.
private val WaitingGlyphSize = 16.dp
private val WaitingGlyphGap = 8.dp

// De-emphasis that reads the row as not-yet-sent, distinct from the full-opacity sent bubbles it now
// sits among (in the spirit of ThreadScreen.ABOVE_DELIMITER_ALPHA).
private const val QUEUED_ALPHA = 0.6f

/**
 * One message still waiting to send while the conversation's agent is busy — the queued form of a user
 * message row, drawn **inline at the message's own position** in the thread (#782).
 *
 * Until #782 this was a private row inside a foot-of-list `QueuedBacklog` section, which drew a queued
 * send a second time below the thread while the optimistic echo drew it in place. The section is gone:
 * [de.pyryco.mobile.ui.conversations.thread.foldQueuedRows] joins the daemon's `queue_state` snapshot
 * against the thread's rows and the list renders this composable for each row the join marked — at the
 * echo's own index when it correlated, after the thread rows when it did not.
 *
 * Stateless and a pure function of its arguments: no local state, no flow, no side effect. [text] is
 * user-authored input rendered through plain [Text] (never `MarkdownText`), the same content class the
 * thread already renders for sent messages; an unmatched row's text comes from another paired device
 * by way of the daemon and is rendered on exactly the same inert path. The row carries the drop
 * affordance as a payload-free [onDrop] — the caller binds the id, so this row never holds one and
 * cannot leak it into a render, a key or a log. The drop is a one-way trigger: this render mutates
 * nothing and the row leaves only on the next `queue_state` snapshot (no optimistic removal).
 *
 * The design-owed Figma frame (16-8) draws no backlog treatment, so the visual follows the app's
 * existing message-row idiom, exactly as [ThinkingIndicator] / [StallPromotionBanner] shipped their
 * Material 3 defaults. When the frame arrives, retune here — no contract change.
 */
@Composable
fun QueuedMessageRow(
    text: String,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The row announces its own text plus the waiting state. `stateDescription` rather than a
    // `contentDescription` that would replace the text: the row now sits among delivered rows, so what
    // distinguishes it is a state, not a different identity. It is also the Compose-test handle, the
    // marker idiom ThreadScreen's ModalOptionButton already uses.
    val queuedState = stringResource(R.string.thread_queued_state_desc)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = MessageContentGutter + MessageRoleInset,
                    end = MessageContentGutter,
                    bottom = QueuedRowVerticalPadding,
                ).alpha(QUEUED_ALPHA)
                .semantics(mergeDescendants = true) { stateDescription = queuedState },
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            // Decorative: the row text + the state description carry the meaning for a11y.
            contentDescription = null,
            modifier =
                Modifier
                    .padding(end = WaitingGlyphGap)
                    .size(WaitingGlyphSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = BubbleShape,
            color = MaterialTheme.colorScheme.userBubbleContainer,
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
        // Trailing un-queue affordance. The clickable IconButton forms its own semantics node, so it
        // stays individually addressable despite the row's mergeDescendants group. `Close` (×) is the
        // Material "remove from a list" convention — a queued message is un-queued, not deleted.
        IconButton(onClick = onDrop) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.cd_thread_queued_drop),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueuedMessageRowPreviewSequence() {
    Column {
        QueuedMessageRow(text = "Can you also update the migration tests once you're done?", onDrop = {})
        QueuedMessageRow(text = "And double-check the rollback path.", onDrop = {})
        QueuedMessageRow(text = "Then push a draft PR.", onDrop = {})
    }
}

@Preview(name = "QueuedMessageRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun QueuedMessageRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { QueuedMessageRowPreviewSequence() }
    }
}

@Preview(
    name = "QueuedMessageRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun QueuedMessageRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { QueuedMessageRowPreviewSequence() }
    }
}

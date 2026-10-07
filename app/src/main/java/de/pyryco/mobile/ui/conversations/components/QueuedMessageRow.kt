package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.userBubbleContainer

// Half of the frame's 16dp inter-row gap; preserve the 8dp bottom rest compensation in ThreadScreen.
private val QueuedRowVerticalPadding = 8.dp

// The bubble geometry is *consumed* from MessageBubble.kt (BubbleShape, BubbleHorizontalPadding,
// BubbleVerticalPadding, MessageRoleInset — `internal`, same package) rather than copied here. A queued
// row and a sent one are one bubble family and there is nothing left to drift.
//
// Reserve the waiting glyph and narrow action column before measuring the wrapping bubble.
private val QueuedBubbleMaxWidth = 200.dp
private val WaitingGlyphSize = 16.dp
private val WaitingGlyphGap = 12.dp
private val QueuedActionsWidth = 13.dp
private val QueuedActionsGap = 12.dp
private val QueuedActionTarget = 48.dp
private val QueuedActionCentreGap = 25.dp
private val QueuedActionGlyph = 12.dp

// De-emphasis that reads the row as not-yet-sent, distinct from the full-opacity sent bubbles it now
// sits among.
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
 * Figma `848:9517` (Mobile) supplies the full-opacity Send now/Cancel column left of the
 * dimmed bubble, preceded by the retained waiting glyph.
 */
@Composable
fun QueuedMessageRow(
    text: String,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
    onSendNow: (() -> Unit)? = null,
) {
    // The row announces its own text plus the waiting state. `stateDescription` rather than a
    // `contentDescription` that would replace the text: the row now sits among delivered rows, so what
    // distinguishes it is a state, not a different identity. It is also the Compose-test handle, the
    // marker idiom ThreadScreen's ModalOptionButton already uses.
    val queuedState = stringResource(R.string.thread_queued_state_desc)
    val actionHeight = if (onSendNow != null) QueuedActionTarget * 2 else QueuedActionTarget
    Layout(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = MessageContentGutter + MessageRoleInset,
                    end = MessageContentGutter,
                    top = QueuedRowVerticalPadding,
                    bottom = QueuedRowVerticalPadding,
                ).semantics(mergeDescendants = true) { stateDescription = queuedState },
        content = {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                modifier = Modifier.size(WaitingGlyphSize).alpha(QUEUED_ALPHA).testTag("queued-waiting-glyph"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                modifier = Modifier.heightIn(min = actionHeight).alpha(QUEUED_ALPHA).testTag("queued-bubble"),
                shape = BubbleShape,
                color = MaterialTheme.colorScheme.userBubbleContainer,
            ) {
                Box(contentAlignment = Alignment.CenterStart) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = BubbleHorizontalPadding, vertical = BubbleVerticalPadding),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            QueuedActions(onDrop, onSendNow)
        },
    ) { measurables, constraints ->
        val waitingWidth = WaitingGlyphSize.roundToPx()
        val waitingGap = WaitingGlyphGap.roundToPx()
        val actionsWidth = QueuedActionsWidth.roundToPx()
        val bubbleGap = QueuedActionsGap.roundToPx()
        val reserved = waitingWidth + waitingGap + actionsWidth + bubbleGap
        val waiting = measurables[0].measure(Constraints.fixed(waitingWidth, WaitingGlyphSize.roundToPx()))
        val bubble =
            measurables[1].measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxWidth = minOf(QueuedBubbleMaxWidth.roundToPx(), (constraints.maxWidth - reserved).coerceAtLeast(0)),
                ),
            )
        val actions = measurables[2].measure(Constraints.fixed(actionsWidth, bubble.height))
        layout(constraints.maxWidth, bubble.height) {
            val bubbleX = constraints.maxWidth - bubble.width
            val actionsX = bubbleX - bubbleGap - actionsWidth
            waiting.placeRelative(actionsX - waitingGap - waitingWidth, (bubble.height - waiting.height) / 2)
            bubble.placeRelative(bubbleX, 0)
            // As in MessageActions, the extended target wins its small overlap with the bubble.
            actions.placeRelative(actionsX, 0)
        }
    }
}

/** Adjoining targets meet at the bubble midpoint while the visible glyphs stay 25dp apart. */
@Composable
private fun QueuedActions(
    onDrop: () -> Unit,
    onSendNow: (() -> Unit)?,
) {
    val paired = onSendNow != null
    val callbacks = if (onSendNow != null) listOf(onSendNow, onDrop) else listOf(onDrop)
    val sendLabel = stringResource(R.string.cd_thread_queued_send_now)
    val dropLabel = stringResource(R.string.cd_thread_queued_drop)
    Box(modifier = Modifier.testTag("queued-actions"), contentAlignment = Alignment.Center) {
        Layout(
            modifier = Modifier.requiredSize(QueuedActionTarget, QueuedActionTarget * callbacks.size),
            content = {
                callbacks.forEachIndexed { index, callback ->
                    val send = paired && index == 0
                    Layout(
                        modifier =
                            Modifier
                                .semantics { contentDescription = if (send) sendLabel else dropLabel }
                                .clickable(role = Role.Button, onClick = callback),
                        content = {
                            Icon(
                                painter = painterResource(if (send) R.drawable.ic_composer_send else R.drawable.ic_queued_cancel),
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(
                                            QueuedActionGlyph,
                                        ).testTag(if (send) "queued-send-glyph" else "queued-cancel-glyph"),
                                // Figma's inversePrimary falls below 3:1 on the thread canvas.
                                // Use the copy/reply Primary contrast resolution, without a backing.
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                    ) { children, targetConstraints ->
                        val glyph = children.single().measure(targetConstraints.copy(minWidth = 0, minHeight = 0))
                        val halfGap = (QueuedActionCentreGap / 2).toPx()
                        val centreY =
                            when {
                                !paired -> targetConstraints.maxHeight / 2f
                                send -> targetConstraints.maxHeight - halfGap
                                else -> halfGap
                            }
                        val x = (targetConstraints.maxWidth - glyph.width) / 2f
                        val y = centreY - glyph.height / 2f
                        layout(targetConstraints.maxWidth, targetConstraints.maxHeight) {
                            glyph.placeRelativeWithLayer(x.toInt(), y.toInt()) {
                                translationX = x - x.toInt()
                                translationY = y - y.toInt()
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val targetHeight = constraints.maxHeight / callbacks.size
            val targets = measurables.map { it.measure(Constraints.fixed(constraints.maxWidth, targetHeight)) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                targets.forEachIndexed { index, target -> target.placeRelative(0, index * targetHeight) }
            }
        }
    }
}

@Composable
private fun QueuedMessageRowPreviewSequence() {
    Column {
        QueuedMessageRow(text = "Can you also update the migration tests once you're done?", onDrop = {}, onSendNow = {})
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

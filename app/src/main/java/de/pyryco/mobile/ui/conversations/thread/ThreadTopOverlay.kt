package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.conversations.components.usageLimitIsWarning
import de.pyryco.mobile.ui.conversations.components.usageLimitLabel

// Figma 541:2446: the pills stack 12dp apart.
private val OverlayPillGap = 12.dp

/**
 * Figma `533:1956`'s `Top overlay` (#1002): the thread's notices as a right-aligned stack of pills pinned
 * to the top of the message area, drawn over the messages so it takes no layout space. Notices live here
 * rather than in the status row, so they never hide what the running turn is doing.
 *
 * Top to bottom: claude's usage-limit report, then the pairing error. The report is a Default pill with an
 * X only when [usageLimitIsWarning] says so, and it is left out once [usageLimitDismissed]; any other
 * reading is an Error pill that cannot be hidden. The pairing pill shows while [showRePair] does and
 * starts the re-pair flow on tap. With neither, nothing is emitted. The report names [agent] (#1115).
 */
@Composable
internal fun ThreadTopOverlay(
    usageLimit: UsageLimitReading?,
    usageLimitDismissed: Boolean,
    onDismissUsageLimit: () -> Unit,
    showRePair: Boolean,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
) {
    val usage = usageLimit?.takeUnless { usageLimitDismissed }
    if (usage == null && !showRePair) return
    // Keep the clickable error pill at the design's 24dp visible height. The default Material layout
    // minimum inserted an extra 12dp above and below it, shifting the visible stack out of position.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(OverlayPillGap),
        ) {
            if (usage != null) {
                val warning = usageLimitIsWarning(usage)
                NoticePill(
                    text = usageLimitLabel(usage, agent),
                    isError = !warning,
                    onDismiss = if (warning) onDismissUsageLimit else null,
                )
            }
            if (showRePair) {
                // The label is a local resource, never daemon text.
                NoticePill(text = stringResource(R.string.thread_re_pair), isError = true, onClick = onRePair)
            }
        }
    }
}

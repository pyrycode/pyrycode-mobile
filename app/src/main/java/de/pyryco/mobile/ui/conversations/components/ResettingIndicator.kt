package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val IndicatorVerticalPadding = 4.dp

/**
 * Status-slot affordance for a running Reset session (#872), rendering the open conversation's
 * [ResetStatus] (#871, hoisted as [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.resetting]).
 * Without it the thread pauses through the wrap-up turn and the respawn with no explanation.
 *
 * Stateless and a pure function of [status]: emits nothing for `null`, the sibling early-return idiom.
 * Otherwise the [CompactingIndicator] row — a plain label, since both phases are progress with no counter
 * on the wire — chosen by [resettingLabelRes]; the band draws the snowflake beside it (#1312). The label
 * is also the row's merged content description, so the wording has one source. Every string is a local
 * resource selected by closed-set enum; no daemon or claude text reaches this composable.
 */
@Composable
fun ResettingIndicator(
    status: ResetStatus?,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
) {
    if (status == null) return
    val label = stringResource(resettingLabelRes(status, agent))
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = IndicatorVerticalPadding)
                .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The label for [status] (#872). Total over both enums, because the wire carries `phase` and `handoff`
 * independently: the phase decides the reading, and the handoff outcome is shown only once restarting. A
 * restart whose outcome is still `Pending` claims no outcome at all. The wrap-up is the conversation's own
 * agent writing the note (#1112), so only that reading names [agent]; the restart labels name no agent.
 */
@StringRes
internal fun resettingLabelRes(
    status: ResetStatus,
    agent: ConversationAgent = ConversationAgent.Claude,
): Int =
    when (status.phase) {
        ResetStatus.Phase.WrappingUp ->
            when (agent) {
                ConversationAgent.Claude -> R.string.thread_resetting_wrapping_up
                ConversationAgent.Codex -> R.string.thread_resetting_wrapping_up_codex
            }
        ResetStatus.Phase.Restarting ->
            when (status.handoff) {
                ResetStatus.Handoff.Written -> R.string.thread_resetting_restarting_written
                ResetStatus.Handoff.Skipped -> R.string.thread_resetting_restarting_skipped
                ResetStatus.Handoff.Pending -> R.string.thread_resetting_restarting
            }
    }

@Composable
private fun ResettingIndicatorPreviewContent() {
    Column {
        ResettingIndicator(status = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending))
        ResettingIndicator(status = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written))
        ResettingIndicator(status = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Skipped))
    }
}

@Preview(name = "ResettingIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun ResettingIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { ResettingIndicatorPreviewContent() }
    }
}

@Preview(
    name = "ResettingIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ResettingIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { ResettingIndicatorPreviewContent() }
    }
}

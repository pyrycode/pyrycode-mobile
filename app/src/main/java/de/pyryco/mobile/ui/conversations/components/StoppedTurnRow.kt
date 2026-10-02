package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.stoppedReportText
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

/**
 * A turn that failed or stopped early (#1356), drawn as one line of muted text in the message stream —
 * Figma's `Thread notification` "No details" state, the treatment [BannerNoticeRow] draws. The client-owned
 * "Stopped" lead carries the meaning, so nothing depends on colour.
 *
 * Security — [ThreadItem.StoppedTurn]'s strings are agent-authored. The render-time obligations:
 * - **Sanitized again** by [stoppedTurnLabel] through `stoppedReportText`, so a row restored from the
 *   thread cache is held to the rule a live one was built by.
 * - **Inert** — a plain [Text]: never markdown, no link detection, no click, no `SelectionContainer`.
 * - **Attributed** — a category follows the conversation's client-owned [agentName], never stands alone.
 * - **No logging** — nothing on this path logs either string.
 */
@Composable
fun StoppedTurnRow(
    item: ThreadItem.StoppedTurn,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stoppedTurnLabel(item, agent),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = MessageContentGutter, end = MessageContentGutter, bottom = MessageAreaRowSpacing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * [item]'s text, desktop's `stoppedTurnText` copy: the reason picks the fixed phrase, an unknown reason
 * follows "Stopped: ", none reads as a bare error, and a category appends "(<agent> reported: <category>)".
 */
@Composable
internal fun stoppedTurnLabel(
    item: ThreadItem.StoppedTurn,
    agent: ConversationAgent,
): String {
    val reason = stoppedReportText(item.reason)
    val category = stoppedReportText(item.category)
    val text =
        when (reason) {
            "max_turns" -> stringResource(R.string.thread_stopped_turn_limit)
            "budget_exhausted" -> stringResource(R.string.thread_stopped_budget)
            "prompt_too_long" -> stringResource(R.string.thread_stopped_context_too_long)
            "api_error" -> stringResource(R.string.thread_stopped_api_error)
            "hook_stopped", "stop_hook_prevented" -> stringResource(R.string.thread_stopped_hook)
            "model_error" -> stringResource(R.string.thread_stopped_model_error)
            "" -> stringResource(R.string.thread_stopped_error)
            else -> stringResource(R.string.thread_stopped_reason, reason)
        }
    if (category.isEmpty()) return text
    return stringResource(R.string.thread_stopped_reported, text, agentName(agent), category)
}

// Preview fixtures are hand-written literals, never captured live payloads.
private val PreviewInstant = Instant.parse("2026-10-02T12:00:00Z")

@Composable
private fun StoppedTurnRowPreviewMatrix() {
    Column {
        StoppedTurnRow(ThreadItem.StoppedTurn("t1", "max_turns", "", PreviewInstant), agent = ConversationAgent.Claude)
        StoppedTurnRow(ThreadItem.StoppedTurn("t2", "api_error", "overloaded", PreviewInstant), agent = ConversationAgent.Claude)
        StoppedTurnRow(ThreadItem.StoppedTurn("t3", "prompt_too_long", "invalid_request", PreviewInstant), agent = ConversationAgent.Codex)
        StoppedTurnRow(ThreadItem.StoppedTurn("t4", "", "", PreviewInstant), agent = ConversationAgent.Claude)
    }
}

@Preview(name = "StoppedTurnRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun StoppedTurnRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            StoppedTurnRowPreviewMatrix()
        }
    }
}

// The dark preview is the fidelity reference against Figma 620:1574 as placed in 16:8.
@Preview(
    name = "StoppedTurnRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun StoppedTurnRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            StoppedTurnRowPreviewMatrix()
        }
    }
}

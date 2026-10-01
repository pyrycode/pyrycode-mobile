package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 4.dp
private val GlyphWidth = 14.dp
private val GlyphHeight = 16.dp
private val IconLabelGap = 8.dp

/**
 * The largest reading this row will put on screen. The largest documented extended-thinking budget for a
 * single inference request is ~64k tokens, so a million leaves better than an order of magnitude of
 * headroom while capping the rendered number at seven digits — which is what keeps the label inside the
 * composer's status band beside the contextual-action slot. Exceeding it is not an error: the status
 * degrades to the less-specific-but-true counter-less form.
 */
private const val MAX_PLAUSIBLE_THINKING_TOKENS = 1_000_000L

/**
 * Status-band "thinking" affordance shown while the active conversation's agent is working but no
 * assistant text has appeared yet (#406's `turn_state` = `thinking` phase, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isThinking]), carrying claude's live token
 * reading when one is available (#801's `thinking_progress`, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.thinkingProgress]). The reading is claude's
 * only mid-turn proof of life on the stream-json surface: without it a three-minute turn is
 * indistinguishable from a wedged session (#803).
 *
 * Stateless and a pure function of [isThinking] and [progress] — it holds no local state and emits
 * nothing when not thinking, mirroring the connection status indicator's early-return idiom.
 *
 * **What the label may not claim.** [progress] is cumulative within *one inference request*, not within
 * a turn, and restarts near zero at every request boundary — repeatedly inside a single turn. The
 * per-line deltas a client receives also do not sum to the turn's total and no field reports the
 * residue, so no denominator exists anywhere. The label therefore scopes its count to the current step
 * and carries no second number, no "of", and no percentage; `estimated_tokens_delta` is not rendered at
 * all, because putting a rate reading on screen is the most direct invitation to the summing error. See
 * `docs/protocol-mobile.md` § `thinking_progress` in the pyrycode repo for the measured contract.
 *
 * **Absence is not a stall.** A gap between frames may only mean the producer's rate bound has not been
 * crossed, and the PTY surface emits none of these frames at all — so `null` degrades to the plain
 * "Thinking…" label and never to a stalled, failed or errored presentation.
 *
 * **One [Row] and one icon, deliberately.** Splitting the two label variants
 * across an `if`/`else` would give Compose two groups, so the first reading to arrive would dispose the
 * icon and compose a fresh one — restarting its pulse exactly when the reading appears. Only the
 * [Text]'s argument and the row's content description vary, which keeps the icon's composition
 * identity stable across the transition. There is likewise no `remember`-cached label and no
 * `derivedStateOf`: either would freeze a changing reading (the [ApiRetryIndicator] rule).
 *
 * **The running tool (#897).** While a turn runs and a tool call is open, [runningTool] is that call
 * (see `openToolCall` beside the thread screen) and the label reads `Running <tool>…`, with claude's latest
 * `tool_progress` reading appended in the tool row's elapsed format. It replaces both thinking labels
 * and raises the row on its own, so a tool running in the `responding` phase is named too. It varies the
 * same [Text] argument, so the icon keeps its identity when a tool opens or closes. With no reading the
 * label shows no time; nothing here counts seconds.
 *
 * **Working and stalled (#1311).** [isWorking] is the `responding` phase of a running turn and reads
 * `Working…`, so the band never goes dark while claude writes text, between two tools, or after a denial.
 * [isStalled] reads the client-owned `The turn seems to have stalled…` in the error colour and outranks
 * every other label here. Both vary the same [Text], so the glyph keeps its identity across Thinking →
 * Working → Running tool → Stalled. Which one the band shows is decided once, by `statusArm` beside the
 * thread screen; the precedence below only mirrors it.
 *
 * Figma's input status glyph keeps its 14 × 16 bounds while its opacity pulses; no animation frame
 * rotates or stretches the supplied shape.
 */
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    progress: ThinkingProgress? = null,
    runningTool: ToolCall? = null,
    agent: ConversationAgent = ConversationAgent.Claude,
    isWorking: Boolean = false,
    isStalled: Boolean = false,
) {
    if (!isThinking && !isWorking && !isStalled && runningTool == null) return
    // Null whenever the reading must not be shown: no frame yet, or one the sanity gate declines.
    val tokens = progress?.takeIf { isThinking && it.isRenderableReading() }?.estimatedTokens
    // #897: name and seconds come from the one open call. The seconds are claude's reading, never a timer.
    val toolName = runningTool?.takeUnless { isStalled }?.toolName
    val elapsed = runningTool?.elapsedSeconds?.let(::formatToolElapsed)
    val description =
        when {
            isStalled -> stringResource(R.string.cd_thread_stalled)
            toolName != null && elapsed != null ->
                stringResource(
                    when (agent) {
                        ConversationAgent.Claude -> R.string.cd_thread_tool_running_elapsed
                        ConversationAgent.Codex -> R.string.cd_thread_tool_running_elapsed_codex
                    },
                    toolName,
                    elapsed,
                )
            toolName != null ->
                stringResource(
                    when (agent) {
                        ConversationAgent.Claude -> R.string.cd_thread_tool_running
                        ConversationAgent.Codex -> R.string.cd_thread_tool_running_codex
                    },
                    toolName,
                )
            tokens != null ->
                stringResource(
                    when (agent) {
                        ConversationAgent.Claude -> R.string.cd_thread_thinking_progress
                        ConversationAgent.Codex -> R.string.cd_thread_thinking_progress_codex
                    },
                    tokens,
                )
            isThinking -> stringResource(R.string.cd_thread_thinking)
            else ->
                stringResource(
                    when (agent) {
                        ConversationAgent.Claude -> R.string.cd_thread_working
                        ConversationAgent.Codex -> R.string.cd_thread_working_codex
                    },
                )
        }
    val label =
        when {
            isStalled -> stringResource(R.string.thread_stalled_label)
            toolName != null && elapsed != null ->
                stringResource(R.string.thread_tool_running_elapsed_label, toolName, elapsed)
            toolName != null -> stringResource(R.string.thread_tool_running_label, toolName)
            tokens != null -> stringResource(R.string.thread_thinking_progress_label, tokens)
            isThinking -> stringResource(R.string.thread_thinking_label)
            else -> stringResource(R.string.thread_working_label)
        }
    val glyphPulse = rememberInfiniteTransition(label = "thinking glyph")
    val glyphAlpha =
        glyphPulse.animateFloat(
            initialValue = 1f,
            targetValue = 0.7f,
            animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
            label = "thinking glyph opacity",
        )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = IndicatorHorizontalPadding,
                    vertical = IndicatorVerticalPadding,
                ).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IconLabelGap),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_thread_thinking),
            contentDescription = null,
            modifier = Modifier.size(GlyphWidth, GlyphHeight).alpha(glyphAlpha.value).testTag("thinking_glyph"),
        )
        // A tool name is daemon text from an open set: one ellipsized line bounds it, as on the tool row.
        // The thinking labels keep their shipped wrapping.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            // #1311: the stall reads in the error colour the turn-outcome arm's text already uses.
            color = if (isStalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            maxLines = if (toolName != null) 1 else Int.MAX_VALUE,
            overflow = if (toolName != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        )
    }
}

/**
 * Whether this reading is sane enough to put on screen — the display sanity gate, and the single place
 * the fallback to the plain "Thinking…" label is decided.
 *
 * Renderable iff `estimatedTokens in 0..`[MAX_PLAUSIBLE_THINKING_TOKENS]. A negative count of tokens is
 * meaningless to a reader, and an unbounded one would push the label past the band's trailing
 * contextual-action slot.
 *
 * This is a **render-or-decline** gate, never a clamp: an unusable reading is not shown, and no server
 * value is rewritten. #801 deliberately declined a lower-bound rejection at the decode boundary, on the
 * carry-verbatim posture every sibling mapper follows, so what arrives here can legally be negative or
 * arbitrarily large — and clamping it at the display layer would re-import exactly the server-data
 * rewrite that decision refused. Declining is plain layout safety against a buggy or hostile daemon on
 * top of that, the same posture [ApiRetryIndicator]'s counter gate ships.
 *
 * `estimatedTokensDelta` is not gated because it is not rendered; see the composable's KDoc for why.
 */
private fun ThinkingProgress.isRenderableReading(): Boolean = estimatedTokens in 0..MAX_PLAUSIBLE_THINKING_TOKENS

@Preview(name = "ThinkingIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun ThinkingIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            // With a reading — 184 is the top of the first of the four restarts the committed capture's
            // single turn contains, so it is a realistic magnitude rather than a round invented one.
            ThinkingIndicator(
                isThinking = true,
                progress = ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64),
            )
        }
    }
}

@Preview(
    name = "ThinkingIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThinkingIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ThinkingIndicator(isThinking = true)
        }
    }
}

@Preview(name = "ThinkingIndicator — Running tool", showBackground = true, widthDp = 412)
@Composable
private fun ThinkingIndicatorRunningToolPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ThinkingIndicator(
                isThinking = false,
                runningTool =
                    ToolCall(
                        toolName = "Bash",
                        input = "",
                        output = "",
                        status = ToolCallStatus.Running,
                        elapsedSeconds = 65,
                    ),
            )
        }
    }
}

@Preview(name = "ThinkingIndicator — Working", showBackground = true, widthDp = 412)
@Composable
private fun ThinkingIndicatorWorkingPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ThinkingIndicator(isThinking = false, isWorking = true)
        }
    }
}

@Preview(
    name = "ThinkingIndicator — Stalled, Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ThinkingIndicatorStalledPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ThinkingIndicator(isThinking = false, isStalled = true)
        }
    }
}

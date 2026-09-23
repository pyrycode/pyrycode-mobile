package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStrokeWidth = 2.dp
private val SpinnerLabelGap = 8.dp

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
 * nothing when not thinking, mirroring [ConnectionBanner]'s early-return idiom.
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
 * **One [Row] and one [CircularProgressIndicator], deliberately.** Splitting the two label variants
 * across an `if`/`else` would give Compose two groups, so the first reading to arrive would dispose the
 * spinner and compose a fresh one — restarting its rotation exactly when the reading appears. Only the
 * [Text]'s argument and the row's content description vary, which keeps the spinner's composition
 * identity stable across the transition. There is likewise no `remember`-cached label and no
 * `derivedStateOf`: either would freeze a changing reading (the [ApiRetryIndicator] rule).
 *
 * The design-owed Figma frame draws a static glyph and a `Schemes/Primary` label; the visual here
 * follows the app's existing Material 3 progress idiom until that retune lands, unchanged by this slice.
 */
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
    progress: ThinkingProgress? = null,
) {
    if (!isThinking) return
    // Null whenever the reading must not be shown: no frame yet, or one the sanity gate declines.
    val tokens = progress?.takeIf { it.isRenderableReading() }?.estimatedTokens
    val description =
        if (tokens != null) {
            stringResource(R.string.cd_thread_thinking_progress, tokens)
        } else {
            stringResource(R.string.cd_thread_thinking)
        }
    val label =
        if (tokens != null) {
            stringResource(R.string.thread_thinking_progress_label, tokens)
        } else {
            stringResource(R.string.thread_thinking_label)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = IndicatorHorizontalPadding,
                    vertical = IndicatorVerticalPadding,
                ).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpinnerLabelGap),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(SpinnerSize),
            strokeWidth = SpinnerStrokeWidth,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

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
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStrokeWidth = 2.dp
private val SpinnerLabelGap = 8.dp

/**
 * The largest `total` this row will render a counter for. Claude's API-retry budget is single-digit in
 * practice, so 99 leaves an order of magnitude of headroom while keeping each number to two digits —
 * which is what keeps the label inside the narrow foot-of-list row at the 412dp reference width.
 * Exceeding it is not an error: the status degrades to the less-specific-but-true counter-less form.
 */
private const val MAX_PLAUSIBLE_ATTEMPTS = 99

/**
 * Foot-of-list "retrying" affordance shown while the active conversation's remote claude is stuck
 * retrying an API error (#593's `api_retry` projection, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.apiRetry]). Without it a multi-minute retry
 * storm is indistinguishable from normal reasoning — claude goes quiet on the content channel and the
 * thread keeps showing the indefinite generic spinner (#594).
 *
 * Stateless and a pure function of [status] — it holds no local state and emits nothing when not
 * retrying, mirroring [ThinkingIndicator]'s early-return idiom. The screen's precedence rule normally
 * keeps it from being called at all in that state; the early return keeps the composable total anyway,
 * the same defence-in-depth posture as its sibling.
 *
 * **No dedup or memoisation, deliberately.** [ApiRetryStatus.Attempt] is a `data class`, so a climbed
 * counter is a *different* value and recomposition follows for free (#593 made that structural equality
 * load-bearing). A `remember`-cached label, `derivedStateOf`, or a `distinctUntilChanged` upstream would
 * freeze a climbing counter.
 *
 * The status this renders is **conversation-level, not turn-scoped** — it neither opens nor closes a
 * turn, so it decorates the existing thinking affordance's slot rather than altering the turn lifecycle.
 * The design-owed Figma frame is not yet drawn (Figma 16-8 has no status-affordance treatment); the
 * visual follows the app's existing Material 3 progress idiom until it lands, exactly as
 * [ThinkingIndicator] and [StallPromotionBanner] shipped their M3 defaults.
 */
@Composable
fun ApiRetryIndicator(
    status: ApiRetryStatus,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
) {
    if (status is ApiRetryStatus.NotRetrying) return
    // Null whenever the counter must not be shown: AttemptUnknown, or an Attempt the sanity gate declines.
    val counter = (status as? ApiRetryStatus.Attempt)?.takeIf { it.isRenderableCounter() }
    val description =
        if (counter != null) {
            stringResource(
                when (agent) {
                    ConversationAgent.Claude -> R.string.cd_thread_api_retry
                    ConversationAgent.Codex -> R.string.cd_thread_api_retry_codex
                },
                counter.current,
                counter.total,
            )
        } else {
            stringResource(
                when (agent) {
                    ConversationAgent.Claude -> R.string.cd_thread_api_retry_unknown
                    ConversationAgent.Codex -> R.string.cd_thread_api_retry_unknown_codex
                },
            )
        }
    val label =
        if (counter != null) {
            stringResource(R.string.thread_api_retry_label, counter.current, counter.total)
        } else {
            stringResource(R.string.thread_api_retry_label_unknown)
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
 * Whether this counter is sane enough to put on screen — the display sanity gate (#594 AC #2), and the
 * single place the fallback to the counter-less status is decided.
 *
 * Renderable iff `current in 1..total` and `total <= `[MAX_PLAUSIBLE_ATTEMPTS]. That one condition covers
 * all three rejected shapes: the unparsed `0/0` (and any non-positive pair) fails the lower bound, an
 * incoherent `9/3` fails the upper bound, and an absurd `1/2147483647` fails the plausibility bound.
 * `current >= 1 && current <= total` already implies `total >= 1`, so `total` needs no separate lower bound.
 *
 * This is a **render-or-decline** gate, never a clamp: an unusable counter is not shown, and no server
 * value is rewritten. Clamping here would re-import at the display layer exactly the server-data rewrite
 * #593's mapper refused at the decode boundary, diverging from every sibling mapper's carry-verbatim
 * posture.
 *
 * Note that `0/0` and negatives cannot actually *arrive* as an [ApiRetryStatus.Attempt] — #593's
 * `toStatus()` already folds them to [ApiRetryStatus.AttemptUnknown]. The lower bound is defence in depth
 * against the type permitting what the mapper forbids; it re-derives nothing and adds no second mapper.
 * The real work here is the hostile-daemon case the SSOT leaves open: `current` / `total` are bounded and
 * pre-sanitized server-side, so an extreme or incoherent pair means a daemon that is buggy or hostile, and
 * declining it is plain layout safety on top of that.
 */
private fun ApiRetryStatus.Attempt.isRenderableCounter(): Boolean = current in 1..total && total <= MAX_PLAUSIBLE_ATTEMPTS

@Preview(name = "ApiRetryIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun ApiRetryIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ApiRetryIndicator(status = ApiRetryStatus.Attempt(current = 3, total = 10))
        }
    }
}

@Preview(
    name = "ApiRetryIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ApiRetryIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ApiRetryIndicator(status = ApiRetryStatus.AttemptUnknown)
        }
    }
}

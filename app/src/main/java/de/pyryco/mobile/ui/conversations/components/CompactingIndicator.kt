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
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 8.dp
private val SpinnerSize = 16.dp
private val SpinnerStrokeWidth = 2.dp
private val SpinnerLabelGap = 8.dp

/**
 * Foot-of-list "compacting" affordance shown while the active conversation's remote claude is
 * auto-compacting its context (#596's `compacting` projection, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isCompacting]). Without it a compaction is
 * indistinguishable from a hung head — claude goes silent on the content channel for tens of seconds
 * while the indefinite generic spinner keeps turning, which reads as a dead session (#597).
 *
 * Stateless and a pure function of [isCompacting] — it holds no local state and emits nothing when not
 * compacting, mirroring [ThinkingIndicator]'s early-return idiom. The screen's precedence rule normally
 * keeps it from being called at all in that state; the early return keeps the composable total anyway,
 * the same defence-in-depth posture as both its siblings.
 *
 * **Indeterminate, deliberately.** The upstream detector streams no compaction progress — the wire
 * payload is `{conversation_id, active}` and carries no counter, percent, or ETA — so an indeterminate
 * spinner is the honest rendering and a progress bar would invent data. Nothing daemon-supplied reaches
 * either string (both are literals with no format argument), so unlike [ApiRetryIndicator] there is no
 * display-sanitisation gate to clone.
 *
 * The status this renders is **conversation-level, not turn-scoped** — it neither opens nor closes a
 * turn, so it decorates the existing thinking affordance's slot rather than altering the turn lifecycle,
 * and it must show even when `turn_state` says `idle`. The design-owed Figma frame is not yet drawn
 * (Figma 16-8 has no status-affordance treatment); the visual follows the app's existing Material 3
 * progress idiom until it lands, exactly as [ThinkingIndicator], [ApiRetryIndicator] and
 * [StallPromotionBanner] shipped their M3 defaults.
 */
@Composable
fun CompactingIndicator(
    isCompacting: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isCompacting) return
    val description = stringResource(R.string.cd_thread_compacting)
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
            text = stringResource(R.string.thread_compacting_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "CompactingIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun CompactingIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            CompactingIndicator(isCompacting = true)
        }
    }
}

@Preview(
    name = "CompactingIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun CompactingIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            CompactingIndicator(isCompacting = true)
        }
    }
}

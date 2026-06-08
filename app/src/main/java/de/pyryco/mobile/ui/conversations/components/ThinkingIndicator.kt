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
 * Foot-of-list "thinking" affordance shown while the active conversation's agent is working but no
 * assistant text has appeared yet (#406's `turn_state` = `thinking` phase, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isThinking]).
 *
 * Stateless and a pure function of the hoisted [isThinking] flag — it holds no local state and emits
 * nothing when not thinking, mirroring [ConnectionBanner]'s early-return idiom. The design-owed Figma
 * frame is not yet drawn; the visual follows the app's existing Material 3 progress idiom (a small
 * indeterminate spinner) until it lands.
 */
@Composable
fun ThinkingIndicator(
    isThinking: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isThinking) return
    val description = stringResource(R.string.cd_thread_thinking)
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
            text = stringResource(R.string.thread_thinking_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(name = "ThinkingIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun ThinkingIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ThinkingIndicator(isThinking = true)
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

package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

private val AffordanceHorizontalPadding = 16.dp
private val AffordanceVerticalPadding = 8.dp

/**
 * Foot-of-list "interrupt the running turn" affordance (#459) shown while the active conversation's
 * agent is in flight — its `thinking` **or** `responding` phase, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isBusy]. Tapping it calls the
 * already-built interrupt-send action ([de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.onInterrupt],
 * #458); the control disappears when the turn ends (the busy flag clears) with no further user action.
 *
 * Stateless and a pure function of the hoisted [isBusy] flag plus the [onInterrupt] callback — it holds
 * no local state and emits nothing when no turn is running, mirroring [ThinkingIndicator]'s early-return
 * idiom. The design-owed Figma frame does not yet draw a dedicated interrupt control; the visual follows
 * the app's existing Material 3 idiom (a small tappable button with a stop glyph + label) until it lands.
 */
@Composable
fun InterruptAffordance(
    isBusy: Boolean,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isBusy) return
    val description = stringResource(R.string.cd_thread_interrupt)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = AffordanceHorizontalPadding,
                    vertical = AffordanceVerticalPadding,
                ),
        horizontalArrangement = Arrangement.Center,
    ) {
        FilledTonalButton(
            onClick = onInterrupt,
            // The button carries the click action and the merged-descendants contentDescription, so the
            // screen test (and a screen reader) locates the control by description, mirroring ThinkingIndicator.
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
        ) {
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
            Text(text = stringResource(R.string.thread_interrupt_label))
        }
    }
}

@Preview(name = "InterruptAffordance — Light", showBackground = true, widthDp = 412)
@Composable
private fun InterruptAffordanceLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            InterruptAffordance(isBusy = true, onInterrupt = {})
        }
    }
}

@Preview(
    name = "InterruptAffordance — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun InterruptAffordanceDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            InterruptAffordance(isBusy = true, onInterrupt = {})
        }
    }
}

package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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

private val BannerVerticalPadding = 12.dp
private val BannerHorizontalPadding = 16.dp
private val BannerRowGap = 4.dp

/**
 * Prominent safe-degrade affordance shown while the active conversation is stalled (#395's
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.isStalled]). When the remote claude
 * stops making forward progress (structured parsing degraded), this banner raises the prominence of
 * the always-available screen-snapshot action (pyrycode ADR-025 § Safe degradation) so it reads as
 * the recommended next step — guiding the user to the parser-independent live view instead of
 * staring at silence (#396).
 *
 * Stateless and a pure function of the hoisted [isStalled] flag — it holds no local state and emits
 * nothing when not stalled, mirroring [ConnectionBanner]'s early-return idiom. The whole banner is a
 * clickable [Surface] whose [onShowLiteralScreen] is the **same** navigation the overflow "Show the
 * literal screen" item triggers — no new data path (#396 AC #4).
 *
 * Styling default (design-owed): `tertiaryContainer` / `onTertiaryContainer` — prominent and
 * attention-drawing, but deliberately not the error-red of [ConnectionBanner]'s offline state (a
 * stall is a degrade fallback, not an error). The Figma 16-8 frame has no stall treatment drawn yet;
 * the visual follows the app's Material 3 banner idiom until it lands, exactly as [ThinkingIndicator]
 * shipped its M3 default.
 */
@Composable
fun StallPromotionBanner(
    isStalled: Boolean,
    onShowLiteralScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isStalled) return
    val description = stringResource(R.string.cd_thread_stall_promotion)
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onShowLiteralScreen)
                .semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = BannerHorizontalPadding,
                    vertical = BannerVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(BannerRowGap),
        ) {
            Text(
                text = stringResource(R.string.thread_stall_promotion_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.thread_overflow_show_literal_screen),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Preview(name = "StallPromotionBanner — Light", showBackground = true, widthDp = 412)
@Composable
private fun StallPromotionBannerLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            StallPromotionBanner(isStalled = true, onShowLiteralScreen = {})
        }
    }
}

@Preview(
    name = "StallPromotionBanner — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun StallPromotionBannerDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            StallPromotionBanner(isStalled = true, onShowLiteralScreen = {})
        }
    }
}

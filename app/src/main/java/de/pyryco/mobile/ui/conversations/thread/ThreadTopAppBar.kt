package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.components.defaultChromeShadow
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.threadColors

// Figma's `Top bar`: a 24dp back vector, title, 6 × 24dp overflow vector and a 1dp inset rule.
// The overflow vector is centred in its 24dp design slot. Both glyphs keep 48dp tap targets; the
// visible paths, not the targets, determine the 20dp gutter and vertical positions.
// Internal since #1027: the markdown reader's bar is this bar without the overflow.
internal val BarGlyphSize = 24.dp
internal val BarTouchSize = 48.dp
internal val BarTouchSlack = (BarTouchSize - BarGlyphSize) / 2
internal val BarGutter = 20.dp

// The thread aligns its 28dp title row at 24dp; the reader keeps its established touch geometry.
internal val BarTopGap = 24.dp - BarTouchSlack
private val ThreadBarTopGap = 24.dp - (BarTouchSize - 28.dp) / 2
private val ThreadRuleGap = 16.dp - (BarTouchSize - 28.dp) / 2
internal val BarRuleGap = 16.dp - BarTouchSlack

// The reader's bar only: the thread's message area starts at its rule (#1562).
internal val BarBottomGap = 16.dp
internal const val BAR_RULE_ALPHA = 0.60f

/**
 * The thread's own bar (#643): back control, conversation title, overflow entry, and the rule that
 * closes the bar — the Figma `16:8` `Top bar` frame, replacing the stock `TopAppBar` this screen drew
 * until now, exactly as [de.pyryco.mobile.ui.conversations.list.ChannelListScreen]'s own bar replaced
 * its own.
 *
 * No window insets of its own, unlike the `TopAppBar` it replaces: the outer `Scaffold` in
 * `MainActivity` declares neither bar slot, so the `innerPadding` it hands `PyryNavHost` is its whole
 * `contentWindowInsets` and every destination is already padded past the system bars. The stock bar's
 * `TopAppBarDefaults.windowInsets` was applying a second status-bar inset on top of that.
 *
 * The title takes the slot between the two controls and truncates inside it, so an over-long display
 * name can never overlap or cover either control. Its `clickable` + `Role.Button` semantics ride on
 * the `Text` so the ripple tracks the visible text rather than the whole slot, and the overflow reports
 * its live button bounds to the screen-owned overlay.
 */
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    modifier: Modifier = Modifier,
    onOverflowAnchorChanged: (Rect) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = BarGutter - BarTouchSlack,
                        end = BarGutter - BarTouchSlack,
                        top = ThreadBarTopGap,
                    ).defaultChromeShadow(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(BarTouchSize)) {
                Icon(
                    painter = painterResource(R.drawable.ic_thread_back),
                    contentDescription = stringResource(R.string.cd_back),
                    modifier = Modifier.size(BarGlyphSize).offset(y = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = title,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(onClick = onTitleClick)
                        .semantics { role = Role.Button },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = onOverflowClick,
                modifier = Modifier.size(BarTouchSize).onGloballyPositioned { onOverflowAnchorChanged(it.boundsInWindow()) },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_thread_overflow),
                    contentDescription = stringResource(R.string.cd_more_actions),
                    modifier = Modifier.size(width = 6.dp, height = BarGlyphSize).offset(y = (-2).dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(
            modifier =
                Modifier.padding(
                    start = BarGutter,
                    end = BarGutter,
                    top = ThreadRuleGap,
                ),
            color =
                MaterialTheme.colorScheme.threadColors.headerRule
                    .copy(alpha = BAR_RULE_ALPHA),
        )
    }
}

@Preview(name = "ThreadTopAppBar — Light", showBackground = true, widthDp = 412)
@Composable
private fun ThreadTopAppBarLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadTopAppBar(
            title = "pyrycode discord integration",
            onBack = {},
            onTitleClick = {},
            onOverflowClick = {},
        )
    }
}

@Preview(name = "ThreadTopAppBar — Dark, long title", showBackground = true, widthDp = 412)
@Composable
private fun ThreadTopAppBarDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadTopAppBar(
            title = "a channel display name long enough to overrun the title slot",
            onBack = {},
            onTitleClick = {},
            onOverflowClick = {},
        )
    }
}

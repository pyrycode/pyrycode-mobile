package de.pyryco.mobile.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The back-and-title header shared by Scanner (13:2), Scanner — Denied (32:2) and Pair Screen (533:2147).
 * Placed at the status-bar inset edge, the 48 dp row starts 14 dp down so the centred 28 dp title line box
 * starts 24 dp below the bar, and the optional divider sits 44 dp below the title top, as all three frames draw.
 */
@Composable
internal fun PairingHeader(
    title: String,
    titleColor: Color,
    onBack: () -> Unit,
    backIcon: Painter,
    modifier: Modifier = Modifier,
    startPadding: Dp = 8.dp,
    backEnabled: Boolean = true,
    divider: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = startPadding, end = 20.dp, top = 14.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(48.dp)) {
                Icon(backIcon, contentDescription = "Back")
            }
            Text(
                text = title,
                modifier = Modifier.testTag("pairing_header_title"),
                // Keep the full 28 dp line box, glyphs centred, as Figma draws the title; the default trim
                // would shrink a single line to the font's own height.
                style = MaterialTheme.typography.titleLarge.copy(lineHeightStyle = TitleLineBox),
                color = titleColor,
            )
        }
        if (divider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp).testTag("pairing_header_divider"),
                color = MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.6f),
            )
        }
    }
}

private val TitleLineBox = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

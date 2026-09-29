package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

// Figma 347:6617's `Pill`: a 6dp radius, 8/4 padding and an 8dp gap before the X.
private val PillRadius = 6.dp
private val PillHorizontalPadding = 8.dp
private val PillVerticalPadding = 4.dp
private val PillGap = 8.dp

// Figma 541:2188 is an 8dp X. The drawable is rendered from its exported vector at 4x resolution.
private val DismissIconSize = 8.dp

// Figma 541:2446's overlay drop shadow (0, 6, blur 4), drawn per pill.
private val PillShadow = 4.dp

/**
 * Shared thread pill, Figma `347:6617`: hugs its right-aligned label and wraps it when it would be wider
 * than the space it is given. The Top overlay uses it for notices; turn outcomes use its error treatment
 * inside the input status area with a leading icon and a two-line limit.
 *
 * The **Default** variant (`primaryContainer` / `onPrimaryContainer`) is a notice the operator may hide
 * and carries a trailing X when [onDismiss] is set. The **Error** variant ([isError], `errorContainer` /
 * `error`, as Figma paints it) never does. [onClick] makes the whole pill a button. The pill's merged
 * content description is [contentDescription], its visible label by default; the X is its own button.
 * [shadowElevation] is the overlay's drop shadow; a pill laid out in the page, not over it, passes none.
 */
@Composable
internal fun NoticePill(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    shadowElevation: Dp = PillShadow,
    leadingIcon: ImageVector? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
    val body: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = PillHorizontalPadding, vertical = PillVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PillGap),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (onDismiss != null) {
                Image(
                    painter = painterResource(R.drawable.ic_notice_pill_close),
                    contentDescription = stringResource(R.string.thread_notice_dismiss),
                    modifier =
                        Modifier
                            .size(DismissIconSize)
                            .clickable(role = Role.Button, onClick = onDismiss),
                    colorFilter = ColorFilter.tint(content),
                )
            }
        }
    }
    val described = modifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
    val shape = RoundedCornerShape(PillRadius)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = described,
            shape = shape,
            color = container,
            contentColor = content,
            shadowElevation = shadowElevation,
            content = body,
        )
    } else {
        Surface(
            modifier = described,
            shape = shape,
            color = container,
            contentColor = content,
            shadowElevation = shadowElevation,
            content = body,
        )
    }
}

@Composable
private fun NoticePillPreviewContent() {
    Column(
        modifier = Modifier.padding(16.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NoticePill(text = "Claude reports usage-limit status: allowed_warning · 94% spent", isError = false, onDismiss = {})
        NoticePill(text = "Pairing error - Re-pair", isError = true, onClick = {})
    }
}

@Preview(name = "NoticePill — Light", showBackground = true, widthDp = 412)
@Composable
private fun NoticePillLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { NoticePillPreviewContent() }
    }
}

@Preview(name = "NoticePill — Dark", showBackground = true, widthDp = 412, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun NoticePillDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { NoticePillPreviewContent() }
    }
}

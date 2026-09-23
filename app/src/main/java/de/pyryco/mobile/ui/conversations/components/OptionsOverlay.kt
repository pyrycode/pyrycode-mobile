package de.pyryco.mobile.ui.conversations.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlin.math.roundToInt

/** One row of an [OptionsOverlay]: [value] is handed back verbatim on selection and never rendered;
 *  [label] is the only text drawn. */
data class OptionsOverlayOption(
    val value: String,
    val label: String,
)

// Figma 533:1958's `Options overlay`: a 6dp-rounded column with 2dp of vertical padding, its rows inset
// 12dp. The rows' vertical padding is 10dp rather than the design's 6dp so each row is a thumb-sized
// target on a phone (36dp tall instead of 28dp).
private val OverlayShape = RoundedCornerShape(6.dp)
private val OverlayVerticalPadding = 2.dp
private val OptionHorizontalPadding = 12.dp
private val OptionVerticalPadding = 10.dp
private val OverlayMinWidth = 80.dp
private val OverlayMaxWidth = 240.dp

// Air between the overlay's foot and the anchor's top, and the overlay's minimum distance from the
// layer's edges.
private val OverlayAnchorGap = 4.dp
private val OverlayEdgeMargin = 8.dp

/**
 * Figma `533:1958`'s `Options overlay` (#808): a compact popup of options that opens above the control
 * that anchors it.
 *
 * **A full-size layer in the caller's own window, not a `Popup`.** A focusable popup takes the input
 * field's focus and drops the keyboard. A non-focusable one lets the tap that dismisses it fall through
 * to the composer. Here a transparent scrim covers the whole layer. It is the topmost node under any tap
 * outside the options, so it takes the dismissing tap and nothing beneath it sees the tap. [anchor] is in
 * this layer's coordinates. The caller reads it from the live bounds of the anchoring control, so the
 * overlay follows the composer's keyboard lift. The option column's height is capped at the space
 * above the anchor, and it scrolls when there are more options than fit.
 *
 * **Colour deviation from the design.** The design fills unselected rows with `Schemes/On Primary Fixed`,
 * and `Theme.kt` defines no `*Fixed` roles. The surface is `surfaceContainerLowest`, which is dark in the
 * dark theme, like the design's fill. The selected row is `primaryContainer`, so it stays the lighter row
 * in both themes. A small shadow separates the surface from the light-theme background.
 *
 * Every [OptionsOverlayOption.label] may be daemon-authored. Labels are drawn through [Text] only, one
 * line, ellipsized. [notListed] > 0 adds a caption that marks the list as a subset, so a cut menu never
 * reads as complete.
 */
@Composable
fun OptionsOverlay(
    options: List<OptionsOverlayOption>,
    selectedValue: String,
    notListed: Int,
    anchor: Rect,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    BackHandler(onBack = { currentOnDismiss() })
    val dismissDescription = stringResource(R.string.cd_options_overlay_dismiss)
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { currentOnDismiss() }) }
                    .semantics {
                        contentDescription = dismissDescription
                        onClick {
                            currentOnDismiss()
                            true
                        }
                    },
        )
        AnchoredAbove(anchor = anchor) {
            OptionsColumn(
                options = options,
                selectedValue = selectedValue,
                notListed = notListed,
                onSelect = onSelect,
            )
        }
    }
}

/** Places its single child with the child's foot [OverlayAnchorGap] above [anchor]'s top. The child's
 *  start aligns its row text with the anchor's own text, clamped inside [OverlayEdgeMargin]. */
@Composable
private fun AnchoredAbove(
    anchor: Rect,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = OverlayEdgeMargin.roundToPx()
        val bottom = (anchor.top.roundToInt() - OverlayAnchorGap.roundToPx()).coerceIn(0, constraints.maxHeight)
        val placeable =
            measurables.single().measure(
                Constraints(
                    maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0),
                    maxHeight = (bottom - margin).coerceAtLeast(0),
                ),
            )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x =
                (anchor.left.roundToInt() - OptionHorizontalPadding.roundToPx())
                    .coerceAtMost(constraints.maxWidth - margin - placeable.width)
                    .coerceAtLeast(margin)
            placeable.place(x, bottom - placeable.height)
        }
    }
}

@Composable
private fun OptionsColumn(
    options: List<OptionsOverlayOption>,
    selectedValue: String,
    notListed: Int,
    onSelect: (String) -> Unit,
) {
    Surface(
        shape = OverlayShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = OverlayMinWidth, max = OverlayMaxWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = OverlayVerticalPadding)
                    .selectableGroup(),
        ) {
            options.forEach { option ->
                val selected = option.value == selectedValue
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option.value) },
                            ).padding(horizontal = OptionHorizontalPadding, vertical = OptionVerticalPadding),
                )
            }
            if (notListed > 0) {
                Text(
                    text = stringResource(R.string.thread_options_not_listed, notListed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = OptionHorizontalPadding, vertical = OptionVerticalPadding),
                )
            }
        }
    }
}

@Preview(name = "OptionsOverlay — Dark", showBackground = true, widthDp = 200, heightDp = 260)
@Composable
private fun OptionsOverlayDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        OptionsOverlayPreviewContent()
    }
}

@Preview(name = "OptionsOverlay — Light", showBackground = true, widthDp = 200, heightDp = 260)
@Composable
private fun OptionsOverlayLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        OptionsOverlayPreviewContent()
    }
}

@Composable
private fun OptionsOverlayPreviewContent() {
    OptionsOverlay(
        options = listOf("low", "medium", "high", "xhigh", "max").map { OptionsOverlayOption(it, it) },
        selectedValue = "high",
        notListed = 0,
        anchor = Rect(left = 40f, top = 600f, right = 80f, bottom = 640f),
        onSelect = {},
        onDismiss = {},
    )
}

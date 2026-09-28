package de.pyryco.mobile.ui.conversations.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
 *  [label] is the row's one-line text. A row that is not [enabled] is greyed out and inert (#884).
 *  [detail], when present, is drawn under the label in at most [DETAIL_MAX_LINES] lines (#885). */
data class OptionsOverlayOption(
    val value: String,
    val label: String,
    val enabled: Boolean = true,
    val detail: String? = null,
)

// Figma 533:1958's `Options overlay`: a 6dp-rounded column with 2dp of vertical padding;
// each 28dp row has 12dp horizontal and 6dp vertical padding.
private val OverlayShape = RoundedCornerShape(6.dp)
private val OverlayVerticalPadding = 2.dp
private val OptionHorizontalPadding = 12.dp
private val OptionVerticalPadding = 6.dp
private val OptionMinHeight = 28.dp
private val OverlayMinWidth = 80.dp
private val OverlayMaxWidth = 240.dp

// Air between the overlay's foot and the anchor's top, and the overlay's minimum distance from the
// layer's edges.
private val OverlayAnchorGap = 4.dp
private val OverlayEdgeMargin = 8.dp

// Material 3's content alpha for a disabled control.
private const val DISABLED_ALPHA = 0.38f

// A row's secondary line is bounded in height, whatever its source text holds (#885).
private const val DETAIL_MAX_LINES = 2

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
 * The design's `Schemes/On Primary` fills the surface and selected row, with
 * `Schemes/On Primary Fixed` on unselected rows in each theme mode.
 *
 * Every [OptionsOverlayOption.label] and [OptionsOverlayOption.detail] may be daemon-authored. They are
 * drawn through [Text] only: the label in one line, the detail in at most two, both ellipsized. [notListed] > 0 adds a caption that marks the list as a subset, so a cut menu never
 * reads as complete.
 *
 * [actions] draws the rows as buttons rather than a radio group (#884): a menu of actions has no
 * selected value, so no row is highlighted and none announces a selection.
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
    actions: Boolean = false,
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
                actions = actions,
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
    actions: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = OverlayShape,
        color = colors.onPrimary,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = OverlayMinWidth, max = OverlayMaxWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = OverlayVerticalPadding)
                    .then(if (actions) Modifier else Modifier.selectableGroup()),
        ) {
            options.forEach { option ->
                val selected = !actions && option.value == selectedValue
                val onClick = { onSelect(option.value) }
                val rowModifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OptionMinHeight)
                        .background(if (selected) colors.onPrimary else colors.onPrimaryFixed)
                        .then(
                            if (actions) {
                                Modifier.clickable(enabled = option.enabled, role = Role.Button, onClick = onClick)
                            } else {
                                Modifier.selectable(
                                    selected = selected,
                                    enabled = option.enabled,
                                    role = Role.RadioButton,
                                    onClick = onClick,
                                )
                            },
                        ).padding(horizontal = OptionHorizontalPadding, vertical = OptionVerticalPadding)
                val label =
                    @Composable { labelModifier: Modifier ->
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                if (option.enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = labelModifier,
                        )
                    }
                val detail = option.detail
                if (detail == null) {
                    label(rowModifier)
                } else {
                    Column(modifier = rowModifier) {
                        label(Modifier)
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = DETAIL_MAX_LINES,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

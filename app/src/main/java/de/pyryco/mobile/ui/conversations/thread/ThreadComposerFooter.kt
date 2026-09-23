package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

/**
 * The composer footer's option controls (#808). The permission-mode control (#650) and the Actions
 * control (#655) join as further entries, each with a [footerMenu] branch.
 */
enum class FooterControl { Model, Effort }

/**
 * What a footer control offers when its overlay opens. [notListed] is how many entries the list leaves
 * out. It is never derived from the length of [options].
 */
data class FooterMenu(
    val options: List<OptionsOverlayOption>,
    val selectedValue: String,
    val notListed: Int,
)

/**
 * The menu [control] offers under [runConfig], or `null` when it has nothing to offer: no published
 * model menu, or a selected model row that publishes no effort levels.
 *
 * The model list's [FooterMenu.notListed] is the producer's `droppedModels` plus this client's
 * `hiddenChoices`, summed for display exactly as the Status sheet sums them. Each figure keeps its own
 * field on [ThreadRunConfig], and neither is recomputed from the row count.
 */
internal fun footerMenu(
    control: FooterControl,
    runConfig: ThreadRunConfig,
): FooterMenu? =
    when (control) {
        FooterControl.Model ->
            runConfig.choices.takeIf { runConfig.menuAvailable && it.isNotEmpty() }?.let { choices ->
                FooterMenu(
                    options = choices.map { OptionsOverlayOption(value = it.value, label = it.label) },
                    selectedValue = runConfig.selectedModel,
                    notListed = runConfig.droppedModels + runConfig.hiddenChoices,
                )
            }
        FooterControl.Effort ->
            runConfig.effortChoices.takeIf { it.isNotEmpty() }?.let { levels ->
                FooterMenu(
                    options = levels.map { OptionsOverlayOption(value = it.value, label = it.label) },
                    selectedValue = runConfig.selectedEffort,
                    notListed = 0,
                )
            }
    }

/**
 * Whether [control]'s button opens its overlay. This mirrors the Status sheet's `enabled && !pending`:
 * no write while one is outstanding, and none without a session to address. It adds one rule of its
 * own: a control with nothing to offer does not open an empty overlay.
 */
internal fun footerControlEnabled(
    control: FooterControl,
    runConfig: ThreadRunConfig,
): Boolean = runConfig.writable && !runConfig.pending && footerMenu(control, runConfig) != null

// Figma 16:8's `Input footer` (110:3494): a 16dp inset inside the composer gutter and a 16dp gap between
// buttons. Each button is a body-small label plus a small up-chevron at a 4dp gap. The design's buttons
// are 16dp tall. Here each is at least 32dp tall so a thumb can hit it.
private val FooterHorizontalPadding = 16.dp
private val FooterButtonGap = 16.dp
private val FooterButtonMinHeight = 32.dp
private val FooterChevronGap = 4.dp
private val FooterChevronSize = 14.dp
private val FooterLabelMaxWidth = 140.dp
private val StatusOpenerSize = 32.dp
private val StatusOpenerIconSize = 16.dp
private const val PENDING_ALPHA = 0.55f

/**
 * Figma `16:8`'s `Input footer` (#808): the model and effort buttons under the input field, followed by
 * the Status sheet opener.
 *
 * It replaces #602's single monospace `model · effort` line (`ThreadStatusRow`). The design has no
 * footer affordance for the Status sheet. The sheet is the only home of the YOLO toggle, so a trailing
 * icon keeps it one tap away. The design's `Actions` (#655), permission-mode (#650), `Cxt:` (#591) and
 * attachment segments belong to other tickets.
 *
 * Stateless. Each button shows [ThreadRunConfig.modelLabel] / [ThreadRunConfig.effortLabel]. Those labels
 * already read the pending tap first and the confirmed reading after it. When the ViewModel clears a
 * refused write, the button returns to its earlier value with no footer logic. A button with an
 * outstanding tap is dimmed and says so in its state description. [onAnchorChanged] reports each
 * button's window bounds, which the screen uses to place the overlay above it.
 */
@Composable
fun ThreadComposerFooter(
    runConfig: ThreadRunConfig,
    onOpen: (FooterControl) -> Unit,
    onStatusClick: () -> Unit,
    onAnchorChanged: (FooterControl, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = FooterHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(FooterButtonGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterButton(
            label = runConfig.modelLabel,
            clickLabel = stringResource(R.string.thread_footer_change_model),
            enabled = footerControlEnabled(FooterControl.Model, runConfig),
            pending = runConfig.pendingModel != null,
            onClick = { onOpen(FooterControl.Model) },
            onBounds = { onAnchorChanged(FooterControl.Model, it) },
        )
        FooterButton(
            label = runConfig.effortLabel,
            clickLabel = stringResource(R.string.thread_footer_change_effort),
            enabled = footerControlEnabled(FooterControl.Effort, runConfig),
            pending = runConfig.pendingEffort != null,
            onClick = { onOpen(FooterControl.Effort) },
            onBounds = { onAnchorChanged(FooterControl.Effort, it) },
        )
        Spacer(modifier = Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(StatusOpenerSize)
                    .clickable(role = Role.Button, onClick = onStatusClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.cd_thread_status_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(StatusOpenerIconSize),
            )
        }
    }
}

/** Figma's `Input footer button`. A disabled button still shows its value, without the chevron that
 *  promises a menu. */
@Composable
private fun FooterButton(
    label: String,
    clickLabel: String,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val pendingDescription = stringResource(R.string.thread_footer_pending)
    val color = if (enabled || pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .heightIn(min = FooterButtonMinHeight)
                .onGloballyPositioned { onBounds(it.boundsInWindow()) }
                .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    if (pending) stateDescription = pendingDescription
                }.alpha(if (pending) PENDING_ALPHA else 1f),
        horizontalArrangement = Arrangement.spacedBy(FooterChevronGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FooterLabelMaxWidth),
        )
        if (enabled || pending) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(FooterChevronSize),
            )
        }
    }
}

private val previewRunConfig =
    ThreadRunConfig(
        choices =
            listOf(
                ThreadModelChoice(
                    value = "opus",
                    label = "Opus 4.7",
                    detail = "",
                    effortChoices = listOf(ThreadEffortChoice("high", "high"), ThreadEffortChoice("max", "max")),
                ),
            ),
        menuAvailable = true,
        settingsAvailable = true,
        savedModel = "opus",
        savedEffort = "max",
        sessionId = "s1",
    )

@Preview(name = "ComposerFooter — Dark", showBackground = true, widthDp = 372)
@Composable
private fun ThreadComposerFooterDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ThreadComposerFooter(runConfig = previewRunConfig, onOpen = {}, onStatusClick = {}, onAnchorChanged = { _, _ -> })
    }
}

@Preview(name = "ComposerFooter — Light, pending", showBackground = true, widthDp = 372)
@Composable
private fun ThreadComposerFooterLightPendingPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ThreadComposerFooter(
            runConfig = previewRunConfig.copy(pendingEffort = "high"),
            onOpen = {},
            onStatusClick = {},
            onAnchorChanged = { _, _ -> },
        )
    }
}

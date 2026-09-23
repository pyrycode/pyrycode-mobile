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
 * The composer footer's option controls (#808). [Permission] joined at #650; the Actions control (#655)
 * joins as a further entry, with its own [footerMenu] branch.
 */
enum class FooterControl { Model, Effort, Permission }

/**
 * The six permission modes the footer names (#650), in menu order, with desktop #1546's labels.
 *
 * [wire] is what a reading reports and what a write sends. `bypassPermissions` is never sent as a mode:
 * the daemon accepts it only as `yolo: true`. A label describes a posture and grants nothing — Manual
 * approval still applies the allow rules already in place.
 */
internal enum class PermissionModeOption(
    val wire: String,
    val label: String,
) {
    Default("default", "Manual approval"),
    AcceptEdits("acceptEdits", "Auto-approve edits"),
    Auto("auto", "Auto approval"),
    Plan("plan", "Plan"),
    DontAsk("dontAsk", "Approved actions only"),
    Bypass("bypassPermissions", "Bypass approvals"),
    ;

    companion object {
        fun fromWire(value: String): PermissionModeOption? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The permission button's label (#650), or `null` when the button is hidden: no reading, or a reading of
 * `""`, which means the current child has confirmed nothing. A known mode shows its fixed label. Any other
 * value is daemon-authored and shows only as [inert] text.
 */
internal fun permissionModeLabel(runConfig: ThreadRunConfig): String? =
    runConfig.permissionMode.takeIf { it.isNotEmpty() }?.let { PermissionModeOption.fromWire(it)?.label ?: it.inert() }

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
 * model menu, a selected model row that publishes no effort levels, or no confirmed permission mode.
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
        // #650: all six modes, Auto approval only when the selected row supports it. The selection is the
        // confirmed reading, so an unrecognised value selects nothing and is never offered.
        FooterControl.Permission ->
            runConfig.permissionMode.takeIf { it.isNotEmpty() }?.let { confirmed ->
                val autoSupported = runConfig.selectedChoice?.supportsAutoMode == true
                FooterMenu(
                    options =
                        PermissionModeOption.entries
                            .filter { it != PermissionModeOption.Auto || autoSupported }
                            .map { OptionsOverlayOption(value = it.wire, label = it.label) },
                    selectedValue = confirmed,
                    notListed = 0,
                )
            }
    }

/**
 * Whether [control]'s button opens its overlay. This mirrors the Status sheet's `enabled && !pending`:
 * no write while one is outstanding, and none without a session to address. It adds one rule of its
 * own: a control with nothing to offer does not open an empty overlay. The permission control has its
 * own outstanding write, so a model or effort tap does not block it and it does not block them.
 */
internal fun footerControlEnabled(
    control: FooterControl,
    runConfig: ThreadRunConfig,
): Boolean {
    val outstanding = if (control == FooterControl.Permission) runConfig.pendingPermission != null else runConfig.pending
    return runConfig.writable && !outstanding && footerMenu(control, runConfig) != null
}

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
 * Figma `16:8`'s `Input footer` (#808): the permission, model and effort buttons under the input field,
 * followed by the Status sheet opener.
 *
 * It replaces #602's single monospace `model · effort` line (`ThreadStatusRow`). The design has no
 * footer affordance for the Status sheet, so a trailing icon keeps it one tap away. The design's
 * `Actions` (#655), `Cxt:` (#591) and attachment segments belong to other tickets.
 *
 * Stateless. The model and effort buttons show [ThreadRunConfig.modelLabel] / [ThreadRunConfig.effortLabel].
 * Those labels read the pending tap first and the confirmed reading after it. When the ViewModel clears a
 * refused write, the button returns to its earlier value with no footer logic. The permission button
 * (#650) is different: it shows only [permissionModeLabel], the confirmed reading, and is absent when
 * there is none. Its outstanding write only dims it. A button with an outstanding tap is dimmed and says
 * so in its state description. [onAnchorChanged] reports each button's window bounds, which the screen
 * uses to place the overlay above it.
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
        permissionModeLabel(runConfig)?.let { label ->
            FooterButton(
                label = label,
                clickLabel = stringResource(R.string.thread_footer_change_permission),
                enabled = footerControlEnabled(FooterControl.Permission, runConfig),
                pending = runConfig.pendingPermission != null,
                onClick = { onOpen(FooterControl.Permission) },
                onBounds = { onAnchorChanged(FooterControl.Permission, it) },
            )
        }
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
        permissionMode = "plan",
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
            runConfig = previewRunConfig.copy(pendingEffort = "high", pendingPermission = "default"),
            onOpen = {},
            onStatusClick = {},
            onAnchorChanged = { _, _ -> },
        )
    }
}

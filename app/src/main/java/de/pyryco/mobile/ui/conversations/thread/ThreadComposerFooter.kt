package de.pyryco.mobile.ui.conversations.thread

import androidx.annotation.StringRes
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
 * The composer footer's option controls (#808). [Permission] joined at #650 and [Actions] at #884, each
 * with its own [footerMenu] branch.
 */
enum class FooterControl { Model, Effort, Permission, Actions }

/**
 * The Actions menu's rows (#884), in menu order, after desktop's `ComposerActionsMenu`. Every field is a
 * client-owned constant: nothing a server publishes is ever shown, handed back or sent through this menu.
 *
 * [value] identifies the row in the overlay. [command] is the text a command row sends as an ordinary
 * message; [ResetSession] has none, because it runs the overflow menu's Reset session path instead, and
 * [BackgroundTasks] (#678) has none because it only opens the read-only task panel.
 */
enum class ComposerAction(
    val value: String,
    val label: String,
    val command: String?,
) {
    ResetSession("reset", "Reset session", null),
    CompactSession("compact", "Compact session", "/compact"),
    KnowledgeCapture("knowledge-capture", "Knowledge capture", "/knowledge-capture"),
    BackgroundTasks("background-tasks", "Background tasks", null),
    ;

    companion object {
        fun fromValue(value: String): ComposerAction? = entries.firstOrNull { it.value == value }
    }
}

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

/** The client-owned explanation for [EffortNote] (#889). Never daemon text. */
@StringRes
internal fun EffortNote.textRes(): Int =
    when (this) {
        EffortNote.SelectedRunningUnavailable -> R.string.thread_effort_note_selected_unavailable
        EffortNote.DefaultRunningUnavailable -> R.string.thread_effort_note_default_unavailable
        EffortNote.NotReported -> R.string.thread_effort_note_not_reported
    }

/**
 * What a footer control offers when its overlay opens. [notListed] is how many entries the list leaves
 * out. It is never derived from the length of [options]. [actions] marks a menu of actions rather than a
 * choice of one value (#884), so the overlay draws buttons instead of a radio group.
 */
data class FooterMenu(
    val options: List<OptionsOverlayOption>,
    val selectedValue: String,
    val notListed: Int,
    val actions: Boolean = false,
)

/**
 * The menu [control] offers under [runConfig], or `null` when it has nothing to offer: no published
 * model menu, a selected model row that publishes no effort levels, or no confirmed permission mode.
 *
 * The model list's [FooterMenu.notListed] is the producer's `droppedModels` plus this client's
 * `hiddenChoices`, summed for display exactly as the Status sheet sums them. Each figure keeps its own
 * field on [ThreadRunConfig], and neither is recomputed from the row count.
 *
 * The Actions menu (#884) reads only [mutationsSupported], which gates Reset session exactly as it gates
 * the overflow menu's item, and [absentActions], the commands the published menu proves absent, which are
 * greyed out. It is never `null`. The background-tasks row (#678) carries [backgroundTaskCount], the open
 * conversation's live count, in its label: a number, never a task string.
 */
internal fun footerMenu(
    control: FooterControl,
    runConfig: ThreadRunConfig,
    mutationsSupported: Boolean = true,
    absentActions: Set<ComposerAction> = emptySet(),
    backgroundTaskCount: Int = 0,
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
        FooterControl.Actions ->
            FooterMenu(
                options =
                    ComposerAction.entries
                        .filter { it != ComposerAction.ResetSession || mutationsSupported }
                        .map {
                            val label = if (it == ComposerAction.BackgroundTasks) "${it.label} ($backgroundTaskCount)" else it.label
                            OptionsOverlayOption(value = it.value, label = label, enabled = it !in absentActions)
                        },
                selectedValue = "",
                notListed = 0,
                actions = true,
            )
    }

/**
 * Whether [control]'s button opens its overlay. This mirrors the Status sheet's `enabled && !pending`:
 * no write while one is outstanding, and none without a session to address. It adds one rule of its
 * own: a control with nothing to offer does not open an empty overlay. The permission control has its
 * own outstanding write, so a model or effort tap does not block it and it does not block them. The
 * Actions control (#884) is always enabled: a command send addresses no session and writes no setting.
 */
internal fun footerControlEnabled(
    control: FooterControl,
    runConfig: ThreadRunConfig,
): Boolean {
    if (control == FooterControl.Actions) return true
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
 * Figma `16:8`'s `Input footer` (#808): the actions, permission, model and effort buttons under the input
 * field, followed by the Status sheet opener.
 *
 * It replaces #602's single monospace `model · effort` line (`ThreadStatusRow`). The design has no
 * footer affordance for the Status sheet, so a trailing icon keeps it one tap away. The design's
 * `Cxt:` (#591) and attachment segments belong to other tickets. The Actions button (#884) leads the row,
 * as in the design, and always opens its menu.
 *
 * Stateless. The model and effort buttons show [ThreadRunConfig.modelLabel] / [ThreadRunConfig.effortLabel].
 * Those labels read the pending tap first and the confirmed reading after it; for effort that reading is
 * Claude's applied value, with the saved choice only as an explained fallback (#889). When the ViewModel clears a
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
        FooterButton(
            label = stringResource(R.string.thread_footer_actions),
            clickLabel = stringResource(R.string.thread_footer_open_actions),
            enabled = true,
            pending = false,
            onClick = { onOpen(FooterControl.Actions) },
            onBounds = { onAnchorChanged(FooterControl.Actions, it) },
        )
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
            note = runConfig.effortNote?.let { stringResource(it.textRes()) },
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
 *  promises a menu. [note] explains the value in the state description when no write is pending (#889). */
@Composable
private fun FooterButton(
    label: String,
    clickLabel: String,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
    note: String? = null,
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
                    (if (pending) pendingDescription else note)?.let { stateDescription = it }
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

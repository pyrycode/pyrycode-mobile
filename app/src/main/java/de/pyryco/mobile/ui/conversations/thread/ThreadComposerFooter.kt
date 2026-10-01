package de.pyryco.mobile.ui.conversations.thread

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption
import de.pyryco.mobile.ui.conversations.components.agentName
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
 * Whether the footer offers [mode] (#650, #1111). Auto approval needs the selected row's auto-mode support.
 * With a capability list, every other mode except Bypass approvals must be named in it; Bypass is sent as
 * `yolo` and is never listed. The permission menu and the ViewModel's write guard both ask this.
 */
internal fun ThreadRunConfig.offersPermission(mode: PermissionModeOption): Boolean {
    if (mode == PermissionModeOption.Auto && selectedMetadata?.supportsAutoMode != true) return false
    val accepted = capabilities?.permissionModes ?: return true
    return mode == PermissionModeOption.Bypass || mode.wire in accepted
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
 * The note's text for a conversation run by [agent] (#1115): the notes that name an agent name this one.
 * The footer and the Status sheet both resolve it here, so the two never disagree.
 */
@Composable
internal fun EffortNote.text(agent: ConversationAgent): String = stringResource(textRes(), agentName(agent))

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
                    selectedValue = runConfig.selectedChoice?.value.orEmpty(),
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
        // #650: the six modes [offersPermission] allows. The selection is the confirmed reading, so an
        // unrecognised value selects nothing and is never offered.
        FooterControl.Permission ->
            runConfig.permissionMode.takeIf { it.isNotEmpty() }?.let { confirmed ->
                FooterMenu(
                    options =
                        PermissionModeOption.entries
                            .filter { runConfig.offersPermission(it) }
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
 * Actions control (#884) needs no session or idle configuration: a command send writes no setting.
 * No control is enabled while the host is not [connected] (#1319), as on desktop.
 */
internal fun footerControlEnabled(
    control: FooterControl,
    runConfig: ThreadRunConfig,
    connected: Boolean,
): Boolean {
    if (!connected) return false
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
private val FooterChevronWidth = 8.dp
private val FooterChevronHeight = 4.dp
private val FooterLabelMaxWidth = 140.dp
private val StatusOpenerSize = 32.dp
private val StatusOpenerIconSize = 16.dp
private val AttachIconWidth = 11.dp
private val AttachIconHeight = 12.dp
private const val PENDING_ALPHA = 0.55f

/**
 * Figma `16:8`'s `Input footer` (#808): the actions, permission, model and effort buttons under the input
 * field, followed by the Status sheet opener.
 *
 * It replaces #602's single monospace `model · effort` line (`ThreadStatusRow`). The design has no
 * footer affordance for the Status sheet, so a trailing icon keeps it one tap away. The design's
 * paperclip (#933) sits just before that icon and calls [onAttach], which opens the file picker. The Actions button (#884) leads the row, as in the design,
 * and always opens its menu. The `Cxt:` segment (#946) follows the buttons as plain text, not a control.
 *
 * Stateless. Model, effort and permission choices live in the run configuration sheet. [onAnchorChanged]
 * reports the Actions button's window bounds so the screen can place its overlay above it.
 */
@Composable
fun ThreadComposerFooter(
    runConfig: ThreadRunConfig,
    onOpen: (FooterControl) -> Unit,
    onStatusClick: () -> Unit,
    onAnchorChanged: (FooterControl, Rect) -> Unit,
    modifier: Modifier = Modifier,
    onAttach: () -> Unit = {},
    agent: ConversationAgent = ConversationAgent.Claude,
    touchHeight: Dp = FooterButtonMinHeight,
    contentBottomPadding: Dp = 0.dp,
    connected: Boolean = true,
) {
    // #1032: the text controls share one weighted slot, measured after the paperclip and the Status opener,
    // so a footer full of long labels shrinks the labels and never squeezes out the two icons.
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = FooterHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(FooterButtonGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        // The buttons in order, then the `Cxt:` segment, which [FooterTextRow] gives the leftover width.
        FooterTextRow(modifier = Modifier.weight(1f)) {
            FooterButton(
                label = stringResource(R.string.thread_footer_actions),
                clickLabel = stringResource(R.string.thread_footer_open_actions),
                enabled = footerControlEnabled(FooterControl.Actions, runConfig, connected),
                pending = false,
                onClick = { onOpen(FooterControl.Actions) },
                onBounds = { onAnchorChanged(FooterControl.Actions, it) },
                touchHeight = touchHeight,
                contentBottomPadding = contentBottomPadding,
            )
            ContextSegment(percent = runConfig.contextPercent, modifier = Modifier.padding(bottom = contentBottomPadding))
        }
        // Figma's `Attachment` (115:3654): the paperclip at the footer's trailing end, before the Status opener
        // the design does not have.
        Box(
            modifier =
                Modifier
                    .size(width = StatusOpenerSize, height = touchHeight)
                    .clickable(role = Role.Button, onClick = onAttach),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_attach_file),
                contentDescription = stringResource(R.string.cd_attach_files),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AttachIconWidth, AttachIconHeight).offset(y = -contentBottomPadding),
            )
        }
        Box(
            modifier =
                Modifier
                    .size(width = StatusOpenerSize, height = touchHeight)
                    .clickable(role = Role.Button, onClick = onStatusClick),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.cd_thread_status_expand),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(StatusOpenerIconSize).offset(y = -contentBottomPadding),
            )
        }
    }
}

/**
 * The footer's text controls in a row at [FooterButtonGap] (#1032). Every child but the last is a button,
 * held to its natural width until the buttons overflow, then to [footerShrinkCap]'s shared cap. The last
 * child, the `Cxt:` segment, gets whatever width the buttons leave, possibly none.
 */
@Composable
private fun FooterTextRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = FooterButtonGap.roundToPx()
        val buttons = measurables.dropLast(1)
        val available = (constraints.maxWidth - gap * (measurables.size - 1)).coerceAtLeast(0)
        val natural = buttons.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val cap = footerShrinkCap(natural, available)
        val placed =
            buttons.zip(natural) { button, width ->
                button.measure(Constraints(maxWidth = minOf(width, cap), maxHeight = constraints.maxHeight))
            }
        val leftover = (available - placed.sumOf { it.width }).coerceAtLeast(0)
        val all = placed + measurables.last().measure(Constraints(maxWidth = leftover, maxHeight = constraints.maxHeight))
        val height = all.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            var x = 0
            all.forEach {
                it.placeRelative(x, height - it.height)
                x += it.width + gap
            }
        }
    }
}

/**
 * The widest a footer button may be so that [widths] fit in [available] (#1032), or [Int.MAX_VALUE] when
 * they already fit. Only the widest labels give up space, and no button is squeezed out while another
 * keeps its full label.
 */
internal fun footerShrinkCap(
    widths: List<Int>,
    available: Int,
): Int {
    if (widths.sum() <= available) return Int.MAX_VALUE
    var left = available
    widths.sorted().forEachIndexed { i, width ->
        val share = left / (widths.size - i)
        if (width > share) return share
        left -= width
    }
    return left
}

/**
 * Figma's `Cxt: 84%` text (110:3497): Claude's reported [percent] as sent, in the footer's body-small style.
 * `null` is the unavailable state, dimmed like a disabled button and described as unavailable — never `0%`.
 */
@Composable
private fun ContextSegment(
    percent: Int?,
    modifier: Modifier = Modifier,
) {
    val description =
        if (percent != null) {
            stringResource(R.string.cd_context_usage, percent)
        } else {
            stringResource(R.string.cd_context_usage_unavailable)
        }
    Text(
        text =
            if (percent != null) {
                stringResource(R.string.thread_footer_context, percent)
            } else {
                stringResource(R.string.thread_footer_context_unavailable)
            },
        style = MaterialTheme.typography.bodySmall,
        color = if (percent != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.testTag(CONTEXT_USAGE_TEST_TAG).semantics { contentDescription = description },
    )
}

/** Marks the footer's context-usage segment for the rung-3 scenario (#946). A static tag; never daemon text. */
const val CONTEXT_USAGE_TEST_TAG = "thread_footer_context_usage"

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
    touchHeight: Dp,
    contentBottomPadding: Dp,
    note: String? = null,
) {
    val pendingDescription = stringResource(R.string.thread_footer_pending)
    val color = if (enabled || pending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier =
            Modifier
                .heightIn(min = touchHeight)
                .onGloballyPositioned { onBounds(it.boundsInWindow()) }
                .clickable(enabled = enabled, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    (if (pending) pendingDescription else note)?.let { stateDescription = it }
                }.alpha(if (pending) PENDING_ALPHA else 1f)
                .padding(bottom = contentBottomPadding),
        horizontalArrangement = Arrangement.spacedBy(FooterChevronGap),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weighted so a capped button (#1032) ellipsizes its label and keeps its chevron.
            modifier = Modifier.weight(1f, fill = false).widthIn(max = FooterLabelMaxWidth),
        )
        if (enabled || pending) {
            Box(modifier = Modifier.size(FooterChevronWidth, 10.dp), contentAlignment = Alignment.TopCenter) {
                Icon(
                    painter = painterResource(R.drawable.ic_footer_chevron_up),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(FooterChevronWidth, FooterChevronHeight).testTag("footer_actions_chevron"),
                )
            }
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
        contextPercent = 84,
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
            runConfig = previewRunConfig.copy(pendingEffort = "high", pendingPermission = "default", contextPercent = null),
            onOpen = {},
            onStatusClick = {},
            onAnchorChanged = { _, _ -> },
        )
    }
}

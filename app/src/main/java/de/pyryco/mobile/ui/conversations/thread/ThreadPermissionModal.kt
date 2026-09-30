package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.components.ModalCancelButton
import de.pyryco.mobile.ui.theme.modalControl
import androidx.compose.ui.semantics.Role as SemanticsRole

/** Bounds each daemon-authored string the inline request renders (#1306). */
private const val MAX_PERMISSION_TEXT = 8192

/**
 * The pending permission or trust request inside its conversation's stream (#1306, superseding the #446
 * dialog): the server [title][ModalUiState.Open.title] as a heading, a card with the verbatim
 * [prompt][ModalUiState.Open.prompt], claude's decision context, the session-grant offer and the
 * [options][ModalUiState.Open.options] in wire order, then Cancel below the card. Under the thread's reverse
 * layout the items are emitted newest end first, so Cancel sits at index 0 and the title furthest up.
 *
 * Security (render-time obligations #445 deferred, unchanged in substance):
 * - **Inert output-encoding** — every server string renders through plain [Text] bounded by
 *   [MAX_PERMISSION_TEXT]; never [de.pyryco.mobile.ui.conversations.components.MarkdownText], no
 *   `SelectionContainer` clipboard path.
 * - **Screen capture and tapjacking** — the dialog window that used to carry `FLAG_SECURE` and the
 *   obscured-touch filter is gone; the thread mounts [QuestionPromptProtection] on the activity surface for as
 *   long as a request is open, including while these items are scrolled offscreen.
 * - **Stale taps** — every callback carries the rendered request's `modalId`, so a tap composed before a
 *   replacement cannot answer, cancel or grant the replacement; the ViewModel compares it.
 * - **No persistence** — item keys carry the `modalId` only, and nothing here is saved.
 *
 * [onOption] forwards every tapped option id verbatim: the ViewModel decides arm-vs-send (#451), and
 * [armedOptionId] only reflects its armed non-default. Toggling the grant never arms or answers (#818).
 */
internal fun LazyListScope.permissionRequestItems(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (modalId: String, optionId: String) -> Unit,
    onCancel: (modalId: String) -> Unit,
    alwaysAllowAccepted: Boolean,
    onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit,
    gutter: Modifier,
) {
    item(key = "permission-cancel:${open.modalId}") {
        Box(gutter.testTag("permission-request-cancel"), contentAlignment = Alignment.Center) {
            ModalCancelButton(label = stringResource(R.string.modal_cancel), onClick = { onCancel(open.modalId) })
        }
    }
    item(key = "permission-card:${open.modalId}") {
        Box(gutter) {
            PermissionRequestCard(open, armedOptionId, onOption, alwaysAllowAccepted, onAlwaysAllowChanged)
        }
    }
    item(key = "permission-title:${open.modalId}") {
        Box(gutter) {
            Text(
                text = open.title.take(MAX_PERMISSION_TEXT),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() }.testTag("permission-request-title"),
            )
        }
    }
}

/** The request's card, in the #1305 question card's container: background fill, primary-container border. */
@Composable
private fun PermissionRequestCard(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (modalId: String, optionId: String) -> Unit,
    alwaysAllowAccepted: Boolean,
    onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("permission-request-card"),
        shape = MaterialTheme.shapes.modalControl,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = open.prompt.take(MAX_PERMISSION_TEXT), style = MaterialTheme.typography.bodyLarge)
            if (!open.context.isEmpty) PermissionContext(open.context)
            if (open.offersAlwaysAllow) {
                AlwaysAllowOffer(
                    rules = open.alwaysAllowRules,
                    accepted = alwaysAllowAccepted,
                    onChanged = { onAlwaysAllowChanged(open.modalId, it) },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Iterate in array order (the canonical display/selection order). isDefault drives the
                // fail-safe-deny highlight; isArmed reflects the VM's armed non-default option — no
                // option-id semantics are interpreted, every tap forwards verbatim.
                open.options.forEach { option ->
                    ModalOptionButton(
                        label = option.label.take(MAX_PERMISSION_TEXT),
                        isDefault = option.id == open.defaultOptionId,
                        isArmed = option.id == armedOptionId,
                        onClick = { onOption(open.modalId, option.id) },
                    )
                }
            }
        }
    }
}

/**
 * Claude's decision context for the ask (#817), in the desktop's order: reason, description, blocked path.
 * The reason row's label names the `reason_type` — a sentence for `classifier` and `rule`, the raw category
 * for any other value (never dropped), `Reason` when none arrived — and it renders alone when only the
 * category did. Labels are local strings; every value is claude-authored and renders through plain [Text]
 * only (no markdown, no link handling, no `SelectionContainer`, no saved state), like the prompt above it.
 */
@Composable
private fun PermissionContext(context: ModalContext) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        // The container frame's (`533-2369`) content-row gap.
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (context.reason != null || context.reasonType != null) {
            val label =
                when (val type = context.reasonType) {
                    "classifier" -> stringResource(R.string.modal_context_reason_classifier)
                    "rule" -> stringResource(R.string.modal_context_reason_rule)
                    null -> stringResource(R.string.modal_context_reason)
                    else -> stringResource(R.string.modal_context_reason_type, type.take(MAX_PERMISSION_TEXT))
                }
            ModalContextRow(label = label, value = context.reason?.take(MAX_PERMISSION_TEXT))
        }
        context.description?.let { ModalContextRow(stringResource(R.string.modal_context_description), it.take(MAX_PERMISSION_TEXT)) }
        context.blockedPath?.let { ModalContextRow(stringResource(R.string.modal_context_blocked_path), it.take(MAX_PERMISSION_TEXT)) }
    }
}

/**
 * The "don't ask again this session" offer (#818): a checkbox row with a local label, then the offered rules.
 * The checkbox with label in Figma `347:6215` supplies the visible box and label geometry. The whole row
 * toggles at the shell's touch floor. The rules are claude-authored and render through plain [Text] only, one per line in wire
 * order (no markdown, no link handling, no `SelectionContainer`, no saved state, never logged).
 */
@Composable
private fun AlwaysAllowOffer(
    rules: List<String>,
    accepted: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(value = accepted, role = SemanticsRole.Checkbox, onValueChange = onChanged),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(20.dp)
                        .border(2.dp, MaterialTheme.colorScheme.tertiary, MaterialTheme.shapes.extraSmall)
                        .testTag("always_allow_box"),
                contentAlignment = Alignment.Center,
            ) {
                if (accepted) {
                    Icon(
                        painter = painterResource(R.drawable.ic_permission_checkbox_check),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Text(
                text = stringResource(R.string.modal_always_allow_label),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        rules.forEach { rule -> Text(text = rule.take(MAX_PERMISSION_TEXT), style = MaterialTheme.typography.bodyMedium) }
    }
}

/**
 * The container frame's label style over its value (the frame's "Input large" stacking, 8 dp apart) rather
 * than its single-line read-only row, because a reason or description is prose an ellipsis would hide. The
 * row merges its semantics so a screen reader reads label and value as one node.
 */
@Composable
private fun ModalContextRow(
    label: String,
    value: String?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (value != null) Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One modal option — a **stateless** pure function of [label] / [isDefault] / [isArmed]; it holds no
 * `remember`-based arm state (the arm lives on the VM, #451; this slice only renders [armedOptionId]).
 * Three disjoint renders, [isArmed] taking precedence:
 * - [isArmed] (an armed non-default awaiting its second confirm, #452) → a [FilledTonalButton], kept
 *   **below** the default's filled emphasis so the safe default stays visually dominant, plus the
 *   `modal_armed_option_desc` `stateDescription` ("Tap again to confirm").
 * - [isDefault] (the fail-safe-deny default) → a high-emphasis filled [Button] + the
 *   `modal_default_option_desc` marker, so the visually prominent button is always the producer's
 *   deny/safe option (it answers on a single tap).
 * - neither → an [OutlinedButton], no marker (a first tap arms it via the VM).
 *
 * The `stateDescription` markers are accessible + test-observable (a screen reader announces them; the AC#4
 * test locates the armed / default option by these, not by colour). Only the local markers are added — the
 * verbatim server [label] stays the sole server text, rendered through plain [Text].
 */
@Composable
private fun ModalOptionButton(
    label: String,
    isDefault: Boolean,
    isArmed: Boolean,
    onClick: () -> Unit,
) {
    val defaultDesc = stringResource(R.string.modal_default_option_desc)
    val armedDesc = stringResource(R.string.modal_armed_option_desc)
    // Figma `489:1876` draws a 40 dp surface; Material supplies the surrounding 48 dp touch floor.
    val base = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
    val modifier =
        when {
            isArmed -> base.semantics { stateDescription = armedDesc }
            isDefault -> base.semantics { stateDescription = defaultDesc }
            else -> base
        }
    val shape = MaterialTheme.shapes.modalControl
    when {
        isArmed ->
            FilledTonalButton(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        isDefault ->
            Button(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        else ->
            OutlinedButton(
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 19.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
    }
}

/**
 * Maps the verbatim [Dismissed.source][ModalUiState.Dismissed.source] to a **local** string resource —
 * never echoing the raw wire token (the snackbar draws in the un-secured Activity window, so the mapping
 * is a confidentiality requirement, not only UX). Unknown forward-compat values fall back to a generic
 * "resolved" message (AC #3).
 */
@Composable
internal fun dismissReasonText(source: String): String =
    when (source) {
        "remote" -> stringResource(R.string.modal_dismissed_remote)
        "local" -> stringResource(R.string.modal_dismissed_local)
        "timeout" -> stringResource(R.string.modal_dismissed_timeout)
        else -> stringResource(R.string.modal_dismissed_resolved)
    }

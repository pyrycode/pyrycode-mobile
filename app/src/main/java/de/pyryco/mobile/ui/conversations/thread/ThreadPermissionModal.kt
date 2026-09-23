package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ModalContext
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.components.MobileGateModal
import de.pyryco.mobile.ui.settings.label
import androidx.compose.ui.semantics.Role as SemanticsRole

/**
 * The permission/choice modal overlay (#446) — a separate-surface M3 dialog floating over the active
 * thread, **not** a row in the thread [LazyColumn]. Renders the verbatim [title][ModalUiState.Open.title],
 * [prompt][ModalUiState.Open.prompt], and [options][ModalUiState.Open.options] (in wire array order) and
 * highlights the producer's fail-safe-deny [defaultOptionId][ModalUiState.Open.defaultOptionId].
 *
 * Since #815 it is drawn in the shared mobile modal container ([MobileGateModal]): the server title fills
 * the header, the prompt and options fill the scroll area, and the footer carries only Cancel. Since #817
 * claude's decision context ([PermissionContext]) sits between the prompt and the options, only when the
 * frame carried any.
 *
 * Security (this slice owns the render-time obligations #445 deferred):
 * - **Inert output-encoding** — every server string renders through plain [Text] (literal, no
 *   markup/HTML/active content; never [de.pyryco.mobile.ui.conversations.components.MarkdownText], no
 *   `SelectionContainer` clipboard path) — the values may name a sensitive command or path.
 * - **Screen-capture hardening** and **tapjacking** (#452) — [MobileGateModal] sets `FLAG_SECURE` on the
 *   dialog's **own** window (the host carries none) and `filterTouchesWhenObscured` on it, a deterministic
 *   View-level net that is *different fabric* from the second-confirm UX belt (#451).
 * - **No persistence** — no modal-derived text reaches `rememberSaveable` / saved-instance state.
 *
 * Live in #452: [onOption] forwards every tapped option id verbatim — the VM decides arm-vs-send; the UI
 * never re-derives the arm. [armedOptionId] reflects the VM's armed non-default option (#451), drawing the
 * second-confirm affordance on that one option. [onCancel] is reached only via the explicit Cancel button;
 * the gate ignores back-press and outside taps and draws no close glyph (#446), so a permission gate never
 * reads a stray gesture as an implicit answer.
 *
 * #818: when the prompt [offers][ModalUiState.Open.offersAlwaysAllow] a session grant, [AlwaysAllowOffer]
 * sits between the context and the options, as on the desktop. Its toggle reports this prompt's `modalId`
 * so the VM can ignore a tap that lands after the prompt was replaced.
 */
@Composable
internal fun PermissionModalOverlay(
    open: ModalUiState.Open,
    armedOptionId: String?,
    onOption: (String) -> Unit,
    onCancel: () -> Unit,
    alwaysAllowAccepted: Boolean = false,
    onAlwaysAllowChanged: (modalId: String, accepted: Boolean) -> Unit = { _, _ -> },
) {
    MobileGateModal(
        title = open.title,
        cancelLabel = stringResource(R.string.modal_cancel),
        onCancel = onCancel,
    ) {
        Text(text = open.prompt, style = MaterialTheme.typography.bodyLarge)
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
                    label = option.label,
                    isDefault = option.id == open.defaultOptionId,
                    isArmed = option.id == armedOptionId,
                    onClick = { onOption(option.id) },
                )
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
                    else -> stringResource(R.string.modal_context_reason_type, type)
                }
            ModalContextRow(label = label, value = context.reason)
        }
        context.description?.let { ModalContextRow(stringResource(R.string.modal_context_description), it) }
        context.blockedPath?.let { ModalContextRow(stringResource(R.string.modal_context_blocked_path), it) }
    }
}

/**
 * The "don't ask again this session" offer (#818): a checkbox row with a local label, then the offered rules.
 * The Figma container (`533-2369`) has no frame for it, so it takes the context rows' "Input large" stacking:
 * the label style over body-medium values, 8 dp apart. The whole row toggles, so the target is the row and
 * not only the box. The rules are claude-authored and render through plain [Text] only, one per line in wire
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(
                text = stringResource(R.string.modal_always_allow_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        rules.forEach { rule -> Text(text = rule, style = MaterialTheme.typography.bodyMedium) }
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
    // The shell's action geometry (#815): small shape and a 48 dp minimum target.
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val modifier =
        when {
            isArmed -> base.semantics { stateDescription = armedDesc }
            isDefault -> base.semantics { stateDescription = defaultDesc }
            else -> base
        }
    val shape = MaterialTheme.shapes.small
    when {
        isArmed -> FilledTonalButton(onClick = onClick, modifier = modifier, shape = shape) { Text(label) }
        isDefault -> Button(onClick = onClick, modifier = modifier, shape = shape) { Text(label) }
        else ->
            OutlinedButton(
                onClick = onClick,
                modifier = modifier,
                shape = shape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) { Text(label) }
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

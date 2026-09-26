package de.pyryco.mobile.ui.components

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

/** The device suites' handle for the name field, which the design draws without a built-in label. */
internal const val EDIT_HOST_NAME_FIELD_TAG: String = "edit-host-name"

// The design's 20dp identity rows, its 10dp label/value gap and the 8dp gap above the name field.
private val IdentityRowMinHeight = 20.dp
private val IdentityLabelGap = 10.dp
private val FieldLabelGap = 8.dp
private val UnpairTopPadding = 8.dp

// The shell's recorded floor for its own Close, Cancel and OK, applied to this component's one
// action for the same reason.
private val ActionMinHeight = 48.dp

// The design fills the name field with `on-primary` at 41%, which reads as a recessed well only
// because the frame is dark-only and that token is a near-black there. `onPrimary` is white in our
// light scheme and the fill would vanish — the trap `SELECTED_FILL_ALPHA` records on the tree rows.
// Tinting with the shell's own content colour instead recesses in light and lifts in dark, which is
// what M3's filled field already does across schemes, at a far lower alpha because
// `onPrimaryContainer` is a high-contrast colour where the reference token is not.
private const val FIELD_FILL_ALPHA = 0.12f

/**
 * The Edit host frame, drawn through [MobileModal] and driven entirely by its caller.
 *
 * Presentation only. It performs no storage, connection or navigation work: it reports the entered
 * name through [onSubmit], the unpair intent through [onUnpairRequested] and every dismissal route
 * through [onDismissRequest], and none of the three closes it — the caller removes it from
 * composition. [loading], [error] and [submissionEnabled] are the caller's flags, and validation is
 * the caller's too, per the shell's contract.
 *
 * Caller obligations, because this component cannot enforce them from inside:
 * - Pass **display text**. Never a device token, a static key or a raw fingerprint: the component
 *   renders what it is handed, so a secret passed in is a secret shown on screen.
 * - [relayAddress] is display text only. It is never the URL anything opens or connects to — the
 *   live endpoint comes from the stored record through the relay supervisor, the same rule
 *   `workspaceDisplayName` states for a workspace label.
 * - Keep [error] generic. The shell renders it verbatim into a live region, so an identity or a
 *   relay address embedded in one is announced aloud.
 * - Do not change [serverIdentity] while the modal is open; it identifies which host is being
 *   edited and reseeding the name buffer mid-edit would discard what the operator typed.
 *
 * No initial focus is requested, deliberately: raising the IME on open would push the unpair action
 * under the keyboard on the 320 × 640 dp viewport. A consumer that wants it must put its focus
 * effect inside this component's content, in the dialog's own subcomposition — see
 * `CreateFolderDialog`'s comment for why a parent-driven request returns cleanly but never lands.
 *
 * While [confirmingUnpair] the frame's four content children are replaced **in place** by the
 * confirmation prompt (#745), and the shell's own footer carries the decision: its OK becomes
 * [onUnpairConfirmed] and every dismissal route it funnels — Cancel, the close glyph, system Back —
 * becomes [onUnpairDeclined]. The alternative, a second [MobileModal]-style `Dialog`, would stack two
 * windows over one decision and give the phone two back targets for it. Declining therefore returns to
 * the editor rather than closing it, which is also why [onDismissRequest] is not reachable from the
 * confirmation at all: no route out of a destructive step should be ambiguous about whether it removed
 * anything.
 */
@Composable
internal fun EditHostModal(
    serverIdentity: String,
    relayAddress: String,
    initialHostName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (String) -> Unit,
    onUnpairRequested: () -> Unit,
    onUnpairConfirmed: () -> Unit,
    onUnpairDeclined: () -> Unit,
    modifier: Modifier = Modifier,
    submissionEnabled: Boolean = true,
    loading: Boolean = false,
    error: String? = null,
    confirmingUnpair: Boolean = false,
) {
    // Clamped once at the boundary, before any of the three reaches text layout or a merged
    // semantics node, and reused everywhere below.
    val boundedIdentity = boundedText(serverIdentity)
    val boundedRelay = boundedText(relayAddress)
    val boundedName = boundedText(initialHostName)

    // Keyed on the **raw** identity, never the clamped one: two hosts whose ids share a 128
    // character prefix must not collapse onto one buffer. `error` and `loading` are deliberately
    // not keys — a failed save must leave what the operator typed exactly where it was.
    var fieldValue by remember(serverIdentity) {
        mutableStateOf(TextFieldValue(text = boundedName, selection = TextRange(boundedName.length)))
    }
    val submit = { onSubmit(fieldValue.text.trim()) }

    MobileModal(
        title = stringResource(if (confirmingUnpair) R.string.edit_host_unpair_confirm_title else R.string.edit_host_title),
        onDismissRequest = if (confirmingUnpair) onUnpairDeclined else onDismissRequest,
        onSubmit = if (confirmingUnpair) onUnpairConfirmed else submit,
        modifier = modifier,
        submissionEnabled = submissionEnabled,
        loading = loading,
        error = error,
    ) {
        if (confirmingUnpair) {
            // The name buffer above is keyed on the identity, not on this flag, so declining comes back
            // to the field with exactly what the operator had typed.
            UnpairConfirmation(hostName = boundedName)
        } else {
            IdentityRow(label = stringResource(R.string.edit_host_server_identity_label), value = boundedIdentity)
            IdentityRow(label = stringResource(R.string.edit_host_relay_address_label), value = boundedRelay)
            HostNameField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                onDone = { if (submissionEnabled && !loading) submit() },
            )
            UnpairAction(
                onClick = {
                    logEditHostEvent("unpair_requested")
                    onUnpairRequested()
                },
            )
        }
    }
}

/**
 * The confirmation the frame does not draw (#745): one prompt naming the host, decided by the shell's
 * own footer.
 *
 * [hostName] is the **clamped** name, and the blank fallback is the app-wide `unnamed_host` the host row
 * itself falls back to, so the confirmation names the host the way its row does. Formatting the raw
 * parameter instead would put an unbounded legacy name — one written before the rename path clamped, or
 * by the pair-with-code form — into both text layout and a merged semantics node, which `maxLines` bounds
 * for painting but not for measurement. The name is passed as a format *argument*, so a `%s` inside it is
 * rendered literally and cannot reinterpret the format.
 *
 * Neither the server identity nor the relay address appears here: the operator is deciding about a
 * machine they named, and the shell announces this content aloud.
 */
@Composable
private fun UnpairConfirmation(hostName: String) {
    Text(
        text =
            stringResource(
                R.string.edit_host_unpair_confirm_body,
                hostName.ifBlank { stringResource(R.string.unnamed_host) },
            ),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * One label-and-value line from the frame's "read only textfield" rows.
 *
 * Drawn as plain [Text], not a field: a real field would take focus and carry a `SetText` action,
 * and these two values are inert by requirement. Merging the row's descendants gives a screen
 * reader "Server identity: <value>" as one node without any code formatting a string — so the
 * clamp above is the only thing standing between an unbounded value and layout, and there is no
 * second path through a built description for it to miss.
 */
@Composable
private fun IdentityRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = IdentityRowMinHeight).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(IdentityLabelGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        // The design already draws its own sample identity ellipsised: an over-long value truncates
        // inside the row rather than stretching it.
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The frame's "Input large": its own label above a filled field, rather than a built-in one. */
@Composable
private fun HostNameField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FieldLabelGap),
    ) {
        Text(
            text = stringResource(R.string.edit_host_name_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        val fill = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = FIELD_FILL_ALPHA)
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().testTag(EDIT_HOST_NAME_FIELD_TAG),
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            // The design draws a plain filled well with no underline, so the indicator is cleared in
            // every state rather than restyled.
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = fill,
                    unfocusedContainerColor = fill,
                    disabledContainerColor = fill,
                    focusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    unfocusedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
        )
    }
}

/** The frame's outlined `Unpair host` action, grown to the shell's touch floor. */
@Composable
private fun UnpairAction(onClick: () -> Unit) {
    Column(modifier = Modifier.padding(top = UnpairTopPadding)) {
        OutlinedButton(
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
            onClick = onClick,
            modifier = Modifier.heightIn(min = ActionMinHeight),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.edit_host_unpair),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * The same bound `boundedRowText` applies on the tree rows, and for the reason its comment states.
 *
 * The identity and the relay address originate in a scanned QR payload, so both are
 * attacker-influenceable in length; the name is the same stored record's field and is already
 * clamped on the row this modal opens from. `maxLines = 1` bounds only what is painted — Compose
 * still measures the whole string — so the clamp, not the overflow, is what keeps an oversized
 * value a truncation rather than an ANR.
 */
private fun boundedText(raw: String): String =
    // A cut between the halves of a surrogate pair would leave a lone high surrogate, and OK would
    // send it back as part of the name: drop it.
    raw.take(MAX_WORKSPACE_LABEL_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }

/** Content-free and debug-gated, in the shell's shape: the event name and nothing it was given. */
private fun logEditHostEvent(event: String) {
    if (BuildConfig.DEBUG) Log.d("EditHostModal", "event=$event")
}

@Composable
private fun PreviewModal(confirmingUnpair: Boolean) {
    PyrycodeMobileTheme {
        EditHostModal(
            serverIdentity = "345345-345345345-gw3vw-w4wv34-vw34t",
            relayAddress = "https://asdf.afwevawef.fwef/asdffe",
            initialHostName = "Pyrybox",
            onDismissRequest = {},
            onSubmit = {},
            onUnpairRequested = {},
            onUnpairConfirmed = {},
            onUnpairDeclined = {},
            confirmingUnpair = confirmingUnpair,
        )
    }
}

@Preview(name = "Edit host — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "Edit host — Dark", widthDp = 412, heightDp = 892, showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditHostModalPreview() {
    PreviewModal(confirmingUnpair = false)
}

@Preview(name = "Unpair confirmation — Light", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(
    name = "Unpair confirmation — Dark",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun EditHostModalUnpairConfirmationPreview() {
    PreviewModal(confirmingUnpair = true)
}

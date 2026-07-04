package de.pyryco.mobile.ui.onboarding

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.pyryco.mobile.data.network.PairingParseResult
import de.pyryco.mobile.data.network.parsePairingPayload

/**
 * The manual paste-code fallback (#503) for when the QR camera can't scan. Store-free by design
 * (#501): its only success output is the raw validated pairing string handed to [onValidPayload] —
 * it holds no reference to PairedServerStore and never persists. MainActivity feeds that string to
 * the same `ScannerEvent.QrDecoded` the camera path uses, so paste flows through the identical
 * fingerprint-confirm gate (#343): parse → derive fingerprint → AwaitingConfirm → Confirm-persist.
 * A paste that fails to parse keeps its inline error and never hands off, so it never reaches the
 * QR-worded full-screen error surface.
 *
 * Owns its `pasteText` / `pasteError` via `remember`: the dialog is mounted fresh each time it is
 * shown, so this state re-initializes on show without an explicit reset. Logs nothing — the pasted
 * string and the token it carries must never reach `Log.*` ([parsePairingPayload] logs nothing either).
 */
@Composable
fun PasteCodeDialog(
    onDismiss: () -> Unit,
    onValidPayload: (String) -> Unit,
) {
    var pasteText by remember { mutableStateOf("") }
    var pasteError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter pairing code") },
        text = {
            OutlinedTextField(
                value = pasteText,
                onValueChange = { pasteText = it },
                label = { Text("Pairing code") },
                isError = pasteError != null,
                supportingText = pasteError?.let { { Text(it) } },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                // Parse the trimmed value once, then hand that same value off — no suspension between
                // parse and callback, so the parsed-from string and the emitted string are identical.
                val trimmed = pasteText.trim()
                when (parsePairingPayload(trimmed)) {
                    is PairingParseResult.Success -> onValidPayload(trimmed)
                    is PairingParseResult.Failure -> pasteError = "Invalid pairing code"
                }
            }) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.ViewModel
import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ScannerUiState {
    // Initial: the camera-permission request is in flight / being checked.
    data object PermissionRequesting : ScannerUiState

    // Granted → the locked scanner viewport. The live camera lands in the next slice; this slice
    // renders the existing viewport unchanged.
    data object ReadyToScan : ScannerUiState

    // Denied → the existing ScannerDeniedScreen (#61).
    data object Denied : ScannerUiState

    data class Error(
        val message: String,
    ) : ScannerUiState

    // A QR code was decoded to its raw payload. The payload is untrusted external input — surfaced
    // here, parsed in #320, identity-confirmed in #321. This slice adds no new visible surface: the
    // Decoded state renders the existing locked viewport unchanged (scope guard).
    data class Decoded(
        val payload: String,
    ) : ScannerUiState {
        // AC5 deterministic net: never leak the untrusted scan string via toString() (e.g. an
        // accidental log of "$state"). Does not affect the data-class equals/hashCode, so
        // assertEquals(Decoded("x"), …) still holds.
        override fun toString(): String = "Decoded(payload=<redacted ${payload.length} chars>)"
    }

    // The security checkpoint (#343): a payload parsed into a real [server] (#320), with its
    // static-key [fingerprint] derived (#342), is held here awaiting the user's explicit confirm
    // before anything persists. [server] is carried so the confirm path saves exactly the parsed
    // record (no re-parse / re-derive — the displayed fingerprint and the saved server are bound to
    // one immutable value); [fingerprint] is carried so the surface renders without re-deriving. No
    // custom toString: the only secret is server.token, already redacted by PairedServer.toString();
    // the fingerprint is a public-key digest (shown on screen and by the desktop), so it is not.
    data class AwaitingConfirm(
        val fingerprint: String,
        val server: PairedServer,
    ) : ScannerUiState
}

sealed interface ScannerEvent {
    data object PermissionGranted : ScannerEvent

    data object PermissionDenied : ScannerEvent

    // Seam for the camera-engine slice (CameraX bind / ML Kit decode failure). No live producer this
    // slice — the permission contract only yields granted/denied — but defining it keeps the Error
    // state reachable and its transition unit-testable now (AC5).
    data class CameraError(
        val message: String,
    ) : ScannerEvent

    // Success counterpart to CameraError: the analyzer surfaces a decoded QR payload exactly once per
    // scan and feeds it here (wired to a live camera in #334). The payload is raw untrusted input.
    data class QrDecoded(
        val payload: String,
    ) : ScannerEvent {
        // AC5 deterministic net — see ScannerUiState.Decoded.toString.
        override fun toString(): String = "QrDecoded(payload=<redacted ${payload.length} chars>)"
    }

    // A scanned payload failed to parse/validate into a PairedServer, or persisting it failed (#320).
    // [message] is an app constant supplied by the caller — never a payload byte — so no redacting
    // toString() is needed; it routes to the existing Error surface (mirrors CameraError).
    data class PairingFailed(
        val message: String,
    ) : ScannerEvent

    // Fired by the composable after a successful parse (#320) AND a successful fingerprint derive
    // (#342) — same "feed the VM the final value" pattern as PairingFailed; carries no payload byte.
    // No redacting toString: server.token is already redacted by PairedServer.toString() and the
    // fingerprint is a public-key digest (#343).
    data class PairingPrepared(
        val fingerprint: String,
        val server: PairedServer,
    ) : ScannerEvent

    // Fired by the Decline button and by system Back while AwaitingConfirm: persist nothing, re-arm
    // the scanner (#343). Confirm is deliberately NOT an event — the suspend save + navigate is a
    // route-scope callback in the composable (mirrors onPasteCode), keeping this VM Android-free.
    data object DeclinePairing : ScannerEvent
}

// Pure synchronous state machine: no viewModelScope, no flows beyond the single state holder, no
// Android types. The only async edge (the runtime permission callback) lives in the composable and
// feeds this VM via onEvent.
class ScannerViewModel : ViewModel() {
    private val scannerState = MutableStateFlow<ScannerUiState>(ScannerUiState.PermissionRequesting)
    val state: StateFlow<ScannerUiState> = scannerState.asStateFlow()

    fun onEvent(event: ScannerEvent) {
        scannerState.value =
            when (event) {
                ScannerEvent.PermissionGranted -> ScannerUiState.ReadyToScan
                ScannerEvent.PermissionDenied -> ScannerUiState.Denied
                is ScannerEvent.CameraError -> ScannerUiState.Error(event.message)
                is ScannerEvent.QrDecoded -> ScannerUiState.Decoded(event.payload)
                is ScannerEvent.PairingFailed -> ScannerUiState.Error(event.message)
                is ScannerEvent.PairingPrepared ->
                    ScannerUiState.AwaitingConfirm(event.fingerprint, event.server)
                // Unconditional → ReadyToScan: DeclinePairing is only ever wired from the confirm
                // surface / the AwaitingConfirm-gated BackHandler. Re-arms CameraPreview (mounted
                // only in ReadyToScan); persists nothing.
                ScannerEvent.DeclinePairing -> ScannerUiState.ReadyToScan
            }
    }
}

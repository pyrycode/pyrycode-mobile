package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val SAVE_FAILED_MESSAGE = "Couldn't save the pairing. Please try again."

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

    // Confirm saved (or is saving) [server] and waits for the host to answer (#1386). The confirm modal
    // stays up in its loading state; [server] is the exact record Retry waits for again.
    data class Verifying(
        val fingerprint: String,
        val server: PairedServer,
    ) : ScannerUiState

    // The shared step's failure for the saved [server] (#1386): its [message], and whether Retry is offered.
    // Flattened from the internal PairingVerification.Failure, which this public state cannot expose.
    data class VerificationFailed(
        val fingerprint: String,
        val server: PairedServer,
        val message: String,
        val retryable: Boolean,
    ) : ScannerUiState

    // The host answered on both legs: the route opens the channel list.
    data object Paired : ScannerUiState

    // Cancel or Back during the wait or after a failure: the route pops the scanner. The host stays saved.
    data object Cancelled : ScannerUiState
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
    // the scanner (#343).
    data object DeclinePairing : ScannerEvent

    // The Confirm button (#1386): save the held record, then wait for the host to answer.
    data object ConfirmPairing : ScannerEvent

    data object RetryVerification : ScannerEvent

    data object CancelVerification : ScannerEvent
}

// A synchronous state machine for the scan itself. Confirm owns the one async edge (#1386): save, then
// wait on the saved record with the step the code path shares, in viewModelScope so the wait survives
// rotation and stops when the route is popped. No Android types.
class ScannerViewModel(
    private val store: PairedServerStore,
    private val controller: RelayConnectionController,
    private val observe: (PairedServer) -> Flow<ConnectionStatus?>,
) : ViewModel() {
    private val scannerState = MutableStateFlow<ScannerUiState>(ScannerUiState.PermissionRequesting)
    val state: StateFlow<ScannerUiState> = scannerState.asStateFlow()
    private var operation: Job? = null

    fun onEvent(event: ScannerEvent) {
        when (val current = scannerState.value) {
            is ScannerUiState.AwaitingConfirm ->
                if (event == ScannerEvent.ConfirmPairing) {
                    scannerState.value = ScannerUiState.Verifying(current.fingerprint, current.server)
                    operation = viewModelScope.launch { persist(current.fingerprint, current.server) }
                    return
                }
            // While the modal waits or holds a failure, only its own actions apply: a rotation re-sends
            // PermissionGranted, which must not hide the modal while the wait still runs.
            is ScannerUiState.Verifying, is ScannerUiState.VerificationFailed -> {
                if (event == ScannerEvent.CancelVerification) {
                    operation?.cancel()
                    scannerState.value = ScannerUiState.Cancelled
                    RelayLog.d { "event=scanner_pair_cancel" }
                } else if (event == ScannerEvent.RetryVerification &&
                    current is ScannerUiState.VerificationFailed &&
                    current.retryable
                ) {
                    scannerState.value = ScannerUiState.Verifying(current.fingerprint, current.server)
                    RelayLog.d { "event=scanner_pair_retry" }
                    operation = viewModelScope.launch { verify(current.fingerprint, current.server, retry = true) }
                }
                return
            }
            ScannerUiState.Paired, ScannerUiState.Cancelled -> return
            else -> Unit
        }
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
                ScannerEvent.ConfirmPairing,
                ScannerEvent.RetryVerification,
                ScannerEvent.CancelVerification,
                -> return
            }
    }

    private suspend fun persist(
        fingerprint: String,
        server: PairedServer,
    ) {
        var saved = false
        RelayLog.d { "event=scanner_pair_save_started" }
        confirmPairingAndConnect(
            server,
            store,
            controller,
            onPersisted = { saved = true },
            onFailed = {
                scannerState.value = ScannerUiState.Error(SAVE_FAILED_MESSAGE)
                RelayLog.w { "event=scanner_pair_failed code=save_failed" }
            },
        )
        if (saved) verify(fingerprint, server, retry = false)
    }

    private suspend fun verify(
        fingerprint: String,
        server: PairedServer,
        retry: Boolean,
    ) {
        RelayLog.d { "event=scanner_pair_connection_wait" }
        val outcome = verifySavedPairing(server, observe, retry)
        // A late result never overrides Cancel or a newer wait.
        if (scannerState.value != ScannerUiState.Verifying(fingerprint, server)) return
        when (outcome) {
            PairingVerification.Connected -> {
                scannerState.value = ScannerUiState.Paired
                RelayLog.d { "event=scanner_pair_connected" }
            }
            is PairingVerification.Failure -> {
                scannerState.value = ScannerUiState.VerificationFailed(fingerprint, server, outcome.message, outcome.retryable)
                RelayLog.w { "event=scanner_pair_failed code=${outcome.code}" }
            }
        }
    }
}

package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.ViewModel
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
            }
    }
}

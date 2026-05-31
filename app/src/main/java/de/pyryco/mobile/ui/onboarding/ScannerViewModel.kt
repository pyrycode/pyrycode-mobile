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
            }
    }
}

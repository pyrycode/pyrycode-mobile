package de.pyryco.mobile.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerViewModelTest {
    @Test
    fun initialState_isPermissionRequesting() {
        val vm = ScannerViewModel()
        assertEquals(ScannerUiState.PermissionRequesting, vm.state.value)
    }

    @Test
    fun permissionGranted_movesToReadyToScan() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.PermissionGranted)
        assertEquals(ScannerUiState.ReadyToScan, vm.state.value)
    }

    @Test
    fun permissionDenied_movesToDenied() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.PermissionDenied)
        assertEquals(ScannerUiState.Denied, vm.state.value)
    }

    @Test
    fun cameraError_movesToErrorCarryingMessage() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.CameraError("boom"))
        assertEquals(ScannerUiState.Error("boom"), vm.state.value)
    }

    @Test
    fun qrDecoded_movesToDecodedCarryingPayload() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.QrDecoded("abc"))
        assertEquals(ScannerUiState.Decoded("abc"), vm.state.value)
    }

    @Test
    fun pairingFailed_movesToErrorCarryingMessage() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.PairingFailed("boom"))
        assertEquals(ScannerUiState.Error("boom"), vm.state.value)
    }
}

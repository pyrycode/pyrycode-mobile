package de.pyryco.mobile.ui.onboarding

import de.pyryco.mobile.data.crypto.PairedServer
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

    @Test
    fun pairingPrepared_movesToAwaitingConfirmCarryingFingerprintAndServer() {
        val vm = ScannerViewModel()
        val server = pairedServer()
        vm.onEvent(ScannerEvent.PairingPrepared("32:0b:5e:a9:9e:65:3b:c2", server))
        assertEquals(
            ScannerUiState.AwaitingConfirm("32:0b:5e:a9:9e:65:3b:c2", server),
            vm.state.value,
        )
    }

    @Test
    fun declinePairing_fromAwaitingConfirm_returnsToReadyToScan() {
        val vm = ScannerViewModel()
        vm.onEvent(ScannerEvent.PairingPrepared("32:0b:5e:a9:9e:65:3b:c2", pairedServer()))
        vm.onEvent(ScannerEvent.DeclinePairing)
        assertEquals(ScannerUiState.ReadyToScan, vm.state.value)
    }

    private fun pairedServer() =
        PairedServer(
            serverId = "srv-1",
            token = "tok-123",
            relayUrl = "wss://relay.example.com",
            serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        )
}

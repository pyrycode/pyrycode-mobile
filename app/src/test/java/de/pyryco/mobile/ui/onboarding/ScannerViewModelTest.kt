package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** A scanner VM for transition tests that never confirm: nothing is saved, dialled or observed. */
internal fun scannerViewModel() =
    ScannerViewModel(
        object : PairedServerStore {
            override suspend fun load(): PairedServer? = null

            override suspend fun save(record: PairedServer) = error("unused")
        },
        object : RelayConnectionController {
            override fun connect() = error("unused")

            override fun close() = Unit
        },
        { emptyFlow() },
    )

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScannerViewModelTest {
    @Test
    fun initialState_isPermissionRequesting() {
        val vm = scannerViewModel()
        assertEquals(ScannerUiState.PermissionRequesting, vm.state.value)
    }

    @Test
    fun permissionGranted_movesToReadyToScan() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.PermissionGranted)
        assertEquals(ScannerUiState.ReadyToScan, vm.state.value)
    }

    @Test
    fun permissionDenied_movesToDenied() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.PermissionDenied)
        assertEquals(ScannerUiState.Denied, vm.state.value)
    }

    @Test
    fun cameraError_movesToErrorCarryingMessage() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.CameraError("boom"))
        assertEquals(ScannerUiState.Error("boom"), vm.state.value)
    }

    @Test
    fun qrDecoded_movesToDecodedCarryingPayload() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.QrDecoded("abc"))
        assertEquals(ScannerUiState.Decoded("abc"), vm.state.value)
    }

    @Test
    fun pairingFailed_movesToErrorCarryingMessage() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.PairingFailed("boom"))
        assertEquals(ScannerUiState.Error("boom"), vm.state.value)
    }

    @Test
    fun pairingPrepared_movesToAwaitingConfirmCarryingFingerprintAndServer() {
        val vm = scannerViewModel()
        val server = pairedServer()
        vm.onEvent(ScannerEvent.PairingPrepared(FINGERPRINT, server))
        assertEquals(
            ScannerUiState.AwaitingConfirm(FINGERPRINT, server),
            vm.state.value,
        )
    }

    @Test
    fun declinePairing_fromAwaitingConfirm_returnsToReadyToScan() {
        val vm = scannerViewModel()
        vm.onEvent(ScannerEvent.PairingPrepared(FINGERPRINT, pairedServer()))
        vm.onEvent(ScannerEvent.DeclinePairing)
        assertEquals(ScannerUiState.ReadyToScan, vm.state.value)
    }

    @Test
    fun confirm_navigatesOnlyAfterBothLegsConnect() =
        runTest {
            withVm {
                confirm()
                // The modal stays up from the tap, through the save, into the wait.
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                vm.onEvent(ScannerEvent.ConfirmPairing)
                runCurrent()
                assertEquals(1, store.saves)
                assertEquals(1, connects)
                assertSame(server, observed)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Down)
                runCurrent()
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(ScannerUiState.Paired, vm.state.value)
                assertEquals(1, store.saves)
            }
        }

    @Test
    fun retryWaitsAgainForTheSavedRecordWithoutConfirmingOrSaving() =
        runTest {
            withVm {
                confirm()
                runCurrent()
                status.value = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)
                runCurrent()
                assertEquals(
                    failed(PairingVerification.Failure.Unavailable),
                    vm.state.value,
                )
                observed = null
                vm.onEvent(ScannerEvent.RetryVerification)
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                runCurrent()
                // The absence reported before Retry does not end the new wait.
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                assertSame(server, observed)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(ScannerUiState.Paired, vm.state.value)
                assertEquals(1, store.saves)
                assertEquals(1, connects)
            }
        }

    @Test
    fun rejectedPairingCannotBeRetried() =
        runTest {
            withVm {
                confirm()
                runCurrent()
                status.value = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
                runCurrent()
                val rejected = vm.state.value
                assertEquals(
                    failed(PairingVerification.Failure.Rejected),
                    rejected,
                )
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                vm.onEvent(ScannerEvent.RetryVerification)
                runCurrent()
                assertSame(rejected, vm.state.value)
                vm.onEvent(ScannerEvent.CancelVerification)
                assertEquals(ScannerUiState.Cancelled, vm.state.value)
                assertEquals(listOf(server), store.saved)
            }
        }

    @Test
    fun cancelDuringTheWaitStopsItAndKeepsTheSavedHost() =
        runTest {
            withVm {
                confirm()
                runCurrent()
                vm.onEvent(ScannerEvent.CancelVerification)
                assertEquals(ScannerUiState.Cancelled, vm.state.value)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                advanceTimeBy(PAIRING_VERIFICATION_DEADLINE_MS)
                runCurrent()
                assertEquals(ScannerUiState.Cancelled, vm.state.value)
                assertEquals(listOf(server), store.saved)
            }
        }

    @Test
    fun deadlineFailsTheWait() =
        runTest {
            withVm {
                confirm()
                runCurrent()
                advanceTimeBy(PAIRING_VERIFICATION_DEADLINE_MS)
                runCurrent()
                assertEquals(
                    failed(PairingVerification.Failure.Deadline),
                    vm.state.value,
                )
            }
        }

    @Test
    fun strayEventsDuringTheWaitKeepTheModal() =
        runTest {
            withVm {
                confirm()
                runCurrent()
                // A rotation re-runs the route's permission check, which re-sends PermissionGranted.
                vm.onEvent(ScannerEvent.PermissionGranted)
                vm.onEvent(ScannerEvent.DeclinePairing)
                vm.onEvent(ScannerEvent.QrDecoded("other"))
                assertEquals(ScannerUiState.Verifying(FINGERPRINT, server), vm.state.value)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(ScannerUiState.Paired, vm.state.value)
                vm.onEvent(ScannerEvent.PermissionGranted)
                assertEquals(ScannerUiState.Paired, vm.state.value)
            }
        }

    @Test
    fun failedSaveShowsTheErrorWithoutDialling() =
        runTest {
            withVm {
                store.failSave = true
                confirm()
                runCurrent()
                assertEquals(ScannerUiState.Error(SAVE_FAILED_MESSAGE), vm.state.value)
                assertEquals(0, connects)
                assertEquals(null, observed)
            }
        }

    private suspend fun TestScope.withVm(block: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val oldSink = RelayLog.sink
        val oldEnabled = RelayLog.enabled
        val logs = mutableListOf<String>()
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
        val f = Fixture()
        try {
            f.block()
            assertFalse(logs.isEmpty())
            for (text in logs) {
                assertFalse(text, text.contains(TOKEN))
                assertFalse(text, text.contains(FINGERPRINT))
            }
            // The fingerprint is public and shown on screen; only the token must never render.
            assertFalse(
                f.vm.state.value
                    .toString()
                    .contains(TOKEN),
            )
        } finally {
            f.vm.viewModelScope.cancel()
            RelayLog.sink = oldSink
            RelayLog.enabled = oldEnabled
            Dispatchers.resetMain()
        }
    }

    private class Fixture {
        val server = pairedServer()
        val store = Store()
        var connects = 0
        var observed: PairedServer? = null
        val status = MutableStateFlow<ConnectionStatus?>(null)
        val vm =
            ScannerViewModel(
                store,
                object : RelayConnectionController {
                    override fun connect() {
                        connects++
                    }

                    override fun close() = Unit
                },
                {
                    observed = it
                    status
                },
            )

        fun failed(failure: PairingVerification.Failure) =
            ScannerUiState.VerificationFailed(FINGERPRINT, server, failure.message, failure.retryable)

        fun confirm() {
            vm.onEvent(ScannerEvent.PermissionGranted)
            vm.onEvent(ScannerEvent.PairingPrepared(FINGERPRINT, server))
            vm.onEvent(ScannerEvent.ConfirmPairing)
        }
    }

    private class Store : PairedServerStore {
        val saved = mutableListOf<PairedServer>()
        val saves get() = saved.size
        var failSave = false

        override suspend fun load() = saved.lastOrNull()

        override suspend fun save(record: PairedServer) {
            if (failSave) throw PairedServerStoreException(TOKEN)
            saved += record
        }
    }

    private companion object {
        const val FINGERPRINT = "32:0b:5e:a9:9e:65:3b:c2"
        const val TOKEN = "tok-123"

        fun pairedServer() =
            PairedServer(
                serverId = "srv-1",
                token = TOKEN,
                relayUrl = "wss://relay.example.com",
                serverStaticPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
    }
}

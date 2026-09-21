package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.PairingParseResult
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.parsePairingPayload
import de.pyryco.mobile.data.network.serverKeyFingerprint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface PairCodePhase {
    data object Editing : PairCodePhase

    data object Confirming : PairCodePhase

    data object Saving : PairCodePhase

    data object Connecting : PairCodePhase

    data object Complete : PairCodePhase

    data object Cancelled : PairCodePhase
}

internal data class PairCodeState(
    val name: String = "",
    val code: String = "",
    val phase: PairCodePhase = PairCodePhase.Editing,
    val confirmation: ScannerUiState.AwaitingConfirm? = null,
    val error: String? = null,
) {
    override fun toString() = "PairCodeState([REDACTED])"
}

internal sealed interface PairCodeEvent {
    class Name(
        val value: String,
    ) : PairCodeEvent

    class Code(
        val value: String,
    ) : PairCodeEvent

    data object Pair : PairCodeEvent

    data object Confirm : PairCodeEvent

    data object Back : PairCodeEvent
}

internal class PairCodeViewModel(
    private val store: PairedServerCollectionStore,
    private val controller: RelayConnectionController,
    private val observe: (PairedServer) -> Flow<ConnectionStatus?>,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PairCodeState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun onEvent(event: PairCodeEvent) {
        val current = state.value
        if (current.phase in setOf(PairCodePhase.Saving, PairCodePhase.Complete, PairCodePhase.Cancelled)) return
        if (event == PairCodeEvent.Back) {
            operation?.cancel()
            mutableState.value =
                current.copy(
                    phase = if (current.phase == PairCodePhase.Confirming) PairCodePhase.Editing else PairCodePhase.Cancelled,
                    confirmation = null,
                )
            RelayLog.d { "event=pair_code_back" }
            return
        }
        if (event == PairCodeEvent.Confirm && current.phase == PairCodePhase.Confirming) {
            val confirmed = current.confirmation ?: return
            mutableState.value = current.copy(phase = PairCodePhase.Saving)
            operation = viewModelScope.launch { persist(confirmed.server, current.name.trim()) }
            return
        }
        if (current.phase != PairCodePhase.Editing) return
        when (event) {
            is PairCodeEvent.Name -> mutableState.value = current.copy(name = event.value)
            is PairCodeEvent.Code -> mutableState.value = current.copy(code = event.value, error = null)
            PairCodeEvent.Pair -> {
                val parsed = parsePairingPayload(current.code.trim()) as? PairingParseResult.Success
                val fingerprint = parsed?.let { serverKeyFingerprint(it.server.serverStaticPublicKey) }
                if (fingerprint == null) {
                    fail("Invalid pairing code", "invalid_code")
                } else {
                    mutableState.value =
                        current.copy(
                            phase = PairCodePhase.Confirming,
                            confirmation = ScannerUiState.AwaitingConfirm(fingerprint, parsed.server),
                            error = null,
                        )
                    RelayLog.d { "event=pair_code_prepared" }
                }
            }
            else -> Unit
        }
    }

    private suspend fun persist(
        server: PairedServer,
        name: String,
    ) {
        var saved = false
        RelayLog.d { "event=pair_code_save_started" }
        confirmPairingAndConnect(
            server,
            store,
            controller,
            onPersisted = { saved = true },
            onFailed = { fail("Could not save pairing. Try again.", "save_failed") },
        )
        if (!saved) return
        try {
            if (name.isNotBlank()) store.setDisplayName(server.serverId, name)
        } catch (_: PairedServerStoreException) {
            fail("Pairing saved, but the host name could not be saved. Retry or cancel.", "name_failed")
            return
        }
        mutableState.value = state.value.copy(phase = PairCodePhase.Connecting, confirmation = null)
        RelayLog.d { "event=pair_code_connection_wait" }
        val terminal =
            withTimeoutOrNull(30_000) {
                observe(server).first {
                    (it?.relay == RelayLinkStatus.Connected && it.pyrycode == PyrycodeLinkStatus.Connected) ||
                        it?.relay == RelayLinkStatus.DaemonAbsent ||
                        it?.relay == RelayLinkStatus.Offline
                }
            }
        if (terminal?.relay == RelayLinkStatus.Connected && terminal.pyrycode == PyrycodeLinkStatus.Connected) {
            mutableState.value = state.value.copy(phase = PairCodePhase.Complete)
            RelayLog.d { "event=pair_code_connected" }
        } else {
            fail("Pairing saved. Host unavailable. Retry or cancel.", if (terminal == null) "deadline" else "unavailable")
        }
    }

    private fun fail(
        message: String,
        code: String,
    ) {
        mutableState.value = state.value.copy(phase = PairCodePhase.Editing, confirmation = null, error = message)
        RelayLog.w { "event=pair_code_failed code=$code" }
    }
}

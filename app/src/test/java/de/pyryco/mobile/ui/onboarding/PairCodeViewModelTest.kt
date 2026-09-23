package de.pyryco.mobile.ui.onboarding

import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.serverKeyFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PairCodeViewModelTest {
    @Test fun validationDeclineAndConfirmBindExactRecordAndLockPersistence() =
        runTest {
            withVm {
                val lowerCasePeer = PairedServerEntry(record.copy(serverId = "b", token = "other-secret"), "Shared")
                store.entries = listOf(lowerCasePeer)
                vm.onEvent(PairCodeEvent.Name("  Shared  "))
                vm.onEvent(PairCodeEvent.Pair)
                assertNotNull(vm.state.value.error)
                vm.onEvent(PairCodeEvent.Code("  $code  "))
                vm.onEvent(PairCodeEvent.Pair)
                assertEquals(
                    serverKeyFingerprint(record.serverStaticPublicKey),
                    vm.state.value.confirmation
                        ?.fingerprint,
                )
                assertEquals(0, store.saves)
                vm.onEvent(PairCodeEvent.Back)
                assertEquals("  $code  ", vm.state.value.code)
                vm.onEvent(PairCodeEvent.Pair)
                val shown =
                    vm.state.value.confirmation
                        ?.server
                store.gate = CompletableDeferred()
                vm.onEvent(PairCodeEvent.Confirm)
                vm.onEvent(PairCodeEvent.Confirm)
                vm.onEvent(PairCodeEvent.Code("replacement"))
                vm.onEvent(PairCodeEvent.Back)
                runCurrent()
                assertEquals(PairCodePhase.Saving, vm.state.value.phase)
                store.gate?.complete(Unit)
                runCurrent()
                assertEquals(record, store.loadById("B")?.record)
                assertSame(shown, store.loadById("B")?.record)
                assertEquals("Shared", store.loadById("B")?.displayName)
                assertEquals(lowerCasePeer, store.loadById("b"))
                assertEquals(2, store.list().size)
                assertEquals(record, observed)
                assertEquals(1, store.saves)
                assertEquals(1, connects)
                assertEquals(PairCodePhase.Connecting, vm.state.value.phase)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(PairCodePhase.Complete, vm.state.value.phase)
            }
        }

    @Test fun failuresRetainDraftAndRetrySameHostWithoutLosingName() =
        runTest {
            withVm {
                vm.onEvent(PairCodeEvent.Code(code))
                vm.onEvent(PairCodeEvent.Name("  Shared  "))
                store.failSave = true
                submit()
                runCurrent()
                assertEquals(0, connects)
                assertTrue(store.list().isEmpty())
                assertFalse(
                    vm.state.value.error
                        .orEmpty()
                        .contains("secret"),
                )
                store.failSave = false
                store.failName = true
                submit()
                runCurrent()
                assertTrue(
                    vm.state.value.error
                        .orEmpty()
                        .contains("Pairing saved"),
                )
                assertEquals(code, vm.state.value.code)
                store.failName = false
                submit()
                runCurrent()
                status.value = ConnectionStatus(RelayLinkStatus.DaemonAbsent, PyrycodeLinkStatus.Down)
                runCurrent()
                assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                assertTrue(
                    vm.state.value.error
                        .orEmpty()
                        .contains("Pairing saved"),
                )
                assertEquals(1, store.list().size)
                vm.onEvent(PairCodeEvent.Name("  "))
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                submit()
                runCurrent()
                assertEquals("Shared", store.list().single().displayName)
                assertEquals(PairCodePhase.Complete, vm.state.value.phase)
            }
        }

    @Test fun deadlineAndCancellationCannotNavigateLater() =
        runTest {
            withVm {
                vm.onEvent(PairCodeEvent.Code(code))
                submit()
                runCurrent()
                advanceTimeBy(29_999)
                runCurrent()
                assertEquals(PairCodePhase.Connecting, vm.state.value.phase)
                advanceTimeBy(1)
                runCurrent()
                assertTrue(
                    vm.state.value.error
                        .orEmpty()
                        .contains("Pairing saved"),
                )
                submit()
                runCurrent()
                vm.onEvent(PairCodeEvent.Back)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(PairCodePhase.Cancelled, vm.state.value.phase)
                assertEquals(null, store.list().single().displayName)
            }
        }

    @Test fun rejectedPairingEndsTheConnectionWaitImmediately() =
        runTest {
            withVm {
                vm.onEvent(PairCodeEvent.Code(code))
                submit()
                runCurrent()
                assertEquals(PairCodePhase.Connecting, vm.state.value.phase)
                status.value = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
                runCurrent()
                assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                assertTrue(
                    vm.state.value.error
                        .orEmpty()
                        .contains("Pairing saved"),
                )
            }
        }

    @Test fun targetModeRefusesACodeForAnotherHostBeforeConfirmation() =
        runTest {
            for (target in listOf("A", "b")) {
                withVm(target) {
                    val other = PairedServerEntry(record.copy(serverId = target, token = "old-secret"), "Pyrybox")
                    store.entries = listOf(other)
                    runCurrent()
                    vm.onEvent(PairCodeEvent.Code(code))
                    vm.onEvent(PairCodeEvent.Pair)
                    vm.onEvent(PairCodeEvent.Confirm)
                    runCurrent()
                    assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                    assertEquals(null, vm.state.value.confirmation)
                    assertEquals(WRONG_HOST_ERROR, vm.state.value.error)
                    assertEquals(0, store.saves)
                    assertEquals(0, connects)
                    assertEquals(listOf(other), store.list())
                }
            }
        }

    @Test fun targetModeReplacesOnlyThatHostAndKeepsItsName() =
        runTest {
            withVm("B") {
                val old = PairedServerEntry(record.copy(token = "old-secret"), "Pyrybox")
                val peer = PairedServerEntry(record.copy(serverId = "C", token = "peer-secret"), "Macbook")
                store.entries = listOf(old, peer)
                assertEquals("B", vm.state.value.targetName)
                runCurrent()
                assertEquals("Pyrybox", vm.state.value.targetName)
                vm.onEvent(PairCodeEvent.Name("Renamed"))
                vm.onEvent(PairCodeEvent.Code(code))
                submit()
                runCurrent()
                assertEquals(record, store.loadById("B")?.record)
                assertEquals("Pyrybox", store.loadById("B")?.displayName)
                assertEquals(peer, store.loadById("C"))
                assertEquals(2, store.list().size)
                assertEquals(0, store.nameWrites)
                assertEquals(1, connects)
                assertEquals(record, observed)
                status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                runCurrent()
                assertEquals(PairCodePhase.Complete, vm.state.value.phase)
            }
        }

    @Test fun targetModeCancelAndFailedSaveLeaveEveryPairingUnchanged() =
        runTest {
            withVm("B") {
                val old = PairedServerEntry(record.copy(token = "old-secret"), "Pyrybox")
                val peer = PairedServerEntry(record.copy(serverId = "C", token = "peer-secret"), "Macbook")
                store.entries = listOf(old, peer)
                runCurrent()
                vm.onEvent(PairCodeEvent.Code(code))
                store.failSave = true
                submit()
                runCurrent()
                assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                assertEquals(listOf(old, peer), store.list())
                assertEquals(0, connects)
                vm.onEvent(PairCodeEvent.Pair)
                vm.onEvent(PairCodeEvent.Back)
                assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                vm.onEvent(PairCodeEvent.Back)
                runCurrent()
                assertEquals(PairCodePhase.Cancelled, vm.state.value.phase)
                assertEquals(listOf(old, peer), store.list())
                assertEquals(0, store.nameWrites)
            }
        }

    @Test fun targetModeRejectedWhileConnectingFailsBeforeTheDeadline() =
        runTest {
            withVm("B") {
                store.entries = listOf(PairedServerEntry(record.copy(token = "old-secret"), "Pyrybox"))
                runCurrent()
                vm.onEvent(PairCodeEvent.Code(code))
                submit()
                runCurrent()
                assertEquals(PairCodePhase.Connecting, vm.state.value.phase)
                advanceTimeBy(1_000)
                status.value = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
                runCurrent()
                assertEquals(PairCodePhase.Editing, vm.state.value.phase)
                assertTrue(
                    vm.state.value.error
                        .orEmpty()
                        .contains("Pairing saved"),
                )
                assertEquals("Pyrybox", store.loadById("B")?.displayName)
            }
        }

    private suspend fun TestScope.withVm(
        target: String? = null,
        block: suspend Fixture.() -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val oldSink = RelayLog.sink
        val logs = mutableListOf<String>()
        RelayLog.sink = { _, _, message -> logs += message }
        val f = Fixture(target)
        try {
            f.block()
            assertFalse(logs.joinToString().contains("secret"))
            assertFalse(logs.joinToString().contains(code))
            assertFalse(
                f.vm.state.value
                    .toString()
                    .contains(code),
            )
        } finally {
            f.vm.viewModelScope.cancel()
            RelayLog.sink = oldSink
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        target: String? = null,
    ) {
        val store = Store()
        var connects = 0
        var observed: PairedServer? = null
        val status = MutableStateFlow<ConnectionStatus?>(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Down))
        val vm =
            PairCodeViewModel(
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
                target,
            )

        fun submit() {
            vm.onEvent(PairCodeEvent.Pair)
            vm.onEvent(PairCodeEvent.Confirm)
        }
    }

    private class Store : PairedServerCollectionStore {
        var entries = emptyList<PairedServerEntry>()
        var saves = 0
        var failSave = false
        var failName = false
        var nameWrites = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun save(record: PairedServer) {
            gate?.await()
            if (failSave) throw PairedServerStoreException("secret")
            saves++
            val name = loadById(record.serverId)?.displayName
            entries = entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, name)
        }

        override suspend fun list() = entries

        override suspend fun load() = entries.lastOrNull()?.record

        override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

        override suspend fun remove(serverId: String) = Unit

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) {
            if (failName) throw PairedServerStoreException("secret")
            nameWrites++
            entries = entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
        }
    }

    private companion object {
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val record = PairedServer("B", "secret", "wss://relay.example", key)
        val code =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                """{"server":"B","token":"secret","relay":"wss://relay.example","server_static_pubkey":"$key"}""".toByteArray(),
            )
    }
}

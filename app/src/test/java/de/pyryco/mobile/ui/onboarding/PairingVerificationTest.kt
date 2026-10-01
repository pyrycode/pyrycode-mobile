package de.pyryco.mobile.ui.onboarding

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PairingVerificationTest {
    private val record = PairedServer("B", "secret", "wss://relay.example", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    private val down = PyrycodeLinkStatus.Down
    private val connected = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)

    @Test fun waitingStatusesKeepWaitingUntilBothLegsConnect() =
        runTest {
            val status = MutableStateFlow<ConnectionStatus?>(null)
            val result = start(status)
            for (relay in listOf(
                RelayLinkStatus.Idle,
                RelayLinkStatus.Connecting,
                RelayLinkStatus.Reconnecting(3),
                RelayLinkStatus.Offline,
                RelayLinkStatus.Connected,
            )) {
                status.value = ConnectionStatus(relay, down)
                runCurrent()
                assertFalse(relay.toString(), result.isCompleted)
            }
            status.value = null
            runCurrent()
            assertFalse(result.isCompleted)
            status.value = connected
            runCurrent()
            assertSame(PairingVerification.Connected, result.await())
        }

    @Test fun offlineThenConnectedSucceeds() =
        runTest {
            val status = MutableStateFlow<ConnectionStatus?>(ConnectionStatus(RelayLinkStatus.Offline, down))
            val result = start(status)
            advanceTimeBy(10_000)
            runCurrent()
            assertFalse(result.isCompleted)
            status.value = connected
            runCurrent()
            assertSame(PairingVerification.Connected, result.await())
        }

    @Test fun terminalStatusesEndTheWaitWithTheirTexts() =
        runTest {
            val cases =
                listOf(
                    RelayLinkStatus.DaemonAbsent to PairingVerification.Failure.Unavailable,
                    RelayLinkStatus.PairingRejected to PairingVerification.Failure.Rejected,
                    RelayLinkStatus.UpdateRequired("1.4.0") to PairingVerification.Failure.UpdateRequired,
                )
            for ((relay, expected) in cases) {
                val result = start(MutableStateFlow(ConnectionStatus(relay, down)))
                runCurrent()
                assertSame(expected, result.await())
            }
            assertEquals(UNAVAILABLE, PairingVerification.Failure.Unavailable.message)
            assertTrue(PairingVerification.Failure.Unavailable.retryable)
            assertEquals(
                "Pairing rejected. The saved host is retained. Cancel, then pair manually with a fresh code.",
                PairingVerification.Failure.Rejected.message,
            )
            assertFalse(PairingVerification.Failure.Rejected.retryable)
            assertEquals(
                "Pairing saved. This app is too old for this host. Update the app, then retry.",
                PairingVerification.Failure.UpdateRequired.message,
            )
            assertTrue(PairingVerification.Failure.UpdateRequired.retryable)
            assertFalse(
                PairingVerification.Failure.UpdateRequired.message
                    .contains("1.4.0"),
            )
        }

    @Test fun thirtySecondsWithoutSuccessFailsRetryably() =
        runTest {
            val result = start(MutableStateFlow(ConnectionStatus(RelayLinkStatus.Offline, down)))
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            val failure = result.await()
            assertSame(PairingVerification.Failure.Deadline, failure)
            assertEquals(UNAVAILABLE, PairingVerification.Failure.Deadline.message)
            assertTrue(PairingVerification.Failure.Deadline.retryable)
        }

    @Test fun aCompletedStatusFlowFailsRetryablyInsteadOfThrowing() =
        runTest {
            val result = start(emptyFlow())
            runCurrent()
            assertSame(PairingVerification.Failure.Deadline, result.await())
        }

    @Test fun retryIgnoresAHeldAbsenceUntilTheStatusChanges() =
        runTest {
            val status = MutableStateFlow<ConnectionStatus?>(ConnectionStatus(RelayLinkStatus.DaemonAbsent, down))
            val result = start(status, retry = true)
            runCurrent()
            assertFalse(result.isCompleted)
            status.value = ConnectionStatus(RelayLinkStatus.Connecting, down)
            runCurrent()
            assertFalse(result.isCompleted)
            status.value = ConnectionStatus(RelayLinkStatus.DaemonAbsent, down)
            runCurrent()
            assertSame(PairingVerification.Failure.Unavailable, result.await())
        }

    private fun TestScope.start(
        status: Flow<ConnectionStatus?>,
        retry: Boolean = false,
    ) = async {
        verifySavedPairing(record, { server ->
            assertSame(record, server)
            status
        }, retry)
    }

    private companion object {
        const val UNAVAILABLE = "The host is temporarily unavailable. The pairing is saved. Retry to wait again, or Cancel."
    }
}

package de.pyryco.mobile.ui.onboarding

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.network.RelayConnectionController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * JVM unit tests for [confirmPairingAndConnect] (#489): the Android-free orchestration behind the
 * Scanner confirm/paste flow. Guards that a successful persist starts the connection loop (AC 1) and,
 * critically, that a failed persist never dials a record that did not save (AC 1 fail-closed).
 */
class PairingConfirmationTest {
    @Test
    fun successfulPersist_savesConnectsAndNavigates() =
        runTest {
            val store = RecordingPairedServerStore()
            val controller = CountingController()
            var persisted = 0
            var failed: PairedServerStoreException? = null

            confirmPairingAndConnect(
                server = PAIRED,
                store = store,
                controller = controller,
                onPersisted = { persisted++ },
                onFailed = { failed = it },
            )

            assertEquals(listOf(PAIRED), store.saved) // the record persisted
            assertEquals(1, controller.connectCalls) // connect() fired exactly once
            assertEquals(1, persisted) // onPersisted (navigate) invoked once
            assertEquals(null, failed) // onFailed not invoked
        }

    @Test
    fun failedPersist_doesNotConnectAndReportsFailure() =
        runTest {
            val boom = PairedServerStoreException("keystore unavailable")
            val store = RecordingPairedServerStore(failWith = boom)
            val controller = CountingController()
            var persisted = 0
            var failed: PairedServerStoreException? = null

            confirmPairingAndConnect(
                server = PAIRED,
                store = store,
                controller = controller,
                onPersisted = { persisted++ },
                onFailed = { failed = it },
            )

            assertEquals(0, controller.connectCalls) // fail-closed: never dial an unpersisted record
            assertEquals(0, persisted) // navigation did not happen
            assertSame(boom, failed) // the failure surfaced to onFailed
        }

    private class RecordingPairedServerStore(
        private val failWith: PairedServerStoreException? = null,
    ) : PairedServerStore {
        val saved = mutableListOf<PairedServer>()

        override suspend fun save(record: PairedServer) {
            failWith?.let { throw it }
            saved += record
        }

        override suspend fun load(): PairedServer? = saved.lastOrNull()
    }

    private class CountingController : RelayConnectionController {
        var connectCalls = 0
            private set

        override fun connect() {
            connectCalls++
        }

        override fun close() = error("close is not exercised by confirmPairingAndConnect")
    }

    private companion object {
        val PAIRED =
            PairedServer(
                serverId = "srv-1",
                token = "tok",
                relayUrl = "ws://localhost/relay",
                serverStaticPublicKey = "key",
            )
    }
}

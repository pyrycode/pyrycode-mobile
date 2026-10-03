package de.pyryco.mobile.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses the installed test application's real Android lifecycle owners during a graph rebuild. */
@RunWith(AndroidJUnit4::class)
class PeerIdentityLifecycleTest {
    @Test
    fun sequentialPeersRetainIdentityAfterCloseAndAppGraphRebuild() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as E2eTestApplication
        val pairing =
            PairedServer(
                serverId = UUID.randomUUID().toString(),
                token = "synthetic-lifecycle-token",
                relayUrl = "ws://localhost:1234",
                serverStaticPublicKey = "unused-without-a-dial",
            )
        val first = SecondClientPeer(pairing)
        val expected =
            try {
                runBlocking { first.keyStore.loadOrCreate(pairing.serverId) }
            } finally {
                first.close()
            }
        instrumentation.runOnMainSync { application.rebuildGraph() }

        val second = SecondClientPeer(pairing.copy())
        try {
            runBlocking {
                val actual = second.keyStore.loadOrCreate(pairing.serverId)
                assertTrue("peer public identity survives close and graph rebuild", expected.publicKey.contentEquals(actual.publicKey))
                assertTrue("peer private identity survives close and graph rebuild", expected.privateKey.contentEquals(actual.privateKey))
                actual.privateKey.fill(0)
                val reloaded = second.keyStore.loadOrCreate(pairing.serverId)
                assertTrue("peer reload returns an intact copy", expected.privateKey.contentEquals(reloaded.privateKey))
                reloaded.privateKey.fill(0)
            }
        } finally {
            expected.privateKey.fill(0)
            second.close()
        }
    }
}

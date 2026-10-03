package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

class PeerDeviceStaticKeyStoreTest {
    private val pairing =
        PairedServer(
            serverId = UUID.randomUUID().toString(),
            token = "synthetic-peer-token",
            relayUrl = "ws://localhost:1234",
            serverStaticPublicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
        )

    @Test
    fun `sequential stores retain the host and token identity`() =
        runTest {
            val original = PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId)
            val nextPairing = pairing.copy(relayUrl = "ws://localhost:5678")
            val next = PeerDeviceStaticKeyStore(nextPairing).loadOrCreate(nextPairing.serverId)

            assertSameIdentity(original, next)
        }

    @Test
    fun `different hosts using the same token have independent identities`() =
        runTest {
            val other = pairing.copy(serverId = pairing.serverId + "-other")
            assertDifferentIdentity(
                PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId),
                PeerDeviceStaticKeyStore(other).loadOrCreate(other.serverId),
            )
        }

    @Test
    fun `different tokens on the same host have independent identities`() =
        runTest {
            val other = pairing.copy(token = "other-synthetic-peer-token")
            assertDifferentIdentity(
                PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId),
                PeerDeviceStaticKeyStore(other).loadOrCreate(other.serverId),
            )
        }

    @Test
    fun `zeroing caller key copies cannot corrupt the retained identity`() =
        runTest {
            val store = PeerDeviceStaticKeyStore(pairing)
            val expected = store.loadOrCreate(pairing.serverId)
            val disposable = store.loadOrCreate(pairing.serverId)
            disposable.privateKey.fill(0)
            disposable.publicKey.fill(0)
            store.publicKey(pairing.serverId).fill(0)

            assertSameIdentity(expected, store.loadOrCreate(pairing.serverId))
            assertSameIdentity(expected, PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId))
        }

    @Test
    fun `concurrent store instances publish one identity`() =
        runTest {
            val pairs =
                List(24) {
                    async(Dispatchers.Default) { PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId) }
                }.awaitAll()

            pairs.drop(1).forEach { assertSameIdentity(pairs.first(), it) }
        }

    @Test
    fun `another server cannot read the bound store`() =
        runTest {
            val store = PeerDeviceStaticKeyStore(pairing)
            assertTrue(runCatching { store.loadOrCreate("other-host") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { store.publicKey("other-host") }.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun `session close and a reconstructed factory preserve reconnect and reload keys`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val expected = PeerDeviceStaticKeyStore(pairing).loadOrCreate(pairing.serverId)
            repeat(3) {
                val store = PeerDeviceStaticKeyStore(pairing.copy())
                val pairingStore =
                    object : PairedServerStore {
                        override suspend fun load(): PairedServer = pairing

                        override suspend fun save(record: PairedServer): Unit = error("fixed test pairing")
                    }
                val factory = NoiseSessionFactory(store, pairingStore, NoiseClientInfo("test-peer", "test"), dispatcher)
                val session = factory.create()
                try {
                    session.writeInit()
                    val reloaded = factory.reloadDeviceStaticKey()
                    assertTrue("reload retains private identity", expected.privateKey.contentEquals(reloaded))
                    reloaded.fill(0)
                } finally {
                    session.close()
                }
                assertSameIdentity(expected, store.loadOrCreate(pairing.serverId))
            }
        }

    private fun assertSameIdentity(
        expected: DeviceStaticKeyPair,
        actual: DeviceStaticKeyPair,
    ) {
        assertTrue("public identity retained", expected.publicKey.contentEquals(actual.publicKey))
        assertTrue("private identity retained", expected.privateKey.contentEquals(actual.privateKey))
    }

    private fun assertDifferentIdentity(
        first: DeviceStaticKeyPair,
        second: DeviceStaticKeyPair,
    ) {
        assertFalse("public identities isolated", first.publicKey.contentEquals(second.publicKey))
        assertFalse("private identities isolated", first.privateKey.contentEquals(second.privateKey))
    }
}

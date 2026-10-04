package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.crypto.PairedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PeerDeviceKeyStoreTest {
    @Test
    fun recreatedPeerKeepsTheIdentityAlreadyBoundToItsPairing() =
        runTest {
            val pairing = pairing()
            val first = PeerDeviceKeyStore(pairing).loadOrCreate(pairing.serverId)
            val expectedPublic = first.publicKey.copyOf()
            val expectedPrivate = first.privateKey.copyOf()

            // NoiseSessionFactory wipes its private-key copy; a consumer may mutate either returned array.
            first.privateKey.fill(0)
            first.publicKey.fill(0)
            val recreated = PeerDeviceKeyStore(pairing.copy(relayUrl = "ws://replacement")).loadOrCreate(pairing.serverId)

            assertTrue("a later scenario changed the pairing's bound identity", expectedPublic.contentEquals(recreated.publicKey))
            assertTrue("factory wiping destroyed the retained identity", expectedPrivate.contentEquals(recreated.privateKey))
            assertNotSame(first.publicKey, recreated.publicKey)
            assertNotSame(first.privateKey, recreated.privateKey)
        }

    @Test
    fun differentPairingsOnOneHostKeepIndependentIdentities() =
        runTest {
            val phonePairing = pairing()
            val peerPairing = phonePairing.copy(token = "another-test-device")
            val phone = PeerDeviceKeyStore(phonePairing).loadOrCreate(phonePairing.serverId)
            val peer = PeerDeviceKeyStore(peerPairing).loadOrCreate(peerPairing.serverId)

            assertFalse("distinct tokens shared an identity", phone.publicKey.contentEquals(peer.publicKey))
        }

    @Test
    fun differentHostsKeepIndependentIdentitiesEvenWithTheSameToken() =
        runTest {
            val first = pairing()
            val second = first.copy(serverId = UUID.randomUUID().toString())
            val a = PeerDeviceKeyStore(first).loadOrCreate(first.serverId)
            val b = PeerDeviceKeyStore(second).loadOrCreate(second.serverId)

            assertFalse("distinct hosts shared an identity", a.publicKey.contentEquals(b.publicKey))
        }

    @Test
    fun publicKeyReadsCannotMutateTheRetainedIdentity() =
        runTest {
            val pairing = pairing()
            val store = PeerDeviceKeyStore(pairing)
            val expected = store.loadOrCreate(pairing.serverId).publicKey
            store.publicKey(pairing.serverId).fill(0)

            assertTrue("public-key read exposed the retained array", expected.contentEquals(store.publicKey(pairing.serverId)))
        }

    @Test
    fun simultaneousFirstReadersPublishOneIdentity() =
        runTest {
            val pairing = pairing()
            val start = CompletableDeferred<Unit>()
            val readers =
                List(12) {
                    async(Dispatchers.Default) {
                        start.await()
                        PeerDeviceKeyStore(pairing).loadOrCreate(pairing.serverId)
                    }
                }
            start.complete(Unit)
            val keys = readers.awaitAll()

            assertTrue(
                "racing first readers produced competing identities",
                keys.all { it.publicKey.contentEquals(keys.first().publicKey) },
            )
            assertTrue("readers shared a mutable private-key array", keys.drop(1).all { it.privateKey !== keys.first().privateKey })
        }

    @Test
    fun wrongHostReadsRefuseBeforeReturningKeyMaterial() =
        runTest {
            val store = PeerDeviceKeyStore(pairing())
            val load = runCatching { store.loadOrCreate("another-host") }
            val publicRead = runCatching { store.publicKey("another-host") }

            assertTrue("wrong-host load succeeded", load.exceptionOrNull() is IllegalArgumentException)
            assertTrue("wrong-host public-key read succeeded", publicRead.exceptionOrNull() is IllegalArgumentException)
        }

    private fun pairing() =
        PairedServer(
            serverId = UUID.randomUUID().toString(),
            token = "test-pairing-token",
            relayUrl = "ws://test-relay",
            serverStaticPublicKey = "unused-test-server-key",
        )
}

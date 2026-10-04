package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
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
    fun sequentialFactoriesPresentSameBoundIdentityInFreshNoiseHandshakes() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val responderKey = Noise.createDH("25519")
            val responderPrivateKey = ByteArray(32)
            try {
                responderKey.generateKeyPair()
                responderKey.getPrivateKey(responderPrivateKey, 0)
                val responderPublicKey = ByteArray(32).also { responderKey.getPublicKey(it, 0) }
                val server = pairing.copy(serverStaticPublicKey = Base64.getEncoder().encodeToString(responderPublicKey))
                val identities = mutableListOf<ByteArray>()
                val handshakes = mutableListOf<ByteArray>()
                repeat(2) {
                    val pairingStore =
                        object : PairedServerStore {
                            override suspend fun load(): PairedServer = server

                            override suspend fun save(record: PairedServer): Unit = error("fixed test pairing")
                        }
                    val session =
                        NoiseSessionFactory(
                            PeerDeviceStaticKeyStore(server.copy()),
                            pairingStore,
                            NoiseClientInfo("test-peer", "test"),
                            dispatcher,
                        ).create()
                    val responder = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER)
                    try {
                        responder.localKeyPair.setPrivateKey(responderPrivateKey, 0)
                        responder.start()
                        val init = session.writeInit()
                        val plaintext = ByteArray(init.size)
                        val length = responder.readMessage(init, 0, init.size, plaintext, 0)
                        val envelope = MobileJson.decodeFromString<Envelope>(String(plaintext, 0, length, Charsets.UTF_8))
                        val hello = MobileJson.decodeFromJsonElement<HelloClientPayload>(envelope.payload)
                        assertTrue("handshake carries hello", envelope.type == "hello")
                        assertTrue("successive peers use the same synthetic pairing", hello.token == server.token)
                        identities += ByteArray(32).also { responder.remotePublicKey.getPublicKey(it, 0) }
                        handshakes += init
                    } finally {
                        responder.destroy()
                        session.close()
                    }
                }
                assertTrue("successive handshakes present the token-bound static identity", identities[0].contentEquals(identities[1]))
                assertFalse("successive peers still generate fresh handshake state", handshakes[0].contentEquals(handshakes[1]))
            } finally {
                responderPrivateKey.fill(0)
                responderKey.destroy()
            }
        }

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

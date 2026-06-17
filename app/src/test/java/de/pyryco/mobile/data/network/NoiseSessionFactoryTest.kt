package de.pyryco.mobile.data.network

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyException
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring tests for [NoiseSessionFactory] (#298): it resolves the device static key
 * (#291) and the PairedServer record (#294) into a ready-to-handshake session, and
 * collapses every setup failure into a single [NoiseSessionException] for the WS
 * client (#276) to catch. Faked stores keep this a pure-JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoiseSessionFactoryTest {
    private val clientInfo = NoiseClientInfo("Pixel-Test", "1.0.0-test")
    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun create_notPaired_throwsNoiseSessionException() =
        runTest(dispatcher) {
            val factory =
                factory(
                    deviceStore = ThrowingDeviceStore,
                    pairedStore = FakePairedServerStore(record = null),
                )
            assertCreateFails(factory)
        }

    @Test
    fun create_invalidServerStaticKey_throwsNoiseSessionException() =
        runTest(dispatcher) {
            val factory =
                factory(
                    deviceStore = FakeDeviceStaticKeyStore(newDeviceKeyPair()),
                    pairedStore = FakePairedServerStore(pairedRecord(serverStaticPublicKey = base64StdEncode(ByteArray(31)))),
                )
            assertCreateFails(factory)
        }

    @Test
    fun create_deviceKeyFailure_wrapsAsNoiseSessionExceptionPreservingCause() =
        runTest(dispatcher) {
            val cause = DeviceStaticKeyException("needs re-pair")
            val factory =
                factory(
                    deviceStore = FakeDeviceStaticKeyStore(error = cause),
                    pairedStore = FakePairedServerStore(pairedRecord()),
                )
            // The original cause is preserved in the chain (coroutine stack-trace recovery may
            // wrap the thrown exception, so walk the chain rather than compare the direct cause).
            val thrown = assertCreateFails(factory)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cause })
        }

    @Test
    fun create_happyPath_buildsSessionAndKeysByServerId() =
        runTest(dispatcher) {
            val deviceStore = FakeDeviceStaticKeyStore(newDeviceKeyPair())
            val factory =
                factory(
                    deviceStore = deviceStore,
                    pairedStore =
                        FakePairedServerStore(
                            pairedRecord(
                                serverId = "srv-42",
                                serverStaticPublicKey = base64StdEncode(newPublicKey()),
                            ),
                        ),
                )

            val session = factory.create()

            assertEquals("srv-42", deviceStore.requestedServerId)
            // The returned session is ready to drive a handshake (NEW state).
            assertNotNull(session.writeInit())
        }

    // ---- #416: the factory forwards the last_event_id supplier into the session
    @Test
    fun create_forwardsLastEventIdSupplierIntoHello() =
        runTest(dispatcher) {
            // A responder keypair we control so the session's writeInit() can be decrypted and the
            // hello recovered — its public key is the paired server static the session pins.
            val responder = HandshakeState(PROTO, HandshakeState.RESPONDER)
            responder.localKeyPair.generateKeyPair()
            val serverStatic = ByteArray(responder.localKeyPair.publicKeyLength)
            responder.localKeyPair.getPublicKey(serverStatic, 0)
            responder.start()

            val factory =
                factory(
                    deviceStore = FakeDeviceStaticKeyStore(newDeviceKeyPair()),
                    pairedStore = FakePairedServerStore(pairedRecord(serverStaticPublicKey = base64StdEncode(serverStatic))),
                    lastEventId = { 9L },
                )

            val init = factory.create().writeInit()
            val buf = ByteArray(init.size)
            val n = responder.readMessage(init, 0, init.size, buf, 0)
            val envelope = MobileJson.decodeFromString<Envelope>(String(buf, 0, n, Charsets.UTF_8))
            val hello = MobileJson.decodeFromJsonElement<HelloClientPayload>(envelope.payload)
            assertEquals(9L, hello.lastEventId)
        }

    // ---- Fakes + helpers -------------------------------------------------------

    private fun factory(
        deviceStore: DeviceStaticKeyStore,
        pairedStore: PairedServerStore,
        lastEventId: () -> Long? = { null },
    ) = NoiseSessionFactory(
        deviceStaticKeyStore = deviceStore,
        pairedServerStore = pairedStore,
        clientInfo = clientInfo,
        ioDispatcher = dispatcher,
        lastEventId = lastEventId,
    )

    private fun pairedRecord(
        serverId: String = "srv-1",
        serverStaticPublicKey: String = base64StdEncode(newPublicKey()),
    ) = PairedServer(
        serverId = serverId,
        token = "tok",
        relayUrl = "wss://relay.example",
        serverStaticPublicKey = serverStaticPublicKey,
    )

    private class FakePairedServerStore(
        private val record: PairedServer?,
    ) : PairedServerStore {
        override suspend fun load(): PairedServer? = record

        override suspend fun save(record: PairedServer) = throw UnsupportedOperationException()
    }

    private class FakeDeviceStaticKeyStore(
        private val keyPair: DeviceStaticKeyPair? = null,
        private val error: Throwable? = null,
    ) : DeviceStaticKeyStore {
        var requestedServerId: String? = null
            private set

        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair {
            requestedServerId = serverId
            error?.let { throw it }
            return keyPair ?: throw DeviceStaticKeyException("no key")
        }

        override suspend fun publicKey(serverId: String): ByteArray? = keyPair?.publicKey
    }

    private object ThrowingDeviceStore : DeviceStaticKeyStore {
        override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair =
            throw AssertionError("device key store must not be touched when unpaired")

        override suspend fun publicKey(serverId: String): ByteArray? = null
    }

    private companion object {
        const val PROTO = "Noise_IK_25519_ChaChaPoly_BLAKE2s"

        fun newDeviceKeyPair(): DeviceStaticKeyPair {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            val priv = ByteArray(dh.privateKeyLength).also { dh.getPrivateKey(it, 0) }
            val pub = ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
            return DeviceStaticKeyPair(publicKey = pub, privateKey = priv)
        }

        fun newPublicKey(): ByteArray {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            return ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
        }

        /** Asserts [NoiseSessionFactory.create] fails with [NoiseSessionException]; returns it. */
        suspend fun assertCreateFails(factory: NoiseSessionFactory): NoiseSessionException {
            try {
                factory.create()
            } catch (e: NoiseSessionException) {
                return e
            }
            throw AssertionError("expected NoiseSessionException")
        }
    }
}

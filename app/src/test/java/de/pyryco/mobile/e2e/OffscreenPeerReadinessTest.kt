package de.pyryco.mobile.e2e

import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.HelloAckPayload
import de.pyryco.mobile.data.network.HelloClientPayload
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

/** #1692: the observing peer can authenticate and answer a probe after a prior peer closed. */
class OffscreenPeerReadinessTest {
    @Test
    fun sequentialClosedPeersCompleteBoundHandshakeAndEncryptedReadinessProbe() = assertSequentialReadiness(::PeerDeviceStaticKeyStore)

    /** #1682: exercise the actual live peer store, independently of the older store's registry. */
    @Test
    fun contextAskPeersCompleteBoundHandshakeAndEncryptedReadinessProbe() = assertSequentialReadiness(::PeerDeviceKeyStore)

    private fun assertSequentialReadiness(keyStore: (PairedServer) -> DeviceStaticKeyStore) =
        runTest {
            val responderKey = Noise.createDH("25519")
            val responderPrivate = ByteArray(32)
            try {
                responderKey.generateKeyPair()
                responderKey.getPrivateKey(responderPrivate, 0)
                val publicKey = ByteArray(32).also { responderKey.getPublicKey(it, 0) }
                val pairing =
                    PairedServer(
                        serverId = UUID.randomUUID().toString(),
                        token = "synthetic-offscreen-peer-token",
                        relayUrl = "ws://localhost:1234",
                        serverStaticPublicKey = Base64.getEncoder().encodeToString(publicKey),
                    )
                var boundIdentity: ByteArray? = null
                repeat(2) { index ->
                    val store =
                        object : PairedServerStore {
                            override suspend fun load(): PairedServer = pairing

                            override suspend fun save(record: PairedServer): Unit = error("fixed test pairing")
                        }
                    val session =
                        NoiseSessionFactory(
                            keyStore(pairing.copy()),
                            store,
                            NoiseClientInfo("test-peer", "test"),
                            StandardTestDispatcher(testScheduler),
                        ).create()
                    val responder = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER)
                    try {
                        responder.localKeyPair.setPrivateKey(responderPrivate, 0)
                        responder.start()
                        val init = session.writeInit()
                        val helloBytes = ByteArray(init.size)
                        val helloLength = responder.readMessage(init, 0, init.size, helloBytes, 0)
                        val hello = MobileJson.decodeFromString<Envelope>(String(helloBytes, 0, helloLength, Charsets.UTF_8))
                        val payload = MobileJson.decodeFromJsonElement<HelloClientPayload>(hello.payload)
                        assertTrue(
                            "authenticated hello carries the synthetic pairing",
                            hello.type == "hello" && payload.token == pairing.token,
                        )
                        val identity = ByteArray(32).also { responder.remotePublicKey.getPublicKey(it, 0) }
                        val previous = boundIdentity
                        if (previous == null) {
                            boundIdentity = identity
                        } else {
                            assertTrue("observing peer authenticates with the prior token-bound key", previous.contentEquals(identity))
                        }

                        val ack =
                            envelope(
                                2,
                                "hello_ack",
                                MobileJson.encodeToJsonElement(
                                    HelloAckPayload(protocolVersion = "v2", serverId = pairing.serverId, connId = "test-connection-$index"),
                                ),
                            )
                        val ackBytes = MobileJson.encodeToString(ack).toByteArray()
                        val response = ByteArray(ackBytes.size + 96)
                        val responseLength = responder.writeMessage(response, 0, ackBytes, 0, ackBytes.size)
                        session.readResp(response.copyOf(responseLength))
                        val ciphers = responder.split()
                        try {
                            val probe = envelope(10, "list_conversations", JsonObject(emptyMap()))
                            val ciphertext = session.encrypt(MobileJson.encodeToString(probe).toByteArray())
                            val plaintext = ByteArray(ciphertext.size)
                            val length = ciphers.receiver.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)
                            val request = MobileJson.decodeFromString<Envelope>(String(plaintext, 0, length, Charsets.UTF_8))
                            assertTrue(
                                "settlement sends the readiness probe",
                                request.type == "list_conversations" && request.id == probe.id,
                            )
                            val reply =
                                envelope(11, "conversations", JsonObject(mapOf("conversations" to JsonArray(emptyList()))))
                                    .copy(inReplyTo = request.id)
                            val replyBytes = MobileJson.encodeToString(reply).toByteArray()
                            val encryptedReply = ByteArray(replyBytes.size + 16)
                            val replyLength = ciphers.sender.encryptWithAd(null, replyBytes, 0, encryptedReply, 0, replyBytes.size)
                            val answered =
                                MobileJson.decodeFromString<Envelope>(
                                    session.decrypt(encryptedReply.copyOf(replyLength)).toString(Charsets.UTF_8),
                                )
                            assertTrue(
                                "settlement receives the correlated encrypted reply",
                                answered.type == "conversations" && answered.inReplyTo == probe.id,
                            )
                        } finally {
                            ciphers.destroy()
                        }
                    } finally {
                        responder.destroy()
                        session.close()
                    }
                }
            } finally {
                responderPrivate.fill(0)
                responderKey.destroy()
            }
        }

    private fun envelope(
        id: Long,
        type: String,
        payload: kotlinx.serialization.json.JsonElement,
    ) = Envelope(id = id, type = type, ts = "2026-10-04T00:00:00Z", payload = payload)
}

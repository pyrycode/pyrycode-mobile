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
import de.pyryco.mobile.data.network.NoiseIkSession
import de.pyryco.mobile.data.network.NoiseSessionFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Settlement, beyond hello: the same bound peer token must reach an answered probe after close. */
class PeerSettlingIdentityTest {
    @Test
    fun sequentialPeerSessionsAnswerCorrelatedProbesForTheBoundToken() =
        runTest {
            BindingResponder().use { daemon ->
                repeat(2) {
                    val pairing = daemon.pairing.copy()
                    val session = factory(pairing, PeerDeviceStaticKeyStore(pairing), StandardTestDispatcher(testScheduler)).create()
                    try {
                        assertTrue("a replacement peer must authenticate and settle", daemon.settle(session))
                    } finally {
                        session.close()
                    }
                }
                assertEquals("both fresh sessions answered their own probe", 2, daemon.answeredProbes)
            }
        }

    @Test
    fun aFreshStaticIdentityForAnAlreadyBoundTokenCannotReachItsProbe() =
        runTest {
            BindingResponder().use { daemon ->
                val dispatcher = StandardTestDispatcher(testScheduler)
                val first = factory(daemon.pairing, PeerDeviceStaticKeyStore(daemon.pairing), dispatcher).create()
                try {
                    assertTrue("the first peer binds its identity", daemon.settle(first))
                } finally {
                    first.close()
                }
                // Negative control: retain the original hello token but substitute another static key,
                // reproducing the old per-peer key lifecycle without relaxing the responder's binding.
                val differentIdentity = PeerDeviceStaticKeyStore(daemon.pairing.copy(token = "synthetic-other-key-owner"))
                val replacement = factory(daemon.pairing, differentIdentity, dispatcher).create()
                try {
                    assertFalse("the bound token rejects a substituted identity", daemon.settle(replacement))
                    assertEquals("a rejected handshake sends no probe", 1, daemon.answeredProbes)
                } finally {
                    replacement.close()
                }
            }
        }

    private fun factory(
        pairing: PairedServer,
        keyStore: DeviceStaticKeyStore,
        dispatcher: CoroutineDispatcher,
    ) = NoiseSessionFactory(
        keyStore,
        object : PairedServerStore {
            override suspend fun load(): PairedServer = pairing

            override suspend fun save(record: PairedServer): Unit = error("fixed synthetic pairing")
        },
        NoiseClientInfo("test-peer", "test"),
        dispatcher,
    )

    private class BindingResponder : AutoCloseable {
        private val key = Noise.createDH("25519").apply { generateKeyPair() }
        private val privateKey = ByteArray(32).also { key.getPrivateKey(it, 0) }
        private var boundIdentity: ByteArray? = null
        var answeredProbes = 0
            private set
        val pairing =
            PairedServer(
                serverId = UUID.randomUUID().toString(),
                token = "synthetic-bound-peer-token",
                relayUrl = "ws://localhost:1234",
                serverStaticPublicKey = Base64.getEncoder().encodeToString(ByteArray(32).also { key.getPublicKey(it, 0) }),
            )

        fun settle(session: NoiseIkSession): Boolean {
            val handshake = HandshakeState("Noise_IK_25519_ChaChaPoly_BLAKE2s", HandshakeState.RESPONDER)
            try {
                handshake.localKeyPair.setPrivateKey(privateKey, 0)
                handshake.start()
                val init = session.writeInit()
                val helloBytes = ByteArray(init.size)
                val helloLength = handshake.readMessage(init, 0, init.size, helloBytes, 0)
                val envelope = MobileJson.decodeFromString<Envelope>(String(helloBytes, 0, helloLength, Charsets.UTF_8))
                val hello = MobileJson.decodeFromJsonElement<HelloClientPayload>(envelope.payload)
                assertTrue("hello carries the test token", hello.token == pairing.token)
                val identity = ByteArray(32).also { handshake.remotePublicKey.getPublicKey(it, 0) }
                val bound = boundIdentity
                if (bound != null && !MessageDigest.isEqual(bound, identity)) return false
                boundIdentity = identity

                val ack = envelope("hello_ack", MobileJson.encodeToJsonElement(HelloAckPayload("v2", pairing.serverId, "test-connection")))
                val response = ByteArray(ack.size + 96)
                val length = handshake.writeMessage(response, 0, ack, 0, ack.size)
                session.readResp(response.copyOf(length))
                val ciphers = handshake.split()
                try {
                    val encryptedProbe = session.encrypt(envelope("list_conversations", JsonObject(emptyMap())))
                    val plaintext = ByteArray(encryptedProbe.size)
                    val probeLength = ciphers.receiver.decryptWithAd(null, encryptedProbe, 0, plaintext, 0, encryptedProbe.size)
                    val probe = MobileJson.decodeFromString<Envelope>(String(plaintext, 0, probeLength, Charsets.UTF_8))
                    assertEquals("the settlement probe", "list_conversations", probe.type)
                    val answer = envelope("conversations", JsonObject(emptyMap()), inReplyTo = probe.id)
                    val ciphertext = ByteArray(answer.size + 16)
                    ciphers.sender.encryptWithAd(null, answer, 0, ciphertext, 0, answer.size)
                    val reply = MobileJson.decodeFromString<Envelope>(session.decrypt(ciphertext).decodeToString())
                    assertEquals("only the correlated answer settles this session", probe.id, reply.inReplyTo)
                    answeredProbes += 1
                    return true
                } finally {
                    ciphers.destroy()
                }
            } finally {
                handshake.destroy()
            }
        }

        private fun envelope(
            type: String,
            payload: kotlinx.serialization.json.JsonElement,
            inReplyTo: Long? = null,
        ): ByteArray =
            MobileJson
                .encodeToString(
                    Envelope(id = 1, type = type, ts = "2026-10-04T00:00:00Z", payload = payload, inReplyTo = inReplyTo),
                ).toByteArray()

        override fun close() {
            privateKey.fill(0)
            key.destroy()
        }
    }
}

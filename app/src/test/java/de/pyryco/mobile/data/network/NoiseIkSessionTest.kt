package de.pyryco.mobile.data.network

import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import com.southernstorm.noise.protocol.Noise
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.BadPaddingException

/**
 * Lifecycle tests for the Noise_IK initiator session (#298): handshake → split →
 * AEAD transport, driven against an in-test responder that mirrors the Go
 * `flynn/noise` peer the 2026-05-29 spike proved interop with. JVM-only — the
 * vendored `noise-java` suite is pure Java and the session takes raw bytes, so the
 * whole lifecycle runs without a device (mirrors NoiseSuiteSmokeTest.kt).
 */
class NoiseIkSessionTest {
    private val clientInfo = NoiseClientInfo(deviceName = "Pixel-Test", clientVersion = "1.0.0-test")

    // ---- AC #1: full handshake recovers conn_id --------------------------------

    @Test
    fun handshake_recoversConnIdFromHelloAck() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)

        responder.readInit(session.writeInit())
        val msg2 = responder.writeResp(ackEnvelope(connId = "conn-xyz"))

        assertEquals("conn-xyz", session.readResp(msg2))
        assertEquals("conn-xyz", session.connId)
    }

    @Test
    fun handshake_carriesHelloTokenAsEncryptedEarlyData() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "secret-tok", clientInfo)

        val helloJson = responder.readInit(session.writeInit())

        // The hello envelope is recovered only after the responder decrypts msg1 — the
        // token rode inside the encrypted early-data, not a plaintext header.
        val envelope = MobileJson.decodeFromString<Envelope>(helloJson)
        assertEquals("hello", envelope.type)
        val hello = MobileJson.decodeFromJsonElement<HelloClientPayload>(envelope.payload)
        assertEquals("secret-tok", hello.token)
        assertEquals("Pixel-Test", hello.deviceName)
    }

    // ---- AC #2: transport round-trip + ciphertext length -----------------------

    @Test
    fun transport_roundTripsBothDirections() {
        val established = establish()
        val plaintext = "list_conversations".toByteArray(Charsets.UTF_8)

        // Outbound: session.sender encrypts -> responder.receiver decrypts.
        val ciphertext = established.session.encrypt(plaintext)
        val recovered = ByteArray(ciphertext.size)
        val recoveredLen =
            established.responderPair.receiver.decryptWithAd(null, ciphertext, 0, recovered, 0, ciphertext.size)
        assertArrayEquals(plaintext, recovered.copyOf(recoveredLen))

        // Inbound: responder.sender encrypts -> session.receiver decrypts.
        val reply = "conversations".toByteArray(Charsets.UTF_8)
        val replyCt = ByteArray(reply.size + 16)
        val replyCtLen = established.responderPair.sender.encryptWithAd(null, reply, 0, replyCt, 0, reply.size)
        assertArrayEquals(reply, established.session.decrypt(replyCt.copyOf(replyCtLen)))
    }

    @Test
    fun encrypt_ciphertextIsPlaintextPlusPoly1305Tag() {
        val established = establish()
        for (size in listOf(0, 1, 32, 4096)) {
            assertEquals(size + 16, established.session.encrypt(ByteArray(size)).size)
        }
    }

    // ---- AC #3: empty AD / correct direction / empty prologue are load-bearing -

    @Test
    fun transport_crossingDirectionFailsMac() {
        val established = establish()
        val ciphertext = established.session.encrypt("hi".toByteArray(Charsets.UTF_8))

        // The initiator's outbound pairs with the responder's RECEIVER, not its sender.
        assertThrowsBadPadding {
            established.responderPair.sender.decryptWithAd(null, ciphertext, 0, ByteArray(ciphertext.size), 0, ciphertext.size)
        }
    }

    @Test
    fun transport_nonEmptyAssociatedDataFailsMac() {
        val established = establish()
        val ciphertext = established.session.encrypt("hi".toByteArray(Charsets.UTF_8))

        // Empty/null AD is load-bearing: decrypting with non-empty AD MAC-fails.
        assertThrowsBadPadding {
            established.responderPair.receiver.decryptWithAd(
                byteArrayOf(1, 2, 3),
                ciphertext,
                0,
                ByteArray(ciphertext.size),
                0,
                ciphertext.size,
            )
        }
    }

    @Test
    fun handshake_nonEmptyPrologueOnInitiatorBreaksHandshake() {
        // The session itself never sets a prologue; this raw-initiator guard proves WHY
        // the empty-prologue choice is load-bearing — a non-empty prologue diverges the
        // handshake hash and the responder (empty prologue) rejects msg1.
        val responder = TestResponder()
        val initiator = HandshakeState(PROTO, HandshakeState.INITIATOR)
        initiator.localKeyPair.setPrivateKey(newPrivateKey(), 0)
        initiator.remotePublicKey.setPublicKey(responder.staticPublicKey, 0)
        initiator.setPrologue(byteArrayOf(9, 9, 9), 0, 3)
        initiator.start()

        val hello = "{}".toByteArray(Charsets.UTF_8)
        val msg1 = ByteArray(hello.size + 96)
        val n = initiator.writeMessage(msg1, 0, hello, 0, hello.size)
        assertThrowsBadPadding { responder.readInit(msg1.copyOf(n)) }
        initiator.destroy()
    }

    // ---- Clean failure surfaces (NoiseSessionException, not raw library leak) ---

    @Test
    fun handshake_wrongServerStaticKeyIsRejectedOnTheWire() {
        // A session built with the wrong remote static cannot complete the handshake:
        // the responder fails to decrypt msg1 (es/ss diverge). This is the trust
        // anchor — a MITM without the server's private half cannot interoperate.
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), newPublicKey(), "tok", clientInfo)
        assertThrowsBadPadding { responder.readInit(session.writeInit()) }
    }

    @Test
    fun readResp_tamperedResponseThrowsNoiseSessionException() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val msg2 = responder.writeResp(ackEnvelope("conn-1"))
        msg2[msg2.size - 1] = (msg2[msg2.size - 1].toInt() xor 0x01).toByte()

        assertThrows(NoiseSessionException::class.java) { session.readResp(msg2) }
    }

    @Test
    fun readResp_malformedHelloAckThrowsNoiseSessionException() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val msg2 = responder.writeResp(ackEnvelope(connId = "conn-1", type = "not_hello_ack"))

        assertThrows(NoiseSessionException::class.java) { session.readResp(msg2) }
    }

    @Test
    fun readResp_helloAckMissingConnIdThrowsNoiseSessionException() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val envelopeWithoutConnId =
            MobileJson.encodeToString(
                Envelope(
                    id = 2L,
                    type = "hello_ack",
                    ts = "2026-05-31T00:00:00Z",
                    payload = MobileJson.parseToJsonElement("""{"protocol_version":"v2","server_id":"s"}"""),
                ),
            )
        val msg2 = responder.writeResp(envelopeWithoutConnId)

        assertThrows(NoiseSessionException::class.java) { session.readResp(msg2) }
    }

    // ---- State guards (caller bugs -> IllegalStateException) --------------------

    @Test
    fun encrypt_beforeHandshakeThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { freshSession().encrypt(byteArrayOf(1)) }
    }

    @Test
    fun decrypt_beforeHandshakeThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { freshSession().decrypt(ByteArray(16)) }
    }

    @Test
    fun connId_beforeHandshakeThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { freshSession().connId }
    }

    @Test
    fun writeInit_calledTwiceThrowsIllegalState() {
        val session = freshSession()
        session.writeInit()
        assertThrows(IllegalStateException::class.java) { session.writeInit() }
    }

    @Test
    fun close_isIdempotentAndOpsAfterCloseThrowIllegalState() {
        val established = establish()
        established.session.close()
        established.session.close() // idempotent — no throw

        assertThrows(IllegalStateException::class.java) { established.session.encrypt(byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { established.session.decrypt(ByteArray(16)) }
    }

    // ---- Helpers ---------------------------------------------------------------

    private fun freshSession() = NoiseIkSession(newPrivateKey(), newPublicKey(), "tok", clientInfo)

    private data class Established(
        val session: NoiseIkSession,
        val responderPair: CipherStatePair,
        val connId: String,
    )

    private fun establish(connId: String = "conn-xyz"): Established {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val returned = session.readResp(responder.writeResp(ackEnvelope(connId)))
        return Established(session, responder.split(), returned)
    }

    private fun ackEnvelope(
        connId: String,
        type: String = "hello_ack",
    ): String {
        val payload = HelloAckPayload(protocolVersion = "v2", serverId = "srv-1", connId = connId)
        return MobileJson.encodeToString(
            Envelope(
                id = 2L,
                type = type,
                ts = "2026-05-31T00:00:00Z",
                payload = MobileJson.encodeToJsonElement(payload),
            ),
        )
    }

    /**
     * In-test mirror of the Go flynn/noise responder. Drives the IK responder leg so the
     * initiator session is exercised against a real peer, not a stub.
     */
    private class TestResponder {
        private val handshake = HandshakeState(PROTO, HandshakeState.RESPONDER)
        val staticPublicKey: ByteArray

        init {
            handshake.localKeyPair.generateKeyPair()
            staticPublicKey = ByteArray(handshake.localKeyPair.publicKeyLength)
            handshake.localKeyPair.getPublicKey(staticPublicKey, 0)
            handshake.start()
        }

        /** Reads msg1 (noise_init); returns the recovered `hello` envelope JSON. */
        fun readInit(init: ByteArray): String {
            val buf = ByteArray(init.size)
            val n = handshake.readMessage(init, 0, init.size, buf, 0)
            return String(buf, 0, n, Charsets.UTF_8)
        }

        /** Writes msg2 (noise_resp) carrying [ackEnvelopeJson] as `hello_ack` early-data. */
        fun writeResp(ackEnvelopeJson: String): ByteArray {
            val payload = ackEnvelopeJson.toByteArray(Charsets.UTF_8)
            val out = ByteArray(payload.size + 96)
            val n = handshake.writeMessage(out, 0, payload, 0, payload.size)
            return out.copyOf(n)
        }

        /** Splits into transport ciphers (responder mirror-swaps, like flynn/noise). */
        fun split(): CipherStatePair = handshake.split()
    }

    private companion object {
        const val PROTO = "Noise_IK_25519_ChaChaPoly_BLAKE2s"

        /** Mints a raw 32-byte X25519 private key, as the device keystore (#291) hands out. */
        fun newPrivateKey(): ByteArray {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            return ByteArray(dh.privateKeyLength).also { dh.getPrivateKey(it, 0) }
        }

        /** Mints a raw 32-byte X25519 public key (a stand-in remote static for guard tests). */
        fun newPublicKey(): ByteArray {
            val dh = Noise.createDH("25519")
            dh.generateKeyPair()
            return ByteArray(dh.publicKeyLength).also { dh.getPublicKey(it, 0) }
        }

        fun assertThrowsBadPadding(block: () -> Unit) {
            assertThrows(BadPaddingException::class.java) { block() }
        }
    }
}

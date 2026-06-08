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
import org.junit.Assert.assertTrue
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

    // ---- #401: advertise + surface the negotiated interactive capability -------

    @Test
    fun handshake_advertisesInteractiveCapabilityInHello() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)

        val helloJson = responder.readInit(session.writeInit())

        val envelope = MobileJson.decodeFromString<Envelope>(helloJson)
        val hello = MobileJson.decodeFromJsonElement<HelloClientPayload>(envelope.payload)
        assertTrue(hello.capabilities.contains(CAPABILITY_INTERACTIVE))
    }

    @Test
    fun readResp_helloAckEchoingInteractiveSurfacesGrantedSet() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val msg2 = responder.writeResp(ackEnvelope("conn-1", capabilities = listOf("interactive")))

        assertEquals("conn-1", session.readResp(msg2))
        assertEquals(setOf("interactive"), session.negotiatedCapabilities)
    }

    @Test
    fun readResp_helloAckOmittingCapabilitiesSurfacesEmptySetAndKeepsConnId() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val msg2 = responder.writeResp(ackEnvelope("conn-1")) // daemon echoes no capabilities

        assertEquals("conn-1", session.readResp(msg2))
        assertTrue(session.negotiatedCapabilities.isEmpty())
    }

    @Test
    fun readResp_helloAckWithNonArrayCapabilitiesThrowsNoiseSessionException() {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        // capabilities as a JSON string (not an array) is malformed at the untrusted-parse boundary.
        val malformed =
            MobileJson.encodeToString(
                Envelope(
                    id = 2L,
                    type = "hello_ack",
                    ts = "2026-05-31T00:00:00Z",
                    payload =
                        MobileJson.parseToJsonElement(
                            """{"protocol_version":"v2","server_id":"s","conn_id":"c","capabilities":"interactive"}""",
                        ),
                ),
            )
        val msg2 = responder.writeResp(malformed)

        assertThrows(NoiseSessionException::class.java) { session.readResp(msg2) }
    }

    @Test
    fun negotiatedCapabilities_beforeHandshakeThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { freshSession().negotiatedCapabilities }
    }

    // ---- AC #1/#2/#4: re-key happy path + atomic swap --------------------------

    @Test
    fun rekey_roundTripsUnderNewKeysAfterAtomicSwap() {
        val established = establish()
        val leg = established.responder.rekey(established.session.writeRekeyInit(newPrivateKey()))
        established.session.readRekeyResp(leg.resp)

        // Outbound under the NEW pair: session.sender -> responder.receiver (correct assignment;
        // a crossed swap MAC-fails here). Ciphertext is still plaintext + 16-byte tag.
        val plaintext = "after_rekey".toByteArray(Charsets.UTF_8)
        val ciphertext = established.session.encrypt(plaintext)
        assertEquals(plaintext.size + 16, ciphertext.size)
        val recovered = ByteArray(ciphertext.size)
        val recoveredLen = leg.pair.receiver.decryptWithAd(null, ciphertext, 0, recovered, 0, ciphertext.size)
        assertArrayEquals(plaintext, recovered.copyOf(recoveredLen))

        // Inbound under the NEW pair: responder.sender -> session.receiver.
        val reply = "ok".toByteArray(Charsets.UTF_8)
        val replyCt = ByteArray(reply.size + 16)
        val replyCtLen = leg.pair.sender.encryptWithAd(null, reply, 0, replyCt, 0, reply.size)
        assertArrayEquals(reply, established.session.decrypt(replyCt.copyOf(replyCtLen)))
    }

    @Test
    fun rekey_frameSealedUnderOldKeysFailsAfterSwap() {
        val established = establish()
        // Seal a frame with the pre-re-key responder sender, before the swap.
        val stale = "stale".toByteArray(Charsets.UTF_8)
        val staleCt = ByteArray(stale.size + 16)
        val staleLen = established.responderPair.sender.encryptWithAd(null, stale, 0, staleCt, 0, stale.size)

        val leg = established.responder.rekey(established.session.writeRekeyInit(newPrivateKey()))
        established.session.readRekeyResp(leg.resp)

        // The old keys were wiped on swap: the new receiver MAC-fails the stale frame (AC #2/#4).
        assertThrows(NoiseSessionException::class.java) { established.session.decrypt(staleCt.copyOf(staleLen)) }
    }

    @Test
    fun rekey_noiseInitCarriesEmptyEarlyData() {
        val established = establish()
        // The re-key noise_init re-sends no hello/token — only the bare handshake is the signal.
        val leg = established.responder.rekey(established.session.writeRekeyInit(newPrivateKey()))
        assertEquals(0, leg.earlyData.size)
    }

    // ---- AC #3: peer-static continuity — a different server static fails + retains

    @Test
    fun rekey_responseFromDifferentServerStaticIsRejectedAndRetainsOldKeys() {
        val established = establish()
        established.session.writeRekeyInit(newPrivateKey()) // re-key now in flight

        // A noise_resp produced by a server holding a DIFFERENT static (rotated key / MITM)
        // cannot satisfy the pinned-rs handshake state, so readRekeyResp MAC-fails.
        assertThrows(NoiseSessionException::class.java) { established.session.readRekeyResp(foreignRekeyResp()) }

        // The live pair is RETAINED: a round-trip against the ORIGINAL responder pair still works.
        val plaintext = "still_here".toByteArray(Charsets.UTF_8)
        val ciphertext = established.session.encrypt(plaintext)
        val recovered = ByteArray(ciphertext.size)
        val recoveredLen = established.responderPair.receiver.decryptWithAd(null, ciphertext, 0, recovered, 0, ciphertext.size)
        assertArrayEquals(plaintext, recovered.copyOf(recoveredLen))

        // pendingRekey was cleared on failure: a re-key is retryable (unlike the initial handshake).
        established.session.writeRekeyInit(newPrivateKey())
    }

    // ---- Re-key state guards (caller bugs -> IllegalStateException) -------------

    @Test
    fun readRekeyResp_withNoRekeyInFlightThrowsIllegalState() {
        val established = establish()
        assertThrows(IllegalStateException::class.java) { established.session.readRekeyResp(ByteArray(48)) }
    }

    @Test
    fun writeRekeyInit_calledTwiceWithoutRespThrowsIllegalState() {
        val established = establish()
        established.session.writeRekeyInit(newPrivateKey())
        assertThrows(IllegalStateException::class.java) { established.session.writeRekeyInit(newPrivateKey()) }
    }

    @Test
    fun writeRekeyInit_beforeEstablishedThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { freshSession().writeRekeyInit(newPrivateKey()) }
    }

    @Test
    fun writeRekeyInit_nonThirtyTwoByteKeyThrowsIllegalArgument() {
        val established = establish()
        assertThrows(IllegalArgumentException::class.java) { established.session.writeRekeyInit(ByteArray(31)) }
    }

    @Test
    fun rekeyOps_afterCloseThrowIllegalState() {
        val established = establish()
        established.session.close()
        assertThrows(IllegalStateException::class.java) { established.session.writeRekeyInit(newPrivateKey()) }
        assertThrows(IllegalStateException::class.java) { established.session.readRekeyResp(ByteArray(48)) }
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
        val responder: TestResponder,
        val responderPair: CipherStatePair,
        val connId: String,
    )

    private fun establish(connId: String = "conn-xyz"): Established {
        val responder = TestResponder()
        val session = NoiseIkSession(newPrivateKey(), responder.staticPublicKey, "tok", clientInfo)
        responder.readInit(session.writeInit())
        val returned = session.readResp(responder.writeResp(ackEnvelope(connId)))
        return Established(session, responder, responder.split(), returned)
    }

    /**
     * A well-formed re-key `noise_resp` produced by an INDEPENDENT server with a DIFFERENT static
     * key — the on-the-wire shape of a rotated server static or a relay-operator MITM. Fed to a
     * session pinned to the original `rs`, it MAC-fails (the pinned-rs handshake state diverges).
     */
    private fun foreignRekeyResp(): ByteArray {
        val foreignResponder = TestResponder()
        val foreignSession = NoiseIkSession(newPrivateKey(), foreignResponder.staticPublicKey, "tok", clientInfo)
        foreignResponder.readInit(foreignSession.writeInit())
        foreignSession.readResp(foreignResponder.writeResp(ackEnvelope("conn-foreign")))
        return foreignResponder.rekey(foreignSession.writeRekeyInit(newPrivateKey())).resp
    }

    private fun ackEnvelope(
        connId: String,
        type: String = "hello_ack",
        capabilities: List<String> = emptyList(),
    ): String {
        val payload = HelloAckPayload(protocolVersion = "v2", serverId = "srv-1", connId = connId, capabilities = capabilities)
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

        /** The responder's own raw static private key, retained to key the fresh re-key handshake. */
        private val staticPrivateKey: ByteArray

        init {
            handshake.localKeyPair.generateKeyPair()
            staticPublicKey = ByteArray(handshake.localKeyPair.publicKeyLength)
            handshake.localKeyPair.getPublicKey(staticPublicKey, 0)
            staticPrivateKey = ByteArray(handshake.localKeyPair.privateKeyLength)
            handshake.localKeyPair.getPrivateKey(staticPrivateKey, 0)
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

        /**
         * Drives the responder leg of a re-key: a FRESH responder handshake re-using this
         * responder's own static key (so the initiator's pinned `rs` continuity holds), reads the
         * re-key noise_init (recovering its empty early-data), writes a noise_resp with empty
         * early-data, and splits (responder mirror-swap) into the fresh transport pair.
         */
        fun rekey(init: ByteArray): RekeyLeg {
            val hs = HandshakeState(PROTO, HandshakeState.RESPONDER)
            hs.localKeyPair.setPrivateKey(staticPrivateKey, 0)
            hs.start()
            val earlyBuf = ByteArray(init.size)
            val earlyLen = hs.readMessage(init, 0, init.size, earlyBuf, 0)
            val out = ByteArray(96)
            val n = hs.writeMessage(out, 0, ByteArray(0), 0, 0)
            val pair = hs.split()
            hs.destroy()
            return RekeyLeg(resp = out.copyOf(n), pair = pair, earlyData = earlyBuf.copyOf(earlyLen))
        }
    }

    /** The artefacts of one responder re-key leg: the noise_resp bytes, the fresh transport pair, and the recovered early-data. */
    private class RekeyLeg(
        val resp: ByteArray,
        val pair: CipherStatePair,
        val earlyData: ByteArray,
    )

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

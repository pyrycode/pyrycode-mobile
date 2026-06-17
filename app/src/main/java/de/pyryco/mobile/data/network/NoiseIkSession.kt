package de.pyryco.mobile.data.network

import com.southernstorm.noise.protocol.CipherState
import com.southernstorm.noise.protocol.CipherStatePair
import com.southernstorm.noise.protocol.HandshakeState
import kotlinx.datetime.Clock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/** The Android-resolved `hello` identity, injected so the session stays `android.*`-free. */
data class NoiseClientInfo(
    val deviceName: String,
    val clientVersion: String,
)

/**
 * A handshake, transport, or setup failure in the Noise_IK session. The message names the
 * failure CATEGORY only — never key material, plaintext, token, raw frame bytes, or any
 * secret (carries #291/#273's no-secrets-in-logs posture forward). Wrapped causes
 * ([BadPaddingException], [DeviceStaticKeyException][de.pyryco.mobile.data.crypto.DeviceStaticKeyException])
 * likewise carry no secrets.
 */
class NoiseSessionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The phone's leg of the Mobile Protocol v2 encrypted transport: a `Noise_IK_25519_ChaChaPoly_BLAKE2s`
 * **initiator** that drives the handshake, recovers the server's `conn_id`, splits into transport
 * ciphers, and exposes AEAD encrypt/decrypt over byte arrays. Proven against the Go `flynn/noise`
 * responder by the 2026-05-29 spike. The session is byte-array in / byte-array out — the WS framing,
 * base64, and frame-size cap belong to the WS client (#276). The in-place re-key mechanism
 * ([writeRekeyInit]/[readRekeyResp]) lands here (#303); its 1-hour timer / `rekey_request` triggers
 * stay at the WS pump.
 *
 * Lifecycle: `NEW ──writeInit()──▶ AWAITING_RESP ──readResp()──▶ ESTABLISHED ──close()──▶ CLOSED`.
 * `encrypt`/`decrypt` are valid only while ESTABLISHED; re-key ([writeRekeyInit] then
 * [readRekeyResp]) runs in place over an established session without pausing transport. Calling out
 * of order (e.g. `encrypt` before `readResp`, `writeInit` twice, any op after `close`) is a caller
 * bug and throws [IllegalStateException]; runtime protocol/crypto failures throw [NoiseSessionException].
 *
 * **Not a `data class`** — it retains [token] until [writeInit], and a generated `toString` would
 * print it to any crash frame. The identity `toString` leaks nothing.
 *
 * **Threading.** The expected usage is a single WS message pump (one outbound and one inbound op
 * at a time), so concurrent same-direction calls should not occur. As a deterministic backstop
 * against the catastrophic, silent nonce-reuse a same-direction race would cause under
 * ChaCha20-Poly1305, [encrypt]/[decrypt]/[close] are `@Synchronized` — as are [writeRekeyInit] and
 * [readRekeyResp], so the re-key CipherState swap cannot interleave with an in-flight same-direction
 * AEAD op. The lock is the floor, not a license to fan out — keep to the single-pump contract.
 *
 * Not resumable: a failed or closed session is discarded and re-created (Noise ephemerals are
 * per-handshake).
 */
class NoiseIkSession(
    localStaticPrivateKey: ByteArray,
    remoteStaticPublicKey: ByteArray,
    token: String,
    private val clientInfo: NoiseClientInfo,
    // The replay cursor (#416), read **live** at [writeInit] (inside [buildHello]) — never captured at
    // construction — so each reconnect advertises the cursor as of its own hello-build. Default `{ null }`
    // omits `last_event_id` (a fresh connection with nothing observed). Invoked once per session.
    private val lastEventId: () -> Long? = { null },
) {
    private enum class State { NEW, AWAITING_RESP, ESTABLISHED, CLOSED }

    private var state: State = State.NEW

    /** The `hello` secret; held only until [writeInit] seals it into the encrypted early-data. */
    private var pendingToken: String? = token
    private var handshake: HandshakeState?
    private var ciphers: CipherStatePair? = null
    private var sender: CipherState? = null
    private var receiver: CipherState? = null
    private var establishedConnId: String? = null
    private var negotiatedCaps: Set<String>? = null

    /** The pinned server static (`rs`); the re-key continuity anchor. Public key — no wipe needed. */
    private val pinnedRemoteStatic: ByteArray = remoteStaticPublicKey.copyOf()

    /** The in-flight re-key handshake (holds the re-loaded device `s`); `null` ⟺ no re-key in flight. */
    private var pendingRekey: HandshakeState? = null

    init {
        require(localStaticPrivateKey.size == KEY_SIZE) { "local static key must be 32 bytes" }
        require(remoteStaticPublicKey.size == KEY_SIZE) { "remote static key must be 32 bytes" }
        val hs =
            try {
                HandshakeState(PROTOCOL, HandshakeState.INITIATOR)
            } catch (e: NoSuchAlgorithmException) {
                throw NoiseSessionException("noise suite unavailable", e)
            }
        // setPrivateKey derives the matching public key; setPublicKey pins the server static (rs).
        hs.localKeyPair.setPrivateKey(localStaticPrivateKey, 0)
        hs.remotePublicKey.setPublicKey(remoteStaticPublicKey, 0)
        hs.start() // never setPrologue — the empty prologue is load-bearing for Go interop
        handshake = hs
    }

    /** The established `conn_id`. Throws [IllegalStateException] until the handshake completes. */
    val connId: String
        get() = establishedConnId ?: throw IllegalStateException("conn_id is not available until the handshake completes")

    /**
     * The capability set the daemon granted in `hello_ack` (the intersection of what the phone
     * advertised with what the daemon supports); empty when the daemon echoed none (#401). A
     * membership set — `CAPABILITY_INTERACTIVE in negotiatedCapabilities` answers "is it granted?".
     * Throws [IllegalStateException] until the handshake completes, mirroring [connId].
     */
    val negotiatedCapabilities: Set<String>
        get() = negotiatedCaps ?: throw IllegalStateException("negotiated capabilities are not available until the handshake completes")

    /**
     * Builds the `noise_init` frame: the `hello` envelope (with the token) sealed as encrypted
     * early-data. Returns the raw frame bytes for the WS client to base64-wrap and send.
     * Valid once, from NEW. → AWAITING_RESP.
     */
    fun writeInit(): ByteArray {
        check(state == State.NEW) { "writeInit() is valid once, before readResp()" }
        val hs = handshake ?: throw IllegalStateException("session is closed")
        val hello = buildHello()
        val out = ByteArray(hello.size + IK_MSG1_OVERHEAD)
        val n = hs.writeMessage(out, 0, hello, 0, hello.size)
        pendingToken = null // sealed into the ciphertext — drop the reference
        state = State.AWAITING_RESP
        return out.copyOf(n)
    }

    /**
     * Reads the `noise_resp` frame: recovers the `hello_ack` early-data, splits into transport
     * ciphers, and returns the established `conn_id`. Valid once, from AWAITING_RESP. → ESTABLISHED.
     * Throws [NoiseSessionException] on a handshake MAC failure (wrong `rs` / suite / tampered msg2)
     * or a malformed `hello_ack`.
     */
    fun readResp(resp: ByteArray): String {
        check(state == State.AWAITING_RESP) { "readResp() requires exactly one prior writeInit()" }
        val hs = handshake ?: throw IllegalStateException("session is closed")
        val ackBuf = ByteArray(resp.size)
        try {
            val ackLen =
                try {
                    hs.readMessage(resp, 0, resp.size, ackBuf, 0)
                } catch (e: BadPaddingException) {
                    throw NoiseSessionException("noise_resp handshake verification failed", e)
                } catch (e: ShortBufferException) {
                    throw NoiseSessionException("malformed noise_resp", e)
                }
            check(hs.action == HandshakeState.SPLIT) { "noise handshake did not complete" }
            val ack = parseHelloAck(ackBuf, ackLen)
            val pair = hs.split() // initiator does NOT swap: sender = encrypt-out, receiver = decrypt-in
            ciphers = pair
            sender = pair.sender
            receiver = pair.receiver
            establishedConnId = ack.connId
            negotiatedCaps = ack.capabilities.toSet() // wire List → membership Set, dedup/normalise here
            state = State.ESTABLISHED
            return ack.connId
        } catch (e: Throwable) {
            state = State.CLOSED
            throw e
        } finally {
            // The split cipher pair is independent (forked keys); destroying the handshake here
            // wipes the device-key copy + handshake secrets while transport keys survive.
            hs.destroy()
            handshake = null
        }
    }

    /**
     * Begins a re-key: builds a fresh IK initiator handshake against the **same pinned `rs`** using
     * the caller-supplied (freshly re-loaded) device static [s], with **empty** handshake early-data,
     * and returns the raw `noise_init` bytes for the WS client to frame and send. Transport stays
     * valid on the CURRENT keys until [readRekeyResp].
     *
     * The caller MUST zero [s] after this returns — the session copies it into the handshake (the
     * only place `s` lives across the re-key) and does not retain it, preserving #298's bounded
     * device-static-key RAM window. Requires an established session with no re-key already in flight.
     */
    @Synchronized
    fun writeRekeyInit(s: ByteArray): ByteArray {
        check(state == State.ESTABLISHED) { "writeRekeyInit() requires an established session" }
        check(pendingRekey == null) { "a re-key is already in flight" }
        require(s.size == KEY_SIZE) { "device static key must be 32 bytes" }
        val hs =
            try {
                HandshakeState(PROTOCOL, HandshakeState.INITIATOR)
            } catch (e: NoSuchAlgorithmException) {
                throw NoiseSessionException("noise suite unavailable", e)
            }
        hs.localKeyPair.setPrivateKey(s, 0) // derives the matching public key
        hs.remotePublicKey.setPublicKey(pinnedRemoteStatic, 0) // re-key against the SAME pinned rs
        hs.start() // never setPrologue — the empty prologue is load-bearing, same as the initial handshake
        val out = ByteArray(IK_MSG1_OVERHEAD) // empty early-data: just the 96-byte IK msg1 overhead
        val n = hs.writeMessage(out, 0, EMPTY_EARLY_DATA, 0, 0)
        pendingRekey = hs
        return out.copyOf(n)
    }

    /**
     * Completes a re-key: reads the `noise_resp`, derives the fresh transport pair, and **atomically**
     * swaps it for the live pair under the session lock, **wiping** the old pair. The re-key
     * `noise_resp` carries empty early-data, so no `conn_id` is parsed (it is unchanged across re-key).
     *
     * On ANY handshake/crypto failure the live pair is **RETAINED** (transport continues on the current
     * keys) and a [NoiseSessionException] is thrown — a forged/rotated/MITM `noise_resp` (not produced
     * by the pinned `rs`'s private half) fails the handshake MAC and cannot down a working session.
     * The re-key handshake (and its copy of `s`) is wiped regardless of outcome, leaving re-key
     * retryable. Requires a re-key in flight.
     */
    @Synchronized
    fun readRekeyResp(resp: ByteArray) {
        check(state == State.ESTABLISHED) { "readRekeyResp() requires an established session" }
        val hs = pendingRekey ?: throw IllegalStateException("no re-key in flight")
        try {
            val earlyDataBuf = ByteArray(resp.size)
            try {
                hs.readMessage(resp, 0, resp.size, earlyDataBuf, 0) // empty early-data; recovered length discarded
            } catch (e: BadPaddingException) {
                throw NoiseSessionException("noise_resp rekey verification failed", e)
            } catch (e: ShortBufferException) {
                throw NoiseSessionException("malformed noise_resp", e)
            }
            if (hs.action != HandshakeState.SPLIT) {
                throw NoiseSessionException("noise rekey handshake did not complete")
            }
            // initiator does NOT swap: sender = encrypt-out, receiver = decrypt-in (same as readResp).
            val newPair = hs.split()
            // Atomic swap (the critical section, under the session lock): rotate, then wipe the old keys.
            val oldPair = ciphers
            ciphers = newPair
            sender = newPair.sender
            receiver = newPair.receiver
            oldPair?.destroy()
        } finally {
            // Wipe the re-key handshake + its copy of `s` regardless of outcome (the bounded RAM window).
            hs.destroy()
            pendingRekey = null
        }
    }

    /** Encrypts [plaintext] for the server. Returns ciphertext (plaintext + 16-byte tag). ESTABLISHED only. */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cs = senderOrThrow()
        val out = ByteArray(plaintext.size + MAC_SIZE)
        cs.encryptWithAd(null, plaintext, 0, out, 0, plaintext.size) // AD = null always
        return out
    }

    /** Decrypts a server [ciphertext] (plaintext + 16-byte tag). ESTABLISHED only. */
    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val cs = receiverOrThrow()
        if (ciphertext.size < MAC_SIZE) {
            throw NoiseSessionException("ciphertext shorter than the MAC tag")
        }
        val out = ByteArray(ciphertext.size)
        val len =
            try {
                cs.decryptWithAd(null, ciphertext, 0, out, 0, ciphertext.size) // AD = null always
            } catch (e: BadPaddingException) {
                throw NoiseSessionException("frame authentication failed", e)
            } catch (e: ShortBufferException) {
                throw NoiseSessionException("malformed frame", e)
            }
        return out.copyOf(len)
    }

    /** Wipes the handshake and transport ciphers. Idempotent; subsequent ops throw [IllegalStateException]. */
    @Synchronized
    fun close() {
        handshake?.destroy()
        handshake = null
        pendingRekey?.destroy() // wipe an abandoned mid-re-key handshake + its copy of `s`
        pendingRekey = null
        ciphers?.destroy()
        ciphers = null
        sender = null
        receiver = null
        pendingToken = null
        state = State.CLOSED
    }

    private fun senderOrThrow(): CipherState {
        check(state == State.ESTABLISHED) { "encrypt() requires an established session" }
        return sender ?: throw IllegalStateException("session is closed")
    }

    private fun receiverOrThrow(): CipherState {
        check(state == State.ESTABLISHED) { "decrypt() requires an established session" }
        return receiver ?: throw IllegalStateException("session is closed")
    }

    private fun buildHello(): ByteArray {
        val token = pendingToken ?: throw IllegalStateException("session is closed")
        val hello =
            HelloClientPayload(
                deviceName = clientInfo.deviceName,
                clientVersion = clientInfo.clientVersion,
                token = token,
                lastEventId = lastEventId(), // live read at hello-build (#416); null → omitted on encode
            )
        val envelope =
            Envelope(
                id = 1L,
                type = "hello",
                ts = Clock.System.now().toString(),
                payload = MobileJson.encodeToJsonElement(hello),
            )
        return MobileJson.encodeToString(envelope).toByteArray(Charsets.UTF_8)
    }

    private fun parseHelloAck(
        ackBuf: ByteArray,
        ackLen: Int,
    ): HelloAckPayload {
        val envelope =
            try {
                MobileJson.decodeFromString<Envelope>(String(ackBuf, 0, ackLen, Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw NoiseSessionException("malformed hello_ack", e)
            }
        if (envelope.type != "hello_ack") {
            throw NoiseSessionException("malformed hello_ack")
        }
        return try {
            MobileJson.decodeFromJsonElement<HelloAckPayload>(envelope.payload)
        } catch (e: SerializationException) {
            throw NoiseSessionException("malformed hello_ack", e)
        }
    }

    private companion object {
        const val PROTOCOL = "Noise_IK_25519_ChaChaPoly_BLAKE2s"
        const val KEY_SIZE = 32
        const val MAC_SIZE = 16

        /** IK msg1 overhead over the payload: 32-byte `e` + 48-byte encrypted `s` + 16-byte payload MAC. */
        const val IK_MSG1_OVERHEAD = 96

        /** The re-key `noise_init` carries no early-data (the handshake itself is the signal). */
        val EMPTY_EARLY_DATA = ByteArray(0)
    }
}

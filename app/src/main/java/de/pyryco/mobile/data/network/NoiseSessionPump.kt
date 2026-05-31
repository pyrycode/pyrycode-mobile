package de.pyryco.mobile.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the `Noise_IK` handshake over a connected relay WS transport (#306) and runs the open-state
 * `noise_msg` decrypt/dispatch loop on top of a [NoiseIkSession] (#303) — the mobile mirror of the Go
 * binary's `V2SessionManager`. It executes `protocol-mobile.md` § Connection lifecycle → Phone
 * **steps 3–6**: send `noise_init` → await `noise_resp` → derive transport ciphers → send/receive
 * `noise_msg`. Encrypted-session consumers (the remote conversation repository #278, the re-key
 * triggers #304) attach to **one** running session here instead of each re-implementing the handshake
 * and the decrypt loop.
 *
 * **Owns:** a connection-scoped [CoroutineScope], the [NoiseIkSession] built via
 * [NoiseSessionFactory.create], the handshake drive to an open session, the single inbound collector
 * that decrypts each `noise_msg`, the outbound encrypt+frame path, and clean lifecycle teardown tied
 * to the transport. **Does not own:** the socket / reconnect / backoff (#306 + #307), the crypto state
 * machine or re-key mechanism (#303), or any typed wire↔domain mapping (#278 builds that on [inbound]).
 *
 * The pump receives a transport that is already `Up`; it never calls [RelayTransport.connect]. It is
 * the **sole** collector of [RelayTransport.inbound] for the connection's lifetime — the handshake
 * `noise_resp` then every open-state `noise_msg` share one sequential collector (steps 3 and 5 below).
 * It does **not** read `events` (that is #307's). Single-use and not resumable: a fresh connection
 * builds a fresh pump (Noise ephemerals are per-handshake). It emits **no logs** — every failure
 * surfaces only via [state] as [PumpState.Closed], whose `cause` carries a category-only message.
 */
class NoiseSessionPump(
    private val transport: RelayTransport,
    private val sessionFactory: NoiseSessionFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mutableState = MutableStateFlow<PumpState>(PumpState.Handshaking)

    /** Handshake-completion + lifecycle signal: `Handshaking → Open(connId) → Closed(cause)`. */
    val state: StateFlow<PumpState> = mutableState.asStateFlow()

    private val inboundChannel = Channel<Envelope>(Channel.BUFFERED)

    /** Hot, **single-consumer** stream of decrypted application envelopes. Lossless and in-order: a
     *  slow/absent consumer applies backpressure all the way down to the transport. Completes on teardown. */
    val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

    @Volatile
    private var session: NoiseIkSession? = null

    private val started = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)

    /** Guards the outbound encrypt→enqueue pair so wire order == AEAD nonce order (see [send]). */
    private val outboundLock = Any()

    /** Launches the single session-drive coroutine. Single-use: a second call is a caller bug. */
    fun start() {
        check(started.compareAndSet(false, true)) { "start() is single-use" }
        scope.launch { drive() }
    }

    /**
     * Encrypts [envelope] and enqueues it as a `noise_msg`. Returns `false` (no frame sent) unless the
     * session is [PumpState.Open], or if a racing teardown closed the session under us. Mirrors
     * [RelayTransport.send]'s non-throwing `Boolean` contract.
     */
    fun send(envelope: Envelope): Boolean {
        if (mutableState.value !is PumpState.Open) return false
        val session = this.session ?: return false
        // The encrypt→enqueue pair is one critical section: the AEAD nonce is a per-session monotonic
        // counter the wire does not carry, so if two concurrent sends enqueue out of encrypt order the
        // receiver MAC-fails the reordered frame. The lock makes wire order == nonce order.
        return synchronized(outboundLock) {
            try {
                val ciphertext = session.encrypt(MobileJson.encodeToString(envelope).encodeToByteArray())
                transport.send(InnerFrameV2(type = TYPE_NOISE_MSG, data = base64StdEncode(ciphertext)))
            } catch (e: IllegalStateException) {
                // A teardown closed the session between the state check and encrypt: no frame emitted,
                // so no nonce was consumed. Fail closed rather than throw.
                false
            }
        }
    }

    /** Idempotent teardown: closes the session (wiping keys), the transport, and the pump scope. */
    fun close() {
        teardown(null)
    }

    /** Phone steps 3–6: drive the handshake to Open, then run the open-state dispatch loop. */
    private suspend fun drive() {
        val session =
            try {
                sessionFactory.create()
            } catch (e: NoiseSessionException) {
                teardown(e)
                return
            }
        this.session = session

        // Step 3: send noise_init (the hello + token ride sealed inside, built by writeInit()).
        transport.send(InnerFrameV2(type = TYPE_NOISE_INIT, data = base64StdEncode(session.writeInit())))

        // Step 4: await the first inbound frame, bounded by the 10 s noise_resp deadline. A timeout
        // (null) or an early Down (inbound completes empty → NoSuchElementException) tears the session
        // down. This .first() and the step-5 .collect() are the SAME sequential consumer of the
        // single-consumer, receiveAsFlow()-backed inbound — no frame is lost in the gap.
        val firstFrame =
            try {
                withTimeoutOrNull(handshakeTimeoutMs) { transport.inbound.first() }
            } catch (e: NoSuchElementException) {
                null
            }
        if (firstFrame == null) {
            teardown(NoiseSessionException("noise_resp not received before the handshake deadline"))
            return
        }
        if (firstFrame.type != TYPE_NOISE_RESP) {
            teardown(NoiseSessionException("expected noise_resp as the first handshake frame"))
            return
        }
        val connId =
            try {
                session.readResp(base64StdDecode(firstFrame.data))
            } catch (e: Exception) {
                // readResp throws NoiseSessionException (MAC failure / malformed hello_ack); a bad
                // base64 throws IllegalArgumentException. Either tears the session down.
                teardown(e)
                return
            }
        mutableState.value = PumpState.Open(connId)

        // Steps 5–6: the single inbound collector decrypts each open-state noise_msg. Completes when
        // the transport goes Down (inbound completes) → clean teardown.
        try {
            transport.inbound.collect { frame -> onOpenFrame(frame) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            teardown(e)
            return
        }
        teardown(null)
    }

    /** Open-state frame dispatch — the clean `when (type)` seam #304 later extends for re-key. */
    private suspend fun onOpenFrame(frame: InnerFrameV2) {
        when (frame.type) {
            TYPE_NOISE_MSG -> {
                val session = this.session ?: throw NoiseSessionException("session is not available")
                // Emit only after a successful decrypt + parse — never surface an unauthenticated frame.
                val plaintext = session.decrypt(base64StdDecode(frame.data))
                val envelope = MobileJson.decodeFromString<Envelope>(plaintext.decodeToString())
                inboundChannel.send(envelope)
            }
            // The ordered encrypted stream cannot skip a frame: an unknown type tears the session down
            // rather than dropping it. This else is the #304 seam (a re-key noise_resp branch goes here).
            else -> throw NoiseSessionException("unexpected open-state frame type")
        }
    }

    /**
     * The single idempotent teardown every trigger funnels into (handshake fault, fatal open-state
     * frame, transport Down, or [close]). Synchronous up to [CoroutineScope.cancel] so the key-wipe is
     * never behind a cancellable suspension. `cause == null` ⟺ a clean Down / [close]; non-null ⟺ a
     * protocol or crypto fault.
     */
    private fun teardown(cause: Throwable?) {
        if (!terminated.compareAndSet(false, true)) return
        mutableState.value = PumpState.Closed(cause)
        inboundChannel.close()
        session?.close() // wipes the transport ciphers (AC 5)
        transport.close() // idempotent; active on a fatal-frame path so the supervisor sees Down
        scope.cancel()
    }

    private companion object {
        /** Protocol step 4: await `noise_resp` within 10 seconds. */
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        const val TYPE_NOISE_INIT = "noise_init"
        const val TYPE_NOISE_RESP = "noise_resp"
        const val TYPE_NOISE_MSG = "noise_msg"
    }
}

/** The pump's lifecycle: the handshake-completion signal ([Open]) and terminal [Closed] state. */
sealed interface PumpState {
    /** Initial / in-flight: `noise_init` sent, awaiting `noise_resp`. */
    data object Handshaking : PumpState

    /** The handshake completed; the encrypted transport is live. Consumers observe this before sending. */
    data class Open(
        val connId: String,
    ) : PumpState

    /** Terminal. [cause] is `null` on a clean transport Down / [NoiseSessionPump.close], else the fault. */
    data class Closed(
        val cause: Throwable?,
    ) : PumpState
}

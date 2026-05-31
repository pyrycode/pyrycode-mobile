package de.pyryco.mobile.data.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val rekeyIntervalMs: Long = REKEY_INTERVAL_MS,
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

    /** Serialises the two re-key initiation paths (timer vs inbound `rekey_request`) so they coalesce. */
    private val rekeyMutex = Mutex()

    /** Set true under [rekeyMutex] before the re-key `noise_init` is sent; cleared when its resp completes. */
    @Volatile
    private var rekeyInFlight = false

    /** The one-shot 1-hour timer; only the drive coroutine assigns it (arm-on-Open, re-arm-on-swap). */
    private var rekeyTimerJob: Job? = null

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
        rebaseRekeyTimer() // arm the 1-hour re-key cadence at handshake completion (#304)

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

    /** Open-state frame dispatch — `noise_msg` app/control traffic + the #304 re-key `noise_resp` seam. */
    private suspend fun onOpenFrame(frame: InnerFrameV2) {
        when (frame.type) {
            TYPE_NOISE_MSG -> {
                val session = this.session ?: throw NoiseSessionException("session is not available")
                // Emit only after a successful decrypt + parse — never surface an unauthenticated frame.
                val plaintext = session.decrypt(base64StdDecode(frame.data))
                val envelope = MobileJson.decodeFromString<Envelope>(plaintext.decodeToString())
                if (envelope.type == TYPE_REKEY_REQUEST) {
                    // A control message (the server nudging a re-key). Initiate it; never forward to the
                    // single inbound consumer (#278). Launched so the keystore re-load can't stall the
                    // collector. The payload (`reason`) is intentionally not decoded — discriminating on
                    // `type` alone makes an unknown/absent/extra `reason` impossible to crash on (AC 2).
                    scope.launch { initiateRekey() }
                } else {
                    inboundChannel.send(envelope)
                }
            }
            TYPE_NOISE_RESP -> {
                // The re-key handshake reply (a raw frame, not a noise_msg): complete the in-flight swap
                // instead of tearing down. A resp with no re-key in flight is a protocol violation.
                val session = this.session ?: throw NoiseSessionException("session is not available")
                if (!rekeyInFlight) throw NoiseSessionException("unexpected noise_resp with no re-key in flight")
                session.readRekeyResp(base64StdDecode(frame.data)) // MAC failure → NoiseSessionException → teardown
                rekeyInFlight = false
                rebaseRekeyTimer() // re-base the cadence from the swap moment
            }
            // The ordered encrypted stream cannot skip a frame: a genuinely unknown type tears the
            // session down rather than dropping it.
            else -> throw NoiseSessionException("unexpected open-state frame type")
        }
    }

    /** Cancels any armed re-key timer and arms a fresh one-shot delay. Drive-coroutine-only (no race). */
    private fun rebaseRekeyTimer() {
        rekeyTimerJob?.cancel()
        rekeyTimerJob =
            scope.launch {
                delay(rekeyIntervalMs)
                initiateRekey()
            }
    }

    /**
     * Drives a single re-key: re-loads the device static `s`, builds + sends a fresh `noise_init`, and
     * arms [rekeyInFlight] so the matching `noise_resp` completes the swap in [onOpenFrame]. The two
     * initiation paths (timer + `rekey_request`) are serialised by [rekeyMutex] and coalesce on
     * [rekeyInFlight]; the second caller skips (the mobile analog of the Go initiator's
     * `skipped_already_awaiting`). Skips silently — transport stays live on the current keys — if the
     * pump is no longer Open or the device key can't be re-loaded. No `rekey_ack` is sent on completion.
     */
    private suspend fun initiateRekey() {
        if (mutableState.value !is PumpState.Open) return
        rekeyMutex.withLock {
            if (rekeyInFlight || mutableState.value !is PumpState.Open) return@withLock
            val session = this.session ?: return@withLock
            val s =
                try {
                    sessionFactory.reloadDeviceStaticKey()
                } catch (e: NoiseSessionException) {
                    return@withLock // can't re-load the key → skip; transport unaffected
                }
            // No suspension point between the re-load returning and the finally, so cancellation/teardown
            // cannot skip zeroing `s`; session.close() independently wipes the session's copy in pendingRekey.
            try {
                val initBytes = session.writeRekeyInit(s) // session.pendingRekey now holds the only live copy of s
                rekeyInFlight = true // set BEFORE the send: the resp can only arrive after the server reads init
                transport.send(InnerFrameV2(type = TYPE_NOISE_INIT, data = base64StdEncode(initBytes)))
            } catch (e: IllegalStateException) {
                // Racing teardown closed the session, or a session-level re-key is already in flight — skip.
            } finally {
                s.fill(0) // zero the device-static copy regardless of outcome (mirrors create())
            }
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

        /** `protocol-mobile.md` § Re-key: time-based re-key fires every 1 hour of session uptime. */
        const val REKEY_INTERVAL_MS = 3_600_000L

        const val TYPE_NOISE_INIT = "noise_init"
        const val TYPE_NOISE_RESP = "noise_resp"
        const val TYPE_NOISE_MSG = "noise_msg"

        /** The inbound control envelope by which the server nudges a re-key (`Envelope.type`). */
        const val TYPE_REKEY_REQUEST = "rekey_request"
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

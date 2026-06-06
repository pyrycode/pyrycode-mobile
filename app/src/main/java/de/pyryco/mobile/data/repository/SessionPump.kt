package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import kotlinx.coroutines.flow.Flow

/**
 * The Mobile Protocol v2 Noise session surface the data layer consumes (#312): a hot,
 * decrypted application-[Envelope] inbound stream plus a non-throwing send. Consumer-defined
 * here in `data/repository/` — mirroring the [ConnectionStateSource] precedent — so the
 * repository depends on this minimal contract rather than on the concrete
 * [de.pyryco.mobile.data.network.NoiseSessionPump], which lives a layer down in `data/network/`.
 *
 * The real implementation is `NoiseSessionPump`; the DI / connection-coordinator slice
 * (#279 / #302) makes it `: SessionPump` (its two members already match structurally) and
 * provides the live binding. Keeping that wiring out of #312 holds this slice to consumer code.
 */
interface SessionPump {
    /**
     * Hot, **single-consumer** stream of decrypted application envelopes; lossless, in-order,
     * and completes on session teardown. The repository is its sole collector and fans the
     * resulting projection out to the cold reads it exposes.
     */
    val inbound: Flow<Envelope>

    /**
     * Encrypts and enqueues [envelope] as a `noise_msg`. Returns `false` (no frame sent) if the
     * session is not yet `Open`; never throws — mirrors the transport's `Boolean` send contract.
     */
    fun send(envelope: Envelope): Boolean
}

/**
 * The connection-coordinator's lifecycle view of the pump (#351). [SessionPump] is the *data* view
 * the repository consumes (read [inbound], call [send]); a coordinator additionally *owns the
 * lifecycle* — it starts the session drive and tears it down with the connection. Interface
 * Segregation: the coordinator depends on this richer contract and hands the same instance, upcast
 * to [SessionPump], to the repository.
 *
 * Satisfied by [de.pyryco.mobile.data.network.NoiseSessionPump]; the [start]/[close] members already
 * match its `start()`/`close()` structurally, so no behavioural change is needed to declare it.
 */
interface ManagedSessionPump : SessionPump {
    /** Single-use: launches the handshake + open-state dispatch drive. A second call is a caller bug. */
    fun start()

    /** Idempotent teardown: wipes session keys and tears the pump's session/scope down. */
    fun close()
}

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

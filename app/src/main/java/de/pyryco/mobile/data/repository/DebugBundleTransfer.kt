package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdDecode
import de.pyryco.mobile.data.network.base64StdEncode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.OutputStream

enum class DebugBundleStatus {
    RECEIVING,
    COMPLETE,
    UNAVAILABLE,
    BUSY,
    RECONNECT_REQUIRED,
    SEND_FAILED,
    REFUSED,
    INVALID_STREAM,
    DISCONNECTED,
}

/** Conditions for a new explicit request, never an instruction to replay automatically. */
enum class DebugBundleRetry { WHEN_AVAILABLE, AFTER_TRANSFER, AFTER_RECONNECT }

data class DebugBundleState(
    val status: DebugBundleStatus,
    val acceptedChunks: Int = 0,
    val retry: DebugBundleRetry = DebugBundleRetry.AFTER_RECONNECT,
)

/** Opaque, completed archive. The save owner handles output errors and releases this object. */
class DebugBundleArchive internal constructor(
    private val chunks: List<ByteArray>,
) {
    val sizeBytes: Long = chunks.sumOf { it.size.toLong() }

    fun writeTo(output: OutputStream) {
        chunks.forEach { output.write(it) }
    }

    override fun toString(): String = "DebugBundleArchive(opaque)"
}

/** One connection-bound attempt. State is safe for screens; archive ownership moves separately. */
class DebugBundleTransfer internal constructor(
    private val requestId: Long,
    initial: DebugBundleState = DebugBundleState(DebugBundleStatus.RECEIVING),
) {
    private val mutableState = MutableStateFlow(initial)
    val state = mutableState.asStateFlow()
    private val chunks = mutableListOf<ByteArray>()

    /**
     * Decoded bytes accepted so far, charged against [MAX_ARCHIVE_BYTES]. A running total rather
     * than a re-sum of [chunks], whose element count the peer chooses: re-summing on every chunk
     * would be quadratic in that count and make the bound itself the exhaustion it exists to
     * prevent. Never reset — the transfer is single-use, and [accept] returns at its terminal-state
     * check before any later frame could read a stale total.
     */
    private var acceptedBytes = 0L
    private var archive: DebugBundleArchive? = null

    init {
        logState()
    }

    /** Returns a completed archive once. Partial bytes are never exposed. */
    @Synchronized
    fun takeArchive(): DebugBundleArchive? = archive.also { archive = null }

    @Synchronized
    internal fun accept(envelope: Envelope): Boolean {
        val bundleFrame = envelope.type == "debug_bundle_chunk" || envelope.type == "debug_bundle_done"
        val refusal = envelope.type == "error" && envelope.inReplyTo == requestId
        if (!bundleFrame && !refusal) return false
        if (state.value.status != DebugBundleStatus.RECEIVING) return true
        if (refusal) {
            fail(DebugBundleStatus.REFUSED)
            return true
        }
        try {
            val payload = envelope.payload as? JsonObject
            val field = if (envelope.type == "debug_bundle_chunk") "seq" else "total"
            val number = payload?.get(field) as? JsonPrimitive
            // JsonPrimitive.intOrNull can round underflowing exponent fractions to zero.
            require(number != null && !number.isString && number.content.toIntOrNull() == chunks.size)
            if (envelope.type == "debug_bundle_chunk") {
                val data = payload["data"] as? JsonPrimitive
                require(data != null && data.isString)
                val bytes = base64StdDecode(data.content)
                require(base64StdEncode(bytes) == data.content)
                // Charged against the accumulated total, never one chunk's own size, and against the
                // bytes the round trip above has already made canonical — so the peer's choice of
                // encoding cannot buy it budget. Subtraction, not addition, so no total can overflow.
                require(bytes.size <= MAX_ARCHIVE_BYTES - acceptedBytes)
                chunks += bytes
                acceptedBytes += bytes.size
                mutableState.value = state.value.copy(acceptedChunks = chunks.size)
            } else {
                archive = DebugBundleArchive(chunks.toList())
                chunks.clear()
                mutableState.value = state.value.copy(status = DebugBundleStatus.COMPLETE)
                logState()
            }
        } catch (_: IllegalArgumentException) {
            fail(DebugBundleStatus.INVALID_STREAM)
        }
        return true
    }

    @Synchronized
    internal fun fail(status: DebugBundleStatus) {
        if (state.value.status != DebugBundleStatus.RECEIVING) return
        chunks.forEach { it.fill(0) }
        chunks.clear()
        mutableState.value = state.value.copy(status = status)
        logState()
    }

    private fun logState() {
        RelayLog.d { "event=debug_bundle status=${state.value.status} chunks=${state.value.acceptedChunks}" }
    }

    internal companion object {
        /**
         * The accumulation bound, in decoded archive bytes. A stream past it is an `INVALID_STREAM`
         * like any other malformed one, so a peer that streams chunks and never sends
         * `debug_bundle_done` costs bounded memory instead of the process.
         *
         * Derivation: the daemon retains at most 32 MiB of base64 payload in one session's push
         * queue and tears the session down past it (`protocol-mobile.md` § Error codes, close code
         * `4413`), so the largest archive it can actually deliver is about three quarters of that —
         * roughly 25 MB. The same 32 MiB counted in *decoded* bytes therefore sits above every
         * deliverable archive while bounding the phone, the memory-constrained side, at a size any
         * API 33 heap absorbs.
         */
        const val MAX_ARCHIVE_BYTES = 33_554_432L

        fun rejected(status: DebugBundleStatus): DebugBundleTransfer =
            DebugBundleTransfer(
                0,
                DebugBundleState(
                    status,
                    retry =
                        when (status) {
                            DebugBundleStatus.UNAVAILABLE -> DebugBundleRetry.WHEN_AVAILABLE
                            DebugBundleStatus.BUSY -> DebugBundleRetry.AFTER_TRANSFER
                            else -> DebugBundleRetry.AFTER_RECONNECT
                        },
                ),
            )
    }
}

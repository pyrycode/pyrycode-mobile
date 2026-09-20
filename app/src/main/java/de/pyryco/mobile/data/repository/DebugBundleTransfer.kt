package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdDecode
import de.pyryco.mobile.data.network.base64StdEncode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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
            require(number != null && !number.isString && number.intOrNull == chunks.size)
            if (envelope.type == "debug_bundle_chunk") {
                val data = payload["data"] as? JsonPrimitive
                require(data != null && data.isString)
                val bytes = base64StdDecode(data.content)
                require(base64StdEncode(bytes) == data.content)
                chunks += bytes
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

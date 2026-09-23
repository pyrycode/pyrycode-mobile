package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ATTACHMENT_CHUNK_BYTES
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.ErrorPayload
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** How one [ConversationRepository.uploadAttachment] call ended (#829). Settled once. */
sealed interface AttachmentUploadResult {
    /** The daemon answered `attachment_stored` naming [attachmentId]: the bytes are stored under it. */
    data class Stored(
        val attachmentId: String,
    ) : AttachmentUploadResult

    /** Every other outcome. None carries the id, so a failed upload can never be named as stored. */
    sealed interface Failed : AttachmentUploadResult

    /**
     * The daemon refused one of this upload's chunks. [retryable] is its advice: `attachment.too_many_uploads`
     * and `attachment.storage_failed` are retryable (after a backoff), the rest are not. [code] is
     * daemon-authored, bounded to 64 characters: branch on it, never render or log it.
     */
    data class Refused(
        val code: String,
        val retryable: Boolean,
    ) : Failed

    /** Over [AttachmentUploadLimit.MAX_BYTES]; refused before any chunk was sent. Do not retry. */
    data object TooLarge : Failed

    /**
     * No live connection, the connection refused a chunk, or it dropped mid-upload. The daemon discards a
     * partial upload with its connection, so retry after reconnecting and resend every chunk.
     */
    data object ReconnectRequired : Failed
}

/**
 * The phone's own per-upload bound (#829): 178 chunks, 8,010,000 bytes.
 *
 * It protects the socket rather than the daemon. OkHttp's `WebSocket.send` closes the socket once more
 * than 16 MiB is queued, and nothing on the send path waits for that queue to drain. A 45000-byte chunk
 * becomes about 80 KB on the socket after base64, Noise encryption and a second base64, and about 87 KB
 * with every metadata field at its bound; 178 of those stay under 16 MiB. A larger file sent in one loop
 * would close the connection for every conversation on the host. The daemon applies its own, larger bound
 * and answers `attachment.too_large`. Raising this one needs pacing against the transport's queue.
 */
object AttachmentUploadLimit {
    const val MAX_BYTES: Int = 178 * ATTACHMENT_CHUNK_BYTES

    fun fits(size: Int): Boolean = size <= MAX_BYTES
}

/**
 * One upload in flight on one connection (#829), the upload-leg sibling of [DebugBundleTransfer]. It
 * correlates in two ways, and both are needed: success by the payload `attachment_id`, because the
 * daemon's `in_reply_to` names whichever chunk completed the set; refusal by the chunk envelope ids,
 * because an `error` names only the chunk it refused.
 */
internal class AttachmentUploadTransfer(
    val attachmentId: String,
) {
    private val chunkIds = ConcurrentHashMap.newKeySet<Long>()
    private val result = CompletableDeferred<AttachmentUploadResult>()

    val isSettled: Boolean get() = result.isCompleted

    /** Records a chunk's envelope id. Called before the chunk is sent, so a fast refusal still matches. */
    fun expectReplyTo(envelopeId: Long) {
        chunkIds += envelopeId
    }

    /** Settles on this upload's own reply and returns `true`; anything else is left for other routes. */
    fun accept(envelope: Envelope): Boolean {
        when (envelope.type) {
            TYPE_ATTACHMENT_STORED -> {
                val id = ((envelope.payload as? JsonObject)?.get(KEY_ATTACHMENT_ID) as? JsonPrimitive)?.takeIf { it.isString }
                if (id?.content != attachmentId) return false
                result.complete(AttachmentUploadResult.Stored(attachmentId))
            }
            TYPE_ERROR -> {
                val inReplyTo = envelope.inReplyTo
                if (inReplyTo == null || inReplyTo !in chunkIds) return false
                result.complete(refusal(envelope))
            }
            else -> return false
        }
        return true
    }

    /** Settles as [failure] unless already settled; the first outcome wins. */
    fun fail(failure: AttachmentUploadResult.Failed) {
        result.complete(failure)
    }

    suspend fun await(): AttachmentUploadResult = result.await()

    override fun toString(): String = "AttachmentUploadTransfer(attachmentId=$attachmentId)"

    private fun refusal(envelope: Envelope): AttachmentUploadResult.Refused {
        val error =
            try {
                MobileJson.decodeFromJsonElement(ErrorPayload.serializer(), envelope.payload)
            } catch (_: IllegalArgumentException) {
                null
            }
        if (error == null || error.code.isEmpty() || error.code.length > MAX_CODE_LENGTH) {
            return AttachmentUploadResult.Refused(MALFORMED_REFUSAL, retryable = false)
        }
        return AttachmentUploadResult.Refused(error.code, error.retryable)
    }

    private companion object {
        const val TYPE_ATTACHMENT_STORED = "attachment_stored"
        const val TYPE_ERROR = "error"
        const val KEY_ATTACHMENT_ID = "attachment_id"
        const val MAX_CODE_LENGTH = 64
        const val MALFORMED_REFUSAL = "error.malformed_reply"
    }
}

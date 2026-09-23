package de.pyryco.mobile.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Raw bytes of `data` per `attachment_chunk`, before base64 — every chunk but the last carries exactly this many. */
const val ATTACHMENT_CHUNK_BYTES = 45_000

/** The byte bound on `filename` and `mime_type`, counted in UTF-8 bytes, not characters. */
const val ATTACHMENT_TEXT_MAX_BYTES = 255

/**
 * Mobile Protocol v2 `attachment_chunk` payload (#829): one slice of one attachment plus the whole
 * transfer's metadata, repeated on every chunk. Encoded through [MobileJson] on the upload leg; the
 * retrieval leg (#671) decodes the same shape.
 *
 * Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § Attachments → `attachment_chunk`. All nine keys are
 * always present. [data] is the user's file bytes and [filename] is private: neither is ever logged, so
 * [toString] names only the id and the position.
 */
@Serializable
data class AttachmentChunkPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("attachment_id") val attachmentId: String,
    val index: Int,
    @SerialName("total_chunks") val totalChunks: Int,
    val filename: String,
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    val sha256: String,
    val data: String,
) {
    override fun toString(): String = "AttachmentChunkPayloadDto(attachmentId=$attachmentId, index=$index, totalChunks=$totalChunks)"
}

/**
 * The sender's arithmetic for one upload (#829): `total_chunks = max(1, ceil(size / 45000))`, the whole
 * file's lowercase-hex sha256 computed once, and `filename` / `mime_type` cut to
 * [ATTACHMENT_TEXT_MAX_BYTES]. [payload] encodes one chunk on demand, so a large file is never held as
 * every encoded chunk at once. Holds the caller's [bytes] without copying them.
 */
class AttachmentChunkPlan(
    private val conversationId: String,
    private val attachmentId: String,
    private val bytes: ByteArray,
    filename: String,
    mimeType: String,
) {
    val totalChunks: Int = maxOf(1, (bytes.size + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES)
    private val filename = truncateUtf8(filename, ATTACHMENT_TEXT_MAX_BYTES)
    private val mimeType = truncateUtf8(mimeType, ATTACHMENT_TEXT_MAX_BYTES)
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()

    fun payload(index: Int): AttachmentChunkPayloadDto {
        require(index in 0 until totalChunks) { "chunk index out of range" }
        val start = index * ATTACHMENT_CHUNK_BYTES
        val end = minOf(bytes.size, start + ATTACHMENT_CHUNK_BYTES)
        return AttachmentChunkPayloadDto(
            conversationId = conversationId,
            attachmentId = attachmentId,
            index = index,
            totalChunks = totalChunks,
            filename = filename,
            mimeType = mimeType,
            size = bytes.size.toLong(),
            sha256 = sha256,
            data = base64StdEncode(bytes.copyOfRange(start, end)),
        )
    }

    override fun toString(): String = "AttachmentChunkPlan(attachmentId=$attachmentId, totalChunks=$totalChunks)"
}

/**
 * [value] cut to at most [maxBytes] UTF-8 bytes, only ever between code points, so the result is still
 * valid UTF-8. A value that already fits is returned unchanged.
 */
internal fun truncateUtf8(
    value: String,
    maxBytes: Int,
): String {
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    var bytes = 0
    var end = 0
    while (end < value.length) {
        val next = value.offsetByCodePoints(end, 1)
        val width = value.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (bytes + width > maxBytes) break
        bytes += width
        end = next
    }
    return value.substring(0, end)
}

private fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it) }

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
 * Mobile Protocol v2 `attachment_offered` payload (#898): the daemon announces a file `claude` produced, with
 * no bytes. Wire SSOT: `../pyrycode/docs/protocol-mobile.md` § Attachments → `attachment_offered`. All three
 * keys are always present, so a missing one fails the decode. Nothing here is trusted as sent: both ids must
 * pass [isAttachmentIdShape] and [filename] is `claude`-authored, so it reaches a reader only through
 * [attachmentDisplayName]. [toString] leaves [filename] out.
 */
@Serializable
data class AttachmentOfferedPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("attachment_id") val attachmentId: String,
    val filename: String,
) {
    override fun toString(): String = "AttachmentOfferedPayloadDto(attachmentId=$attachmentId)"
}

/**
 * Whether [value] is the published `attachment_id` shape (`protocol-mobile.md` § The `attachment_id` shape),
 * which conversation ids share: a lowercase UUIDv4, 36 bytes, `-` at 8, 13, 18 and 23, `4` at 14, one of
 * `8`, `9`, `a`, `b` at 19, and lowercase hex everywhere else. The alphabet is what keeps the id from
 * spelling any path component but itself, so it is checked character by character, never with a
 * locale-aware digit test.
 */
internal fun isAttachmentIdShape(value: String): Boolean {
    if (value.length != 36) return false
    return value.withIndex().all { (i, c) ->
        when (i) {
            8, 13, 18, 23 -> c == '-'
            14 -> c == '4'
            19 -> c in "89ab"
            else -> c in '0'..'9' || c in 'a'..'f'
        }
    }
}

/**
 * The `claude`-authored [raw] file name made safe to display (#898): every code point that is an ISO control
 * character, a Unicode format character (bidi overrides and isolates, zero-width characters, tag
 * characters), a line or paragraph separator, or an unpaired surrogate is dropped, then the rest is cut to
 * [ATTACHMENT_TEXT_MAX_BYTES] UTF-8 bytes between code points. The walk is by code point because a
 * supplementary-plane format character is two surrogate `Char`s, neither of which reads as a format
 * character on its own. The result may be empty. It is still attacker-chosen text: display it, never use
 * it as a path.
 */
internal fun attachmentDisplayName(raw: String): String {
    val kept = StringBuilder(raw.length)
    raw.codePoints().forEach { codePoint ->
        val dropped =
            Character.isISOControl(codePoint) ||
                when (Character.getType(codePoint).toByte()) {
                    Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> true
                    else -> false
                }
        if (!dropped) kept.appendCodePoint(codePoint)
    }
    return truncateUtf8(kept.toString(), ATTACHMENT_TEXT_MAX_BYTES)
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

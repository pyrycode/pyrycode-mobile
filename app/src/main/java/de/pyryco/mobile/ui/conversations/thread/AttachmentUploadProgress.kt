package de.pyryco.mobile.ui.conversations.thread

/**
 * Uploads of fewer chunks than this show no figure (#1327), as on desktop (`ATTACHMENT_PROGRESS_MIN_CHUNKS`,
 * about 360 kB): a small file finishes before a figure could be read.
 */
const val ATTACHMENT_PROGRESS_MIN_CHUNKS = 8

/** How far the upload of the pending entry [key] has got (#1327), as a whole percentage. */
data class AttachmentUploadProgress(
    val key: Long,
    val percent: Int,
)

/** Desktop's `uploadProgressPercent`: floor of sent over total times 100, clamped to 0..100, and 0 for a non-positive total. */
fun uploadProgressPercent(
    sentChunks: Int,
    totalChunks: Int,
): Int {
    if (totalChunks <= 0) return 0
    return (sentChunks.toLong() * 100 / totalChunks).coerceIn(0, 100).toInt()
}

/** [key]'s figure for one chunk report, or `null` for an upload under [ATTACHMENT_PROGRESS_MIN_CHUNKS]. */
fun attachmentUploadProgress(
    key: Long,
    sentChunks: Int,
    totalChunks: Int,
): AttachmentUploadProgress? {
    if (totalChunks < ATTACHMENT_PROGRESS_MIN_CHUNKS) return null
    return AttachmentUploadProgress(key, uploadProgressPercent(sentChunks, totalChunks))
}

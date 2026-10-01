package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Resources
import androidx.annotation.StringRes
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.data.repository.AttachmentUploadResult

/**
 * Why a send carrying attachments stopped at a file (#1325), one fixed sentence each, as desktop's
 * `ATTACHMENT_UPLOAD_FAILURE_COPY`. Only the reason crosses to the screen, never a name, URI or daemon text.
 */
enum class AttachmentSendFailure(
    @StringRes val message: Int,
) {
    UNREADABLE(R.string.thread_attachment_send_unreadable),
    TOO_LARGE(R.string.thread_attachment_send_too_large),
    NOT_CONNECTED(R.string.thread_attachment_send_not_connected),
    CONNECTION_LOST(R.string.thread_attachment_send_connection_lost),
    SEND_FAILED(R.string.thread_attachment_send_failed),
    HOST_INVALID_CHUNK(R.string.thread_attachment_send_host_invalid_chunk),
    HOST_INTEGRITY_FAILED(R.string.thread_attachment_send_host_integrity_failed),
    HOST_TOO_LARGE(R.string.thread_attachment_send_host_too_large),
    HOST_TOO_MANY_UPLOADS(R.string.thread_attachment_send_host_too_many_uploads),
    HOST_STORAGE_FAILED(R.string.thread_attachment_send_host_storage_failed),
    HOST_MESSAGE_TOO_LONG(R.string.thread_attachment_send_host_message_too_long),
    HOST_INCOMPLETE(R.string.thread_attachment_send_host_incomplete),
    UNCLASSIFIED(R.string.thread_attachment_send_unclassified),
}

/** The failure a read stopped on, or `null` when it read the bytes. */
fun attachmentSendFailure(read: AttachmentRead): AttachmentSendFailure? =
    when (read) {
        is AttachmentRead.Bytes -> null
        AttachmentRead.TooLarge -> AttachmentSendFailure.TOO_LARGE
        AttachmentRead.Unreadable -> AttachmentSendFailure.UNREADABLE
    }

/**
 * The failure an upload settled on. A refusal's daemon-authored code is only compared against the codes
 * below; any other, the malformed-reply code included, is [AttachmentSendFailure.UNCLASSIFIED].
 */
fun attachmentSendFailure(result: AttachmentUploadResult.Failed): AttachmentSendFailure =
    when (result) {
        AttachmentUploadResult.TooLarge -> AttachmentSendFailure.TOO_LARGE
        AttachmentUploadResult.ReconnectRequired -> AttachmentSendFailure.NOT_CONNECTED
        AttachmentUploadResult.ConnectionLost -> AttachmentSendFailure.CONNECTION_LOST
        AttachmentUploadResult.SendFailed -> AttachmentSendFailure.SEND_FAILED
        is AttachmentUploadResult.Refused ->
            when (result.code) {
                CODE_INVALID_CHUNK -> AttachmentSendFailure.HOST_INVALID_CHUNK
                CODE_INTEGRITY_FAILED -> AttachmentSendFailure.HOST_INTEGRITY_FAILED
                CODE_TOO_LARGE -> AttachmentSendFailure.HOST_TOO_LARGE
                CODE_TOO_MANY_UPLOADS -> AttachmentSendFailure.HOST_TOO_MANY_UPLOADS
                CODE_STORAGE_FAILED -> AttachmentSendFailure.HOST_STORAGE_FAILED
                CODE_MESSAGE_TOO_LONG -> AttachmentSendFailure.HOST_MESSAGE_TOO_LONG
                CODE_NOT_FOUND, CODE_STREAM_ABORTED -> AttachmentSendFailure.HOST_INCOMPLETE
                else -> AttachmentSendFailure.UNCLASSIFIED
            }
    }

/**
 * [bytes] in decimal megabytes with at most one decimal, as desktop's `formatByteLimit`: 8,010,000 is "8".
 * Integer arithmetic, so no locale changes the figure.
 */
fun formatMegabytes(bytes: Int): String {
    val tenths = (bytes.toLong() + BYTES_PER_TENTH / 2) / BYTES_PER_TENTH
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** The sentence for this failure; the too-large one names the app's own limit. */
fun AttachmentSendFailure.text(resources: Resources): String =
    if (this == AttachmentSendFailure.TOO_LARGE) {
        resources.getString(message, formatMegabytes(AttachmentUploadLimit.MAX_BYTES))
    } else {
        resources.getString(message)
    }

private const val BYTES_PER_TENTH = 100_000L
private const val CODE_INVALID_CHUNK = "attachment.invalid_chunk"
private const val CODE_INTEGRITY_FAILED = "attachment.integrity_failed"
private const val CODE_TOO_LARGE = "attachment.too_large"
private const val CODE_TOO_MANY_UPLOADS = "attachment.too_many_uploads"
private const val CODE_STORAGE_FAILED = "attachment.storage_failed"
private const val CODE_MESSAGE_TOO_LONG = "message.too_long"
private const val CODE_NOT_FOUND = "attachment.not_found"
private const val CODE_STREAM_ABORTED = "attachment.stream_aborted"

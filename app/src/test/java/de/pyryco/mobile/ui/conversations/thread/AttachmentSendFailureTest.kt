package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1325: each way a send's read or upload can fail picks one fixed sentence, as on desktop. */
class AttachmentSendFailureTest {
    @Test
    fun localUploadFailures_mapToTheirSentence() {
        assertEquals(AttachmentSendFailure.TOO_LARGE, attachmentSendFailure(AttachmentUploadResult.TooLarge))
        assertEquals(AttachmentSendFailure.NOT_CONNECTED, attachmentSendFailure(AttachmentUploadResult.ReconnectRequired))
        assertEquals(AttachmentSendFailure.CONNECTION_LOST, attachmentSendFailure(AttachmentUploadResult.ConnectionLost))
        assertEquals(AttachmentSendFailure.SEND_FAILED, attachmentSendFailure(AttachmentUploadResult.SendFailed))
    }

    @Test
    fun refusals_mapByTheirCode_andAnythingElseIsUnclassified() {
        val expected =
            mapOf(
                "attachment.invalid_chunk" to AttachmentSendFailure.HOST_INVALID_CHUNK,
                "attachment.integrity_failed" to AttachmentSendFailure.HOST_INTEGRITY_FAILED,
                "attachment.too_large" to AttachmentSendFailure.HOST_TOO_LARGE,
                "attachment.too_many_uploads" to AttachmentSendFailure.HOST_TOO_MANY_UPLOADS,
                "attachment.storage_failed" to AttachmentSendFailure.HOST_STORAGE_FAILED,
                "message.too_long" to AttachmentSendFailure.HOST_MESSAGE_TOO_LONG,
                "attachment.not_found" to AttachmentSendFailure.HOST_INCOMPLETE,
                "attachment.stream_aborted" to AttachmentSendFailure.HOST_INCOMPLETE,
                "error.malformed_reply" to AttachmentSendFailure.UNCLASSIFIED,
                "attachment.something_new" to AttachmentSendFailure.UNCLASSIFIED,
                "ATTACHMENT.TOO_LARGE" to AttachmentSendFailure.UNCLASSIFIED,
            )
        for ((code, failure) in expected) {
            for (retryable in listOf(false, true)) {
                assertEquals(code, failure, attachmentSendFailure(AttachmentUploadResult.Refused(code, retryable)))
            }
        }
    }

    @Test
    fun everyFailureHasItsOwnSentence() {
        val messages = AttachmentSendFailure.entries.map { it.message }
        assertEquals(messages.size, messages.toSet().size)
        assertEquals(R.string.thread_attachment_send_too_large, AttachmentSendFailure.TOO_LARGE.message)
    }

    @Test
    fun formatMegabytes_isDecimalWithAtMostOneDecimal() {
        assertEquals("8", formatMegabytes(AttachmentUploadLimit.MAX_BYTES))
        assertEquals("8", formatMegabytes(8_000_000))
        assertEquals("8.5", formatMegabytes(8_500_000))
        assertEquals("8.1", formatMegabytes(8_060_000))
        assertEquals("16", formatMegabytes(15_960_000))
        assertEquals("0", formatMegabytes(0))
    }
}

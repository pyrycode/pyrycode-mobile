package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1027: which taps open the reader, and how a kept file becomes its text. */
@OptIn(ExperimentalCoroutinesApi::class)
class MarkdownReaderLoadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class OneFileRepository(
        private val result: () -> AttachmentRetrievalResult,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        override suspend fun retrieveAttachment(
            conversationId: String,
            attachmentId: String,
        ): AttachmentRetrievalResult = result()
    }

    private fun keptFile(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }

    private suspend fun read(repository: ConversationRepository) =
        readMarkdownAttachment(repository, CONV, ATTACHMENT, UnconfinedTestDispatcher())

    @Test
    fun markdownNames_matchInAnyCase() {
        listOf("notes.md", "NOTES.MD", "Plan.Markdown", "a.markdown", ".md").forEach {
            assertTrue(it, isMarkdownAttachmentName(it))
        }
        listOf("notes.txt", "notes.md.txt", "md", "notes.mdx", "", null).forEach {
            assertFalse("$it", isMarkdownAttachmentName(it))
        }
    }

    @Test
    fun strictDecode_keepsValidText_andRefusesMalformedBytes() {
        assertEquals("# Otsikko äö €", decodeUtf8Strictly("# Otsikko äö €".toByteArray()))
        // A lone continuation byte, a truncated two-byte sequence, and an overlong `/`.
        assertNull(decodeUtf8Strictly(byteArrayOf(0x41, 0x80.toByte())))
        assertNull(decodeUtf8Strictly(byteArrayOf(0x41, 0xC3.toByte())))
        assertNull(decodeUtf8Strictly(byteArrayOf(0xC0.toByte(), 0xAF.toByte())))
    }

    @Test
    fun retrievedValidFile_isTheDocument_namedByTheRetrieval() =
        runTest {
            val file = keptFile("# Plan\n\n- one".toByteArray())
            val document = read(OneFileRepository({ AttachmentRetrievalResult.Retrieved(file, "Plan.md", "text/markdown") }))

            assertNotNull(document)
            assertEquals("Plan.md", document?.name)
            assertEquals("# Plan\n\n- one", document?.text)
        }

    @Test
    fun malformedUtf8_isNoDocument() =
        runTest {
            val file = keptFile(byteArrayOf(0x23, 0x20, 0xFF.toByte(), 0x41))

            assertNull(read(OneFileRepository({ AttachmentRetrievalResult.Retrieved(file, "bad.md", "text/markdown") })))
        }

    @Test
    fun retrievalFailures_areNoDocument() =
        runTest {
            listOf(
                AttachmentRetrievalResult.NotFound,
                AttachmentRetrievalResult.Unavailable,
                AttachmentRetrievalResult.Invalid,
                AttachmentRetrievalResult.TooLarge,
            ).forEach { failure ->
                assertNull("$failure", read(OneFileRepository({ failure })))
            }
        }

    @Test
    fun aThrowingRepository_orAMissingFile_isNoDocument() =
        runTest {
            assertNull(read(OneFileRepository({ throw IllegalStateException("no store") })))
            val gone = File(folder.root, "gone")
            assertNull(read(OneFileRepository({ AttachmentRetrievalResult.Retrieved(gone, "gone.md", "text/markdown") })))
        }

    @Test
    fun theByteBound_admitsItsOwnSize_andRefusesOneMore() =
        runTest {
            val atBound = keptFile(ByteArray(MAX_MARKDOWN_READER_BYTES) { 'a'.code.toByte() })
            val overBound = keptFile(ByteArray(MAX_MARKDOWN_READER_BYTES + 1) { 'a'.code.toByte() })

            assertEquals(
                MAX_MARKDOWN_READER_BYTES,
                read(OneFileRepository({ AttachmentRetrievalResult.Retrieved(atBound, "big.md", "text/markdown") }))?.text?.length,
            )
            assertNull(read(OneFileRepository({ AttachmentRetrievalResult.Retrieved(overBound, "big.md", "text/markdown") })))
        }

    @Test
    fun documentToString_printsNoNameOrText() {
        val printed = MarkdownDocument("secret-name.md", "secret text").toString()

        assertFalse(printed, "secret" in printed)
    }

    private companion object {
        const val CONV = "c1"
        const val ATTACHMENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}

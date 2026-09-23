package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ATTACHMENT_CHUNK_BYTES
import de.pyryco.mobile.data.network.AttachmentChunkPayloadDto
import de.pyryco.mobile.data.network.AttachmentChunkPlan
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdEncode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The retrieval leg's reassembly rules (#899): `protocol-mobile.md` § Attachments → "Reassembly & integrity"
 * and "Retrieval, and its two terminal signals". Correlation, placement by index, the claim checks before
 * anything is sized, and the integrity check before anything is exposed.
 */
class AttachmentRetrievalTransferTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun zeroByteFile_isOneEmptyChunk_andCompletes() =
        runTest {
            val transfer = transfer()
            assertTrue(transfer.accept(chunk(ByteArray(0), 0)))
            assertFetched(ByteArray(0), transfer.await())
        }

    @Test
    fun fileOfExactlyOneChunk_completes() =
        runTest {
            val bytes = bytes(ATTACHMENT_CHUNK_BYTES)
            val transfer = transfer()
            transfer.accept(chunk(bytes, 0))
            assertFetched(bytes, transfer.await())
        }

    @Test
    fun fileOneByteOverOneChunk_needsBothChunks_inAnyOrder() =
        runTest {
            val bytes = bytes(ATTACHMENT_CHUNK_BYTES + 1)
            val transfer = transfer()
            transfer.accept(chunk(bytes, 1))
            assertFalse("one index is still missing", transfer.isSettled)
            assertEquals(1, transfer.activity.value)
            transfer.accept(chunk(bytes, 0))
            assertFetched(bytes, transfer.await())
        }

    @Test
    fun chunksLandByIndex_whateverTheArrivalOrder() =
        runTest {
            val bytes = bytes(ATTACHMENT_CHUNK_BYTES * 3 + 17)
            val transfer = transfer()
            listOf(2, 0, 3, 1).forEach { transfer.accept(chunk(bytes, it)) }
            assertFetched(bytes, transfer.await())
        }

    @Test
    fun displayNameAndMimeType_areSanitisedHints() =
        runTest {
            val transfer = transfer()
            transfer.accept(chunk(ByteArray(3), 0, filename = "re‮port\u0000.pdf", mimeType = "text/html\n"))
            val fetched = transfer.await() as AttachmentFetchResult.Fetched
            assertEquals("report.pdf", fetched.displayName)
            assertEquals("text/html", fetched.mimeType)
            assertTrue(logs.none { it.contains("port") || it.contains("text/html") })
        }

    @Test
    fun framesNamingAnotherRequest_areNotClaimed() {
        val transfer = transfer()
        assertFalse(transfer.accept(chunk(ByteArray(3), 0, inReplyTo = REQUEST_ID + 1)))
        assertFalse(transfer.accept(chunk(ByteArray(3), 0, inReplyTo = null)))
        assertFalse(transfer.accept(error("attachment.not_found", inReplyTo = REQUEST_ID + 1)))
        assertFalse(transfer.accept(Envelope(1, "ack", TS, JsonObject(emptyMap()), inReplyTo = REQUEST_ID)))
        assertFalse(transfer.isSettled)
    }

    @Test
    fun chunkNamingTheRequestButAnotherAttachment_failsTheRetrieval() =
        runTest {
            val transfer = transfer()
            assertTrue(transfer.accept(chunk(ByteArray(3), 0, attachmentId = OTHER_ID)))
            assertEquals(AttachmentRetrievalResult.Invalid, transfer.await())
        }

    @Test
    fun duplicateIndex_fails() = assertStreamInvalid(bytes(ATTACHMENT_CHUNK_BYTES + 5)) { listOf(chunk(it, 0), chunk(it, 0)) }

    @Test
    fun indexOutOfRange_fails() = assertStreamInvalid(bytes(10)) { listOf(chunk(it, 0) { dto -> dto.copy(index = 1) }) }

    @Test
    fun negativeIndex_fails() = assertStreamInvalid(bytes(10)) { listOf(chunk(it, 0) { dto -> dto.copy(index = -1) }) }

    @Test
    fun totalChunksChangedMidStream_fails() =
        assertStreamInvalid(bytes(ATTACHMENT_CHUNK_BYTES + 5)) {
            listOf(chunk(it, 0), chunk(it, 1) { dto -> dto.copy(totalChunks = 3) })
        }

    @Test
    fun sizeChangedMidStream_fails() =
        assertStreamInvalid(bytes(ATTACHMENT_CHUNK_BYTES + 5)) {
            listOf(chunk(it, 0), chunk(it, 1) { dto -> dto.copy(size = dto.size + 1) })
        }

    @Test
    fun sha256ChangedMidStream_fails() =
        assertStreamInvalid(bytes(ATTACHMENT_CHUNK_BYTES + 5)) {
            listOf(chunk(it, 0), chunk(it, 1) { dto -> dto.copy(sha256 = "0".repeat(64)) })
        }

    @Test
    fun totalChunksDisagreeingWithSize_failsOnTheFirstChunk() =
        assertStreamInvalid(bytes(10)) { listOf(chunk(it, 0) { dto -> dto.copy(totalChunks = 2) }) }

    @Test
    fun negativeSize_fails() = assertStreamInvalid(bytes(0)) { listOf(chunk(it, 0) { dto -> dto.copy(size = -1) }) }

    @Test
    fun claimedSizeOverTheBound_isTooLarge_onTheFirstChunk() =
        runTest {
            val over = AttachmentRetrievalLimit.MAX_BYTES + 1
            val total = (over + ATTACHMENT_CHUNK_BYTES - 1) / ATTACHMENT_CHUNK_BYTES
            val transfer = transfer()
            transfer.accept(chunk(bytes(10), 0) { it.copy(size = over, totalChunks = total.toInt()) })
            assertEquals(AttachmentRetrievalResult.TooLarge, transfer.await())
        }

    @Test
    fun hugeClaims_areRefusedWithoutAllocating() =
        runTest {
            val transfer = transfer()
            transfer.accept(chunk(bytes(10), 0) { it.copy(size = Long.MAX_VALUE, totalChunks = Int.MAX_VALUE) })
            assertEquals(AttachmentRetrievalResult.TooLarge, transfer.await())
        }

    @Test
    fun claimAtTheBound_isAdmitted() {
        val transfer = transfer()
        val size = AttachmentRetrievalLimit.MAX_BYTES
        val full = base64StdEncode(bytes(ATTACHMENT_CHUNK_BYTES))
        transfer.accept(chunk(bytes(10), 0) { it.copy(size = size, totalChunks = AttachmentRetrievalLimit.MAX_CHUNKS, data = full) })
        assertFalse("a claim at the bound is waiting for more chunks, not refused", transfer.isSettled)
    }

    @Test
    fun nonCanonicalBase64_fails() =
        // Four bytes encode with `==` padding; the unpadded form decodes to the same bytes but is not canonical.
        assertStreamInvalid(bytes(4)) { listOf(chunk(it, 0) { dto -> dto.copy(data = dto.data.trimEnd('=')) }) }

    @Test
    fun chunkOverTheChunkBound_fails() =
        assertStreamInvalid(bytes(ATTACHMENT_CHUNK_BYTES + 1)) {
            listOf(chunk(it, 0) { dto -> dto.copy(data = base64StdEncode(bytes(ATTACHMENT_CHUNK_BYTES + 1))) })
        }

    @Test
    fun bytesBeyondTheClaimedSize_fail() =
        assertStreamInvalid(bytes(3)) { listOf(chunk(it, 0) { dto -> dto.copy(data = base64StdEncode(bytes(4))) }) }

    @Test
    fun completedStreamShorterThanSize_fails() =
        assertStreamInvalid(bytes(3)) { listOf(chunk(it, 0) { dto -> dto.copy(data = base64StdEncode(bytes(2))) }) }

    @Test
    fun digestMismatch_fails() = assertStreamInvalid(bytes(3)) { listOf(chunk(it, 0) { dto -> dto.copy(sha256 = "f".repeat(64)) }) }

    @Test
    fun uppercaseDigest_fails() =
        assertStreamInvalid(bytes(3)) { listOf(chunk(it, 0) { dto -> dto.copy(sha256 = dto.sha256.uppercase()) }) }

    @Test
    fun malformedChunkPayload_fails() =
        runTest {
            val transfer = transfer()
            transfer.accept(Envelope(5, "attachment_chunk", TS, buildJsonObject { put("index", 0) }, inReplyTo = REQUEST_ID))
            assertEquals(AttachmentRetrievalResult.Invalid, transfer.await())
        }

    @Test
    fun notFound_reportsNotFound() =
        runTest {
            val transfer = transfer()
            assertTrue(transfer.accept(error("attachment.not_found")))
            assertEquals(AttachmentRetrievalResult.NotFound, transfer.await())
        }

    @Test
    fun streamAborted_afterChunks_discardsThemAndIsRetryable() =
        runTest {
            val bytes = bytes(ATTACHMENT_CHUNK_BYTES + 5)
            val transfer = transfer()
            transfer.accept(chunk(bytes, 0))
            transfer.accept(error("attachment.stream_aborted"))
            assertEquals(AttachmentRetrievalResult.Unavailable, transfer.await())
            assertTrue("a chunk after the abort is claimed but changes nothing", transfer.accept(chunk(bytes, 1)))
            assertEquals(AttachmentRetrievalResult.Unavailable, transfer.await())
        }

    @Test
    fun otherOrMalformedErrors_areRetryable() =
        runTest {
            val other = transfer()
            other.accept(error("attachment.too_many_retrievals"))
            assertEquals(AttachmentRetrievalResult.Unavailable, other.await())

            val malformed = transfer()
            malformed.accept(Envelope(5, "error", TS, JsonPrimitive("nope"), inReplyTo = REQUEST_ID))
            assertEquals(AttachmentRetrievalResult.Unavailable, malformed.await())
        }

    @Test
    fun firstOutcomeWins() =
        runTest {
            val transfer = transfer()
            transfer.fail(AttachmentRetrievalResult.Unavailable)
            transfer.accept(chunk(ByteArray(0), 0))
            assertEquals(AttachmentRetrievalResult.Unavailable, transfer.await())
        }

    @Test
    fun toStringNamesNoContent() {
        val content = AttachmentContent(listOf(bytes(3)))
        assertFalse(content.toString().contains("["))
        assertEquals(3L, content.size)
    }

    private fun assertStreamInvalid(
        bytes: ByteArray,
        frames: (ByteArray) -> List<Envelope>,
    ) = runTest {
        val transfer = transfer()
        frames(bytes).forEach { assertTrue(transfer.accept(it)) }
        assertEquals(AttachmentRetrievalResult.Invalid, transfer.await())
    }

    private fun assertFetched(
        expected: ByteArray,
        result: AttachmentFetchResult,
    ) {
        val fetched = result as AttachmentFetchResult.Fetched
        val out = ByteArrayOutputStream()
        fetched.content.writeTo(out)
        assertArrayEquals(expected, out.toByteArray())
        assertEquals(expected.size.toLong(), fetched.content.size)
        assertEquals("notes.txt", fetched.displayName)
        assertEquals("text/plain", fetched.mimeType)
    }

    private fun transfer() = AttachmentRetrievalTransfer(REQUEST_ID, ATTACHMENT_ID)

    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    private fun chunk(
        bytes: ByteArray,
        index: Int,
        attachmentId: String = ATTACHMENT_ID,
        filename: String = "notes.txt",
        mimeType: String = "text/plain",
        inReplyTo: Long? = REQUEST_ID,
        edit: (AttachmentChunkPayloadDto) -> AttachmentChunkPayloadDto = { it },
    ): Envelope {
        val dto = AttachmentChunkPlan("", attachmentId, bytes, filename, mimeType).payload(index).copy(conversationId = "")
        return Envelope(10L + index, "attachment_chunk", TS, MobileJson.encodeToJsonElement(edit(dto)), inReplyTo = inReplyTo)
    }

    private fun error(
        code: String,
        inReplyTo: Long = REQUEST_ID,
    ) = Envelope(
        id = 9,
        type = "error",
        ts = TS,
        payload =
            buildJsonObject {
                put("code", code)
                put("message", "static")
                put("retryable", false)
            },
        inReplyTo = inReplyTo,
    )

    private companion object {
        const val TS = "2026-09-24T00:00:00Z"
        const val REQUEST_ID = 41L
        const val ATTACHMENT_ID = "7c1d5e92-4a30-4b8f-9e21-6d4c3b0a8f55"
        const val OTHER_ID = "0f4c8a52-3d1e-4b7a-9c6d-2e5f8a1b3c4d"
    }
}

package de.pyryco.mobile.data.network

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * The sender's side of `attachment_chunk` (#829): chunk arithmetic, the whole-file metadata every chunk
 * repeats, and the 255-byte text bounds. The repository round trip is `RemoteConversationRepositoryAttachmentTest`.
 */
class AttachmentPayloadsTest {
    @Test
    fun emptyFile_isOneChunkCarryingZeroBytes() {
        val plan = plan(ByteArray(0))

        assertEquals(1, plan.totalChunks)
        val chunk = plan.payload(0)
        assertEquals("", chunk.data)
        assertEquals(0L, chunk.size)
        assertEquals(1, chunk.totalChunks)
        assertEquals(sha256Hex(ByteArray(0)), chunk.sha256)
    }

    @Test
    fun exactlyOneChunkOfBytes_isOneFullChunk() {
        val bytes = bytes(45_000)
        val plan = plan(bytes)

        assertEquals(1, plan.totalChunks)
        assertEquals(45_000, base64StdDecode(plan.payload(0).data).size)
    }

    @Test
    fun oneByteOverAChunk_isAFullChunkThenOneByte() {
        val bytes = bytes(45_001)
        val plan = plan(bytes)

        assertEquals(2, plan.totalChunks)
        val chunks = (0 until plan.totalChunks).map(plan::payload)
        assertEquals(listOf(45_000, 1), chunks.map { base64StdDecode(it.data).size })
        assertEquals(listOf(0, 1), chunks.map { it.index })
        assertTrue(chunks.all { it.totalChunks == 2 })
    }

    @Test
    fun everyChunkRepeatsTheWholeFilesMetadata_andTheDataReassembles() {
        val bytes = bytes(100_000)
        val plan = plan(bytes)

        val chunks = (0 until plan.totalChunks).map(plan::payload)
        assertEquals(3, chunks.size)
        chunks.forEach {
            assertEquals("conv-1", it.conversationId)
            assertEquals(ID, it.attachmentId)
            assertEquals(100_000L, it.size)
            assertEquals(sha256Hex(bytes), it.sha256)
            assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals("notes.txt", it.filename)
            assertEquals("text/plain", it.mimeType)
        }
        val reassembled = chunks.flatMap { base64StdDecode(it.data).toList() }.toByteArray()
        assertTrue(bytes.contentEquals(reassembled))
    }

    @Test
    fun encodesTheNineWireKeys() {
        val json = MobileJson.encodeToJsonElement(AttachmentChunkPayloadDto.serializer(), plan(bytes(3)).payload(0))

        assertEquals(
            setOf("conversation_id", "attachment_id", "index", "total_chunks", "filename", "mime_type", "size", "sha256", "data"),
            json.jsonObject.keys,
        )
    }

    // A cut inside a multibyte character would send invalid UTF-8; each "é" is two bytes, "😀" four.
    @Test
    fun longFilenameAndMimeType_areCutTo255BytesAtACodePointBoundary() {
        val name = "é".repeat(200)
        val emoji = "a" + "😀".repeat(100)
        val plan = AttachmentChunkPlan("conv-1", ID, bytes(1), name, emoji)

        val chunk = plan.payload(0)
        assertEquals(254, chunk.filename.toByteArray(Charsets.UTF_8).size)
        assertEquals("é".repeat(127), chunk.filename)
        assertEquals(253, chunk.mimeType.toByteArray(Charsets.UTF_8).size)
        assertEquals("a" + "😀".repeat(63), chunk.mimeType)
    }

    @Test
    fun textAtTheBound_isUnchanged() {
        val name = "a".repeat(255)

        assertEquals(name, truncateUtf8(name, 255))
        assertEquals("short", truncateUtf8("short", 255))
    }

    @Test
    fun toString_carriesNoContent() {
        val text = AttachmentChunkPlan("conv-1", ID, "secret".toByteArray(), "private-name.pdf", "application/pdf").toString()

        assertFalse(text.contains("private-name"))
        assertFalse(text.contains(sha256Hex("secret".toByteArray())))
    }

    private fun plan(bytes: ByteArray) = AttachmentChunkPlan("conv-1", ID, bytes, "notes.txt", "text/plain")

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ID = "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4a67"
    }
}

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

    // ---- attachment_offered (#898): the id shape and the display name --------------------------------

    @Test
    fun idShape_acceptsTheCanonicalLowercaseUuidV4() {
        assertTrue(isAttachmentIdShape(ID))
        assertTrue(isAttachmentIdShape("b8e0c374-2f61-4a95-8d0e-5c37a91b6e28"))
        // Every permitted variant nibble.
        for (variant in "89ab") assertTrue(isAttachmentIdShape("3f2a1c40-9b7e-4d16-${variant}5c3-0e8f1b2d4a67"))
    }

    @Test
    fun idShape_rejectsEveryNearMiss() {
        val rejected =
            listOf(
                "",
                ID.uppercase(),
                "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4A67", // one uppercase hex digit
                "3f2a1c40-9b7e-1d16-a5c3-0e8f1b2d4a67", // version 1
                "3f2a1c40-9b7e-4d16-c5c3-0e8f1b2d4a67", // variant outside 89ab
                "3f2a1c40-9b7e-4d16-75c3-0e8f1b2d4a67", // variant outside 89ab
                ID.dropLast(1), // 35 bytes
                ID + "0", // 37 bytes
                "3f2a1c409-b7e-4d16-a5c3-0e8f1b2d4a67", // hyphen moved
                "3f2a1c40-9b7e-4d16-a5c3_0e8f1b2d4a67", // hyphen replaced
                "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4g67", // non-hex
                "../../../../etc/passwd/../../aaaaaaa", // traversal, 36 bytes
                "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4a6١", // a non-ASCII digit
            )

        for (id in rejected) assertFalse("accepted $id", isAttachmentIdShape(id))
    }

    @Test
    fun displayName_dropsAnEscapeSequencesControlCharacters() {
        assertEquals("[31mred[0m.txt", attachmentDisplayName("\u001B[31mred\u001B[0m.txt"))
        assertEquals("abc", attachmentDisplayName("a\u0000b\nc\u007F\u009B"))
    }

    // `exe.pdf` rendered through a right-to-left override reads as `fdp.exe`, and an isolate hides the
    // real extension the same way.
    @Test
    fun displayName_dropsBidiOverridesAndOtherFormatCharacters() {
        assertEquals("invoice-exe.pdf", attachmentDisplayName("invoice-‮exe.pdf"))
        assertEquals("ab", attachmentDisplayName("a⁦⁧⁨⁩‪‫‬‭b"))
        assertEquals("ab", attachmentDisplayName("a​‍‎‏﻿b"))
        // A tag character sits outside the BMP: two surrogate Chars whose own type is not FORMAT.
        assertEquals("ab", attachmentDisplayName("a󠁁b"))
    }

    @Test
    fun displayName_dropsLineSeparatorsAndUnpairedSurrogates() {
        assertEquals("ab", attachmentDisplayName("a  b"))
        assertEquals("ab", attachmentDisplayName("a\uD800b\uDC00"))
    }

    @Test
    fun displayName_keepsOrdinaryTextVerbatim() {
        val name = "Quarterly summary — Ärzte 😀 (final).png"

        assertEquals(name, attachmentDisplayName(name))
    }

    @Test
    fun displayName_isCutToTheByteBoundAtACodePointBoundary() {
        // 150 two-byte characters: 300 bytes, so the cut falls on a character boundary at 254 bytes.
        val cut = attachmentDisplayName("ä".repeat(150))
        assertEquals("ä".repeat(127), cut)

        // A four-byte code point straddling the bound is dropped whole, never split.
        val emoji = attachmentDisplayName("a".repeat(253) + "😀")
        assertEquals("a".repeat(253), emoji)
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= ATTACHMENT_TEXT_MAX_BYTES)
    }

    // The bound is measured after cleaning, so dropped characters do not count against it.
    @Test
    fun displayName_cleansBeforeCutting() {
        val cleaned = attachmentDisplayName("‮".repeat(200) + "a".repeat(255))

        assertEquals("a".repeat(255), cleaned)
    }

    @Test
    fun offeredPayload_toStringCarriesNoFilename() {
        val text = AttachmentOfferedPayloadDto(ID, ID, "private-name.pdf").toString()

        assertFalse(text.contains("private-name"))
    }

    private fun plan(bytes: ByteArray) = AttachmentChunkPlan("conv-1", ID, bytes, "notes.txt", "text/plain")

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ID = "3f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4a67"
    }
}

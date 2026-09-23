package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.AttachmentChunkPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdDecode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The attachment upload round trip (#829): `attachment_chunk` frames out, `attachment_stored` or an
 * `error` naming a chunk back. The chunk arithmetic itself is `AttachmentPayloadsTest`; this class
 * proves correlation, settlement and the connection binding. Its own small pump, like the other
 * sibling classes of [RemoteConversationRepositoryTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryAttachmentTest {
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
    fun upload_sendsEveryChunkNamingTheConversationAndOneId_andSettlesOnItsStoredReply() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(45_001))
            runCurrent()

            val chunks = pump.chunks()
            assertEquals(2, chunks.size)
            assertEquals(listOf("attachment_chunk", "attachment_chunk"), pump.sent.map { it.type })
            assertTrue(chunks.all { it.conversationId == "conv-1" })
            val id = chunks.map { it.attachmentId }.distinct().single()
            assertTrue(id.matches(UUID_V4))
            assertEquals(listOf(45_000, 1), chunks.map { base64StdDecode(it.data).size })
            assertNull("nothing settles before the daemon answers", upload())

            pump.push(stored(id, inReplyTo = pump.sent.first().id))
            runCurrent()

            assertEquals(AttachmentUploadResult.Stored(id), upload())
            assertEquals(
                listOf(
                    "event=attachment_chunk id=$id index=0 total=2",
                    "event=attachment_chunk id=$id index=1 total=2",
                    "event=attachment_upload id=$id outcome=Stored",
                ),
                logs,
            )
            assertFalse(logs.any { it.contains("notes.txt") || it.contains("text/plain") || it.contains(chunks[0].sha256) })
        }

    @Test
    fun storedForAnotherId_settlesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(10))
            runCurrent()
            val id = pump.chunks().single().attachmentId
            pump.push(stored("0f2a1c40-9b7e-4d16-a5c3-0e8f1b2d4a67", inReplyTo = pump.sent.single().id))
            runCurrent()
            assertNull(upload())

            pump.push(stored(id, inReplyTo = null))
            runCurrent()
            assertEquals(AttachmentUploadResult.Stored(id), upload())
        }

    @Test
    fun storedOnAnotherHostsConnection_settlesNothing() =
        runTest {
            val pumpA = FakeSessionPump()
            val pumpB = FakeSessionPump()
            val repoA = repo(pumpA)
            repo(pumpB)

            val upload = startUpload(repoA, ByteArray(10))
            runCurrent()
            val id = pumpA.chunks().single().attachmentId
            pumpB.push(stored(id, inReplyTo = pumpA.sent.single().id))
            runCurrent()

            assertTrue(pumpB.sent.isEmpty())
            assertNull(upload())
        }

    @Test
    fun twoUploadsOfTheSameName_runOneAtATime_withDistinctIds_eachSettlingOnItsOwnReply() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val first = startUpload(repo, ByteArray(10))
            val second = startUpload(repo, ByteArray(20))
            runCurrent()
            val firstId = pump.chunks().single().attachmentId
            assertEquals("the second waits for the first to settle", 1, pump.sent.size)

            pump.push(stored(firstId, inReplyTo = pump.sent.single().id))
            runCurrent()
            assertEquals(AttachmentUploadResult.Stored(firstId), first())
            val secondId = pump.chunks().last().attachmentId
            assertNotEquals(firstId, secondId)
            assertTrue(pump.chunks().all { it.filename == "notes.txt" })

            pump.push(stored(firstId, inReplyTo = pump.sent.last().id))
            runCurrent()
            assertNull(second())
            pump.push(stored(secondId, inReplyTo = pump.sent.last().id))
            runCurrent()
            assertEquals(AttachmentUploadResult.Stored(secondId), second())
        }

    @Test
    fun errorNamingAChunk_carriesTheDaemonsCodeAndRetryable() =
        runTest {
            listOf("attachment.too_many_uploads" to true, "attachment.integrity_failed" to false).forEach { (code, retryable) ->
                val pump = FakeSessionPump()
                val repo = repo(pump)

                val upload = startUpload(repo, ByteArray(45_001))
                runCurrent()
                pump.push(error(inReplyTo = pump.sent.first().id, code = code, retryable = retryable))
                runCurrent()

                assertEquals(AttachmentUploadResult.Refused(code, retryable), upload())
            }
        }

    @Test
    fun refusalMidUpload_stopsFurtherChunks() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            pump.onSend = { envelope, count -> if (count == 1) pump.push(error(envelope.id, "attachment.too_large", false)) }

            val upload = startUpload(repo, ByteArray(3 * 45_000))
            runCurrent()

            assertEquals(AttachmentUploadResult.Refused("attachment.too_large", false), upload())
            assertEquals(1, pump.sent.size)
        }

    @Test
    fun errorNamingAnotherRequest_settlesNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(10))
            runCurrent()
            pump.push(error(inReplyTo = 4_242L, code = "attachment.storage_failed", retryable = true))
            runCurrent()

            assertNull(upload())
        }

    @Test
    fun oversizedErrorCode_isTreatedAsMalformed() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(10))
            runCurrent()
            pump.push(error(inReplyTo = pump.sent.single().id, code = "x".repeat(65), retryable = true))
            runCurrent()

            val result = upload() as AttachmentUploadResult.Refused
            assertEquals(false, result.retryable)
            assertNotEquals("x".repeat(65), result.code)
        }

    @Test
    fun refusedSend_failsAsReconnectRequired_withoutFurtherChunks() =
        runTest {
            listOf(false, true).forEach { throwOnSend ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.sendResult = false
                pump.throwOnSend = throwOnSend

                val upload = startUpload(repo, ByteArray(3 * 45_000))
                runCurrent()

                assertEquals(AttachmentUploadResult.ReconnectRequired, upload())
                assertEquals(1, pump.sent.size)
            }
        }

    @Test
    fun connectionDropMidUpload_failsAsReconnectRequired_andALaterUploadSendsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(10))
            runCurrent()
            val id = pump.chunks().single().attachmentId
            pump.close()
            runCurrent()
            assertEquals(AttachmentUploadResult.ReconnectRequired, upload())

            val later = startUpload(repo, ByteArray(10))
            runCurrent()
            assertEquals(AttachmentUploadResult.ReconnectRequired, later())
            assertEquals(1, pump.sent.size)
            assertTrue(pump.chunks().all { it.attachmentId == id })
        }

    @Test
    fun fileOverTheLocalBound_isRefusedBeforeAnyChunk() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(AttachmentUploadLimit.MAX_BYTES + 1))
            runCurrent()

            assertEquals(AttachmentUploadResult.TooLarge, upload())
            assertTrue(pump.sent.isEmpty())
        }

    @Test
    fun fileAtTheLocalBound_isSent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            startUpload(repo, ByteArray(AttachmentUploadLimit.MAX_BYTES))
            runCurrent()

            assertEquals(178, pump.sent.size)
        }

    private fun TestScope.repo(pump: FakeSessionPump) = RemoteConversationRepository(pump, backgroundScope)

    private fun TestScope.startUpload(
        repo: RemoteConversationRepository,
        bytes: ByteArray,
    ): () -> AttachmentUploadResult? {
        var outcome: AttachmentUploadResult? = null
        backgroundScope.launch { outcome = repo.uploadAttachment("conv-1", bytes, "notes.txt", "text/plain") }
        return { outcome }
    }

    private fun FakeSessionPump.chunks(): List<AttachmentChunkPayloadDto> =
        sent.map { MobileJson.decodeFromJsonElement(AttachmentChunkPayloadDto.serializer(), it.payload) }

    private fun stored(
        attachmentId: String,
        inReplyTo: Long?,
    ) = Envelope(
        id = 99L,
        type = "attachment_stored",
        ts = TS,
        payload = buildJsonObject { put("attachment_id", attachmentId) },
        inReplyTo = inReplyTo,
    )

    private fun error(
        inReplyTo: Long,
        code: String,
        retryable: Boolean,
    ) = Envelope(
        id = 98L,
        type = "error",
        ts = TS,
        payload =
            buildJsonObject {
                put("code", code)
                put("message", "refused")
                put("retryable", retryable)
            },
        inReplyTo = inReplyTo,
    )

    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()
        var sendResult = true
        var throwOnSend = false

        /** Runs after each recorded send with the envelope and the running count. */
        var onSend: (Envelope, Int) -> Unit = { _, _ -> }

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            onSend(envelope, sent.size)
            if (throwOnSend) throw IllegalStateException("transport down")
            return sendResult
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }

        fun close() {
            inboundChannel.close()
        }
    }

    private companion object {
        const val TS = "2026-09-23T00:00:00Z"
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

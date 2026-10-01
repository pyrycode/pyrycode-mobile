package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.AttachmentChunkPayloadDto
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.base64StdDecode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun progress_reportsEachChunkInOrder_afterItReachesTheWire() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val (reports, onProgress) = pump.progressLog()

            val upload = startUpload(repo, ByteArray(3 * 45_000), onProgress)
            runCurrent()

            assertEquals(listOf("1/3@1", "2/3@2", "3/3@3"), reports)
            pump.push(stored(pump.chunks().first().attachmentId, inReplyTo = pump.sent.first().id))
            runCurrent()
            assertTrue(upload() is AttachmentUploadResult.Stored)
            assertEquals("the settle reports nothing", 3, reports.size)
        }

    @Test
    fun progress_stopsAtARefusal_afterTheChunkThatWasSent() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val (reports, onProgress) = pump.progressLog()
            pump.onSend = { envelope, count -> if (count == 2) pump.push(error(envelope.id, "attachment.too_large", false)) }

            val upload = startUpload(repo, ByteArray(3 * 45_000), onProgress)
            runCurrent()

            assertEquals(AttachmentUploadResult.Refused("attachment.too_large", false), upload())
            assertEquals(listOf("1/3@1", "2/3@2"), reports)
        }

    @Test
    fun progress_skipsAChunkWhoseTransferSettledDuringItsSend() =
        runTest {
            val pump = FakeSessionPump()
            // The collector resumes inline on the push below, so the refusal settles inside `send`.
            val repo =
                RemoteConversationRepository(
                    pump,
                    CoroutineScope(
                        backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
                    ),
                )
            val (reports, onProgress) = pump.progressLog()
            pump.onSend = { envelope, count -> if (count == 2) pump.push(error(envelope.id, "attachment.too_large", false)) }

            val upload = startUpload(repo, ByteArray(3 * 45_000), onProgress)
            runCurrent()

            assertEquals(AttachmentUploadResult.Refused("attachment.too_large", false), upload())
            assertEquals(2, pump.sent.size)
            assertEquals(listOf("1/3@1"), reports)
        }

    @Test
    fun progress_reportsNothingForAFailedSend() =
        runTest {
            listOf(false, true).forEach { throwOnSend ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                val (reports, onProgress) = pump.progressLog()
                pump.onSend = { _, count ->
                    if (count == 2) {
                        pump.sendResult = false
                        pump.throwOnSend = throwOnSend
                    }
                }

                val upload = startUpload(repo, ByteArray(3 * 45_000), onProgress)
                runCurrent()

                assertEquals(AttachmentUploadResult.SendFailed, upload())
                assertEquals(listOf("1/3@1"), reports)
            }
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
    fun refusedSend_failsAsSendFailed_withoutFurtherChunks() =
        runTest {
            listOf(false, true).forEach { throwOnSend ->
                val pump = FakeSessionPump()
                val repo = repo(pump)
                pump.sendResult = false
                pump.throwOnSend = throwOnSend

                val upload = startUpload(repo, ByteArray(3 * 45_000))
                runCurrent()

                assertEquals(AttachmentUploadResult.SendFailed, upload())
                assertEquals(1, pump.sent.size)
            }
        }

    @Test
    fun connectionDropMidUpload_failsAsConnectionLost_andALaterUploadIsNotConnected() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            val upload = startUpload(repo, ByteArray(10))
            runCurrent()
            val id = pump.chunks().single().attachmentId
            pump.close()
            runCurrent()
            assertEquals(AttachmentUploadResult.ConnectionLost, upload())

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

    // ---- naming uploaded attachments on a sent message (#830) -----------------------------------

    @Test
    fun sendWithIds_namesEachOnceInCallerOrder_andDrawsTheEchoBeforeTheAckLikeATextOnlySend() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val thread = collectThread(repo)
            runCurrent()

            val send = startSend(repo, listOf(ID_B, ID_A, ID_B))
            runCurrent()

            val sent = pump.sends().single()
            val payload = sent.payload.jsonObject
            assertEquals("conv-1", payload.getValue("conversation_id").jsonPrimitive.content)
            assertEquals(listOf(ID_B, ID_A), payload.getValue("attachment_ids").jsonArray.map { it.jsonPrimitive.content })
            assertNull("the send still awaits its ack", send())
            // #1355: the echo is drawn before the reply, under the frame's own message_id.
            val echo = (thread.last().single() as ThreadItem.MessageItem).message
            assertEquals(payload.getValue("message_id").jsonPrimitive.content, echo.id)

            pump.push(ack(sent.id))
            runCurrent()

            val message = requireNotNull(send()).getOrThrow()
            assertEquals(Role.User, message.role)
            assertEquals("hi", message.content)
            assertEquals(payload.getValue("message_id").jsonPrimitive.content, message.id)
            assertEquals(listOf(ThreadItem.MessageItem(message)), thread.last())
            assertEquals(echo, message)
            assertEquals(listOf("event=send_message attachments=2"), logs)
        }

    @Test
    fun sendWithAttachments_theThreadRowCarriesOneReferencePerIdInSendOrder_withItsNameAndMimeType() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val thread = collectThread(repo)
            runCurrent()

            val send =
                startSend(
                    repo,
                    emptyList(),
                    listOf(
                        MessageAttachment(ID_B, "photo.jpg", "image/jpeg"),
                        MessageAttachment(ID_A, "notes\u202E.txt", "text/plain"),
                        MessageAttachment(ID_B, "again.jpg", "image/jpeg"),
                    ),
                )
            runCurrent()
            val sent = pump.sends().single()
            assertEquals(
                listOf(ID_B, ID_A),
                sent.payload.jsonObject
                    .getValue("attachment_ids")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            pump.push(ack(sent.id))
            runCurrent()

            val expected =
                listOf(
                    MessageAttachment(ID_B, "photo.jpg", "image/jpeg"),
                    MessageAttachment(ID_A, "notes.txt", "text/plain"),
                )
            assertEquals(expected, requireNotNull(send()).getOrThrow().attachments)
            val row = (thread.last().single() as ThreadItem.MessageItem).message
            assertEquals(expected, row.attachments)
        }

    @Test
    fun sendWithoutIds_carriesNoAttachmentIdsKey() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)

            startSend(repo, emptyList())
            backgroundScope.launch { runCatching { repo.sendMessage("conv-1", "hi") } }
            runCurrent()

            assertEquals(2, pump.sends().size)
            pump.sends().forEach { sent ->
                assertEquals(setOf("conversation_id", "message_id", "text"), sent.payload.jsonObject.keys)
            }
            assertTrue(logs.isEmpty())
        }

    @Test
    fun sendNamingMoreThanTheBound_isRefusedBeforeAnythingGoesOut() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val thread = collectThread(repo)
            runCurrent()

            val ids = (0..MessageAttachmentIds.MAX).map { uuid(it) }
            val send = startSend(repo, ids + ids.first())
            runCurrent()

            assertTrue(requireNotNull(send()).exceptionOrNull() is IllegalArgumentException)
            assertTrue(pump.sends().isEmpty())
            assertEquals(listOf(emptyList<ThreadItem>()), thread)
            assertEquals(listOf("event=send_message outcome=too_many_attachments count=33"), logs)
        }

    @Test
    fun sendRefusedWithAttachmentNotFound_surfacesTheCodeAndKeepsTheEcho() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val thread = collectThread(repo)
            runCurrent()

            val send = startSend(repo, listOf(ID_A))
            runCurrent()
            pump.push(error(pump.sends().single().id, code = "attachment.not_found", retryable = false))
            runCurrent()

            val failure = requireNotNull(send()).exceptionOrNull()
            assertTrue("expected RelayErrorException, got $failure", failure is RelayErrorException)
            assertEquals("attachment.not_found", (failure as RelayErrorException).code)
            // #1355: as on desktop, a refused send leaves its echo drawn.
            assertEquals(1, thread.last().size)
        }

    private fun TestScope.repo(pump: FakeSessionPump) = RemoteConversationRepository(pump, backgroundScope)

    private fun TestScope.startSend(
        repo: RemoteConversationRepository,
        attachmentIds: List<String>,
        attachments: List<MessageAttachment> = attachmentIds.map { MessageAttachment(it) },
    ): () -> Result<Message>? {
        var outcome: Result<Message>? = null
        backgroundScope.launch { outcome = runCatching { repo.sendMessage("conv-1", "hi", attachments) } }
        return { outcome }
    }

    private fun TestScope.collectThread(repo: RemoteConversationRepository): List<List<ThreadItem>> {
        val emissions = mutableListOf<List<ThreadItem>>()
        backgroundScope.launch { repo.observeMessages("conv-1").collect { emissions += it } }
        return emissions
    }

    /** The `send_message` frames only — collecting the thread sends its own catch-up request. */
    private fun FakeSessionPump.sends(): List<Envelope> = sent.filter { it.type == "send_message" }

    private fun ack(inReplyTo: Long) = Envelope(id = 97L, type = "ack", ts = TS, payload = JsonObject(emptyMap()), inReplyTo = inReplyTo)

    private fun uuid(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    private fun TestScope.startUpload(
        repo: RemoteConversationRepository,
        bytes: ByteArray,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): () -> AttachmentUploadResult? {
        var outcome: AttachmentUploadResult? = null
        backgroundScope.launch { outcome = repo.uploadAttachment("conv-1", bytes, "notes.txt", "text/plain", onProgress) }
        return { outcome }
    }

    /** Each report as `sent/total@chunksOnTheWire`, so a report's position relative to its send is visible. */
    private fun FakeSessionPump.progressLog(): Pair<MutableList<String>, (Int, Int) -> Unit> {
        val reports = mutableListOf<String>()
        return reports to { sent, total -> reports += "$sent/$total@${this.sent.size}" }
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
        const val ID_A = "0f4c8a52-3d1e-4b7a-9c6d-2e5f8a1b3c4d"
        const val ID_B = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val UUID_V4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }
}

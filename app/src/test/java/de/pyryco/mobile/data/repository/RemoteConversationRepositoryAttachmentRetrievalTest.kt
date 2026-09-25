package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.ATTACHMENT_CHUNK_BYTES
import de.pyryco.mobile.data.network.AttachmentChunkPlan
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ReadWorkspaceFilePayloadDto
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The retrieval round trip on one connection (#899): one `request_attachment` out, `attachment_chunk` frames
 * or an `error` naming it back, and every way the connection can end it. The reassembly rules themselves are
 * [AttachmentRetrievalTransferTest]; this class proves the request, the routing and the teardown doors.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConversationRepositoryAttachmentRetrievalTest {
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
    fun fetch_sendsOneRequestNamingTheConversationAndAttachment_andAssemblesItsChunks() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val bytes = ByteArray(ATTACHMENT_CHUNK_BYTES + 3) { it.toByte() }

            val fetch = startFetch(repo)
            runCurrent()

            val request = pump.sent.single()
            assertEquals("request_attachment", request.type)
            val payload = request.payload.jsonObject
            assertEquals(CONVERSATION_ID, payload.getValue("conversation_id").jsonPrimitive.content)
            assertEquals(ATTACHMENT_ID, payload.getValue("attachment_id").jsonPrimitive.content)
            assertNull(fetch())

            pump.push(chunk(bytes, 1, request.id))
            pump.push(chunk(bytes, 0, request.id))
            runCurrent()

            val fetched = fetch() as AttachmentFetchResult.Fetched
            val out = ByteArrayOutputStream().also { fetched.content.writeTo(it) }
            assertArrayEquals(bytes, out.toByteArray())
            assertEquals(1, pump.sent.size)
            assertEquals(
                listOf(
                    "event=attachment_request id=$ATTACHMENT_ID",
                    "event=attachment_chunk_in id=$ATTACHMENT_ID index=1 total=2",
                    "event=attachment_chunk_in id=$ATTACHMENT_ID index=0 total=2",
                    "event=attachment_retrieval id=$ATTACHMENT_ID outcome=Fetched",
                ),
                logs,
            )
            assertFalse(logs.any { it.contains("secret.pdf") || it.contains("application/pdf") })
        }

    @Test
    fun notFound_isNotFound() =
        runTest {
            val pump = FakeSessionPump()
            val fetch = startFetch(repo(pump))
            runCurrent()
            pump.push(error("attachment.not_found", pump.sent.single().id))
            runCurrent()
            assertEquals(AttachmentRetrievalResult.NotFound, fetch())
        }

    @Test
    fun idsOfTheWrongShape_areNotFound_andSendNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            assertEquals(AttachmentRetrievalResult.NotFound, repo.fetchAttachment(CONVERSATION_ID, "../../etc/passwd"))
            assertEquals(AttachmentRetrievalResult.NotFound, repo.fetchAttachment("", ATTACHMENT_ID))
            assertTrue(pump.sent.isEmpty())
            assertTrue(logs.none { it.contains("passwd") })
        }

    @Test
    fun refusedSend_isRetryable() =
        runTest {
            val pump = FakeSessionPump().apply { sendResult = false }
            assertEquals(AttachmentRetrievalResult.Unavailable, repo(pump).fetchAttachment(CONVERSATION_ID, ATTACHMENT_ID))
            pump.sendResult = true
            pump.throwOnSend = true
            assertEquals(AttachmentRetrievalResult.Unavailable, repo(pump).fetchAttachment(CONVERSATION_ID, ATTACHMENT_ID))
        }

    @Test
    fun droppedConnection_midStream_isRetryable_andLaterFetchesAreRefused() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val fetch = startFetch(repo)
            runCurrent()
            pump.push(chunk(ByteArray(ATTACHMENT_CHUNK_BYTES + 1), 0, pump.sent.single().id))
            runCurrent()

            pump.close()
            runCurrent()

            assertEquals(AttachmentRetrievalResult.Unavailable, fetch())
            assertEquals(AttachmentRetrievalResult.Unavailable, repo.fetchAttachment(CONVERSATION_ID, ATTACHMENT_ID))
            assertEquals("nothing is sent on a dead connection", 1, pump.sent.size)
        }

    @Test
    fun streamWithNoChunkForTheStallInterval_isRetryable_andEachChunkResetsTheClock() =
        runTest {
            val pump = FakeSessionPump()
            val bytes = ByteArray(ATTACHMENT_CHUNK_BYTES * 2 + 1)
            val fetch = startFetch(repo(pump))
            runCurrent()
            val requestId = pump.sent.single().id

            advanceTimeBy(29_000)
            pump.push(chunk(bytes, 0, requestId))
            runCurrent()
            advanceTimeBy(29_000)
            runCurrent()
            assertNull("an accepted chunk re-arms the deadline", fetch())

            advanceTimeBy(1_001)
            runCurrent()
            assertEquals(AttachmentRetrievalResult.Unavailable, fetch())
        }

    @Test
    fun afterAFailure_aLaterFetchSendsAFreshRequest_andIgnoresTheOldStream() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val bytes = ByteArray(10) { 1 }
            val first = startFetch(repo)
            runCurrent()
            val firstId = pump.sent.single().id
            pump.push(error("attachment.stream_aborted", firstId))
            runCurrent()
            assertEquals(AttachmentRetrievalResult.Unavailable, first())

            val second = startFetch(repo)
            runCurrent()
            val secondId = pump.sent.last().id
            assertEquals(2, pump.sent.size)
            assertTrue(secondId != firstId)

            pump.push(chunk(ByteArray(10) { 9 }, 0, firstId))
            runCurrent()
            assertNull("a chunk answering the old request is not this one's", second())

            pump.push(chunk(bytes, 0, secondId))
            runCurrent()
            assertTrue(second() is AttachmentFetchResult.Fetched)
        }

    @Test
    fun retrievalsOnOneConnection_runOneAtATime() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val first = startFetch(repo)
            val second = startFetch(repo)
            runCurrent()
            assertEquals(1, pump.sent.size)

            pump.push(error("attachment.not_found", pump.sent.single().id))
            runCurrent()
            assertEquals(AttachmentRetrievalResult.NotFound, first())
            assertEquals(2, pump.sent.size)
            assertNull(second())
        }

    @Test
    fun readWorkspaceFile_sendsOneRequestNamingTheConversationAndPathVerbatim_andAssemblesTheMintedStream() =
        runTest {
            val pump = FakeSessionPump()
            val bytes = ByteArray(ATTACHMENT_CHUNK_BYTES + 3) { it.toByte() }
            val read = startRead(repo(pump))
            runCurrent()

            val request = pump.sent.single()
            assertEquals("read_workspace_file", request.type)
            val payload = request.payload.jsonObject
            assertEquals(setOf("conversation_id", "path"), payload.keys)
            assertEquals(CONVERSATION_ID, payload.getValue("conversation_id").jsonPrimitive.content)
            assertEquals(PATH, payload.getValue("path").jsonPrimitive.content)

            pump.push(chunk(bytes, 1, request.id, MINTED_ID))
            pump.push(chunk(bytes, 0, request.id, MINTED_ID))
            runCurrent()

            val out = ByteArrayOutputStream().also { (read() as AttachmentFetchResult.Fetched).content.writeTo(it) }
            assertArrayEquals(bytes, out.toByteArray())
            assertEquals(
                listOf(
                    "event=workspace_file_request",
                    "event=attachment_chunk_in id=$MINTED_ID index=1 total=2",
                    "event=attachment_chunk_in id=$MINTED_ID index=0 total=2",
                    "event=workspace_file_read outcome=Fetched",
                ),
                logs,
            )
        }

    @Test
    fun readWorkspaceFile_twiceForOnePath_sendsTwoRequests() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val first = startRead(repo)
            runCurrent()
            pump.push(chunk(ByteArray(4), 0, pump.sent.single().id, MINTED_ID))
            runCurrent()
            assertTrue(first() is AttachmentFetchResult.Fetched)

            val second = startRead(repo)
            runCurrent()
            assertEquals(2, pump.sent.size)
            assertEquals("read_workspace_file", pump.sent.last().type)
            pump.push(chunk(ByteArray(4) { 7 }, 0, pump.sent.last().id, ATTACHMENT_ID))
            runCurrent()
            assertTrue(second() is AttachmentFetchResult.Fetched)
        }

    @Test
    fun readWorkspaceFile_malformedConversationOrBlankPath_isNotFound_andSendsNothing() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            assertEquals(AttachmentRetrievalResult.NotFound, repo.readWorkspaceFile("../conv", PATH))
            assertEquals(AttachmentRetrievalResult.NotFound, repo.readWorkspaceFile(CONVERSATION_ID, ""))
            assertEquals(AttachmentRetrievalResult.NotFound, repo.readWorkspaceFile(CONVERSATION_ID, " \t"))
            assertTrue(pump.sent.isEmpty())
            assertTrue(logs.isEmpty())
        }

    @Test
    fun readWorkspaceFile_refusalsAndFailures_matchFetchAttachment() =
        runTest {
            assertEquals(AttachmentRetrievalResult.NotFound, readAnswered { error("attachment.not_found", it) })
            assertEquals(AttachmentRetrievalResult.Unavailable, readAnswered { error("attachment.stream_aborted", it) })
            assertEquals(AttachmentRetrievalResult.Unavailable, readAnswered { error("protocol.malformed", it) })
            assertEquals(
                AttachmentRetrievalResult.Unavailable,
                repo(FakeSessionPump().apply { sendResult = false }).readWorkspaceFile(CONVERSATION_ID, PATH),
            )
            assertEquals(
                AttachmentRetrievalResult.Unavailable,
                repo(FakeSessionPump().apply { throwOnSend = true }).readWorkspaceFile(CONVERSATION_ID, PATH),
            )
        }

    @Test
    fun readWorkspaceFile_droppedConnectionOrStall_isRetryable() =
        runTest {
            val dropped = FakeSessionPump()
            val droppedRead = startRead(repo(dropped))
            runCurrent()
            dropped.push(chunk(ByteArray(ATTACHMENT_CHUNK_BYTES + 1), 0, dropped.sent.single().id, MINTED_ID))
            runCurrent()
            dropped.close()
            runCurrent()
            assertEquals(AttachmentRetrievalResult.Unavailable, droppedRead())

            val stalled = FakeSessionPump()
            val stalledRead = startRead(repo(stalled))
            runCurrent()
            advanceTimeBy(30_001)
            runCurrent()
            assertEquals(AttachmentRetrievalResult.Unavailable, stalledRead())
        }

    @Test
    fun readWorkspaceFile_andFetchAttachment_runOneAtATimeOnOneConnection() =
        runTest {
            val pump = FakeSessionPump()
            val repo = repo(pump)
            val read = startRead(repo)
            val fetch = startFetch(repo)
            runCurrent()
            assertEquals(listOf("read_workspace_file"), pump.sent.map { it.type })

            pump.push(error("attachment.not_found", pump.sent.single().id))
            runCurrent()
            assertEquals(AttachmentRetrievalResult.NotFound, read())
            assertEquals(listOf("read_workspace_file", "request_attachment"), pump.sent.map { it.type })
            assertNull(fetch())
        }

    @Test
    fun readWorkspaceFile_neverLogsOrPrintsThePathOrConversation() =
        runTest {
            val pump = FakeSessionPump()
            val read = startRead(repo(pump))
            runCurrent()
            pump.push(chunk(ByteArray(5), 0, pump.sent.single().id, MINTED_ID))
            runCurrent()
            assertTrue(read() is AttachmentFetchResult.Fetched)

            val printed =
                logs + ReadWorkspaceFilePayloadDto(CONVERSATION_ID, PATH).toString() +
                    AttachmentRetrievalTransfer(1L, null).toString()
            assertTrue(printed.none { it.contains("secret notes") || it.contains(CONVERSATION_ID) })
        }

    private fun TestScope.readAnswered(answer: (Long) -> Envelope): AttachmentFetchResult? {
        val pump = FakeSessionPump()
        val read = startRead(repo(pump))
        runCurrent()
        pump.push(answer(pump.sent.single().id))
        runCurrent()
        return read()
    }

    private fun TestScope.startRead(repo: RemoteConversationRepository): () -> AttachmentFetchResult? {
        var outcome: AttachmentFetchResult? = null
        backgroundScope.launch { outcome = repo.readWorkspaceFile(CONVERSATION_ID, PATH) }
        return { outcome }
    }

    private fun TestScope.repo(pump: FakeSessionPump) = RemoteConversationRepository(pump, backgroundScope)

    private fun TestScope.startFetch(repo: RemoteConversationRepository): () -> AttachmentFetchResult? {
        var outcome: AttachmentFetchResult? = null
        backgroundScope.launch { outcome = repo.fetchAttachment(CONVERSATION_ID, ATTACHMENT_ID) }
        return { outcome }
    }

    private fun chunk(
        bytes: ByteArray,
        index: Int,
        inReplyTo: Long,
        attachmentId: String = ATTACHMENT_ID,
    ): Envelope {
        val dto = AttachmentChunkPlan("", attachmentId, bytes, "secret.pdf", "application/pdf").payload(index)
        return Envelope(100L + index, "attachment_chunk", TS, MobileJson.encodeToJsonElement(dto), inReplyTo = inReplyTo)
    }

    private fun error(
        code: String,
        inReplyTo: Long,
    ) = Envelope(
        id = 98L,
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

    private class FakeSessionPump : SessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        val sent = mutableListOf<Envelope>()
        var sendResult = true
        var throwOnSend = false

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
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
        const val TS = "2026-09-24T00:00:00Z"
        const val CONVERSATION_ID = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
        const val ATTACHMENT_ID = "7c1d5e92-4a30-4b8f-9e21-6d4c3b0a8f55"
        const val MINTED_ID = "0f4c8a52-3d1e-4b7a-9c6d-2e5f8a1b3c4d"
        const val PATH = "../docs/secret notes.md"
    }
}

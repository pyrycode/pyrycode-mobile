package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheException
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.MAX_CACHED_THREAD_ROWS
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The thread restore (#797): cached rows merged under the live projection through [mergeCachedRows],
 * settled rows written back only when they change, every other flow pure delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachingConversationRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    private val live = MutableStateFlow<List<ThreadItem>>(emptyList())
    private val stall = MutableStateFlow(true)
    private var failDelete = false
    private val archiveCalls = mutableListOf<String>()

    /**
     * The live side: a fake for everything, with the thread and stall projections under test control.
     * Delete, archive and unarchive are recorded rather than applied to the fake's seed (#798), so the
     * removal tests can name any conversation id and make the daemon refuse a delete.
     */
    private val delegate =
        object : ConversationRepository by FakeConversationRepository() {
            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live

            override fun observeStall(conversationId: String): Flow<Boolean> = stall

            override suspend fun delete(conversationId: String) {
                if (failDelete) throw IllegalStateException("daemon refused the delete")
            }

            override suspend fun archive(conversationId: String) {
                archiveCalls += "archive $conversationId"
            }

            override suspend fun unarchive(conversationId: String) {
                archiveCalls += "unarchive $conversationId"
            }
        }

    private fun message(
        id: String,
        isStreaming: Boolean = false,
        content: String = "content of $id",
    ) = ThreadItem.MessageItem(
        Message(id, "session-2", Role.Assistant, content, Instant.parse("2026-09-22T10:00:00Z"), isStreaming),
    )

    private fun tool(
        id: String,
        status: ToolCallStatus,
    ) = ThreadItem.MessageItem(
        Message(id, "session-2", Role.Tool, "", Instant.parse("2026-09-22T10:00:00Z"), false, ToolCall("Bash", "ls", "", status)),
    )

    private val boundary =
        ThreadItem.SessionBoundary("session-1", "session-2", BoundaryReason.Clear, Instant.parse("2026-09-22T09:00:00Z"))

    @Test
    fun `offline the restored rows draw verbatim and nothing is written`() =
        runTest(UnconfinedTestDispatcher()) {
            val restored = listOf(message("m1"), boundary, message("m2"))
            val cache = RecordingCache(restored)

            val drawn = CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").first()

            assertEquals(restored, drawn)
            assertEquals(emptyList<List<ThreadItem>>(), cache.writes)
        }

    @Test
    fun `live rows merge over restored rows with no second row for a drawn key`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache =
                RecordingCache(listOf(message("m1"), boundary, message("m2", content = "cached"), tool("toolu_1", ToolCallStatus.Done)))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch {
                    CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect {
                        emissions +=
                            it
                    }
                }

            // A reconnect: the newest page re-delivers the boundary, m2 and the tool call, then a new row.
            live.value = listOf(boundary, message("m2"), tool("toolu_1", ToolCallStatus.Done), message("m3"))

            assertEquals(
                listOf(message("m1"), boundary, message("m2"), tool("toolu_1", ToolCallStatus.Done), message("m3")),
                emissions.last(),
            )
            assertEquals(emissions.last(), cache.writes.single())
            job.cancel()
        }

    @Test
    fun `a disconnect keeps the rows drawn during the connection and writes nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1")))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            live.value = listOf(message("m1"), message("m2"), message("t1", isStreaming = true))
            val written = listOf(message("m1"), message("m2"))
            assertEquals(listOf(written), cache.writes)

            // The stable facade's empty emission on a connection loss.
            live.value = emptyList()

            assertEquals(written, emissions.last())
            assertEquals(listOf(written), cache.writes)
            job.cancel()
        }

    @Test
    fun `a reconnect after a disconnect merges over everything drawn so far`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1")))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            live.value = listOf(message("m1"), message("m2"))
            live.value = emptyList()
            // The next connection's newest page re-delivers m2 and adds m3.
            live.value = listOf(message("m2"), message("m3"))

            val drawn = listOf(message("m1"), message("m2"), message("m3"))
            assertEquals(drawn, emissions.last())
            assertEquals(drawn, cache.writes.last())
            job.cancel()
        }

    // ---- #983: a row only the cache holds keeps its place ---------------------------------------

    private val offer =
        ThreadItem.MessageItem(attachmentOfferRow(AttachmentOffer(ATTACHMENT_ID, "report.pdf"), Instant.parse("2026-09-22T10:00:01Z")))

    @Test
    fun `a cold restore keeps an offer row between the rows around it and writes that order back`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1"), message("a1"), offer, message("a2")))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            // The daemon never replays the offer: the first page holds only its neighbours.
            live.value = listOf(message("m1"), message("a1"), message("a2"))
            assertEquals(listOf(message("m1"), message("a1"), offer, message("a2")), emissions.last())

            live.value = listOf(message("m1"), message("a1"), message("a2"), message("m3"))
            val drawn = listOf(message("m1"), message("a1"), offer, message("a2"), message("m3"))
            assertEquals(drawn, emissions.last())
            assertEquals(drawn, cache.writes.last())
            job.cancel()
        }

    @Test
    fun `a reconnect keeps an offer row drawn during the connection in arrival order`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(emptyList())
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            live.value = listOf(message("m1"), message("a1"), offer, message("a2"))
            live.value = emptyList()
            live.value = listOf(message("m1"), message("a1"), message("a2"))

            val drawn = listOf(message("m1"), message("a1"), offer, message("a2"))
            assertEquals(drawn, emissions.last())
            assertEquals(drawn, cache.writes.last())
            job.cancel()
        }

    @Test
    fun `cache-only rows with no row above them on the live page still draw in front`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(offer, message("m1"), message("m2")))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            // A page that does not overlap the cache at all: every cached row goes above it, in cached order.
            live.value = listOf(message("m3"))

            assertEquals(listOf(offer, message("m1"), message("m2"), message("m3")), emissions.last())
            job.cancel()
        }

    @Test
    fun `a sent row re-delivered by history takes its attachment names back from the cache`() =
        runTest(UnconfinedTestDispatcher()) {
            val named = MessageAttachment(ATTACHMENT_ID, "photo.jpg", "image/jpeg")

            fun sent(attachment: MessageAttachment) =
                ThreadItem.MessageItem(
                    Message("s1", "", Role.User, "", Instant.parse("2026-09-22T10:00:00Z"), false, attachments = listOf(attachment)),
                )
            val cache = RecordingCache(listOf(sent(named)))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            live.value = listOf(sent(MessageAttachment(ATTACHMENT_ID)), message("a1"))

            assertEquals(listOf(sent(named), message("a1")), emissions.last())
            assertEquals(listOf(sent(named), message("a1")), cache.writes.last())
            job.cancel()
        }

    // ---- #1353: banners, compaction dividers and refusals survive a restore ------------------------

    @Test
    fun `an empty suppressed live thread retains restored history and delivery attachment hints`() =
        runTest(UnconfinedTestDispatcher()) {
            val named = MessageAttachment(ATTACHMENT_ID, "photo.jpg", "image/jpeg")
            val sent =
                ThreadItem.MessageItem(
                    Message("mine", "", Role.User, "", Instant.parse("2026-09-22T10:00:00Z"), false, attachments = listOf(named)),
                )
            val snapshots = MutableStateFlow(ThreadSnapshot(listOf(sent)))
            val source =
                object : ConversationRepository by delegate, ThreadSnapshotSource {
                    override fun observeThreadSnapshot(conversationId: String): Flow<ThreadSnapshot> = snapshots
                }
            val cache = RecordingCache(listOf(message("older"), sent))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch {
                    CachingConversationRepository(source, cache, "server-a").observeMessages("conv-1").collect { emissions += it }
                }

            snapshots.value = ThreadSnapshot(emptyList(), setOf("mine"))
            assertEquals(listOf(message("older")), emissions.last())
            snapshots.value = ThreadSnapshot(emptyList(), setOf("mine", "another-pending-echo"))
            assertEquals(listOf(message("older")), emissions.last())
            assertEquals(listOf(message("older")), cache.writes.last())

            // Pending suppression must not rebase the cache as if the connection closed. Otherwise
            // the second empty reading spends the attachment hint before the actual delivered push.
            val delivered = ThreadItem.MessageItem(sent.message.copy(attachments = listOf(MessageAttachment(ATTACHMENT_ID))))
            snapshots.value = ThreadSnapshot(listOf(delivered))
            assertEquals(listOf(message("older"), sent), emissions.last())
            assertEquals(listOf(message("older"), sent), cache.writes.last())
            job.cancel()
        }

    private val banner = ThreadItem.Banner(BannerLevel.Warning, "hook said no", false, Instant.parse("2026-09-22T10:00:01Z"))
    private val compaction = ThreadItem.CompactionBoundary(24000, 3000, true, Instant.parse("2026-09-22T10:00:02Z"))
    private val refusal =
        ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "refused", false, Instant.parse("2026-09-22T10:00:03Z"))

    @Test
    fun `a restored thread draws its banner compaction and refusal rows where they were`() =
        runTest(UnconfinedTestDispatcher()) {
            val rows = listOf(message("m1"), banner, message("m2"), compaction, boundary, refusal, message("m3"))
            val cache = fileCache()
            cache.writeThread("server-a", "conv-1", rows).getOrThrow()

            val drawn = CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").first()

            assertEquals(rows, drawn)
        }

    @Test
    fun `a cached banner the live page re-delivers draws once`() = assertRedeliveredRowDrawsOnce(banner)

    @Test
    fun `a cached compaction divider the live page re-delivers draws once`() = assertRedeliveredRowDrawsOnce(compaction)

    @Test
    fun `a cached model refusal the live page re-delivers draws once`() = assertRedeliveredRowDrawsOnce(refusal)

    /** [row] sits between two messages in the cache; the reconnect's page re-delivers all three and one more. */
    private fun assertRedeliveredRowDrawsOnce(row: ThreadItem) =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1"), row, message("m2")))
            val emissions = mutableListOf<List<ThreadItem>>()
            val job =
                launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { emissions += it } }

            live.value = listOf(message("m1"), row, message("m2"), message("m3"))

            val drawn = listOf(message("m1"), row, message("m2"), message("m3"))
            assertEquals(drawn, emissions.last())
            assertEquals(drawn, cache.writes.last())
            job.cancel()
        }

    @Test
    fun `an in-flight turn writes nothing until it settles`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1")))
            val job = launch { CachingConversationRepository(delegate, cache, "server-a").observeMessages("conv-1").collect { } }

            live.value = listOf(message("m1"), message("t1", isStreaming = true, content = "Hel"))
            live.value =
                listOf(message("m1"), message("t1", isStreaming = true, content = "Hello"), tool("toolu_1", ToolCallStatus.Running))
            assertEquals(emptyList<List<ThreadItem>>(), cache.writes)

            live.value = listOf(message("m1"), message("t1", content = "Hello"), tool("toolu_1", ToolCallStatus.Done))
            live.value = listOf(message("m1"), message("t1", content = "Hello"), tool("toolu_1", ToolCallStatus.Done))

            assertEquals(
                listOf(listOf(message("m1"), message("t1", content = "Hello"), tool("toolu_1", ToolCallStatus.Done))),
                cache.writes,
            )
            job.cancel()
        }

    @Test
    fun `a failed read draws the live rows only`() =
        runTest(UnconfinedTestDispatcher()) {
            live.value = listOf(message("m1"))

            val drawn = CachingConversationRepository(delegate, RecordingCache(emptyList()), "server-a").observeMessages("conv-1").first()

            assertEquals(listOf(message("m1")), drawn)
        }

    @Test
    fun `a failed write is retried on the next change and logs a static event only`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(emptyList(), failWrites = true)
            val job = launch { CachingConversationRepository(delegate, cache, "server-SECRET").observeMessages("conv-SECRET").collect { } }

            live.value = listOf(message("m1"))
            cache.failWrites = false
            live.value = listOf(message("m1"), message("m2"))

            assertEquals(2, cache.writes.size)
            assertTrue(logs.any { it.contains("event=thread_cache_write_failed") })
            logs.forEach { assertTrue("log leaked an identifier or row: $it", !it.contains("SECRET") && !it.contains("content of")) }
            job.cancel()
        }

    @Test
    fun `every other flow is the live one untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(listOf(message("m1")))
            val repository = CachingConversationRepository(delegate, cache, "server-a")

            assertEquals(true, repository.observeStall("conv-1").first())
            stall.value = false
            assertEquals(false, repository.observeStall("conv-1").first())
            assertEquals(0, cache.reads)
        }

    // #899: a retrieval is kept under this wrapper's own host, fetched through the delegate's connection.
    @Test
    fun `a retrieved attachment is fetched through the delegate and kept for this host`() =
        runTest(UnconfinedTestDispatcher()) {
            val fetches = mutableListOf<Pair<String, String>>()
            val fetching =
                object : ConversationRepository by delegate {
                    override suspend fun fetchAttachment(
                        conversationId: String,
                        attachmentId: String,
                    ): AttachmentFetchResult {
                        fetches += conversationId to attachmentId
                        return AttachmentFetchResult.Fetched(AttachmentContent(listOf(byteArrayOf(1, 2, 3))), "a.txt", "text/plain")
                    }
                }
            val store = AttachmentStore(tmp.newFolder("attachments"), UnconfinedTestDispatcher(testScheduler))
            val repository = CachingConversationRepository(fetching, RecordingCache(emptyList()), "server-a", store)

            val first = repository.retrieveAttachment(CONVERSATION_ID, ATTACHMENT_ID) as AttachmentRetrievalResult.Retrieved
            val again =
                CachingConversationRepository(
                    fetching,
                    RecordingCache(emptyList()),
                    "server-a",
                    store,
                ).retrieveAttachment(CONVERSATION_ID, ATTACHMENT_ID)
            CachingConversationRepository(
                fetching,
                RecordingCache(emptyList()),
                "server-b",
                store,
            ).retrieveAttachment(CONVERSATION_ID, ATTACHMENT_ID)

            assertEquals(listOf(1, 2, 3), first.file.readBytes().map { it.toInt() })
            assertEquals(first, again)
            assertEquals("one fetch per host", listOf(CONVERSATION_ID to ATTACHMENT_ID, CONVERSATION_ID to ATTACHMENT_ID), fetches)
        }

    @Test
    fun `a permanent delete removes that conversation's cached content and nothing else`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }

            CachingConversationRepository(delegate, cache, "server-a").delete("conv-1")

            assertEquals(listOf("conv-2"), cache.readConversations("server-a").map { it.id })
            assertEquals(emptyList<ThreadItem>(), cache.readThread("server-a", "conv-1"))
            assertEquals(listOf(message("m2")), cache.readThread("server-a", "conv-2"))
            // Conversation ids are host-local: the other host's conversation of the same id is untouched.
            assertEquals(listOf("conv-1"), cache.readConversations("server-b").map { it.id })
            assertEquals(listOf(message("b1")), cache.readThread("server-b", "conv-1"))
        }

    @Test
    fun `a delete the daemon refuses leaves the cached content and propagates`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            failDelete = true

            val result = runCatching { CachingConversationRepository(delegate, cache, "server-a").delete("conv-1") }

            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(listOf("conv-1", "conv-2"), cache.readConversations("server-a").map { it.id })
            assertEquals(listOf(message("m1")), cache.readThread("server-a", "conv-1"))
        }

    @Test
    fun `archiving and restoring leave the cached content readable`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            val repository = CachingConversationRepository(delegate, cache, "server-a")

            repository.archive("conv-1")
            repository.unarchive("conv-1")

            assertEquals(listOf("archive conv-1", "unarchive conv-1"), archiveCalls)
            assertEquals(listOf("conv-1", "conv-2"), cache.readConversations("server-a").map { it.id })
            assertEquals(listOf(message("m1")), cache.readThread("server-a", "conv-1"))
        }

    @Test
    fun `the thread that issued the delete never writes its rows back`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            val repository = CachingConversationRepository(delegate, cache, "server-a")
            val job = launch { repository.observeMessages("conv-1").collect { } }
            live.value = listOf(message("m1"), message("m3"))
            assertEquals(listOf(message("m1"), message("m3")), cache.readThread("server-a", "conv-1"))

            repository.delete("conv-1")
            // A late row between the delete and the screen's PopBack must not resurrect the document.
            live.value = listOf(message("m1"), message("m3"), message("m4"))

            assertEquals(emptyList<ThreadItem>(), cache.readThread("server-a", "conv-1"))
            job.cancel()
        }

    @Test
    fun `a failed cache removal still completes the delete and logs a static event only`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(emptyList(), failRemovals = true)

            CachingConversationRepository(delegate, cache, "server-SECRET").delete("conv-SECRET")

            assertEquals(listOf("server-SECRET" to "conv-SECRET"), cache.removals)
            assertTrue(logs.any { it.contains("event=conversation_cache_remove_failed") })
            logs.forEach { assertTrue("log leaked an identifier: $it", !it.contains("SECRET")) }
        }

    @Test
    fun `a saved history position survives the row writer and reads back under this host`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            val repository = CachingConversationRepository(delegate, cache, "server-a")
            val job = launch { repository.observeMessages("conv-1").collect { } }
            val position = HistoryPosition(cursor = "opaque-cursor", atStart = false)

            repository.writeHistoryPosition("conv-1", position)
            // A row write after the position write keeps it.
            live.value = listOf(message("m0"), message("m1"))
            assertEquals(listOf(message("m0"), message("m1")), cache.readThread("server-a", "conv-1"))

            assertEquals(position, repository.readHistoryPosition("conv-1"))
            assertEquals(position, CachingConversationRepository(delegate, cache, "server-a").readHistoryPosition("conv-1"))
            assertEquals(null, CachingConversationRepository(delegate, cache, "server-b").readHistoryPosition("conv-1"))

            repository.writeHistoryPosition("conv-1", null)
            assertEquals(null, repository.readHistoryPosition("conv-1"))
            assertEquals(listOf(message("m0"), message("m1")), cache.readThread("server-a", "conv-1"))
            job.cancel()
        }

    @Test
    fun `a drawn thread trimmed at the row limit drops the saved history position`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            val repository = CachingConversationRepository(delegate, cache, "server-a")
            val job = launch { repository.observeMessages("conv-1").collect { } }
            val position = HistoryPosition(cursor = "opaque-cursor", atStart = false)
            repository.writeHistoryPosition("conv-1", position)
            val atLimit = (1..MAX_CACHED_THREAD_ROWS).map { message("m$it") }

            live.value = atLimit
            // Exactly at the limit nothing was trimmed, so the oldest saved row still matches the position.
            assertEquals(position, repository.readHistoryPosition("conv-1"))

            live.value = atLimit + message("newest")
            assertEquals(MAX_CACHED_THREAD_ROWS, cache.readThread("server-a", "conv-1").size)
            assertEquals(null, repository.readHistoryPosition("conv-1"))
            job.cancel()
        }

    @Test
    fun `a deleted conversation's history position is never written back`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = fileCache().also { it.seed() }
            val repository = CachingConversationRepository(delegate, cache, "server-a")

            repository.delete("conv-1")
            repository.writeHistoryPosition("conv-1", HistoryPosition(cursor = "c1", atStart = false))

            assertEquals(null, cache.readHistoryPosition("server-a", "conv-1"))
            assertEquals(emptyList<ThreadItem>(), cache.readThread("server-a", "conv-1"))
        }

    private fun TestScope.fileCache() = FileConversationCache(tmp.newFolder(), UnconfinedTestDispatcher(testScheduler))

    /** Two conversations under one host, and a conversation of the same id under another. */
    private suspend fun ConversationCache.seed() {
        writeConversations("server-a", listOf(conversation("conv-1"), conversation("conv-2")))
        writeThread("server-a", "conv-1", listOf(message("m1")))
        writeThread("server-a", "conv-2", listOf(message("m2")))
        writeConversations("server-b", listOf(conversation("conv-1")))
        writeThread("server-b", "conv-1", listOf(message("b1")))
    }

    private fun conversation(id: String) =
        Conversation(
            id = id,
            name = "Channel $id",
            cwd = "/home/pyry/projects/$id",
            currentSessionId = "session-$id",
            sessionHistory = listOf("session-$id"),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-09-22T10:00:00Z"),
        )

    /** One conversation's thread, seeded; records every write it is handed. */
    private class RecordingCache(
        private val seed: List<ThreadItem>,
        var failWrites: Boolean = false,
        var failRemovals: Boolean = false,
    ) : ConversationCache {
        val writes = mutableListOf<List<ThreadItem>>()
        val removals = mutableListOf<Pair<String, String>>()
        var reads = 0

        override suspend fun readThread(
            serverId: String,
            conversationId: String,
        ): List<ThreadItem> {
            reads++
            return seed
        }

        override suspend fun writeThread(
            serverId: String,
            conversationId: String,
            rows: List<ThreadItem>,
        ): Result<Unit> {
            // What the cache keeps, per writeThread's contract: the repository hands it the drawn rows (#1354).
            writes += cacheableThreadRows(rows)
            return if (failWrites) {
                Result.failure(
                    ConversationCacheException("conversation cache write_thread failed: io"),
                )
            } else {
                Result.success(Unit)
            }
        }

        override suspend fun readConversations(serverId: String) = emptyList<Conversation>()

        override suspend fun writeConversations(
            serverId: String,
            conversations: List<Conversation>,
        ) = Result.success(Unit)

        override suspend fun removeHost(serverId: String) = Result.success(Unit)

        override suspend fun removeConversation(
            serverId: String,
            conversationId: String,
        ): Result<Unit> {
            removals += serverId to conversationId
            return if (failRemovals) {
                Result.failure(ConversationCacheException("conversation cache remove_conversation failed: io"))
            } else {
                Result.success(Unit)
            }
        }
    }
}

private const val CONVERSATION_ID = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
private const val ATTACHMENT_ID = "7c1d5e92-4a30-4b8f-9e21-6d4c3b0a8f55"

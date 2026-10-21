package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheException
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.cache.cacheableThreadRows
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    private val offer =
        ThreadItem.MessageItem(
            de.pyryco.mobile.data.model.Message(
                "offered",
                "",
                de.pyryco.mobile.data.model.Role.Assistant,
                "",
                Instant.parse("2026-09-22T10:00:01Z"),
                false,
                attachments =
                    listOf(
                        de.pyryco.mobile.data.model
                            .MessageAttachment(ATTACHMENT_ID, "report.pdf"),
                    ),
            ),
        )

    private val banner = ThreadItem.Banner(BannerLevel.Warning, "hook said no", false, Instant.parse("2026-09-22T10:00:01Z"))
    private val compaction = ThreadItem.CompactionBoundary(24000, 3000, true, Instant.parse("2026-09-22T10:00:02Z"))
    private val refusal =
        ThreadItem.ModelRefusal("claude-opus-5-5", "claude-sonnet-5", "refused", false, Instant.parse("2026-09-22T10:00:03Z"))

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
    fun `a failed cache removal still completes the delete and logs a static event only`() =
        runTest(UnconfinedTestDispatcher()) {
            val cache = RecordingCache(emptyList(), failRemovals = true)

            CachingConversationRepository(delegate, cache, "server-SECRET").delete("conv-SECRET")

            assertEquals(listOf("server-SECRET" to "conv-SECRET"), cache.removals)
            assertTrue(logs.any { it.contains("event=conversation_cache_remove_failed") })
            logs.forEach { assertTrue("log leaked an identifier: $it", !it.contains("SECRET")) }
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

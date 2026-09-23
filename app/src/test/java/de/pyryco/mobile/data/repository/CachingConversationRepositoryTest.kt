package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheException
import de.pyryco.mobile.data.cache.FileConversationCache
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
 * The thread restore (#797): cached rows merged under the live projection through [mergeHistoryRows],
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
            writes += rows
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

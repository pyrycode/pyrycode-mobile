package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.ConversationCacheException
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The thread restore (#797): cached rows merged under the live projection through [mergeHistoryRows],
 * settled rows written back only when they change, every other flow pure delegation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachingConversationRepositoryTest {
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

    /** The live side: a fake for everything, with the thread and stall projections under test control. */
    private val delegate =
        object : ConversationRepository by FakeConversationRepository() {
            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = live

            override fun observeStall(conversationId: String): Flow<Boolean> = stall
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

    /** One conversation's thread, seeded; records every write it is handed. */
    private class RecordingCache(
        private val seed: List<ThreadItem>,
        var failWrites: Boolean = false,
    ) : ConversationCache {
        val writes = mutableListOf<List<ThreadItem>>()
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
        ) = Result.success(Unit)
    }
}

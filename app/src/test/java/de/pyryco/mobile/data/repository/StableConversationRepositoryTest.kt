package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the #352 stable facade: a single long-lived [ConversationRepository] that
 * delegates to whichever connection-scoped repository is currently published on the injected
 * `currentRepository: StateFlow<ConversationRepository?>` (#351's seam), switching transparently on
 * connection churn and exposing a defined non-crashing surface while no connection is live. JUnit4 +
 * `runTest` driven with `runCurrent()` (per repo memory: `runCurrent()`, not `advanceUntilIdle()`,
 * for these StateFlow projections), hand fakes (no MockK). Simpler than the coordinator test — it
 * drives a `MutableStateFlow<ConversationRepository?>` directly, with no coordinator and no pump.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StableConversationRepositoryTest {
    // ---- AC #1: one stable reference serves every connection across churn ------------------------

    @Test
    fun stableReference_servesEveryConnectionAcrossChurn() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)
            val ref = facade

            // The facade object is fixed for the test's life; null -> repoA -> null -> repoB never
            // replaces it, yet the live one is the one that records each one-shot.
            current.value = repoA
            facade.sendMessage("c1", "to-A")
            current.value = null
            current.value = repoB
            facade.sendMessage("c2", "to-B")

            assertSame("the facade reference never changes across connection churn", ref, facade)
            assertEquals(listOf("c1" to "to-A"), repoA.sendMessageCalls)
            assertEquals(listOf("c2" to "to-B"), repoB.sendMessageCalls)
        }

    // ---- AC #2 / #3: cold reads emit a defined empty projection while no connection is live ------

    @Test
    fun coldReads_whileAbsent_emitDefinedEmptyProjection() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val conversations = mutableListOf<List<Conversation>>()
            val messages = mutableListOf<List<ThreadItem>>()
            val lastMessages = mutableListOf<Message?>()
            val workspaces = mutableListOf<List<String>>()
            backgroundScope.launch { facade.observeConversations(ConversationFilter.All).collect { conversations += it } }
            backgroundScope.launch { facade.observeMessages("c1").collect { messages += it } }
            backgroundScope.launch { facade.observeLastMessage("c1").collect { lastMessages += it } }
            backgroundScope.launch { facade.recentWorkspaces().collect { workspaces += it } }
            runCurrent()

            assertEquals(listOf(emptyList<Conversation>()), conversations)
            assertEquals(listOf(emptyList<ThreadItem>()), messages)
            assertEquals(listOf<Message?>(null), lastMessages)
            assertEquals(listOf(emptyList<String>()), workspaces)
        }

    // ---- AC #2 / #3: a cold read resumes the moment a connection arrives -------------------------

    @Test
    fun coldRead_resumesWhenConnectionArrives() =
        runTest {
            val repoA = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { facade.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()
            assertEquals("the empty projection is emitted while absent", listOf(emptyList<Conversation>()), emissions)

            current.value = repoA
            runCurrent()
            // The live repo has not emitted yet (cold; its projection is empty until a snapshot lands),
            // so the facade shows only the empty projection so far.
            assertEquals(listOf(emptyList<Conversation>()), emissions)

            val a = conversation("a")
            repoA.pushConversations(listOf(a))
            runCurrent()
            assertEquals(listOf(emptyList(), listOf(a)), emissions)
        }

    // ---- AC #2: the switch is transparent — the live repo's data replaces the prior one's --------

    @Test
    fun coldRead_switchesTransparentlyBetweenConnections() =
        runTest {
            val a = conversation("a")
            val b = conversation("b")
            val repoA = RecordingConversationRepository().apply { pushConversations(listOf(a)) }
            val repoB = RecordingConversationRepository().apply { pushConversations(listOf(b)) }
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { facade.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()
            assertEquals(listOf(listOf(a)), emissions)

            current.value = repoB
            runCurrent()
            assertEquals(listOf(listOf(a), listOf(b)), emissions)
        }

    // ---- AC #2: cross-connection isolation — a reader never re-observes a prior connection's data

    @Test
    fun coldRead_afterReconnect_neverObservesPreviousConnectionData() =
        runTest {
            val a = conversation("a")
            val b = conversation("b")
            val repoA = RecordingConversationRepository().apply { pushConversations(listOf(a)) }
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { facade.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()
            assertEquals(listOf(listOf(a)), emissions)

            // Churn: null clears to the empty projection; repoB has not emitted yet.
            current.value = null
            runCurrent()
            current.value = repoB
            runCurrent()

            // A frame pushed to the now-cancelled repoA must surface nowhere (its inner flow was
            // cancelled by the flatMapLatest switch).
            repoA.pushConversations(listOf(a, conversation("a2")))
            runCurrent()

            repoB.pushConversations(listOf(b))
            runCurrent()

            // repoA's [a] appears exactly once, before the switch; it never reappears after, and the
            // ghost push to the dead repoA leaked nothing.
            assertEquals(listOf(listOf(a), emptyList(), listOf(b)), emissions)
        }

    // ---- AC #3: one-shots surface the defined not-connected outcome while absent -----------------

    @Test
    fun oneShots_whileAbsent_throwIllegalState() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            assertTrue(runCatching { facade.createDiscussion("/ws") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.sendMessage("c1", "hi") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.promote("c1", "Name", null) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.requestScreenSnapshot("c1") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.dropQueuedMessage("c1", 42L) }.exceptionOrNull() is IllegalStateException)
        }

    // ---- AC #4: one-shots delegate verbatim to the live repo (args + return value) ---------------

    @Test
    fun oneShots_whenLive_delegateWithExactArgsAndReturn() =
        runTest {
            val repoA = RecordingConversationRepository()
            val created = conversation("created")
            val sent = message("m1")
            repoA.createDiscussionResult = created
            repoA.sendMessageResult = sent
            repoA.requestScreenSnapshotResult = "screen!"
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val createResult = facade.createDiscussion("/ws")
            val sendResult = facade.sendMessage("c1", "hi")
            val snapshotResult = facade.requestScreenSnapshot("c9")
            facade.dropQueuedMessage("c7", 99L)

            assertEquals(listOf<String?>("/ws"), repoA.createDiscussionCalls)
            assertEquals(listOf("c1" to "hi"), repoA.sendMessageCalls)
            assertEquals(listOf("c9"), repoA.requestScreenSnapshotCalls)
            assertEquals(listOf("c7" to 99L), repoA.dropQueuedMessageCalls)
            assertSame(created, createResult)
            assertSame(sent, sendResult)
            assertEquals("screen!", snapshotResult)
        }

    // ---- AC #4: a throwing stub on the live repo passes through unchanged -------------------------

    @Test
    fun oneShot_stubOnLiveRepo_passesThroughUnchanged() =
        runTest {
            val repoA = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            // archive is a throwing stub on the live repo (mirrors RemoteConversationRepository); the
            // facade must rethrow the same type, neither translating nor suppressing it.
            assertTrue(runCatching { facade.archive("c1") }.exceptionOrNull() is UnsupportedOperationException)
        }

    // ---- #395: observeStall delegates and tracks connection churn --------------------------------

    @Test
    fun observeStall_whileAbsent_emitsFalse() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val stalls = mutableListOf<Boolean>()
            backgroundScope.launch { facade.observeStall("c1").collect { stalls += it } }
            runCurrent()

            assertEquals(listOf(false), stalls)
        }

    @Test
    fun observeStall_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val stalls = mutableListOf<Boolean>()
            backgroundScope.launch { facade.observeStall("c1").collect { stalls += it } }
            runCurrent()
            assertEquals(listOf(false), stalls)

            repoA.pushStall(true)
            runCurrent()
            assertEquals(listOf(false, true), stalls)

            // Switching to a fresh (not-stalled) connection drops the prior connection's stall.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(false, true, false), stalls)
        }

    // ---- #460: observeQueue delegates and tracks connection churn --------------------------------

    @Test
    fun observeQueue_whileAbsent_emitsEmpty() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val queues = mutableListOf<List<QueuedMessage>>()
            backgroundScope.launch { facade.observeQueue("c1").collect { queues += it } }
            runCurrent()

            assertEquals(listOf(emptyList<QueuedMessage>()), queues)
        }

    @Test
    fun observeQueue_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val queues = mutableListOf<List<QueuedMessage>>()
            backgroundScope.launch { facade.observeQueue("c1").collect { queues += it } }
            runCurrent()
            assertEquals(listOf(emptyList<QueuedMessage>()), queues)

            val backlog = listOf(QueuedMessage(1L, "a", Instant.parse("2026-05-31T00:00:01Z")))
            repoA.pushQueue(backlog)
            runCurrent()
            assertEquals(listOf(emptyList(), backlog), queues)

            // Switching to a fresh (empty-backlog) connection drops the prior connection's queue.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(emptyList(), backlog, emptyList()), queues)
        }

    // ---- #593: observeApiRetry delegates and tracks connection churn -----------------------------

    @Test
    fun observeApiRetry_whileAbsent_emitsNotRetrying() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val retries = mutableListOf<ApiRetryStatus>()
            backgroundScope.launch { facade.observeApiRetry("c1").collect { retries += it } }
            runCurrent()

            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)
        }

    @Test
    fun observeApiRetry_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val retries = mutableListOf<ApiRetryStatus>()
            backgroundScope.launch { facade.observeApiRetry("c1").collect { retries += it } }
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying), retries)

            repoA.pushApiRetry(ApiRetryStatus.Attempt(3, 10))
            runCurrent()
            assertEquals(listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10)), retries)

            // Switching to a fresh (not-retrying) connection drops the prior connection's retry state.
            current.value = repoB
            runCurrent()
            assertEquals(
                listOf(ApiRetryStatus.NotRetrying, ApiRetryStatus.Attempt(3, 10), ApiRetryStatus.NotRetrying),
                retries,
            )
        }

    // ---- #507: mutationsSupported capability — delegates to the live value, fail-safe-deny false --

    @Test
    fun mutationsSupported_whileAbsent_isFalse() {
        val current = MutableStateFlow<ConversationRepository?>(null)
        val facade = StableConversationRepository(current)

        assertFalse("no connection live → fail-safe-deny false", facade.mutationsSupported)
    }

    @Test
    fun mutationsSupported_delegatesToLiveValue_andReReadsAcrossChurn() {
        // A live repo that inherits the interface default (true), and one that overrides to false
        // (the relay's shape). The getter must reflect whichever is currently published.
        val supporting = RecordingConversationRepository()
        val notSupporting =
            object : ConversationRepository by RecordingConversationRepository() {
                override val mutationsSupported: Boolean = false
            }
        val current = MutableStateFlow<ConversationRepository?>(null)
        val facade = StableConversationRepository(current)

        assertFalse("absent → false", facade.mutationsSupported)

        current.value = supporting
        assertTrue("delegates to a mutation-supporting live repo", facade.mutationsSupported)

        current.value = notSupporting
        assertFalse("delegates to a non-supporting live repo (relay shape)", facade.mutationsSupported)

        current.value = null
        assertFalse("getter re-reads .value live → back to false when the connection drops", facade.mutationsSupported)
    }

    // ---- fakes / builders ------------------------------------------------------------------------

    /**
     * Hand fake standing in for one connection-scoped repository. Backs [observeConversations] with a
     * `null`-until-pushed [MutableStateFlow] (mirroring the real repo's cold `projection.filterNotNull()`,
     * so a fresh connection emits nothing until its first snapshot) and records each one-shot call. The
     * out-of-scope stubs throw [UnsupportedOperationException], mirroring [RemoteConversationRepository].
     */
    private class RecordingConversationRepository : ConversationRepository {
        private val conversations = MutableStateFlow<List<Conversation>?>(null)
        private val stalled = MutableStateFlow(false)
        private val queued = MutableStateFlow<List<QueuedMessage>>(emptyList())
        private val apiRetry = MutableStateFlow<ApiRetryStatus>(ApiRetryStatus.NotRetrying)

        val createDiscussionCalls = mutableListOf<String?>()
        val sendMessageCalls = mutableListOf<Pair<String, String>>()
        val requestScreenSnapshotCalls = mutableListOf<String>()
        val dropQueuedMessageCalls = mutableListOf<Pair<String, Long>>()

        var createDiscussionResult: Conversation = conversation("created")
        var sendMessageResult: Message = message("sent")
        var requestScreenSnapshotResult: String = "snapshot-text"

        fun pushConversations(value: List<Conversation>) {
            conversations.value = value
        }

        fun pushStall(value: Boolean) {
            stalled.value = value
        }

        fun pushQueue(value: List<QueuedMessage>) {
            queued.value = value
        }

        fun pushApiRetry(value: ApiRetryStatus) {
            apiRetry.value = value
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = conversations.filterNotNull()

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

        override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

        override fun observeStall(conversationId: String): Flow<Boolean> = stalled

        override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = queued

        override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = apiRetry

        override suspend fun createDiscussion(workspace: String?): Conversation {
            createDiscussionCalls += workspace
            return createDiscussionResult
        }

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation = conversation(conversationId)

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sendMessageCalls += (conversationId to text)
            return sendMessageResult
        }

        override suspend fun requestScreenSnapshot(conversationId: String): String {
            requestScreenSnapshotCalls += conversationId
            return requestScreenSnapshotResult
        }

        override suspend fun dropQueuedMessage(
            conversationId: String,
            queuedMessageId: Long,
        ) {
            dropQueuedMessageCalls += (conversationId to queuedMessageId)
        }

        override suspend fun archive(conversationId: String): Unit = throw UnsupportedOperationException("archive stub")

        override suspend fun unarchive(conversationId: String): Unit = throw UnsupportedOperationException("unarchive stub")

        override suspend fun rename(
            conversationId: String,
            name: String,
        ): Conversation = throw UnsupportedOperationException("rename stub")

        override suspend fun startNewSession(
            conversationId: String,
            workspace: String?,
        ): Session = throw UnsupportedOperationException("startNewSession stub")

        override suspend fun changeWorkspace(
            conversationId: String,
            workspace: String,
        ): Session = throw UnsupportedOperationException("changeWorkspace stub")
    }

    private companion object {
        fun conversation(id: String): Conversation =
            Conversation(
                id = id,
                name = id,
                cwd = "/$id",
                currentSessionId = "session-$id",
                sessionHistory = emptyList(),
                isPromoted = false,
                lastUsedAt = Instant.parse("2026-06-06T00:00:00Z"),
            )

        fun message(id: String): Message =
            Message(
                id = id,
                sessionId = "",
                role = Role.User,
                content = "content-$id",
                timestamp = Instant.parse("2026-06-06T00:00:00Z"),
                isStreaming = false,
            )
    }
}

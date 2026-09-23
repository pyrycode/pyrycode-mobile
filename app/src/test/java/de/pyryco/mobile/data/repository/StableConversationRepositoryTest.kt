package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import kotlinx.coroutines.CompletableDeferred
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
            assertTrue(runCatching { facade.requestHistory("c1") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.requestSystemPrompt("c1") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.setSystemPrompt("c1", "x") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.renameWorkspace("/w", "x") }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { facade.archiveWorkspace("/w") }.exceptionOrNull() is IllegalStateException)
        }

    // #663: the workspace verbs reach this host's live repo with the path and label untouched — the
    // facade trims nothing, and a null label is the clear, not an omission.
    @Test
    fun workspaceVerbs_whenLive_delegateVerbatim() =
        runTest {
            val repoA = RecordingConversationRepository()
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(repoA))

            facade.renameWorkspace("/w/alpha ", " Tax ")
            facade.renameWorkspace("/w/alpha", null)
            facade.archiveWorkspace("/w/alpha/")

            assertEquals(listOf<Pair<String, String?>>("/w/alpha " to " Tax ", "/w/alpha" to null), repoA.renameWorkspaceCalls)
            assertEquals(listOf("/w/alpha/"), repoA.archiveWorkspaceCalls)
        }

    // #823: both system-prompt calls reach the live repo with the id and value untouched — null, "" and
    // text are three different writes, so the facade must not coalesce them.
    @Test
    fun systemPrompt_whenLive_delegatesVerbatim() =
        runTest {
            val repoA = RecordingConversationRepository()
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(repoA))

            val reading = facade.requestSystemPrompt("c3")
            facade.setSystemPrompt("c3", null)
            facade.setSystemPrompt("c3", "")
            facade.setSystemPrompt("c3", "text")

            assertSame(repoA.requestSystemPromptResult, reading)
            assertEquals(listOf("c3"), repoA.requestSystemPromptCalls)
            assertEquals(listOf<Pair<String, String?>>("c3" to null, "c3" to "", "c3" to "text"), repoA.setSystemPromptCalls)
        }

    // #829: an upload with no live connection is a result, never a throw — and an oversized file says
    // not to retry even then, since reconnecting would not help.
    // #830: the attachment-bearing send reaches the live host's repository with its ids untouched.
    @Test
    fun sendMessageWithAttachments_delegatesToTheLiveRepositoryOrThrowsWhileAbsent() =
        runTest {
            val repoA = RecordingConversationRepository()
            val sent = message("m-att")
            repoA.sendMessageResult = sent
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            assertTrue(runCatching { facade.sendMessage("c1", "hi", listOf("a")) }.exceptionOrNull() is IllegalStateException)

            current.value = repoA
            assertSame(sent, facade.sendMessage("c1", "hi", listOf("b", "a", "b")))
            assertEquals(listOf(Triple("c1", "hi", listOf("b", "a", "b"))), repoA.sendWithAttachmentsCalls)
            assertTrue("the two-argument send was not used", repoA.sendMessageCalls.isEmpty())
        }

    @Test
    fun uploadAttachment_whileAbsent_isReconnectRequiredOrTooLarge() =
        runTest {
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(null))

            assertEquals(AttachmentUploadResult.ReconnectRequired, facade.uploadAttachment("c1", ByteArray(1), "a", "b"))
            assertEquals(
                AttachmentUploadResult.TooLarge,
                facade.uploadAttachment("c1", ByteArray(AttachmentUploadLimit.MAX_BYTES + 1), "a", "b"),
            )
        }

    // #829: the upload runs on the connection live at entry; a later change of connection does not move it.
    @Test
    fun uploadAttachment_staysOnTheRepositoryLiveAtEntry() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            var result: AttachmentUploadResult? = null
            launch { result = facade.uploadAttachment("c1", ByteArray(3), "notes.txt", "text/plain") }
            runCurrent()
            current.value = repoB
            repoA.uploadResult.complete(AttachmentUploadResult.Stored("id-a"))
            runCurrent()

            assertEquals(AttachmentUploadResult.Stored("id-a"), result)
            assertEquals(listOf("c1"), repoA.uploadCalls)
            assertTrue(repoB.uploadCalls.isEmpty())
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
            // #623: the cursor and limit must arrive at the live repo untouched — an opaque cursor the
            // facade re-encoded or a limit it "normalized" would break the walk one layer down.
            val historyResult = facade.requestHistory("c8", cursor = "OPAQUE==", limit = 25)

            assertEquals(listOf<String?>("/ws"), repoA.createDiscussionCalls)
            assertEquals(listOf("c1" to "hi"), repoA.sendMessageCalls)
            assertEquals(listOf("c9"), repoA.requestScreenSnapshotCalls)
            assertEquals(listOf("c7" to 99L), repoA.dropQueuedMessageCalls)
            assertEquals(listOf(Triple("c8", "OPAQUE==", 25)), repoA.requestHistoryCalls)
            assertSame(created, createResult)
            assertSame(sent, sendResult)
            assertEquals("screen!", snapshotResult)
            assertSame(repoA.requestHistoryResult, historyResult)
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

    // ---- #596: observeCompacting delegates and tracks connection churn ---------------------------

    @Test
    fun observeCompacting_whileAbsent_emitsFalse() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val compacting = mutableListOf<Boolean>()
            backgroundScope.launch { facade.observeCompacting("c1").collect { compacting += it } }
            runCurrent()

            assertEquals(listOf(false), compacting)
        }

    @Test
    fun observeCompacting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val compacting = mutableListOf<Boolean>()
            backgroundScope.launch { facade.observeCompacting("c1").collect { compacting += it } }
            runCurrent()
            assertEquals(listOf(false), compacting)

            repoA.pushCompacting(true)
            runCurrent()
            assertEquals(listOf(false, true), compacting)

            // Switching to a fresh (not-compacting) connection drops the prior connection's state.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(false, true, false), compacting)
        }

    // ---- #871: observeResetting delegates and tracks connection churn ----------------------------

    @Test
    fun observeResetting_whileAbsent_emitsNull() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<ResetStatus?>()
            backgroundScope.launch { facade.observeResetting("c1").collect { readings += it } }
            runCurrent()

            assertEquals(listOf<ResetStatus?>(null), readings)
        }

    @Test
    fun observeResetting_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<ResetStatus?>()
            backgroundScope.launch { facade.observeResetting("c1").collect { readings += it } }
            runCurrent()
            assertEquals(listOf<ResetStatus?>(null), readings)

            val wrappingUp = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
            repoA.pushResetting(wrappingUp)
            runCurrent()
            assertEquals(listOf(null, wrappingUp), readings)

            // Switching to a fresh (not-resetting) connection drops the prior connection's reading.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(null, wrappingUp, null), readings)
        }

    // ---- #802: observeUsageLimit delegates and tracks connection churn ---------------------------

    @Test
    fun observeUsageLimit_whileAbsent_emitsNull() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<UsageLimitReading?>()
            backgroundScope.launch { facade.observeUsageLimit("c1").collect { readings += it } }
            runCurrent()

            assertEquals(listOf<UsageLimitReading?>(null), readings)
        }

    // The switch is the ACCOUNT-isolation mechanism here, not just plumbing: a usage-limit window
    // belongs to an account, so one connection's quota posture must never be attributed to the next.
    @Test
    fun observeUsageLimit_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<UsageLimitReading?>()
            backgroundScope.launch { facade.observeUsageLimit("c1").collect { readings += it } }
            runCurrent()
            assertEquals(listOf<UsageLimitReading?>(null), readings)

            val reading = UsageLimitReading("allowed_warning", "seven_day", 1_780_189_200L, 0.94, null)
            repoA.pushUsageLimit(reading)
            runCurrent()
            assertEquals(listOf(null, reading), readings)

            // Switching to a fresh connection drops the prior connection's reading.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(null, reading, null), readings)
        }

    // ---- #801: observeThinkingProgress delegates and tracks connection churn ---------------------

    @Test
    fun observeThinkingProgress_whileAbsent_emitsNull() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<ThinkingProgress?>()
            backgroundScope.launch { facade.observeThinkingProgress("c1").collect { readings += it } }
            runCurrent()

            assertEquals(listOf<ThinkingProgress?>(null), readings)
        }

    // The slice's reconnect proof: a reading is NOT held across a connection switch. The daemon
    // re-asserts no `thinking_progress` on connect, so a held one would report the depth of a think
    // that has since finished — and the clear is structural (a fresh connection-scoped repository plus
    // this flatMapLatest), not a clear written into any demux arm.
    @Test
    fun observeThinkingProgress_delegatesToLiveRepo_andDoesNotSurviveAReconnect() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<ThinkingProgress?>()
            backgroundScope.launch { facade.observeThinkingProgress("c1").collect { readings += it } }
            runCurrent()
            assertEquals(listOf<ThinkingProgress?>(null), readings)

            repoA.pushThinkingProgress(ThinkingProgress(184, 12))
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(184, 12)), readings)

            // Switching to a fresh connection drops the prior connection's reading back to unavailable.
            current.value = repoB
            runCurrent()
            assertEquals(listOf(null, ThinkingProgress(184, 12), null), readings)
        }

    // ---- #590: observeSessionSettings delegates, and a host switch resets the reading ------------

    @Test
    fun observeSessionSettings_whileAbsent_emitsNull() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<SessionSettings?>()
            backgroundScope.launch { facade.observeSessionSettings("c1").collect { readings += it } }
            runCurrent()

            assertEquals(listOf<SessionSettings?>(null), readings)
        }

    // AC #1: a reading cannot survive a host switch — the new connection's repository answers, and the
    // previous host's values are dropped rather than left standing.
    @Test
    fun observeSessionSettings_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val readings = mutableListOf<SessionSettings?>()
            backgroundScope.launch { facade.observeSessionSettings("c1").collect { readings += it } }
            runCurrent()

            repoA.pushSessionSettings(READING)
            runCurrent()
            assertEquals(READING, readings.last())

            current.value = repoB
            runCurrent()
            assertEquals(listOf(null, READING, null), readings)
        }

    // ---- #791: observeModelMenu delegates, and a host switch drops the previous host's menu -------

    // A facade with no live repository reads UNAVAILABLE, never the device enum.
    @Test
    fun observeModelMenu_whileAbsent_emitsNull() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val facade = StableConversationRepository(current)

            val menus = mutableListOf<ModelMenu?>()
            backgroundScope.launch { facade.observeModelMenu("c1").collect { menus += it } }
            runCurrent()

            assertEquals(listOf<ModelMenu?>(null), menus)
        }

    // The menu is per HOST: each connection has its own repository, so a host switch drops the old
    // host's vocabulary back to unavailable rather than leaving another machine's rows standing.
    @Test
    fun observeModelMenu_delegatesToLiveRepo_andDoesNotLeakAcrossSwitch() =
        runTest {
            val repoA = RecordingConversationRepository()
            val repoB = RecordingConversationRepository()
            val current = MutableStateFlow<ConversationRepository?>(repoA)
            val facade = StableConversationRepository(current)

            val menus = mutableListOf<ModelMenu?>()
            backgroundScope.launch { facade.observeModelMenu("c1").collect { menus += it } }
            runCurrent()

            repoA.pushModelMenu(MENU)
            runCurrent()
            assertEquals(MENU, menus.last())

            current.value = repoB
            runCurrent()
            assertEquals(listOf(null, MENU, null), menus)
        }

    @Test
    fun refreshSessionSettings_delegatesToLiveRepo() =
        runTest {
            val repo = RecordingConversationRepository()
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(repo))

            facade.refreshSessionSettings("c1")

            assertEquals(listOf("c1"), repo.refreshSessionSettingsCalls)
        }

    // The one facade method that must not throw with no connection live: an invalidation has nothing
    // for the caller to recover, and the next connection re-reads on subscription anyway.
    @Test
    fun refreshSessionSettings_whileAbsent_isSilentNoOp() =
        runTest {
            val facade = StableConversationRepository(MutableStateFlow<ConversationRepository?>(null))

            facade.refreshSessionSettings("c1")
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
        private val compacting = MutableStateFlow(false)
        private val usageLimit = MutableStateFlow<UsageLimitReading?>(null)

        val createDiscussionCalls = mutableListOf<String?>()
        val sendMessageCalls = mutableListOf<Pair<String, String>>()
        val requestScreenSnapshotCalls = mutableListOf<String>()
        val dropQueuedMessageCalls = mutableListOf<Pair<String, Long>>()
        val requestHistoryCalls = mutableListOf<Triple<String, String, Int>>()

        var createDiscussionResult: Conversation = conversation("created")
        var sendMessageResult: Message = message("sent")
        var requestScreenSnapshotResult: String = "snapshot-text"
        var requestHistoryResult: HistoryPage = HistoryPage(entries = emptyList(), cursor = "", atStart = true)
        val requestSystemPromptResult = SystemPromptReading("stored", SessionPromptStatus.Matches)
        val requestSystemPromptCalls = mutableListOf<String>()
        val setSystemPromptCalls = mutableListOf<Pair<String, String?>>()
        val renameWorkspaceCalls = mutableListOf<Pair<String, String?>>()
        val archiveWorkspaceCalls = mutableListOf<String>()

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

        fun pushCompacting(value: Boolean) {
            compacting.value = value
        }

        fun pushUsageLimit(value: UsageLimitReading?) {
            usageLimit.value = value
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = conversations.filterNotNull()

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

        override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

        override fun observeStall(conversationId: String): Flow<Boolean> = stalled

        override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> = queued

        override fun observeApiRetry(conversationId: String): Flow<ApiRetryStatus> = apiRetry

        override fun observeCompacting(conversationId: String): Flow<Boolean> = compacting

        private val resetting = MutableStateFlow<ResetStatus?>(null)

        fun pushResetting(value: ResetStatus?) {
            resetting.value = value
        }

        override fun observeResetting(conversationId: String): Flow<ResetStatus?> = resetting

        override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> = usageLimit

        private val thinkingProgress = MutableStateFlow<ThinkingProgress?>(null)

        fun pushThinkingProgress(value: ThinkingProgress?) {
            thinkingProgress.value = value
        }

        override fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> = thinkingProgress

        private val sessionSettings = MutableStateFlow<SessionSettings?>(null)

        val refreshSessionSettingsCalls = mutableListOf<String>()

        fun pushSessionSettings(value: SessionSettings?) {
            sessionSettings.value = value
        }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = sessionSettings

        private val modelMenu = MutableStateFlow<ModelMenu?>(null)

        fun pushModelMenu(value: ModelMenu?) {
            modelMenu.value = value
        }

        override fun observeModelMenu(conversationId: String): Flow<ModelMenu?> = modelMenu

        override fun refreshSessionSettings(conversationId: String) {
            refreshSessionSettingsCalls += conversationId
        }

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

        val sendWithAttachmentsCalls = mutableListOf<Triple<String, String, List<String>>>()

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachmentIds: List<String>,
        ): Message {
            sendWithAttachmentsCalls += Triple(conversationId, text, attachmentIds)
            return sendMessageResult
        }

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            requestHistoryCalls += Triple(conversationId, cursor, limit)
            return requestHistoryResult
        }

        override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading {
            requestSystemPromptCalls += conversationId
            return requestSystemPromptResult
        }

        val uploadCalls = mutableListOf<String>()
        val uploadResult = CompletableDeferred<AttachmentUploadResult>()

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
        ): AttachmentUploadResult {
            uploadCalls += conversationId
            return uploadResult.await()
        }

        override suspend fun setSystemPrompt(
            conversationId: String,
            systemPrompt: String?,
        ) {
            setSystemPromptCalls += conversationId to systemPrompt
        }

        override suspend fun renameWorkspace(
            path: String,
            label: String?,
        ) {
            renameWorkspaceCalls += path to label
        }

        override suspend fun archiveWorkspace(path: String) {
            archiveWorkspaceCalls += path
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
        /** One settings reading (#590) — the values are arbitrary; only their survival is asserted. */
        val READING =
            SessionSettings(
                sessionId = "sess-a",
                model = "opus",
                effort = "high",
                effectiveEffort = EffectiveEffort.Applied("medium"),
                permissionMode = "default",
                yolo = false,
                usedTokens = 12480L,
                windowTokens = 200000L,
            )

        /** One retained menu (#791) — the values are arbitrary; only their survival is asserted. */
        val MENU =
            ModelMenu(
                rows = listOf(ModelMenuRow("claude-sonnet-5", "sonnet", "Sonnet 5", listOf("low", "high"), true, null)),
                droppedModels = 4,
            )

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

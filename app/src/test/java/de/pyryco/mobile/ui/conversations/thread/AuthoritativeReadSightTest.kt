package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadOutcome
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ReadCheckpointRetries
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeReadSightTest {
    private val logging = RelayLog.enabled

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        RelayLog.enabled = false
    }

    @After fun teardown() {
        Dispatchers.resetMain()
        RelayLog.enabled = logging
    }

    @Test fun sendingWaitingAndLostTailsPreserveExactHistoryReadRequirements() {
        for (status in listOf("sending", "queued", "lost")) {
            val snapshot = authoritativeSendTail(status)
            val p = projectAuthoritativeThread("h", "c", snapshot)
            val key = p.rows.first().listKey(0)
            val tailKey = p.rows.last().listKey(0)
            assertEquals(status, 4uL, p.readCheckpoint(key, facts(4u), p.shownRowRevisions))
            assertNull(status, p.readCheckpoint(key, facts(4u), emptyMap()))
            assertNull(status, p.readCheckpoint(key, facts(4u), mapOf(key to 3L)))
            assertNull(status, p.readCheckpoint(tailKey, facts(4u), p.shownRowRevisions + (tailKey to 4L)))
            assertNull(status, p.readCheckpoint(key, facts(5u), p.shownRowRevisions))
            val partial = projectAuthoritativeThread("h", "c", snapshot.copy(checkpoint = 3))
            assertNull(status, partial.readCheckpoint(key, facts(4u), p.shownRowRevisions))

            val reply =
                projectAuthoritativeThread(
                    "h",
                    "c",
                    snapshot.copy(items = snapshot.items + authoritativeItem(3, rev = 5), checkpoint = 5),
                )
            val replyKey =
                reply.rows
                    .filterIsInstance<ThreadRow.Delivered>()
                    .last()
                    .listKey(0)
            assertEquals(status, tailKey, reply.rows.last().listKey(0))
            assertNull(status, reply.readCheckpoint(key, facts(5u), reply.shownRowRevisions))
            assertNull(status, reply.readCheckpoint(replyKey, facts(5u), p.shownRowRevisions))
            assertNull(status, reply.readCheckpoint(replyKey, facts(5u), mapOf(replyKey to 5L)))
            assertEquals(status, 5uL, reply.readCheckpoint(replyKey, facts(5u).copy(readUpTo = 4u), mapOf(replyKey to 5L)))
            assertEquals(status, 5uL, reply.readCheckpoint(replyKey, facts(5u), reply.shownRowRevisions))

            val tailOnly = projectAuthoritativeThread("h", "c", snapshot.copy(items = snapshot.items.filter { it.order == null }))
            assertTrue(status, tailOnly.shownRowRevisions.isEmpty())
            assertNull(status, tailOnly.readCheckpoint(tailKey, facts(4u), mapOf(tailKey to 4L)))
        }
    }

    @Test fun completeCoverageCannotBeInferredFromABatchOrHighestItemRevision() {
        val row = authoritativeItem(1, rev = 8)
        val partial = authoritativeSnapshot(row).copy(checkpoint = 5, completedVersion = 100)
        val p = projectAuthoritativeThread("h", "c", partial)
        assertNull(p.readCheckpoint(p.rows.last().listKey(0), facts(8u), p.shownRowRevisions))
        val complete = projectAuthoritativeThread("h", "c", partial.copy(checkpoint = 8))
        assertEquals(8uL, complete.readCheckpoint(complete.rows.last().listKey(0), facts(8u), complete.shownRowRevisions))
        for (bad in listOf(partial.copy(checkpoint = 8, repairNeeded = true), partial.copy(checkpoint = 8, resetNeeded = true))) {
            val rejected = projectAuthoritativeThread("h", "c", bad)
            assertNull(rejected.readCheckpoint(rejected.rows.last().listKey(0), facts(8u), rejected.shownRowRevisions))
        }
    }

    @Test fun storeBatchOverlapAndRepairCannotCertifySightUntilCompleteBoundary() {
        val store = AuthoritativeThreadStore()
        val first = authoritativeItem(1, rev = 5)
        val newer = authoritativeItem(2, rev = 8)
        store.establishComplete("h", "c", "e", 5, listOf(first), 0)
        repeat(2) { store.applyBatch("h", "c", "e", 8, listOf(first, newer), 0) }

        fun presentation() = projectAuthoritativeThread("h", "c", store.current("h", "c"))
        val partial = presentation()
        assertEquals(8L, store.current("h", "c").completedVersion)
        assertEquals(5L, store.current("h", "c").checkpoint)
        assertNull(partial.readCheckpoint(partial.rows.last().listKey(0), facts(8u), partial.shownRowRevisions))
        store.establishComplete("h", "c", "e", 8, listOf(first, newer), 0)
        val complete = presentation()
        assertEquals(8uL, complete.readCheckpoint(complete.rows.last().listKey(0), facts(8u), complete.shownRowRevisions))
        store.requestRepair("h", "c")
        val repairing = presentation()
        assertNull(repairing.readCheckpoint(repairing.rows.last().listKey(0), facts(8u), repairing.shownRowRevisions))
        store.reset("h", "c", "e")
        assertNotEquals(complete.scope, presentation().scope)
        assertEquals(AuthoritativeThreadOutcome.StaleGeneration, store.establishComplete("h", "c", "e", 8, listOf(newer), 0))
    }

    @Test fun hiddenAndStateOnlyChangesNeverRaiseTheSummaryBoundedCheckpoint() {
        val p =
            projectAuthoritativeThread(
                "h",
                "c",
                authoritativeSnapshot(authoritativeItem(1, rev = 4), authoritativeItem(99, shown = false, rev = 99)).copy(checkpoint = 100),
            )
        assertEquals(4uL, p.readCheckpoint(p.rows.last().listKey(0), facts(4u), p.shownRowRevisions))
        assertNull(p.readCheckpoint(p.rows.last().listKey(0), facts(null), p.shownRowRevisions))
        assertNull(p.readCheckpoint(p.rows.last().listKey(0), facts(0u), p.shownRowRevisions))
        val changed =
            projectAuthoritativeThread(
                "h",
                "c",
                authoritativeSnapshot(authoritativeItem(1, rev = 9, status = "done")).copy(checkpoint = 10),
            )
        assertEquals(4uL, changed.readCheckpoint(changed.rows.last().listKey(0), facts(4u), changed.shownRowRevisions))
        assertNull(changed.readCheckpoint(changed.rows.last().listKey(0), facts(4u).copy(readUpTo = 4u), changed.shownRowRevisions))
    }

    @Test fun anAppendToAnOlderShownRowRequiresThatExactContentSight() {
        val old = authoritativeItem(1, rev = 8, content = """{"text":"appended older text"}""")
        val newer = authoritativeItem(2, rev = 2)
        val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(old, newer).copy(checkpoint = 8))
        assertNull(p.readCheckpoint(p.rows.last().listKey(0), facts(8u), p.sightOf(p.rows.lastIndex)))
        assertEquals(8uL, p.readCheckpoint(p.rows.last().listKey(0), facts(8u), p.shownRowRevisions))
        val replacement =
            projectAuthoritativeThread("h", "c", authoritativeSnapshot(authoritativeItem(1, rev = 9), newer).copy(checkpoint = 9))
        assertNull(replacement.readCheckpoint(replacement.rows.last().listKey(0), facts(9u), p.shownRowRevisions))
    }

    @Test fun unseenIntermediateAppendMustWithholdRead() {
        val old = authoritativeItem(1, rev = 6, content = """{"text":"appended older text"}""")
        val newest = authoritativeItem(2, rev = 8)
        val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(old, newest).copy(checkpoint = 8))
        val marks = facts(8u).copy(readUpTo = 5u)
        assertNull(p.readCheckpoint(p.rows.last().listKey(0), marks, p.sightOf(p.rows.lastIndex)))
        assertEquals(8uL, p.readCheckpoint(p.rows.last().listKey(0), marks, p.shownRowRevisions))
        assertEquals(8uL, p.readCheckpoint(p.rows.last().listKey(0), marks.copy(readUpTo = 6u), p.sightOf(p.rows.lastIndex)))
    }

    @Test fun equalContentNonMessageRowsMustNotShareSight() {
        val divider = """{"cause":"idle_sleep","previous_session_id":"old","new_session_id":"new"}"""
        val variants =
            listOf(
                Triple("notice", "banner", """{"text":"same warning","level":"warning"}"""),
                Triple("notice", "model_refusal_no_fallback", """{"original_model":"model","banner":"same refusal"}"""),
                Triple("session_divider", null, divider),
                Triple("compaction", null, """{"trigger":"manual","pre_tokens":100,"post_tokens":50}"""),
            )
        for ((kind, subtype, content) in variants) {
            val p =
                projectAuthoritativeThread(
                    "h",
                    "c",
                    authoritativeSnapshot(
                        authoritativeItem(1, kind = kind, subtype = subtype, rev = 6, content = content),
                        authoritativeItem(2, kind = kind, subtype = subtype, rev = 8, content = content),
                    ).copy(checkpoint = 8),
                )
            val first = p.items.first()
            val last = p.items.last()
            if (first is ThreadItem.ModelRefusal && last is ThreadItem.ModelRefusal) {
                assertNotNull(first.canonicalIdentity)
                assertNotNull(last.canonicalIdentity)
                assertNotEquals(first.canonicalIdentity, last.canonicalIdentity)
                assertEquals(first.copy(canonicalIdentity = null), last.copy(canonicalIdentity = null))
            } else {
                assertEquals(first, last)
            }
            assertNotEquals(p.rows.first().listKey(0), p.rows.last().listKey(0))
            assertEquals(2, p.shownRowRevisions.size)
            assertNull(kind, p.readCheckpoint(p.rows.last().listKey(0), facts(8u).copy(readUpTo = 5u), p.sightOf(0)))
            assertNull(kind, p.readCheckpoint(p.rows.first().listKey(0), facts(8u).copy(readUpTo = 5u), p.shownRowRevisions))
            assertEquals(8uL, p.readCheckpoint(p.rows.last().listKey(0), facts(8u).copy(readUpTo = 5u), p.shownRowRevisions))
        }
    }

    @Test fun productionViewModelRequiresCarriedScopedRevisionSight() =
        runTest {
            val peer = Peer().apply { facts.value = facts(8u).copy(readUpTo = 5u) }
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            val source = MutableStateFlow<ConversationRepository?>(peer)
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                    StableConversationRepository(source, readRetries = retry),
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val warning = """{"text":"same warning","level":"warning"}"""
            val p =
                projectAuthoritativeThread(
                    "h",
                    "c",
                    authoritativeSnapshot(
                        authoritativeItem(1, kind = "notice", subtype = "banner", rev = 6, content = warning),
                        authoritativeItem(2, kind = "notice", subtype = "banner", rev = 8, content = warning),
                    ).copy(checkpoint = 8),
                )
            val token = Any()
            vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadPresented(p, token))
            val event = ThreadEvent.NewestContentPresented(p.items.last(), 8u, p, token, p.rows.last().listKey(0), p.shownRowRevisions)
            try {
                for (unqualified in listOf(
                    event.copy(rowKey = null),
                    event.copy(rowKey = p.rows.first().listKey(0)),
                    event.copy(seenRowRevisions = emptyMap()),
                    event.copy(seenRowRevisions = p.sightOf(p.rows.lastIndex)),
                    event.copy(seenRowRevisions = p.shownRowRevisions + (p.rows.first().listKey(0) to 5)),
                    event.copy(seenRowRevisions = p.shownRowRevisions.mapKeys { "retired:${it.key}" }),
                )) {
                    vm.onOverflowEvent(unqualified)
                }
                runCurrent()
                assertTrue(peer.writes.isEmpty())
                vm.onOverflowEvent(event)
                runCurrent()
                assertEquals(listOf(8uL), peer.writes)
            } finally {
                retry.dispose()
            }
        }

    @Test fun duplicateAndOverlappingDeliveryDoesNotChangeProofAndScopesFenceIdentityReuse() {
        val item = authoritativeItem(1, rev = 8)
        val snapshot = authoritativeSnapshot(item).copy(checkpoint = 8)
        val p = projectAuthoritativeThread("h", "c", snapshot)
        val duplicate = projectAuthoritativeThread("h", "c", snapshot.copy(items = listOf(item, item)))
        assertEquals(p.items, duplicate.items)
        assertEquals(8uL, duplicate.readCheckpoint(duplicate.rows.last().listKey(0), facts(8u), p.shownRowRevisions))
        for (next in listOf(
            projectAuthoritativeThread("other", "c", snapshot),
            projectAuthoritativeThread("h", "other", snapshot),
            projectAuthoritativeThread("h", "c", snapshot.copy(epoch = "other")),
            projectAuthoritativeThread("h", "c", snapshot.copy(generation = 1)),
        )) {
            assertNotEquals(p.scope, next.scope)
            assertNull(next.readCheckpoint(next.rows.last().listKey(0), facts(8u), p.shownRowRevisions))
        }
    }

    @Test fun productionViewModelRejectsRetiredForeignAndOverAdvancedSightBeforeHostRetry() =
        runTest {
            val peer = Peer()
            val retry = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            val source = MutableStateFlow<ConversationRepository?>(peer)
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                    StableConversationRepository(source, readRetries = retry),
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(authoritativeItem(1, rev = 8)).copy(checkpoint = 8))
            val binding = Any()

            fun present(
                presentation: AuthoritativeThreadPresentation = p,
                token: Any = binding,
            ) = vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadPresented(presentation, token))

            fun sight(
                checkpoint: ULong = 8u,
                presentation: AuthoritativeThreadPresentation = p,
                token: Any? = binding,
            ) = vm.onOverflowEvent(
                ThreadEvent.NewestContentPresented(
                    presentation.items.last(),
                    checkpoint,
                    presentation,
                    token,
                    presentation.rows.last().listKey(0),
                    presentation.shownRowRevisions,
                ),
            )
            try {
                present()
                sight(9u)
                sight(token = Any())
                vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadRetired(binding))
                sight()
                val foreign =
                    projectAuthoritativeThread("other", "c", authoritativeSnapshot(authoritativeItem(1, rev = 8)).copy(checkpoint = 8))
                present(foreign)
                sight(presentation = foreign)
                runCurrent()
                assertTrue(peer.writes.isEmpty())
                present()
                sight()
                runCurrent()
                assertEquals(listOf(8uL), peer.writes)
                vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadRetired(binding))
                source.value = null
                runCurrent()
                val reconnected = Peer().apply { response = 7u }
                source.value = reconnected
                runCurrent()
                assertTrue(reconnected.writes.isEmpty())
                repeat(3) { sight() }
                runCurrent()
                assertTrue(reconnected.writes.isEmpty())
                reconnected.facts.value = facts(8u).copy(readUpTo = 7u)
                source.value = null
                runCurrent()
                val noop =
                    Peer().apply {
                        response = 7u
                        facts.value = facts(8u).copy(readUpTo = 7u)
                    }
                source.value = noop
                runCurrent()
                assertTrue(noop.writes.isEmpty())
                noop.facts.value = facts(8u).copy(readUpTo = 8u)
                runCurrent()
                source.value = null
                runCurrent()
                val settled = Peer()
                source.value = settled
                runCurrent()
                assertTrue(settled.writes.isEmpty())
            } finally {
                retry.dispose()
            }
        }

    @Test fun reconnectCannotReuseAnActiveCompositionToQualifyNewSight() =
        runTest {
            val peer = Peer()
            val source = MutableStateFlow<ConversationRepository?>(peer)
            val retries = ReadCheckpointRetries(StandardTestDispatcher(testScheduler))
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "h", "conversationId" to "c")),
                    StableConversationRepository(source, readRetries = retries),
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(authoritativeItem(1, rev = 8)).copy(checkpoint = 8))
            val binding = Any()
            vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadPresented(p, binding))
            vm.onOverflowEvent(
                ThreadEvent.NewestContentPresented(p.items.last(), 8u, p, binding, p.rows.last().listKey(0), p.shownRowRevisions),
            )
            runCurrent()
            assertEquals(listOf(8uL), peer.writes)
            source.value = null
            runCurrent()
            val next = Peer()
            source.value = next
            runCurrent()
            vm.onOverflowEvent(
                ThreadEvent.NewestContentPresented(p.items.last(), 8u, p, binding, p.rows.last().listKey(0), p.shownRowRevisions),
            )
            runCurrent()
            assertEquals(listOf(8uL), peer.writes)
            assertTrue(next.writes.isEmpty())
            val replacementBinding = Any()
            vm.onOverflowEvent(ThreadEvent.AuthoritativeThreadPresented(p, replacementBinding))
            vm.onOverflowEvent(
                ThreadEvent.NewestContentPresented(
                    p.items.last(),
                    8u,
                    p,
                    replacementBinding,
                    p.rows.last().listKey(0),
                    p.shownRowRevisions,
                ),
            )
            runCurrent()
            assertEquals(listOf(8uL), next.writes)
            retries.dispose()
        }

    private class Peer : ConversationRepository by FakeConversationRepository() {
        val facts = MutableStateFlow<ConversationReadMarks?>(ConversationReadMarks(0u, 100u, 8u, true))
        val writes = mutableListOf<ULong>()
        var response: ULong? = null

        override fun observeReadMarks(conversationId: String) = facts

        override suspend fun markConversationRead(
            conversationId: String,
            upTo: ULong,
        ): Result<ULong> {
            writes += upTo
            return response?.let { Result.success(it) } ?: Result.failure(IllegalStateException("offline"))
        }
    }

    private fun AuthoritativeThreadPresentation.sightOf(index: Int): Map<String, Long> =
        rows[index].listKey(0).let { mapOf(it to shownRowRevisions.getValue(it)) }

    private fun facts(shown: ULong?) = ConversationReadMarks(0u, 100u, shown, true)
}

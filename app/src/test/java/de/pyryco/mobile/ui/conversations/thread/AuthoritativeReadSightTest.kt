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

    @Test fun completeCoverageCannotBeInferredFromABatchOrHighestItemRevision() {
        val row = authoritativeItem(1, rev = 8)
        val partial = authoritativeSnapshot(row).copy(checkpoint = 5, completedVersion = 100)
        val p = projectAuthoritativeThread("h", "c", partial)
        assertNull(p.readCheckpoint(p.items.last(), facts(8u), p.items.toSet()))
        val complete = projectAuthoritativeThread("h", "c", partial.copy(checkpoint = 8))
        assertEquals(8uL, complete.readCheckpoint(complete.items.last(), facts(8u), complete.items.toSet()))
        for (bad in listOf(partial.copy(checkpoint = 8, repairNeeded = true), partial.copy(checkpoint = 8, resetNeeded = true))) {
            val rejected = projectAuthoritativeThread("h", "c", bad)
            assertNull(rejected.readCheckpoint(rejected.items.last(), facts(8u), rejected.items.toSet()))
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
        assertNull(partial.readCheckpoint(partial.items.last(), facts(8u), partial.items.toSet()))
        store.establishComplete("h", "c", "e", 8, listOf(first, newer), 0)
        val complete = presentation()
        assertEquals(8uL, complete.readCheckpoint(complete.items.last(), facts(8u), complete.items.toSet()))
        store.requestRepair("h", "c")
        val repairing = presentation()
        assertNull(repairing.readCheckpoint(repairing.items.last(), facts(8u), repairing.items.toSet()))
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
        assertEquals(4uL, p.readCheckpoint(p.items.last(), facts(4u), p.items.toSet()))
        assertNull(p.readCheckpoint(p.items.last(), facts(null), p.items.toSet()))
        assertNull(p.readCheckpoint(p.items.last(), facts(0u), p.items.toSet()))
        val changed =
            projectAuthoritativeThread(
                "h",
                "c",
                authoritativeSnapshot(authoritativeItem(1, rev = 9, status = "done")).copy(checkpoint = 10),
            )
        assertEquals(4uL, changed.readCheckpoint(changed.items.last(), facts(4u), changed.items.toSet()))
        assertNull(changed.readCheckpoint(changed.items.last(), facts(4u).copy(readUpTo = 4u), changed.items.toSet()))
    }

    @Test fun anAppendToAnOlderShownRowRequiresThatExactContentSight() {
        val old = authoritativeItem(1, rev = 8, content = """{"text":"appended older text"}""")
        val newer = authoritativeItem(2, rev = 2)
        val p = projectAuthoritativeThread("h", "c", authoritativeSnapshot(old, newer).copy(checkpoint = 8))
        assertNull(p.readCheckpoint(p.items.last(), facts(8u), setOf(p.items.last())))
        assertEquals(8uL, p.readCheckpoint(p.items.last(), facts(8u), p.items.toSet()))
        val replacement =
            projectAuthoritativeThread("h", "c", authoritativeSnapshot(authoritativeItem(1, rev = 9), newer).copy(checkpoint = 9))
        assertNull(replacement.readCheckpoint(replacement.items.last(), facts(9u), p.items.toSet()))
    }

    @Test fun duplicateAndOverlappingDeliveryDoesNotChangeProofAndScopesFenceIdentityReuse() {
        val item = authoritativeItem(1, rev = 8)
        val snapshot = authoritativeSnapshot(item).copy(checkpoint = 8)
        val p = projectAuthoritativeThread("h", "c", snapshot)
        val duplicate = projectAuthoritativeThread("h", "c", snapshot.copy(items = listOf(item, item)))
        assertEquals(p.items, duplicate.items)
        assertEquals(8uL, duplicate.readCheckpoint(duplicate.items.last(), facts(8u), p.items.toSet()))
        for (next in listOf(
            projectAuthoritativeThread("other", "c", snapshot),
            projectAuthoritativeThread("h", "other", snapshot),
            projectAuthoritativeThread("h", "c", snapshot.copy(epoch = "other")),
            projectAuthoritativeThread("h", "c", snapshot.copy(generation = 1)),
        )) {
            assertNotEquals(p.scope, next.scope)
            assertNull(next.readCheckpoint(next.items.last(), facts(8u), p.items.toSet()))
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
            ) = vm.onOverflowEvent(ThreadEvent.NewestContentPresented(presentation.items.last(), checkpoint, presentation, token))
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
                assertEquals(listOf(8uL), reconnected.writes)
                repeat(3) { sight() }
                runCurrent()
                assertEquals(listOf(8uL), reconnected.writes)
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
                assertEquals(listOf(8uL), noop.writes)
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
            source.value = null
            runCurrent()
            val next = Peer()
            source.value = next
            runCurrent()
            vm.onOverflowEvent(ThreadEvent.NewestContentPresented(p.items.last(), 8u, p, binding))
            runCurrent()
            assertTrue(peer.writes.isEmpty())
            assertTrue(next.writes.isEmpty())
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

    private fun facts(shown: ULong?) = ConversationReadMarks(0u, 100u, shown, true)
}

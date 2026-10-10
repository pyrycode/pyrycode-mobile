package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class DeletionMutationReadinessTest {
    @Test
    fun renameWithRetiredPublication_waitsForTheOwningReplacementAndSubmitsOnce() =
        runTest {
            val old = RecordingRepository()
            val owner = LaggingRepositoryPublication(old)
            val stable = StableConversationRepository(owner.current)
            owner.available = null
            assertSame(old, owner.published.value)
            assertNull(owner.current.value)
            var submissions = 0
            val rename =
                async {
                    runCatching {
                        awaitDeletionMutationReady(owner.current)
                        submissions++
                        stable.rename(CHAT, "unique discussion")
                    }
                }
            runCurrent()
            assertFalse("A retired publication must not release Save", rename.isCompleted)
            assertEquals(0, submissions)
            val fresh = RecordingRepository()
            owner.available = fresh
            owner.published.value = null
            runCurrent()
            owner.published.value = old
            runCurrent()
            assertFalse("Save must await publication of its current owner", rename.isCompleted)
            owner.published.value = fresh
            rename.await().getOrThrow()
            assertEquals(1, submissions)
            assertTrue(old.calls.isEmpty())
            assertEquals(listOf("rename"), fresh.calls)
            assertTrue(fresh.observeConversations(ConversationFilter.All).first().any { it.id == CHAT && it.name == "unique discussion" })
        }

    @Test
    fun deleteWithRetiredPublication_waitsForTheOwningReplacementAndSubmitsOnce() =
        runTest {
            val old = RecordingRepository()
            val owner = LaggingRepositoryPublication(old)
            val stable = StableConversationRepository(owner.current)
            owner.available = null
            assertSame(old, owner.published.value)
            assertNull(owner.current.value)
            var submissions = 0
            val delete =
                async {
                    runCatching {
                        awaitDeletionMutationReady(owner.current)
                        submissions++
                        stable.delete(CHAT)
                    }
                }
            runCurrent()
            assertFalse("A retired publication must not release confirmation", delete.isCompleted)
            assertEquals(0, submissions)
            val fresh = RecordingRepository()
            owner.available = fresh
            owner.published.value = null
            runCurrent()
            owner.published.value = old
            runCurrent()
            assertFalse("Confirmation must await publication of its current owner", delete.isCompleted)
            owner.published.value = fresh
            delete.await().getOrThrow()
            assertEquals(1, submissions)
            assertTrue(old.calls.isEmpty())
            assertEquals(listOf("delete"), fresh.calls)
            assertFalse(fresh.observeConversations(ConversationFilter.All).first().any { it.id == CHAT })
        }

    @Test
    fun renameDuringGap_waitsAndReachesTheNewDelegateExactlyOnce() =
        runTest {
            val old = RecordingRepository()
            val current = MutableStateFlow<ConversationRepository?>(old)
            val stable = StableConversationRepository(current)
            current.value = null
            val rename =
                async {
                    runCatching {
                        awaitDeletionMutationReady(current)
                        stable.rename(CHAT, "unique discussion")
                    }
                }
            runCurrent()
            assertFalse("Save must not submit into a connection gap", rename.isCompleted)
            val fresh = RecordingRepository()
            current.value = fresh
            rename.await().getOrThrow()
            assertTrue(old.calls.isEmpty())
            assertEquals(listOf("rename"), fresh.calls)
            assertTrue(fresh.observeConversations(ConversationFilter.All).first().any { it.id == CHAT && it.name == "unique discussion" })
        }

    @Test
    fun deleteDuringGap_waitsAndReachesTheNewDelegateExactlyOnce() =
        runTest {
            val old = RecordingRepository()
            val current = MutableStateFlow<ConversationRepository?>(old)
            val stable = StableConversationRepository(current)
            current.value = null
            val delete =
                async {
                    runCatching {
                        awaitDeletionMutationReady(current)
                        stable.delete(CHAT)
                    }
                }
            runCurrent()
            assertFalse("confirmation must not submit into a connection gap", delete.isCompleted)
            val fresh = RecordingRepository()
            current.value = fresh
            delete.await().getOrThrow()
            assertTrue(old.calls.isEmpty())
            assertEquals(listOf("delete"), fresh.calls)
            assertFalse(fresh.observeConversations(ConversationFilter.All).first().any { it.id == CHAT })
        }

    @Test
    fun missingHost_preservesTheCallersTimeout() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val stable = StableConversationRepository(current)
            val wait =
                async {
                    runCatching {
                        withTimeout(100) {
                            awaitDeletionMutationReady(current)
                            stable.rename(CHAT, "unique discussion")
                            stable.delete(CHAT)
                        }
                    }
                }
            advanceUntilIdle()
            assertTrue(wait.await().exceptionOrNull() is TimeoutCancellationException)
            val fresh = RecordingRepository()
            current.value = fresh
            advanceUntilIdle()
            assertTrue("Timed-out actions must not submit after reconnect", fresh.calls.isEmpty())
        }

    @Test
    fun cancelledWait_doesNotSubmitAfterReconnect() =
        runTest {
            val current = MutableStateFlow<ConversationRepository?>(null)
            val stable = StableConversationRepository(current)
            val delete =
                async {
                    runCatching {
                        awaitDeletionMutationReady(current)
                        stable.delete(CHAT)
                    }
                }
            runCurrent()
            assertFalse(delete.isCompleted)
            delete.cancel()
            val fresh = RecordingRepository()
            current.value = fresh
            advanceUntilIdle()
            assertTrue(delete.isCancelled)
            assertTrue(fresh.calls.isEmpty())
        }

    @Test
    fun anotherReadyHost_doesNotReleaseTheOwningHostWait() =
        runTest {
            val owner = MutableStateFlow<ConversationRepository?>(null)
            val otherRepository = RecordingRepository()
            val other = MutableStateFlow<ConversationRepository?>(otherRepository)
            val stable = StableConversationRepository(owner)
            val rename =
                async {
                    awaitDeletionMutationReady(owner)
                    stable.rename(CHAT, "unique discussion")
                }
            val delete =
                async {
                    awaitDeletionMutationReady(owner)
                    stable.delete(CHAT)
                }
            runCurrent()
            val otherReplacement = RecordingRepository()
            other.value = otherReplacement
            runCurrent()
            assertFalse(rename.isCompleted)
            assertFalse(delete.isCompleted)
            val fresh = RecordingRepository()
            owner.value = fresh
            rename.await()
            delete.await()
            assertEquals(1, fresh.calls.count { it == "rename" })
            assertEquals(1, fresh.calls.count { it == "delete" })
            assertTrue(otherRepository.calls.isEmpty())
            assertTrue(otherReplacement.calls.isEmpty())
        }

    @Test
    fun readyHost_submitsWithoutWaitingOrRepeating() =
        runTest {
            val repository = RecordingRepository()
            val current = MutableStateFlow<ConversationRepository?>(repository)
            awaitDeletionMutationReady(current)
            StableConversationRepository(current).delete(CHAT)
            assertEquals(listOf("delete"), repository.calls)
        }

    /** Mirrors the coordinator: collection can retain a repository rejected by synchronous value. */
    private class LaggingRepositoryPublication(
        initial: ConversationRepository,
    ) {
        val published = MutableStateFlow<ConversationRepository?>(initial)
        var available: ConversationRepository? = initial
        val current =
            object : StateFlow<ConversationRepository?> by published {
                override val value: ConversationRepository? get() = available
            }
    }

    private class RecordingRepository(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val calls = mutableListOf<String>()

        override suspend fun rename(
            conversationId: String,
            name: String,
        ): de.pyryco.mobile.data.model.Conversation {
            calls += "rename"
            return delegate.rename(conversationId, name)
        }

        override suspend fun delete(conversationId: String) {
            calls += "delete"
            delegate.delete(conversationId)
        }
    }

    private companion object {
        const val CHAT = "seed-channel-personal"
    }
}

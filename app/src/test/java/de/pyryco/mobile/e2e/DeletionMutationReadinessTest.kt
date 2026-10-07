package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeletionMutationReadinessTest {
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
            val wait = async { runCatching { withTimeout(100) { awaitDeletionMutationReady(current) } } }
            advanceUntilIdle()
            assertTrue(wait.await().exceptionOrNull() is TimeoutCancellationException)
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
            assertTrue(fresh.calls.isEmpty())
        }

    @Test
    fun anotherReadyHost_doesNotReleaseTheOwningHostWait() =
        runTest {
            val owner = MutableStateFlow<ConversationRepository?>(null)
            val other = MutableStateFlow<ConversationRepository?>(RecordingRepository())
            val wait = async { awaitDeletionMutationReady(owner) }
            runCurrent()
            other.value = RecordingRepository()
            runCurrent()
            assertFalse(wait.isCompleted)
            owner.value = RecordingRepository()
            wait.await()
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

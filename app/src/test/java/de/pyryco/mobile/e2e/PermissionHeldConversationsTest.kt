package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionHeldConversationsTest {
    @Test
    fun missingInitialSnapshotKeepsConversationsStageSnapshotAndTimeoutCause() {
        assertReadTimeout(PermissionHeldStage.Conversations, ListRepository(emptyFlow())) { live ->
            live.awaitPermissionHeldConversationIds(LIST_TIMEOUT_MS)
        }
    }

    @Test
    fun missingNewChatKeepsChatIdentityStageSnapshotAndTimeoutCause() {
        val rows = MutableStateFlow(listOf(conversation("existing")))
        assertReadTimeout(PermissionHeldStage.ChatIdentity, ListRepository(rows)) { live ->
            live.awaitPermissionHeldChatId(setOf("existing"), LIST_TIMEOUT_MS)
        }
    }

    @Test
    fun initialSnapshotAcceptsEmptyListWithoutReadingDiagnosticSnapshot() {
        val events = mutableListOf<String>()
        val result =
            permissionHeldStep(PermissionHeldStage.Conversations, { error("unused snapshot") }, events::add) {
                runTest {
                    val live = MutableStateFlow<ConversationRepository?>(ListRepository(MutableStateFlow(emptyList())))
                    assertEquals(emptySet<String>(), live.awaitPermissionHeldConversationIds(LIST_TIMEOUT_MS))
                    assertEquals(0L, currentTime)
                }
            }
        assertEquals(Unit, result)
        assertEquals("event=permission_held_stage stage=Conversations status=completed", events.last())
    }

    @Test
    fun chatIdentityWaitsForNewIdAcrossRepositoryReplacement() =
        runTest {
            val oldRows = MutableStateFlow(listOf(conversation("existing")))
            val live = MutableStateFlow<ConversationRepository?>(ListRepository(oldRows))
            val read = async { live.awaitPermissionHeldChatId(setOf("existing"), LIST_TIMEOUT_MS) }
            runCurrent()
            assertFalse(read.isCompleted)

            live.value = null
            oldRows.value = listOf(conversation("retired"))
            runCurrent()
            assertFalse("retired repository supplied the chat", read.isCompleted)

            val newRows = MutableStateFlow(listOf(conversation("existing")))
            live.value = ListRepository(newRows)
            runCurrent()
            assertFalse("an existing id was accepted", read.isCompleted)
            newRows.value = listOf(conversation("existing"), conversation("created"))
            runCurrent()
            assertEquals("created", read.await())
        }

    private fun assertReadTimeout(
        stage: PermissionHeldStage,
        repository: ConversationRepository,
        read: suspend (StateFlow<ConversationRepository?>) -> Any,
    ) {
        val events = mutableListOf<String>()
        var snapshots = 0
        var elapsed = 0L
        val snapshot = "targetPresent=true targetRelay=Connected targetDaemon=Connected repositoryAvailable=true; peer=closed"
        val failure =
            runCatching {
                permissionHeldStep(stage, {
                    snapshots++
                    snapshot
                }, events::add) {
                    runTest {
                        val live = MutableStateFlow<ConversationRepository?>(repository)
                        val pending = async { runCatching { read(live) } }
                        runCurrent()
                        advanceTimeBy(LIST_TIMEOUT_MS - 1)
                        runCurrent()
                        assertFalse("read ended before its original deadline", pending.isCompleted)
                        advanceTimeBy(1)
                        runCurrent()
                        elapsed = currentTime
                        pending.await().getOrThrow()
                    }
                }
            }.exceptionOrNull()

        assertEquals(LIST_TIMEOUT_MS, elapsed)
        assertTrue(failure is AssertionError)
        assertTrue("lost timeout cause: $failure", failure?.cause is TimeoutCancellationException)
        assertEquals("Permission-held tool stage=$stage timed out; $snapshot", failure?.message)
        assertEquals(1, snapshots)
        assertEquals(
            listOf("event=permission_held_stage stage=$stage status=started", "event=permission_held_stage stage=$stage status=failed"),
            events,
        )
    }

    private class ListRepository(
        private val rows: Flow<List<Conversation>>,
    ) : ConversationRepository by FakeConversationRepository() {
        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> {
            assertEquals(ConversationFilter.All, filter)
            return rows
        }
    }

    private fun conversation(id: String) =
        Conversation(
            id = id,
            name = null,
            cwd = "scratch",
            currentSessionId = "session",
            sessionHistory = emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.fromEpochMilliseconds(0),
        )

    private companion object {
        const val LIST_TIMEOUT_MS = 30_000L
    }
}

package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun state_initialValue_isConversationIdPlaceholderBeforeSubscription() {
        val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
        val vm = ThreadViewModel(handle, FakeConversationRepository())
        // No collect{} — the stateIn(WhileSubscribed) initial value is the conversationId fallback.
        assertEquals(
            ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal"),
            vm.state.value,
        )
    }

    @Test
    fun state_resolvedTitle_isChannelNameForSeededChannel() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = ThreadViewModel(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Personal", vm.state.value.displayName)
            assertEquals("seed-channel-personal", vm.state.value.conversationId)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-discussion-a"))
            val vm = ThreadViewModel(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Untitled discussion", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_isUntitledChannelForUnnamedChannel() =
        runTest {
            val unnamedChannel =
                Conversation(
                    id = "x",
                    name = null,
                    cwd = "~/x",
                    currentSessionId = "x-s1",
                    sessionHistory = listOf("x-s1"),
                    isPromoted = true,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "x"))
            val vm = ThreadViewModel(handle, fixedRepo(listOf(unnamedChannel)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Untitled channel", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "ghost-id"))
            val vm = ThreadViewModel(handle, fixedRepo(emptyList()))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("ghost-id", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_displayName_reemitsOnRename() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = ThreadViewModel(handle, repository)
            val emissions = mutableListOf<ThreadUiState>()
            val collector = launch { vm.state.collect { emissions += it } }
            advanceUntilIdle()
            assertEquals("Personal", vm.state.value.displayName)
            repository.rename("seed-channel-personal", "Personal — renamed")
            advanceUntilIdle()
            assertEquals("Personal — renamed", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_collapsesAbsentConversationIdToEmptyString() {
        val handle = SavedStateHandle(initialState = emptyMap())
        val vm = ThreadViewModel(handle, FakeConversationRepository())
        assertEquals("", vm.state.value.conversationId)
    }

    @Test
    fun sendMessage_blankText_isNoOp() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = ThreadViewModel(handle, repository)
            val observed = mutableListOf<List<ThreadItem>>()
            val collector =
                launch {
                    repository.observeMessages("seed-channel-personal").collect { observed += it }
                }
            advanceUntilIdle()
            val initialCount = observed.last().size
            vm.sendMessage("")
            vm.sendMessage("   \n\t ")
            advanceUntilIdle()
            assertEquals(initialCount, observed.last().size)
            collector.cancel()
        }

    @Test
    fun sendMessage_nonBlankText_appendsToConversation() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = ThreadViewModel(handle, repository)
            val observed = mutableListOf<List<ThreadItem>>()
            val collector =
                launch {
                    repository.observeMessages("seed-channel-personal").collect { observed += it }
                }
            advanceUntilIdle()
            val initialCount = observed.last().size
            vm.sendMessage("Hello world")
            advanceUntilIdle()
            val latest = observed.last()
            assertEquals(initialCount + 1, latest.size)
            val appended = (latest.last() as ThreadItem.MessageItem).message
            assertEquals("Hello world", appended.content)
            assertEquals(Role.User, appended.role)
            assertEquals("seed-session-personal", appended.sessionId)
            collector.cancel()
        }

    // --- helpers ---

    private fun fixedRepo(conversations: List<Conversation>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = flowOf(conversations)

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

            override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

            override suspend fun promote(
                conversationId: String,
                name: String,
                workspace: String?,
            ): Conversation = TODO("not used")

            override suspend fun archive(conversationId: String): Unit = TODO("not used")

            override suspend fun unarchive(conversationId: String): Unit = TODO("not used")

            override suspend fun rename(
                conversationId: String,
                name: String,
            ): Conversation = TODO("not used")

            override suspend fun startNewSession(
                conversationId: String,
                workspace: String?,
            ): Session = TODO("not used")

            override suspend fun changeWorkspace(
                conversationId: String,
                workspace: String,
            ): Session = TODO("not used")

            override suspend fun sendMessage(
                conversationId: String,
                text: String,
            ): Message = TODO("not used")
        }
}

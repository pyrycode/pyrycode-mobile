package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isLoading() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        assertEquals(ChannelListUiState.Loading, vm.state.value)
    }

    @Test
    fun loaded_whenSourceEmitsNonEmpty() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        source.emit(listOf(sampleChannel))
        advanceUntilIdle()
        assertEquals(ChannelListUiState.Loaded(listOf(sampleChannel)), vm.state.value)
        collector.cancel()
    }

    @Test
    fun empty_whenSourceEmitsEmptyList() = runTest {
        val source = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(source))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        source.emit(emptyList())
        advanceUntilIdle()
        assertEquals(ChannelListUiState.Empty, vm.state.value)
        collector.cancel()
    }

    @Test
    fun error_whenSourceFlowThrows() = runTest {
        val erroring = erroringRepo("network down")
        val vm = ChannelListViewModel(erroring)
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
        assertEquals("network down", (state as ChannelListUiState.Error).message)
        collector.cancel()
    }

    @Test
    fun error_messageIsNonBlank_whenExceptionMessageIsNull() = runTest {
        val erroring = erroringRepo(null)
        val vm = ChannelListViewModel(erroring)
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
        assertTrue(
            "message must be non-blank",
            (state as ChannelListUiState.Error).message.isNotBlank(),
        )
        collector.cancel()
    }

    @Test
    fun createDiscussionTapped_createsOneUnpromotedConversation() = runTest {
        val repository = FakeConversationRepository()
        val before = repository.observeConversations(ConversationFilter.Discussions).first()
        val vm = ChannelListViewModel(repository)

        vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
        advanceUntilIdle()

        val after = repository.observeConversations(ConversationFilter.Discussions).first()
        assertEquals(before.size + 1, after.size)
        val created = after.single { it.id !in before.map(Conversation::id).toSet() }
        assertEquals(false, created.isPromoted)
    }

    @Test
    fun createDiscussionTapped_emitsToThreadNavigationWithCreatedId() = runTest {
        val repository = FakeConversationRepository()
        val before = repository.observeConversations(ConversationFilter.Discussions).first()
        val vm = ChannelListViewModel(repository)

        val deferredEvent = async { vm.navigationEvents.first() }
        vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
        advanceUntilIdle()

        val event = deferredEvent.await()
        assertTrue("expected ToThread, was $event", event is ChannelListNavigation.ToThread)
        val after = repository.observeConversations(ConversationFilter.Discussions).first()
        val createdId = after.map(Conversation::id).toSet().minus(
            before.map(Conversation::id).toSet(),
        ).single()
        assertEquals(createdId, (event as ChannelListNavigation.ToThread).conversationId)
        assertNotNull(createdId)
    }

    // --- helpers ---

    private fun stubRepo(source: MutableSharedFlow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source
            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")
            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")
            override suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation = TODO("not used")
            override suspend fun archive(conversationId: String): Unit = TODO("not used")
            override suspend fun rename(conversationId: String, name: String): Conversation = TODO("not used")
            override suspend fun startNewSession(conversationId: String, workspace: String?): Session = TODO("not used")
            override suspend fun changeWorkspace(conversationId: String, workspace: String): Session = TODO("not used")
        }

    private fun erroringRepo(message: String?): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                flow { throw RuntimeException(message) }
            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")
            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")
            override suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation = TODO("not used")
            override suspend fun archive(conversationId: String): Unit = TODO("not used")
            override suspend fun rename(conversationId: String, name: String): Conversation = TODO("not used")
            override suspend fun startNewSession(conversationId: String, workspace: String?): Session = TODO("not used")
            override suspend fun changeWorkspace(conversationId: String, workspace: String): Session = TODO("not used")
        }

    private val sampleChannel = Conversation(
        id = "test-channel",
        name = "Test",
        cwd = "~/test",
        currentSessionId = "s1",
        sessionHistory = listOf("s1"),
        isPromoted = true,
        lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
    )
}

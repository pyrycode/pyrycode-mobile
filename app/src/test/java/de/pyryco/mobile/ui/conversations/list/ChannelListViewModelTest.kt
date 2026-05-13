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
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        assertEquals(ChannelListUiState.Loading, vm.state.value)
    }

    @Test
    fun loaded_whenSourceEmitsNonEmpty() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        channels.emit(listOf(sampleChannel))
        discussions.emit(emptyList())
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Loaded(
                channels = listOf(sampleChannel),
                recentDiscussions = emptyList(),
                recentDiscussionsCount = 0,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun empty_whenSourceEmitsEmptyList() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        channels.emit(emptyList())
        discussions.emit(emptyList())
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Empty(
                recentDiscussions = emptyList(),
                recentDiscussionsCount = 0,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun loaded_carriesDiscussionsCount() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val d1 = sampleDiscussion("d1", Instant.parse("2026-05-12T03:00:00Z"))
        val d2 = sampleDiscussion("d2", Instant.parse("2026-05-12T02:00:00Z"))
        val d3 = sampleDiscussion("d3", Instant.parse("2026-05-12T01:00:00Z"))
        channels.emit(listOf(sampleChannel))
        discussions.emit(listOf(d1, d2, d3))
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Loaded(
                channels = listOf(sampleChannel),
                recentDiscussions = listOf(d1, d2, d3),
                recentDiscussionsCount = 3,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun empty_carriesDiscussionsCount() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val d1 = sampleDiscussion("d1", Instant.parse("2026-05-12T02:00:00Z"))
        val d2 = sampleDiscussion("d2", Instant.parse("2026-05-12T01:00:00Z"))
        channels.emit(emptyList())
        discussions.emit(listOf(d1, d2))
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Empty(
                recentDiscussions = listOf(d1, d2),
                recentDiscussionsCount = 2,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun discussionsCount_updatesReactively() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        channels.emit(listOf(sampleChannel))
        discussions.emit(listOf(sampleDiscussion("d1")))
        advanceUntilIdle()
        val d1 = sampleDiscussion("d1", Instant.parse("2026-05-12T05:00:00Z"))
        val d2 = sampleDiscussion("d2", Instant.parse("2026-05-12T04:00:00Z"))
        val d3 = sampleDiscussion("d3", Instant.parse("2026-05-12T03:00:00Z"))
        val d4 = sampleDiscussion("d4", Instant.parse("2026-05-12T02:00:00Z"))
        val d5 = sampleDiscussion("d5", Instant.parse("2026-05-12T01:00:00Z"))
        discussions.emit(listOf(d1, d2, d3, d4, d5))
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Loaded(
                channels = listOf(sampleChannel),
                recentDiscussions = listOf(d1, d2, d3),
                recentDiscussionsCount = 5,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun loadingPersists_untilBothFlowsEmit() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        assertEquals(ChannelListUiState.Loading, vm.state.value)
        channels.emit(listOf(sampleChannel))
        advanceUntilIdle()
        assertEquals(ChannelListUiState.Loading, vm.state.value)
        discussions.emit(emptyList())
        advanceUntilIdle()
        assertEquals(
            ChannelListUiState.Loaded(
                channels = listOf(sampleChannel),
                recentDiscussions = emptyList(),
                recentDiscussionsCount = 0,
            ),
            vm.state.value,
        )
        collector.cancel()
    }

    @Test
    fun recentDiscussions_isCappedAtThree() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val d1 = sampleDiscussion("d1", Instant.parse("2026-05-12T04:00:00Z"))
        val d2 = sampleDiscussion("d2", Instant.parse("2026-05-12T03:00:00Z"))
        val d3 = sampleDiscussion("d3", Instant.parse("2026-05-12T02:00:00Z"))
        val d4 = sampleDiscussion("d4", Instant.parse("2026-05-12T01:00:00Z"))
        channels.emit(listOf(sampleChannel))
        discussions.emit(listOf(d1, d2, d3, d4))
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Loaded, was $state", state is ChannelListUiState.Loaded)
        val loaded = state as ChannelListUiState.Loaded
        assertEquals(3, loaded.recentDiscussions.size)
        assertEquals(listOf("d1", "d2", "d3"), loaded.recentDiscussions.map(Conversation::id))
        assertEquals(4, loaded.recentDiscussionsCount)
        collector.cancel()
    }

    @Test
    fun recentDiscussions_orderingFollowsUpstream() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        // Upstream emits in a deliberately non-time-sorted order; the VM must not re-sort.
        val a = sampleDiscussion("a", Instant.parse("2026-05-12T01:00:00Z"))
        val b = sampleDiscussion("b", Instant.parse("2026-05-12T03:00:00Z"))
        val c = sampleDiscussion("c", Instant.parse("2026-05-12T02:00:00Z"))
        channels.emit(listOf(sampleChannel))
        discussions.emit(listOf(a, b, c))
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Loaded, was $state", state is ChannelListUiState.Loaded)
        assertEquals(listOf(a, b, c), (state as ChannelListUiState.Loaded).recentDiscussions)
        collector.cancel()
    }

    @Test
    fun error_whenChannelsFlowThrows() = runTest {
        val vm = ChannelListViewModel(erroringRepo("network down", throwOn = ConversationFilter.Channels))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
        assertEquals("network down", (state as ChannelListUiState.Error).message)
        collector.cancel()
    }

    @Test
    fun error_whenDiscussionsFlowThrows() = runTest {
        val vm = ChannelListViewModel(erroringRepo("discussions broke", throwOn = ConversationFilter.Discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        val state = vm.state.value
        assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
        assertEquals("discussions broke", (state as ChannelListUiState.Error).message)
        collector.cancel()
    }

    @Test
    fun error_messageIsNonBlank_whenExceptionMessageIsNull() = runTest {
        val vm = ChannelListViewModel(erroringRepo(null, throwOn = ConversationFilter.Channels))
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
    fun recentDiscussionsTapped_isNoOp() = runTest {
        val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
        val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
        val vm = ChannelListViewModel(stubRepo(channels, discussions))
        val collector = launch { vm.state.collect { } }
        advanceUntilIdle()
        channels.emit(listOf(sampleChannel))
        discussions.emit(listOf(sampleDiscussion("d1")))
        advanceUntilIdle()
        val before = vm.state.value
        vm.onEvent(ChannelListEvent.RecentDiscussionsTapped)
        advanceUntilIdle()
        assertEquals(before, vm.state.value)
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

    private fun stubRepo(
        channels: Flow<List<Conversation>>,
        discussions: Flow<List<Conversation>>,
    ): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                when (filter) {
                    ConversationFilter.Channels -> channels
                    ConversationFilter.Discussions -> discussions
                    ConversationFilter.All -> TODO("not used")
                }
            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")
            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")
            override suspend fun promote(conversationId: String, name: String, workspace: String?): Conversation = TODO("not used")
            override suspend fun archive(conversationId: String): Unit = TODO("not used")
            override suspend fun rename(conversationId: String, name: String): Conversation = TODO("not used")
            override suspend fun startNewSession(conversationId: String, workspace: String?): Session = TODO("not used")
            override suspend fun changeWorkspace(conversationId: String, workspace: String): Session = TODO("not used")
        }

    private fun erroringRepo(
        message: String?,
        throwOn: ConversationFilter,
    ): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                if (filter == throwOn) {
                    flow { throw RuntimeException(message) }
                } else {
                    flow { emit(emptyList()) }
                }
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

    private fun sampleDiscussion(
        id: String,
        lastUsedAt: Instant = Instant.parse("2026-05-12T00:00:00Z"),
    ) = Conversation(
        id = id,
        name = null,
        cwd = "~/scratch",
        currentSessionId = "$id-s1",
        sessionHistory = listOf("$id-s1"),
        isPromoted = false,
        lastUsedAt = lastUsedAt,
    )
}

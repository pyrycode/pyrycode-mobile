package de.pyryco.mobile.ui.conversations.list

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscussionListViewModelTest {
    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isLoading() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = DiscussionListViewModel(stubRepo(source))
            assertEquals(DiscussionListUiState.Loading, vm.state.value)
        }

    @Test
    fun loaded_containsOnlyUnpromoted_inSourceOrder() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val captured = mutableListOf<ConversationFilter>()
            val repo =
                object : ConversationRepository {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> {
                        captured += filter
                        return source
                    }

                    override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

                    override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

                    override suspend fun promote(
                        conversationId: String,
                        name: String,
                        workspace: String?,
                    ): Conversation = TODO("not used")

                    override suspend fun archive(conversationId: String): Unit = TODO("not used")

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
                }
            val vm = DiscussionListViewModel(repo)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val discussions =
                listOf(
                    sampleDiscussion("disc-1"),
                    sampleDiscussion("disc-2"),
                    sampleDiscussion("disc-3"),
                )
            source.emit(discussions)
            advanceUntilIdle()
            assertEquals(DiscussionListUiState.Loaded(discussions), vm.state.value)
            assertTrue(
                "VM must request the Discussions filter, got $captured",
                captured.contains(ConversationFilter.Discussions),
            )
            collector.cancel()
        }

    @Test
    fun empty_whenSourceEmitsEmptyList() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = DiscussionListViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            source.emit(emptyList())
            advanceUntilIdle()
            assertEquals(DiscussionListUiState.Empty, vm.state.value)
            collector.cancel()
        }

    @Test
    fun rowTapped_emitsToThreadNavigation() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = DiscussionListViewModel(stubRepo(source))

            val deferredEvent = async { vm.navigationEvents.first() }
            vm.onEvent(DiscussionListEvent.RowTapped("disc-7"))
            advanceUntilIdle()

            val event = deferredEvent.await()
            assertEquals(DiscussionListNavigation.ToThread("disc-7"), event)
        }

    @Test
    fun saveAsChannelRequested_isNoOp_inPhase0() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = DiscussionListViewModel(stubRepo(source))

            vm.onEvent(DiscussionListEvent.SaveAsChannelRequested("disc-1"))
            advanceUntilIdle()

            val event = withTimeoutOrNull(50) { vm.navigationEvents.first() }
            assertEquals(null, event)
        }

    @Test
    fun error_whenSourceFlowThrows() =
        runTest {
            val erroring = erroringRepo("network down")
            val vm = DiscussionListViewModel(erroring)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is DiscussionListUiState.Error)
            assertEquals("network down", (state as DiscussionListUiState.Error).message)
            collector.cancel()
        }

    @Test
    fun error_messageIsNonBlank_whenExceptionMessageIsNull() =
        runTest {
            val erroring = erroringRepo(null)
            val vm = DiscussionListViewModel(erroring)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is DiscussionListUiState.Error)
            assertTrue(
                "message must be non-blank",
                (state as DiscussionListUiState.Error).message.isNotBlank(),
            )
            collector.cancel()
        }

    // --- helpers ---

    private fun stubRepo(source: MutableSharedFlow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

            override suspend fun promote(
                conversationId: String,
                name: String,
                workspace: String?,
            ): Conversation = TODO("not used")

            override suspend fun archive(conversationId: String): Unit = TODO("not used")

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
        }

    private fun erroringRepo(message: String?): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                flow { throw RuntimeException(message) }

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

            override suspend fun promote(
                conversationId: String,
                name: String,
                workspace: String?,
            ): Conversation = TODO("not used")

            override suspend fun archive(conversationId: String): Unit = TODO("not used")

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
        }

    private fun sampleDiscussion(id: String): Conversation =
        Conversation(
            id = id,
            name = null,
            cwd = DefaultScratchCwd,
            currentSessionId = "s-$id",
            sessionHistory = listOf("s-$id"),
            isPromoted = false,
            lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
        )
}

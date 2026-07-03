package de.pyryco.mobile.ui.conversations.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.ThrowingConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("app_prefs.preferences_pb") },
        )

    private fun TestScope.makeVm(
        repository: ConversationRepository,
        prefs: AppPreferences = AppPreferences(newDataStore()),
    ): ChannelListViewModel = ChannelListViewModel(repository, prefs)

    @Test
    fun initialState_isLoading() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
            assertEquals(ChannelListUiState.Loading, vm.state.value)
        }

    @Test
    fun loaded_whenSourceEmitsNonEmpty() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun empty_whenSourceEmitsEmptyList() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun loaded_carriesDiscussionsCount() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun empty_carriesDiscussionsCount() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun discussionsCount_updatesReactively() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun loadingPersists_untilBothFlowsEmit() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun recentDiscussions_isCappedAtThree() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun recentDiscussions_orderingFollowsUpstream() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun error_whenChannelsFlowThrows() =
        runTest(dispatcher) {
            val vm = makeVm(erroringRepo("network down", throwOn = ConversationFilter.Channels))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
            assertEquals("network down", (state as ChannelListUiState.Error).message)
            collector.cancel()
        }

    @Test
    fun error_whenDiscussionsFlowThrows() =
        runTest(dispatcher) {
            val vm = makeVm(erroringRepo("discussions broke", throwOn = ConversationFilter.Discussions))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is ChannelListUiState.Error)
            assertEquals("discussions broke", (state as ChannelListUiState.Error).message)
            collector.cancel()
        }

    @Test
    fun error_messageIsNonBlank_whenExceptionMessageIsNull() =
        runTest(dispatcher) {
            val vm = makeVm(erroringRepo(null, throwOn = ConversationFilter.Channels))
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
    fun recentDiscussionsTapped_isNoOp() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
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
    fun createDiscussionTapped_createsOneUnpromotedConversation() =
        runTest(dispatcher) {
            val repository = FakeConversationRepository()
            val before = repository.observeConversations(ConversationFilter.Discussions).first()
            val vm = makeVm(repository)

            vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
            advanceUntilIdle()

            val after = repository.observeConversations(ConversationFilter.Discussions).first()
            assertEquals(before.size + 1, after.size)
            val created = after.single { it.id !in before.map(Conversation::id).toSet() }
            assertEquals(false, created.isPromoted)
        }

    @Test
    fun recentDiscussionLastMessages_populatedFromFake_endToEnd() =
        runTest(dispatcher) {
            val vm = makeVm(FakeConversationRepository())
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ChannelListUiState.Loaded)
            val loaded = state as ChannelListUiState.Loaded
            val lastA = loaded.recentDiscussionLastMessages["seed-discussion-a"]
            assertNotNull("seed-discussion-a should have a last message", lastA)
            assertEquals(
                Instant.parse("2026-05-11T14:00:00Z"),
                lastA!!.timestamp,
            )
            assertTrue(
                "seed-discussion-b has no messages — must be absent from the map",
                "seed-discussion-b" !in loaded.recentDiscussionLastMessages,
            )
            collector.cancel()
        }

    @Test
    fun createDiscussionTapped_emitsToThreadNavigationWithCreatedId() =
        runTest(dispatcher) {
            val repository = FakeConversationRepository()
            val before = repository.observeConversations(ConversationFilter.Discussions).first()
            val vm = makeVm(repository)

            val deferredEvent = async { vm.navigationEvents.first() }
            vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
            advanceUntilIdle()

            val event = deferredEvent.await()
            assertTrue("expected ToThread, was $event", event is ChannelListNavigation.ToThread)
            val after = repository.observeConversations(ConversationFilter.Discussions).first()
            val createdId =
                after
                    .map(Conversation::id)
                    .toSet()
                    .minus(
                        before.map(Conversation::id).toSet(),
                    ).single()
            assertEquals(createdId, (event as ChannelListNavigation.ToThread).conversationId)
            assertNotNull(createdId)
        }

    @Test
    fun longPressFab_setsWorkspacePickerVisibleToTrue() =
        runTest(dispatcher) {
            val channels = MutableSharedFlow<List<Conversation>>(replay = 0)
            val discussions = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(stubRepo(channels, discussions))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            channels.emit(listOf(sampleChannel))
            discussions.emit(emptyList())
            advanceUntilIdle()

            vm.onEvent(ChannelListEvent.LongPressFab)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ChannelListUiState.Loaded)
            assertTrue((state as ChannelListUiState.Loaded).workspacePickerVisible)
            collector.cancel()
        }

    @Test
    fun workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility() =
        runTest(dispatcher) {
            val repository = FakeConversationRepository()
            val before = repository.observeConversations(ConversationFilter.Discussions).first()
            val vm = makeVm(repository)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            vm.onEvent(ChannelListEvent.LongPressFab)
            advanceUntilIdle()

            val deferredEvent = async { vm.navigationEvents.first() }
            vm.onEvent(ChannelListEvent.WorkspacePicked("pyry-workspace/my-folder"))
            advanceUntilIdle()

            val event = deferredEvent.await()
            assertTrue("expected ToThread, was $event", event is ChannelListNavigation.ToThread)
            val after = repository.observeConversations(ConversationFilter.Discussions).first()
            val beforeIds = before.map(Conversation::id).toSet()
            val created = (after - before.toSet()).single { it.id !in beforeIds }
            assertEquals("pyry-workspace/my-folder", created.cwd)
            assertEquals(created.id, (event as ChannelListNavigation.ToThread).conversationId)

            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ChannelListUiState.Loaded)
            assertEquals(false, (state as ChannelListUiState.Loaded).workspacePickerVisible)
            collector.cancel()
        }

    @Test
    fun shortPressFab_usesPersistedDefaultWorkspace() =
        runTest(dispatcher) {
            val repository = FakeConversationRepository()
            val before = repository.observeConversations(ConversationFilter.Discussions).first()
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace("~/projects/my-thing")
            advanceUntilIdle()
            val vm = makeVm(repository, prefs = prefs)

            vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
            advanceUntilIdle()

            val after = repository.observeConversations(ConversationFilter.Discussions).first()
            val beforeIds = before.map(Conversation::id).toSet()
            val created = (after - before.toSet()).single { it.id !in beforeIds }
            assertEquals("~/projects/my-thing", created.cwd)
        }

    @Test
    fun longPressPicker_overridesDefaultWorkspace() =
        runTest(dispatcher) {
            val repository = FakeConversationRepository()
            val before = repository.observeConversations(ConversationFilter.Discussions).first()
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace("~/projects/default-path")
            advanceUntilIdle()
            val vm = makeVm(repository, prefs = prefs)

            vm.onEvent(ChannelListEvent.LongPressFab)
            vm.onEvent(ChannelListEvent.WorkspacePicked("~/projects/user-pick"))
            advanceUntilIdle()

            val after = repository.observeConversations(ConversationFilter.Discussions).first()
            val beforeIds = before.map(Conversation::id).toSet()
            val created = (after - before.toSet()).single { it.id !in beforeIds }
            assertEquals("~/projects/user-pick", created.cwd)
        }

    // ---- #490: one-shot repository-call guard (launchGuardedRepoCall) --------------------------

    @Test
    fun createDiscussionPaths_whenCreateThrowsEachHandledType_areSwallowedWithoutCrashing() =
        runTest(dispatcher) {
            // AC #2/#3: both create-discussion launches (CreateDiscussionTapped, WorkspacePicked) swallow the
            // three relay failure types. A throw escaping the launched coroutine reaches the default handler,
            // not runTest (viewModelScope is a separate SupervisorJob), so capture uncaught throws and assert
            // none fired — the only proof the typed catch ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val failures =
                    listOf<Throwable>(
                        IllegalStateException("not connected"),
                        RelayErrorException(code = "server.error", retryable = false, message = "no"),
                        UnsupportedOperationException("not wired"),
                    )
                // One shared DataStore across iterations — newDataStore() reuses a fixed filename, so a
                // per-iteration prefs would collide on tmp.newFile.
                val prefs = AppPreferences(newDataStore())
                for (failure in failures) {
                    val vm = makeVm(ThrowingConversationRepository(failure), prefs)
                    advanceUntilIdle()

                    vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
                    vm.onEvent(ChannelListEvent.WorkspacePicked("pyry-workspace/app"))
                    advanceUntilIdle()
                }
                assertTrue("create-discussion failures must be swallowed, not propagated: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun createDiscussionPaths_whenCreateThrows_doNotNavigateToThread() =
        runTest(dispatcher) {
            // AC #4: the follow-on navigationChannel.send(ToThread) sits inside the guarded block, after the
            // repo call, so a throwing createDiscussion jumps to the catch and no navigation fires.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val vm =
                    makeVm(
                        ThrowingConversationRepository(
                            RelayErrorException(code = "server.error", retryable = false, message = "no"),
                        ),
                    )
                val nav = mutableListOf<ChannelListNavigation>()
                val navCollector = launch { vm.navigationEvents.collect { nav += it } }
                advanceUntilIdle()

                vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
                vm.onEvent(ChannelListEvent.WorkspacePicked("pyry-workspace/app"))
                advanceUntilIdle()

                assertTrue("a failed create must not navigate to a thread: $nav", nav.isEmpty())
                assertTrue("create failures must be swallowed: $uncaught", uncaught.isEmpty())
                navCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
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
                    ConversationFilter.Archived -> TODO("not used")
                }

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

            override fun observeLastMessage(conversationId: String): Flow<Message?> = TODO("not used")

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

    private val sampleChannel =
        Conversation(
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

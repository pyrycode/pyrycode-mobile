package de.pyryco.mobile.ui.settings

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivedDiscussionsViewModelTest {
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
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            assertEquals(ArchivedDiscussionsUiState.Loading, vm.state.value)
        }

    @Test
    fun loaded_passesArchivedFilter() =
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
                }
            val vm = ArchivedDiscussionsViewModel(repo)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            source.emit(emptyList())
            advanceUntilIdle()
            assertTrue(
                "VM must request the Archived filter, got $captured",
                captured.contains(ConversationFilter.Archived),
            )
            collector.cancel()
        }

    @Test
    fun loaded_partitionsByIsPromoted() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val discussionA = sampleArchivedDiscussion("disc-A")
            val channelB = sampleArchivedChannel("chan-B")
            val discussionC = sampleArchivedDiscussion("disc-C")
            source.emit(listOf(discussionA, channelB, discussionC))
            advanceUntilIdle()
            assertEquals(
                ArchivedDiscussionsUiState.Loaded(
                    channels = listOf(channelB),
                    discussions = listOf(discussionA, discussionC),
                    selectedTab = ArchiveTab.Discussions,
                ),
                vm.state.value,
            )
            collector.cancel()
        }

    @Test
    fun loaded_defaultSelectedTab_isDiscussions() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            source.emit(listOf(sampleArchivedDiscussion("disc-1")))
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ArchivedDiscussionsUiState.Loaded)
            assertEquals(
                ArchiveTab.Discussions,
                (state as ArchivedDiscussionsUiState.Loaded).selectedTab,
            )
            collector.cancel()
        }

    @Test
    fun tabSelected_channels_updatesStateOnly() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val discussion = sampleArchivedDiscussion("disc-1")
            val channel = sampleArchivedChannel("chan-1")
            source.emit(listOf(discussion, channel))
            advanceUntilIdle()

            vm.onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Channels))
            advanceUntilIdle()

            assertEquals(
                ArchivedDiscussionsUiState.Loaded(
                    channels = listOf(channel),
                    discussions = listOf(discussion),
                    selectedTab = ArchiveTab.Channels,
                ),
                vm.state.value,
            )
            collector.cancel()
        }

    @Test
    fun tabSelected_discussions_returnsToDefault() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            source.emit(listOf(sampleArchivedDiscussion("disc-1"), sampleArchivedChannel("chan-1")))
            advanceUntilIdle()

            vm.onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Channels))
            advanceUntilIdle()
            vm.onEvent(ArchivedDiscussionsEvent.TabSelected(ArchiveTab.Discussions))
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ArchivedDiscussionsUiState.Loaded)
            assertEquals(
                ArchiveTab.Discussions,
                (state as ArchivedDiscussionsUiState.Loaded).selectedTab,
            )
            collector.cancel()
        }

    @Test
    fun loaded_channels_emptyWhenAllUnpromoted() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val discussion = sampleArchivedDiscussion("disc-A")
            source.emit(listOf(discussion))
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ArchivedDiscussionsUiState.Loaded)
            val loaded = state as ArchivedDiscussionsUiState.Loaded
            assertTrue("channels must be empty, was ${loaded.channels}", loaded.channels.isEmpty())
            assertEquals(listOf(discussion), loaded.discussions)
            collector.cancel()
        }

    @Test
    fun loaded_discussions_emptyWhenAllPromoted() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val channel = sampleArchivedChannel("chan-A")
            source.emit(listOf(channel))
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Loaded, was $state", state is ArchivedDiscussionsUiState.Loaded)
            val loaded = state as ArchivedDiscussionsUiState.Loaded
            assertEquals(listOf(channel), loaded.channels)
            assertTrue("discussions must be empty, was ${loaded.discussions}", loaded.discussions.isEmpty())
            collector.cancel()
        }

    @Test
    fun loaded_bothEmpty_whenSourceEmitsEmptyList() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            source.emit(emptyList())
            advanceUntilIdle()
            assertEquals(
                ArchivedDiscussionsUiState.Loaded(
                    channels = emptyList(),
                    discussions = emptyList(),
                    selectedTab = ArchiveTab.Discussions,
                ),
                vm.state.value,
            )
            collector.cancel()
        }

    @Test
    fun restoreRequested_callsUnarchive_withConversationId() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val recording = recordingRepo(source)
            val vm = ArchivedDiscussionsViewModel(recording)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            vm.onEvent(ArchivedDiscussionsEvent.RestoreRequested("disc-7", "Untitled discussion"))
            advanceUntilIdle()

            assertEquals(listOf("disc-7"), recording.unarchiveCalls)
            collector.cancel()
        }

    @Test
    fun restoreRequested_emitsRestoreSucceededEffect_afterUnarchive() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val recording = recordingRepo(source)
            val vm = ArchivedDiscussionsViewModel(recording)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            val effectDeferred = async { vm.effects.first() }
            advanceUntilIdle()
            vm.onEvent(
                ArchivedDiscussionsEvent.RestoreRequested("disc-7", "old-project-experiments"),
            )
            advanceUntilIdle()

            assertEquals(
                ArchivedDiscussionsEffect.RestoreSucceeded(displayName = "old-project-experiments"),
                effectDeferred.await(),
            )
            collector.cancel()
        }

    @Test
    fun restoreRequested_doesNotEmitEffect_whenUnarchiveThrows() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(throwingUnarchiveRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            vm.onEvent(
                ArchivedDiscussionsEvent.RestoreRequested("disc-7", "old-project-experiments"),
            )
            advanceUntilIdle()

            val emitted =
                withTimeoutOrNull(100.milliseconds) {
                    vm.effects.first()
                }
            assertNull("no effect should be emitted when unarchive throws, was $emitted", emitted)
            collector.cancel()
        }

    @Test
    fun backTapped_doesNotCallUnarchive() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val recording = recordingRepo(source)
            val vm = ArchivedDiscussionsViewModel(recording)
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            vm.onEvent(ArchivedDiscussionsEvent.BackTapped)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), recording.unarchiveCalls)
            collector.cancel()
        }

    @Test
    fun error_whenSourceFlowThrows() =
        runTest {
            val vm = ArchivedDiscussionsViewModel(erroringRepo("network down"))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is ArchivedDiscussionsUiState.Error)
            assertEquals("network down", (state as ArchivedDiscussionsUiState.Error).message)
            collector.cancel()
        }

    @Test
    fun error_messageIsNonBlank_whenExceptionMessageIsNull() =
        runTest {
            val vm = ArchivedDiscussionsViewModel(erroringRepo(null))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Error, was $state", state is ArchivedDiscussionsUiState.Error)
            assertTrue(
                "message must be non-blank",
                (state as ArchivedDiscussionsUiState.Error).message.isNotBlank(),
            )
            collector.cancel()
        }

    // --- helpers ---

    private fun stubRepo(source: MutableSharedFlow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source

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
        }

    private fun erroringRepo(message: String?): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                flow { throw RuntimeException(message) }

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
        }

    private class RecordingRepo(
        private val source: MutableSharedFlow<List<Conversation>>,
    ) : ConversationRepository {
        val unarchiveCalls = mutableListOf<String>()

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

        override fun observeLastMessage(conversationId: String): Flow<Message?> = TODO("not used")

        override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation = TODO("not used")

        override suspend fun archive(conversationId: String): Unit = TODO("not used")

        override suspend fun unarchive(conversationId: String) {
            unarchiveCalls += conversationId
        }

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

    private fun recordingRepo(source: MutableSharedFlow<List<Conversation>>): RecordingRepo = RecordingRepo(source)

    private fun throwingUnarchiveRepo(source: MutableSharedFlow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = source

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = TODO("not used")

            override fun observeLastMessage(conversationId: String): Flow<Message?> = TODO("not used")

            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

            override suspend fun promote(
                conversationId: String,
                name: String,
                workspace: String?,
            ): Conversation = TODO("not used")

            override suspend fun archive(conversationId: String): Unit = TODO("not used")

            override suspend fun unarchive(conversationId: String): Unit = throw RuntimeException("boom")

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

    private fun sampleArchivedDiscussion(id: String): Conversation =
        Conversation(
            id = id,
            name = null,
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "s-$id",
            sessionHistory = listOf("s-$id"),
            isPromoted = false,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )

    private fun sampleArchivedChannel(id: String): Conversation =
        Conversation(
            id = id,
            name = "Archived channel",
            cwd = "~/Workspace/archived",
            currentSessionId = "s-$id",
            sessionHistory = listOf("s-$id"),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
}

package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

                    override suspend fun sendMessage(
                        conversationId: String,
                        text: String,
                    ): Message = TODO("not used")
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
    fun restoreRequested_whenDisconnected_surfacesRestoreFailed() =
        runTest {
            // AC #1/#6: a disconnected restore (repository `live` path throws IllegalStateException)
            // must surface RestoreFailed — never a silent no-op. The VM dropped the old Throwable-wide
            // runCatching, so a leaked non-cancellation throw would escape to the default uncaught
            // handler; capture it and assert none fired — the only proof the typed catch ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val source = MutableSharedFlow<List<Conversation>>(replay = 0)
                val vm =
                    ArchivedDiscussionsViewModel(
                        throwingUnarchiveRepo(source, IllegalStateException("not connected")),
                    )
                val collector = launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.onEvent(
                    ArchivedDiscussionsEvent.RestoreRequested("disc-7", "old-project-experiments"),
                )
                advanceUntilIdle()

                val emitted = withTimeoutOrNull(100.milliseconds) { vm.effects.first() }
                assertEquals(ArchivedDiscussionsEffect.RestoreFailed, emitted)
                assertTrue(
                    "the not-connected throw must be caught, not propagated: $uncaught",
                    uncaught.isEmpty(),
                )
                collector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun restoreRequested_whenServerError_surfacesRestoreFailed_withoutLeakingMessage() =
        runTest {
            // AC #1/#5: unarchive is request/reply, so a server `error` reply surfaces as
            // RelayErrorException — reachable here. It must surface RestoreFailed, and the
            // server-supplied message must never reach the surface: the effect is payload-free,
            // so "leak me" is structurally unable to escape. Capture uncaught throws to prove the
            // typed catch ran rather than the throw propagating.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val source = MutableSharedFlow<List<Conversation>>(replay = 0)
                val vm =
                    ArchivedDiscussionsViewModel(
                        throwingUnarchiveRepo(
                            source,
                            RelayErrorException(code = "server.error", retryable = false, message = "leak me"),
                        ),
                    )
                val collector = launch { vm.state.collect { } }
                advanceUntilIdle()

                vm.onEvent(
                    ArchivedDiscussionsEvent.RestoreRequested("disc-7", "old-project-experiments"),
                )
                advanceUntilIdle()

                val emitted = withTimeoutOrNull(100.milliseconds) { vm.effects.first() }
                assertEquals(ArchivedDiscussionsEffect.RestoreFailed, emitted)
                assertTrue(
                    "the server-error throw must be caught, not propagated: $uncaught",
                    uncaught.isEmpty(),
                )
                collector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun restoreRequested_scopeCancellationMidRestore_isInert_notMisSurfaced() =
        runTest {
            // AC #4: `catch (CancellationException) { throw e }` MUST precede the typed catches —
            // j.u.c.CancellationException extends ISE on the JVM. An unarchive suspends mid-call and
            // viewModelScope teardown must neither crash, emit RestoreSucceeded, nor mis-surface the
            // cancellation as a RestoreFailed.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val source = MutableSharedFlow<List<Conversation>>(replay = 0)
                val gate = CompletableDeferred<Unit>() // never completes — the restore stays suspended
                val entered = CompletableDeferred<Unit>()
                val vm = ArchivedDiscussionsViewModel(GatingUnarchiveRepo(source, gate, entered))
                val effects = mutableListOf<ArchivedDiscussionsEffect>()
                val effectCollector = launch { vm.effects.collect { effects += it } }
                val store = ViewModelStore().apply { put("vm", vm) }

                vm.onEvent(
                    ArchivedDiscussionsEvent.RestoreRequested("disc-7", "old-project-experiments"),
                )
                advanceUntilIdle()
                assertTrue("the restore must be in-flight", entered.isCompleted)

                store.clear() // cancels viewModelScope → the suspended restore throws CancellationException
                advanceUntilIdle()

                assertTrue("cancellation must not surface any effect: $effects", effects.isEmpty())
                assertTrue(
                    "cancellation must propagate, not reach the uncaught handler: $uncaught",
                    uncaught.isEmpty(),
                )
                effectCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
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

    @Test
    fun host_surfacesOwnerLabel_andFollowsRenames() =
        runTest {
            // #715: the header identifies the owning host, and keeps identifying it while the screen
            // stays open — a rename is an emission on the same flow, not a re-navigation.
            val label = MutableStateFlow("Alpha")
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source), label)
            val collector = launch { vm.host.collect { } }
            advanceUntilIdle()

            assertEquals("Alpha", vm.host.value)
            label.value = "Alpha renamed"
            advanceUntilIdle()

            assertEquals("Alpha renamed", vm.host.value)
            collector.cancel()
        }

    @Test
    fun host_isBlank_whenOwnerIsNotSaved() =
        runTest {
            // An unknown owner names no host rather than falling back to another one's. The route
            // guard sends such a destination away; this is the layer below that, proving the label
            // itself cannot borrow an identity.
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source), flowOf(""))
            val collector = launch { vm.host.collect { } }
            advanceUntilIdle()

            assertEquals("", vm.host.value)
            collector.cancel()
        }

    @Test
    fun restoreRequested_callsOnlyTheConstructedRepository_whenIdsCollide() =
        runTest {
            // #715 AC #2, at unit scope: conversation ids are host-local, so two hosts can hold the
            // same one. Restore must reach the repository this view model was constructed with and no
            // other — the guarantee the host-bound facade makes structural, asserted here as behaviour.
            val owner = RecordingRepo(MutableSharedFlow(replay = 0))
            val other = RecordingRepo(MutableSharedFlow(replay = 0))
            val vm = ArchivedDiscussionsViewModel(owner, flowOf("Alpha"))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            vm.onEvent(ArchivedDiscussionsEvent.RestoreRequested("shared-id", "old-project-experiments"))
            advanceUntilIdle()

            assertEquals(listOf("shared-id"), owner.unarchiveCalls)
            assertEquals(emptyList<String>(), other.unarchiveCalls)
            collector.cancel()
        }

    @Test
    fun loaded_eachTabFollowsTheArchiveOrder_mixingStampedAndLegacyRows() =
        runTest {
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = ArchivedDiscussionsViewModel(stubRepo(source))
            val collector = launch { vm.state.collect { } }
            advanceUntilIdle()

            fun archived(
                id: String,
                promoted: Boolean,
                lastUsedAt: String,
                archivedAt: String?,
            ) = sampleArchivedDiscussion(id).copy(
                isPromoted = promoted,
                lastUsedAt = Instant.parse(lastUsedAt),
                archivedAt = archivedAt?.let(Instant::parse),
            )
            // The input order disagrees with both the archive order and the last-use order. Keys:
            // d-new 06-10 (stamped), d-legacy 06-05 (last use), d-old 06-01 (stamped); d-tie-b and
            // d-tie-a share 05-20, one stamped and one legacy, so only the id breaks the tie.
            val dOld = archived("d-old", false, lastUsedAt = "2026-06-20T00:00:00Z", archivedAt = "2026-06-01T00:00:00Z")
            val dTieB = archived("d-tie-b", false, lastUsedAt = "2026-01-01T00:00:00Z", archivedAt = "2026-05-20T00:00:00Z")
            val dLegacy = archived("d-legacy", false, lastUsedAt = "2026-06-05T00:00:00Z", archivedAt = null)
            val dNew = archived("d-new", false, lastUsedAt = "2026-01-02T00:00:00Z", archivedAt = "2026-06-10T00:00:00Z")
            val dTieA = archived("d-tie-a", false, lastUsedAt = "2026-05-20T00:00:00Z", archivedAt = null)
            // Channels sort on their own: c-legacy (06-03) sits between the two stamped channels.
            val cOld = archived("c-old", true, lastUsedAt = "2026-06-30T00:00:00Z", archivedAt = "2026-06-02T00:00:00Z")
            val cLegacy = archived("c-legacy", true, lastUsedAt = "2026-06-03T00:00:00Z", archivedAt = null)
            val cNew = archived("c-new", true, lastUsedAt = "2026-01-01T00:00:00Z", archivedAt = "2026-06-04T00:00:00Z")

            source.emit(listOf(dOld, cOld, dTieB, dLegacy, cLegacy, dNew, cNew, dTieA))
            advanceUntilIdle()

            val loaded = vm.state.value as ArchivedDiscussionsUiState.Loaded
            assertEquals(
                listOf("d-new", "d-legacy", "d-old", "d-tie-a", "d-tie-b"),
                loaded.discussions.map { it.id },
            )
            assertEquals(listOf("c-new", "c-legacy", "c-old"), loaded.channels.map { it.id })
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

            override suspend fun sendMessage(
                conversationId: String,
                text: String,
            ): Message = TODO("not used")
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

            override suspend fun sendMessage(
                conversationId: String,
                text: String,
            ): Message = TODO("not used")
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

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = TODO("not used")
    }

    private fun recordingRepo(source: MutableSharedFlow<List<Conversation>>): RecordingRepo = RecordingRepo(source)

    private fun throwingUnarchiveRepo(
        source: MutableSharedFlow<List<Conversation>>,
        error: Throwable,
    ): ConversationRepository =
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

            override suspend fun unarchive(conversationId: String): Unit = throw error

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

    private class GatingUnarchiveRepo(
        private val source: MutableSharedFlow<List<Conversation>>,
        private val gate: CompletableDeferred<Unit>,
        private val entered: CompletableDeferred<Unit>,
    ) : ConversationRepository {
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
            entered.complete(Unit)
            gate.await() // suspends until viewModelScope cancellation throws CancellationException
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

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = TODO("not used")
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

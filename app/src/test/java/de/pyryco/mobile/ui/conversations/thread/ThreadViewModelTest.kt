package de.pyryco.mobile.ui.conversations.thread

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("prefs_${UUID.randomUUID()}.preferences_pb") },
        )

    @Test
    fun state_initialValue_isConversationIdPlaceholderBeforeSubscription() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initial value is the conversationId fallback.
            assertEquals(
                ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal"),
                vm.state.value,
            )
        }

    @Test
    fun connectionState_initialValue_isConnected() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initialValue matches the fake's seeded value.
            assertEquals(ConnectionState.Connected, vm.connectionState.value)
        }

    @Test
    fun connectionState_reemitsOnSourceChange() =
        runTest {
            val source = FakeConnectionStateSource()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), source)
            val collector = launch { vm.connectionState.collect {} }
            advanceUntilIdle()
            source.emit(ConnectionState.Offline)
            advanceUntilIdle()
            assertEquals(ConnectionState.Offline, vm.connectionState.value)
            collector.cancel()
        }

    @Test
    fun retry_invokesSourceRetry() =
        runTest {
            val source = RecordingConnectionStateSource()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), source)
            vm.retry()
            advanceUntilIdle()
            assertEquals(1, source.retryCallCount)
        }

    @Test
    fun state_resolvedTitle_isChannelNameForSeededChannel() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
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
            val vm = makeVm(handle, FakeConversationRepository())
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
            val vm = makeVm(handle, fixedRepo(listOf(unnamedChannel)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Untitled channel", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "ghost-id"))
            val vm = makeVm(handle, fixedRepo(emptyList()))
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
            val vm = makeVm(handle, repository)
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
    fun state_collapsesAbsentConversationIdToEmptyString() =
        runTest {
            val handle = SavedStateHandle(initialState = emptyMap())
            val vm = makeVm(handle, FakeConversationRepository())
            assertEquals("", vm.state.value.conversationId)
        }

    @Test
    fun state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initial value is the data-class defaults.
            assertEquals(Model.OPUS_4_7, vm.state.value.selectedModel)
            assertEquals("high", vm.state.value.effort)
            assertEquals(0, vm.state.value.tokenPercent)
        }

    @Test
    fun state_postSubscription_emitsDefaultModelEffortAndTokenPercent() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(Model.OPUS_4_7, vm.state.value.selectedModel)
            assertEquals("high", vm.state.value.effort)
            assertEquals(73, vm.state.value.tokenPercent)
            collector.cancel()
        }

    @Test
    fun selectedModel_followsAppPreferencesDefault() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultModel(Model.SONNET_4_6)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.SONNET_4_6 }
                }
            assertEquals(Model.SONNET_4_6, seen.selectedModel)
        }

    @Test
    fun selectedModel_reemitsWhenAppPreferencesDefaultChanges() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val initial =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.OPUS_4_7 }
                }
            assertEquals(Model.OPUS_4_7, initial.selectedModel)
            prefs.setDefaultModel(Model.HAIKU_4_5)
            val updated =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
                }
            assertEquals(Model.HAIKU_4_5, updated.selectedModel)
        }

    @Test
    fun onModelSelected_overridesPerConversationWithoutMutatingPreferences() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            // Sanity: the default starts at OPUS_4_7 (unparseable/missing → OPUS_4_7).
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.OPUS_4_7 }
            }
            vm.onModelSelected(Model.HAIKU_4_5)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
                }
            assertEquals(Model.HAIKU_4_5, seen.selectedModel)
            // The AC's verification line: Settings default is unchanged.
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
        }

    @Test
    fun onModelSelected_overrideWinsOverSubsequentDefaultChange() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.OPUS_4_7 }
            }
            vm.onModelSelected(Model.HAIKU_4_5)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
            }
            prefs.setDefaultModel(Model.SONNET_4_6)
            advanceUntilIdle()
            // The override sticks; the Settings default change does not override it.
            assertEquals(Model.HAIKU_4_5, vm.state.value.selectedModel)
            assertEquals(Model.SONNET_4_6, prefs.defaultModel.first())
        }

    @Test
    fun sendMessage_blankText_isNoOp() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
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
            val vm = makeVm(handle, repository)
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

    @Test
    fun state_workspaceLabel_isScratch_whenCwdIsEmptyString() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            // Pre-condition: createDiscussion(null) yields cwd = "" (see FakeConversationRepository.createDiscussion).
            assertEquals("", freshDiscussion.cwd)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("scratch", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_isScratch_whenCwdIsDefaultScratchSentinel() =
        runTest {
            val scratchDiscussion =
                Conversation(
                    id = "d-scratch",
                    name = null,
                    cwd = DEFAULT_SCRATCH_CWD,
                    currentSessionId = "d-scratch-s1",
                    sessionHistory = listOf("d-scratch-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-scratch"))
            val vm = makeVm(handle, fixedRepo(listOf(scratchDiscussion)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("scratch", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_isBasename_forArbitraryCwd() =
        runTest {
            val boundDiscussion =
                Conversation(
                    id = "d-my-app",
                    name = null,
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "d-my-app-s1",
                    sessionHistory = listOf("d-my-app-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-my-app"))
            val vm = makeVm(handle, fixedRepo(listOf(boundDiscussion)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("my-app", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_items_reflectsObserveMessagesStream() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(
                vm.state.value.items
                    .isNotEmpty(),
            )
            assertTrue(
                vm.state.value.items
                    .first() is ThreadItem.MessageItem,
            )
            collector.cancel()
        }

    @Test
    fun state_chipFields_reflectChannelAndMessagePresence() =
        runTest {
            // Seeded channel: isPromoted = true; hasMessages = true (currentMessages are seeded).
            val channelHandle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val channelRepo = FakeConversationRepository()
            val channelVm = makeVm(channelHandle, channelRepo)
            val channelCollector = launch { channelVm.state.collect {} }
            advanceUntilIdle()
            assertTrue(channelVm.state.value.isPromoted)
            assertTrue(channelVm.state.value.hasMessages)
            channelCollector.cancel()

            // Fresh discussion: isPromoted = false; hasMessages flips from false → true on sendMessage.
            val discussionRepo = FakeConversationRepository()
            val freshDiscussion = discussionRepo.createDiscussion(workspace = null)
            val discussionHandle =
                SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val discussionVm = makeVm(discussionHandle, discussionRepo)
            val discussionCollector = launch { discussionVm.state.collect {} }
            advanceUntilIdle()
            assertFalse(discussionVm.state.value.isPromoted)
            assertFalse(discussionVm.state.value.hasMessages)

            discussionVm.sendMessage("hi")
            advanceUntilIdle()
            assertFalse(discussionVm.state.value.isPromoted)
            assertTrue(discussionVm.state.value.hasMessages)
            discussionCollector.cancel()
        }

    @Test
    fun onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onWorkspaceChipTapped()
            advanceUntilIdle()
            assertTrue(vm.state.value.workspacePickerVisible)

            vm.onWorkspacePicked("pyry-workspace/my-app")
            advanceUntilIdle()

            assertFalse(vm.state.value.workspacePickerVisible)
            val persisted =
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .first { it.id == freshDiscussion.id }
            assertEquals("pyry-workspace/my-app", persisted.cwd)
            collector.cancel()
        }

    @Test
    fun onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onWorkspaceChipTapped()
            advanceUntilIdle()
            assertTrue(vm.state.value.workspacePickerVisible)

            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()

            assertFalse(vm.state.value.workspacePickerVisible)
            val persisted =
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .first { it.id == freshDiscussion.id }
            assertEquals(freshDiscussion.cwd, persisted.cwd)
            collector.cancel()
        }

    // --- helpers ---

    private fun TestScope.makeVm(
        handle: SavedStateHandle,
        repository: ConversationRepository,
        source: ConnectionStateSource = FakeConnectionStateSource(),
        prefs: AppPreferences = AppPreferences(newDataStore()),
    ): ThreadViewModel = ThreadViewModel(handle, repository, source, prefs)

    private class RecordingConnectionStateSource : ConnectionStateSource {
        private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        var retryCallCount: Int = 0
            private set

        override fun observe(): Flow<ConnectionState> = state.asStateFlow()

        override suspend fun retry() {
            retryCallCount++
        }
    }

    private fun fixedRepo(conversations: List<Conversation>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = flowOf(conversations)

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

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

package de.pyryco.mobile.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
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

    private fun makeVm(
        prefs: AppPreferences,
        repo: ConversationRepository = stubRepo(),
    ): SettingsViewModel = SettingsViewModel(prefs, repo)

    @Test
    fun initialState_emitsSystem_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun initialState_mirrorsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setThemeMode(ThemeMode.DARK)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun onSelectTheme_persistsLight() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectTheme(ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, prefs.themeMode.first())
        }

    @Test
    fun onSelectTheme_persistsDark() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, prefs.themeMode.first())
        }

    @Test
    fun onSelectTheme_persistsSystem() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setThemeMode(ThemeMode.DARK)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            vm.onSelectTheme(ThemeMode.SYSTEM)
            advanceUntilIdle()
            assertEquals(ThemeMode.SYSTEM, prefs.themeMode.first())
        }

    @Test
    fun themeMode_flowReEmits_afterOnSelectTheme() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.themeMode.collect { } }
            advanceUntilIdle()
            vm.onSelectTheme(ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, vm.themeMode.value)
            vm.onSelectTheme(ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
            collector.cancel()
        }

    @Test
    fun useWallpaperColors_initialState_emitsFalse_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            assertEquals(false, vm.useWallpaperColors.value)
            collector.cancel()
        }

    @Test
    fun useWallpaperColors_initialState_mirrorsPersistedTrue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setUseWallpaperColors(true)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            assertEquals(true, vm.useWallpaperColors.value)
            collector.cancel()
        }

    @Test
    fun onToggleUseWallpaperColors_persistsAndFlowReEmits() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.useWallpaperColors.collect { } }
            advanceUntilIdle()
            vm.onToggleUseWallpaperColors(true)
            advanceUntilIdle()
            assertEquals(true, prefs.useWallpaperColors.first())
            assertEquals(true, vm.useWallpaperColors.value)
            vm.onToggleUseWallpaperColors(false)
            advanceUntilIdle()
            assertEquals(false, prefs.useWallpaperColors.first())
            assertEquals(false, vm.useWallpaperColors.value)
            collector.cancel()
        }

    @Test
    fun defaultModel_initialState_emitsOpus47_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultModel.collect { } }
            advanceUntilIdle()
            assertEquals(Model.OPUS_4_7, vm.defaultModel.value)
            collector.cancel()
        }

    @Test
    fun defaultModel_initialState_mirrorsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultModel(Model.HAIKU_4_5)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultModel.collect { } }
            advanceUntilIdle()
            assertEquals(Model.HAIKU_4_5, vm.defaultModel.value)
            collector.cancel()
        }

    @Test
    fun onSelectDefaultModel_persistsSonnet46() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectDefaultModel(Model.SONNET_4_6)
            advanceUntilIdle()
            assertEquals(Model.SONNET_4_6, prefs.defaultModel.first())
        }

    @Test
    fun onSelectDefaultModel_persistsHaiku45() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectDefaultModel(Model.HAIKU_4_5)
            advanceUntilIdle()
            assertEquals(Model.HAIKU_4_5, prefs.defaultModel.first())
        }

    @Test
    fun defaultModel_flowReEmits_afterOnSelectDefaultModel() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultModel.collect { } }
            advanceUntilIdle()
            vm.onSelectDefaultModel(Model.SONNET_4_6)
            advanceUntilIdle()
            assertEquals(Model.SONNET_4_6, vm.defaultModel.value)
            vm.onSelectDefaultModel(Model.HAIKU_4_5)
            advanceUntilIdle()
            assertEquals(Model.HAIKU_4_5, vm.defaultModel.value)
            collector.cancel()
        }

    @Test
    fun defaultEffort_initialState_emitsHigh_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultEffort.collect { } }
            advanceUntilIdle()
            assertEquals(Effort.HIGH, vm.defaultEffort.value)
            collector.cancel()
        }

    @Test
    fun defaultEffort_initialState_mirrorsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultEffort(Effort.LOW)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultEffort.collect { } }
            advanceUntilIdle()
            assertEquals(Effort.LOW, vm.defaultEffort.value)
            collector.cancel()
        }

    @Test
    fun onSelectDefaultEffort_persistsLow() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectDefaultEffort(Effort.LOW)
            advanceUntilIdle()
            assertEquals(Effort.LOW, prefs.defaultEffort.first())
        }

    @Test
    fun onSelectDefaultEffort_persistsMax() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onSelectDefaultEffort(Effort.MAX)
            advanceUntilIdle()
            assertEquals(Effort.MAX, prefs.defaultEffort.first())
        }

    @Test
    fun defaultEffort_flowReEmits_afterOnSelectDefaultEffort() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultEffort.collect { } }
            advanceUntilIdle()
            vm.onSelectDefaultEffort(Effort.LOW)
            advanceUntilIdle()
            assertEquals(Effort.LOW, vm.defaultEffort.value)
            vm.onSelectDefaultEffort(Effort.XHIGH)
            advanceUntilIdle()
            assertEquals(Effort.XHIGH, vm.defaultEffort.value)
            collector.cancel()
        }

    @Test
    fun defaultYolo_initialState_emitsFalse_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultYolo.collect { } }
            advanceUntilIdle()
            assertEquals(false, vm.defaultYolo.value)
            collector.cancel()
        }

    @Test
    fun defaultYolo_initialState_mirrorsPersistedTrue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultYolo(true)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultYolo.collect { } }
            advanceUntilIdle()
            assertEquals(true, vm.defaultYolo.value)
            collector.cancel()
        }

    @Test
    fun onToggleDefaultYolo_persistsAndFlowReEmits() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultYolo.collect { } }
            advanceUntilIdle()
            vm.onToggleDefaultYolo(true)
            advanceUntilIdle()
            assertEquals(true, prefs.defaultYolo.first())
            assertEquals(true, vm.defaultYolo.value)
            vm.onToggleDefaultYolo(false)
            advanceUntilIdle()
            assertEquals(false, prefs.defaultYolo.first())
            assertEquals(false, vm.defaultYolo.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_initialValue_isZero() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            assertEquals(0, vm.archivedDiscussionCount.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_reflectsArchivedUnpromotedConversations() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            source.emit(
                listOf(
                    archivedDiscussion("a"),
                    archivedDiscussion("b"),
                    archivedDiscussion("c"),
                ),
            )
            advanceUntilIdle()
            assertEquals(3, vm.archivedDiscussionCount.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_excludesPromotedArchivedConversations() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            source.emit(
                listOf(
                    archivedDiscussion("disc-1"),
                    archivedChannel("chan-1"),
                    archivedChannel("chan-2"),
                ),
            )
            advanceUntilIdle()
            assertEquals(1, vm.archivedDiscussionCount.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_updates_whenConversationArchived() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            source.emit(emptyList())
            advanceUntilIdle()
            assertEquals(0, vm.archivedDiscussionCount.value)
            source.emit(listOf(archivedDiscussion("disc-1")))
            advanceUntilIdle()
            assertEquals(1, vm.archivedDiscussionCount.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_updates_whenConversationUnarchived() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            source.emit(listOf(archivedDiscussion("disc-1")))
            advanceUntilIdle()
            assertEquals(1, vm.archivedDiscussionCount.value)
            source.emit(emptyList())
            advanceUntilIdle()
            assertEquals(0, vm.archivedDiscussionCount.value)
            collector.cancel()
        }

    @Test
    fun archivedDiscussionCount_passesArchivedFilter() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val captured = mutableListOf<ConversationFilter>()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source, captureFiltersInto = captured))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            assertEquals(listOf(ConversationFilter.Archived), captured)
            collector.cancel()
        }

    @Test
    fun defaultWorkspace_initialState_emitsScratchSentinel_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            assertEquals(DEFAULT_SCRATCH_CWD, vm.defaultWorkspace.value)
            collector.cancel()
        }

    @Test
    fun defaultWorkspace_initialState_mirrorsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace("~/Workspace/Projects/foo")
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            assertEquals("~/Workspace/Projects/foo", vm.defaultWorkspace.value)
            collector.cancel()
        }

    @Test
    fun onSelectDefaultWorkspace_persistsPath_andHidesPicker() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace("~/Workspace/Projects/foo")
            advanceUntilIdle()
            assertEquals("~/Workspace/Projects/foo", prefs.defaultWorkspace.first())
            assertEquals(false, vm.workspacePickerVisible.value)
        }

    @Test
    fun defaultWorkspace_flowReEmits_afterOnSelectDefaultWorkspace() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            vm.onSelectDefaultWorkspace("~/Workspace/Projects/foo")
            advanceUntilIdle()
            assertEquals("~/Workspace/Projects/foo", vm.defaultWorkspace.value)
            vm.onSelectDefaultWorkspace("~/Workspace/Projects/bar")
            advanceUntilIdle()
            assertEquals("~/Workspace/Projects/bar", vm.defaultWorkspace.value)
            collector.cancel()
        }

    @Test
    fun onDefaultWorkspaceTapped_setsPickerVisible() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            assertEquals(false, vm.workspacePickerVisible.value)
            vm.onDefaultWorkspaceTapped()
            assertEquals(true, vm.workspacePickerVisible.value)
        }

    @Test
    fun onWorkspacePickerDismissed_hidesPicker_andLeavesPersistedDefaultUnchanged() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            vm.onSelectDefaultWorkspace("~/Workspace/Projects/foo")
            advanceUntilIdle()
            vm.onDefaultWorkspaceTapped()
            assertEquals(true, vm.workspacePickerVisible.value)
            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()
            assertEquals(false, vm.workspacePickerVisible.value)
            assertEquals("~/Workspace/Projects/foo", prefs.defaultWorkspace.first())
            collector.cancel()
        }

    @Test
    fun onWorkspacePickerDismissed_fromDefaultState_neverPersistsNonSentinel() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            vm.onDefaultWorkspaceTapped()
            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()
            assertEquals(false, vm.workspacePickerVisible.value)
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
        }

    private fun stubRepo(
        source: MutableSharedFlow<List<Conversation>> = MutableSharedFlow(replay = 0),
        captureFiltersInto: MutableList<ConversationFilter>? = null,
    ): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> {
                captureFiltersInto?.add(filter)
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

    private fun archivedDiscussion(id: String): Conversation =
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

    private fun archivedChannel(id: String): Conversation =
        Conversation(
            id = id,
            name = "Channel-$id",
            cwd = "~/Workspace/$id",
            currentSessionId = "s-$id",
            sessionHistory = listOf("s-$id"),
            isPromoted = true,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = true,
        )
}

package de.pyryco.mobile.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.preferences.AppPreferences
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

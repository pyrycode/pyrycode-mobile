package de.pyryco.mobile.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.RelayLog
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    // The real sink is `android.util.Log.println`, which throws on plain JVM; capturing it also lets
    // the workspace tests assert what the picker's branches actually log.
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDownMainDispatcher() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
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
        ownerServerId: String = "",
        hosts: Flow<List<SettingsHost>> = MutableStateFlow(emptyList()),
    ): SettingsViewModel = SettingsViewModel(prefs, repo, ownerServerId, hosts)

    private fun host(
        serverId: String,
        displayName: String? = null,
        relayUrl: String = "wss://relay.example/$serverId",
        status: StateFlow<ConnectionStatus> = MutableStateFlow(OFFLINE),
    ) = SettingsHost(serverId, displayName, relayUrl, status)

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
    fun pushNotifications_initialState_emitsTrue_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.pushNotifications.collect { } }
            advanceUntilIdle()
            assertEquals(true, vm.pushNotifications.value)
            collector.cancel()
        }

    @Test
    fun pushNotifications_initialState_mirrorsPersistedFalse() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setNotificationsEnabled(false)
            advanceUntilIdle()
            val vm = makeVm(prefs)
            val collector = launch { vm.pushNotifications.collect { } }
            advanceUntilIdle()
            assertEquals(false, vm.pushNotifications.value)
            collector.cancel()
        }

    @Test
    fun onTogglePushNotifications_persistsAndFlowReEmits() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.pushNotifications.collect { } }
            advanceUntilIdle()
            vm.onTogglePushNotifications(false)
            advanceUntilIdle()
            assertEquals(false, prefs.notificationsEnabled.first())
            assertEquals(false, vm.pushNotifications.value)
            vm.onTogglePushNotifications(true)
            advanceUntilIdle()
            assertEquals(true, prefs.notificationsEnabled.first())
            assertEquals(true, vm.pushNotifications.value)
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

    /**
     * The count asks for exactly [ConversationFilter.Archived] — and, since #723, it is one of two
     * streams this VM asks the repository for. The chains are built in the constructor, so both
     * filters are recorded here regardless of what is collected: `All` is
     * [SettingsViewModel.defaultWorkspaceLabel]'s arm, the only filter admitting archived rows, and
     * asserting the exact pair keeps either arm's filter from drifting unnoticed.
     */
    @Test
    fun archivedDiscussionCount_passesArchivedFilter() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val captured = mutableListOf<ConversationFilter>()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, stubRepo(source, captureFiltersInto = captured))
            val collector = launch { vm.archivedDiscussionCount.collect { } }
            advanceUntilIdle()
            assertEquals(listOf(ConversationFilter.All, ConversationFilter.Archived), captured)
            collector.cancel()
        }

    @Test
    fun defaultWorkspace_initialState_emitsScratchSentinel_whenNoStoredValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            assertEquals(DEFAULT_SCRATCH_CWD, vm.defaultWorkspace.value)
            collector.cancel()
        }

    /**
     * Each destination reads its own host's stored value out of one shared store, and the ids are
     * matched exactly: `Host` and `host` are two hosts, as the preference layer's key rule requires.
     */
    @Test
    fun defaultWorkspace_initialState_mirrorsItsOwnHostsPersistedValue() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            prefs.setDefaultWorkspace(OTHER, BAR).getOrThrow()
            advanceUntilIdle()
            val vm = makeVm(prefs, ownerServerId = OWNER)
            val other = makeVm(prefs, ownerServerId = OTHER)
            val collectors = listOf(vm, other).map { launch { it.defaultWorkspace.collect { } } }
            advanceUntilIdle()
            assertEquals(FOO, vm.defaultWorkspace.value)
            assertEquals(BAR, other.defaultWorkspace.value)
            collectors.forEach { it.cancel() }
        }

    /**
     * The AC2 core: two destinations over one store each write under their own host key, neither
     * moves the other's value, and the app-wide unqualified default is left where it was.
     */
    @Test
    fun onSelectDefaultWorkspace_persistsUnderItsOwnHostKey_andClosesPicker() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            val other = makeVm(prefs, ownerServerId = OTHER)
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(FOO)
            other.onDefaultWorkspaceTapped()
            other.onSelectDefaultWorkspace(BAR)
            advanceUntilIdle()
            assertEquals(FOO, prefs.defaultWorkspace(OWNER).first())
            assertEquals(BAR, prefs.defaultWorkspace(OTHER).first())
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
            assertEquals(null, vm.workspacePickerServerId.value)
            assertEquals(null, other.workspacePickerServerId.value)
        }

    @Test
    fun defaultWorkspace_flowReEmits_afterOnSelectDefaultWorkspace() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(FOO)
            advanceUntilIdle()
            assertEquals(FOO, vm.defaultWorkspace.value)
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(BAR)
            advanceUntilIdle()
            assertEquals(BAR, vm.defaultWorkspace.value)
            collector.cancel()
        }

    /** The target the route binds the picker's repository to is this destination's own owner. */
    @Test
    fun onDefaultWorkspaceTapped_exposesItsOwnerAsThePickerTarget() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            assertEquals(null, vm.workspacePickerServerId.value)
            vm.onDefaultWorkspaceTapped()
            assertEquals(OWNER, vm.workspacePickerServerId.value)
        }

    @Test
    fun onWorkspacePickerDismissed_clearsTheTarget_andLeavesPersistedDefaultUnchanged() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(FOO)
            advanceUntilIdle()
            vm.onDefaultWorkspaceTapped()
            assertEquals(OWNER, vm.workspacePickerServerId.value)
            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()
            assertEquals(null, vm.workspacePickerServerId.value)
            assertEquals(FOO, prefs.defaultWorkspace(OWNER).first())
            collector.cancel()
        }

    @Test
    fun onWorkspacePickerDismissed_fromDefaultState_neverPersistsNonSentinel() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            vm.onDefaultWorkspaceTapped()
            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()
            assertEquals(null, vm.workspacePickerServerId.value)
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace(OWNER).first())
        }

    /**
     * A pick that arrives with no picker open — a cancelled sheet whose callback still fires, or a
     * second pick behind the first — writes nothing. The handler reads the target off the pending
     * value rather than off the captured owner precisely so this case has somewhere to fail.
     */
    @Test
    fun onSelectDefaultWorkspace_withNoPickerOpen_writesNothing() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = OWNER)
            vm.onDefaultWorkspaceTapped()
            vm.onWorkspacePickerDismissed()
            vm.onSelectDefaultWorkspace(FOO)
            advanceUntilIdle()
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace(OWNER).first())
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(FOO)
            vm.onSelectDefaultWorkspace(BAR)
            advanceUntilIdle()
            assertEquals(FOO, prefs.defaultWorkspace(OWNER).first())
        }

    /**
     * A destination that captured no host has no host to pick folders on: the picker never opens, so
     * the route never has a repository to bind and the sheet can never fall back to the
     * compatibility one. Nothing is written under the blank key or the app-wide one.
     */
    @Test
    fun blankOwner_neverOpensThePicker_andWritesNothing() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs)
            val collector = launch { vm.defaultWorkspace.collect { } }
            advanceUntilIdle()
            assertEquals(DEFAULT_SCRATCH_CWD, vm.defaultWorkspace.value)
            vm.onDefaultWorkspaceTapped()
            assertEquals(null, vm.workspacePickerServerId.value)
            vm.onSelectDefaultWorkspace(FOO)
            advanceUntilIdle()
            assertEquals(DEFAULT_SCRATCH_CWD, vm.defaultWorkspace.value)
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("").first())
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
            collector.cancel()
        }

    /**
     * Both picker branches log an event and a static code only — never the server id, the picked
     * path or any other value — so nothing here could reach a debug build's logcat.
     */
    @Test
    fun workspacePickerLogs_carryNoServerIdAndNoPath() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            makeVm(prefs).onDefaultWorkspaceTapped()
            val vm = makeVm(prefs, ownerServerId = OWNER)
            vm.onDefaultWorkspaceTapped()
            vm.onSelectDefaultWorkspace(FOO)
            vm.onSelectDefaultWorkspace(BAR)
            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()
            val picker = logs.filter { it.contains("workspace_picker") || it.contains("workspace_default") }
            assertEquals(
                listOf(
                    "event=settings_workspace_picker_rejected code=no_owner",
                    "event=settings_workspace_picker_opened",
                    "event=settings_workspace_default_rejected code=no_open_picker",
                    "event=settings_workspace_picker_dismissed",
                ),
                picker.filterNot { it.startsWith("event=workspace_default_set") },
            )
        }

    /** A bound default named on this host reads back that host's chosen name, not a basename. */
    @Test
    fun defaultWorkspaceLabel_namesTheBoundPath_fromItsOwnHostsConversation() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(source), ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspaceLabel.collect { } }
            advanceUntilIdle()
            source.emit(listOf(conversationAt(BAR, label = "Bar"), conversationAt(FOO, label = "Foo")))
            advanceUntilIdle()
            assertEquals("Foo", vm.defaultWorkspaceLabel.value)
            collector.cancel()
        }

    /**
     * An archived conversation still names its workspace, so a row matching only one must still read
     * that name. That this reaches production depends on the arm asking for [ConversationFilter.All],
     * the only filter `RemoteConversationRepository.project` admits archived rows under — pinned by
     * `archivedDiscussionCount_passesArchivedFilter`, which asserts this VM's exact filter pair.
     */
    @Test
    fun defaultWorkspaceLabel_readsEveryConversation_soAnArchivedOneStillNames() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(source), ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspaceLabel.collect { } }
            advanceUntilIdle()
            source.emit(listOf(conversationAt(FOO, label = "Foo", archived = true)))
            advanceUntilIdle()
            assertEquals("Foo", vm.defaultWorkspaceLabel.value)
            collector.cancel()
        }

    /**
     * Nothing at that path, and a blank name at it, both read as unnamed — a blank one must not mask
     * a real name later in the list, which is why the scan selects on non-blank rather than non-null.
     */
    @Test
    fun defaultWorkspaceLabel_isNull_forAnUnmatchedCwdOrABlankLabel() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(source), ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspaceLabel.collect { } }
            advanceUntilIdle()
            source.emit(listOf(conversationAt(BAR, label = "Bar")))
            advanceUntilIdle()
            assertEquals(null, vm.defaultWorkspaceLabel.value)
            source.emit(listOf(conversationAt(FOO, label = "   ", id = "blank"), conversationAt(FOO, label = "Foo")))
            advanceUntilIdle()
            assertEquals("Foo", vm.defaultWorkspaceLabel.value)
            collector.cancel()
        }

    /**
     * AC2's live half, across one VM and one collector: a rename and then a clear on this host's own
     * conversation move the row, and neither touches the stored path the picker wrote.
     */
    @Test
    fun defaultWorkspaceLabel_followsRenameAndClear_withoutRewritingTheSavedPath() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(source), ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspaceLabel.collect { } }
            advanceUntilIdle()
            source.emit(listOf(conversationAt(FOO, label = "Foo")))
            advanceUntilIdle()
            assertEquals("Foo", vm.defaultWorkspaceLabel.value)
            source.emit(listOf(conversationAt(FOO, label = "Renamed")))
            advanceUntilIdle()
            assertEquals("Renamed", vm.defaultWorkspaceLabel.value)
            source.emit(listOf(conversationAt(FOO, label = null)))
            advanceUntilIdle()
            assertEquals(null, vm.defaultWorkspaceLabel.value)
            assertEquals(FOO, prefs.defaultWorkspace(OWNER).first())
            collector.cancel()
        }

    /**
     * The sentinel and the empty string mean *no bound workspace*, and every unbound conversation
     * shares them — so a name found at one of them belongs to some other conversation, never to this
     * host's default. The row reads `scratch` through the shared rule instead of borrowing it.
     */
    @Test
    fun defaultWorkspaceLabel_isNull_forAnUnboundDefault() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val source = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(source), ownerServerId = OWNER)
            val collector = launch { vm.defaultWorkspaceLabel.collect { } }
            advanceUntilIdle()
            source.emit(
                listOf(
                    conversationAt(DEFAULT_SCRATCH_CWD, label = "Scratchpad"),
                    conversationAt("", label = "Nowhere", id = "empty"),
                ),
            )
            advanceUntilIdle()
            assertEquals(null, vm.defaultWorkspaceLabel.value)
            prefs.setDefaultWorkspace(OWNER, "").getOrThrow()
            advanceUntilIdle()
            assertEquals(null, vm.defaultWorkspaceLabel.value)
            collector.cancel()
        }

    /**
     * Two hosts whose defaults are the same directory: each row reads only its own host's repository,
     * so neither can show the other's name. This is also the at-this-layer form of "a compatibility
     * selection change cannot retarget the row" — selection is not observable here at all, because
     * the repository is bound to the destination's captured owner at construction.
     */
    @Test
    fun defaultWorkspaceLabel_cannotReadAnotherHostsLabelForTheSameCwd() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultWorkspace(OWNER, FOO).getOrThrow()
            prefs.setDefaultWorkspace(OTHER, FOO).getOrThrow()
            val ownerSource = MutableSharedFlow<List<Conversation>>(replay = 0)
            val otherSource = MutableSharedFlow<List<Conversation>>(replay = 0)
            val vm = makeVm(prefs, repo = stubRepo(ownerSource), ownerServerId = OWNER)
            val other = makeVm(prefs, repo = stubRepo(otherSource), ownerServerId = OTHER)
            val collectors =
                listOf(vm, other).map { target -> launch { target.defaultWorkspaceLabel.collect { } } }
            advanceUntilIdle()
            ownerSource.emit(listOf(conversationAt(FOO, label = "Mine")))
            otherSource.emit(listOf(conversationAt(FOO, label = "Theirs")))
            advanceUntilIdle()
            assertEquals("Mine", vm.defaultWorkspaceLabel.value)
            assertEquals("Theirs", other.defaultWorkspaceLabel.value)
            collectors.forEach { it.cancel() }
        }

    @Test
    fun connection_listsEverySavedHostAndMarksTheOwner() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm =
                makeVm(
                    prefs,
                    ownerServerId = "B",
                    hosts =
                        MutableStateFlow(
                            listOf(
                                host("A", "Alpha", "wss://a.example", MutableStateFlow(CONNECTED)),
                                host("B", "Bravo", "wss://b.example", MutableStateFlow(OFFLINE)),
                            ),
                        ),
                )
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(false, loaded.ownerMissing)
            assertEquals(listOf("A", "B"), loaded.hosts.map { it.serverId })
            val alpha = loaded.hosts[0]
            assertEquals("Alpha", alpha.name)
            assertEquals("wss://a.example", alpha.relayUrl)
            assertEquals(CONNECTED, alpha.status)
            assertEquals(false, alpha.isOwner)
            val bravo = loaded.hosts[1]
            assertEquals("Bravo", bravo.name)
            assertEquals("wss://b.example", bravo.relayUrl)
            assertEquals(OFFLINE, bravo.status)
            assertEquals(true, bravo.isOwner)
            collector.cancel()
        }

    /** Each row carries its own host's status, so one host's change cannot move another's row. */
    @Test
    fun connection_followsEachHostsOwnStatusIndependently() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val alphaStatus = MutableStateFlow(OFFLINE)
            val vm =
                makeVm(
                    prefs,
                    ownerServerId = "B",
                    hosts =
                        MutableStateFlow(
                            listOf(
                                host("A", "Alpha", status = alphaStatus),
                                host("B", "Bravo", status = MutableStateFlow(OFFLINE)),
                            ),
                        ),
                )
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            alphaStatus.value = CONNECTED
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(CONNECTED, loaded.hosts.single { it.serverId == "A" }.status)
            assertEquals(OFFLINE, loaded.hosts.single { it.serverId == "B" }.status)
            collector.cancel()
        }

    /**
     * The at-this-layer form of "a compatibility-selection change while Settings is open": selection
     * is not observable here at all, so what a change of it produces is a host-list re-emission. The
     * owned row must not move with it, and must not pick up the other host's status.
     */
    @Test
    fun connection_keepsOwnerAcrossHostListReemission() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val a = host("A", "Alpha", "wss://a.example", MutableStateFlow(CONNECTED))
            val b = host("B", "Bravo", "wss://b.example", MutableStateFlow(OFFLINE))
            val hosts = MutableStateFlow(listOf(a, b))
            val vm = makeVm(prefs, ownerServerId = "B", hosts = hosts)
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            hosts.value = listOf(b, a)
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(listOf("B", "A"), loaded.hosts.map { it.serverId })
            val owned = loaded.hosts.single { it.isOwner }
            assertEquals("B", owned.serverId)
            assertEquals(OFFLINE, owned.status)
            collector.cancel()
        }

    @Test
    fun connection_followsRenameAndStatusChangeWhileSubscribed() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val status = MutableStateFlow(OFFLINE)
            val hosts = MutableStateFlow(listOf(host("B", "Bravo", "wss://b.example", status)))
            val vm = makeVm(prefs, ownerServerId = "B", hosts = hosts)
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            status.value = CONNECTED
            advanceUntilIdle()
            assertEquals(CONNECTED, vm.rows().single().status)
            hosts.value = listOf(host("B", "Renamed", "wss://b.example", status))
            advanceUntilIdle()
            val renamed = vm.rows().single()
            assertEquals("Renamed", renamed.name)
            assertEquals(CONNECTED, renamed.status)
            assertEquals(true, renamed.isOwner)
            collector.cancel()
        }

    @Test
    fun connection_namesUnnamedHostByItsServerId() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val hosts = MutableStateFlow(listOf(host("B", displayName = null)))
            val vm = makeVm(prefs, ownerServerId = "B", hosts = hosts)
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            assertEquals("B", vm.rows().single().name)
            hosts.value = listOf(host("B", displayName = "   "))
            advanceUntilIdle()
            assertEquals("B", vm.rows().single().name)
            collector.cancel()
        }

    /** A vanished owner is reported as such, and does not take the other saved hosts with it. */
    @Test
    fun connection_reportsOwnerMissingButStillListsSavedHosts() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm =
                makeVm(
                    prefs,
                    ownerServerId = "ghost",
                    hosts = MutableStateFlow(listOf(host("A", "Alpha"), host("B", "Bravo"))),
                )
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(true, loaded.ownerMissing)
            assertEquals(listOf("A", "B"), loaded.hosts.map { it.serverId })
            assertEquals(emptyList<SettingsHostRow>(), loaded.hosts.filter { it.isOwner })
            collector.cancel()
        }

    @Test
    fun connection_reportsOwnerMissingWhenTheOwnerIsRemovedWhileOpen() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val hosts = MutableStateFlow(listOf(host("A", "Alpha"), host("B", "Bravo")))
            val vm = makeVm(prefs, ownerServerId = "B", hosts = hosts)
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            assertEquals("B", vm.rows().single { it.isOwner }.serverId)
            hosts.value = listOf(host("A", "Alpha"))
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(true, loaded.ownerMissing)
            assertEquals(listOf("A"), loaded.hosts.map { it.serverId })
            collector.cancel()
        }

    /**
     * A destination that captured no host owns nothing that could be missing, so it lists every
     * saved host with no row owned — and never claims one is no longer paired.
     */
    @Test
    fun connection_listsHostsWithoutOwningOneWhenTheDestinationOwnsNoHost() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val hosts = MutableStateFlow(emptyList<SettingsHost>())
            val vm = makeVm(prefs, ownerServerId = "", hosts = hosts)
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            hosts.value = listOf(host("A", "Alpha"))
            advanceUntilIdle()
            val loaded = vm.connection.value as SettingsConnectionState.Loaded
            assertEquals(false, loaded.ownerMissing)
            assertEquals(listOf("A"), loaded.hosts.map { it.serverId })
            assertEquals(emptyList<SettingsHostRow>(), loaded.hosts.filter { it.isOwner })
            collector.cancel()
        }

    /**
     * `combine` over an empty array never emits, so without the empty short-circuit this state stays
     * `Resolving` forever and an unpaired phone silently loses its no-host copy.
     */
    @Test
    fun connection_reportsNoHostsForAnEmptyList() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            listOf("", "B").forEach { owner ->
                val vm = makeVm(prefs, ownerServerId = owner, hosts = MutableStateFlow(emptyList()))
                val collector = launch { vm.connection.collect { } }
                advanceUntilIdle()
                val loaded = vm.connection.value as SettingsConnectionState.Loaded
                assertEquals(emptyList<SettingsHostRow>(), loaded.hosts)
                collector.cancel()
            }
        }

    @Test
    fun connection_resolvesBeforeTheFirstHostEmission() =
        runTest(dispatcher) {
            val prefs = AppPreferences(newDataStore())
            val vm = makeVm(prefs, ownerServerId = "B", hosts = MutableSharedFlow(replay = 0))
            val collector = launch { vm.connection.collect { } }
            advanceUntilIdle()
            assertEquals(SettingsConnectionState.Resolving, vm.connection.value)
            collector.cancel()
        }

    private fun SettingsViewModel.rows(): List<SettingsHostRow> = (connection.value as SettingsConnectionState.Loaded).hosts

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

    /** A conversation bound to [cwd], optionally carrying the host's chosen name for it (#723). */
    private fun conversationAt(
        cwd: String,
        label: String?,
        archived: Boolean = false,
        id: String = "c-$cwd",
    ): Conversation =
        Conversation(
            id = id,
            name = null,
            cwd = cwd,
            currentSessionId = "s-$id",
            sessionHistory = listOf("s-$id"),
            isPromoted = false,
            lastUsedAt = Instant.parse("2026-04-15T12:00:00Z"),
            archived = archived,
            workspaceLabel = label,
        )

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

    private companion object {
        val CONNECTED = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
        val OFFLINE = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)

        // Two ids that differ only in case, so the workspace tests pin the preference layer's exact
        // case-sensitive keying rather than merely two different strings.
        const val OWNER = "Host"
        const val OTHER = "host"
        const val FOO = "~/Workspace/Projects/foo"
        const val BAR = "~/Workspace/Projects/bar"
    }
}

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
    }
}

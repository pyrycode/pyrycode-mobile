package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.preferences.ThemeMode
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One saved host's identity plus its own live two-part status, as this destination needs them.
 *
 * Four scalars and a status flow — deliberately **not** a `PairedServerEntry` or a `PairedServer`.
 * Those records carry the pairing token and the server static key, and unlike `PairedServer` this
 * class has no redacting `toString` to stop a crash trace from rendering whatever it holds. The
 * projection that builds one copies the three display fields explicitly for that reason; never add
 * a record-typed field here.
 */
data class SettingsHost(
    val serverId: String,
    val displayName: String?,
    val relayUrl: String,
    val status: StateFlow<ConnectionStatus>,
)

/** What a Settings destination's captured owner resolves to against the saved hosts. */
sealed interface SettingsHostState {
    /** A non-blank owner whose host list has not arrived yet: render nothing rather than a guess. */
    data object Resolving : SettingsHostState

    /** This destination owns no host, because none was paired when it was opened. */
    data object Unpaired : SettingsHostState

    /** The captured owner is not among the saved hosts. Never resolves to a different host. */
    data object Unknown : SettingsHostState

    /** The captured owner, with the four facts the Connection section displays inertly. */
    data class Owned(
        val serverId: String,
        val displayName: String?,
        val relayUrl: String,
        val status: ConnectionStatus,
    ) : SettingsHostState {
        /** Its local name, or its server id when unnamed — resolved here so one place owns it. */
        val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: serverId
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
    /** The server id the gear captured into this destination's route. Blank means it owns none. */
    ownerServerId: String,
    /** Every saved host's identity and status; the owner is resolved out of it, never selected. */
    hosts: Flow<List<SettingsHost>>,
) : ViewModel() {
    /**
     * The captured owner, resolved by exact case-sensitive server id — the same equality
     * `PairedServerCollectionStore.loadById` and the registry's entry map use, so this cannot match
     * a host either of those would miss.
     *
     * A blank owner short-circuits to [SettingsHostState.Unpaired] without ever consulting [hosts]:
     * it is correct on the first frame (the state AC3 names for an unpaired phone), and it makes a
     * blank-vs-blank id match structurally impossible rather than merely unreachable.
     *
     * Unlike the `connectionStatus` flow this replaced (#398, forwarded verbatim because its
     * upstream was already hot), this is a derived projection over a cold join and therefore does
     * take the `stateIn(WhileSubscribed)` lift its eight preference siblings use. [flatMapLatest]
     * keeps at most one owner-status collector alive: a removal is itself a [hosts] emission, so the
     * departing host's collector is cancelled by the same event that invalidates it.
     */
    val host: StateFlow<SettingsHostState> =
        if (ownerServerId.isBlank()) {
            MutableStateFlow<SettingsHostState>(SettingsHostState.Unpaired)
        } else {
            hosts
                .flatMapLatest { saved ->
                    val owner = saved.firstOrNull { it.serverId == ownerServerId }
                    owner
                        ?.status
                        ?.map {
                            SettingsHostState.Owned(owner.serverId, owner.displayName, owner.relayUrl, it)
                        }
                        ?: flowOf(SettingsHostState.Unknown)
                }
                // Supportive-metadata projections swallow upstream errors, as the archived count does:
                // an unreadable host list must not tear the screen down, and "no host's identity" is
                // the honest thing to show when we cannot prove which host this is.
                .catch { emit(SettingsHostState.Unknown) }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = SettingsHostState.Resolving,
                )
        }

    val themeMode: StateFlow<ThemeMode> =
        appPreferences.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThemeMode.SYSTEM,
        )

    val useWallpaperColors: StateFlow<Boolean> =
        appPreferences.useWallpaperColors.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val defaultModel: StateFlow<Model> =
        appPreferences.defaultModel.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Model.OPUS_4_7,
        )

    val defaultEffort: StateFlow<Effort> =
        appPreferences.defaultEffort.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = Effort.HIGH,
        )

    val defaultYolo: StateFlow<Boolean> =
        appPreferences.defaultYolo.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = false,
        )

    val pushNotifications: StateFlow<Boolean> =
        appPreferences.notificationsEnabled.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = true,
        )

    val defaultWorkspace: StateFlow<String> =
        appPreferences.defaultWorkspace.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = DEFAULT_SCRATCH_CWD,
        )

    private val pendingWorkspacePicker = MutableStateFlow(false)
    val workspacePickerVisible: StateFlow<Boolean> = pendingWorkspacePicker.asStateFlow()

    val archivedDiscussionCount: StateFlow<Int> =
        conversationRepository
            .observeConversations(ConversationFilter.Archived)
            .map { conversations -> conversations.count { !it.isPromoted } }
            .catch { emit(0) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = 0,
            )

    fun onSelectTheme(mode: ThemeMode) {
        viewModelScope.launch { appPreferences.setThemeMode(mode) }
    }

    fun onToggleUseWallpaperColors(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setUseWallpaperColors(enabled) }
    }

    fun onSelectDefaultModel(model: Model) {
        viewModelScope.launch { appPreferences.setDefaultModel(model) }
    }

    fun onSelectDefaultEffort(effort: Effort) {
        viewModelScope.launch { appPreferences.setDefaultEffort(effort) }
    }

    fun onToggleDefaultYolo(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setDefaultYolo(enabled) }
    }

    fun onTogglePushNotifications(enabled: Boolean) {
        viewModelScope.launch { appPreferences.setNotificationsEnabled(enabled) }
    }

    fun onDefaultWorkspaceTapped() {
        pendingWorkspacePicker.value = true
    }

    fun onSelectDefaultWorkspace(path: String) {
        pendingWorkspacePicker.value = false
        viewModelScope.launch { appPreferences.setDefaultWorkspace(path) }
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = false
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

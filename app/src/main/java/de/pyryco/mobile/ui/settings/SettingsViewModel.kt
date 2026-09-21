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
import kotlinx.coroutines.flow.combine
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

/**
 * One saved host as the Connection section draws it: resolved display text and its own status.
 *
 * Five scalars, for the reason [SettingsHost] gives about itself — neither this class nor the state
 * that holds a list of them has a redacting `toString`, and a crash trace renders whatever they
 * hold. Never give it a record-typed field.
 */
data class SettingsHostRow(
    val serverId: String,
    val displayName: String?,
    val relayUrl: String,
    val status: ConnectionStatus,
    /** True for the host whose Settings this is: the one row that is inert, because it is here. */
    val isOwner: Boolean,
) {
    /** Its local name, or its server id when unnamed — resolved here so one place owns it. */
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: serverId
}

/** Every saved host, and what this destination's own captured owner resolved to among them. */
sealed interface SettingsConnectionState {
    /** The host list has not arrived yet: render nothing rather than flash the wrong copy. */
    data object Resolving : SettingsConnectionState

    /**
     * @param hosts every saved host in the order the store holds them, at most one of them owned.
     * @param ownerMissing a **non-blank** captured owner that no saved host matches. False for a
     *   destination that captured none: it owns nothing that could be missing, and must not claim
     *   a host is no longer paired.
     */
    data class Loaded(
        val hosts: List<SettingsHostRow>,
        val ownerMissing: Boolean,
    ) : SettingsConnectionState
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
     * Every saved host with its own live status, and the destination's owner marked among them
     * (#750, widening #749's single-host resolution).
     *
     * The owner is matched by exact case-sensitive server id — the same equality
     * `PairedServerCollectionStore.loadById` and the registry's entry map use, so this cannot match
     * a host either of those would miss. The blank check is what keeps a destination that captured
     * no host from matching a host whose id is somehow blank too; it no longer short-circuits the
     * whole flow, because such a destination still lists every saved host.
     *
     * Order is the store's own, as `RelayConnectionRegistry.reconcile` builds it: sorting here would
     * make the section reshuffle whenever a save moves a record last.
     *
     * Unlike the `connectionStatus` flow #749 replaced (#398, forwarded verbatim because its
     * upstream was already hot), this is a derived projection over a cold join and therefore does
     * take the `stateIn(WhileSubscribed)` lift its eight preference siblings use. [flatMapLatest]
     * keeps at most one generation of status collectors alive: an unpair is itself a [hosts]
     * emission, so the departing host's collector is cancelled by the same event that invalidates it.
     */
    val connection: StateFlow<SettingsConnectionState> =
        hosts
            .flatMapLatest { saved ->
                val ownerMissing = ownerServerId.isNotBlank() && saved.none { it.serverId == ownerServerId }
                // `combine` over an empty array never emits, which would leave this at `Resolving`
                // forever and silently cost an unpaired phone its no-host copy.
                if (saved.isEmpty()) {
                    flowOf(SettingsConnectionState.Loaded(emptyList(), ownerMissing))
                } else {
                    combine(saved.map { it.status }) { statuses ->
                        SettingsConnectionState.Loaded(
                            saved.mapIndexed { index, host ->
                                SettingsHostRow(
                                    serverId = host.serverId,
                                    displayName = host.displayName,
                                    relayUrl = host.relayUrl,
                                    status = statuses[index],
                                    isOwner = ownerServerId.isNotBlank() && host.serverId == ownerServerId,
                                )
                            },
                            ownerMissing = ownerMissing,
                        )
                    }
                }
            }
            // Supportive-metadata projections swallow upstream errors, as the archived count does: an
            // unreadable host list must not tear the screen down, and naming no host is the honest
            // thing to show when we cannot read which hosts there are.
            .catch { emit(SettingsConnectionState.Loaded(emptyList(), ownerMissing = false)) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = SettingsConnectionState.Resolving,
            )

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

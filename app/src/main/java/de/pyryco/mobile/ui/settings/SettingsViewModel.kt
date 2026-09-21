package de.pyryco.mobile.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.network.RelayLog
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
    private val ownerServerId: String,
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

    /**
     * This destination's own host's default workspace (#714), not the app-wide one its eight
     * siblings above and below read.
     *
     * Keyed by the owner captured into the route, so two hosts' Settings hold two values and a later
     * compatibility-selection change moves neither. A destination that captured no host reads the
     * scratch sentinel and can write nothing: the unqualified [AppPreferences.defaultWorkspace] alias
     * is #711's transitional migration surface, not a per-host fallback, and resolving through it
     * here is exactly what let one host's Settings show and overwrite another's value.
     */
    val defaultWorkspace: StateFlow<String> =
        (if (ownerServerId.isBlank()) flowOf(DEFAULT_SCRATCH_CWD) else appPreferences.defaultWorkspace(ownerServerId))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = DEFAULT_SCRATCH_CWD,
            )

    /**
     * The host the open picker reads folders from and writes its pick to, or null while none is open.
     *
     * One nullable rather than a `Boolean` beside a separate owner, following the flat list's
     * `HostChannelListState.workspacePickerServerId` (#636/#738): the Settings route both keys
     * `LocalWorkspacePickerRepository` off this value and derives the sheet's `visible` from it, so
     * an open sheet whose repository is the compatibility one — the bug this closes — is not
     * representable rather than merely forbidden.
     */
    private val pendingWorkspacePicker = MutableStateFlow<String?>(null)
    val workspacePickerServerId: StateFlow<String?> = pendingWorkspacePicker.asStateFlow()

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
        if (ownerServerId.isBlank()) {
            // Nothing to list folders from and nothing to write them to, so the sheet stays shut.
            RelayLog.d { "event=settings_workspace_picker_rejected code=no_owner" }
            return
        }
        pendingWorkspacePicker.value = ownerServerId
        RelayLog.d { "event=settings_workspace_picker_opened" }
    }

    fun onSelectDefaultWorkspace(path: String) {
        // The target is read off the pending value, not off the captured owner, so a pick arriving
        // behind a dismissal or behind another pick writes nothing. Read and cleared before the
        // launch, so no suspension point sits between deciding the host and writing to it.
        val serverId = pendingWorkspacePicker.value
        if (serverId == null) {
            RelayLog.d { "event=settings_workspace_default_rejected code=no_open_picker" }
            return
        }
        pendingWorkspacePicker.value = null
        // Fire-and-forget like its seven preference siblings. A failed write is already logged
        // content-free by `AppPreferences.editWorkspace`, and the row re-reads from the same flow, so
        // it keeps showing the stored value rather than a pick that never landed.
        viewModelScope.launch { appPreferences.setDefaultWorkspace(serverId, path) }
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = null
        RelayLog.d { "event=settings_workspace_picker_dismissed" }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

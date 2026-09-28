# SettingsViewModel — how it works

Split out of [SettingsViewModel](settings-viewmodel.md) on 2026-09-21 to keep that document under
the 50000-byte size cap the docs guard enforces. This section moved here verbatim and kept its
heading, so its anchors are unchanged. Part of [SettingsViewModel](settings-viewmodel.md); see that
document for what it does, its configuration/usage wiring, its edge cases, its testing and its links.

## How it works

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    conversationRepository: ConversationRepository,
    private val ownerServerId: String,   // from Routes.settingsOwner(handle); blank = no host
    hosts: Flow<List<SettingsHost>>,     // every saved host's identity + live status
    pairedServers: PairedServerCollectionStore,   // #751 — handed straight to hostEditorController
    requestDebugBundle: (String) -> DebugBundleTransfer,   // #683 — registry::requestDebugBundle in production
) : ViewModel() {
    private val hostEditorController = HostEditorController(viewModelScope, pairedServers, appPreferences)  // #751
    val hostEditor: StateFlow<HostEditorState?> = hostEditorController.state

    // #683 — one instance per destination, over this view model's own scope, for the reason the
    // editor above has one: clearing this owner cancels its collect and its write, and two Settings
    // entries on the back stack never share a download. See § Log data download below.
    private val debugBundleController = DebugBundleDownloadController(viewModelScope, ownerServerId, requestDebugBundle)
    val logDataDownload: StateFlow<DebugBundleDownloadState?> = debugBundleController.state   // #751

    val connection: StateFlow<SettingsConnectionState> =           // #750, replacing #749's host
        hosts
            .flatMapLatest { saved ->
                val ownerMissing = ownerServerId.isNotBlank() && saved.none { it.serverId == ownerServerId }
                // combine over an empty array never emits — without this short-circuit an unpaired
                // phone would stay at Resolving forever and silently lose its no-host copy.
                if (saved.isEmpty()) {
                    flowOf(SettingsConnectionState.Loaded(emptyList(), ownerMissing))
                } else {
                    combine(saved.map { it.status }) { statuses ->
                        SettingsConnectionState.Loaded(
                            saved.mapIndexed { index, h ->
                                SettingsHostRow(
                                    h.serverId, h.displayName, h.relayUrl, statuses[index],
                                    isOwner = ownerServerId.isNotBlank() && h.serverId == ownerServerId,
                                )
                            },
                            ownerMissing = ownerMissing,
                        )
                    }
                }
            }
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
            initialValue = ThemeMode.DARK,
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
            initialValue = true,                      // #268 — matches the `?: true` default
        )

    val defaultWorkspace: StateFlow<String> =
        (if (ownerServerId.isBlank()) flowOf(DEFAULT_SCRATCH_CWD) else appPreferences.defaultWorkspace(ownerServerId))
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = DEFAULT_SCRATCH_CWD,
            )

    val defaultWorkspaceLabel: StateFlow<String?> =        // #723
        combine(
            defaultWorkspace,
            conversationRepository
                .observeConversations(ConversationFilter.All)
                .onStart { emit(emptyList()) }
                .catch { emit(emptyList()) },
        ) { path, conversations ->
            if (path.isEmpty() || path == DEFAULT_SCRATCH_CWD) {
                null
            } else {
                conversations
                    .firstOrNull { it.cwd == path && !it.workspaceLabel.isNullOrBlank() }
                    ?.workspaceLabel
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

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
            RelayLog.d { "event=settings_workspace_picker_rejected code=no_owner" }
            return
        }
        pendingWorkspacePicker.value = ownerServerId
        RelayLog.d { "event=settings_workspace_picker_opened" }
    }

    fun onSelectDefaultWorkspace(path: String) {
        val serverId = pendingWorkspacePicker.value
        if (serverId == null) {
            RelayLog.d { "event=settings_workspace_default_rejected code=no_open_picker" }
            return
        }
        pendingWorkspacePicker.value = null
        viewModelScope.launch { appPreferences.setDefaultWorkspace(serverId, path) }
    }

    fun onWorkspacePickerDismissed() {
        pendingWorkspacePicker.value = null
        RelayLog.d { "event=settings_workspace_picker_dismissed" }
    }

    // #751 — opens on the captured owner, never a row id and never selection; the five sibling
    // transitions (submitHostName, requestHostUnpair, declineHostUnpair, confirmHostUnpair,
    // dismissHostEditor) are one-line delegations to hostEditorController, omitted here — see
    // [Host editor](host-editor.md) for the machine itself.
    fun openOwnerHostEditor() {
        if (ownerServerId.isBlank()) {
            RelayLog.d { "event=settings_host_editor_rejected code=no_owner" }
            return
        }
        hostEditorController.open(ownerServerId)
    }

    // #683 — same second-lock shape as openOwnerHostEditor above. The name shown is the one the
    // Connection section already resolved for the owner's own row, read off `connection.value`
    // rather than a second store lookup, so the modal can never name the host differently than the
    // row it was opened from; a row that hasn't arrived yet, or a host no longer paired, falls back
    // to the captured id itself. requestLogArchive / onLogArchiveDestination / dismissLogData are
    // one-line delegations to debugBundleController — see § Log data download below.
    fun openLogData() {
        if (ownerServerId.isBlank()) {
            RelayLog.d { "event=log_data_rejected code=no_owner" }
            return
        }
        val loaded = connection.value as? SettingsConnectionState.Loaded
        debugBundleController.open(loaded?.hosts?.firstOrNull { it.isOwner }?.name ?: ownerServerId)
    }

    fun requestLogArchive() = debugBundleController.requestArchive()

    fun onLogArchiveDestination(destination: ArchiveDestination?) = debugBundleController.onDestination(destination)

    fun dismissLogData() = debugBundleController.dismiss()

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
```

- **`stateIn(WhileSubscribed(5_000L), initial = <neutral>)`** — same idiom as [`ChannelListViewModel`](channel-list-viewmodel.md) and [`DiscussionListViewModel`](discussion-list-viewmodel.md). The 5 s grace period keeps the upstream subscription alive across config changes (rotation, dark-mode toggle) without holding it open after the screen leaves the back stack. All eight preference/derived `stateIn` projections use their empty-store defaults as their `initialValue` (`ThemeMode.DARK` for `themeMode`, `false` for `useWallpaperColors`, `Model.OPUS_4_7` for `defaultModel` — matches `AppPreferences.defaultModel`'s tolerant-unknown fallback from #231, `Effort.HIGH` for `defaultEffort` — same shape, `false` for `defaultYolo` — matches `AppPreferences.defaultYolo`'s default from #231, **`true` for `pushNotifications` (#268) — the lone `true` in the set, matching `AppPreferences.notificationsEnabled`'s `?: true` Figma-sourced default**, `DEFAULT_SCRATCH_CWD` for `defaultWorkspace` (#235; host-keyed by #714) — matches `AppPreferences.defaultWorkspace(serverId)`'s sentinel fallback so the row shows "scratch" before the first emission, and is also the literal value for a blank `ownerServerId`'s `flowOf(DEFAULT_SCRATCH_CWD)` — the two paths share one `initialValue` even though only the non-blank path has a cold upstream to guess ahead of, `0` for `archivedDiscussionCount` — which also happens to be Figma 17:2's "0 archived" empty-state literal) — same value, no first-frame flash. The `pushNotifications` case is the one where picking the wrong literal would be *visible* (a `false` initial would flash OFF→ON on cold open), which is why the #268 spec called the `true` initial load-bearing. `STOP_TIMEOUT_MILLIS = 5_000L` is the single tuning knob for all these projections, `connection` included. The `workspacePickerServerId` flag (#235, rekeyed by #714) is **not** in this set — it's a hot `MutableStateFlow<String?>(null)` exposed via `asStateFlow()`, not a `stateIn`-lifted cold upstream, so it has no `WhileSubscribed`/`initialValue` recipe; it's pure transient UI state that resets to `null` on pick or dismiss.
- **`connection`'s `initialValue` is `SettingsConnectionState.Resolving`, not a "neutral" preference-shaped value.** This is the one projection here over a genuinely cold upstream `hosts` join (the registry's `hostConnections` re-map plus one `store.list()` read per emission), rather than a DataStore flow that always has a well-known first value — so unlike the other seven, there is no correct guess to make before the first emission. `Resolving` renders nothing, deliberately, rather than flashing an empty or wrong list for one frame while the join catches up. Unlike #749's `host`, this `stateIn` lift is **unconditional** — a blank `ownerServerId` still subscribes to `hosts`, because #750 needs the saved-host list even for a destination that owns none of it; only the per-row `isOwner` computation, not the subscription, is what a blank owner short-circuits.
- **`flatMapLatest` over `hosts`, not `map`.** Each re-emission of the host list re-derives every row's `isOwner` flag and switches to a fresh `combine` over *every* host's own `status` flow, cancelling the previous `combine` — and every per-host collector inside it — first. A host-list re-emission that carries a removal is itself what cancels the departing host's status collector, whether or not it was the owner — there is no window where a removed host's stale status can still reach `connection`. `combine` over an empty array never emits, which is why the empty-list branch above short-circuits to a plain `flowOf(...)` rather than falling into `combine(emptyList()) { ... }` and leaving `connection` at `Resolving` forever.
- **`conversationRepository` is a constructor parameter, not a `private val`.** Since #723 two field-initializers read it — `archivedDiscussionCount` (`ConversationFilter.Archived`) and `defaultWorkspaceLabel` (`ConversationFilter.All`) — still each exactly once, so promoting it to a property would still advertise an instance dependency that doesn't exist. If a future event handler ever needs to call `conversationRepository.archive(...)` from this VM, promote then — see [`../codebase/164.md`](../codebase/164.md) § Patterns established.
- **`defaultWorkspaceLabel` matches on exact `cwd` against `ConversationFilter.All`, never the saved-host list cache.** `All` is the only filter `RemoteConversationRepository.project` admits archived conversations under (AC1 requires an archived match to count — an archived conversation still names its workspace), and the host-list cache `connection` builds from drops archived rows entirely, so it isn't a candidate source here at all. Selecting the first conversation with a **non-blank** `workspaceLabel` (not merely non-null) matters: a conversation carrying `workspaceLabel = ""` must not mask a properly named one later in the same list, and the shared `workspaceDisplayName` rule treats blank the same as absent anyway, so this keeps the two consistent.
- **The unbound-default short-circuit (`path.isEmpty() || path == DEFAULT_SCRATCH_CWD → null`) is the one place this projection diverges from the thread's identical-looking rule.** [`workspaceDisplayName`](workspace-chip.md#workspacelabel-derivation) itself is untouched and still prefers a label unconditionally — on the thread, the subject is a conversation that owns its label, so there's nothing to guard against. Here the subject is a *path*, and `DEFAULT_SCRATCH_CWD` is a sentinel every unbound conversation shares; matching on it verbatim would let any conversation that merely happens to be unbound lend its name to a host whose default is *not* bound to anything. The guard runs before the scan, not as a post-filter, so an unbound default never even reads the conversation list.
- **`onStart { emit(emptyList()) }` on the conversation arm is defensive, not what keeps the row from reading "scratch" over a saved path.** It was written expecting to close a real gap — the remote projection emits only after the first `list_conversations` reply, so a bare `combine` would otherwise hold at its initial value while a host is slow or offline — but an empty list and the `stateIn`'s own `initialValue = null` already agree, so removing it changes nothing observable. The actual reason a bound path can't render "scratch" while its label is still unknown is `workspaceDisplayName`'s own fallback chain: a `null` label over a *non-empty, non-scratch* `cwd` renders the path's basename, never "scratch" — that string only ever comes from `defaultWorkspace`'s own `DEFAULT_SCRATCH_CWD` initial value. Kept anyway because it makes the empty-conversations arm explicit and keeps the projection correct if `defaultWorkspaceLabel`'s `initialValue` ever stops being `null` (verifier NIT on PR #760; don't restate the disproven "prevents a stale scratch" framing elsewhere).
- **`.catch { emit(emptyList()) }` sits on the conversation arm alone, matching the `archivedDiscussionCount` / `connection` supportive-metadata rule below** — `combine` keeps a completed flow's last value, so scoping the catch there costs the name only; the path arm (`defaultWorkspace`) keeps updating the row after a conversation-stream failure.
- **`.catch { emit(0) }` upstream of `stateIn` (archived count) and `.catch { emit(Loaded(emptyList(), ownerMissing = false)) }` (connection).** Deliberate divergence from `ArchivedDiscussionsViewModel`, which surfaces `Error(message)` for the same upstream because the list IS its screen's primary content. Settings' supporting text and connection rows are read-only metadata about hosts and conversations that live elsewhere, so an upstream throw collapses to `"0 archived"` or to naming no host rather than tearing down the flow. The rule these consumers now demonstrate: **supportive-metadata projections swallow upstream errors; primary-content projections surface them.** Future supportive-metadata flows (count badges, last-updated timestamps, "N pending" lines) should follow.
- **Fire-and-forget writes.** None of `onSelectTheme`, `onToggleUseWallpaperColors`, `onSelectDefaultModel`, `onSelectDefaultEffort`, `onToggleDefaultYolo`, `onTogglePushNotifications`, `onSelectDefaultWorkspace` `await`s the `edit { … }` or surfaces a result. The persisted value re-emits through the upstream `Flow` after the setter returns; this VM's projection picks up the change. `MainActivity` does not collect `themeMode` or `useWallpaperColors`; it keeps the static dark palette. `defaultModel` has [`ThreadViewModel.selectedModelFlow`](thread-screen.md) as a second consumer since #253 (StatusSheet model section), so an `onSelectDefaultModel` write fans out to both this VM's projection and the StatusSheet's current-model display on every open conversation. `defaultEffort`'s second consumer is `ThreadViewModel.selectedEffortFlow` (#229) via the StatusSheet `FilterChip` row. `defaultYolo` (since #234) has **no second consumer** — an `onToggleDefaultYolo` write fans out only to this VM's own projection (the row's reflected Switch state); #229 deliberately does not seed per-conversation YOLO from it (see § Edge cases). `pushNotifications` (since #268) likewise has **no second consumer** — an `onTogglePushNotifications` write fans out only to this VM's own projection; notification *delivery* is Phase 4, so nothing else reads `notificationsEnabled` today. `defaultWorkspace` has a second consumer in [`ChannelListViewModel`](channel-list-viewmodel.md) (#240), but **not a live one** — it reads `appPreferences.defaultWorkspace(capturedServerId).first()` as a one-shot snapshot at FAB-short-press time, not a continuous collector, so an `onSelectDefaultWorkspace` write doesn't fan out live; it's simply persisted and picked up on the next short-press for that same host. Since #714 both readers key off `serverId` rather than sharing one unqualified flow, so a write from Settings for host A and a short-press read for host B legitimately never see each other — only a write and a later read for the *same* host agree. Same fire-and-forget shape as `MainActivity.kt`'s `setPairedServerExists` call from #12 — see [App preferences § Edge cases](app-preferences.md) for why no `Result`-returning write API exists today.
- **`onSelectDefaultWorkspace` targets the *pending* owner, not `ownerServerId` (#714).** `pendingWorkspacePicker.value` is read into a local `serverId` and cleared **before** `viewModelScope.launch` — no suspension point sits between deciding the write's target and clearing the flag, so a dismissal or a second pick racing the coroutine cannot retarget an in-flight write. Reading off the pending value rather than off the constructor-captured `ownerServerId` is what gives a late-arriving pick (one behind a dismiss, or a second one behind a first) somewhere to fail: `serverId == null` and the write is dropped and logged, rather than silently landing on whatever owner happens to be captured.

## Log data download (#683)

`ui/settings/DebugBundleDownload.kt` holds `DebugBundleDownloadController` + `DebugBundleDownloadState` +
`DebugBundleModal`, the Storage section's `Log data` entry — this ticket's only caller of #682's
transfer, [`RelayConnectionRegistry.requestDebugBundle`](dependency-injection.md). One controller
instance per Settings destination, constructed over `viewModelScope` exactly like
[`HostEditorController`](host-editor.md) and for the same reason: clearing this owner cancels its own
collect and its own write, and two Settings entries on the back stack never share a download.
`serverId` is fixed at construction from the destination's captured owner (#749) and is the only thing
ever handed to `request` — selecting another host, opening another host's Settings, or unpairing has no
second id here to move a request or a pending save onto.

**Single-request guard.** `requestArchive()` returns early unless `DebugBundleDownloadState.idle` is
true — nothing receiving, nothing saving, no archive held, nothing saved. Deterministic code, and its
belt-and-suspenders half is a different fabric: `RemoteConversationRepository.requestDebugBundle`
already holds one transfer per connection and answers a second request `BUSY`, not a second copy of
this guard.

**Take-once discipline.** `DebugBundleTransfer.takeArchive()` yields the completed archive once and
nulls itself, so the controller calls it exactly once, at `COMPLETE`, into its own private `archive`
field — never into `DebugBundleDownloadState`, which carries only `readyBytes: Long?`. Taking it inside
the save path instead would leave a cancelled picker or a failed write with nothing left to save, since
a second `takeArchive()` call returns null; taking it at completion and clearing the field only after a
write returns is what lets both retries find the same archive still held. `dismiss()` drops it too —
daemon bytes do not outlive the modal that is saving them — but the picker round trip does not go
through `dismiss()`, so only a real dismissal discards an unsaved download.

**Nine failure categories, one static sentence each, no raw daemon or exception text.** `failureFor` is
a single exhaustive `when (status: DebugBundleStatus)` with no `else` arm — the one place the closed
status set is interpreted, so #682 adding an arm breaks this file's build and nowhere else. The seven
non-terminal `DebugBundleStatus` values (`UNAVAILABLE`, `BUSY`, `RECONNECT_REQUIRED`, `SEND_FAILED`,
`REFUSED`, `INVALID_STREAM`, `DISCONNECTED`) each map to their own `DebugBundleFailure` arm and leave
the action idle for a fresh request; `RECEIVING`/`COMPLETE` map to `null`. Two more arms have no
transfer status behind them — `PICKER_CANCELLED` (the launcher returned no `Uri`) and `WRITE_FAILED`
(`name()`, `openStream()`, `writeTo` or `flush` threw) — and both leave the archive held so OK
re-opens the picker rather than losing the completed download. A failed write calls
`destination.discard()` under `runCatching` before publishing, so the picker-created document does not
survive as a partial archive; a `discard()` that itself throws is swallowed, since the save has already
failed and there is nothing further to report.

**The modal names the host through `connection`, not a second lookup.** `openLogData()` reads the
Connection section's already-resolved name off `connection.value`'s `Loaded.hosts` (falling back to
the raw `ownerServerId` when that row hasn't arrived yet or the host is no longer paired), so the modal
and the row it was opened from can never disagree about what the host is called. `open(hostName)`
clamps it to `MAX_WORKSPACE_LABEL_CHARS` — the same bound every surface rendering externally authored
text on this screen applies — and `onDestination`'s success path applies the identical clamp to the
picked document's own `OpenableColumns.DISPLAY_NAME` before publishing it as `savedTo`: that string is
supplied by whichever document provider the operator chose, a third-party app under no obligation to
return the name the app suggested, so it is untrusted the same way a scanned-QR host name is.

**`maxLines` is not the security bound and a render defect proved it isn't.** Code review's MUST FIX on
this PR: an early draft rendered every modal line, including the scope sentence naming the whole-daemon
warning, through `maxLines = 1` — clipping the sentence AC1 and the plan's own security review both
required, on a line long enough (99 characters before the host name) to overflow the shell's ~355dp
content column well before reaching it. The clamp above is the actual bound on layout cost; `maxLines`
only stops pixels, since Compose measures the whole string regardless, so removing it removes no
protection. `ModalLine` (renamed from the pre-fix `BoundedModalLine`) now wraps every line instead,
costing height only inside the shell's already-scrolling content column. The gate gap it exposed:
Compose's `hasText` matches the semantics string, which `maxLines` never bounds, so a `hasText`
assertion passes on a sentence the operator — and TalkBack — cannot read; the fix is asserting
`TextLayoutResult.hasVisualOverflow` via `SemanticsActions.GetTextLayoutResult` wherever required copy
might outrun its column. `DebugBundleModal` carries five light/dark `@Preview` pairs, one per content
state (receiving, ready, saving, saved, and a cancelled-picker retry rendered at the name clamp — the
densest content the modal draws), added in the same rework.

The write itself — off `Dispatchers.IO`, `savedTo` published only after the stream closes, terminal
transitions `compareAndSet` against the state published before the suspension — and
`documentArchiveDestination`'s `ContentResolver`-backed `ArchiveDestination` (a SAF `Uri` grant only,
`"wt"` truncates so a retry replaces an earlier partial attempt, no `exists()` probe) are documented in
the file's own KDoc rather than restated here.

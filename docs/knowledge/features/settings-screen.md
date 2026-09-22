# Settings screen

Sectioned settings screen at the `settings` route. Visual treatment is locked to Figma node `17:2` (since #64). Phase 0 visual skeleton — every row destination, every toggle's persistence, and every subtitle's data source is deferred to Phase 3+ tickets.

## What it does

Replaces the prior `SettingsPlaceholder` stub with a scrollable list of seven labelled sections enumerating the canonical settings surface:

1. **Connection** — one [`HostIdentityRow`](settings-screen-how-it-works.md#hostidentityrow-sibling-file) per saved host (since #750; widening #749's single-host row): each host's local name (its server id when unnamed) as headline, then its full server id and relay URL as supporting lines, with its own two-part status line beneath (since #398 — `● Relay   ● Pyrycode`, each dot green/amber/red per leg). The host whose Settings is open — the exact server id the channel list's settings gear captured, **not** whichever host is currently selected, see [Host ownership](settings-screen-how-it-works.md#host-ownership-749-750) — is the row that opens the Edit host modal on itself (since #751, reusing the same modal and machine the channel list's own host row drives — see [Host editor](host-editor.md)), marked by a trailing "This server" badge *and* a chevron, alongside each other; every other row is tappable and opens that host's own Settings, keeping #750's host-to-host navigation. Below the rows: `Pair another server`, wired to the scanner (since #749; previously a no-op).
2. **Appearance** — `Theme` (subtitle reads "System" / "Light" / "Dark" from the `AppPreferences.themeMode` flow since #86; SYSTEM subtitle shortened from `"System default"` → `"System"` in #163 to match Figma `17:2`; tapping the row opens an M3 single-choice `ThemePickerDialog` since #87 that writes through `SettingsViewModel.onSelectTheme(...)` → `AppPreferences.setThemeMode(...)`), `Use Material You dynamic color` (since #89 — VM-backed M3 `Switch` row whose `checked` state mirrors `AppPreferences.useWallpaperColors`; tapping calls `SettingsViewModel.onToggleUseWallpaperColors(...)` → `AppPreferences.setUseWallpaperColors(...)`, and the composition-root collector from #88 repaints `PyrycodeMobileTheme(dynamicColor = …)` live; headline string updated #163 from the prior `"Use wallpaper colors"` to match Figma `17:2`. SDK-gated: on Android < 12 the switch renders `enabled = false` with `supporting` text "Requires Android 12 or newer" — structurally unreachable in production today since Min SDK 33 > S = 31, but implemented per AC).
3. **Defaults for new conversations** — `Default model` (subtitle reads `"Opus 4.7"` / `"Sonnet 4.6"` / `"Haiku 4.5"` from `AppPreferences.defaultModel` since #232; tapping the row opens an M3 single-choice `ModelPickerDialog` that writes through `SettingsViewModel.onSelectDefaultModel(...)` → `AppPreferences.setDefaultModel(...)`), `Default effort` (subtitle reads lowercase `"low"` / `"medium"` / `"high"` / `"xhigh"` / `"max"` from `AppPreferences.defaultEffort` since #233; tapping the row opens an M3 single-choice `EffortPickerDialog` that writes through `SettingsViewModel.onSelectDefaultEffort(...)` → `AppPreferences.setDefaultEffort(...)`), `Default YOLO` (since #234 — a VM-backed M3 `Switch` row whose `checked` state mirrors `AppPreferences.defaultYolo`; toggling calls `SettingsViewModel.onToggleDefaultYolo(...)` → `AppPreferences.setDefaultYolo(...)`; static `supporting = "off"` literal preserved — the dynamic on/off subtitle is a flagged follow-up), `Default workspace` (subtitle reads the owning host's chosen name for the bound directory since #723 — `workspaceDisplayName(cwd = defaultWorkspace, label = defaultWorkspaceLabel)`, the same shared rule #722 put on the thread; the stored path itself is unchanged and still `defaultWorkspace`, matched against that host's own conversations by `SettingsViewModel.defaultWorkspaceLabel` — see § Default workspace row below).
4. **Notifications** — `Push notifications when claude responds` (since #268 — a VM-backed M3 `Switch` row whose `checked` mirrors `AppPreferences.notificationsEnabled`; toggling calls `SettingsViewModel.onTogglePushNotifications(...)` → `AppPreferences.setNotificationsEnabled(...)`; **defaults ON** — the only persisted boolean preference that defaults `true`, sourced from Figma `17:2`; no supporting text, mirroring the design), `Notification sound` (still a no-op `onClick = {}` row with `supporting = "Default"` — Phase 3+).
5. **Memory** — `Installed memory plugins`, `Manage per-channel memory`.
6. **Storage** — `Archived discussions` (headline updated #94 — taps navigate to the in-app [Archived Discussions screen](archived-discussions-screen.md) via the `onOpenArchivedDiscussions` callback, **nullable since #715**: non-null only when this destination's own captured owner is non-empty, opening that owner's archive; `null` when it owns no host, which draws the row without a click action (it still shows its trailing chevron — a verifier NIT, not fixed). The `"11 archived"` subtitle placeholder was dropped in #94, brought back as a real live count in #164, and since #715 that count reads the **same owning host** the row opens — see § Storage row supporting line below), `Clear cache`, and (since #683) `Log data` — headline plus `ChevronIcon`, `Clear cache`'s own shape with no supporting line (the locked frame `17:2` has no Log data row of its own, so the entry borrows the shape the section already draws rather than inventing one); opens the shared [`MobileModal`](mobile-modal.md) on this destination's own captured host and downloads that host's whole daemon-wide diagnostic archive through `RelayConnectionRegistry.requestDebugBundle`, then offers the completed archive to Android's document-creation picker under a fixed `pyrycode-debug-bundle.tar.gz` / `application/gzip`. **Nullable, the same rule #715 gave the row above it**: `onOpenLogData` is `null` — row drawn inert, no click action — when this destination owns no host, and the view model's own `openLogData()` carries the identical guard as a second lock. See [SettingsViewModel § Log data download](settings-viewmodel-how-it-works.md#log-data-download-683) for the state machine.
7. **About** — a single tappable "About" entry (headline `R.string.about_settings_row` = "About", trailing `ChevronIcon`) that navigates to the dedicated [About screen](about-screen.md) via the `onOpenAbout` callback (since #271). The `SettingsSectionHeader("About")` is **kept** above it so the entry doesn't read as a Storage item. Before #271 this section rendered the four About rows inline (Version + build SHA, Open source, Privacy policy, License: MIT); #271 extracted them verbatim into `AboutScreen` and left only the navigable entry. The row-content history (#90 Version + Open source, #163 License row text-only + `LicenseScreen` deletion, #165 Version supporting line → `BuildConfig.GIT_SHA`) now lives on the [About screen](about-screen.md).

Each row has one of four trailing-affordance shapes: chevron, M3 `Switch`, outlined "Add" pill, or none — the `OpenInNew` trailing icon left Settings with the About rows in #271 and now lives in [`AboutScreen`](about-screen.md). The full row inventory (headlines, subtitle literals, trailing per row) is enumerated in [`../codebase/64.md`](../codebase/64.md).

## Default workspace row

Since #723 the Default workspace row's supporting line is `workspaceDisplayName(cwd = defaultWorkspace, label = defaultWorkspaceLabel)` — [#722's shared display rule](workspace-chip.md#workspacelabel-derivation), the same one the thread renders workspace names through. `defaultWorkspace: String` is unchanged: still the stored path, still what the row's `onClick` opens the [`WorkspacePicker`](workspace-picker.md) against, still what a new discussion is created in. `defaultWorkspaceLabel: String?` is the new, purely additive input — display text only, produced by [`SettingsViewModel.defaultWorkspaceLabel`](settings-viewmodel.md), and it reaches nothing but this one `Text`.

**Matching.** The label comes from a conversation belonging to *this destination's own captured host* (the route's owner, #715) whose `cwd` exactly equals `defaultWorkspace` and whose `workspaceLabel` is non-blank — including an **archived** conversation, since an archived conversation still names its workspace. A conversation on a different host at the same path, or a differently-selected host under the pre-#749/#714 compatibility model, cannot supply it: the lookup has no selection input at all, only the constructor-bound repository.

**Fallback chain**, all handled inside `workspaceDisplayName` — this row resolves nothing of its own:

1. A matching label exists → the label, clamped to `MAX_WORKSPACE_LABEL_CHARS`.
2. No label, but `defaultWorkspace` is a non-empty, non-scratch path → the path's last segment (`substringAfterLast('/')`), or the whole path if that segment is empty.
3. `defaultWorkspace` is empty or the scratch sentinel → `"scratch"`.

Arm 3 is reached **without ever scanning conversations** — an empty or `DEFAULT_SCRATCH_CWD` default resolves to `null` before the lookup runs, because that sentinel means "no bound workspace" and every unbound conversation shares it; matching on it would let an unrelated conversation's name stand in for this host's own unbound state. This is the one place the row's *input* diverges from the thread's identical-looking rule — see [SettingsViewModel](settings-viewmodel.md) for why.

The row's own former private formatter (also named `workspaceLabel(cwd)`, arms 2–3 only) is deleted; the daemon-authored-text clamp is inherited from the shared function rather than re-implemented here, so there is no second formatter to bypass it.

## How it works

Split into [Settings screen — how it works](settings-screen-how-it-works.md) on 2026-09-21 to keep
this document under the 50000-byte cap the docs guard enforces. Every section — the `SettingsScreen`
signature, the skeleton, § Host ownership (#749), `HostIdentityRow` (#749), the same-file composables,
`ThemePickerDialog`/`ModelPickerDialog`/`EffortPickerDialog`, and Strings — moved there verbatim,
headings and anchors intact.

## Configuration / usage

Mounted at the `settings` route in `PyryNavHost` (`MainActivity.kt`):

```kotlin
composable(route = Routes.SETTINGS, arguments = Routes.settingsArguments()) { backStackEntry ->   // #749
    val settingsOwner = Routes.settingsOwner(backStackEntry.arguments)   // #715 reads this back for the archive callback
    val vm = koinViewModel<SettingsViewModel>()
    val connection by vm.connection.collectAsStateWithLifecycle()   // #750, replacing #749's host
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
    val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
    val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()
    val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()   // #268
    val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()     // #235
    // Resolved against this destination's own host's conversations (#723); the row renders the two
    // through the shared display rule, and the path above stays the stored one.
    val defaultWorkspaceLabel by vm.defaultWorkspaceLabel.collectAsStateWithLifecycle()
    // One value drives both the picker's repository and whether it is on screen at all (#714), the
    // way the flat list already drives its own picker: the sheet cannot be visible without a host
    // bound, so it can never fall back to the compatibility repository.
    val workspacePickerOwner by vm.workspacePickerServerId.collectAsStateWithLifecycle()
    // The editor this destination opens on its own host (#751), driven by this view model's own
    // HostEditorController instance — see Host editor.
    val hostEditor by vm.hostEditor.collectAsStateWithLifecycle()
    // The Log data download (#683). The launcher is a document-creation contract, not a path: the
    // operator names the destination and no daemon field can influence the suggested name or type.
    val logData by vm.logDataDownload.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val archiveLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(DEBUG_BUNDLE_MEDIA_TYPE)) { uri ->
            // Null is a cancelled picker; the controller reports it without touching the held archive.
            vm.onLogArchiveDestination(uri?.let { documentArchiveDestination(resolver, it) })
        }
    HostWorkspaceRepository(workspacePickerOwner, destinations) {
        SettingsScreen(
            connection = connection,                                // #750
            themeMode = themeMode,
            useWallpaperColors = useWallpaperColors,
            archivedDiscussionCount = archivedDiscussionCount,
            defaultModel = defaultModel,
            defaultEffort = defaultEffort,
            defaultYolo = defaultYolo,
            pushNotifications = pushNotifications,                  // #268
            defaultWorkspace = defaultWorkspace,                   // #235
            defaultWorkspaceLabel = defaultWorkspaceLabel,          // #723
            // Read off the picker's own target, as the flat channel screen reads off its.
            workspacePickerVisible = workspacePickerOwner != null,
            onSelectTheme = vm::onSelectTheme,
            onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
            onSelectDefaultModel = vm::onSelectDefaultModel,
            onSelectDefaultEffort = vm::onSelectDefaultEffort,
            onToggleDefaultYolo = vm::onToggleDefaultYolo,
            onTogglePushNotifications = vm::onTogglePushNotifications,  // #268
            onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped,    // #235
            onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,    // #235
            onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed, // #235
            // Lateral hop between two instances of this destination, not descent: replaces this entry
            // rather than stacking on it, so Back always reaches the list a Settings hop chain started
            // from. See Navigation § Settings for why launchSingleTop is the wrong tool here (#750).
            onOpenHost = { serverId ->
                navController.navigate(Routes.settings(serverId)) {
                    popUpTo(Routes.SETTINGS) { inclusive = true }
                }
            },
            hostEditor = hostEditor,
            // The owner's row is the only caller; the view model opens on the captured id, never a
            // row's own id and never selection (#751). Removal reacts to nothing here: the host list
            // re-emits without it, and this destination stays put — see § Edge cases.
            onEditHost = vm::openOwnerHostEditor,
            onEditHostNameSubmitted = vm::submitHostName,
            onHostUnpairRequested = vm::requestHostUnpair,
            onHostUnpairConfirmed = vm::confirmHostUnpair,
            onHostUnpairDeclined = vm::declineHostUnpair,
            onEditHostDismissed = vm::dismissHostEditor,
            onPairServer = { navController.navigate(Routes.SCANNER) },  // #749
            onBack = { navController.popBackStack() },
            // Nullable since #715: this destination's own owner, not selection. Null draws the row inert
            // rather than offering a tap that could only be rejected — a blank id matches no destination.
            onOpenArchivedDiscussions =
                settingsOwner.takeIf { it.isNotEmpty() }?.let { owner ->
                    { navController.navigate(Routes.archive(owner)) }
                },
            // Same nullable rule as the archive callback above it (#683): no host, no tap to offer.
            onOpenLogData = settingsOwner.takeIf { it.isNotEmpty() }?.let { { vm.openLogData() } },
            logData = logData,
            onLogDataRequested = vm::requestLogArchive,
            onLogDataSaveRequested = { archiveLauncher.launch(DEBUG_BUNDLE_FILE_NAME) },
            onLogDataDismissed = vm::dismissLogData,
            onOpenAbout = { navController.navigate(Routes.ABOUT) },
        )
    }
}
```

All `vm.*` projections are `StateFlow`s, so the no-arg `collectAsStateWithLifecycle()` overload is used — no `initialValue` argument needed. The underlying `appPreferences.themeMode` / `appPreferences.useWallpaperColors` flows are also collected at the `setContent` root (drive `darkTheme: Boolean` / `dynamicColor: Boolean` for `PyrycodeMobileTheme(...)`); a single `setThemeMode` / `setUseWallpaperColors` write fans out to both collectors. `archivedDiscussionCount` is Settings-screen-local. `defaultModel` already has a second consumer — [`ThreadViewModel.selectedModelFlow`](thread-screen.md) (#253) reads `appPreferences.defaultModel` for the StatusSheet model section, so a `setDefaultModel` write from this screen fans out to the StatusSheet's current-model display on every open conversation. `defaultEffort`'s second consumer is the StatusSheet `FilterChip` row (#229); the new-conversation materialiser will eventually become a third consumer of both. `defaultYolo` (#234) and `pushNotifications` (#268) are Settings-screen-local with **no** second consumer — both persist but nothing reads them yet (per-conversation YOLO stays hardcoded off per #229; notification delivery is Phase 4). `defaultWorkspaceLabel` (#723) is likewise Settings-screen-local with no second consumer — display text derived from `defaultWorkspace` plus this destination's own conversations, reaching nothing but the Default workspace row's subtitle. `connection` (#750, replacing #749's `host`) has no composition-root sibling — every row in it is resolved from `ThreadDestinationFactory.hosts()`'s saved-host join, and which row is the owner comes from this destination's own captured `serverId` argument, never from compatibility selection. `hostEditor` (#751) likewise has no composition-root sibling and no second consumer — it is this destination's own [`HostEditorController`](host-editor.md) instance, entirely separate from the channel list's. See [Settings ViewModel](settings-viewmodel.md) for the VM surface and [App preferences](app-preferences.md) for the flow contract.

Entry point is the trailing settings-gear `IconButton` in `ChannelListScreen`'s own bar (via `ChannelListEvent.SettingsTapped → navController.navigate(Routes.settings(destinations.selectedServerId()))`, #749 — previously a bare `navigate(Routes.SETTINGS)`). See [Navigation § Settings](navigation.md#settings-an-optionally-owned-destination) for the route shape, why this destination is not wrapped in `HostDestination`, and (#750) the host-to-host `popUpTo … inclusive` back-stack decision a non-owner row's tap makes.

## Edge cases / limitations

Split into [Settings screen — edge cases and previews](settings-screen-previews-and-edge-cases.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Edge cases / limitations and Previews — moved there verbatim, headings and anchors intact.

## Related

- Spec: `docs/specs/architecture/64-settings-screen.md`; About-row wiring specs `docs/specs/architecture/90-settings-about-version-row-and-open-source-row.md`, `docs/specs/architecture/91-settings-in-app-license-viewer.md` (in-app viewer; entry point + screen deleted by #163); Theme-row subtitle spec `docs/specs/architecture/86-theme-mode-preference.md`; Theme picker dialog spec `docs/specs/architecture/87-settings-theme-picker-dialog.md`; Use-wallpaper-colors switch spec `docs/specs/architecture/89-settings-use-wallpaper-colors-switch.md`; copy-fixes + License-row deletion spec `docs/specs/architecture/163-settings-copy-fixes-license-row-non-clickable.md`; archived-count supporting line spec `docs/specs/architecture/164-settings-archived-discussion-live-count.md`; Version-row Git-SHA spec `docs/specs/architecture/165-settings-version-row-git-sha.md`; Default-model picker spec `docs/specs/architecture/232-settings-default-model-picker-dialog.md`; Default-effort picker spec `docs/specs/architecture/233-settings-default-effort-picker-dialog.md`; Default-YOLO toggle spec `docs/specs/architecture/234-settings-default-yolo-toggle-persistence.md`; Default-workspace picker spec `docs/specs/architecture/235-settings-default-workspace-picker.md`; Push-notifications toggle spec `docs/specs/architecture/268-settings-push-notifications-toggle-persistence.md`; About-screen extraction spec `docs/specs/architecture/271-dedicated-about-screen.md`; Connection-status-line wiring spec `docs/specs/architecture/398-settings-connection-status-line-wiring.md` (superseded by #749); host-ownership spec `docs/specs/architecture/749-settings-destination-host-owner.md`; saved-host connection rows spec `docs/specs/architecture/750-settings-saved-host-connection-rows.md`; Archive host-binding spec (nullable `onOpenArchivedDiscussions` + owner-matched count) `docs/specs/architecture/715-archive-host-owner.md`; workspace-picker host-scoping spec `docs/specs/architecture/714-settings-workspace-picker-host-scope.md`; shared display-rule spec `docs/specs/architecture/722-show-workspace-labels-in-the-conversation-thread.md`; Default workspace row label spec `docs/specs/architecture/723-settings-default-workspace-label.md`; Edit/unpair-from-Settings spec `docs/specs/architecture/751-settings-host-edit-and-unpair.md`; Log data download spec `docs/specs/architecture/683-save-a-host-diagnostic-archive.md`
- Ticket notes: [`../codebase/64.md`](../codebase/64.md) (visual skeleton), [`../codebase/90.md`](../codebase/90.md) (About Version + Open-source wiring), [`../codebase/91.md`](../codebase/91.md) (License row → in-app viewer, walked back by #163), [`../codebase/86.md`](../codebase/86.md) (Theme row subtitle → `AppPreferences.themeMode`), [`../codebase/87.md`](../codebase/87.md) (Theme picker dialog + `SettingsViewModel`), [`../codebase/89.md`](../codebase/89.md) (Use-wallpaper-colors switch + VM extension), [`../codebase/163.md`](../codebase/163.md) (copy fixes + License-row non-clickable + LicenseScreen deletion), [`../codebase/164.md`](../codebase/164.md) (Storage row "N archived" supporting line + `archivedDiscussionCount` parameter), [`../codebase/165.md`](../codebase/165.md) (Version row supporting line → `BuildConfig.GIT_SHA` via Gradle `ValueSource`), [`../codebase/232.md`](../codebase/232.md) (Default-model picker dialog + row wire-up + fourth writable preference), [`../codebase/233.md`](../codebase/233.md) (Default-effort picker dialog + row wire-up + third writable preference), [`../codebase/234.md`](../codebase/234.md) (Default-YOLO switch row + VM pair), [`../codebase/235.md`](../codebase/235.md) (Default-workspace row + `WorkspacePicker` host), [`../codebase/268.md`](../codebase/268.md) (Push-notifications switch row + the last placeholder-switch local removed + the fourth androidTest-cascade occurrence), [`../codebase/271.md`](../codebase/271.md) (About section → single navigable entry + `AboutScreen` extraction + `SettingsRow` `internal`), [`../codebase/398.md`](../codebase/398.md) (Connection-section two-part status line under the Server row + `connectionStatus` param, superseded by #749)
- Note: #750 (saved-host connection rows), #714 (workspace-picker host-scoping), #723 (Default workspace row label), #751 (edit/unpair from Settings) and #683 (Log data download) have no per-ticket archive entry — the archive was frozen 2026-09-05, before any of the five; their lessons live in this document and in [Settings ViewModel](settings-viewmodel.md), [Navigation](navigation.md) and [Host editor](host-editor.md) instead.
- Edit host modal, second caller (#751): [Host editor](host-editor.md) — `HostEditorController`, `HostEditorState`, `HostEditorModal`, shared verbatim with [ChannelListViewModel](channel-list-viewmodel.md)
- Log data download (#683): [SettingsViewModel — how it works § Log data download](settings-viewmodel-how-it-works.md#log-data-download-683) — `DebugBundleDownloadController`, `DebugBundleDownloadState`, `DebugBundleModal`, this ticket's first caller of `RelayConnectionRegistry.requestDebugBundle`; reuses [`MobileModal`](mobile-modal.md) directly rather than through `HostEditorModal`
- Shared display rule (#722): [Workspace chip § label derivation](workspace-chip.md#workspacelabel-derivation) — `workspaceDisplayName(cwd, label)`, consumed by both the thread and (since #723) the Default workspace row
- How it works (split #749): [Settings screen — how it works](settings-screen-how-it-works.md) — `SettingsScreen` signature, skeleton, Host ownership, `HostIdentityRow`, same-file composables, the three picker dialogs, Strings
- Hosted ViewModel: [Settings ViewModel](settings-viewmodel.md)
- Archived discussions sub-screen (since #94): [Archived Discussions screen](archived-discussions-screen.md)
- About sub-screen (since #271): [About screen](about-screen.md) — the extracted Version / Open source / Privacy / License rows now live there, reached via the `onOpenAbout` entry
- Figma node: `17:2` — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2
- Phase 1 stub it replaces: ticket #16 (`SettingsPlaceholder` in `MainActivity`)
- Entry point: [Channel list screen](channel-list-screen.md) — settings entry on the list's own bar (its
  `TopAppBar` until #737 replaced it with that bar)
- Sibling back-nav screen pattern: [Discussion list screen](discussion-list-screen.md) — same `TopAppBar` + `IconButton(onBack)` shape

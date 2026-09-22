# SettingsViewModel — configuration and edge cases

Split out of [SettingsViewModel](settings-viewmodel.md) on 2026-09-22 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [SettingsViewModel](settings-viewmodel.md); see that document for the rest.

## Configuration / usage

Registered once in the Koin module:

```kotlin
// di/AppModule.kt
viewModel { get<ThreadDestinationFactory>().settings(get(), get(), get()) }
```

The Koin registration changed shape in #749: `SettingsViewModel` construction moved from an inline
`viewModel { SettingsViewModel(get(), get(), get<RelayRepositoryCoordinator>().connectionStatus) }`
into `ThreadDestinationFactory.settings(handle, preferences, repository)`, alongside its `thread`
and `literal` methods — see [dependency injection § Destination ownership](dependency-injection.md#destination-ownership)
for the factory method and the `hosts()` projection it builds `SettingsViewModel`'s `hosts` argument
from. `preferences` is still the one process-wide `AppPreferences` singleton #398 resolved, but
Settings no longer reads or writes all of it app-wide: since #714 `defaultWorkspace` and its picker
handlers key their reads and writes through the destination's captured `ownerServerId`, while the
other six preference pairs stay genuinely app-wide. `repository` stays the compatibility-bound
`ConversationRepository` singleton — the archived-discussion count is rebound with the Archive
destination by #715 — and, since #714, is no longer what the workspace picker resolves to either:
the picker's own repository is bound by the route (`HostWorkspaceRepository`, below), not by this
constructor argument. Since #751 the factory also passes its existing `store: PairedServerCollectionStore`
field as a fifth constructor argument — no new Koin definition, since `ThreadDestinationFactory` already
holds that store for `hosts()`'s saved-host join — which this class hands straight to its own
[`HostEditorController`](host-editor.md).

Consumed once at the Settings NavHost destination. Since #714 the destination wraps
`SettingsScreen` in `HostWorkspaceRepository`, keyed by the same nullable that drives the sheet's
visibility, so an open sheet can never be bound to the compatibility repository — the same shape
`ChannelListScreen` already used for its own picker:

```kotlin
composable(route = Routes.SETTINGS, arguments = Routes.settingsArguments()) { backStackEntry ->
    val settingsOwner = Routes.settingsOwner(backStackEntry.arguments)   // #715
    val vm = koinViewModel<SettingsViewModel>()
    val connection by vm.connection.collectAsStateWithLifecycle()   // #750, replacing #749's host
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val useWallpaperColors by vm.useWallpaperColors.collectAsStateWithLifecycle()
    val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
    val defaultModel by vm.defaultModel.collectAsStateWithLifecycle()
    val defaultEffort by vm.defaultEffort.collectAsStateWithLifecycle()
    val defaultYolo by vm.defaultYolo.collectAsStateWithLifecycle()
    val pushNotifications by vm.pushNotifications.collectAsStateWithLifecycle()
    val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()
    // Resolved against this destination's own host's conversations (#723); the row renders the two
    // through the shared display rule, and the path above stays the stored one.
    val defaultWorkspaceLabel by vm.defaultWorkspaceLabel.collectAsStateWithLifecycle()
    // One value drives both the picker's repository and whether it is on screen at all (#714): the
    // sheet cannot be visible without a host bound, so the pre-#714 fallback to the compatibility
    // repository is not representable.
    val workspacePickerOwner by vm.workspacePickerServerId.collectAsStateWithLifecycle()
    // The editor this destination opens on its own host (#751), driven by this view model's own
    // HostEditorController instance — never shared with the channel list's.
    val hostEditor by vm.hostEditor.collectAsStateWithLifecycle()
    HostWorkspaceRepository(workspacePickerOwner, destinations) {
        SettingsScreen(
            connection = connection,                     // #750
            themeMode = themeMode,
            useWallpaperColors = useWallpaperColors,
            archivedDiscussionCount = archivedDiscussionCount,
            defaultModel = defaultModel,
            defaultEffort = defaultEffort,
            defaultYolo = defaultYolo,
            pushNotifications = pushNotifications,
            defaultWorkspace = defaultWorkspace,
            defaultWorkspaceLabel = defaultWorkspaceLabel,   // #723
            // Read off the picker's own target, as the flat channel screen reads off its.
            workspacePickerVisible = workspacePickerOwner != null,
            onSelectTheme = vm::onSelectTheme,
            onToggleUseWallpaperColors = vm::onToggleUseWallpaperColors,
            onSelectDefaultModel = vm::onSelectDefaultModel,
            onSelectDefaultEffort = vm::onSelectDefaultEffort,
            onToggleDefaultYolo = vm::onToggleDefaultYolo,
            onTogglePushNotifications = vm::onTogglePushNotifications,
            onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped,
            onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,
            onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
            // Lateral hop between two instances of this destination, not descent — replaces this entry
            // rather than stacking on it. See Navigation § Settings (#750).
            onOpenHost = { serverId ->
                navController.navigate(Routes.settings(serverId)) {
                    popUpTo(Routes.SETTINGS) { inclusive = true }
                }
            },
            hostEditor = hostEditor,
            // The owner's row is the only caller; the view model opens on the captured id, never a
            // row's own id and never selection (#751). Nothing here reacts to a removal that follows:
            // the host list re-emits without it, this destination stays put.
            onEditHost = vm::openOwnerHostEditor,
            onEditHostNameSubmitted = vm::submitHostName,
            onHostUnpairRequested = vm::requestHostUnpair,
            onHostUnpairConfirmed = vm::confirmHostUnpair,
            onHostUnpairDeclined = vm::declineHostUnpair,
            onEditHostDismissed = vm::dismissHostEditor,
            onPairServer = { navController.navigate(Routes.SCANNER) },   // #749
            onBack = { navController.popBackStack() },
            // Nullable since #715: this destination's own owner, not selection.
            onOpenArchivedDiscussions =
                settingsOwner.takeIf { it.isNotEmpty() }?.let { owner ->
                    { navController.navigate(Routes.archive(owner)) }
                },
            onOpenAbout = { navController.navigate(Routes.ABOUT) },
        )
    }
}
```

`SettingsScreen`'s own signature is unchanged — it still takes `workspacePickerVisible: Boolean` —
so its previews and `SettingsScreenTest` compile untouched; only the route derives that boolean
differently now. `HostWorkspaceRepository` already existed (#636): Settings becomes its third
caller alongside the host-owned thread destinations and the flat channel screen — see
[`WorkspacePicker` § Repository ownership](workspace-picker.md#repository-ownership).

**Rejected alternative (from the #714 plan): bind the provider to the route's captured owner for
the destination's whole life instead of to the picker's own target.** That value is constant, so it
would avoid the `staticCompositionLocalOf` recomposition on picker open/close that this shape pays.
It was rejected because it splits the invariant across two places — the route would decide the
repository and the ViewModel would decide whether the sheet opens — and a later change to either
guard could let the sheet open with a null provider, which is exactly the bug this ticket removes.
Deriving both from one nullable makes the disagreement unrepresentable rather than merely
prohibited; `ChannelListScreen` already pays the one extra redraw this costs in production.

Note the no-arg `collectAsStateWithLifecycle()` calls — all twelve `vm.*` reads (eight preference/derived projections + `defaultWorkspaceLabel` + the `workspacePickerServerId` flag + `connection` + since #751 `hostEditor`) are already `StateFlow`s, so the overload picks up `StateFlow.value` as initial. Compare with the root-level `appPreferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)` / `appPreferences.useWallpaperColors.collectAsStateWithLifecycle(initialValue = false)` in `MainActivity`'s `setContent` block, which collect the *cold* flows and must supply same-shaped neutral defaults. The composition-root collectors are independent of this VM's projections — DataStore broadcasts to every subscriber, so one `setUseWallpaperColors(...)` write reaches both the global theme repaint and this VM's `useWallpaperColors.value` from a single upstream emission. The `archivedDiscussionCount` flow has no composition-root sibling — it's Settings-screen-local. `defaultModel` and `defaultEffort` each have a second consumer in `ThreadViewModel` (StatusSheet model + effort sections) since #253 / #229 respectively; the new-conversation materialiser will eventually become a third consumer of both. `defaultYolo` (since #234) is Settings-screen-local with no second consumer — #229 deliberately does not read it; the new-conversation materialiser is its only prospective future reader. `pushNotifications` (since #268) is likewise Settings-screen-local with no second consumer — notification *delivery* (Phase 4) is its only prospective future reader. `defaultWorkspace` is read by [`ChannelListViewModel`](channel-list-viewmodel.md) (#240) but as a one-shot `.first()` snapshot at FAB-short-press time under that call's own captured host, not a live composition-root collector — since #714 the two reads are keyed by different owners (the FAB's captured host vs. this destination's), so they can legitimately disagree when Settings and the list are open for different hosts. The `workspacePickerServerId` flag is Settings-screen-local transient UI state with no upstream and no sibling collector at all. `connection` has no composition-root sibling either — every row's identity and status is resolved from `ThreadDestinationFactory.hosts()`'s saved-host join, and which row is the owner comes from the destination's own `SavedStateHandle` argument, not from any compatibility selection state `MainActivity` tracks elsewhere. `defaultWorkspaceLabel` (#723) is likewise Settings-screen-local with no composition-root sibling and no second consumer — it derives from `defaultWorkspace` and this destination's own `conversationRepository`, and reaches nothing beyond the Default workspace row's subtitle; unlike `defaultWorkspace` itself, `ChannelListViewModel`'s FAB-short-press read has no use for a display label. `hostEditor` (#751) has no composition-root sibling and no second consumer either — it is this VM's own [`HostEditorController`](host-editor.md) instance, entirely separate from `ChannelListViewModel`'s, so an editor open in one screen is invisible to the other.

## Edge cases / limitations

- **No write-failure surface.** `setThemeMode` / `setUseWallpaperColors` / `setDefaultModel` / `setDefaultEffort` / `setDefaultYolo` / `setNotificationsEnabled` / `setDefaultWorkspace` can each in principle throw `IOException` from DataStore; this VM does not catch or surface that. If a write ever fails in the field, the persisted value stays at its prior value, the upstream re-emits the prior value, and the dialog (or Switch, or picker row) reverts either way. Adding a snackbar would mean adding a `Result`-returning write API on `AppPreferences` — out of scope until an actual failure mode is observed (see [evidence-based fix selection](app-preferences.md)). `setDefaultWorkspace(serverId, cwd)` (since #714) is the one exception that already returns `Result<Unit>` — its own `AppPreferences.editWorkspace` logs `event=workspace_default_set outcome=io_failure` content-free on failure, and this VM adds no second log or UI affordance on top: the row simply re-reads from the same flow, so a failed write leaves the old value visible.
- **A blank `ownerServerId` cannot open the picker or write a default (#714).** A destination that captured no host has no host to read recents from or create folders on, so `onDefaultWorkspaceTapped()` leaves `workspacePickerServerId` at `null` and logs a content-free reject; `defaultWorkspace` reads `DEFAULT_SCRATCH_CWD` unconditionally rather than falling through to the unqualified legacy flow. Don't "fix" the no-op tap by falling back to the legacy `AppPreferences.defaultWorkspace` — that fallback is exactly the cross-host leak #714 closes.
- **A pick or dismiss cannot retarget a picker opened for a different owner (#714).** `onSelectDefaultWorkspace(path)` reads and clears `pendingWorkspacePicker` — not `ownerServerId` — before launching the write, so a pick that arrives after a dismissal, or a second pick behind a first, finds no pending owner and is dropped and logged rather than writing to whichever owner happens to be captured at that moment. There is no suspension point between reading the pending owner and clearing it, so a dismissal racing the write cannot retarget it either.
- **`defaultYolo` is write-live but read-dead (#234).** The Default-YOLO Switch persists end-to-end — toggle it, restart, it sticks. But **nothing reads `defaultYolo` to act on it.** Per [#229](../codebase/229.md)'s design the StatusSheet does not seed per-conversation YOLO from this default — `ThreadViewModel.yoloEnabled` is hardcoded `false` and `ThreadViewModelTest.kt:375-416` pins that invariant. The Settings default exists purely as a persisted user-intent signal and a hook for the eventual new-conversation materialiser. Don't wire it into new-conversation creation as a "fix" for the dead-end — that would have to revisit #229's "per-conversation YOLO always starts off" invariant first. See [`../codebase/234.md`](../codebase/234.md) § Lessons learned.
- **`pushNotifications` is write-live but read-dead (#268), and defaults `true`.** The Push-notifications Switch persists end-to-end, but **nothing reads `notificationsEnabled` to act on it** — actual notification *delivery* is Phase 4 (same scope boundary as #234). The `true` default is the load-bearing exception: it's the only preference here whose `initialValue` is `true` (Figma `17:2` renders the toggle ON), copied from the `defaultYolo` block with the `false` default deliberately *not* carried over. Don't wire it into a system-notification channel as a "fix" for the dead-end — that's a deliberate Phase-0 scope boundary. See [`../codebase/268.md`](../codebase/268.md).
- **`connection` resolution errors swallow to an empty, unowned list, matching the archived-count rule.** `.catch { emit(Loaded(emptyList(), ownerMissing = false)) }` sits directly upstream of the `stateIn` — a throwing `hosts` source (in practice defence in depth: an unreadable paired-server blob makes `store.list()` return empty rather than throw) collapses to naming no host, never to a crash or a stale list. `ownerMissing = false` here, not `true`: an unreadable host list is not evidence the destination's own owner is gone, so the recovery copy must not claim it. This is the same "supportive-metadata projections swallow upstream errors" rule `archivedDiscussionCount`'s `.catch { emit(0) }` established.
- **Archived-count read-failure swallowed silently.** `.catch { emit(0) }` on the upstream — if the repository's `Archived` stream ever throws, the row reads `"0 archived"` and `[Archived Discussions screen](archived-discussions-screen.md)` becomes the surface that owns the error UI. Tested via `MutableSharedFlow` source emit + cancel-then-throw scenarios isn't necessary; the `.catch` is one line and the AC explicitly accepts "0 archived" as the empty/error display.
- **Seven-pair surface (writable preferences) — the "lift to sealed `SettingsState`" threshold passed unevaluated a fourth time at #268, and there are no triggers left.** #87 set the rule that the MVI envelope arrives on the *third real persisted preference*. The count was one after #87 (`themeMode`), two after #89 (`+useWallpaperColors`), three after #233 (`+defaultEffort`, deferred), four after #232 (`+defaultModel`), five after #234 (`+defaultYolo`), six after #235 (`+defaultWorkspace`), and is **seven after #268 (`+pushNotifications`)**. #232/#233's lessons named "the next defaults consumer (YOLO switch row or workspace picker, whichever lands first)" as the unambiguous re-evaluation point; #234 (YOLO) and #235 (workspace) both landed scoped as XS/S plumbing and never raised the lift question, so the mechanical-copy pattern ran out the entire "Defaults for new conversations" group. #268 then copied the same `(StateFlow<Boolean>, fun onToggleX)` shape *outside* that group — into the Notifications section — confirming the pattern is self-perpetuating across sections and that **no "natural trigger" row remains.** The shape has settled by default; any lift is now a dedicated refactor ticket that must restructure all seven pairs at once, not a side-effect of the next feature slice. See [`../codebase/232.md`](../codebase/232.md), [`../codebase/233.md`](../codebase/233.md), [`../codebase/234.md`](../codebase/234.md), and [`../codebase/268.md`](../codebase/268.md) § Threshold notes for the deferral trail. The `archivedDiscussionCount` projection from #164 (read-only, data-derived), the `workspacePickerServerId` flag (transient UI state, `workspacePickerVisible` before #714 rekeyed it), the `connection` projection (read-only, resolved against saved hosts — successor to #749's `host`, itself the successor to #398's forwarded `connectionStatus`, all three counted the same way), and the `defaultWorkspaceLabel` projection from #723 (read-only, derived from `defaultWorkspace` plus conversation data — display text with no setter of its own) do not count toward the threshold. #714 rekeyed `defaultWorkspace` from an app-wide `AppPreferences.defaultWorkspace` read to a per-owner `AppPreferences.defaultWorkspace(ownerServerId)` read without changing its `(StateFlow<String>, one setter)` shape, so it still counts as the sixth pair in this history; the threshold narrative above is unaffected.
- **`connection`'s N status collectors dial nothing.** Each saved host's `status` flow inside `combine(...)` is the coordinator's existing hot `connectionStatus` for that host, so subscribing to N of them on Settings-open cannot reopen a socket [`LifecycleConnectionDriver`](lifecycle-connection-driver.md) has closed for a backgrounded host. Don't wire this projection to `RelayConnectionRegistry.pairingStatus` instead — that is a *different* per-host flow that deliberately fires `retryHost` on first subscribe, and using it here would turn merely opening Settings into a reconnect attempt against every saved host at once.

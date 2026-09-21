# 751 — Edit and unpair a host from mobile Settings

Gives the Connection row that marks this Settings' own host the edit affordance the channel list
already has, by moving the host-editor state machine onto a seam both screens drive. No second copy
of that machine, and no second removal path.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` →
  `HostEditorState` and the six transitions `openHostEditor`, `submitHostName`, `requestHostUnpair`,
  `declineHostUnpair`, `confirmHostUnpair`, `dismissHostEditor` — the machine this ticket moves,
  including the `compareAndSet` failure handling and the open-job cancellation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` →
  `ChannelListScreen` — the one existing `EditHostModal` call site and its flag-to-string mapping.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal` — the
  presentation component and its stated caller obligations (display text, generic error, stable
  identity while open, caller owns removal from composition).
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `SettingsViewModel`,
  `SettingsConnectionState`, `SettingsHostRow` — where `isOwner` is resolved and what the owner-gone
  case already looks like.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt` → `SettingsScreen`,
  `HostOwnerBadge`, `ChevronIcon` — the Connection section's row loop and its `key(row.serverId)`
  guard, which the Settings screen doc calls "a guard for the edit/unpair slice".
- `app/src/main/java/de/pyryco/mobile/ui/settings/HostIdentityRow.kt` → `HostIdentityRow` — its
  `onClick` / `trailing` slots, which already take exactly what this ticket needs.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.settings` — it
  already holds `store`, so the new dependency needs no Koin change.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.SETTINGS` composable and the
  channel list's `ChannelListEvent` dispatch — how editor state reaches a stateless screen today.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` →
  `PairedServerCollectionStore.remove` / `setDisplayName` / `loadById` — id-exact, no-op on unknown.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `remove` — the revision
  bump that makes `RelayConnectionRegistry` close the removed host's connection with no call here.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` →
  `removeDefaultWorkspace(serverId)` — id-keyed, so a second host's #711 workspace survives.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` →
  its `Store` fake (gates for read, write and remove; `failWrite` / `failRemove`) — the fixture shape
  the Settings tests mirror, and the proofs that must stay green through the move.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` → `makeVm` — the single
  construction helper the new dependency threads through.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` → `setSettings` —
  the single `SettingsScreen` call site in that suite.
- `docs/knowledge/features/settings-screen.md` § Edge cases — the `key(row.serverId)` note and the
  four-times-repeated `androidTest` threading cascade, which is why `compileDebugAndroidTestKotlin`
  is in this ticket's own gate rather than left to the dispatcher.
- `docs/knowledge/features/settings-screen.md` § How it works — the `connection` state's owner-gone
  and no-host copy, which AC4 reuses rather than adding new copy.

## Design source

**Figma:** Settings https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2 ·
Edit host https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The Settings frame draws its single Server row as a `ListItem` — host name over server id, the
two-dot Relay/Pyrycode status line beneath — with a trailing chevron at the right edge, the same
chevron every other navigating row in the frame carries. That chevron is the Edit host affordance,
and per the ticket it arrives **alongside** the "This server" badge #750 gives the owner, not instead
of it. The Edit host frame itself is already built to node `533-2369` (#743/#744/#745) and is not
redrawn: a dark modal titled "Edit host", two read-only `Server identity:` / `Relay address:` lines,
a filled `Host name:` field, an outlined `Unpair host` button, and the shell's own Cancel / OK footer.

## Context

Settings lists every saved host (#750) but nothing on the screen acts on one, so renaming or
unpairing means leaving Settings for the conversation tree. The modal (#743), the rename (#744) and
the confirmed removal (#745) all exist; only the owner row's entry point is missing.

The machine behind them lives in `ChannelListViewModel` and carries subtleties a re-derivation loses:
the `editorOpenJob` cancellation that stops a slower `loadById` from publishing over a newer tap, the
`compareAndSet` transitions that stop a completed write from resurrecting a dismissed modal, the
`saving` guard on every transition, and the removal ordering that clears the host's cached workspace
only after the pairing is actually gone. Its removal path is destructive; two copies would drift.

So this ticket moves the machine to a seam and leaves both screens driving one implementation. The
seam is a plain controller object rather than a shared `ViewModel`: the two owning view models have
different lifetimes and different constructor graphs, and a controller taking a `CoroutineScope`
composes into both without either inheriting the other's dependencies.

**This design deserves no ADR.** It introduces no new architectural rule — it is an extraction of
existing behaviour into a shared object, and the existing decisions (#744's `compareAndSet` handling,
#745's in-place confirmation) already have their homes in the feature overviews.

## Design

### The seam — `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` (new)

A new `ui/host/` package, following `ui/workspace/`'s precedent for a cross-screen shared rule. One
file holds the three parts of one contract: the state, the machine that produces it, and the single
composable that binds it to the presentational modal.

- `HostEditorState` — moved **verbatim** from `ChannelListViewModel.kt`, KDoc included. Same eight
  fields, same reasons: display text and the target id only, never the `PairedServer` record; flags
  rather than messages so no identity reaches a live region; no name draft, because `EditHostModal`
  owns its own buffer.
- `HostEditorController(scope: CoroutineScope, pairedServers: PairedServerCollectionStore,
  appPreferences: AppPreferences)` — the machine. Exposes `val state: StateFlow<HostEditorState?>`
  and `open(serverId)`, `submitName(name)`, `requestUnpair()`, `declineUnpair()`, `confirmUnpair()`,
  `dismiss()`. Bodies move verbatim; only `viewModelScope` becomes the injected `scope` and
  `hostEditor` becomes the controller's own private `MutableStateFlow`. Every `RelayLog` event name
  is preserved so the feature docs and any field log continue to read.
- `@Composable internal fun HostEditorModal(state: HostEditorState?, onSubmit: (String) -> Unit,
  onUnpairRequested, onUnpairConfirmed, onUnpairDeclined, onDismissRequest)` — renders nothing when
  `state` is null, `EditHostModal` otherwise. It owns the three things that would otherwise be copied
  into the second screen: the presence rule, `loading = saving`, and the `unpairFailed` →
  `edit_host_unpair_failed` / `failed` → `edit_host_save_failed` resolution. Keeping the string
  resolution here is what keeps both view models free of `Context` and keeps AC3's generic-failure
  requirement enforced in one place rather than agreed to in two.

Verbatim moves, not rewrites: the point is that the verifier can diff the moved bodies against the
originals and see nothing changed but the scope field.

### `ChannelListViewModel`

Constructs `HostEditorController(viewModelScope, pairedServers, appPreferences)`, combines
`controller.state` into `hostState` where the private `hostEditor` flow used to sit, and keeps its
six public methods as one-line delegations. The public surface is unchanged, so `MainActivity`'s
channel-list dispatch and `HostChannelListViewModelTest`'s proofs are untouched by the move — which
is the ticket's stated requirement and the cheapest available evidence that the move is behaviour-
preserving. `ChannelListScreen` swaps its inline `EditHostModal(...)` block for `HostEditorModal`.

### `SettingsViewModel`

Gains a fifth constructor parameter, `pairedServers: PairedServerCollectionStore`, and its own
`HostEditorController` over `viewModelScope`. Exposes `hostEditor: StateFlow<HostEditorState?>` plus
the same six method names the channel list uses, so the two screens' wiring reads alike.

`openOwnerHostEditor()` is the one Settings-specific transition: it rejects a blank `ownerServerId`
with `event=settings_host_editor_rejected code=no_owner` and otherwise opens on that exact captured
id — never on selection, and never on a row's own id. That is what makes "the owner's row, and only
the owner's" a property of the view model rather than a promise the screen keeps.

An owner that is no longer paired needs no branch of its own: `open` already reads the record through
`loadById` and rejects an absent one with `code=unknown_host`, and the screen offers no affordance in
that case anyway because no row carries `isOwner`.

### `SettingsScreen`

Seven new parameters, no defaults, matching the screen's existing style: `hostEditor:
HostEditorState?`, `onEditHost: () -> Unit`, and the five editor callbacks. In the Connection
section's row loop:

- `onClick = if (row.isOwner) ({ onEditHost() }) else ({ onOpenHost(row.serverId) })` — every other
  host's row keeps #750's host-to-host navigation exactly as it is.
- `trailing` for the owner becomes a `Row` of `HostOwnerBadge()` then `ChevronIcon()`, the badge and
  the chevron alongside each other per the frame and the ticket; non-owner rows keep `ChevronIcon()`.

`HostEditorModal(state = hostEditor, …)` is called beside `WorkspacePicker`, inside the `Scaffold`
body and outside the scrolling `Column`, as that sibling dialog already is.

The `isOwner` flag is the only gate: a destination that captured no host, or whose captured host is
no longer paired, has no `isOwner` row, therefore no chevron and no `onEditHost` path — AC1's and
AC4's "offers no editor at all; its rows still navigate" falls out of the existing projection rather
than needing a second nullable callback.

### `ThreadDestinationFactory.settings` (`di/AppModule.kt`)

Passes its existing `store` field as the new argument. No Koin definition changes: the factory
already holds `PairedServerCollectionStore` for `isSavedHost` and `hosts()`.

### `MainActivity`

Collects `vm.hostEditor` alongside the screen's other nine projections and threads the six callbacks
to the view model's methods. No navigation work on removal — see below.

## State + concurrency model

- The controller's `MutableStateFlow<HostEditorState?>` is the single source of truth for the open
  editor. Both owners publish it through their existing `StateFlow` surfaces, so each screen keeps
  its own editor: two Settings entries or a Settings entry and the channel list never share one.
- Every job runs in the injected `scope`, which is the owning view model's `viewModelScope` in
  production. Clearing the view model cancels the open-read, the rename and the removal. The
  controller owns no scope of its own and starts nothing at construction.
- `open` keeps its `editorOpenJob?.cancel()`: two taps racing on `loadById` must not let the slower
  reply publish. Read and written only from main-dispatcher tap dispatch, so no synchronisation.
- Terminal transitions stay `compareAndSet` against the state published before the call, so a write
  landing after a dismissal cannot resurrect a closed modal (AC3's "leaves the modal open with the
  typed name where the operator left it" depends on the failure arm copying rather than reopening —
  `EditHostModal` keys its buffer on `serverIdentity`, so the same instance staying published is what
  preserves the draft).
- The connection close on removal needs no call: `ObservablePairedServerStore.remove` bumps the
  revision and `RelayConnectionRegistry` reconciles by closing exactly the removed id's bundle. The
  same bump re-emits `hosts()`, so `SettingsViewModel.connection` re-resolves and the row disappears.
- **AC4 needs no navigation code at all.** Nothing in the Settings route reacts to a host list
  change, so the destination stays put, its `ownerMissing` arm lights up, and the app-wide sections
  keep rendering. Adding a pop or a retarget here is the failure mode, not the fix.

## Error handling

Failures at the store boundary are caught inside the controller, never rethrown into `scope` (an
escaping throw in `viewModelScope` reaches the default handler and kills the process):

| Failure | Controller | Screen |
|---|---|---|
| `loadById` throws | `event=host_editor_open_failed`, no state published | modal never opens |
| `loadById` returns null | `event=host_editor_open_rejected code=unknown_host` | modal never opens |
| `setDisplayName` throws | `compareAndSet` to `saving=false, failed=true` | generic `edit_host_save_failed`, modal open, draft intact |
| `remove` throws | `compareAndSet` to `saving=false, unpairFailed=true` | generic `edit_host_unpair_failed`, modal open, draft intact |
| `removeDefaultWorkspace` fails | not surfaced — the pairing is already gone | modal closes |
| blank owner in Settings | `event=settings_host_editor_rejected code=no_owner` | unreachable: no affordance |

No log line and no user-facing string carries a server id, a relay address, a host name or the
store's own message.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`, `runTest`, the `Store` fake shape `HostChannelListViewModelTest`
already uses — fakes, not MockK):

- `SettingsViewModelTest` — the owner's editor opens on the **captured** owner and carries its
  identity, relay address and current name; a blank owner rejects with `code=no_owner` and publishes
  nothing; a rename writes through to the store under the owner's id; `requestHostUnpair` arms the
  confirmation and `declineHostUnpair` returns to the editor writing nothing; `confirmHostUnpair`
  removes exactly the owner's pairing and its own default workspace, leaving a second host's stored
  record and that host's `defaultWorkspace` untouched, and leaving app-wide preferences untouched;
  a failing `remove` publishes `unpairFailed` with the editor still open and removes nothing.
- `HostChannelListViewModelTest` — unchanged, and green. That file is the move's regression proof.

Device (`app/src/androidTest/`, focused runs per § B2):

- `SettingsScreenTest` — the owner row is clickable and fires `onEditHost` (it was inert before
  #751); a non-owner row still fires `onOpenHost` with its own id; a non-null `hostEditor` renders
  the modal's own text; an `ownerMissing` / no-owner list renders no editor affordance.
- `ChannelListScreenTest` — import path only; its six existing editor scenarios are the proof that
  the shared `HostEditorModal` renders what the inline call rendered.

**No new e2e rung here.** AC5 assigns the rung-3 two-host pairing and unpair run to #676, and the
dispatcher owns the live regression gate. This ticket adds no scripted scenario and no rung-4 twin.

Gate for this ticket (§ B2): scoped `testDebugUnitTest`, `lint`, `assembleDebug`, and
`compileDebugAndroidTestKotlin` — the last one explicitly, because this change threads seven new
parameters through `SettingsScreen` and the `androidTest` threading cascade has been missed four
times on this exact screen (#232, #234, #268, and the near miss #398 caught).

## Sizing — two boundaries exceeded, split barred by depth

#751's parent is #713 and its grandparent is #637, so the split-depth cap bars a further split;
`needs-human:sizing` is already applied by the refiner. Per the depth-capped rule the work proceeds,
with the measurement recorded here rather than argued away:

- **Production source files: 7, against a ceiling of 5.** `ui/host/HostEditor.kt` (new),
  `ChannelListViewModel.kt`, `ChannelListScreen.kt`, `SettingsViewModel.kt`, `SettingsScreen.kt`,
  `MainActivity.kt`, `di/AppModule.kt`. The refiner forecast 6 and did not count `ChannelListScreen`,
  which must change because the shared modal composable replaces its inline call — the alternative,
  leaving the channel list calling `EditHostModal` directly, duplicates AC3's generic-error mapping
  into the second screen and is exactly the drift the ticket forbids.
- **Total written work: ~850 lines, against a ceiling of 800.** About 180 of those are the verbatim
  move of the existing machine and its documentation.
- The other four lines hold: 2 new exported types (`HostEditorController`, `HostEditorModal`; a third,
  `HostEditorState`, moves rather than appearing), ~10 consumer call sites, 5 acceptance criteria,
  and no new reject branch beyond the blank-owner one.

The split I would otherwise have proposed — a first slice extracting the controller, a second wiring
Settings to it — fails the sizing floor as well as the depth cap: the extracted seam's only consumer
is this slice, so the first child would land a shared object nothing outside its own family calls.
Floor beats ceiling, so the merged ticket is the right unit even at 7 files.

## Open questions

1. Does the owner row's trailing want the badge and chevron in that order, or the chevron first?
   Resolve against the Figma render in Phase B; the frame's other rows put the chevron last at the
   right edge, which suggests badge-then-chevron.
2. Does `SettingsScreenTest`'s existing `connectionSection_rendersEverySavedHostAndMarksTheOwner`
   still hold? It asserts the owner's row has **no** click action, which #751 deliberately reverses.
   That assertion must be updated in this ticket, not deleted — the property it protects (exactly one
   badge, and the two rows' taps going to different places) survives in a changed form.

## Documentation handoff

Pending for the documentation stage, per the ticket's own handoff section — not written here:

- `docs/knowledge/features/settings-screen.md` — the owner row's new edit affordance (badge plus
  chevron), the modal on this screen, and what AC4 leaves behind after a removal.
- `docs/knowledge/features/settings-viewmodel.md` — the `pairedServers` dependency, `hostEditor`, and
  `openOwnerHostEditor`'s blank-owner reject.
- `docs/knowledge/features/channel-list-viewmodel.md` — the machine moved to
  `ui/host/HostEditor.kt`; the six public methods now delegate.
- `docs/knowledge/features/mobile-modal.md` — `EditHostModal` now has a second caller, both through
  the shared `HostEditorModal` binding.
- `docs/knowledge/features/paired-server-store.md` — the second entry point into the id-exact
  `remove`, and what a removal leaves behind.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. The boundary is one function, `HostEditorController.open`: it
  copies `entry.record.serverId`, `entry.record.relayUrl` and `entry.displayName` out and drops the
  `PairedServerEntry`, which also carries the pairing token and the server static key. **Phase B
  obligation:** the move must not add a record-typed field to `HostEditorState` — moving the type
  verbatim is what preserves the property, and re-authoring it is where it would be lost. The second
  caller's id has a single source: `openOwnerHostEditor` opens on the route-captured `ownerServerId`,
  never on a `SettingsHostRow.serverId` and never on compatibility selection, so the id that reaches
  the id-exact `remove` is the one the destination was constructed for. A host unpaired elsewhere
  while the modal is open leaves a stale id, which `remove` treats as a silent no-op — the failure is
  "nothing happens", never "the wrong host is removed".
- **[Tokens, secrets, credentials]** No finding. No token is generated, stored, rotated or compared
  here; no new storage is introduced. `HostEditorState` is a plain `data class` with no redacting
  `toString`, so a crash trace renders the server id, relay URL and host name — pre-existing, accepted
  in #744 and again in #750 for this screen (neither value is authentication material, and both are
  already drawn in the row beneath the modal). Unchanged and not widened by this ticket.
- **[File / storage operations]** No finding, and AC2's survival property is structural rather than
  incidental. `removeDefaultWorkspace` deletes `stringPreferencesKey("default_workspace_host:$serverId")`.
  Because that prefix is fixed and ends in `:`, no server id can make the concatenation equal a bare
  snake_case app-wide key (`theme_mode`, `notifications_enabled`, `push_token`, …), so the removal
  cannot reach an app-wide preference; and colliding with another host's key would require an
  identical id, which is the same host. No filesystem path, no traversal, no TOCTOU on this path.
- **[Inter-process / Android attack surface]** Not applicable, by design: this ticket adds no
  exported component, no `<intent-filter>`, no deep link, no `PendingIntent`, no content provider and
  no WebView. Its only new input is an internal navigation argument that has existed since #749.
- **[Cryptographic primitives]** Not applicable, by design: no primitive is touched. The removal and
  the rename go through the existing Keystore-wrapped `KeystorePairedServerStore.mutate`; the wrap
  key, its per-encrypt IV and the Noise stack are untouched, and nothing is hand-rolled. The
  `serverId` comparisons are routing equality on non-secret identifiers, so `==` is correct —
  constant-time comparison here would be cargo cult.
- **[Network & I/O]** Not applicable, by design: no network code, no frame decoding, no timeout or
  TLS decision. `relayAddress` stays display text under the obligation `EditHostModal` already
  states, and nothing on this path dials it. The removed host's socket closes through the registry's
  existing revision reconcile, so no new connection lifecycle is introduced.
- **[Error messages, logs, telemetry]** No finding. Every moved and new log line is content-free —
  an event name plus a static code — and `RelayLog.d` is gated on `BuildConfig.DEBUG`, a compile-time
  constant, so none of it reaches a release Logcat. Both user-facing failures are the existing
  generic strings, resolved in one place by `HostEditorModal`, and neither takes a format argument.
  **MUST-NOT-log, restated for Phase B:** no server id, relay URL, host name, or store exception
  message in any new line.
- **[Concurrency]** One SHOULD FIX. Two controllers can be live at once — the channel list stays on
  the back stack beneath Settings — each able to have a write in flight against the same store. That
  is safe: `KeystorePairedServerStore.mutate` performs its read **inside** `dataStore.edit`, and
  DataStore serializes edits per instance, so an overlapping rename and removal cannot lose an update
  or resurrect a removed host. **SHOULD FIX:** `HostEditorController`'s `scope` parameter must carry
  a KDoc obligation that it is the owning view model's `viewModelScope` — an application scope would
  outlive the screen and leave a write publishing into an editor nothing is watching. A
  `ViewModel`-typed parameter would close it structurally but defeat the seam, so the obligation is
  documented rather than enforced. The verifier should check the KDoc landed.
- **[Threat model alignment]** Malicious relay and hostile daemon frame are off this path — the
  ticket does no wire work and decodes no frame. Token-theft posture is unchanged. **UI-side leakage
  — OUT OF SCOPE, named:** the modal puts one host's identity and relay address on screen above a row
  already showing both, so screenshot and accessibility harvesting of the saved-host inventory stays
  exactly where #750's review left it. No ticket is assigned to it; this one does not widen it.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

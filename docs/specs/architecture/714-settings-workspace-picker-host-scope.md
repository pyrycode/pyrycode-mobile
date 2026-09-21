# Spec: scope the Settings workspace picker to its host (#714)

**Size:** S. Blockers #711, #712 and #749 are merged; this closes the Settings half of the
host-scoped default workspace.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `SettingsViewModel` —
  holds `ownerServerId` from #749 but still reads and writes the unqualified default through
  `defaultWorkspace` / `onSelectDefaultWorkspace`. The file this ticket changes.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.SETTINGS` `composable`,
  `HostWorkspaceRepository`, `HostDestination`, `Routes.settingsOwner` — the Settings destination is
  the one picker host with no `LocalWorkspacePickerRepository` provider around it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` →
  `openHostWorkspacePicker`, `pickHostWorkspace`, `dismissHostWorkspacePicker`,
  `HostChannelListState.workspacePickerServerId` — #636/#738's ownership pattern, reused verbatim.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → its
  `WorkspacePicker` call, where `visible` is read off the same nullable that keys the provider.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` →
  `LocalWorkspacePickerRepository`, `WorkspacePicker`, `WorkspacePickerInternal` — the picker reads
  recents and creates folders through whichever repository the local resolves to.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` → `defaultWorkspace(serverId)`,
  `setDefaultWorkspace(serverId, cwd)` and the legacy unqualified pair — #711's contract.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.settings`,
  `ThreadDestinationFactory.repository` — how a destination resolves its owner's repository.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` →
  `recentWorkspaces` — `switchToLive(emptyList())`, so an owner with no live connection yields an
  empty recents list rather than a crash. This is what makes the unconnected-owner path safe.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt` — the #749/#750
  harness this ticket's production wiring test extends: `PyryNavHost`, two saved hosts, `select`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt` →
  `threadWorkspacePickerKeepsOwnerAcrossSelectionChanges`, `assertOwnerPicker`,
  `assertNoOtherPickerCalls` — the exact assertion shape for "the picker talked only to its owner".
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` → `makeVm`, the seven
  `defaultWorkspace*` / `onSelectDefaultWorkspace` / picker tests that move to the host-keyed API.
- `docs/knowledge/features/workspace-picker.md` § "Repository ownership" — the lesson that drives
  this ticket's test split: *"Recents and `createWorkspaceFolder` must use the same owner as the
  caller's final workspace change… Binding only the ViewModel leaves this"* gap, and *"Direct seam
  tests verify behavior but cannot prove production repository ownership."*
- `docs/knowledge/features/app-preferences.md` § "Transitional legacy API" / "Consumer integration" —
  the legacy pair's documented semantics and the note naming #714 as the owner of Settings' migration.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Settings screen is a scrolling column of label-headed sections on `Schemes/Surface`; the row this
ticket touches is "Default workspace" under the `Schemes/Primary` "Defaults for new conversations"
header — an `M3/body/large` headline on `Schemes/On Surface` over an `M3/body/small` value on
`Schemes/On Surface Variant`, with a trailing chevron. `SettingsScreen` already draws exactly that,
and the picker it opens is the shared `WorkspacePicker`. **No visual change:** this ticket rebinds
what the row reads and writes, so the fidelity check is that the rendered row and sheet are
byte-identical to today's.

## Context

#711 gave `AppPreferences` a host-keyed default workspace, #712 made startup migrate to it and
creation read it, and #749 gave the Settings destination an owner captured into its route. Settings
itself never moved: `SettingsViewModel` still reads `appPreferences.defaultWorkspace` and writes
`setDefaultWorkspace(cwd)` — the transitional legacy pair — and the Settings destination is the one
remaining `WorkspacePicker` host with no `LocalWorkspacePickerRepository` provider, so its recents
and folder creation resolve through the compatibility `ConversationRepository`, which follows
whichever host the registry selected last. Two hosts' Settings therefore show one another's folders
and write one another's default.

After this ticket the displayed value, the folder list, the create-folder request and the stored
default all key off the same captured owner.

No ADR is warranted: this applies #636's existing ownership pattern to the last destination that
lacks it, and introduces no new decision.

## Design

Two production changes, plus two comments that this ticket makes false.

### `SettingsViewModel` — one nullable owns the picker

`ownerServerId` becomes a `private val` (the handlers need it). The picker's open-state flag changes
from a `Boolean` to the owner it was opened for, mirroring `ChannelListViewModel`'s
`pendingHostWorkspacePicker`:

```kotlin
private val pendingWorkspacePicker = MutableStateFlow<String?>(null)
/** The host this picker is reading and writing for, or null when no picker is open. */
val workspacePickerServerId: StateFlow<String?>
```

`workspacePickerVisible: StateFlow<Boolean>` is **removed**. The route derives visibility from the
same nullable — see below for why that matters.

- `defaultWorkspace` projects `appPreferences.defaultWorkspace(ownerServerId)` for a non-blank owner,
  and `flowOf(DEFAULT_SCRATCH_CWD)` for a blank one. Same `stateIn(WhileSubscribed)` recipe as its
  eight siblings; same `DEFAULT_SCRATCH_CWD` initial value.
- `onDefaultWorkspaceTapped()` sets the pending owner to `ownerServerId` when it is non-blank, and
  otherwise leaves the picker shut and logs a content-free reject. A destination that captured no
  host has no host to pick folders on.
- `onSelectDefaultWorkspace(path)` takes the pending owner or returns, clears it, then writes
  `appPreferences.setDefaultWorkspace(serverId, path)`. Reading the owner off the pending value
  rather than off `ownerServerId` is what makes a pick that arrives after a dismissal a no-op.
- `onWorkspacePickerDismissed()` clears the pending owner and writes nothing.

The eight app-wide preference projections and their handlers are untouched: theme, dynamic colour,
model, effort, YOLO and notifications stay app-wide, as the AC requires.

### The Settings route — provider keyed by the same nullable

`composable(Routes.SETTINGS)` collects `workspacePickerServerId` once and uses it twice:

```kotlin
HostWorkspaceRepository(pickerOwner, destinations) {
    SettingsScreen(..., workspacePickerVisible = pickerOwner != null, ...)
}
```

`HostWorkspaceRepository` already exists (#636) and already remembers `factory.repository(serverId)`
per id; Settings becomes its third caller. `SettingsScreen`'s own signature does not change, so its
preview and `SettingsScreenTest` call sites are untouched.

**Rejected alternative: bind the provider to the captured owner for the destination's whole life**
(`Routes.settingsOwner(backStackEntry.arguments)`), which is constant and so would avoid the
`staticCompositionLocalOf` recomposition on picker open and close. It splits the invariant across two
places: the route would decide the repository and the ViewModel would decide whether the sheet opens,
and a later change to either guard could let the sheet open with a null provider — which is precisely
`WorkspacePicker`'s fallback to the compatibility repository, the bug this ticket removes. Deriving
both from one nullable makes the disagreement unrepresentable rather than merely prohibited. The cost
is one redraw of a static column of rows, which `ChannelListScreen` already pays.

### Comments this ticket falsifies

- `LocalWorkspacePickerRepository`'s "settings retains its compatibility binding until #637" — all
  three production picker hosts now bind their owner.
- `ThreadDestinationFactory.settings`'s "[preferences] and [repository] stay compatibility-bound:
  app-wide settings are not host-scoped" — `preferences` is still the process-wide `AppPreferences`,
  but Settings now reads and writes it under the owner's key. `repository` stays compatibility-bound
  for the archived count until #715.

### What is deliberately not removed

`WorkspacePicker`'s `?: koinInject<ConversationRepository>()` fallback stays. It is the picker's
documented contract for a composition with no provider (`workspace-picker.md` § Configuration), and
it is still reachable from `SettingsScreen`'s previews and component tests. The ticket's "remove the
legacy Settings workspace adapter only once unused" is satisfied by Settings ceasing to depend on it;
`AppPreferences`'s unqualified `defaultWorkspace` / `setDefaultWorkspace(cwd)` pair likewise stays —
it is #711's transitional alias contract with its own suite in `HostWorkspacePreferencesTest`, and
retiring it is not this ticket's deliverable.

## State + concurrency model

No new jobs. `defaultWorkspace` is one more cold DataStore flow lifted with
`stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), DEFAULT_SCRATCH_CWD)`, exactly like its
siblings; it is cancelled with `viewModelScope`. `pendingWorkspacePicker` is a plain `MutableStateFlow`
mutated only from the main thread through the three handlers, with no read-then-write across a
suspension point. `onSelectDefaultWorkspace` reads and clears the pending owner **before**
`viewModelScope.launch`, so the write's target is fixed at the moment of the pick and a dismissal
racing the launch cannot retarget it. The write itself is fire-and-forget on `viewModelScope`, as
its seven sibling handlers are.

## Error handling

- **Blank owner.** No picker opens and nothing is written; the row shows the scratch sentinel.
  Logged content-free.
- **Pick with no pending owner** (a dismissal or a second pick racing the first). Dropped, logged.
- **Owner saved but not connected, or unpaired while the picker is open.** `factory.repository(id)`
  yields a `StableConversationRepository` over a null current repository, so `recentWorkspaces()`
  emits an empty list and `createWorkspaceFolder` fails into the picker's existing generic
  "Couldn't create folder" dialog. Neither path can reach another host — that is the point.
- **DataStore write failure.** `setDefaultWorkspace(serverId, cwd)` returns `Result<Unit>` and
  `AppPreferences.editWorkspace` already logs `event=workspace_default_set outcome=io_failure`
  content-free. The handler does not add a second log or a UI affordance: the row re-reads from the
  same flow, so a failed write leaves the old value visible, which is the honest result. No AC asks
  for an error surface here.

Logs name events and static codes only — never a path, a host label or a relay URL.

## Testing strategy

Unit (`app/src/test/.../SettingsViewModelTest.kt`), all with the existing `TemporaryFolder`-backed
DataStore and `makeVm`; the seven current workspace tests move to an explicit owner:

- Two owners, two view models over **one** `AppPreferences`: each reads and writes its own value, and
  neither sees the other's — the AC2 core.
- A blank owner reads the scratch sentinel, a tap leaves `workspacePickerServerId` null, and a pick
  writes nothing.
- `onDefaultWorkspaceTapped` exposes the owner; dismiss clears it and leaves the stored value alone;
  a pick after a dismiss writes nothing.
- The existing initial-value, re-emit-after-write and persist-and-close assertions, rekeyed.

Production wiring (`app/src/androidTest/.../SettingsNavigationTest.kt`), one test in the
`LiteralScreenNavigationTest` shape — that file's lesson is that the seam cannot prove production
ownership:

- Open Alpha's Settings with both hosts live, tap Default workspace, assert Alpha's recents are on
  screen and Bravo's are not; flip compatibility selection to Bravo while the sheet is open and
  assert both again; create a folder and assert the `create_workspace_folder` request reached Alpha's
  peer and that Bravo's peer saw no `recent_workspaces` or `create_workspace_folder` at all.
- The harness's read-only `DataStore` is replaced with a small in-memory one so the same test can
  assert the stored value landed under Alpha's key and Bravo's is untouched.

No new rung-3 scenario: #676 owns the live follow-up that picks different host defaults and reads
back each created discussion's workspace, and the dispatcher owns the live gate. This ticket's flow
is Settings-local and fully provable on the scripted ladder.

## Open questions

1. Does `SettingsNavigationTest`'s `NavigationPeer` answer `recent_workspaces` and
   `create_workspace_folder` per host the way `LiteralScreenNavigationTest` relies on? If not, the
   production test asserts the outbound frames on each peer only, which is still the AC1 claim.
2. Whether the in-memory `DataStore` belongs in `SettingsNavigationTest` alone or is worth sharing
   with `LiteralScreenNavigationTest`. Default: keep it local — sharing is a refactor of a file this
   ticket otherwise does not touch.

## Documentation handoff

Pending for the documentation stage; **not** written by this ticket.

- `docs/knowledge/features/settings-viewmodel.md` — the default-workspace projection and the three
  picker handlers are now host-keyed off the destination's captured owner, with the blank-owner
  no-op; `workspacePickerVisible` is replaced by `workspacePickerServerId`.
- `docs/knowledge/features/workspace-picker.md` § "Repository ownership" — Settings is now the third
  `HostWorkspaceRepository` caller, so no production picker host resolves through the compatibility
  binding; the fallback remains for previews and component tests.
- `docs/knowledge/features/app-preferences.md` § "Consumer integration" — Settings no longer uses the
  transitional legacy API, closing the #711/#712 round trip from Settings edit to discussion creation.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX, and the ticket *closes* one crossing. Today the Settings picker
  resolves through the compatibility `ConversationRepository`, so daemon A's folder paths are rendered
  on daemon B's Settings and a pick made there is stored as the app-wide default — a cross-host leak
  of one daemon's filesystem layout into another's screen. After this ticket the crossing is a single
  named seam per destination: `LocalWorkspacePickerRepository`, keyed by the owner the route captured.
  Daemon-authored path strings still cross into Compose, unchanged: `WorkspacePickerSheet` renders
  them with `Text`, never as markup, a URL or a filename the phone opens, and
  `CREATE_FOLDER_ERROR_MESSAGE` is a static literal that never interpolates a server message. A
  picked path is written as a DataStore *value*; the *key* is `default_workspace_host:<serverId>`
  built from the locally saved pairing identity, never from daemon-supplied text — so a hostile
  daemon can influence only its own host's stored default, which is its own filesystem anyway. Two
  distinct saved ids cannot collide on one key: the prefix is fixed and the id is the whole suffix.
- **[Trust boundaries — deferred]** OUT OF SCOPE: no per-path length bound on `recent_workspaces` /
  `create_workspace_folder` replies. This is a property of the wire layer and of #564/#565's picker,
  identical before and after this ticket for all three picker hosts; nothing here widens it, and the
  cap belongs in the envelope decoder, not in a Settings binding.
- **[Tokens, secrets, credentials]** No findings. This ticket touches no token, key or pairing record.
  `SettingsHost` / `SettingsHostRow`'s "never add a record-typed field" rule from #749/#750 is
  respected — the new state is a nullable `String` server id, an identity already drawn on screen by
  the host rows.
- **[File / storage operations]** No findings. Same app-private DataStore file, same atomic
  `dataStore.edit` transaction; the new key is a host-qualified sibling of the one already written.
  The phone never opens the picked path — it is sent back to the host that supplied it as a `cwd`, so
  no canonicalisation or TOCTOU boundary applies on this side. No new backup surface: the value class
  (a folder path) is one already stored unqualified.
- **[Inter-process / Android attack surface]** No findings. No exported component, PendingIntent,
  content provider or WebView is added. `AndroidManifest.xml` registers exactly one `intent-filter`,
  the launcher, and `PyryNavHost` declares no `navDeepLink`, so `Routes.SETTINGS` and its `serverId`
  argument are reachable only in-process. Were that to change, a Settings destination owning an
  arbitrary id is already inert: it renders "This host is no longer paired" and its picker resolves
  to a `StableConversationRepository` over no live connection.
- **[Cryptographic primitives]** N/A by design — no randomness, key, nonce or handshake code is in
  this diff. The Noise session and the vendored `noise-java` path are untouched.
- **[Network & I/O]** No findings, and one property worth pinning: opening the picker on a Settings
  destination whose owner is saved but **not** connected must not dial that host.
  `ThreadDestinationFactory.repository` resolves the bundle through `registry.connectionFor(serverId)`
  and falls back to `MutableStateFlow(null)`, and `StableConversationRepository.recentWorkspaces`
  is `switchToLive(emptyList())` — so a picker on an unconnected owner emits an empty list and opens
  no socket. Timeouts, TLS and the supervisor's backoff are inherited unchanged; no new verb, no new
  client.
- **[Error messages, logs, telemetry]** No findings. The three new call sites are `RelayLog.d`, which
  is gated on `BuildConfig.DEBUG` and builds its message inside the gate, so nothing reaches release
  Logcat. Each message carries an event name and a static code only — never the server id, the picked
  path, a host label or a relay URL — matching `ChannelListViewModel.openHostWorkspacePicker`'s
  precedent. The DataStore write's failure path already logs `outcome=io_failure` content-free inside
  `AppPreferences.editWorkspace`; this ticket adds no second log and no telemetry.
- **[Concurrency]** No findings. `onSelectDefaultWorkspace` reads and clears `pendingWorkspacePicker`
  before entering `viewModelScope.launch`, so there is no check-then-act across a suspension point and
  a dismissal racing the write cannot retarget it. Every new flow is `stateIn(viewModelScope, …)` and
  dies with the ViewModel. Adversarial check on the `staticCompositionLocalOf` flip (null → owner →
  null): it forces the content lambda to re-execute without skipping, but the composition's slot table
  is preserved, so `SettingsScreen`'s `rememberSaveable` dialog state is not reset — and the provider
  value is constant for the whole interval the sheet is open, so the picker's own
  `rememberSaveable { showCreateDialog }` cannot be dropped mid-write. `ChannelListScreen` already
  runs this exact shape in production.
- **[Threat model alignment]** Malicious relay: content-blind and on-path; a stalled
  `recent_workspaces` reply leaves the sheet on its `initialValue = emptyList()` rather than hanging,
  as today. Hostile daemon frame: paths are rendered as text and stored only under that daemon's own
  key. UI-side leakage: this ticket is a net reduction — one host's folder list stops appearing on
  another host's screen. Token theft from disk: unaffected, no new secret at rest.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

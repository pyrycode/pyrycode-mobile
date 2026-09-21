# Host editor — shared Edit host state machine

`ui/host/HostEditor.kt` (`de.pyryco.mobile.ui.host`) holds the Edit host modal's state, the machine
that drives it, and the one composable binding that draws it. Extracted from `ChannelListViewModel`
(#744/#745) into this shared seam in #751, when [Settings](settings-viewmodel.md) became the machine's
second driver. Two screens now open the same modal on their own host through the same code; there is
no second copy of the rename or the removal.

## Shape

```kotlin
data class HostEditorState(
    val serverId: String,
    val serverIdentity: String,
    val relayAddress: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val confirmingUnpair: Boolean = false,
    val unpairFailed: Boolean = false,
)

class HostEditorController(
    scope: CoroutineScope,
    pairedServers: PairedServerCollectionStore,
    appPreferences: AppPreferences,
) {
    val state: StateFlow<HostEditorState?>
    fun open(serverId: String)
    fun submitName(name: String)
    fun requestUnpair()
    fun declineUnpair()
    fun confirmUnpair()
    fun dismiss()
}

@Composable
internal fun HostEditorModal(
    state: HostEditorState?,
    onSubmit: (String) -> Unit,
    onUnpairRequested: () -> Unit,
    onUnpairConfirmed: () -> Unit,
    onUnpairDeclined: () -> Unit,
    onDismissRequest: () -> Unit,
)
```

Moved verbatim from `ChannelListViewModel.kt` — same fields, same six transitions, same KDoc,
only `viewModelScope` became the injected `scope`. The verbatim move is deliberate: it is what let the
verifier diff the moved bodies against the originals and confirm nothing changed but the scope field.

`HostEditorState` holds **display text and the target id only** — never the `PairedServer` record it
was read from. That record also carries the pairing token and the server static key; keeping it here
would put both credentials in a `StateFlow` that outlives the modal, for no gain, since the two fields
this state is allowed are copied out at open time and the record is dropped. It has no redacting
`toString`, so never give it a record-typed field — a crash trace renders whatever it holds.

`serverIdentity` and `relayAddress` are carried unclamped on purpose — the clamp is `EditHostModal`'s
own, keyed on the raw identity so two hosts sharing a long prefix cannot collapse onto one name buffer.
`failed` and `unpairFailed` are flags rather than messages, so the string resolves at the screen (inside
`HostEditorModal`, see below) and this class stays free of `Context`; that also keeps an identity or a
relay address from ever reaching the shell's live region. There is no name-draft field — `EditHostModal`
owns its own buffer, so a failed save keeps what the operator typed with no controller involvement,
provided the same `HostEditorState` instance stays published. `confirmingUnpair` is a flag on the open
editor rather than a second pending-target flow: the target is already `serverId`, captured at open
time and never re-resolved at confirmation time, so a removal never has a second source of truth for
which host it targets. `saving` covers a rename and a removal alike — "a write is in flight, block the
rest" is the same statement either way.

## The controller

One instance per owner — `ChannelListViewModel` and `SettingsViewModel` each construct their own over
their own `viewModelScope`, so two screens (or two Settings entries on the back stack) never share an
open editor, and clearing either owner cancels only its own open-read, rename or removal.

**`scope` must be the owning view model's `viewModelScope`.** An application-lifetime scope would
outlive the screen and leave a write publishing into an editor nothing is watching. A `ViewModel`-typed
parameter would enforce this structurally and defeat the point of a plain, dependency-light seam, so it
is a KDoc obligation on the caller rather than a compiler-checked one (flagged as a SHOULD FIX in the
\#751 security review; verified by reading the KDoc landed on both call sites, not by a type).

- `open(serverId)` reads `pairedServers.loadById(serverId)` and publishes a `HostEditorState` built
  from exactly two of the record's fields (`serverId`, `relayUrl`) plus the stored `displayName`
  (blank when absent). Held in a single `openJob`, cancelled on every call: two opens racing on a slow
  `loadById` must not let the slower reply publish over the faster one's target.
- `submitName(name)` trims and clamps to `MAX_WORKSPACE_LABEL_CHARS` itself (never trusted from the
  caller), maps a blank result to `null`, and writes through `setDisplayName`. A thrown
  `PairedServerStoreException` republishes `saving = false, failed = true`; success closes the editor.
- `requestUnpair()` / `declineUnpair()` arm and disarm the confirmation without writing. Both — and
  `confirmUnpair()` itself — carry the same `if (target.saving) return` guard `submitName` uses: the
  shell disables its OK while loading but leaves Cancel and the content live, so a decline or a second
  unpair request can land mid-write. Without the guard either would publish a new editor state and make
  the in-flight write's `compareAndSet` fail silently, stranding the modal on a step the store never
  took. (The first draft of `declineUnpair` shipped unguarded in #745 and had exactly this defect
  against an in-flight removal; the security review caught it before merge.)
- `confirmUnpair()` removes the pairing, then the host's cached default workspace, then closes the
  editor — that order is the requirement as code: a failed `remove` must leave the pairing intact, so
  the workspace cache is not cleared until removal reports success. `removeDefaultWorkspace`'s own
  failure is not surfaced — the pairing is already gone, so reporting a failure there would claim the
  host is still paired when it is not. No connection-close call: `pairedServers` resolves to
  [`ObservablePairedServerStore`](paired-server-store.md#wiring--usage), whose revision bump
  `RelayConnectionRegistry` reconciles by closing exactly the removed id's bundle.
- `dismiss()` is the only unguarded transition — Cancel, Close and Back all land here, publish `null`,
  and write nothing. Unlike the other five it needs no guard: `null` is the state a completing write
  lands on anyway, so there is no pending transition for it to strand.

Every terminal transition (`submitName`, `confirmUnpair`, and their failure arms) is
`MutableStateFlow.compareAndSet` against the state published before the call — a write that completes
after a dismissal cannot resurrect a closed modal or overwrite a newer one. Every `RelayLog.d` line is a
static event name plus code — `host_editor_opened`, `host_editor_open_failed`,
`host_editor_open_rejected code=unknown_host`, `host_name_save_started/_failed/_saved`,
`host_unpair_requested/_declined/_started/_failed/_unpaired`, `host_editor_dismissed` — never the
entered name, the identity or the relay address.

## `HostEditorModal`

The one composable either screen draws the modal through. Renders nothing while `state` is null,
`EditHostModal` otherwise. Owns the three things a second caller would otherwise have to re-derive: the
presence rule (drawn exactly while a state is published), `loading = state.saving`, and the failure-flag
→ string resolution (`unpairFailed` → `R.string.edit_host_unpair_failed`, `failed` →
`R.string.edit_host_save_failed`). Resolving the string here rather than in a view model is what keeps
both `HostEditorController` and its owners free of `Context` and makes it impossible for an identity or
a relay address to reach the shell's live region from either caller.

## Callers

- [`ChannelListViewModel`](channel-list-viewmodel.md) — the original owner (#744/#745). Constructs
  `HostEditorController(viewModelScope, pairedServers, appPreferences)` and keeps its six public
  methods (`openHostEditor`, `submitHostName`, `requestHostUnpair`, `declineHostUnpair`,
  `confirmHostUnpair`, `dismissHostEditor`) as one-line delegations, so the screen's event dispatch and
  `HostChannelListViewModelTest`'s existing proofs are untouched by the move.
- [`SettingsViewModel`](settings-viewmodel.md) — the second owner (#751). Constructs its own
  `HostEditorController` the same way and delegates the same five modal-driving methods, plus its own
  `openOwnerHostEditor()` — the one caller-specific transition, gating on the destination's captured
  owner rather than a row id (see that document for the gate).
- `ChannelListScreen` and `SettingsScreen` both call `HostEditorModal(state = …, onSubmit = …, …)`
  directly, replacing what was, before #751, an inline `EditHostModal` call in `ChannelListScreen` with
  its own copy of the failure-string resolution — see [Shared mobile modal § Callers](mobile-modal.md#callers).

## Testing

No new test file — the machine is proven by its two callers' own suites:

- [`HostChannelListViewModelTest`](channel-list-viewmodel.md#testing) — unchanged by the move, and
  still green. That is the move's regression proof: same fixture, same assertions, same behavior
  through the delegating one-liners.
- `SettingsViewModelTest`'s nine `hostEditor_*` cases (#751) — the second owner's coverage: opening on
  the captured owner (never a row id, never selection), a blank owner rejecting content-free, an owner
  no longer paired opening nothing, a rename writing under the owner's id, decline writing nothing, a
  confirmed removal taking exactly the owner's pairing and its own #711 workspace while a second host's
  pairing, workspace and every app-wide preference survive, a failed rename and a failed removal each
  keeping the editor open with a generic flag and nothing written, and dismissal writing nothing. Uses
  an in-memory `Store: PairedServerCollectionStore` fake (`renames` / `removals` lists, `failWrite` /
  `failRemove` switches) — the same fixture shape `HostChannelListViewModelTest`'s `Store` already
  established.
- `SettingsScreenTest` — `connectionSection_opensTheEditorFromTheOwnersRowOnly` /
  `_keepsHostToHostNavigationOnEveryOtherRow` (which row fires `onEditHost` vs. `onOpenHost`),
  `hostEditor_rendersTheModalForTheOpenTarget`, `hostEditor_showsTheUnpairConfirmationInPlace`,
  `hostEditor_reportsAFailedRemovalWithoutNamingTheHost` (asserts the relay address renders exactly
  once — the owner's row behind the scrim draws it legitimately per #750, so "absent from screen" is
  the wrong property; "the modal adds no second rendering of it" is the one that holds), and
  `connectionSection_offersNoEditorOnceTheOwnerIsNoLongerPaired`.
- `SettingsNavigationTest.tappingTheOwnersRowOpensTheEditorOnTheCapturedHostNotTheSelectedOne` — the
  join `SettingsScreen`'s `onEditHost` and `SettingsViewModel.openOwnerHostEditor` don't individually
  prove: it lives in `PyryNavHost`, so this test taps the owner's row on the production nav graph with
  a *different* host selected and asserts the modal's name field carries the captured owner's name, not
  the selected host's — the security review's "never on selection" claim, proven end to end. Landed a
  cycle late (`2d53a1d`) alongside restating `assertBadgedRowIs`, which had asserted the owner's row was
  the *inert* one — the property #751 deliberately reverses.

## Related

- [ChannelListViewModel](channel-list-viewmodel.md) — first owner, `ui/conversations/list/`
- [SettingsViewModel](settings-viewmodel.md) — second owner, `ui/settings/`, `openOwnerHostEditor`
- [Shared mobile modal](mobile-modal.md) — `EditHostModal` / `MobileModal`, the presentation layer
  `HostEditorModal` binds
- [Paired server store](paired-server-store.md) — `PairedServerCollectionStore.loadById` /
  `setDisplayName` / `remove`, the controller's only store
- [App preferences](app-preferences.md) — `removeDefaultWorkspace`, called only from `confirmUnpair`
- Specs: `docs/specs/architecture/744-host-row-edit-and-rename.md`,
  `docs/specs/architecture/745-unpair-host-from-edit-modal.md`,
  `docs/specs/architecture/751-settings-host-edit-and-unpair.md` (the extraction and Settings' second
  caller, including the security review's scope/concurrency findings)

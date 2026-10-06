# Host editor — shared Edit host state machine

`ui/host/HostEditor.kt` (`de.pyryco.mobile.ui.host`) holds the Edit host modal's state, the machine
that drives it, and the one composable binding that draws it. Extracted from `ChannelListViewModel`
(#744/#745) into this shared seam in #751, when [Settings](settings-viewmodel.md) became the machine's
second driver. Since #1239, only the channel list mounts this modal; Settings retains its controller
for compatibility but has no host-edit UI. The same flow also owns the host system prompt editor (#1775).

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
    val prompt: HostPromptState = HostPromptState.Loading,
    val editingPrompt: Boolean = false,
)

class HostEditorController(
    scope: CoroutineScope,
    pairedServers: PairedServerCollectionStore,
    appPreferences: AppPreferences,
    repositoryFor: (String) -> ConversationRepository? = { null },
) {
    val state: StateFlow<HostEditorState?>
    val lastHostUnpaired: Flow<Unit>
    fun open(serverId: String)
    fun submitName(name: String)
    fun requestUnpair()
    fun declineUnpair()
    fun confirmUnpair()
    fun dismiss()
    fun onPromptEvent(event: HostPromptEvent)
}

@Composable
internal fun HostEditorModal(
    state: HostEditorState?,
    onSubmit: (String) -> Unit,
    onUnpairRequested: () -> Unit,
    onUnpairConfirmed: () -> Unit,
    onUnpairDeclined: () -> Unit,
    onDismissRequest: () -> Unit,
    onPromptEvent: (HostPromptEvent) -> Unit = {},
)
```

`HostEditorState` contains display fields, the captured target id and prompt editing state, never the
`PairedServer` record (which also contains pairing credentials). `HostPromptState` distinguishes
`Loading`, `Unavailable` and `Loaded(confirmed, defaultPrompt, draft, saving, failed)`. Only a bounded,
successful read becomes Loaded; an empty string is a deliberately cleared value, never an unread
value. Loaded and `HostPromptEvent.Edit` redact all prompt text in their string representations,
including when nested in the outer state. Prompt text has no phone persistence or saved-state bundle.

`serverIdentity` and `relayAddress` are carried unclamped on purpose — the clamp is `EditHostModal`'s
own, keyed on the raw identity so two hosts sharing a long prefix cannot collapse onto one name buffer.
`failed` and `unpairFailed` are flags rather than messages, so the string resolves at the screen (inside
`HostEditorModal`, see below) and this class stays free of `Context`; that also keeps an identity or a
relay address from ever reaching the shell's live region. There is no name-draft field — `EditHostModal`
owns its own buffer, so a failed save keeps what the operator typed with no controller involvement,
provided the modal remains composed under the same raw host identity. `confirmingUnpair` is a flag on the open
editor rather than a second pending-target flow: the target is already `serverId`, captured at open
time and never re-resolved at confirmation time, so a removal never has a second source of truth for
which host it targets. `saving` covers a rename and a removal alike — "a write is in flight, block the
rest" is the same statement either way.

## The controller

One instance per owner — `ChannelListViewModel` and `SettingsViewModel` each construct their own over
their own `viewModelScope`. Controller state is never shared between destinations; clearing the owner
cancels its own reads and writes. Only the channel list currently exposes this UI.

**`scope` must be the owning view model's `viewModelScope`.** An application-lifetime scope would
outlive the screen and leave a write publishing into an editor nothing is watching. A `ViewModel`-typed
parameter would enforce this structurally and defeat the point of a plain, dependency-light seam, so it
is a KDoc obligation on the caller rather than a compiler-checked one (flagged as a SHOULD FIX in the
\#751 security review; verified by reading the KDoc landed on both call sites, not by a type).

- `open(serverId)` reads `pairedServers.loadById(serverId)` and publishes a `HostEditorState` built
  from exactly two of the record's fields (`serverId`, `relayUrl`) plus the stored `displayName`
  (blank when absent). Held in a single `openJob`, cancelled on every call: two opens racing on a slow
  `loadById` must not let the slower reply publish over the faster one's target. Each open also creates
  a generation token, checked before identity or prompt publication, including non-cooperative reads.
- `submitName(name)` trims and clamps to `MAX_WORKSPACE_LABEL_CHARS` itself (never trusted from the
  caller), maps a blank result to `null`, and writes through `setDisplayName`. A thrown
  `PairedServerStoreException` republishes `saving = false, failed = true`; success closes the editor.
  This clamp is a plain `take` and can still split a UTF-16 surrogate pair in an operator-*typed*
  name at the 128-char boundary — #851 fixed the daemon-written seed/round-trip clamps in
  `EditHostModal` and `workspaceDisplayName` but left this one out of scope; see
  [mobile modal § Callers](mobile-modal-callers.md#callers) for the fuller account.
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
  `RelayConnectionRegistry` reconciles by closing exactly the removed id's bundle. Last, after every
  cleanup above, it re-reads `pairedServers.list()`; an empty result sends once on `lastHostUnpaired`
  (#1323, matching desktop's `runUnpairServer` re-read + `onLastServerUnpaired`), which the owning
  destination collects in [`PyryNavHost`](navigation.md#returning-to-welcome-after-the-last-host-1323)
  to return to Welcome with the back stack cleared. A thrown read is logged
  (`event=host_last_check_failed`) and treated as hosts remaining — staying on the current screen is
  the safe default, since navigating on a spurious signal would be worse than not navigating on a real
  one. The read runs only after `onHostRemoved`'s registry reconciliation and the workspace clear
  finish, deliberately: an observer on `hostConnections`/`revision` going non-empty → empty would fire
  while those are still running, and popping the owning destination then would cancel its
  `viewModelScope` mid-cleanup.
- `dismiss()` is the only unguarded transition — Cancel, Close and Back all land here, publish `null`,
  and write nothing. Unlike the other five it needs no guard: `null` is the state a completing write
  lands on anyway, so there is no pending transition for it to strand.

Outer terminal transitions (`submitName`, `confirmUnpair`, and their failure arms) check the open
generation and use `MutableStateFlow.compareAndSet` against the pending state — a write that completes
after a dismissal cannot resurrect a closed modal or overwrite a newer one. Every `RelayLog.d` line is a
static event name plus code — `host_editor_opened`, `host_editor_open_failed`,
`host_editor_open_rejected code=unknown_host`, `host_name_save_started/_failed/_saved`,
`host_unpair_requested/_declined/_started/_failed/_unpaired`, `host_editor_dismissed` — never the
entered name, the identity or the relay address.

### Host prompt reads and writes

Opening Edit host reads `requestHostSystemPrompt` for the captured `serverId`. The controller resolves
`HostConversationSource.repositoryFor` at the read and again at each Save, so background/foreground
replacement does not bind a returning draft to a retired repository. Selected-host changes do not
retarget the editor. A missing repository, failed read or oversized current/default becomes Unavailable;
reopening after recovery makes another read. Exceptions become static failure flags; cancellation is
re-thrown, and logs contain only static events/outcomes.

Open copies acknowledged current text into the draft. Loading/Unavailable can be viewed but cannot
edit, reset or save. Reset copies the daemon-returned default into the draft only; there is no reset
wire verb or local default. Save sends the draft verbatim, including unchanged or empty strings,
whitespace and line breaks. `SystemPromptLimit.fits` supplies the inclusive 8192 UTF-8-byte bound;
over-limit drafts remain editable but cannot save. Both read and write acknowledgements are bounded
before publication. A successful acknowledgement supplies current/default and returns to Edit host
with its confirmed preview. Failure, including an unavailable connection, keeps the draft for retry
with a generic error. No session is started or refreshed: the host prompt takes effect at each
conversation's next session, before the channel prompt.

Only one prompt write runs at a time; field/reset are disabled during it. Discard returns to Edit host
without writing and restores the draft from confirmed text. Dismissal, discard or another open prevents
late writes from reopening or overwriting a newer editor through generation and pending-state identity
guards. Cancellation cannot roll back an already sent daemon write. The generation guard also matters
when reopening the *same* host: equality of outer state alone cannot identify the old open.

A prompt read can complete while rename/unpair owns a pending outer state. Replacing that state would
defeat the outer write's publication guard; dropping the read would leave Loading after a failed write.
`deferredPromptRead` retains Loaded/Unavailable separately and merges it on outer failure, then clears
it. Open/dismiss and successful outer completion clear it too. It must not overwrite a later prompt
acknowledgement. The controller tests cover both read outcomes for both outer failures and same-host
reopening races.

## `HostEditorModal`

The binding renders nothing for null state and otherwise keeps `EditHostModal` composed. It maps
outer saving/failure flags to the shared shell and static resource strings. The Host system prompt row
sits between the name field and Unpair host, with bodyLarge headline, bodySmall/onSurfaceVariant
subtitle and chevron. Only a successfully read empty value says “Empty”; Loading and Unavailable have
their own subtitles. Saved text is one ellipsized line.

`EditHostModal` owns the name buffer above its shell-selection branch. A dedicated prompt editor
replaces the shell without unmounting that buffer, so prompt save and discard both preserve an unsaved
host-name draft. Outer Cancel cannot undo an acknowledged prompt save.

The dedicated editor uses `MobileModal` close, Back and Cancel as Discard, and OK as Save. Its plain
multiline field has no empty hint, uses bodyMedium and the modal field colors/shape, and has a 280 dp
minimum well. Visible field content is capped at 24 lines with internal scrolling: an over-limit paste
must not grow the well until surrounding controls disappear. The helper uses bodySmall/onSurfaceVariant:
“Added to every conversation on this host, before the channel system prompt. A change takes effect
from each conversation's next session.” The outlined Reset to default action follows `UnpairAction`
geometry and appears exactly when draft differs from returned default. Reset hides it by changing
only the draft. Validation and save failure use a generic error.

## Callers

- [`ChannelListViewModel`](channel-list-viewmodel.md) constructs the controller over `viewModelScope`
  with `hostSource::repositoryFor`, delegates outer transitions and prompt events, and exposes
  `lastHostUnpaired` to navigation. `ChannelListScreen` mounts the shared binding.
- [`SettingsViewModel`](settings-viewmodel.md) retains its owner-scoped controller and delegations,
  including `lastHostUnpaired`, with the default unavailable resolver. Since #1239, `SettingsScreen`
  does not mount the editor; constructor compatibility does not restore that retired surface.

## Testing

`HostPromptControllerTest` covers unread gating, retry on reopen, explicit empty/verbatim saves,
acknowledged previews, reset/discard, inclusive multibyte limits, failed/null/replaced repositories,
captured hosts, non-cooperative reads, duplicate/late writes, same-host reopening and deferred reads
across failed rename/unpair. State/event/log assertions keep current/default/draft and raw exception
text out of diagnostics. Existing `HostChannelListViewModelTest` and Settings controller tests retain
rename/unpair and captured-owner coverage; historical Settings screen mounting tests describe the
pre-#1239 UI rather than a currently reachable editor.

Shared `HostPromptEditorTest` covers Loading/Unavailable/empty/filled/default controls, exact helper,
no hint, reset, generic errors, editable oversized drafts, save/discard name retention, close/Back,
and one-line ellipsized preview. A clickable row merges its descendants: waits for
`HOST_PROMPT_PREVIEW_TAG` plus subtitle text must query `useUnmergedTree = true` in component *and*
live tests. A merged-tree wait can time out before reset checks despite correctly rendered text.

`HostPromptCaptureTest.darkHostAndPromptStates` retains five synthetic dark-state PNGs and focused
XML/context under `app/src/androidTest/assets/host-prompt-1775/`. They establish geometry/content,
including close/OK pixels, not hardware blur or real system bars. The default fixture is synthetic;
production always reads the daemon default. `MobileModalTest.editHostFieldAndUnpairRemainReachableWithKeyboard`
retains keyboard reachability coverage. `UnpairNavigationTest` proves last-host navigation through
the production graph; see [Navigation](navigation.md#returning-to-welcome-after-the-last-host-1323).

The rung-3 `InteractiveStreamE2ETest.interactiveTurn_hostSystemPrompt_editsResetsAndCancels` exercises
custom save/fresh read/reopened preview, reset then Cancel, reset then OK, and bounded original-value
restoration. Controller/component fakes cover deterministic transitions; no new
`DeterministicInteractiveStreamE2ETest` twin is needed for this storage/editor flow without a Claude
turn. Full curated live evidence and its unrelated failure/rerun are recorded in the
[interactive stream ladder](../../e2e-interactive-stream.md#verification-status).

An isolated live scenario that overrides host instructions must capture the exact current value,
confirm its clear before starting a fresh session, and restore and confirm the captured value even
after setup or assertion failure. Resetting to the daemon default is not restoration of a custom
or empty original. The Stop scenario's `withClearedHostInstructions` guard (#1721) follows this
rule locally, preserving scenario order independence and visible restoration failures. Daemon
foreground guidance can otherwise make Claude refuse the hold before any tool permission appears;
the controlled interrupt request must also explain why the foreground hold is intentional. See
[rung-3 Stop coverage](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

## Related

- [ChannelListViewModel](channel-list-viewmodel.md) — first owner, `ui/conversations/list/`
- [SettingsViewModel](settings-viewmodel.md) — second owner, `ui/settings/`, `openOwnerHostEditor`
- [Shared mobile modal](mobile-modal.md) — `EditHostModal` / `MobileModal`, the presentation layer
  `HostEditorModal` binds
- [Paired server store](paired-server-store.md) — `PairedServerCollectionStore.loadById` /
  `setDisplayName` / `remove`, the pairing store used for identity, rename and unpair
- [App preferences](app-preferences.md) — `removeDefaultWorkspace`, called only from `confirmUnpair`
- Spec: [Host system prompt editor](../../specs/architecture/1775-host-system-prompt-editor.md) — prompt UI, concurrency and security review.
- [System prompt editor](system-prompt-editor.md) — channel semantics and why construction-bound repositories are unsafe for reconnecting list modals.
- Specs: `docs/specs/architecture/744-host-row-edit-and-rename.md`,
  `docs/specs/architecture/745-unpair-host-from-edit-modal.md`,
  `docs/specs/architecture/751-settings-host-edit-and-unpair.md` (the extraction and Settings' second
  caller, including the security review's scope/concurrency findings)
- [`ChannelEditorController`](channel-list-viewmodel.md#channeleditorcontroller-667--1561) (#1561) — the
  second plain-class extraction built on this one's shape: `ChannelListViewModel`'s Edit channel machine
  (#667) pulled out the same way, so `ThreadViewModel` could compose a second instance over its own
  repository without inheriting the list's `HostConversationSource` lookup. One difference from `open`
  above: this controller's `open` publishes the editor state synchronously, from name/mute the caller
  already read off its own snapshot, and only the system-prompt read is async (still cancelled on every
  call, the same race guard this controller's `openJob` gives `loadById`) — Edit channel has no
  equivalent of a slow identity lookup standing between the tap and the modal's first paint.

# Open the edit-host modal from a host row and rename the host (#744)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` → `EditHostModal` — the component
  this slice drives, and its caller-obligation list: pass display text, keep `error` generic, and do not
  change `serverIdentity` while the modal is open. Not edited.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileModal` — how `loading`,
  `submissionEnabled` and `error` are consumed, and that Cancel/Close both route through
  `onDismissRequest` while `Dialog`'s own Back does too (`dismissOnClickOutside = false`, so the scrim
  is not a fourth route). Confirms one dismissal callback covers AC3's three routes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` →
  `TreeHostRow`, `TreeAddControl`, `treeHostAddTestTag`, `boundedRowText` — the row that gains the
  control, the control shape to reuse, the tag convention and the clamp.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListEvent`,
  `ChannelListScreen`, `treeSection` — the sealed event set, where the workspace picker is hung off the
  screen root (the modal follows it), and the row's `serverId`-carrying callbacks.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` →
  `ChannelListViewModel`, `HostChannelListState`, `pendingHostWorkspacePicker`, `collapsedKeys` — the
  four-flow `combine` the editor joins and the picker's target as the shape to mirror.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` →
  `PairedServerCollectionStore.loadById` / `setDisplayName`, `PairedServerEntry`, `PairedServer`,
  `PairedServerStoreException` — the read and the write, and that `PairedServer` holds `token` and
  `serverStaticPublicKey` beside the two fields this slice may read.
- `app/src/main/java/de/pyryco/mobile/data/crypto/KeystorePairedServerStore.kt` →
  `list`, `mutate`, `failureCode` — the actual failure surface: `list` (so `loadById`) swallows
  classified failures to an empty list but **rethrows an unclassified one**, and `mutate` throws
  `PairedServerStoreException` on a classified one and is a silent no-op for an absent id.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `setDisplayName` — bumps
  `revision` only after the delegate's write returns, which is the first link of the no-reconnect chain.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt` → its `store.revision` collector —
  the second link: a revision bump re-lists and rebuilds `hostConnections` with the new `displayName`.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `reconcile`, `Held` — the third
  link: an entry whose `repositories` flow is unchanged keeps its job and takes
  `copy(displayName = …)` onto the held snapshot, so the row renames without a reconnect.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ChannelListViewModel` factory and the
  `ObservablePairedServerStore` binding that already exposes `PairedServerCollectionStore`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `Routes.CHANNEL_LIST` composable's
  exhaustive `when` over `ChannelListEvent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` →
  `launchGuardedRepoCall` — read and **not** reused here: its catch set is the relay repository's three
  throws, not the store's, and this slice needs an error surface rather than an inert swallow.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` →
  `Fixture` — the view model is built through Koin with `appModule` plus overrides, so a third
  constructor dependency needs a fake bound in the override module or the test resolves the real
  Keystore-backed store on the JVM.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` → `LatestStore` — the existing
  in-memory `PairedServerCollectionStore` double to model the new fake on.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`,
  `.../list/ChannelListScreenTest.kt` → the five and one `TreeHostRow` call sites a new required
  parameter reaches.
- `docs/knowledge/features/channel-list-screen.md` § "Add controls (#738)", § "Tree rows (#730)" —
  the 48dp trade, the persistent-control rule the phone substitutes for hover, and the tag convention.
- `docs/knowledge/features/mobile-modal.md` — the shell's caller contract as documented, including that
  the shell closes on nothing. Read, not edited (documentation stage owns it).
- `docs/knowledge/features/paired-server-store.md` — the store's metadata-vs-credential split.
- `docs/specs/architecture/738-list-add-controls-retire-fab.md`, `743-edit-host-modal.md` — the
  precedent wiring chain and the component's own spec.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

Node `533-2369` is the Edit host modal itself, which #743 already drew and this slice does not change.
The design input for *this* slice is the host row's edit control in the sidebar adaptation `133-259`
under node `15-8`: the `Macbook` row's hover treatment draws a primary-tinted pencil immediately inboard
of the trailing plus, with the two connection-leg dots swapped out for the pair. The phone has no hover,
so — exactly as #738 did for the plus — the pencil is drawn persistently and the dots are kept, giving a
trailing run of dots, pencil, plus in the design's own left-to-right order.

## Context

The tree draws a row per paired host and offers no way to edit one. `TreeHostRow` reserves the design's
pencil slot in its own comment and #743 landed the modal that slot should open, stateless and
caller-driven. This slice supplies the caller: the control, the state that survives recomposition, the
read that fills the modal, and the write that renames the host. Unpairing from the same modal is #745;
the live emulator scenario is #676, which is blocked by this ticket.

Nothing new is decided, so no ADR is warranted. The slice consumes three existing decisions — the
persistent-control substitution for hover (#738), the app-authored `testTag` as the device suites' handle
(#731), and the store's metadata-vs-credential split (#292) — and adds no fourth.

## Design

### The row's control

`TreeAddControl` is renamed `TreeRowControl` and gains a leading `icon: ImageVector` parameter. Its body
is already glyph-agnostic — a `TreeAddTouchSize` box, `CircleShape`, `combinedClickable` with the
caller's labels, a `TreeGlyphSize` icon tinted `primary` — so the pencil needs the parameter and nothing
else. Three in-file call sites move with the rename; the composable is private, so nothing outside the
file sees it.

`TreeHostRow` gains one required parameter, `onEditTapped: () -> Unit`, and draws a second
`TreeRowControl` with `Icons.Filled.Edit` between `ConnectionLegPair` and the add control. The glyph
choice matches the design's pencil to the Material set the same way #730 matched `Dns` and `FolderOpen`
and #737 matched `Archive`, rather than vendoring a drawable.

Its accessible name is `cd_tree_host_edit` formatted with the row's already-clamped `bounded` name, so
the two controls on one row are distinguishable and a row-per-host tree stays unambiguous — the reason
#738's names carry the host. No long-press path: the control has one action.

`treeHostEditTestTag(serverId)` mirrors `treeHostAddTestTag`; the clamp-with-length-suffix both need
moves into one private helper so the two tags cannot drift apart.

### Event surface

`ChannelListEvent` gains three cases, all carrying what the caller needs and nothing more:

- `TreeHostEditTapped(serverId: String)` — the row's **own** host, as `TreeHostAddTapped` does.
- `HostEditNameSubmitted(name: String)` — the entered name. No `serverId`: the target is the open
  editor's, held in the view model, and a second id on the event would be a second source of truth for
  which host is being renamed.
- `HostEditDismissed` — all three of Cancel, Close and Back, which the shell routes through one callback.

The modal's `Unpair host` action is wired to an empty lambda at the screen, not to a fourth event. #745
owns that path; an event plus a no-op dispatch arm would add surface that establishes no capability, and
the inert result AC5 asks for is what an empty lambda already is.

### View-model state

A new `HostEditorState` joins `HostChannelListState` as `hostEditor: HostEditorState?`, published from a
fifth `MutableStateFlow` in the existing `combine` — beside the picker's target, the fold set and the
selection, and for the reason their comment gives: it must survive recomposition, `LazyColumn` recycling
and an incoming snapshot.

```kotlin
data class HostEditorState(
    val serverId: String,
    val serverIdentity: String,
    val relayAddress: String,
    val initialName: String,
    val saving: Boolean = false,
    val failed: Boolean = false,
)
```

Three properties of that shape are load-bearing:

- It holds **two display strings, not the record.** `PairedServer` also holds `token` and
  `serverStaticPublicKey`; keeping the entry in published UI state would put both in a `StateFlow` that
  outlives the modal. The two fields AC2 allows are copied out at open time and the entry is dropped.
- `failed` is a flag, not a message. The string is resolved at the screen from
  `edit_host_save_failed`, which keeps the view model free of `Context` and makes it impossible for an
  identity or a relay address to reach the live region AC4 guards.
- `serverIdentity` is set once per open and never rewritten, which is the component's stated obligation:
  it keys the name buffer, so touching it mid-edit would discard what the operator typed. It is carried
  **unclamped**, deliberately. `parsePairingPayload` bounds neither `server` nor `relay` in length — it
  validates the relay's scheme and host and explicitly tolerates a path — so both are QR-authored and
  unbounded here. The clamp belongs where `EditHostModal.boundedText` already puts it, at the component's
  own boundary before layout and semantics, because that component keys its buffer on the raw identity
  precisely so two hosts sharing a 128-character prefix cannot collapse onto one buffer. Clamping in this
  state would hand the component a value that defeats that. Nothing else reads these two fields, and a
  data class's generated `equals` short-circuits on reference identity, so the shared instance riding
  along in `HostChannelListState` is never string-compared as snapshots arrive.

The name draft is deliberately absent. `EditHostModal` owns its own buffer, so a failed save keeps the
entered value with no view-model involvement at all — provided the same state instance stays published,
which is why the failure path copies rather than reopens.

### Three methods

- `openHostEditor(serverId)` — reads `loadById(serverId)` and publishes
  `HostEditorState(serverId, record.serverId, record.relayUrl, entry.displayName ?: "")`, mapping a blank
  stored name to `""` the same way the row maps it to its placeholder. A `null` entry publishes nothing
  and logs a content-free reject: an id no longer stored has no record to show, and the design's two
  identity rows cannot be drawn from an absent one.

  It holds its launch in a single `editorOpenJob` field and cancels the previous one first, so only the
  most recently tapped pencil can publish. Without that, two taps on two rows' pencils while the first
  `loadById` is still decrypting race on the assignment, and the slower one wins: the modal then shows —
  and renames — a host the operator did not tap last. That is the wrong-host outcome AC1 exists to
  forbid, reached by a route the row's own `serverId` cannot close, because both events carry a correct
  id and the defect is in which reply lands. Cancellation is the deterministic fix: a cancelled launch
  cannot publish at all, rather than being asked to check whether it still should.
- `submitHostName(name)` — no-ops without an open editor or while one is already saving, publishes
  `saving = true, failed = false`, then calls `setDisplayName(serverId, …)` with
  `name.trim().take(MAX_WORKSPACE_LABEL_CHARS).ifBlank { null }`. The trim is the view model's own rather
  than trusted from the component, so the method's contract holds for a unit-test caller too. Success
  closes the editor; a throw republishes `saving = false, failed = true`.

  The clamp is the same `MAX_WORKSPACE_LABEL_CHARS` every surface that renders a host name already
  applies, moved to the write. The name is operator-authored, but a `singleLine` field still accepts an
  arbitrarily long paste, and this value is stored **inside the encrypted pairing blob** that
  `KeystorePairedServerStore.list` decrypts and JSON-parses on every revision bump and every registry
  reconcile — so an oversized name is a recurring cost on the same read path that loads credentials, not
  a one-off. Bytes past the bound were never renderable anyway: the row and the modal both clamp to it.
  The visible tradeoff is that a name longer than the bound is stored truncated, which is exactly what
  every surface would have displayed.
- `dismissHostEditor()` — publishes `null` and logs. It writes nothing, which is AC3's "change nothing".

Both terminal transitions use `MutableStateFlow.compareAndSet(pending, …)` against the exact state
published before the call, so a save that completes after a dismissal cannot resurrect a closed modal or
overwrite a newer one.

### Failure handling

| Failure | Where | Result |
| --- | --- | --- |
| `setDisplayName` throws `PairedServerStoreException` | classified Keystore/IO/parse failure in `mutate` | editor stays open, `failed = true`, previous name kept |
| `setDisplayName` throws anything else | `mutate`'s `?: throw e` re-raise | same arm — the catch is on `Exception` with `CancellationException` rethrown first |
| `loadById` throws | `list`'s `?: throw e` re-raise | editor stays closed, content-free log |
| `loadById` returns null | absent id, or storage classified-unreadable | editor stays closed, content-free log |
| `setDisplayName` silently no-ops | id no longer stored | treated as success; the host is already gone from the tree |

The `catch (e: Exception)` with a leading `CancellationException` rethrow is this file's existing shape in
`sendHostDiscussion`, and the leading arm is mandatory for the reason `launchGuardedRepoCall` documents:
`java.util.concurrent.CancellationException` extends `IllegalStateException` on the JVM. Catching the
store's unclassified re-raise matters because these launches are in `viewModelScope`, where an escaping
throw reaches the default handler and kills the process.

Every new log line is an event name and a static code — never the entered name, the identity or the
relay address: `host_editor_opened`, `host_editor_open_rejected code=unknown_host`,
`host_editor_open_failed`, `host_name_save_started`, `host_name_saved`, `host_name_save_failed`,
`host_editor_dismissed`.

### Screen wiring

`ChannelListScreen` hangs `EditHostModal` off its root beside `WorkspacePicker`, drawn only when
`hostState.hostEditor != null`, reading straight off that state rather than a copy:

```kotlin
hostState.hostEditor?.let { editor ->
    EditHostModal(
        serverIdentity = editor.serverIdentity,
        relayAddress = editor.relayAddress,
        initialHostName = editor.initialName,
        onDismissRequest = { onEvent(ChannelListEvent.HostEditDismissed) },
        onSubmit = { name -> onEvent(ChannelListEvent.HostEditNameSubmitted(name)) },
        onUnpairRequested = {},
        loading = editor.saving,
        error = if (editor.failed) stringResource(R.string.edit_host_save_failed) else null,
    )
}
```

`submissionEnabled` keeps its default `true`: a blank name must be submittable, because clearing the
name is how AC3 returns a host to its unnamed treatment.

`MainActivity`'s `when` gains three arms — `openHostEditor`, `submitHostName`, `dismissHostEditor` —
and `AppModule`'s factory line gains a third `get()` for the store `ObservablePairedServerStore` already
binds.

## State + concurrency model

Two `viewModelScope` launches, one per store call, both cancelled with the view model — the same
ownership `createHostDiscussion` has. The open's launch is additionally cancelled by the next open
through the `editorOpenJob` field, which is read and written only from the main dispatcher (a tap
dispatch), so it needs no synchronisation of its own. `dismissHostEditor` launches nothing; it is a
plain `MutableStateFlow` assignment.

`MutableStateFlow<HostEditorState?>` is hot and conflated; it is combined into the existing
`stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS))` projection, so the editor's target
survives the list going briefly unsubscribed during the thread round trip exactly as the fold set does.
Nothing new subscribes and nothing new is cold-collected.

The store's own dispatcher discipline is unchanged: `KeystorePairedServerStore` moves every read and
write to `Dispatchers.IO` itself, so these launches stay on the view model's default dispatcher and add
no `withContext` of their own.

Check-then-act is confined to the two terminal transitions, both via `compareAndSet` against the
state published before the suspension point. `submitHostName`'s saving guard is a same-dispatch read of
`hostEditor.value` with no suspension between the read and the write, so no atomicity is owed there.

A background close mid-save changes nothing: the write is local storage through DataStore, not the relay
socket, so it completes (or throws) independently of `LifecycleConnectionDriver`. Process death mid-save
leaves DataStore's own atomic replace as the only writer, which is the store's existing guarantee.

## Error handling

`setDisplayName`'s classified failures arrive as `PairedServerStoreException` and surface as
`failed = true`, rendered by the screen as one static string in the shell's live region. `loadById`
failures leave the modal closed. No failure reaches `Toast`, a crash reporter, or a log line carrying
caller data. The UI never renders an exception message — only `edit_host_save_failed`.

## Testing strategy

Unit tests in `HostChannelListViewModelTest`, whose `Fixture` gains an in-memory
`PairedServerCollectionStore` fake bound in the override module (the Koin-built view model would
otherwise resolve the Keystore-backed store on the JVM):

- opening publishes the host's stored `serverId` and `relayUrl` and its current name; an unnamed and a
  blank-named host each open with `""`; a later snapshot leaves the published editor state untouched.
- opening an unknown id publishes no editor.
- a second open while the first host's read is still in flight publishes the **second** host's editor and
  never the first's — the race the `editorOpenJob` cancellation closes, gated in the test by holding the
  fake store's read open.
- OK writes the trimmed name and closes; a blank and a whitespace-only name each write `null`; a name
  past `MAX_WORKSPACE_LABEL_CHARS` is written clamped.
- a throwing `setDisplayName` leaves the editor open with `saving = false`, `failed = true`, and the
  stored name unchanged; a dismissal during an in-flight save is not undone by its completion.
- dismissal closes the editor and writes nothing.

Compose UI tests:

- `ConversationTreeRowsTest` — the host row draws an edit control named for its host at its own
  `treeHostEditTestTag`, reports its tap, and does not fold the row; the add control still does its own
  job beside it.
- `ChannelListScreenTest` — the second host row's control emits `TreeHostEditTapped` with the **second**
  host's id; a non-null `hostEditor` draws the modal showing the identity, the relay address and the
  name; OK emits the entered name; `Unpair host` leaves the modal drawn and emits nothing.

No rung-3 or rung-4 emulator scenario lands here: #676 owns the live behaviour and is blocked by this
ticket, which is the ticket's own stated split. The focused regression coverage above stands in.

## Documentation handoff

Pending for the documentation stage; not written here.

- `docs/knowledge/features/channel-list-screen.md` — the host row's edit entry, under
  `## Add controls (#738)` or its own heading beside it, and the list's ownership of the editor state
  under `## Wiring`.
- `docs/knowledge/features/mobile-modal.md` — name the channel list as `EditHostModal`'s first driving
  caller, where that document records the shell's consumers.

## Open questions

1. Does the pencil-then-plus run fit a 320 dp viewport without the host name collapsing to nothing? The
   name has `weight(1f, fill = false)` inside a `weight(1f)` row, so it ellipsizes rather than pushing
   anything off — but the residue at 320 dp needs checking in the preview matrix, and if it is too thin
   the fallback is the design's own answer: the hover treatment hides the dots, so the phone could too.
2. Should a dismissal cancel an in-flight save rather than letting it complete? Kept as let-it-complete
   for now: the operator pressed OK, and AC3's "change nothing" is about Cancel/Close/Back on their own.

## Security review

**Verdict:** PASS (second run — the first run's verdict was FAIL on the concurrency finding below, and
the plan was revised before this pass rather than after the commit)

**Findings:**

- **[Trust boundaries]** No finding after revision — design decision recorded instead. The untrusted
  input is the stored pairing record's `serverId` and `relayUrl`, both QR-authored: `parsePairingPayload`
  checks the relay's scheme and host and deliberately tolerates a path, and bounds neither field's
  length. The boundary is the single explicit `EditHostModal.boundedText`, which clamps all three of its
  text inputs before layout or semantics. This slice passes both values through raw on purpose — the
  component keys its name buffer on the raw identity so two hosts sharing a 128-character prefix cannot
  collapse onto one buffer, and clamping upstream would defeat that. The first pass proposed clamping in
  `HostEditorState` on the theory that `StateFlow` conflation would string-compare the values on every
  snapshot; that is wrong — a data class's generated `equals` short-circuits on reference identity and
  the same editor instance rides along unchanged, so the comparison never reaches the strings. Nothing
  outside `EditHostModal` reads either field.
- **[Tokens, secrets, credentials]** No finding — the design reads exactly two fields,
  `record.serverId` and `record.relayUrl`, and drops the `PairedServerEntry` at the end of the open.
  `token` and `serverStaticPublicKey` are never read, so they never enter UI state, a screenshot buffer
  or a heap dump reachable from the view model. Holding the entry (or the record) in `HostEditorState`
  instead would put both credentials in a `StateFlow` that outlives the modal — the plan forbids it in
  the state section, which is the one place an implementer would be tempted. No token is created, stored,
  rotated or compared here; revocation is `Unpair host`, which is inert in this slice and is #745's.
- **[File / storage operations]** SHOULD FIX, applied in the plan: bound the saved display name to
  `MAX_WORKSPACE_LABEL_CHARS` at the write. No path is built from input, so there is no traversal or
  TOCTOU surface, and the write reuses `KeystorePairedServerStore.mutate`, which reads inside
  `dataStore.edit` (so an overlapping save is not lost) and leaves DataStore's own atomic replace as the
  only writer — process death mid-save cannot leave a partial blob. Encryption at rest, storage scope and
  `allowBackup` are all inherited unchanged. The finding is that the name is operator-authored through a
  field that accepts an arbitrary paste, and it lands inside the encrypted blob that every revision bump
  and every registry reconcile decrypts and JSON-parses alongside the credentials; bytes past the bound
  are unrenderable on every existing surface anyway.
- **[Inter-process / Android attack surface]** No finding — no manifest change: no new or newly exported
  component, no `intent-filter`, no deep link, no `PendingIntent`, no content provider and no `WebView`.
  The modal is an in-process `Dialog`. Screenshot and accessibility exposure was considered and accepted:
  the identity and relay rows are on screen and in the merged semantics tree by the design's own
  requirement, and neither is a credential — which holds only because of the two-field read above.
- **[Cryptographic primitives]** No finding — no RNG, hash, KDF, AEAD or handshake is touched, and no
  key material is read. The Keystore wrap key is re-entered only through the existing `mutate`. The one
  comparison of an untrusted value, `loadById`'s `serverId` match, is a lookup and not an
  authentication, so constant-time comparison is not owed; nothing here compares anything to a secret.
- **[Network & I/O]** No finding — this slice makes no network call, and that is the point of AC3's
  "without a reconnect". `relayAddress` is display text only and is never handed to a URL parser or a
  connect path; the live endpoint stays the stored record's, resolved by the relay supervisor. Verified
  rather than assumed: `RelayConnectionRegistry.reconcile` tears down only entries whose *record*
  changed, and a rename changes none, so the bundle and `coordinator.currentRepository` survive by
  identity and `HostConversationSource.reconcile` keeps the held entry and copies the new name onto its
  snapshot. `mutate` preserves entry order, so the registry's `saved.lastOrNull()` selection does not
  move either. No timeout, TLS, pinning or backoff setting is in scope.
- **[Error messages, logs, telemetry]** No finding — the only user-facing failure text is the static
  `edit_host_save_failed`, which satisfies AC4's "without naming the identity or the relay address" by
  construction rather than by careful phrasing at a call site; no exception message reaches the UI. Every
  new log line is an event name plus a static code, listed exhaustively in the design section, and
  `RelayLog.d` builds its message only when `enabled`, which defaults to `BuildConfig.DEBUG` — so none
  of it reaches release Logcat. `PairedServer.toString` is redacted, so even an accidental interpolation
  of a whole record could not leak the token. No telemetry is added.
- **[Concurrency]** MUST FIX, fixed in the plan before this pass: two taps on two different rows'
  pencils, while the first `loadById` is still decrypting, raced on the publish, and the slower read
  won — the modal would show and then rename a host the operator did not tap last. This is the
  wrong-host class AC1 forbids, and carrying the row's own `serverId` on the event does not close it,
  because both events carry a correct id and the defect is in which reply lands. Fixed by holding the
  open in a single `editorOpenJob` and cancelling the previous launch, so a superseded read cannot
  publish at all. The remaining shared-state transitions use `compareAndSet` against the exact state
  published before the suspension point, so a save completing after a dismissal cannot resurrect a
  closed modal; the double-submit guard is deterministic in the view model rather than relying on the
  shell's disabled OK, which is a second mechanism of a different kind. Every launch is
  `viewModelScope`-owned, `CancellationException` is rethrown ahead of every typed catch, and no
  `NonCancellable` or mutex is introduced.
- **[Threat model alignment]** No finding, with three threats named. A **malicious relay** is not on this
  path: the rename is local storage only, and the design's no-reconnect property means a hostile relay
  cannot even observe it. **Token theft from disk** is unchanged — this slice adds no plaintext store and
  writes through the same Keystore-wrapped blob. A **hostile daemon frame** cannot reach this surface:
  the value being edited is operator-authored local metadata, which is a narrowing worth noting against
  `boundedRowText`'s comment that a host's `displayName` "arrives here unbounded" — after this slice it
  is authored locally and bounded at the write. Out of scope and named: unpairing and its revocation
  propagation (#745), and the live emulator proof of this flow (#676, blocked by this ticket).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

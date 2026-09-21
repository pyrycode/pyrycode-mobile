# #745 — Unpair one host from the Edit host modal

Wires the `Unpair host` action #744 left on an empty lambda: an explicit confirmation naming the host,
then a removal that drops that host's pairing, clears its cached default workspace and lets the existing
registry reconcile close its connection.

## Files read

| Path | Symbol | Why it matters |
| --- | --- | --- |
| `app/src/main/java/de/pyryco/mobile/ui/components/EditHostModal.kt` | `EditHostModal`, `UnpairAction`, `boundedText` | The frame this slice extends; `onUnpairRequested` is the seam, and `boundedText` is the one clamp boundary for every value the frame renders |
| `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` | `MobileModal` | The shell is itself a `Dialog` with a fixed Cancel/OK footer and a live-region `error` slot — that shape decides the confirmation treatment below |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` | `ChannelListEvent`, `ChannelListScreen` | Owns the event vocabulary and composes the modal off `hostState.hostEditor` |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` | `HostEditorState`, `openHostEditor`, `submitHostName`, `dismissHostEditor` | The editor's target and its terminal-transition discipline (`compareAndSet`) that the removal path copies |
| `app/src/main/java/de/pyryco/mobile/MainActivity.kt` | the `when (event)` on `ChannelListEvent` | Exhaustive, so every new variant lands here too |
| `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt` | `PairedServerCollectionStore.remove` | Id-exact, no-op on an unknown id, throws `PairedServerStoreException` on a failed write |
| `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` | `remove` | Bumps the revision **only after** the delegate's write returns — which is what makes the connection close follow the pairing removal rather than race it |
| `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` | `removeDefaultWorkspace`, `workspaceKey` | The host-owned workspace cache; returns `Result<Unit>` and swallows `IOException` rather than throwing |
| `app/src/main/res/values/strings.xml` | `edit_host_*`, `unnamed_host` | The existing modal strings and the app-wide unnamed-host fallback the confirmation must reuse |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` | `Fixture`, `Store`, `preferences` | The fakes this slice extends: `Store.remove` is `error("unused")` today and the stub `DataStore` cannot serve `edit` |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` | `setTree`, `openEditor`, `editHostModal_unpairActionIsInertInThisSlice` | The inertness assertion this slice replaces, and the fold-applying harness the confirmation walk extends |
| `app/src/androidTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` | `Modal`, `submitAndUnpairReportSeparatelyAndNeitherCloses` | The component's own suite; its `Modal` helper is a call site of the signature this slice widens |
| `docs/knowledge/features/paired-server-store.md` | § Wiring & usage, § Failure model | "Use the shared DI store for app mutations" — a raw store instance cannot notify the observable decorator, so the connection would never close |
| `docs/knowledge/features/channel-list-screen.md` | § Host row edit control (#744) | Records that `Unpair host` is inert and that the failure string resolves at the screen, never in the view model |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The frame is the Edit host modal already built in #743: a `titleLarge` header with a circular close glyph
over an `inversePrimary @ 60%` rule, two 20dp label/value identity rows, a `Host name:` label above a
filled well, and the outlined `Unpair host` button (`primary` border, 6dp radius, `bodyLarge` medium) in
the content area above the Cancel/OK footer. Nothing in this slice changes that draw. The frame draws **no**
confirmation step, so the confirmation is a required adaptation — recorded below in the shape #638 used for
the shell's own adaptations.

**Adaptation — the confirmation replaces the modal's content in place.** `MobileModal` is itself a `Dialog`,
so a confirmation drawn as a second dialog would stack two windows over each other; the phone would then
have two back targets and two dismiss-outside behaviours for one decision. Instead `EditHostModal` branches
its own content: while confirming, the two identity rows, the name field and the `Unpair host` button are
replaced by a prompt naming the host, inside the same shell, the same window and the same scroll container.
The shell's own footer carries the decision — its `OK` confirms and its `Cancel` declines — because
`EditHostModal` passes a *different* `onSubmit` and `onDismissRequest` pair while confirming. Declining
therefore returns to the editor rather than closing it, and every dismissal route the shell already
funnels into one callback (Cancel, the close glyph, system Back) declines identically, with no new
back-handling of this slice's own.

**What that trade costs.** The footer's labels are `MobileModal`'s fixed "Cancel" and "OK", so the
destructive action is confirmed by a button reading OK. Restyling the shell's footer per caller is a change
to a component with other callers and is out of this slice's scope; the prompt copy carries the weight
instead, naming the host and stating what is removed and that re-pairing needs the QR code again. The close
glyph declining rather than closing the whole modal is the second cost — one extra tap, accepted so that no
route out of the confirmation is ambiguous about whether it removed anything.

## Context

#743 drew the modal, #744 opened it from a host row and made the name field save. `Unpair host` has been
wired to `onUnpairRequested = {}` since, and `ChannelListScreenTest.editHostModal_unpairActionIsInertInThisSlice`
asserts exactly that. This slice replaces both.

The teardown already exists: `ObservablePairedServerStore.remove` bumps its revision after the delegate's
write returns, `RelayConnectionRegistry` reconciles by closing only the removed id's bundle, and
`HostConversationSource` republishes the survivors onto the tree. `RelayConnectionFactoryTest`'s removal
cases already cover that chain, so this slice proves the seam it owns — the view model's call and its
ordering — and does not re-prove the chain below it.

No ADR is warranted: this adds no new mechanism, only a caller for two existing ones.

## Design

### The confirmation's state lives beside the editor's target

`HostEditorState` gains two booleans and no new id:

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
```

`confirmingUnpair` is a flag on the open editor rather than a separate pending-target flow because the
target is already here: `serverId` is the exact id the modal was opened for, captured at open time and
never re-resolved. A second id would be a second source of truth for which host is being removed, and
`remove` being a no-op on an unknown id only protects against taking *another* host if the id it is given
is the right one.

`unpairFailed` is separate from #744's `failed` rather than shared, so the screen resolves which string to
show from an explicit flag instead of inferring it from `confirmingUnpair`. Both stay flags, not messages,
for the reason #744 recorded: the string resolves at the screen, so the view model needs no `Context` and
no identity or relay address can reach the shell's live region.

`saving` is reused for a removal in flight — it means "a write is in flight; block the submit", and it
already gates `submitHostName` and drives the shell's `loading`. A fifth flag would say the same thing.

### Three events, three view-model methods

`ChannelListEvent` gains `HostUnpairRequested`, `HostUnpairConfirmed` and `HostUnpairDeclined`, all
`data object`s carrying no id for the same reason `HostEditNameSubmitted` carries none. `MainActivity`'s
exhaustive `when` maps each to `vm.requestHostUnpair()`, `vm.confirmHostUnpair()`, `vm.declineHostUnpair()`.

Decline is its own event rather than a reuse of `HostEditDismissed` because the two outcomes differ:
dismissal closes the modal, a decline returns to the editor with the typed name still in the field.

**All three carry `submitHostName`'s `if (target.saving) return` guard**, not only the confirm. The shell
disables OK while `loading` but leaves Cancel and the content live, so a decline or a second unpair request
can land while a write is in flight — and either would publish a new editor state, which makes the
in-flight write's `compareAndSet(pending, …)` fail and strand the modal on a step that no longer matches
what the store did. The guard is what keeps the terminal transition the property it claims to be.

### Removal order

`confirmHostUnpair` runs in `viewModelScope`, guarded by `saving`:

1. `pairedServers.remove(target.serverId)` — the shared DI store, so the revision bump that closes the
   connection follows this write. A throw lands on the failure path below and **nothing else runs**.
2. `appPreferences.removeDefaultWorkspace(target.serverId)` — only after step 1 reported success. Its
   `Result` failure is logged and not surfaced: the pairing is already gone and the connection already
   closing, so reporting a failure would claim the host is still paired when it is not.
3. `hostEditor.compareAndSet(pending, null)` — the same terminal-transition discipline `submitHostName`
   uses, so a removal completing after a dismissal cannot resurrect a closed modal or overwrite a newer one.

The order is the ticket's requirement stated as code: a failed store write leaves the pairing intact, and
a cleared cache is never evidence the host is gone.

### The screen and the component

`ChannelListScreen` passes the editor's `confirmingUnpair` straight through, routes `onUnpairRequested`,
`onUnpairConfirmed` and `onUnpairDeclined` to the three events, and resolves the error slot from two flags:
`unpairFailed` → `R.string.edit_host_unpair_failed`, else `failed` → `R.string.edit_host_save_failed`.

`EditHostModal` gains `confirmingUnpair: Boolean = false` (a caller flag, like `loading` and `error`) plus
required `onUnpairConfirmed` / `onUnpairDeclined` callbacks (the file's convention: callbacks are required,
flags default). While confirming it swaps `MobileModal`'s `title`, `onSubmit` and `onDismissRequest` and
renders a file-private `UnpairConfirmation` in place of its four content children. The name buffer's
`remember(serverIdentity)` stays above the branch, so a decline returns to the field with the draft intact.

The prompt names the host from the component's already-clamped `boundedName`, falling back to
`R.string.unnamed_host` when it is blank — the same rule the host row uses, so the confirmation names the
host the way its row does. Resolving the fallback inside the component rather than adding a parameter keeps
the clamp at the single boundary the component already owns.

**New strings** in `res/values/strings.xml`, beside the existing `edit_host_*` block:
`edit_host_unpair_confirm_title` (distinct from the button's `edit_host_unpair`, so a matcher cannot
confuse the two), `edit_host_unpair_confirm_body` (`%1$s`, the host label), `edit_host_unpair_failed`
(generic — no identity, no relay address, no name).

## State + concurrency model

- `confirmHostUnpair` launches once in `viewModelScope`; cancellation on screen exit is the scope's.
  `CancellationException` is rethrown before the failure branch, as both existing editor methods do.
- Re-entry is blocked by `if (target.saving) return`, which also blocks a rename starting mid-removal and
  a second confirm from a double tap. The shell disables OK while `loading` on top of that.
- `requestHostUnpair` / `declineHostUnpair` are synchronous main-dispatcher writes of the editor state,
  like `dismissHostEditor` — but unlike it they carry the `saving` guard, because publishing a new editor
  state mid-write would defeat the in-flight write's `compareAndSet`. `dismissHostEditor` needs no guard:
  it publishes `null`, which is the same state a completing removal would land on.
- Both terminal transitions of the removal use `compareAndSet` against the state published before the call.
- No new flow, no new scope, no dispatcher switch: the store runs its own injected IO dispatcher and
  DataStore owns its own.

## Error handling

| Failure | Result type | Surface |
| --- | --- | --- |
| `pairedServers.remove` throws `PairedServerStoreException` | exception, caught | `unpairFailed = true`, `saving = false`, still on the confirmation step; the shell's error slot shows the generic unpair string; pairing, connection and cached workspace all intact |
| `appPreferences.removeDefaultWorkspace` returns `Result.failure` | `Result<Unit>` | Logged `outcome=io_failure` by `AppPreferences`; the modal still closes — the host **is** removed, and claiming otherwise would be the worse lie |
| No open editor, or a stale target | early `return` | Nothing written |
| Unknown id (removal racing a snapshot) | store no-op | Treated as success and the modal closes, matching `submitHostName`'s reasoning for the same case |

Logging follows the existing shape exactly — event name only, no id, no name, no path:
`host_unpair_requested`, `host_unpair_declined`, `host_unpair_started`, `host_unpair_failed`,
`host_unpaired`.

## Testing strategy

Unit (`HostChannelListViewModelTest`, `runTest` + `UnconfinedTestDispatcher`), extending the existing
`Fixture`: `Store` gains a working `remove` with a `removals` list, a `failRemove` switch and a
`removeGate`; the stub `DataStore` gains a real `updateData` so `removeDefaultWorkspace` can be asserted;
`Fixture` exposes its `AppPreferences` for read-back.

- **AC-1** — request sets `confirmingUnpair` without writing anything; decline clears it, leaves the editor
  open and leaves the store, the other host and both workspace preferences untouched.
- **AC-2 / AC-3** — confirm removes the exact id, clears exactly that id's workspace key, leaves the other
  host's entry and workspace intact, and closes the editor. Asserts the order: with the store gated, the
  workspace key is still present until the removal completes.
- **AC-4** — a throwing `remove` leaves `unpairFailed` set, `saving` cleared, the confirmation still up, the
  pairing present and the workspace uncleared; the log line carries neither the id nor the name; a retry
  succeeds. A confirm landing after a dismissal does not resurrect the modal.
- **The `saving` guard** — a decline and a second unpair request arriving while the removal is gated are
  both ignored, so the removal's own close still lands and the modal does not strand on a stale step.

Compose (`ChannelListScreenTest`, replacing `editHostModal_unpairActionIsInertInThisSlice` — `setTree`
gains unpair-event handling alongside its existing fold handling so one composition can walk the steps):

- Tapping `Unpair host` emits `HostUnpairRequested`; the confirmation then names the host and the editor's
  field is gone; Cancel emits `HostUnpairDeclined`, not `HostEditDismissed`, and the field returns.
- An unnamed host's confirmation names it by the `unnamed_host` fallback.
- With `unpairFailed`, the unpair string shows and the save string does not, and OK still emits
  `HostUnpairConfirmed` — the failure is actionable.

Compose (`EditHostModalTest`): one test for the component contract — confirming replaces the content in
place (identity rows and name field gone, prompt present), Cancel / close glyph / Back each decline and
none dismisses, OK confirms exactly once, and the typed draft survives the decline round trip.

No rung-3 scenario lands here: live acceptance is **#676**, which unpairs a second host against the real
daemon and is already blocked by this ticket — the #481/#482 follow-up shape, filed before this slice.

## Documentation handoff

Pending for the documentation stage, per the ticket:

- `docs/knowledge/features/channel-list-screen.md` § *Host row edit control* — the host row's unpair path
  and what a removal clears (it already holds #744's half; `Unpair host` is no longer inert).
- `docs/knowledge/features/paired-server-store.md` § *Wiring & usage* — the store's first production
  `remove` caller.

No documentation-only acceptance criteria on this ticket.

## Open questions

- Whether the confirmation should eventually get its own destructive footer labels rather than the shell's
  Cancel/OK. Out of scope here (it changes a component with other callers); resolve if the operator reads
  "OK" as ambiguous in use.
- Whether a `viewModelScope` cancellation between the two writes leaves a stale workspace preference worth
  defending against. See the security review's Concurrency finding — accepted as inert for now.

## Security review

**Verdict:** PASS (first pass FAILed on two Concurrency findings, both fixed in the design above and
re-reviewed; the fix introduced no new finding)

**Findings:**

- **[Trust boundaries]** MUST HOLD in Phase B — the confirmation prompt must be formatted from
  `EditHostModal`'s already-clamped `boundedName`, never from the raw `initialHostName` parameter.
  `displayName` is only guaranteed bounded for names written through #744's `submitHostName`; the
  pair-with-code form writes one too, and legacy entries predate the clamp entirely. An unbounded name
  formatted into the prompt would reach both text layout and a merged semantics node — `maxLines` bounds
  what is painted, not what is measured. The component's single clamp boundary is the whole defence.
- **[Trust boundaries]** No finding — `stringResource(id, hostLabel)` passes the name as a format
  *argument*, so a `%s` or `%1$d` inside a host name is rendered literally and cannot reinterpret the
  format. The identity and the relay address never enter the prompt or the error string at all.
- **[Trust boundaries]** No finding — the removal targets `HostEditorState.serverId`, the exact id captured
  when the modal opened, never re-resolved from a snapshot at confirmation time. `remove` being id-exact
  and a no-op on an unknown id only protects other hosts if the id handed to it is the right one.
- **[File / storage]** No finding — `workspaceKey(serverId)` produces a DataStore *preference* key
  (`default_workspace_host:<id>`), not a filesystem path, so there is no traversal surface; and the
  function is injective in the id behind a fixed prefix, so clearing host A's cache cannot clear host B's.
  This slice only removes a key that the same function wrote. Storage is app-private `filesDir` by
  DataStore's construction. `remove` is one `DataStore.edit` transaction, so process death mid-write
  leaves the previous encrypted blob intact.
- **[Tokens, secrets, credentials]** No finding — the removal path reads no credential: `remove` takes only
  an id, and `HostEditorState` deliberately holds no `PairedServer`. Removing the entry deletes that host's
  pairing token and server static key from the encrypted collection, and removing the last entry persists
  an encrypted empty collection, so a removed pairing cannot be resurrected by reopening storage. The
  device static key lives under a separate Keystore alias and is untouched by a collection mutation.
- **[Tokens, secrets, credentials]** OUT OF SCOPE — **revocation is local only.** Unpairing does not
  revoke the daemon-side token, so a token already exfiltrated before the unpair stays valid on the daemon;
  the phone simply forgets it. This is the existing product contract, stated in the ticket ("nothing is
  revoked on the daemon") and in `docs/knowledge/features/paired-server-store.md` § Edge cases. No mobile
  ticket can close it — a revoke verb is a daemon-side protocol change and belongs to the `pyrycode` repo.
  Named here so the gap is not read as an oversight of this slice.
- **[Inter-process / Android attack surface]** No finding — nothing exported, no intent filter, no pending
  intent, no content provider, no WebView. Choosing an in-place content swap over a stacked `Dialog` means
  the confirmation adds **no second window**: no second back target, no second dismiss-outside surface,
  and no new `BackHandler` competing with the shell's.
- **[Inter-process / Android attack surface]** OUT OF SCOPE — tapjacking. No surface in this app sets
  `filterTouchesWhenObscured`, so a screen overlay could obscure the confirmation's OK. A destructive
  confirm raises the value of that attack slightly, but the defence belongs to `MobileModal`'s dialog view
  and would change every caller; no such attack has been observed against this app. Not taken here.
- **[Cryptographic primitives]** No finding — nothing is chosen or hand-rolled. The AES-256-GCM rewrite of
  the collection under `pyrycode.paired_server_wrap`, with its fresh per-write IV, is entirely inside
  `KeystorePairedServerStore`; this slice calls `remove` and touches no key, nonce or handshake.
- **[Network & I/O]** No finding — no socket work. The connection close is `RelayConnectionRegistry`
  reconciling the revision bump, and a hostile relay cannot prevent a local write or keep a locally-closed
  socket alive. The modal deliberately closes without awaiting reconciliation: the pairing is already gone,
  and blocking the modal on a connection teardown would hang it on the one actor that is untrusted.
- **[Error messages, logs, telemetry]** MUST HOLD in Phase B — the five new `RelayLog.d` lines carry the
  event name and nothing else: no `serverId`, no display name, no workspace path, and not the store's
  exception or its message. The user-facing failure is one static string taking no format argument, because
  the shell renders `error` verbatim into a `LiveRegionMode.Polite` node and announces it aloud. The VM
  test asserts the host name never appears in the captured log, mirroring #744's `host_name_save_failed`.
- **[Concurrency]** FIXED (was MUST FIX) — `declineHostUnpair` as first planned was an unguarded
  `hostEditor.value = …` write. A Cancel tap during an in-flight removal (the shell disables OK while
  `loading`, but not Cancel) would publish a new state, the removal's `compareAndSet(pending, null)` would
  then fail, and the modal would sit on the editor step for a host that had just been removed — a ghost
  editor over a host already leaving the tree. Now guarded by `if (target.saving) return`.
- **[Concurrency]** FIXED (was MUST FIX) — the same defect via `requestHostUnpair` tapped during an
  in-flight **rename**: `Unpair host` stays live while OK is disabled, so the confirmation step would be
  published under the rename's pending state and strand the modal on a confirmation after a successful
  save. Same guard, same reason.
- **[Concurrency]** Accepted residual — a `viewModelScope` cancellation (screen destroyed) landing between
  the pairing removal and the workspace clear leaves one stale `default_workspace_host:<id>` preference for
  a host that is gone. It is inert while unpaired and would only resurface if the same id is paired again,
  as a pre-filled cwd the operator previously chose for that same machine. Reversing the order to avoid it
  is strictly worse — it would clear the cache on a removal that then fails — and escaping `viewModelScope`
  for a preference delete is disproportionate to an unobserved, low-value staleness.
- **[Concurrency]** No finding elsewhere — one `viewModelScope.launch`, cancelled with the ViewModel;
  `CancellationException` rethrown ahead of the failure branch as both existing editor methods do; the
  read-modify-write of `hostEditor` spans no suspension point, and both terminal transitions are
  `compareAndSet` against the state published before the call.
- **[Threat model alignment]** No finding — a malicious relay can neither forge, observe nor prevent a
  local removal, and learns only that a socket closed, which is indistinguishable from backgrounding.
  Token theft from disk is strictly reduced by removal. No daemon frame enters this path: the name the
  prompt renders comes from local encrypted storage, not the wire. UI-side leakage is limited to a display
  name the operator chose themselves plus a static error string.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-21

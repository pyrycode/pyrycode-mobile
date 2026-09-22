# #790 — Drop composer drafts when their host or conversation goes away

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerDraftStore.kt` → `ComposerDraftStore`,
  `setDraft` — the store #789 landed. `setDraft`'s "only the empty string clears, and an emptied bucket is
  dropped" rule is the primitive both new evictions reuse.
- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController.confirmUnpair` — the
  single unpair seam, and the `removeDefaultWorkspace`-after-success idiom the ticket points at. Read to
  decide against it; see **Design**.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt` → `ObservablePairedServerStore`,
  its `remove` override — the chosen hook. Thirty lines, one job today: bump a revision after persistence
  succeeds.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `ObservablePairedServerStore` binding, the
  `ComposerDraftStore` single, `ThreadDestinationFactory.thread` — where the two stores meet.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onOverflowEvent`'s
  `ThreadEvent.DeleteConfirm` arm, `onDraftChange`, `sendMessage`, the `serverId` / `conversationId`
  constructor reads — the conversation-side seam, and the key both evictions must agree on.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall` —
  which throwables it swallows, hence what "a delete that fails" reaches.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` and
  `.../ui/conversations/list/ChannelListViewModel.kt` → their `HostEditorController` construction — the two
  owners a controller-side hook would have to thread a draft store through. Neither owns a composer.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/HostChannelListViewModelTest.kt` → its
  `Fixture`, `Store` fake (`failRemove`, `removeGate`, `removals`) and
  `confirmingRemovesThePairingThenItsWorkspaceAndClosesTheEditor` — the gesture-level unpair harness AC #1's
  regression joins, and the precedent for asserting a post-removal side effect.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → `makeVm`,
  `threadHandle`, `DRAFT_CONV`, the `#789: per-chat composer drafts` block — AC #2's regressions reuse all
  of it, so no new fixture is needed.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/ThrowingConversationRepository.kt` → its `delete`
  override — the failing-delete half of AC #2.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt` plus
  `ArchiveNavigationTest` / `SettingsNavigationTest` / `LiteralScreenNavigationTest` → the four existing
  `ObservablePairedServerStore(raw)` construction sites the new constructor parameter cascades to.
- `docs/knowledge/features/paired-server-store.md` § The contract, § Wiring & usage — `remove` is id-exact
  and a no-op on an unknown id; app mutations **must** go through the shared observable instance, which is
  what makes it an unbypassable seam; and the revision bump is already how "a host went away" reaches
  host-keyed state (`RelayConnectionRegistry` closes exactly that id's bundle).
- `docs/knowledge/features/thread-screen.md` § Composer draft ownership — #789's lifetime entry: the store
  is app-scoped, in-memory only, and cleared today from exactly one place (an accepted send).

## Design source

**Figma:** N/A — the ticket declares no `## Figma` section deliberately. Nothing new renders: the composer
stays as #643 left it and the Edit host modal as #745 left it. The visual-fidelity check is intentionally
skipped.

## Context

#789 gave composer drafts an app-scoped home keyed by `(serverId, conversationId)`. Nothing removes an
entry except an accepted send, so a draft outlives the thing it was written for. A `serverId` is stable
across a re-pair, so unpairing and re-pairing the same server resurfaces text typed before the unpair; a
deleted conversation's draft sits in heap for the life of the process. Unpairing is the user's revocation
gesture, and unsent text addressed to that host should not survive it.

No ADR is warranted — this adds no new mechanism, only a second consequence to an existing one.

## Design

### Where the host-side eviction hooks: `ObservablePairedServerStore.remove`

The ticket offers two hooks and asks for a reason. This takes the store, not
`HostEditorController.confirmUnpair`:

1. **Neither owner of the unpair gesture owns a composer.** A controller-side hook needs a
   `ComposerDraftStore` constructor parameter on `HostEditorController`, and therefore on both
   `ChannelListViewModel` and `SettingsViewModel` — two view models gaining a dependency neither screen
   otherwise uses, purely so a nested controller can reach it — plus their two wiring sites and a test
   fixture. The store route asks nothing of either screen.
2. **"This host's pairing no longer exists" is a store-level fact.** The decorator is already where that
   fact fans out to host-keyed app state: its revision bump is what `RelayConnectionRegistry` reconciles
   into closing exactly the removed id's connection bundle. Draft eviction is the same shape of
   consequence, and it lands beside its closest existing analogue rather than beside a preference clear
   that sits in the controller because #745 put it there.
3. **It cannot be bypassed.** The paired-server-store overview already requires app mutations to go
   through this instance, so a future removal path — a bulk "forget all hosts", a re-pair that removes
   first — inherits the eviction without having to remember it.

What this gives up is locality: `confirmUnpair` no longer reads as the complete list of what an unpair
clears. Accepted deliberately, and recorded here and in the **Documentation handoff** below rather than
paid for with a cross-file comment that would rot.

`save` and `setDisplayName` deliberately do **not** evict. Re-pairing the same id and renaming a host both
keep their drafts; the eviction happens at the unpair, so a stable `serverId` has nothing left to
resurface.

### The seam's shape

`ObservablePairedServerStore` gains one constructor parameter:

```kotlin
class ObservablePairedServerStore(
    private val delegate: PairedServerCollectionStore,
    private val onHostRemoved: (String) -> Unit,
) : PairedServerCollectionStore by delegate
```

- **A `(String) -> Unit`, not the store itself**, so a credential-custody decorator gains no
  `ui/conversations/thread` dependency and stays unit-testable without one. The policy — *an unpaired
  host's drafts go* — is expressed at the composition root, which is where a cross-cutting lifecycle rule
  belongs.
- **Required, not defaulted.** This is #789's own reasoning for `ThreadViewModel`'s `draftStore`: a
  forgotten binding is the one failure that leaves every test green while production drops nothing. A
  required parameter makes it a compile error instead. Cost: four existing construction sites (one unit,
  three `androidTest`) pass an explicit value; a navigation test passing `{}` is saying something true.
- **Called last, after the revision bump.** The bump is the custody notification the class exists to
  deliver; a hook must not be able to delay or skip it. Ordering is otherwise free — no revision observer
  reads drafts.
- **The hook must not throw and must not block**, documented on the parameter. It runs after the pairing
  is already gone, so an exception would reach `confirmUnpair`'s catch and report a failed unpair on a
  removal that in fact succeeded. Its one production binding, `ComposerDraftStore::clearHost`, is a
  non-suspending `MutableStateFlow.update`. No defensive `try`/`catch`: that failure has not been observed
  and the single binding provably cannot produce it.

### `ComposerDraftStore` — two named evictions

```kotlin
fun clearHost(serverId: String)                                  // the whole bucket, nothing else
fun clearConversation(serverId: String, conversationId: String)   // one pair
```

`clearHost` is `_drafts.update { it - serverId }` — a compare-and-set loop like `setDraft`, so a concurrent
write to another host cannot be lost. An unknown or already-empty id is a no-op, matching `remove`'s own
unknown-id contract, so the caller needs no existence check.

`clearConversation` delegates to `setDraft(serverId, conversationId, "")`. Named rather than spelled that
way at the call site: a deletion is not an edit to empty, and the delete path must not depend on
`setDraft`'s "only the empty string clears" convention to mean "this chat is gone". `setDraft` itself is
untouched.

### Where the conversation-side eviction hooks: `ThreadEvent.DeleteConfirm`

Inside the existing `launchGuardedRepoCall` block, after `repository.delete` returns and before the
`PopBack` send — the same success-only position `sendMessage`'s clear occupies. The three throwables
`launchGuardedRepoCall` swallows all skip it, so a delete that fails leaves the draft for a conversation
that still exists. Before the send so the eviction is unconditional on it.

Keyed by the constructor's own `serverId` / `conversationId` — the pair `onDraftChange` wrote under — never
re-derived from `state.value`. The two are equal today by construction, but the draft key has exactly one
correct source.

### Rejected: a reactive prune off the paired-server revision

Diffing `list()` on each revision bump to evict departed hosts looks tidier and is unsafe. The store's
read contract is *graceful*: an expected IO, Keystore or decode failure returns an **empty list** without
the blob being gone. A prune driven off that read would wipe every draft on a transient decrypt failure —
data loss from a recoverable error. The revision is also a bare counter: it names no id and bumps on
`save` and `setDisplayName` too, so a consumer could not tell a removal from a rename anyway.

## State + concurrency model

No new coroutines, jobs, scopes or flows. Both evictions are synchronous, non-suspending
`MutableStateFlow.update` calls, so both are thread-safe and neither has a cancellation path to define.

- `onHostRemoved` runs on whatever dispatcher `delegate.remove` completed on (`Dispatchers.IO` in
  production), inside `confirmUnpair`'s `viewModelScope.launch`. `update` is a CAS loop, so the dispatcher
  does not matter.
- `clearConversation` runs on `viewModelScope`'s `Dispatchers.Main.immediate`, inside the guarded block.
- `ThreadViewModel.draft` re-derives from `draftStore.drafts` as it already does; an eviction is just
  another emission. A `StateFlow` drops equal consecutive values, so evicting one host cannot recompose
  another's composer.
- A cancelled unpair (the owner cleared mid-write) cancels before `remove` returns, so no eviction runs —
  correct: the pairing may still exist.

## Error handling

- **A failed removal drops nothing.** `delegate.remove` throws `PairedServerStoreException` before the
  hook line is reached. AC #1's negative half.
- **A failed delete drops nothing.** The throw leaves the guarded block before the eviction line. AC #2's
  negative half.
- **A throwing hook** would surface as `unpairFailed` on a successful removal. Prevented by contract, not
  by a catch — see **The seam's shape**.
- No new user-facing failure surface: an eviction has no failure mode to report.
- **Nothing is logged.** A draft is private user message content; no new log line is added, and no existing
  one gains a draft-derived field. AC #1's regression asserts the draft text never appears in the captured
  log lines.

## Testing strategy

Focused JVM (`./gradlew testDebugUnitTest`), driving the production seams rather than `ComposerDraftStore`
directly — AC #3. No `androidTest` behaviour changes and no new device scenario: nothing renders differently
and this is not an operator-facing flow of its own, so no rung-3 scenario is owed.

**AC #1 — `HostChannelListViewModelTest`**, beside the existing
`confirmingRemovesThePairingThenItsWorkspaceAndClosesTheEditor`. Its `Fixture` binds
`PairedServerCollectionStore` to a bare fake today, bypassing the decorator that production always has;
the fixture wraps that fake in a real `ObservablePairedServerStore` with the new hook bound to a fixture-held
`ComposerDraftStore`. The decorator is a pass-through plus a revision nothing in that file observes, so
every other test there reads what it read before. One new test drives the full gesture —
`openHostEditor` → `requestHostUnpair` → `confirmHostUnpair`:

- a failing removal (`failRemove = true`) leaves all seeded drafts intact;
- a succeeding removal drops both of `"Host"`'s drafts and leaves `"host"`'s draft for the **same
  conversation id** intact (the cross-host half of AC #1);
- no draft text reaches the captured log lines.

**AC #2 — `ThreadViewModelTest`**, in the `#789: per-chat composer drafts` block, reusing `makeVm`,
`threadHandle` and `DRAFT_CONV`:

- `DeleteConfirm` with a `FakeConversationRepository` drops this chat's draft only — the same host's other
  conversation and another host's draft for the same id both survive;
- `DeleteConfirm` with a `ThrowingConversationRepository(IllegalStateException(...))` leaves the draft
  exactly as typed. One failure type: `launchGuardedRepoCall`'s coverage of all three is already pinned by
  the existing guard tests, and this ticket's claim is only "a delete that fails drops nothing".

**Constructor cascade.** The four existing `ObservablePairedServerStore(raw)` sites gain an explicit second
argument; the three navigation tests pass `{}` (no composer in view), and `RelayConnectionFactoryTest`'s
fixture likewise. Compilation is the check.

No `ComposerDraftStore`-level tests for the two new methods: AC #3 asks for seam-driven regressions, and
store-level assertions of the same two facts would be the same fabric twice.

## Open questions

1. Does `FakeConversationRepository.delete(DRAFT_CONV)` succeed for the seeded
   `"seed-channel-personal"`? The existing draft tests use that id with a fake for `sendMessage`, and the
   existing `DeleteConfirm` tests use `ACTIVE_CONV`. Resolve by running the test; if it rejects, AC #2's
   positive half moves to `ACTIVE_CONV` with the drafts seeded under it.
2. Does wrapping `HostChannelListViewModelTest`'s fake in the decorator disturb any other test in that
   file? Expected not — delegation is transparent and `save` still reaches the fake's `error("unused")`.
   Resolve by running the class.

## Documentation handoff

Pending for the documentation stage; not written here.

- **Required by the ticket:** fold host-removal and conversation-deletion draft eviction into
  `docs/knowledge/features/thread-screen.md` under **Wiring** (the section holding #789's
  § Composer draft ownership draft-lifetime entry).
- **Suggested, from this design:** `docs/knowledge/features/paired-server-store.md` § Wiring & usage now
  understates `ObservablePairedServerStore` — after a successful `remove` it both bumps the revision and
  runs a host-removal hook, and the hook's no-throw obligation is a caller contract worth recording beside
  the existing "use the shared DI store for app mutations" rule.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. No boundary is crossed: both `serverId` and `conversationId` stay
  opaque map keys — `clearHost` is a map minus-key and `clearConversation` delegates to `setDraft`, so
  nothing parses, splits, concatenates or resolves either, and no path, wire field, log field or cache key
  is built from them (`ComposerDraftStore`'s existing key contract, unchanged). The eviction key is not
  re-derived: `onHostRemoved` receives the exact id handed to `remove`, which `confirmUnpair` captured at
  modal-open time, so the id whose drafts go is the id whose pairing went. Matching is `String.equals` on
  both sides — exact and case-sensitive, the same equality the store's own identity rule uses — so no
  prefix or case collision can evict a neighbouring host; AC #1's regression pins that with two ids
  differing only in case (`"Host"` / `"host"`). A blank `serverId` is inherited from #789's route read and
  is unreachable in production (`HostDestination` rejects an unresolvable owner); were it reachable, a
  `""` bucket could only be evicted by removing a pairing whose id is `""`.
- **[Tokens, secrets, credentials]** No findings, by a deliberate narrowing. The hook is
  `(String) -> Unit` and receives **only** the removed `serverId` — never the `PairedServer` or
  `PairedServerEntry` that was removed. The obvious "more flexible" signature, `(PairedServerEntry) -> Unit`,
  would hand the pairing token and the server static public key to a `ui/` class held in an app-scoped
  singleton with no redacting `toString`; the narrow signature makes that impossible rather than merely
  discouraged. This ticket generates, stores, compares and rotates nothing. It strengthens revocation:
  the unpair gesture now also drops unsent text addressed to the revoked host. Removal remains local — it
  revokes no daemon token and deletes no device static key — which is the paired-server store's own
  documented limit and is out of scope here.
- **[File / storage operations]** No findings. No path is constructed, no file opened, nothing written to
  disk or to `SavedStateHandle`; no canonicalisation or TOCTOU question arises. Drafts stay heap-only per
  #789, and the ticket forbids adding persistence — so `clearHost` must **not** acquire a persisted
  counterpart, and no `dataExtractionRules` / `allowBackup` change is owed, because heap state is not
  backup-eligible. **Accepted residual:** an evicted draft's characters may survive in unreferenced heap
  until GC, and Kotlin `String` is immutable so there is no zeroing. Same residual #789 already accepted
  for every draft; the plan claims a map eviction, not an erase, and must keep claiming exactly that.
- **[Inter-process / Android attack surface]** No findings — nothing is added. No intent filter, deep link,
  `PendingIntent`, exported component, content provider, `WebView` or FCM payload handling is touched, and
  no new externally reachable trigger exists: both evictions sit behind an in-app confirmed gesture (the
  Edit host modal's unpair confirmation, the thread's delete dialog). #446's dialog-window `FLAG_SECURE` is
  unaffected.
- **[Cryptographic primitives]** No findings. No RNG, primitive, key, nonce or comparison is introduced;
  the Noise stack and the vendored `noise-java` path are untouched. The hook cannot perturb wrap-at-rest
  (ADR 0006): it runs strictly after `delegate.remove` has committed inside its own `DataStore.edit`, and
  adds no read, no decrypt and no Keystore key lookup.
- **[Network & I/O]** No findings. No frame, URL, header, timeout, TLS setting or backoff is touched. The
  one I/O-adjacent risk is a hook that blocks the dispatcher `delegate.remove` completed on and stalls the
  store; the production binding is a bounded non-blocking CAS loop, and "must not block" is documented on
  the parameter alongside "must not throw". The revision bump — which `RelayConnectionRegistry` reconciles
  into closing the removed host's connection bundle — is emitted *before* the hook, so no hook can delay or
  skip the connection teardown.
- **[Error messages, logs, telemetry]** **SHOULD FIX, carried into Phase B.** A draft is private user
  message content, and the design adds no log line, no telemetry field and no exception — but two
  obligations must hold in the implementation and the verifier should check both: (a) neither `clearHost`
  nor `clearConversation` may log, and no existing log line in either eviction path may gain a
  draft-derived field — `confirmUnpair`'s `host_unpaired` and `DeleteConfirm`'s guarded block stay
  content-free; (b) **no `data class` may take a `ComposerDraftStore` field.** The store holds draft text
  and has no redacting `toString`, so a generated `toString` would render every live draft into a crash
  trace — the same trap `HostEditorState`'s KDoc already names for record-typed fields.
  `ObservablePairedServerStore` is a plain `class`, so its default `toString` is an identity hash and the
  bound `onHostRemoved` receiver is not rendered; keep it a plain class. AC #1's regression asserts the
  seeded draft text appears in none of the captured log lines.
- **[Concurrency]** No findings. No coroutine, scope or job is added, and neither eviction suspends, so
  there is no cancellation path to define and no `NonCancellable` use. Neither is a check-then-act: each is
  a single `MutableStateFlow.update` CAS, so a concurrent write to another pair cannot be lost, and the two
  `HostEditorController` instances that #751 allows to overlap produce two hook calls that are each
  idempotent (evicting an absent bucket is a no-op). No mutex is taken, so no ordering to document. A
  cancelled unpair cancels before `remove` returns, so no eviction runs — correct, since the pairing may
  still exist. **Named residual:** text typed after the hook has run would re-create a draft keyed to an
  unpaired host. It requires an interleaving the navigation graph does not offer (the unpair gesture lives
  on the channel list or in Settings, not on that host's thread, whose connection is being torn down), and
  the result would be unreachable — no destination can resolve an unpaired host — and would die with the
  process. No disclosure; accepted rather than defended against.
- **[Threat model alignment]** No findings, and one property worth stating. A **malicious or compromised
  relay** cannot trigger either eviction: nothing inbound from the wire reaches `clearHost` or
  `clearConversation`, and in particular a daemon-pushed deletion does **not** evict, because the hook sits
  in `onOverflowEvent`'s `DeleteConfirm` arm, reachable only from the confirmed dialog. That narrowness is
  deliberate and is the safer default — an inbound-driven eviction would hand an on-path relay a way to
  destroy the user's unsent text — so it is a security property of the scope, not a gap to close later.
  **Token theft from disk** is unchanged (drafts are not on disk). **Hostile daemon frame** is not in the
  path; nothing is decoded. **UI-side leakage** (screenshot, accessibility, overlay) is reduced in the only
  direction this ticket moves it — an evicted draft stops being renderable — and is otherwise out of scope.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

# #789 — Keep a separate composer draft for each chat

Move the composer's text out of the input bar's own `rememberSaveable` into a process-scoped store
keyed by the route's `(serverId, conversationId)` pair, and clear a draft only once its send has been
accepted.

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → the self-owning
  `ThreadInputBar` overload holding `var text by rememberSaveable` — the ownership this ticket moves;
  and the stateless `ThreadInputBar(text, onTextChange, onSend, …)` overload that survives it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — the
  sole caller of the self-owning overload, mounted in the `bottomBar` composer column.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendMessage` (the
  clear has to move behind it), the `conversationId` read off `SavedStateHandle`, and `sendArchive` —
  the shipped "success-only continuation" idiom this reuses.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` → `launchGuardedRepoCall`
  — its catches sit *outside* the caller's lambda, so a continuation written inside the lambda already
  runs only on success. No change needed here.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `Routes.target` / `Routes.hostArguments` (both
  route arguments are required path segments) and the `Routes.CONVERSATION_THREAD` destination block
  that binds the ViewModel's flows to `ThreadScreen`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.thread` and the
  `viewModel { get<ThreadDestinationFactory>().thread(get(), get()) }` definition — where a
  process-scoped collaborator gets injected, and where `serverId` is already read off the same handle.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameTest.kt` → the three
  input-bar cases (`inputButton_sendsWhenTextPresent`, `inputButton_stopsWhileBusyWithEmptyField`,
  `inputButton_isDisabledSendWhenIdleAndEmpty`) — all already drive the **stateless** overload, so
  AC #4's "unchanged" holds by construction.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → the `makeVm`
  helper every case in the file routes through, and the existing `sendMessage_*` cases.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → the
  only other `ThreadViewModel(...)` construction, by named arguments.
- `docs/knowledge/features/thread-screen.md` § Wiring → the destination reads `serverId` at the factory
  and `conversationId` at the ViewModel; each back-stack entry owns its ViewModel, so equal conversation
  ids on two hosts are already distinct *destinations* — but nothing today keeps their composer text apart.
- `docs/knowledge/features/dependency-injection.md` § Host identity and snapshots → host identity lives
  at the aggregation boundary and conversation ids are host-local, which is exactly why the draft key
  must be the pair and never the conversation id alone. § Destination ownership → passing a
  process-wide singleton per `thread(...)` call is the established shape (`preferences` does it today).
- `../pyrycode-desktop/src/renderer/src/store/composerDraftStore.ts` → the behavioural reference:
  two-level host→conversation map, exact text preserved, `''` deletes the entry and an emptied host
  bucket is dropped.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=16-8

The composer sits at the foot of the thread frame as the middle band of the `Input area` column: a
6dp-cornered `surfaceContainerHigh` field 52dp tall carrying the `My message` placeholder inset from
the leading edge, with a filled circular up-arrow button overlapping its trailing edge, above the
`Actions / Auto / Opus / Max / Cxt:` footer row. **No visual code changes in this ticket** — the
geometry, tokens and both button states #643 landed are untouched; only the owner of the field's text
moves. The verifier's fidelity check should find the rendered composer byte-identical to `main`.

## Context

`ThreadInputBar`'s self-owning overload holds its text in `rememberSaveable`, so the draft's lifetime is
the thread destination's composition. Two failures follow. Navigating away and back loses the text.
And because `rememberSaveable` state is keyed by position in the composition, the next chat opened in
that slot starts from whatever that state happens to hold rather than from its own draft.

Separately, that overload clears the field synchronously on tap — `onSend(text); text = ""` — before
`ConversationRepository.sendMessage` has awaited the daemon's ack. `launchGuardedRepoCall` swallows the
three failure types a relay-backed repository throws, so a refused send silently eats the user's text
with no surface at all.

Both are the same defect: the composer owns state whose correct lifetime and clear-condition it cannot
see. Ownership moves to a process-scoped store keyed by the pair the route already carries, and the
clear moves inside the guarded call, where "the suspend call returned" *is* "the daemon accepted it".

This introduces no architectural decision worth an ADR: it is the mobile counterpart of a shipped
desktop store, and the keying follows #636's existing host-qualified destination identity.

## Design

### `ComposerDraftStore` — new, `ui/conversations/thread/ComposerDraftStore.kt`

A process-scoped (Koin `single`) holder of unsent composer text. One `MutableStateFlow` over a
two-level immutable map, mirroring `composerDraftStore.ts` field for field:

```kotlin
class ComposerDraftStore {
    val drafts: StateFlow<Map<String, Map<String, String>>>
    fun draftFor(serverId: String, conversationId: String): String
    fun setDraft(serverId: String, conversationId: String, text: String)
}
```

- Text is stored **exactly**, surrounding whitespace included. Only the empty string removes an entry;
  a whitespace-only draft is a draft (AC #1).
- When a host's last entry is removed the host bucket is dropped, so the map never accumulates empty
  branches.
- `setDraft` rebuilds through `MutableStateFlow.update {}` — a CAS loop, not read-then-assign.
- The store holds no coroutine scope, no `Context`, no disk handle, and logs nothing.

Keys are opaque strings. The store never parses, splits or concatenates them, and never builds a path
or a wire field from them.

### `ThreadViewModel`

- Reads `serverId` off the same `SavedStateHandle` the factory already reads it from, beside the
  existing `conversationId` read.
- Takes `draftStore: ComposerDraftStore` as a **required** constructor parameter, positioned after
  `appPreferences` and before the block of inert-defaulted parameters. Not defaulted: a default would
  hand each ViewModel its own store, which is precisely the bug this ticket removes, and a silent
  miswire would reproduce it invisibly.
- Exposes `val draft: StateFlow<String>` — `draftStore.drafts` mapped to this pair's entry and
  `stateIn`'d on `viewModelScope` with `SharingStarted.Eagerly`, seeded from `draftFor(...)` so the
  initial value and the first emission can never disagree. A `StateFlow` drops equal consecutive
  values, so another host's or another chat's edit cannot recompose this composer.
  It is **not** folded into `ThreadUiState`: the state `combine` is at its documented five-arity
  ceiling, and a sibling `StateFlow` matches how `connectionState`, `isBusy` and `currentModal` are
  already exposed.
- Gains `fun onDraftChange(text: String)` → `draftStore.setDraft(serverId, conversationId, text)`.
- `sendMessage(text)` keeps its blank-text early return and its existing guarded call, and gains one
  continuation **inside** the guarded lambda, after the repository call:

  > clear this pair's draft, but only if it still equals the text that was sent.

  Inside the lambda is the whole mechanism: `launchGuardedRepoCall`'s catches wrap the lambda, so a
  `RelayErrorException`, a not-connected `IllegalStateException` or an unwired
  `UnsupportedOperationException` skips the clear and leaves the text in place (AC #2). The equality
  guard keeps a send in flight from eating text typed while it was in flight.

No other ViewModel member is touched. `sendNewSession` in particular is left exactly as it is — it
never reads or writes the store, which is what makes AC #3 true; the test asserts it rather than any
new code enforcing it.

### `ThreadInputBar`

The self-owning overload is deleted. The stateless overload and every preview stay as they are.

### `ThreadScreen`

Two new parameters, both defaulted (`draft: String = ""`, `onDraftChange: (String) -> Unit = {}`),
following the file's convention for additive parameters. Defaulting is what keeps the ~30 existing
`ThreadScreen(...)` call sites across `androidTest` untouched; none of them types into the composer, so
a constant-`""` composer is what they already effectively rendered. The `bottomBar` mount becomes the
stateless overload: `text = draft`, `onTextChange = onDraftChange`, `onSend = { onSendMessage(draft) }`.

The IME `Send` action can still fire on a blank field — as it could before — and the ViewModel's
existing blank guard absorbs it, so the button's `enabled` rule and the stop-variant behaviour are
unchanged.

### `MainActivity` and `AppModule`

The thread destination collects `vm.draft` with `collectAsStateWithLifecycle()` alongside its existing
nine flows and binds `onDraftChange = vm::onDraftChange`. `AppModule` registers
`single { ComposerDraftStore() }` and the thread definition becomes `.thread(get(), get(), get())`,
with `ThreadDestinationFactory.thread` taking the store as a third parameter and passing it down both
the demo and relay construction arms — the same per-call shape `preferences` already uses.

## State + concurrency model

- **Ownership:** app process. The store is a Koin singleton, so it outlives every back-stack entry and
  survives configuration change for free. It is *not* connection-scoped: `LifecycleConnectionDriver`'s
  background close and any reconnect leave it untouched, so a draft is still there after a
  foreground/background cycle.
- **Lifetime:** process. Nothing is written to disk or to `SavedStateHandle` (ticket note); process
  death drops drafts, accepted.
- **Dispatcher:** everything runs on `viewModelScope` (`Dispatchers.Main.immediate`). `onDraftChange`,
  the `stateIn` collector and the post-send clear are all on main, so the compare-then-clear after the
  suspension point cannot interleave with a concurrent edit.
- **Atomicity:** the map rebuild uses `update {}`, so two ViewModels writing different pairs cannot
  lose each other's entry.
- **Cancellation:** the `stateIn` collector is the only coroutine introduced and dies with
  `viewModelScope` at `onCleared`. The store launches nothing.
- **Duplicate entries:** two back-stack entries on the same pair share the store, so they stay in sync;
  a double clear is idempotent.

## Error handling

No new failure mode and no new error surface. The send path's three failure types keep their existing
inert-swallow in `launchGuardedRepoCall`; the only behavioural change is that they no longer take the
user's text with them. Deliberately **no** snackbar for a refused send: the retained draft is the
feedback, and a surfacing channel would be a second deliverable this ticket does not carry.

`ComposerDraftStore` has no failure path — a missing key reads as `""`.

## Testing strategy

JVM only (`./gradlew testDebugUnitTest`); no new device test and no new e2e rung. The existing
`ThreadFrameTest` input-bar cases already drive the stateless overload and must pass byte-unchanged.
This is not an operator-facing flow in the §B1 sense — it ships no new daemon interaction, no new verb
and no new rendered surface — so no rung-3 scenario is owed; the ticket routes live two-host behaviour
to #673.

New `ComposerDraftStoreTest` (`app/src/test/.../thread/`):

- exact text round-trips, surrounding whitespace included
- the empty string removes the entry, and empties the host bucket when it was the last one
- a whitespace-only draft is retained, not treated as empty
- the same conversation id under two hosts holds two independent drafts, neither visible in the other
- an unknown pair reads as `""`

Additions to `ThreadViewModelTest`, driven through a `draftStore` parameter added to the `makeVm`
helper (defaulted to a fresh store, so every existing case is unaffected):

- `onDraftChange` writes this VM's pair and no other
- `draft` emits the store's existing text at construction (the navigate-away-and-back restore, AC #1)
- an accepted send clears this pair's draft (AC #2, positive)
- a send the repository refuses leaves the draft intact — one case per failure type the guard catches:
  `RelayErrorException`, `IllegalStateException`, `UnsupportedOperationException` (AC #2, negative)
- text typed while a send is in flight is not cleared by that send's completion
- a blank send never touches the store
- `ThreadEvent.NewSession` leaves the draft untouched (AC #3)

## Open questions

- **Does the extra store→`stateIn`→`collectAsStateWithLifecycle` hop lag the e2e suites'
  `performTextInput`?** Expected no: the whole chain is main-dispatcher and the e2e suites already
  drive every other composer-adjacent assertion through `collectAsStateWithLifecycle`. Resolve by
  running `DeterministicInteractiveStreamE2ETest`'s ping scenario, whose first act is typing into this
  field. Record the outcome under `## Revisions` if it forces a design change.

## Sizing

Six production files, one over the ≤5 boundary. The sixth is the Koin wiring the refiner's estimate
already named. The only available split — `ComposerDraftStore` plus its binding as one child, the
ownership move as the other — produces a first child consumed by exactly one sibling and provable by
nothing a user can see, which the floor rule forbids; the floor wins over the ceiling, so it ships as
one ticket with the overage stated here. Every other line of the boundary holds: one new exported type,
four acceptance criteria, no new reject branches, ~6 consumer call sites (four `ThreadViewModel`
constructions, one Koin definition, one input-bar mount — the ~30 `ThreadScreen` call sites are held at
zero by defaulting both new parameters), and total written work well inside 800 lines.

## Documentation handoff

Pending for the documentation stage: fold draft ownership and its lifetime into
`docs/knowledge/features/thread-screen.md` under `Wiring`, naming where a draft is created, restored
and cleared. Not written here — the builder does not edit `docs/knowledge/features/`.

## Security review

**Verdict:** PASS

The asset this ticket handles is unsent user message content — not a credential, but private user text
whose new lifetime is longer than the one it replaces. That is what the walk below is about.

**Findings:**

- **[Trust boundaries]** No findings. The text crossing into the store is *user-authored*, arriving from
  the IME at `ThreadInputBar`'s `onTextChange` and leaving at `ConversationRepository.sendMessage` as a
  wire field it already was. **No daemon-authored text reaches the store** — no inbound verb, mapper or
  live-event path writes it — so the "daemon text is untrusted relative to the UI" rule has no new
  surface here. The keying inputs are route arguments: `Routes.hostArguments` declares both `serverId`
  and `conversationId` as required path segments and `HostDestination` rejects an unresolvable owner
  before `ThreadViewModel` is constructed, so the `orEmpty()` fallback on the `serverId` read cannot
  collapse two real hosts into a shared `""` bucket in production (demo mode supplies
  `HostConversationSource.DEMO_SERVER_ID`, non-blank). `ComposerDraftStore` treats both keys as opaque
  map keys: it never parses, splits, concatenates or resolves them.

- **[Tokens, secrets, credentials]** No findings — and a net improvement worth naming. There is no token
  or key in scope. But `rememberSaveable` today puts the composer's text into the activity's
  saved-instance-state `Bundle`, which Android may persist as part of the task record and which is
  `allowBackup`-eligible. Deleting the self-owning overload **removes that at-rest surface**: the store
  is heap-only, and the plan forbids DataStore, `SharedPreferences` and `SavedStateHandle` persistence.
  The accepted cost is the mirror image — the draft now sits in heap for the process's life rather than
  the destination's, cleared on an accepted send or by process death. That is the feature, and it is
  bounded by the app's own lifetime with no cross-app reach.

- **[File / storage operations]** No findings — by a design decision, not by absence of thought. Nothing
  in the plan opens a file, builds a path or writes a byte to disk. No path is derived from `serverId`
  or `conversationId`, so path traversal has no candidate input. No partial-write/atomicity concern
  exists because there is no write. The Phase-B constraint that carries this finding: **do not add
  persistence to `ComposerDraftStore`**, however convenient a surviving draft looks.

- **[Inter-process / Android attack surface]** No findings. No exported component, `<intent-filter>`,
  deep link, `PendingIntent`, content provider, push-payload handling or WebView is added or touched.
  One consequence to state rather than skip: a draft is now visible in the composer for longer (across
  navigation) than before, so it can appear in the recents task snapshot on a background. The composer
  already rendered typed text, so the exposure class is unchanged — only its duration grows, as an
  intended consequence of the feature. The activity window carries no `FLAG_SECURE` (only #446's
  permission-modal dialog window does); that is pre-existing and **out of scope** for this ticket.

- **[Cryptographic primitives]** Not applicable, stated concretely: the design introduces no randomness,
  no key material, no hashing and no handshake code. The one comparison it adds —
  `draft.value == text` before clearing — compares user text against user text, not a value against a
  secret, so `MessageDigest.isEqual` would be meaningless here and `==` is correct.

- **[Network & I/O]** No findings. The only network touch is the pre-existing
  `repository.sendMessage(conversationId, text)` call; no frame, cap, timeout, TLS setting or backoff
  is altered. Considered and rejected as a finding: drafts are unbounded in length and now accumulate
  per conversation, but the only actor who can grow them is the device's own user typing into their own
  keyboard — a hostile relay cannot write a single byte into this store, and a relay that refuses every
  send can at most cause the user's own text to be *retained*, which is the defect being fixed.

- **[Error messages, logs, telemetry]** **SHOULD FIX — carried into Phase B.** Draft text is private
  unsent message content and must never be logged. `launchGuardedRepoCall` already never logs its caught
  exception, and the neighbouring `RelayLog.d { "event=thread_destination_bound" }` is content-free, so
  the existing paths are clean. The obligation is on the new code: **no `RelayLog` / `Log` call inside
  `ComposerDraftStore`, `onDraftChange` or `draft`, and no draft text in any exception message or
  `require`/`check` argument.** Content-free is the bar — if a log is ever wanted here, it logs an event
  name and at most a length, never the text and never a key. The verifier should check this landed.

- **[Concurrency]** No findings. Every write goes through `MutableStateFlow.update {}`, a CAS loop, so
  the two-level map rebuild cannot lose a concurrent write to a different pair — a plain read-then-assign
  would have been the bug here. The post-send clear *is* a check-then-act across a suspension point
  (`repository.sendMessage` suspends before it), and it is safe for a reason worth writing down rather
  than assuming: both the compare and the clear run on `viewModelScope`'s `Dispatchers.Main.immediate`,
  as does `onDraftChange`, so no edit can interleave between them. Two back-stack entries on the same
  pair share one store and stay consistent; a double clear is idempotent. The only coroutine introduced
  is the `stateIn` collector, cancelled with `viewModelScope` at `onCleared`; the store owns no scope.
  Shutdown: process death drops drafts (accepted), and `LifecycleConnectionDriver`'s background close
  cannot disturb the store because it is app-scoped, not connection-scoped.

- **[Threat model alignment]** No findings. *Malicious / compromised relay* — content-blind and on-path
  only; it has no write path into the store, and its worst move (refusing every send) now preserves user
  text instead of destroying it. *Hostile daemon frame* — no inbound decode path touches drafts.
  *Token theft from disk* — nothing is written to disk. *UI-side leakage* — recents-snapshot exposure
  discussed above, unchanged in kind; third-party keyboard logging of composer text is pre-existing and
  unaffected by where the text is subsequently held. Live two-host behaviour is routed to **#673** by the
  ticket and is not deferred security work.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

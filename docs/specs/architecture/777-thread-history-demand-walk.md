# #777 — load thread history on open and page backwards to the start

`requestHistory` has been wired end to end below the UI since #623 (the round trip) and #645 (the fold),
and has no caller. This ticket lands the demand: the ask on open, the ask when the reader reaches the
oldest loaded row, the termination rules, and the oldest-end loading affordance. Recovery — retry, the
refused-cursor restart, the reconnect restart — is #778.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `requestHistory`, `HistoryPage`, `HistoryEntry` | The contract this ticket calls: `cursor` echoed verbatim, `atStart` as the **only** termination signal, a short page meaning nothing, and the four documented throw types. The interface default is `error(...)` → `IllegalStateException`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | `requestHistory`, `mergeHistoryPage` | Confirms the page is already folded into `threadByConversation` before it returns — the VM must not fold it a second time. |
| `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` | `reduceHistoryPage`, `mergeHistoryRows` | The gap-free, duplicate-free join, keyed on the renderer's own row key. Nothing here changes. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` | `requestHistory`, `FAKE_HISTORY_PAGE_SIZE` | The demand-loop harness: a real backward walk over seeded messages, reproducing the daemon's `atStart` rule. Throws `IllegalArgumentException` for an unseeded conversation id. |
| `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` | `requestHistory` | Verbatim delegation; `IllegalStateException` with no live connection — one type either way. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | `threadContent`, `state`, `sendAnswer` | The five-arm typed `combine` the history fields must ride (`threadContent`), and the `CancellationException`-first catch ladder this ticket copies. No `init` block exists today. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | `ThreadScreen`, the `LazyColumn` `else` arm | `reverseLayout = true` over `state.items.asReversed()`, stable per-subtype keys, and the two existing `snapshotFlow` effects whose idiom the demand predicate follows. Every extra parameter is defaulted, so a new one has zero fan-out. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` | `ThinkingIndicator` | The shipped small-spinner-plus-label affordance whose tokens, sizes and `semantics` posture the oldest-end loading row mirrors. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/GuardedRepoLaunch.kt` | `launchGuardedRepoCall` | Why this path cannot reuse it (it swallows inertly; this walk must record the failure) and why the `CancellationException` arm must come first on the JVM. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` | `fixedRepo`, `makeVm`, `ACTIVE_CONV` | The trap: `fixedRepo`'s inline `object` does not override `requestHistory`, and `ACTIVE_CONV` is not in the fake's seed — an `init` ask reaches both throws across the whole file. |
| `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` | § *History pages fold into the same thread (#645)* | "A page cannot promote a `Running` tool row to `Done`/`Failed`" — the merge skips rather than updates. A walking caller must not assume otherwise. |
| `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` | § `LazyColumn(reverseLayout = true)` | Why index 0 is the newest row, why keys are computed from item fields and never position, and the existing streaming auto-scroll effects the new predicate must not disturb. |
| `../pyrycode/docs/protocol-mobile.md` | § *Conversation history (v2)* | The wire SSOT for the cursor / `atStart` pair. Cited, not restated. |
| desktop `main` `008018a` `src/renderer/src/store/historyPageBridge.ts` | `requestOlderHistory` | The counterpart: near-top predicate, in-flight guard, `atStart` termination, cursor echoed. Its `HistoryRequestFailure` / `retryable` split is what #778 adds here. |

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread Screen: a `Schemes/surface` column of a divider-footed top bar, a flexing message
area of `Schemes/on-primary-fixed` assistant and `Schemes/on-primary` user bubbles at `M3/body/medium` on
a 6dp radius, and the input area. The frame carries **no** history-loading element — it predates this
ticket — so the oldest-end affordance reuses the shipped in-app idiom instead: `ThinkingIndicator`'s 16dp
indeterminate `CircularProgressIndicator` with a `bodySmall` / `onSurfaceVariant` label, which is itself
the stand-in the design has not yet drawn. Nothing already in the frame moves.

## Context

Three sources feed one thread and are deduped by message id in the repository, never joined by cursor:
the `backfill_since` replay ring, the live stream, and the on-disk history log. Only the third can reach
back past the moment this phone connected, and nothing asks it for a page. A phone user opening an
existing channel therefore sees an empty or near-empty thread.

The demand is a walk with two termination rules and one ordering rule, and none of the three belongs in a
composable or in a ViewModel method body: `atStart` ends it, a client-side cap bounds it, and exactly one
request may be outstanding per conversation. That is a value with transitions, so it gets its own pure
type and its own test file, testable without a ViewModel and without a device.

No ADR is warranted. This is a caller for a contract two shipped tickets already designed.

## Design

### `ThreadHistoryDemand` — the walk, as a value (new, `ui/conversations/thread/ThreadHistoryDemand.kt`)

```kotlin
internal enum class HistoryWalkStop { AtStart, NotAdvancing, PageCap, Failed }

internal data class ThreadHistoryDemand(
    val cursor: String = "",          // echoed verbatim; empty = start at the newest
    val pagesLoaded: Int = 0,
    val inFlight: Boolean = false,
    val stoppedBy: HistoryWalkStop? = null,
)
```

Contract, no bodies:

- `val canAsk: Boolean` — `!inFlight && stoppedBy == null`. The one place the "one outstanding request,
  and an ask arriving during a request is dropped rather than queued" rule lives.
- `fun asking(): ThreadHistoryDemand` — marks the slot claimed; the cursor is unchanged, so the caller
  reads the returned value's `cursor` as the one to ask with.
- `fun settled(pageCursor: String, atStart: Boolean): ThreadHistoryDemand` — advances the cursor,
  increments `pagesLoaded`, clears `inFlight`, and sets `stoppedBy` from the first matching rule:
  `atStart` → `AtStart`; an empty or unchanged `pageCursor` → `NotAdvancing`; the incremented count
  reaching `MAX_HISTORY_PAGES` → `PageCap`; otherwise `null` and the walk continues. It takes the two
  scalars rather than the whole `HistoryPage` so this file never imports the page type and no
  daemon-authored `HistoryEntry` can structurally reach the walk state — see § Security review.
- `fun failed(): ThreadHistoryDemand` — clears `inFlight`, sets `Failed`, and leaves `cursor` and
  `pagesLoaded` **untouched**. That is the AC-#4 "keeps every loaded row and the walk's position".

`stoppedBy` is an enum rather than a `Boolean` precisely so #778 can reopen exactly one of its members:
`Failed` is retryable, `AtStart` / `NotAdvancing` / `PageCap` are terminal. A `stopped: Boolean` would
have designed that split shut.

`MAX_HISTORY_PAGES = 100` is the client-side bound the security review names as load-bearing. At the
fake's page size that is 2000 rows of scroll-back — past any plausible reader — while a hostile daemon
alternating two cursor values (which defeats the `NotAdvancing` guard, an honest-bug guard only) is
bounded at 100 asks rather than unbounded.

### `ThreadViewModel` — the caller

One new private `MutableStateFlow<ThreadHistoryDemand>`, one new public method, one new `init`, and two
new `ThreadUiState` fields' worth of surface (only one field, see below).

- `init { requestOlderHistory() }` — AC #1's opening ask.
- `fun onDemandOlderHistory()` — the screen's entry point; the same private body.
- The private body claims the slot with a `compareAndSet` retry loop over `canAsk` / `asking()` (not a
  read-then-assign: the settle runs in a launched coroutine, so a check-then-act would open a real
  window), returns early when the claim is refused, then launches on `viewModelScope` and calls
  `repository.requestHistory(conversationId, claimedCursor)` — `limit` left at its default, letting the
  daemon choose.
- Catch ladder, in this order and no other: `CancellationException` rethrown first (on the JVM
  `j.u.c.CancellationException` extends `IllegalStateException`), then `RelayErrorException`,
  `IllegalStateException` and `IllegalArgumentException`, each settling `failed()`. Nothing is logged on
  any arm — `RelayErrorException.message` is server-supplied. `kotlinx.serialization`'s
  `SerializationException` is an `IllegalArgumentException`, so the malformed-page decode failure is
  covered by the arm already required for the fake's unknown-conversation throw.
- The returned `HistoryPage` is read for `cursor` and `atStart` **only**. Its `entries` are never folded
  here: `RemoteConversationRepository.requestHistory` already merged them into `threadByConversation`
  before returning, and the VM reads that through `observeMessages` exactly as it does today.

`threadContent` takes `historyDemand` as a third `combine` arm, keeping the `state` combine at five arms.
`ThreadUiState` gains exactly one field, `historyLoading: Boolean = false`, sourced from `inFlight`. The
stop reason deliberately does not reach the screen: the screen asks, the ViewModel decides whether the
ask is honoured, and a second copy of that decision in Compose would be a second place to get it wrong.

### `ThreadScreen` — the predicate and the affordance

One new defaulted parameter, `onDemandOlderHistory: () -> Unit = {}`. Inside the existing `else` arm,
beside the two `snapshotFlow` effects already there:

- `rememberUpdatedState` over `state.items.size` and over the callback, then a single
  `LaunchedEffect(listState)` whose `snapshotFlow` emits `lastVisibleIndex >= rowCount - 1`,
  `distinctUntilChanged()`, and calls the callback on a `true`.
- **The predicate counts thread rows, never `layoutInfo.totalItemsCount`.** The obvious shape counts the
  loading row itself, so a page answering `atStart = false` with zero entries self-drives: ask → the
  indicator mounts → the count rises → the page settles → the indicator unmounts → the count falls →
  re-fire. Reading `rowCount` through `rememberUpdatedState` makes the indicator's presence unable to move
  the predicate: at the oldest end the last visible index is `rowCount - 1` without it and `rowCount` with
  it, and `>=` holds for both, so `distinctUntilChanged` sees no edge and issues no second demand.
- The loading row is a keyed `item` appended after `itemsIndexed`, rendered only when
  `state.historyLoading`. Under `reverseLayout = true` a later item takes a higher index and draws further
  up, which puts it at the oldest end for free.
- A private `HistoryLoadingRow` composable in the same file, mirroring `ThinkingIndicator`: a 16dp
  indeterminate spinner, a `bodySmall` / `onSurfaceVariant` label, and a merged `contentDescription`. Both
  strings are new `strings.xml` entries; neither is derived from anything the daemon sent.

**Stable reading position is geometry the screen already has, not new code.** `observeMessages` is
chronologically ascending and the list reverses it, so prepended older rows land at *higher* reversed
indices — beyond the viewport, above the reader — while the keys, computed from item fields and never
from position, keep every already-composed row identified. Nothing shifts. The above-delimiter dimming
applies to prepended rows sitting above the most recent boundary; that is the designed behaviour and is
left alone.

`MainActivity` wires `onDemandOlderHistory = vm::onDemandOlderHistory` — one line, the only consumer.

## State + concurrency model

`historyDemand` is a `MutableStateFlow` owned by the ViewModel, read in the `threadContent` combine and
written from two places: the `compareAndSet` claim loop (from the main thread, via `init` or the screen
callback) and the settle/fail in the launched coroutine. Both writes are CAS-shaped, so no read-modify-write
window exists. There is no second mutable history state anywhere.

Each ask is one `viewModelScope.launch`, cancelled with the scope on `onCleared`; nothing outlives the
ViewModel. `requestHistory` is a suspending request/reply that runs on the injected repository's own
dispatcher — this ticket adds no `withContext` and no dispatcher of its own.

The projections are connection-scoped: `threadByConversation` starts empty on each connection, so a cursor
minted on one connection is not valid on the next. This slice does **not** restart the walk on reconnect —
a stale cursor simply fails or returns a page the merge drops, and the walk stops. That restart is #778,
named here as the deliberate gap.

## Error handling

| Failure | Where it surfaces | What the walk does |
|---|---|---|
| Server `error` frame (`RelayErrorException`, incl. the retryable `history.unavailable`) | Caught in the launch | `failed()` — in-flight cleared, rows and cursor kept, no further ask |
| Not connected, or the interface-default `error(...)` (`IllegalStateException`) | Caught in the launch | Same |
| Unknown conversation id, or a malformed page (`IllegalArgumentException`, incl. `SerializationException`) | Caught in the launch | Same |
| Structured cancellation | Rethrown, first arm | Propagates; no state write |
| Daemon returns `atStart = false` with a non-advancing cursor | `settled()` | `NotAdvancing` — walk ends |
| Daemon keeps serving advancing pages forever | `settled()` | `PageCap` at 100 pages — walk ends |

No user-visible error surface ships here, by design: the loading indicator always clears, so this slice
never shows a visibly stuck state, and there is no retry affordance to be stuck *on*. A one-shot error
channel would be a retry affordance with no retry behind it.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`, `runTest`, fakes):

- **`ThreadHistoryDemandTest`** (new): `canAsk` false while in flight and after each stop reason; `asking`
  preserving the cursor; `settled` advancing the cursor and clearing in flight; `atStart` ending the walk;
  a repeated cursor and an empty cursor both ending it as `NotAdvancing`; an empty-entries page with an
  advancing cursor **not** ending it; the cap firing at exactly `MAX_HISTORY_PAGES`; `failed` keeping
  cursor and `pagesLoaded` while refusing further asks.
- **`ThreadViewModelTest`** additions: an opening ask on construction, with the asked cursor empty; a
  second ask using the cursor the first page returned; an ask during an in-flight request dropped rather
  than queued; a page reporting `atStart` ending the walk; a failing page settling `historyLoading` back
  to false and issuing no further ask; and the AC-#4 regression — a double serving `atStart = false`
  with no entries and a non-advancing cursor drives a *settling*, bounded ask count.
- **AC #2** is asserted at this layer as the "nothing needs a second fold" property: with a page in
  flight, a message arriving on `observeMessages` renders exactly one row, and completing that page with
  entries adds no further rows. The join itself is already proven in `RemoteConversationRepositoryTest`
  (#645) and is not re-proven here.

Compose UI (`app/src/androidTest/`, run focused per § B2 on the managed API 33 device):

- **`ThreadScreenHistoryTest`** (new): the loading row visible when `historyLoading` and absent when not;
  **the demand regression** — toggling `historyLoading` with the row set unchanged issues no further
  demand; and AC #3 — a row the reader is looking at stays displayed across a prepend of older rows.

Fakes throughout; no MockK. Per AC #5 **no rung-3 scenario ships here** — the live proof stays with #673,
which waits on this work through #624 → #647 — so this ticket carries no `needs-real-claude`.

## Open questions

1. `MAX_HISTORY_PAGES = 100` is chosen, not measured; no live daemon page size is known yet. Resolve by
   confirming nothing in the wire contract implies a smaller natural bound.
2. Whether the opening ask should be suppressed when the thread already holds rows from the replay ring.
   Resolve by checking whether a redundant first page is fully absorbed by `mergeHistoryRows` — if it is,
   no suppression is warranted and the simpler unconditional ask stands.

## Revisions

### 2026-09-22 — Open questions resolved during implementation

Both resolved without changing the committed design; recorded here so the resolution is auditable rather
than merely absent.

1. **`MAX_HISTORY_PAGES = 100` stands.** The wire contract bounds a single page (the daemon chooses the
   size, clamps a large ask, and narrows a page to fit its frame cap) but names **no** bound on the
   number of pages in a log, so there is no smaller natural ceiling to adopt. 100 stays, documented as a
   chosen client-side bound rather than a derived one.
2. **The opening ask stays unconditional.** `mergeHistoryRows` skips any row the thread already holds,
   keyed on the renderer's own row key, so a first page overlapping the replay ring is fully absorbed —
   suppressing the ask would add a condition that buys nothing and could skip a genuinely needed page
   after a daemon restart empties the ring. The other half of the property, that this ViewModel adds no
   second fold of its own, is pinned by `history_aMessageLandingWhileTheFirstPageIsInFlight_rendersExactlyOnce`.

### 2026-09-22 — The oldest-end regression test was rewritten after a mutation check

The first version of `toggling_the_loading_flag_with_the_rows_unchanged_issues_no_further_demand` used
three short rows and **passed against the buggy `layoutInfo.totalItemsCount` predicate** — when every row
fits the viewport, the mounted indicator is visible too, so the last visible index tracks the total either
way. The test now seeds 30 rows and scrolls to the oldest end first, which is the only configuration where
the indicator mounts *above* the viewport and the buggy predicate flips. Verified by mutation: the
`totalItemsCount` variant fails it (2 demands, expected 1) and the shipped variant passes.

## Documentation handoff

Pending for the documentation stage; not written by this ticket.

- Fold the demand model into
  `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, where #645 recorded
  the page fold.
- Fold the list-side behaviour — the demand predicate, the oldest-end loading affordance, and why
  `reverseLayout` makes the trigger the **last** visible index — into
  `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] The genuinely new exposure is control flow, not content.** The decode boundary is
  `toHistoryPage` and already shipped (#623); what this ticket adds is the first client code whose
  *outbound request rate* is a function of daemon-supplied data. The two values that drive it, `cursor`
  and `atStart`, are held in `ThreadHistoryDemand` and read nowhere else. The `cursor` is echoed verbatim
  into the next request and is never parsed, concatenated into a path, URL, filename, cache key or log
  line, and never reaches Compose. Its length is bounded by the inbound WebSocket frame cap, an existing
  control, so a hostile daemon cannot grow VM state without bound through it.
- **[Trust boundaries] FIXED IN PLAN — `settled` now takes two scalars, not the `HistoryPage`.** As first
  drafted the helper took the whole page, which left `entries` — the most untrusted strings the thread
  holds — reachable from the walk's own code by a one-line future edit. Narrowing the signature makes
  "the walk cannot read daemon-authored text" structural rather than conventional: `ThreadHistoryDemand.kt`
  does not import `HistoryPage` at all. The ViewModel likewise reads the returned page for `cursor` and
  `atStart` only, and never folds its `entries` — `RemoteConversationRepository.requestHistory` merged
  them before returning.
- **[Network & I/O] Request amplification is the worst case, and the client-side page cap is what bounds
  it.** A hostile daemon answering every page with `atStart = false` and a fresh advancing cursor defeats
  the `NotAdvancing` guard — that guard is an honest-bug guard only, and an alternating pair of cursors
  beats it. Three controls bound the real case: `MAX_HISTORY_PAGES = 100` (hard, deterministic,
  client-side), the one-outstanding-request rule (so the rate is bounded by round-trip latency, never by
  a loop), and the screen-side predicate's `distinctUntilChanged`. Worst case is 100 sequential round
  trips, then a terminal stop.
- **[Network & I/O] The cap is per ViewModel instance, so leaving and re-entering the thread resets it.**
  Named rather than left for the verifier to find. Each reset costs a deliberate human gesture, and a
  per-screen walk is the correct scope for a per-screen cap; a process-scoped counter would leak walk
  state across conversations for no attacker-relevant gain.
- **[Network & I/O] No new timeout, and none is needed.** A daemon that accepts the request and never
  replies would suspend the `await` — but #488's `failAllPending` teardown sweep completes every pending
  request with `IllegalStateException` when the connection tears down, which this ticket's catch ladder
  handles into `failed()`, clearing the indicator. The residual case is a *connected* daemon that
  silently never answers: the indicator stays visible, the walk issues no further ask, and nothing grows.
  That is a stuck affordance, not a loop or a leak, and the restart that clears it is #778.
- **[Errors, logs, telemetry] Nothing on any branch is logged.** The catch ladder has four arms and none
  logs; `RelayErrorException.message` is server-supplied and is never read, formatted or surfaced. The
  loading row's two strings are local `strings.xml` resources with no interpolation. Phase B check: no
  `Log.` / `Timber.` call and no string template containing `cursor` anywhere in the new code.
- **[Concurrency] The claim is CAS-shaped precisely because the settle is asynchronous.** A read-then-assign
  `canAsk` check would open a real window between the check and the `inFlight` write, admitting two
  concurrent asks. The `compareAndSet` retry loop closes it, and `MutableStateFlow.update` is used for
  the settle and fail. Every job is `viewModelScope`-bound and dies with the screen; the
  `CancellationException` arm is first because `j.u.c.CancellationException` extends
  `IllegalStateException` on the JVM.
- **[Cryptographic primitives] The one comparison of an attacker-controlled value is not a secret
  compare.** `pageCursor == cursor` compares two daemon-supplied strings to each other, so its timing
  discloses nothing; `MessageDigest.isEqual` would be cargo-culted here, not a hardening. No key, nonce,
  RNG or handshake material is touched — the walk rides the existing `Noise_IK` session unchanged.
- **[Tokens, secrets, credentials] Not applicable, by the cursor's own documented design.** It is
  "unsigned by design and carries only the conversation id the caller already knows"; authorization is
  pairing, enforced at the Noise handshake. It is also deliberately **not persisted** — VM state only, no
  `SavedStateHandle`, no DataStore — because the repository's projections are connection-scoped and a
  surviving cursor would become a stale-cursor bug on the next connection.
- **[File / storage] OUT OF SCOPE — nothing here touches the filesystem.** The cursor never becomes a
  filename or a cache key. The category bites when history is cached on disk, which is #647.
- **[Inter-process / Android attack surface] Not applicable.** No `Activity`, `Service`,
  `BroadcastReceiver`, intent filter, deep link, `PendingIntent`, content provider or WebView is added or
  touched. The one new composable renders a `CircularProgressIndicator` and a `stringResource`; no
  daemon-authored text reaches the screen through this ticket.
- **[Threat model alignment] A malicious relay is on-path but content-blind**, so against this walk it can
  drop, delay or reorder a `history_page` reply. Each of those lands on a path already covered: a dropped
  connection sweeps to `IllegalStateException` → `failed()`; a delay leaves one in-flight ask and no loop;
  it cannot forge a page from outside the Noise session. Reconnect restart is the named #778 gap.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
</content>
</invoke>

# #778 — retry a failed history page and restart the walk on reconnect

#777 shipped the backward walk with a deliberate dead end: any failure clears the in-flight state, keeps
every loaded row and the cursor, and then stops asking forever. This ticket reopens exactly one of its
stop reasons and adds two restarts — a refused cursor and a new connection — both on the **same** page
budget, because a restart that resets the bound is a bound with an off switch.

## Files read

| Path | Symbol | Why it matters |
|---|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryDemand.kt` | `ThreadHistoryDemand`, `HistoryWalkStop`, `MAX_HISTORY_PAGES` | The value this ticket extends. Its KDoc names `Failed` as "the recoverable one" and the enum as the shape the retryable split reopens — the split is designed for, not bolted on. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | `requestOlderHistory`, `claimHistoryAsk`, `threadContent`, `connectionState`, `ThreadUiState` | The CAS claim idiom the new claims copy, the `CancellationException`-first catch ladder the new arms extend, the five-arm `combine` the tail field must ride, and the already-injected `connectionStateSource`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | `ThreadScreen`, `HistoryLoadingRow`, the demand `snapshotFlow` | The oldest-end slot this ticket widens from one row to three mutually exclusive ones, and the predicate that must keep counting **thread rows** rather than `layoutInfo.totalItemsCount`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt` | `ConnectionBanner` | The shipped error-action idiom: `errorContainer` / `onErrorContainer` on a clickable surface. The retry row mirrors it rather than inventing an error style. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` | `requestHistory` KDoc | The contract that decides the split: `history.unavailable` is "the only **retryable** member", an unknown id is `IllegalArgumentException`, a closed session is `IllegalStateException`, and every failure is "scoped to **this ask alone**". |
| `app/src/main/java/de/pyryco/mobile/data/network/MobileWireModels.kt` | `RelayErrorException` | Carries `code` and `retryable` alongside a **server-authored** `message`. The `code` drives control flow; the `message` is untrusted display text this ticket never reads. |
| `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt` | `observe` | "Collectors receive the current value on subscription" — the fact that makes the first emission the connection the thread opened on, not a new one. |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt` | `emit` | The existing test seam for driving a reconnect. No new double is needed. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | `ThreadDestinationFactory.thread` | Confirms the real `ConnectionStateSource` is the supervisor's own flow (and `flowOf(Offline)` when unbound), so the reconnect collector sees true transitions. |
| `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` | § *History pages fold into the same thread (#645)* | `mergeHistoryRows` skips a row the thread already holds, keyed on the renderer's row key — which is why a restart's re-fetched newest page cannot duplicate what is on screen. |
| `docs/specs/architecture/777-thread-history-demand-walk.md` | § *Security review* | Names the reconnect restart as this ticket's gap and the page cap as the load-bearing bound — the premise the restart must not break. |
| desktop `main` `008018a` `src/renderer/src/screens/conversation/historyRetry.ts` | `retryHistoryPage` | Retry gated on `retryable`, and an action invalidated by a newer settlement — the same problem the walk generation solves here. |
| `../pyrycode/docs/protocol-mobile.md` | § *Conversation history (v2)* | The wire SSOT for the `history.*` codes. Cited, not restated. |

**Size.** ~570 lines of code and tests across 4 production `.kt` files, 1 new exported type
(`ThreadHistoryTail`), ~2 consumer call sites, 5 criteria, 8 reject branches — every line of the
one-ticket table holds. Counting this plan the total lands near the 800-line ceiling; the code itself is
comfortably inside it, and the nearest analogue #777 measured ~780 for code and tests alone.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread Screen: a `Schemes/surface` column of a divider-footed top bar, a flexing message
area of `Schemes/on-primary-fixed` assistant and `Schemes/on-primary` user bubbles at `M3/body/medium` on
a 6dp radius, and the input area. The frame carries **no** history element of any kind — it predates
#777 — but it does carry one error-plus-action affordance, the status-area "Pairing error - Re-pair"
chip: an error-toned container, a `M3/body/small-emphasized` label, 6dp radius. The oldest-end retry row
adopts that shape through its shipped Compose equivalent, `ConnectionBanner`'s `errorContainer` /
`onErrorContainer` clickable surface, so the new state reads as the same family as the error affordance
the design already drew. Nothing already in the frame moves.

## Context

`ThreadHistoryDemand.failed()` is a one-way door. The reader who hits one bad page loses the rest of the
log until they leave the screen and come back, and a reconnect leaves the walk holding a cursor the new
connection will refuse — the repository's projections are connection-scoped, so a cursor minted on one
connection is not valid on the next.

Three recoveries, one value. The retryable/permanent split is a two-member widening of an enum #777 wrote
for exactly this. The two restarts are the same transition with different triggers. What is genuinely new
is that a restart runs *concurrently with an ask that is already in flight* — the reconnect case, where
the in-flight ask belongs to the connection that just died. Without a guard its late settle writes a dead
connection's cursor into the live walk, which is precisely what AC #4 forbids. That is what the walk
generation below exists for, and it is the only new field.

No ADR is warranted: this completes a caller for a contract two shipped tickets designed.

## Design

### `ThreadHistoryDemand` — the split, the restart, and one new field

`HistoryWalkStop.Failed` becomes two members; `AtStart` / `NotAdvancing` / `PageCap` are unchanged:

```kotlin
internal enum class HistoryWalkStop {
    AtStart, NotAdvancing, PageCap,
    RetryableFailure,   // history.unavailable — a retry affordance resumes from the same cursor
    PermanentFailure,   // every other failure — visible, and the walk never resumes from it
}
```

One new field, `walk: Int = 0` — which walk the state belongs to, bumped by every restart. A settle or
fail carrying a superseded generation is **dropped** rather than applied. Contract, no bodies:

- `val canAsk: Boolean` — unchanged (`!inFlight && stoppedBy == null`). A stopped walk still refuses the
  scroll-driven ask; the retry is a deliberate gesture, not a scroll.
- `val canRetry: Boolean` — `!inFlight && stoppedBy == RetryableFailure`. The whole of AC #1's gate.
- `fun failed(retryable: Boolean)` — clears `inFlight`, sets `RetryableFailure` or `PermanentFailure`,
  leaves `cursor`, `pagesLoaded` and `walk` untouched. AC #1's "every loaded row and the walk's position
  survive **both** the failure and the retry" is this plus the next one.
- `fun retrying()` — claims the slot and clears `stoppedBy`, with `cursor` and `pagesLoaded` unchanged.
  The retry therefore asks with exactly the cursor the failed ask used, and costs one page of budget when
  it settles, same as any other page.
- `fun restarted()` — the one transition both AC #3 and AC #4 use: `cursor` back to empty (the newest
  page), `pagesLoaded` **carried**, `walk` incremented. When the budget is already spent
  (`pagesLoaded >= MAX_HISTORY_PAGES`) it returns a **not**-in-flight value stopped at `PageCap` instead
  of claiming the slot, so a restart can never buy an ask the cap already refused. The generation bumps
  on both arms, so a restart invalidates an in-flight ask even when it issues none itself.
- `fun tail(): ThreadHistoryTail` — the walk's one projection onto the UI, mapping `inFlight` → `Loading`,
  `RetryableFailure` → `Retry`, `PermanentFailure` → `DeadEnd`, everything else → `None`. Pure, and
  tested without a ViewModel per the ticket's Technical Notes.
- `settled(...)` keeps its signature and its two-scalar narrowing (#777's security finding), and becomes
  `copy`-shaped so `walk` survives it.

`ThreadHistoryTail` (new, public, declared in the same file) is the enum the screen switches on:
`None`, `Loading`, `Retry`, `DeadEnd`. One slot, four states — the ticket's "not three".

### `ThreadViewModel` — three claims, one guard, one collector

`ThreadUiState.historyLoading: Boolean` is **replaced** by `historyTail: ThreadHistoryTail = None`; the
`ThreadContent` arm carries the tail rather than the flag. No sixth `combine` arm.

- `private fun claimHistorySlot(claim: (ThreadHistoryDemand) -> ThreadHistoryDemand?): ThreadHistoryDemand?`
  — #777's `compareAndSet` retry loop, generalised over the claim rule. `requestOlderHistory` passes
  `{ if (it.canAsk) it.asking() else null }`; the new `onRetryOlderHistory` passes
  `{ if (it.canRetry) it.retrying() else null }`. One loop, two rules; still no read-then-assign.
- `private fun restartHistoryWalk(fromWalk: Int)` — the same CAS loop shape, returning without a write
  when the generation has already moved. Launches an ask only when the claimed value came back in flight.
- `private fun launchHistoryAsk(claimed: ThreadHistoryDemand)` — the single launch site, capturing
  `claimed.walk`. Every settle and fail goes through
  `historyDemand.update { if (it.walk == walk) transform(it) else it }`, so a superseded ask writes
  nothing.
- The catch ladder keeps `CancellationException` first, then gains one branch inside the
  `RelayErrorException` arm: `code == "history.invalid_cursor" && claimed.cursor.isNotEmpty()` →
  `restartHistoryWalk`, everything else → `failed(retryable = e.retryable)`. `IllegalStateException` and
  `IllegalArgumentException` settle `failed(retryable = false)`: the contract marks neither retryable, and
  a closed session recovers through the reconnect trigger rather than a button. No arm logs, and
  `e.message` is never read.
- **The non-empty-cursor condition is load-bearing, not defensive tidying.** A daemon refusing the
  *newest-page* ask with `invalid_cursor` would otherwise drive restart → ask with `""` → identical
  refusal → restart, with no user input; the cap bounds it at 100 round trips, but there is nothing to
  restart *to* when the walk is already at the newest page, so that case is a permanent failure.
- `init` gains one collector beside the existing opening ask:
  `connectionStateSource.observe().map { it == Connected }.distinctUntilChanged().drop(1)`, restarting on
  a `true`. It collects the **source**, not the `connectionState` `StateFlow`: that flow is
  `WhileSubscribed` and seeds `Connected`, so its first value is synthetic and its upstream depends on the
  screen being subscribed. `drop(1)` after `distinctUntilChanged` drops exactly the connection the thread
  opened on — the ticket's named trap — while a first value of `Offline` correctly makes the following
  connect a restart, because the opening ask on that connection already failed.

### `ThreadScreen` — one slot, three rows

One new defaulted parameter, `onRetryOlderHistory: () -> Unit = {}`, wired by `MainActivity` to
`vm::onRetryOlderHistory` (one line; the only consumer). The `state.historyLoading` conditional becomes a
`when (state.historyTail)` emitting **at most one** `item(key = "history-tail")` — literally one slot,
never three.

- `HistoryLoadingRow` is unchanged.
- `HistoryRetryRow(onRetry)` — an `errorContainer` / `onErrorContainer` `Surface` on
  `MaterialTheme.shapes.small`, its content `Row` carrying `Modifier.clickable(role = Role.Button)`, a
  `bodySmall` failure label and a `labelLarge` action label, with a merged `contentDescription`.
- `HistoryDeadEndRow()` — the same surface and label with no `clickable` and no action label. AC #2's
  "a state the reader can see" with nothing to press.
- Both strings are local `strings.xml` resources with no interpolation; nothing daemon-authored reaches
  either row.
- **The demand predicate does not change and must not.** It already counts `state.items.size` rather than
  `layoutInfo.totalItemsCount`, so widening the slot from one row to three cannot move it — the same
  reason #777 gave, now covering three mounts instead of one.

## State + concurrency model

`historyDemand` stays the single `MutableStateFlow`, now written from four places, all CAS-shaped: the ask
claim, the retry claim, the restart, and the generation-guarded settle/fail. Every ask is one
`viewModelScope.launch`; the reconnect collector is one more, cancelled with the scope in `onCleared`.
Nothing outlives the ViewModel and no dispatcher is introduced.

The generation is what makes the concurrent case safe rather than merely unlikely: a restart and an
in-flight ask genuinely overlap on reconnect, and the late settle is dropped by value comparison, not by
timing. Two restarts racing each other (an `invalid_cursor` restart and a reconnect restart) each take a
distinct generation, so at most one settle applies — two round trips for one page of budget, which spends
the bound faster rather than laundering it. Both triggers are serial in themselves (one collector; one
settle per walk), so the race is bounded at two.

Restart state is not persisted, for #777's reason: the projections are connection-scoped, so a surviving
cursor is a stale-cursor bug.

## Error handling

| Failure | Walk transition | What the reader sees |
|---|---|---|
| `RelayErrorException("history.unavailable", retryable = true)` | `failed(true)` → `RetryableFailure` | `Retry` row; pressing it resumes from the same cursor |
| `RelayErrorException("history.invalid_cursor")`, non-empty cursor | `restarted()` | `Loading`, then the newest page; rows kept, budget carried |
| `RelayErrorException("history.invalid_cursor")`, empty cursor | `failed(false)` → `PermanentFailure` | `DeadEnd` row; no self-driving restart loop |
| `history.invalid_page_size` / `history.invalid_request` / any other code | `failed(e.retryable)` — all non-retryable per the contract | `DeadEnd` row |
| `IllegalStateException` (closed session, #488's teardown sweep, interface default) | `failed(false)` | `DeadEnd`, cleared by the reconnect restart |
| `IllegalArgumentException` (unknown conversation id, malformed page) | `failed(false)` | `DeadEnd` row |
| `CancellationException` | rethrown, first arm | nothing; no state write |
| A settle or fail from a superseded walk | dropped by the generation guard | nothing |
| Restart with `pagesLoaded >= MAX_HISTORY_PAGES` | `restarted()` → `PageCap`, not in flight | `None`; the walk stays stopped |

**Content-free breadcrumbs, added by the security pass below.** #777 logged nothing, which was right for a
walk that could only stop; a walk that restarts itself needs a field breadcrumb, because a restart cycle
is invisible in a screenshot. Three `RelayLog.d` calls (debug-only by `BuildConfig.DEBUG`, message built
lazily): `event=history_ask_failed retryable=<Boolean>`,
`event=history_walk_restart reason=invalid_cursor` and `event=history_walk_restart reason=reconnect`. The
`reason` values are **local literals chosen by which branch fired**, never `e.code`; `retryable` is a
`Boolean`, not text. `e.message` is never read on any arm, and no cursor value is ever formatted.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`, `runTest`, fakes; no MockK):

- **`ThreadHistoryDemandTest`** additions: `failed(true)`/`failed(false)` reaching the two members with
  `cursor`, `pagesLoaded` and `walk` intact; `canRetry` true only for `RetryableFailure` and false while
  in flight; `retrying()` clearing the stop and keeping the cursor; `restarted()` emptying the cursor,
  carrying `pagesLoaded`, bumping `walk` and claiming the slot; `restarted()` at the cap returning a
  stopped, not-in-flight value; `tail()` over every stop reason. Existing `Failed` assertions updated.
- **`ThreadViewModelTest`** additions, over the existing `HistoryRepo` / `FakeConnectionStateSource`
  seams: a retryable failure exposing `Retry` and `onRetryOlderHistory()` re-asking with the **same**
  cursor; a permanent failure exposing `DeadEnd` and `onRetryOlderHistory()` asking nothing;
  `invalid_cursor` on a non-empty cursor re-asking with `""` while the loaded rows stay on screen;
  `invalid_cursor` on the empty cursor settling permanently rather than looping; a reconnect
  (`Connected → Offline → Connected`) restarting from `""`; the opening `Connected` restarting nothing
  (the ticket's named trap — asserted as "exactly one ask"); and the AC-#4 budget regression — a daemon
  that refuses every cursor while the connection flaps is still bounded by `MAX_HISTORY_PAGES` in total.
- The stale-settle guard is asserted through the reconnect test: with a page gated in flight, a reconnect
  restart followed by releasing the old page must leave the walk on the restarted cursor.

Compose UI (`app/src/androidTest/`, run focused per § B2 on the managed API 33 device):

- **`ThreadScreenHistoryTest`** additions: the `Retry` row displayed and its press reaching the callback;
  the `DeadEnd` row displayed with no retry callback reachable; and the predicate regression re-run across
  the widened slot — cycling `Loading → Retry → DeadEnd → None` with the row set unchanged issues no
  further demand. Existing `historyLoading` call sites migrate to `historyTail`.

Per AC #5 **no rung-3 scenario ships here** — the live proof stays with #673 — so this ticket carries no
`needs-real-claude`.

## Open questions

1. Whether `IllegalStateException` should map to `RetryableFailure` rather than `PermanentFailure`, given
   that a closed session genuinely recovers. Resolve by confirming the reconnect restart already covers
   it; if it does, a retry button for a state with no connection behind it is an affordance that cannot
   work, and permanent is correct.
2. Whether the retry claim needs its own guard against a stale in-flight ask. Resolve by checking whether
   `canRetry`'s `!inFlight` term already makes the case unreachable.

## Revisions

### 2026-09-22 — Open questions resolved during implementation

Both resolved without changing the committed design.

1. **`IllegalStateException` stays `PermanentFailure`.** A closed session does recover, but not through a
   button: the thing that recovers it is the reconnect restart, which fires on its own. Offering a retry
   there would be an affordance with no connection behind it, and pressing it would fail identically.
   `history_aPermanentFailure_isVisibleAndOffersNoRetry` covers it alongside the other two shapes.
2. **The retry claim needs no extra guard.** `canRetry`'s `!inFlight` term already makes a stale
   in-flight retry unreachable, and the claim is CAS-shaped besides.
   `canRetry_isFalseForEveryTerminalStopAndWhileInFlight` pins it.

### 2026-09-22 — The breadcrumbs forced a test-fixture change #777 had silently avoided

`RelayLog`'s default sink is `android.util.Log.println`, which throws on plain JVM, and its gate defaults
to `BuildConfig.DEBUG` (true under unit test). Adding the three log calls therefore broke **every** test
in `ThreadViewModelTest`, not just the history ones: most of the file's inline doubles inherit
`requestHistory`'s throwing interface default, so the opening ask reaches a classified failure — and now a
log call — during construction. Fixed with the seam `RelayLog` documents for exactly this and that
`SettingsViewModelTest` already uses: swap `sink` and `enabled` in `@Before`, restore in `@After`. The
captured log is then asserted on, so `history_breadcrumbsNameTheBranchAndCarryNothingTheDaemonWrote`
proves the security review's "no cursor, no code, no server prose in a log line" claim as a test rather
than as a Phase B grep. #777's total silence was load-bearing for this file in a way its plan never said.

### 2026-09-22 — The two guards the security review called load-bearing were mutation-checked

Not assumed — removed, one at a time, to confirm a test reddens:

- Dropping the walk-generation check in `applyToWalk` fails
  `history_aReconnectMidFlight_dropsThePreviousConnectionsPageAndKeepsTheRestartedCursor`, and nothing
  else — so that test is the guard's sole proof and must not be weakened.
- Dropping `claimed.cursor.isNotEmpty()` from the `invalid_cursor` branch fails
  `history_aRefusedNewestPage_settlesPermanentlyRatherThanRestartingForever`. The mutated run took 4m05s
  against a 9s baseline: the unguarded walk really does spin restart → ask → refusal → restart until
  `runTest`'s own timeout, because a failure never spends page budget. That is the security note's
  "no user input at all" cycle, reproduced.

The third property, the carried page budget, is asserted directly by exact page counts in
`restarted_cannotBuyAnAskTheCapAlreadyRefused`, `repeatedRestarts_spendTheBudgetRatherThanResettingIt`
and `history_aFlappingConnection_cannotLaunderAFreshPageBudget`; it was **not** mutation-checked, since an
assertion on an exact count cannot pass against a reset counter.

## Documentation handoff

Pending for the documentation stage; not written by this ticket.

- Fold the failure and restart model into
  `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, beside the demand
  model #777 recorded there.
- Fold the retry affordance into
  `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`.
- In `docs/e2e-interactive-stream.md`, under "Follow-ups to ticket", record that #673 owns the rung-3
  scroll-back and reconnect-continuity coverage for history paging — its current entry names two-host
  navigation, reconnect and phone-reply continuity, but not history paging.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] The automatic restart loop the ticket names is structurally impossible, not merely
  bounded — and one line is what makes it so.** `restarted()` always sets `cursor = ""`, so the ask
  immediately following *any* restart carries the empty cursor; and `invalid_cursor` on an empty cursor
  takes the `failed(false)` branch, never the restart branch. Every non-empty-cursor ask in the walk
  originates from a reader scroll (`canAsk`) or a reader press (`retrying()`). There is therefore no edge
  from "restart" back to "restart" that does not pass through a human gesture. Had the guard been written
  as the obvious `code == "history.invalid_cursor" → restart`, the daemon in the ticket's security note
  would have driven restart → ask `""` → refusal → restart at round-trip speed, bounded only by the page
  cap at 100. The condition is load-bearing and is named in § Design as such.
- **[Trust boundaries] `RelayErrorException.code` now selects control flow, and its fallback is the safe
  branch.** An exact `==` against the compile-time literal `"history.invalid_cursor"`; anything else —
  an unknown code, a differently-cased one, a code invented by a hostile daemon — falls through to
  `failed(...)`, which stops the walk. A daemon cannot reach the restart branch by *guessing*, only by
  sending the one documented code, and reaching it costs it the analysis above.
- **[Trust boundaries] `RelayErrorException.message` is never read, so the ticket's "rendered if
  surfaced" case does not arise.** Both new rows render local `strings.xml` resources with no
  interpolation, as `HistoryLoadingRow` already does. `ThreadHistoryDemand.kt` still does not import
  `HistoryPage`, so #777's structural narrowing survives this ticket's edits to the same file. Phase B
  check: no `e.message` read, and no string template containing `cursor`, `code` or `message` in the new
  code.
- **[Trust boundaries] `retryable` is a daemon-controlled boolean that decides whether a button appears,
  and that is acceptable.** A hostile daemon setting it `true` on every failure gets a retry row that
  never succeeds; each press is one round trip and burns no budget (`failed()` does not increment
  `pagesLoaded`). The rate is bounded by a human finger, which is the bound every retry button in the app
  has. The inverse — `false` on a genuinely retryable failure — costs the reader a screen reopen, which
  is the pre-#778 behaviour.
- **[Network & I/O] The budget is carried across both restarts, which is what keeps the `invalid_cursor`
  cycle bounded.** The reader-driven cycle (restart → newest page → scroll → refusal → restart) spends
  one page of budget per turn on the successful newest page, so it terminates at `MAX_HISTORY_PAGES`
  turns. `restarted()` additionally refuses to claim the slot once the budget is spent, returning a
  `PageCap`-stopped value — so a restart can never buy an ask the cap already refused, which is the
  precise shape of "a restart that defeats a bound by resetting it".
- **[Network & I/O] A flapping connection drives one ask per reconnect and cannot launder a fresh
  budget.** `pagesLoaded` survives `restarted()` by construction. The residual case a carried budget does
  *not* bound is a flap where every ask **fails** (failures do not increment the counter): that is one
  request per successful reconnect, proportional to reconnects rather than a loop, and the reconnect rate
  is bounded by `RelayReconnectSupervisor`'s existing capped-exponential backoff. Named rather than left
  for the verifier.
- **[Network & I/O] Two restarts racing spend the bound faster, not slower.** An `invalid_cursor` restart
  and a reconnect restart can overlap; each takes a distinct generation, so at most one settle applies —
  two round trips for one page of budget. Wrong direction for an attacker. Both triggers are serial in
  themselves (one collector; one settle per walk), so the race is bounded at two.
- **[Network & I/O] No new client, timeout, frame or dispatcher.** The restart re-asks the newest page,
  whose entries `mergeHistoryRows` dedupes against rows the thread already holds, so a restart cannot
  grow the thread without bound. The collector is per-ViewModel and so per-conversation; only one thread
  destination is live at a time, so a reconnect issues one ask, not N.
- **[Concurrency] The generation guard is the design, and it exists because the race is real, not
  hypothetical.** On reconnect an ask issued on the dead connection is genuinely in flight; without the
  guard its late `settled()` writes that connection's cursor into the restarted walk, which is exactly
  what AC #4 forbids ("a cursor minted on the previous connection is never sent on the new one"). The
  check is a value comparison inside `MutableStateFlow.update`, not a timing assumption. All four writers
  are CAS-shaped; `CancellationException` stays the first catch arm; every job is `viewModelScope`-bound.
- **[Concurrency] The background/foreground cycle self-heals, which is worth stating because it looks
  like a stuck state.** `LifecycleConnectionDriver` closes the socket on background, failing the in-flight
  ask with `IllegalStateException` → `PermanentFailure` → a dead-end row. Returning to foreground
  reconnects, the collector fires, `restarted()` clears `stoppedBy`, and the row clears. Process death
  loses the walk entirely, which is the designed non-persistence.
- **[Errors, logs, telemetry] SHOULD FIX — FIXED IN PLAN.** As first drafted this ticket inherited #777's
  total silence, which was right for a walk that could only stop and wrong for one that restarts itself:
  a restart cycle in the field leaves no trace at all. § Error handling now specifies three `RelayLog.d`
  breadcrumbs, debug-gated by the compile-time `BuildConfig.DEBUG` constant with lazy message
  construction, carrying a local literal `reason` and a `Boolean` — never `e.code`, never `e.message`,
  never a cursor. Desktop's counterpart logs the same event (`historyRetryDeps.logRequested`), so this
  also closes a divergence.
- **[Tokens, secrets, credentials] Not applicable by the cursor's own design, unchanged from #777.** It
  is unsigned, carries only the conversation id the caller already knows, and is deliberately not
  persisted — no `SavedStateHandle`, no DataStore — because the projections are connection-scoped. The
  new `walk` field is a local monotonic `Int`, not security material, and never leaves the ViewModel.
- **[Cryptographic primitives] No key, nonce, RNG or handshake material is touched.** The two
  attacker-controlled comparisons — `e.code` against a literal and #777's unchanged
  `pageCursor == cursor` — compare daemon-supplied values to a constant or to each other, so their timing
  discloses nothing and `MessageDigest.isEqual` would be cargo cult. The walk rides the existing
  `Noise_IK` session unchanged.
- **[File / storage] OUT OF SCOPE — nothing here touches the filesystem.** The restart's empty cursor
  never becomes a path, filename or cache key. The category bites when history is cached on disk, which
  is #647.
- **[Inter-process / Android attack surface] Not applicable.** No `Activity`, `Service`,
  `BroadcastReceiver`, intent filter, deep link, `PendingIntent`, content provider or WebView is added or
  touched; `MainActivity` gains one lambda wire and no destination. The two new rows render a
  `stringResource` inside an M3 `Surface`.
- **[Threat model alignment] A malicious relay is on-path but content-blind.** Against this walk its
  strongest move is to drop the connection repeatedly, which drives restarts — covered above by the
  carried budget and the supervisor's backoff. It cannot forge a `history.invalid_cursor` frame from
  outside the Noise session, so the restart branch is reachable only by the paired daemon. UI-side
  leakage is unchanged: no daemon-authored text reaches the screen through this ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

# 498 — Drain the stale retry signal before a fresh backoff

**Ticket:** #498 — A retry during a healthy connection pre-collapses the next backoff (LOW)
**Size:** XS · **Security-sensitive:** no (pure timing; no untrusted input, no key material)
**Same subsystem as:** #493 / #495 / #496 (reconnect/backoff lifecycle) — all merged.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:79-80` — the `retrySignal = Channel<Unit>(Channel.CONFLATED)` declaration; the conflation is *why* a retry sent while connected survives to the next backoff.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:118-124` — `retry()`: `connect()` (idempotent) + `retrySignal.trySend(Unit)`. **Do not change this method** — AC#3 requires it stays non-blocking and never throws.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:185-217` — `backoff()` (the fix site) and `collapsibleWait()` (`:217`, `withTimeoutOrNull(ms) { retrySignal.receive() }`). Note `backoff()` has three wait branches (daemonAbsent `:191`, sub-cap per-second loop `:199-207`, cap `:208-212`) — the drain must precede **all three**.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:126-183` — `runLoop()`: confirms `backoff()` is called from exactly one place, and that nothing suspends between `events.collect` completing (`:168`) and `backoff()` (`:181`).
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:160-183` — `retry_collapsesPendingBackoffWithoutThrowing` (AC#2 regression guard — must keep passing).
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:478-518` — `repeated4404_staysDaemonAbsent_onTheExistingBackoffSchedule`: copy its `advanceTimeBy(interval - 1)` → still-waiting, `advanceTimeBy(1)` → re-dialled assertion shape for the new test.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt:586-603` — `newPairedSupervisor()` + `intervalsFor(vararg)` helpers; the new test uses `intervalsFor(1).first()` for the exact seeded first interval.

## Context

`RelayConnectionSupervisor.retry()` writes `Unit` into a **conflated** channel (`retrySignal`, `:80`). When `retry()` is called while the loop is inside `transport.events.collect` (connected — no receiver on the signal), the `Unit` buffers in the channel. On the *next* drop, the first `collapsibleWait` (`:217`) calls `retrySignal.receive()`, immediately consumes that stale buffered `Unit`, and returns early — collapsing a backoff that should have waited its full jittered interval.

Observable effect: a tap-to-retry (or a #302 lifecycle `connect()`/`retry()`) that arrived during a healthy connection pre-arms the *next* drop's reconnect to fire instantly instead of after backoff. LOW severity, self-corrects on subsequent attempts. Manifests only with `USE_RELAY_REPOSITORY` on.

## Design

**Chosen direction (of the ticket's two candidates): drain any stale signal once, at the top of `backoff()`, before its first wait.**

The `retrySignal` is only meaningful when a wait is in progress — its sole job is to collapse a wait that is happening *now*, as a direct result of *this* drop. A signal that was buffered while no receiver was waiting (during `events.collect` on a healthy connection, or during the `Connecting` window before `Up`) is stale by definition: the retry's `connect()` half already did its job (the loop was already running / already about to re-dial), and there was no backoff to collapse. Draining discards exactly those stale signals and nothing else.

**Contract:**

```
// at the very top of backoff(attempt, daemonAbsent), before any state emission or wait branch:
while (retrySignal.tryReceive().isSuccess) { /* discard a stale pre-drop signal */ }
```

- `tryReceive()` is non-suspending and never throws — safe on an empty channel (returns a failed `ChannelResult`).
- `CONFLATED` holds at most one element, so a single `tryReceive()` is logically sufficient; the `while` loop is the robust, intent-revealing form (drains regardless of channel capacity) and costs one extra no-op poll. Prefer the loop.
- Placement is the **top of `backoff()`**, before the `daemonAbsent` check at `:191`, so it covers all three wait branches with one drain. Do **not** put it inside `collapsibleWait` — that runs per-second in the sub-cap loop and would eat an in-progress retry (breaks AC#2).

**Why this preserves AC#2 (retry *during* a wait still collapses):** the drain runs *before* `collapsibleWait` suspends on `receive()`. A retry that arrives *during* the wait is sent *after* the drain has already completed, so it lands in the channel while `receive()` is (or is about to be) suspended on it → collapses the wait exactly as today. The drain can only ever remove a signal that predates this `backoff()` call.

**Why `retry()` itself is untouched:** the fix is entirely on the consume side. `retry()` stays `connect()` + `trySend(Unit)` — non-blocking, never throws (AC#3 preserved by construction).

### State + concurrency model

No change to the state machine, flows, dispatchers, scopes, or shutdown behavior. `backoff()` remains a private suspend function on the loop coroutine; the drain is a synchronous, non-suspending poll at its head. No new `StateFlow`, no new job, no new channel.

**Accepted narrow window (not a defense to build).** Under a multi-threaded dispatcher (`Dispatchers.Default` in prod), a `retry()` whose `trySend` races into the sub-microsecond gap between `events.collect` completing (`:168`) and the drain could be discarded — costing one full backoff interval before re-dial (base-1 ≈ 1 s). This is the opposite, gentler direction of the same LOW/self-correcting class the ticket already accepts, and it does not reproduce under the single-threaded `StandardTestDispatcher` the tests use. Per evidence-based fix selection, do **not** add synchronization to close it; noted here so review understands the boundary. (On the single-threaded test dispatcher there is no suspension point in that gap, so the drain provably only ever removes genuinely-stale signals.)

## Error handling

No new failure modes. `tryReceive()` on an empty or closed channel returns a failed `ChannelResult` (no throw); `retrySignal` is never closed in this class's lifetime, so only the empty case occurs. The drain has no error surface.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.data.network.RelayConnectionSupervisorTest"`), virtual clock + seeded `Random(SEED)`, per the sibling tests. No instrumented test, no new injectable seam.

**New test — AC#1** (`retryWhileConnected_doesNotShortenFirstBackoffAfterLaterDrop`, add alongside the AC#2 test around `:183`):
- `newPairedSupervisor()`; `connect()`; `runCurrent()`.
- `factory.created[0].emitUp()`; `runCurrent()` → assert `Connected`.
- `supervisor.retry()` **while Connected** (loop inside `events.collect`, no wait in progress); `runCurrent()`. Assert still `Connected` and `factory.created.size == 1` — the stale retry's `connect()` is idempotent, so it must **not** trigger a re-dial while healthy.
- `factory.created[0].emitDown()`; `runCurrent()`. Compute `val interval = intervalsFor(1).first()`; assert `state == ConnectionState.Reconnecting(ceil(interval / 1000.0).toInt())`.
- `advanceTimeBy(interval - 1)`; `runCurrent()` → assert **still** `Reconnecting` and `factory.created.size == 1` (backoff **not** collapsed by the stale signal — the core assertion).
- `advanceTimeBy(1)`; `runCurrent()` → assert `Connecting` and `factory.created.size == 2` (re-dialled only after the full jittered interval).
- `supervisor.close()`.

**Regression — AC#2** (existing `retry_collapsesPendingBackoffWithoutThrowing`, `:163`): must keep passing unchanged. The drain runs before the wait on an empty channel, then the mid-wait `retry()` still collapses.

**Regression — AC#3 / benign paths:** `benignUnpaired_doesNotDialAndStaysConnected` (`:260`) and the existing backoff-progression / stability / 4404 tests must all keep passing — the drain is a no-op whenever no stale signal was buffered.

## Open questions

None. The direction, placement, and the accepted narrow window are all resolved above.

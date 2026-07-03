# 489 — Connect after pairing, and reload the paired server per dial

**Ticket:** #489 (HIGH, `security-sensitive`) · **Size:** XS

## Files to read first

- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:34-40` — the `RelayConnectionController` seam (`connect()` / `close()`); the narrow, data-free surface the Scanner will inject and call.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:98-104` — `connect()`: `@Synchronized`, idempotent (early-returns if a loop is already active). This is the whole reason re-pair-while-connected relies on reload-per-dial, not on a second `connect()`.
- `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt:125-177` — `runLoop()`: the `load()` + null→idle early-return at `:126-131` sits **before** `while (isActive)` at `:134`. This is the single production edit — move that read inside the loop.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:152-181` — `composable(Routes.SCANNER)` + `confirmPairAndNavigate`; the `try { save(); navigate() } catch (PairedServerStoreException)` shape to hook, and the existing `koinInject<PairedServerStore>()` at `:154` to sit the controller inject beside.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:289-302` — the paste dialog's Pair button; confirm it already funnels through `confirmPairAndNavigate` (`:294`), so hooking one function covers both entry points.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:77-78` — the supervisor `single { … } bind ConnectionStateSource::class`; add the `RelayConnectionController` bind here. Note `get<RelayConnectionSupervisor>()` is resolved by concrete type at `:84`, `:95-96` — the primary registration must stay intact.
- `app/src/main/java/de/pyryco/mobile/lifecycle/LifecycleConnectionDriver.kt:27-52` — the established consumer of `RelayConnectionController` (injects the interface, calls `connect()`/`close()`). The Scanner follows the same pattern; do NOT re-implement the unpaired gate or idempotency in the Scanner.
- `app/src/main/java/de/pyryco/mobile/data/crypto/PairedServerStore.kt:19-52` — `load(): PairedServer?` / `save(record)` contract; `load()` returns `null` for absent **or** undecryptable, `save()` throws `PairedServerStoreException`.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerViewModel.kt:86-89` — the explicit design note that the confirm/save side effect is a route-scope callback, NOT a VM event (keeps the VM Android-free). This spec preserves that: the new helper is a free function, not VM logic.
- `app/src/test/java/de/pyryco/mobile/data/network/RelayConnectionSupervisorTest.kt` — the whole file. Reuse `newPairedSupervisor()`, `FakeRelayTransportFactory`, `StubPairedServerStore`, `intervalsFor()`, the `runCurrent()`/`advanceUntilIdle()` virtual-clock idiom, and the `benignUnpaired_doesNotDialAndStaysConnected` test (`:254-279`) as the shape for the new AC-2/AC-3 tests.

## Context

The relay supervision loop reads the paired record **once**, before `while (isActive)`, and early-returns when unpaired. Two bugs surface only when `USE_RELAY_REPOSITORY` is flipped on (this ticket is a HIGH blocker for that flip):

1. **No connect-on-pairing.** Nothing calls `connect()` when a fresh pairing persists. The loop only (re)starts on the next process-lifecycle `onStart` (`LifecycleConnectionDriver`), so a first pairing appears to connect only after a background→foreground cycle.
2. **Stale-record dial.** A loop that started against server A keeps dialing A even after the user re-pairs to B, because the record is captured once at loop start and never re-read.

This is plausibly what the temporary `PYRYDBG` logging (#500) was chasing.

## Design

Three surgical changes plus one tiny testable seam. No new types beyond one free function.

### 1. Reload the paired record per dial (AC 2, AC 3)

In `RelayConnectionSupervisor.runLoop()`, move the `load()` + null-guard from before the loop to the **top of the loop body**. Keep `var attempt = 0` before the loop (the backoff-escalation counter still spans dials). Contract sketch of the changed region only:

```kotlin
private suspend fun CoroutineScope.runLoop() {
    var attempt = 0
    while (isActive) {
        val paired = pairedServerStore.load() // re-read per dial (AC 2)
        if (paired == null) {
            state.value = RelayLinkStatus.Connected // idle, banner hidden (AC 3)
            return
        }
        state.value = RelayLinkStatus.Connecting
        val transport = transportFactory.create(paired)
        // …unchanged: connect / events.collect / finally / stability / backoff…
    }
}
```

Everything from `state.value = Connecting` downward is **unchanged**. On a `null` read the loop `return`s (ends the job) exactly as the pre-change early-return did — a later `connect()`/`retry()` starts a fresh loop that re-reads the store. Do **not** `continue` on null (that would busy-spin).

Behavioural consequences, all intended:
- First-pairing (loop previously ended on null): the new `connect()` from §3 starts a fresh loop → reads the now-present record → dials. No background/foreground needed.
- Re-pair A→B while a loop is **running**: `connect()` no-ops (idempotent); the loop stays on A until A's socket drops, then the next iteration's `load()` returns B and dials B. Immediate teardown-and-reconnect on re-pair is explicitly out of scope (see Security review).
- A later-iteration `null` (un-pair, or an undecryptable transient read) idles at `Connected` — the AC-3 semantics applied per dial, not just at loop start.

### 2. Expose the controller to the Scanner (Koin)

Add `RelayConnectionController` to the supervisor's binds (`AppModule.kt:78`), matching the existing single-`bind` idiom (Koin 4.0.4 chains `bind`):

```kotlin
single { RelayConnectionSupervisor(get(), get()) } bind
    ConnectionStateSource::class bind RelayConnectionController::class
```

Add `import de.pyryco.mobile.data.network.RelayConnectionController`. The primary concrete registration is unchanged, so `get<RelayConnectionSupervisor>()` at `:84`/`:95-96` still resolves. Inject the **interface**, not the concrete supervisor — the Scanner must only be able to start the loop, never touch `currentConnection` / `relayStatus` / `retry` (this is exactly the seam's purpose per its KDoc, and mirrors `LifecycleConnectionDriver`).

### 3. Connect after a successful persist (AC 1) — via a testable free function

The persist→connect→navigate side effect currently lives inline in the `confirmPairAndNavigate` composable lambda, where it cannot be reached by a JVM unit test (it captures the NavController and is inside the private, Koin-bound `PyryNavHost` — the codebase's own `LiteralScreenNavigationTest` documents why that is untestable directly). Extract the orchestration into one free, Android-free `suspend fun` so AC 1 gets a fast `testDebugUnitTest` regression test that guards the real code path. This does **not** move logic into `ScannerViewModel` (that would break its deliberate Android-free contract) — it's a plain function.

New file `app/src/main/java/de/pyryco/mobile/ui/onboarding/PairingConfirmation.kt`:

```kotlin
suspend fun confirmPairingAndConnect(
    server: PairedServer,
    store: PairedServerStore,
    controller: RelayConnectionController,
    onPersisted: () -> Unit,
    onFailed: (PairedServerStoreException) -> Unit,
)
```

Behaviour: `try { store.save(server); controller.connect(); onPersisted() } catch (e: PairedServerStoreException) { onFailed(e) }`.

- `connect()` is called **only after** `save()` returns successfully — a failed persist throws before `connect()`, so a record that never persisted is never dialed (AC 1 fail-closed).
- Order is `save → connect → onPersisted`: call `connect()` **before** the navigate callback so the loop is launched (on the supervisor's own scope) even though `onPersisted()` navigates and pops the Scanner, cancelling the composable coroutine scope.
- The catch is narrow (`PairedServerStoreException` only), matching today's code, so `CancellationException` from a cancelled `save()` propagates rather than being swallowed (see [[catch-illegalstate-swallows-cancellation]]).

`MainActivity.confirmPairAndNavigate` becomes a thin caller: inject `val connectionController = koinInject<RelayConnectionController>()` beside the existing `pairedServerStore` inject (`:154`), then `scope.launch { confirmPairingAndConnect(server, pairedServerStore, connectionController, onPersisted = { navController.navigate(...) { popUpTo(SCANNER){inclusive=true}; launchSingleTop=true } }, onFailed = { e -> Log.w(TAG, "paired-server save failed: ${e.javaClass.simpleName}"); vm.onEvent(ScannerEvent.PairingFailed(SAVE_FAILED_MSG)) }) }`. The `Log.w` + `PairingFailed` stay in the `onFailed` callback (keeps the extracted function log-free and Android-free). Add `import de.pyryco.mobile.ui.onboarding.confirmPairingAndConnect` and the `RelayConnectionController` import.

## State + concurrency model

- **Loop scope ownership is unchanged.** `connect()` launches `runLoop()` on the supervisor's own `CoroutineScope(SupervisorJob() + dispatcher)` — decoupled from the composable's `rememberCoroutineScope`. Navigating away from the Scanner (which cancels that composable scope) does NOT kill the supervision loop; only `close()` (app background, via `LifecycleConnectionDriver`) does. This is the property that makes "connect on pairing and stay connected on the channel list" work.
- **`connect()` idempotency + `@Synchronized`** already guards against a double loop; the Scanner adds a caller but no new concurrency primitive.
- **Reload adds one suspension point per dial** (`load()`), on the supervisor's dispatcher, inside the cancellation-cooperative `while (isActive)` loop. On `close()` an in-flight `load()` cancels cleanly (the job is cancelled). No `NonCancellable`, no mutex.
- **No new hot/cold flow.** `relayStatus`/`observe()`/`currentConnection` surfaces are untouched.

## Error handling

- **Failed persist** → `PairedServerStoreException` → `onFailed` → existing `SAVE_FAILED_MSG` Error surface; `connect()` never fires. Unchanged user-facing behaviour, now with the connect() gated behind it.
- **`load()` returns null mid-loop** (un-pair or undecryptable record) → loop idles at `Connected` (banner hidden) and ends; a subsequent `connect()`/`retry()` re-reads. This is the intended AC-3 semantics per dial. Not treated as an error — `PairedServerStore.load()` defines an undecryptable record as "graceful → re-pair," and idle is the correct response to "no valid record." No new observed failure mode, so no added defense (evidence-based).
- **Transport/dial failures** are handled by the unchanged backoff path below the edit.

## Testing strategy

All JVM (`testDebugUnitTest`) — no device, matching the existing supervisor + data-layer posture. Run a single class with `./gradlew testDebugUnitTest --tests "<FQCN>"` (bare `test --tests` fails — [[gradle-single-test-class-task]]).

**`RelayConnectionSupervisorTest.kt` (extend):**
- Extend `FakeRelayTransportFactory` to record the `PairedServer` passed to `create()` (e.g. a `createdWith: MutableList<PairedServer>`), so a test can assert which server dial N targeted.
- Add a scripted `PairedServerStore` stub that returns a different value on successive `load()` calls (constructor takes an ordered list / a flippable `var`, clamping to the last).
- **AC 2 — reload switches server:** store returns SERVER_A then SERVER_B; `connect()` → dial 0 (assert `createdWith[0] == A`) → `emitUp`/`emitDown` → `advanceUntilIdle` → dial 1 → assert `createdWith[1] == B`.
- **AC 3 — later-iteration null idles:** store returns SERVER_A then `null`; `connect()` → dial 0 → `emitDown` → `advanceUntilIdle` → assert `state == Connected`, `created.size == 1`, `currentConnection.value == null` (no second dial). Complements the existing first-iteration `benignUnpaired_…` test.
- The existing tests should still pass unchanged. Note the extra per-iteration `load()` suspension: transitions still settle under the same `runCurrent()`/`advanceUntilIdle()` calls (a returning suspend `load()` schedules its continuation at the current virtual time, which `runCurrent()` drains) — if a re-dial assertion ever needs one extra `runCurrent()`, that suspension is why.

**New `PairingConfirmationTest.kt` (`app/src/test/java/de/pyryco/mobile/ui/onboarding/`), under `runTest`:**
- Fakes: a `PairedServerStore` whose `save()` either records the record or throws `PairedServerStoreException`; a `RelayConnectionController` fake counting `connect()`.
- **AC 1 success:** `save` succeeds → assert the record was saved, `connect()` called exactly once, `onPersisted` invoked once, `onFailed` not invoked.
- **AC 1 fail-closed:** `save` throws → assert `connect()` called **zero** times, `onFailed` invoked with the exception, `onPersisted` not invoked.

Write test bodies in the project idiom (JUnit4 + `runTest`); the scenarios above are the contract, not the code.

## Open questions

- None blocking. The `attempt` backoff counter carries across an A→B server switch (a B dial after A escalated to attempt 5 waits on the attempt-5 backoff). This is benign and out of scope — resetting `attempt` on server change is not required by any AC and would add branch logic.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No new finding; the change *closes* a boundary gap. The paired record is the trusted credential, validated at pairing time by the fingerprint-confirm gate (#320/#342/#343). Reload-per-dial re-reads that already-trusted store each dial, so the encrypted transport tracks the *current* paired server instead of one captured once — eliminating the stale-record dial the ticket flags. `connect()` is payload-free by the `RelayConnectionController` contract (KDoc `RelayConnectionSupervisor.kt:34-40`), so the Scanner cannot inject data into the connection path.
- **[Tokens, secrets, credentials]** No findings. `PairedServer.token` is redacted in `toString()`; the supervisor's no-log contract (`RelayConnectionSupervisor.kt:62-66`) is preserved — the added `load()` logs nothing. The extracted helper is log-free (the `Log.w` stays in `MainActivity`'s `onFailed` and logs only `e.javaClass.simpleName`, no secret). Storage is the unchanged Keystore-wrapped `KeystorePairedServerStore`.
- **[File / storage operations]** N/A. No new file/path operations; `save`/`load` are the existing encrypted store.
- **[Inter-process / Android attack surface]** N/A. No new exported component, intent, deep link, or pending intent. QR/paste input handling is unchanged.
- **[Cryptographic primitives]** N/A. No crypto changes; the Noise_IK handshake and the non-security jitter `Random` are untouched.
- **[Network & I/O]** No findings. `OkHttpRelayTransport` (frame caps, timeouts, TLS) is created per dial via the unchanged factory. `connect()`-after-`save` cannot spam connections: it is idempotent, so a rapid re-pair loop no-ops while a loop runs — no token-exhaustion amplification.
- **[Error messages, logs, telemetry]** No findings — see Tokens; no new telemetry.
- **[Concurrency]** No MUST FIX. Loop lifetime stays on the supervisor's own `SupervisorJob` scope (survives Scanner navigation); `connect()` is `@Synchronized` + idempotent; `save → connect → onPersisted` ordering runs `connect()` before navigation-induced cancellation; the narrow `catch (PairedServerStoreException)` does not swallow `CancellationException` ([[catch-illegalstate-swallows-cancellation]]). Store save/load serialize at the DataStore/Keystore layer — no cross-corruption on a re-pair-mid-backoff TOCTOU (the loop simply reads whichever record is current at dial time, which is the intended convergence).
- **[Threat model alignment]** OUT OF SCOPE — *immediate* teardown-and-reconnect on re-pair. While a loop is **connected** to server A, re-pairing to B does not converge until A's socket drops; a hostile A that holds the socket open would keep the transport bound to A despite the user re-pairing to B. This is a *residual narrowing* of a pre-existing gap (pre-fix, the loop dialed A **forever** even across drops), not a new exploit, and it is inert until `USE_RELAY_REPOSITORY` is flipped on. The ticket explicitly scopes immediate teardown out. Recommend a follow-up ticket ("relay: tear down and re-dial immediately on re-pair") owned by PO; code-review need not gate on it.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-03

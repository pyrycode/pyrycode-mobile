# #476 — Layer 2b: reconnect continuity (reply survives a mid-turn drop)

**Ticket:** test(e2e): mobile e2e ladder — Layer 2b reconnect continuity
**Size:** S (test + harness only; **no production Kotlin**)
**Split from:** #436 (sibling #477 = ordering, `blockedBy #476` — reuses this slice's drop/restore primitive)
**Labels:** `size:s` — NOT `security-sensitive`, NO Figma (exercises shipped, code-reviewed UI/behaviour).

## Design source

N/A — pure e2e test of already-shipped behaviour (#416 cursor advertise, #337/#385 delta fold, #391/#392 reconnect). No new UI; visual fidelity is out of scope.

## Files to read first

| Path | Lines | Extract |
| --- | --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` | full (esp. `showsThinkingSpinnerDuringTurn` 136-164, `arriveInSeededThread` 222-238, companion 256-298) | The two-drop causal-fence pattern this scenario mirrors; the shared helpers (`arriveInSeededThread`, `typeAndSend`, `awaitConnected`) and Koin-fetch idiom (`GlobalContext.get().get<…>()`) to reuse verbatim. |
| `scripts/e2e-emulator.sh` | 135-180 (SCENARIO `case`), 318-353 (two-drop watcher) | Add a `reconnect)` case arm beside `spinner)`/`tool)`; the existing two-drop watcher (drop A on enqueue #1, drop B on enqueue #2) is reused **unchanged**. |
| `scripts/e2e-fixtures/spinner-open.jsonl`, `spinner-end.jsonl` | 1 line each | The held-open `thinking` line (drop A shape) and the turn-ending text line (drop B shape) this scenario's fixtures copy. |
| `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` | 34-40 (`RelayConnectionController`), 100-123 (`connect`/`close`/`retry`), 83-90 (`currentConnection`/`relayStatus`) | The drop/restore seam: `close()` severs the live socket (phone leg drops, daemon stays up), `connect()` re-dials. The concrete supervisor implements both `ConnectionStateSource` and `RelayConnectionController`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` | 99 (`replayCursor`), 118-141 (`currentRepository`, Open-gated) | The reconnect detector: `currentRepository` is `null` between connections, non-null once the fresh pump reaches `Open`. The `ReplayCursor` lives here (survives connection churn) → the next `hello` re-advertises `last_event_id`. |
| `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt` | full | The high-water dedup fold (`record` strictly-greater, `reset` on `resync`) that makes a re-delivered event idempotent. Read-only context — already shipped, not modified. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | 60-107 | Confirms `get<RelayConnectionSupervisor>()` and `get<RelayRepositoryCoordinator>()` resolve (both registered `single`s); `last_event_id` is read live at hello-build off the coordinator's `replayCursor`. |
| `docs/e2e-interactive-stream.md` | "Scenarios (#454)" table 199-212, "Fixture format" 184-197, "Assumptions to confirm" 291-326 | Where the new scenario row + subsection land; the prose style and the first-run-assumption list to extend. |

## Context

The deterministic e2e ladder (rung 4, #431) drives the **real** app on a headless emulator against the scripted `fakeclaude` backend over the real Noise/relay path. #454/#455 added steady-state stream scenarios (multi-delta, thinking spinner, tool rows) and deferred the harder case: the phone **losing and re-establishing its relay link while a turn is still streaming**.

This slice adds the `reconnect` scenario and — its reusable deliverable — a **drop/restore harness primitive** (`severAndRestoreLink()`) that #477 (ordering) builds on. It proves an in-flight turn survives a mid-turn drop: the daemon's turn stays open across the phone's outage (daemon never restarted, its in-ring buffer intact), and after reconnect the phone re-attaches, advertises its `last_event_id` (#416), and the reply completes and renders **exactly once** — the end-to-end proof that the `event_id` high-water + `message_id` upsert dedup (#337/#385) can't silently regress.

**Buildability verdict: BUILDABLE, no finding.** The ticket's #431 "buildable-in-the-right-shape" gate asks two questions:

1. *Can the harness cleanly drop+restore the phone link mid-turn, without restarting the app or daemon?* **Yes** — `RelayConnectionSupervisor.close()` severs the live socket at the transport/relay layer (the daemon's session to the relay is independent and stays up, so its in-ring buffer survives), and `connect()` re-dials with a fresh Noise session whose `hello` re-advertises `last_event_id`. Both are shipped (#307/#416) and reachable from the instrumented test via Koin (`get<RelayConnectionSupervisor>()` — the concrete class implements `RelayConnectionController`). This is a **phone-side** sever — it never touches the daemon, so it cannot trip the `resync`/gap path that a daemon restart (#417) would.
2. *Does the daemon replay the in-flight turn to the reconnecting phone in the fakeclaude setup?* **The design is deliberately built to NOT depend on this** (see "Why drop A carries no partial text" below) — so the murky question the ticket flags is sidestepped, not relied upon. What the design *does* require (the open turn continues + completes after reconnect; the brief drop stays in-window so no `resync`) is listed as first-run assumptions, consistent with every prior rung-4 scenario.

## Design

### Shape: the `reconnect` scenario = the two-drop fence + a sever/restore in the gap

It mirrors the **shipped** `spinner` scenario (`showsThinkingSpinnerDuringTurn`) exactly, with one insertion between the two sends:

1. `arriveInSeededThread()` (shared helper, verbatim) → land in the seeded `e2e-seed` channel thread, connection `Connected`.
2. `typeAndSend(SEND_PROMPT)` → **enqueue #1** → watcher copies **drop A** (`reconnect-open.jsonl`, a `thinking`-only line) → producer emits `turn_state(thinking)`, turn held open.
3. Wait for the thinking content-description (`cd_thread_thinking`) → **proves the turn is mid-stream** at the moment we sever (AC #2).
4. **`severAndRestoreLink()`** — the new primitive (below): drop the phone's socket, confirm it dropped, restore it, confirm it's back (AC #1).
5. `typeAndSend(SECOND_PROMPT)` → **enqueue #2** → watcher copies **drop B** (`reconnect-done.jsonl`, a complete reply + `end_turn`) → producer continues the held-open turn → the reply streams to the reconnected phone (AC #2: "completes after the phone reconnects").
6. Wait for the reply substring, then assert it renders in **exactly one** node (AC #3 — no missing, no duplicated text/rows).

The 2nd prompt is **inert** (the scripted backend ignores its text); its only job is to causally fence drop B on `send_message.enqueued` #2 — identical to spinner/tool. The sever/restore in step 4 injects **no** `send_message`, so the watcher's enqueue count stays clean (exactly 2) and the **existing two-drop watcher needs no change**.

### The drop/restore primitive (`severAndRestoreLink()`) — the reusable harness deliverable

A private helper in the test, fetching the singletons off Koin the same way `awaitConnected()` already fetches `ConnectionStateSource`:

- `val supervisor = GlobalContext.get().get<RelayConnectionSupervisor>()` — concrete; exposes `close()`/`connect()` (`RelayConnectionController`) and `currentConnection`/`retry()`.
- `val coordinator = GlobalContext.get().get<RelayRepositoryCoordinator>()` — exposes `currentRepository: StateFlow<ConversationRepository?>`.

Behaviour (contract — developer writes the body in the project idiom, `runBlocking { withTimeout(…) { … } }` per `awaitConnected`):

1. **Sever:** `supervisor.close()` — cancels the supervision loop, closes the live socket (the daemon/relay see the phone leg vanish), returns the supervisor to idle.
2. **Confirm dropped:** await `coordinator.currentRepository.first { it == null }` — the connection-scoped repo is torn down. `close()` leaves the loop cancelled (no auto-redial racing), so the `null` phase is **stable** → this await is race-free (no StateFlow conflation hazard).
3. **Restore:** `supervisor.connect()` — starts a fresh supervision loop → new dial → new Noise `hello` advertising `last_event_id` (read live off the coordinator's surviving `replayCursor`).
4. **Confirm reconnected:** await `coordinator.currentRepository.first { it != null }` — the fresh pump reached `Open` (so the step-5 `sendMessage` won't throw `IllegalStateException`).

**Why the intentional `close()`/`connect()` path, not a transport-level drop.** `close()` cancels the loop so the `null` phase is stable and the reconnect is test-triggered (no backoff jitter) — maximally deterministic, which is the rung-4 contract. From the **daemon's** view it is identical to a network drop (a WS close on the phone leg; the in-ring buffer is process-scoped and survives), and `connect()` exercises the same `last_event_id` advertise (#416) as an auto-reconnect, so replay continuity is tested identically. A real-network-drop variant (`supervisor.currentConnection.value?.close()` then `supervisor.retry()` to collapse backoff) is available as a more faithful alternative if a future ticket wants the auto-reconnect state machine in the loop — note it in the doc, don't wire it here.

### Why drop A carries no partial text (the load-bearing design choice)

Drop A is `thinking`-only (no `assistant_delta`). This is deliberate: on drop, the connection-scoped repo is torn down and `currentRepository` → `null`, so the facade's `observeMessages` emits `emptyList()` (the thread clears); the new repo on reconnect starts with an **empty** projection. If drop A had streamed partial text, that partial would live only on the torn-down repo, and whether the daemon re-delivers it on reconnect depends on uncertain partial-turn replay semantics in fakeclaude (events at-or-below `last_event_id` are not re-delivered by the gap-free path) — exactly the buildability risk the ticket flags. By keeping drop A text-free and delivering the **complete** reply in drop B **after** reconnect, the test proves continuity without depending on that uncertain behaviour, and "no missing text" is guaranteed by construction (the whole reply arrives post-reconnect, once).

### Fixtures (2 new, same format as #454/#455)

- `scripts/e2e-fixtures/reconnect-open.jsonl` — one `thinking`-only assistant line (held-open turn), shape-identical to `spinner-open.jsonl`. Dedicated (not a `spinner-open` reuse) so a future edit to the spinner fixture can't silently change this scenario.
- `scripts/e2e-fixtures/reconnect-done.jsonl` — one complete assistant line: `stop_reason: "end_turn"` + a **unique** multi-word text (e.g. `"reconnected reply ok"`). Single-line (no multi-delta — #454 covers assembly); the unique substring `"reconnected reply"` self-documents intent and collides with nothing on screen (`e2e-seed` title, `ping`, `Bash`, `streamed world`).

### Script wiring (`e2e-emulator.sh`)

Add a `reconnect)` arm to the SCENARIO `case` (beside `spinner)`/`tool)`), a two-drop scenario:

- `TEST_METHOD=interactiveTurn_seededChannel_replySurvivesMidTurnReconnect`
- `FIXTURE_FILE=${FIXTURES_DIR}/reconnect-open.jsonl` (drop A), `FIXTURE_FILE_2=${FIXTURES_DIR}/reconnect-done.jsonl` (drop B)
- Add it to the `case` error message and the usage/`SCENARIO` comment block. The two-drop watcher and everything downstream are unchanged.

## State + concurrency model

- No new app state, no new types, no ViewModel/Flow changes — this is a test that drives **shipped** flows.
- The test runs on the instrumentation thread; the two `currentRepository` awaits and the existing `awaitConnected` use `runBlocking { withTimeout(...) { flow.first { … } } }` (the established idiom at `DeterministicInteractiveStreamE2ETest.kt:247-254`). Compose waits use `composeTestRule.waitUntil(timeout) { … }`.
- `close()`/`connect()` are `@Synchronized` and idempotent; the `ProcessLifecycleOwner` driver only fires on lifecycle **edges** (none during a stable foreground instrumented run), so it does not race the explicit drive — listed as a first-run assumption.
- `StateFlow.first { predicate }` over `currentRepository`: race-free here because `close()` makes the `null` phase stable (no auto-reconnect collapsing it).

## Error handling

Failure modes are **test failures** (timeouts), surfaced by the existing generous timeouts (`REPLY_TIMEOUT_MS = 90_000L`, etc.). Add a `RECONNECT_TIMEOUT_MS` (reuse 30_000L like the connect/thread waits) for the two `currentRepository` awaits. There is no in-app error path to exercise: a clean reconnect surfaces no `resync` (AC #4), and a stale/aged-out cursor would (the path this test must NOT hit) — guarded structurally by the immediate reconnect, not by app code.

## Testing strategy

- This *is* the test: one `@Test` method on `DeterministicInteractiveStreamE2ETest`, run as `…class=<class>#<method>` by `SCENARIO=reconnect`. Operator-run on a headless emulator + host daemon (not a CI gate — the org doesn't gate on Actions).
- Host-JVM verification before operator run (no device): the whole project still compiles incl. `compileDebugAndroidTestKotlin` (per the [[androidtest-not-compiled-by-mandatory-gates]] lesson — `test`/`lint`/`assembleDebug` do **not** compile androidTest; run it explicitly), and the new fixtures are valid one-line JSON.
- **Tolerant assertions**, with one deliberate, scoped exception: the "renders exactly once" check is `onAllNodesWithText(<reply substring>, substring = true).assertCountEquals(1)`. The ladder's "never assert on counts" rule targets **delta counts and streaming timing** (which vary harmlessly); a count of **1 on the final assembled reply text** is the load-bearing dedup invariant — the entire point of the scenario — and is stable (any duplicate from replay/snapshot is a stable extra row, not a transient). Call this out in the method KDoc.
- Test scenarios the one method asserts (bullet form — developer writes them in the project idiom):
  - *mid-stream proof*: after send #1, `cd_thread_thinking` is displayed (turn open when severed).
  - *drop landed*: `currentRepository` observed `null` during `severAndRestoreLink()` (the connection truly dropped — not a no-op).
  - *reconnected*: `currentRepository` observed non-null again (fresh pump `Open`).
  - *exactly once*: after send #2, wait for `"reconnected reply"` present, then `assertCountEquals(1)` — complete reply, one node, no duplicate/missing.

## Open questions / first-run assumptions (extend the doc's list)

- **Open turn continues + completes after phone reconnect (fakeclaude).** After the phone re-attaches (new conn_id/Noise session), the relay routes the daemon's continued stream to the new connection, and drop B (fenced on send #2) completes the held-open turn to the reconnected phone. This is the shipped relay+daemon contract (#416/#646) but unverified end-to-end with a reconnect in the middle — confirm on first run. If the held-open turn does **not** resume to the reconnected phone, that is the buildability finding to surface (do **not** work around it by restarting the daemon — that trips #417's gap path).
- **Brief drop stays in the in-ring window → no `resync` (AC #4).** The reconnect is immediate (test-triggered, no host delay), so `last_event_id` cannot age out of the daemon's bounded buffer → the gap-free path runs and the cursor is never `reset()`. Guaranteed by construction; the observable proxy is the exactly-once render (a `resync`-driven full reload would be a different code path). `resync` is not cleanly UI-observable, so it is not separately asserted — confirm on first run that no `resync` is logged.
- **`ProcessLifecycleOwner` driver doesn't fight the explicit drive.** A stable foreground instrumented run emits no `onStart`/`onStop` edges, so `LifecycleConnectionDriver` won't re-`connect()`/`close()` under the test. If it does (e.g. an emulator focus blip), prefer the real-transport-drop variant (`currentConnection.value?.close()` + `retry()`), which doesn't cancel the loop.
- **Fixture-drop fence token** unchanged from #454/#455 (`send_message.enqueued`); the two enqueues are the two user sends.

## Out of scope (follow-ups for #477 / future)

- Asserting replay of events **buffered during the outage** (drop B firing while the phone is disconnected) — needs a host-observable disconnect fence rather than the post-reconnect send fence; deferred. This slice's `severAndRestoreLink()` primitive is the seam #477 (ordering) reuses.
- Multi-delta assembly across the drop, and the auto-reconnect backoff state machine (#391/#392 own that) — not this test's concern.

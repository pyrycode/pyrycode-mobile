# #477 — Layer 2d: post-reconnect replay ordering (missed events replay in order)

**Ticket:** test(e2e): mobile e2e ladder — Layer 2d reconnect replay ordering
**Size:** S (test + harness only; **no production Kotlin**)
**Split from:** #436 (sibling #476 = reconnect *continuity*, shipped PR #478 — this slice reuses its drop/restore seam)
**Labels:** `size:s` — NOT `security-sensitive`, NO Figma (exercises shipped, code-reviewed behaviour).

## Design source

N/A — pure e2e test of already-shipped behaviour (#416 cursor advertise + in-ring replay, #337/#385 delta+dedup fold, #391/#392 reconnect). No new UI; visual fidelity is out of scope. Code-review's visual-fidelity check is intentionally skipped.

## Files to read first

| Path | Lines | Extract |
| --- | --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt` | full (esp. `replySurvivesMidTurnReconnect` 228-275, `severAndRestoreLink` 311-342, `arriveInSeededThread` 284-293, `typeAndSend` 295-299, companion 344-396) | The #476 scenario this one forks; the **atomic** `severAndRestoreLink()` to **split** into `severLink()` + `restoreLink()`; the shared helpers + Koin-fetch idiom (`GlobalContext.get().get<…>()`) to reuse verbatim; the `assertCountEquals(1)` dedup-invariant pattern + its import. |
| `docs/knowledge/codebase/476.md` | "Lessons learned" 115-121 | The explicit hand-off: #476 proved continuity but **did not stress** an actual buffered-during-outage re-delivery (its reply arrived strictly *after* reconnect, event_id > advertised cursor → delivered once, never deduplicated). This slice is the home for that. |
| `scripts/e2e-emulator.sh` | 136-187 (SCENARIO `case`, esp. `reconnect)` 165-169), 325-361 (the two-drop watcher) | Add a `replay-order)` case arm beside `reconnect)`; the two-drop watcher is **not** reused as-is — this scenario needs a new fence for drop B (below). |
| `scripts/e2e-fixtures/reconnect-open.jsonl`, `stream.jsonl`, `reconnect-done.jsonl` | 1 / 3 / 1 lines | Drop-A shape (a lone `thinking` line, held open), the multi-delta shape (distinct `message.id`s, last with `stop_reason: "end_turn"`), and the single-reply shape this scenario's two new fixtures combine. |
| `app/src/main/java/de/pyryco/mobile/data/network/RelayConnectionSupervisor.kt` | 68 (class), 96 (`observe`), 100 (`connect`), 108 (`close`), 120 (`retry`); `currentConnection`/`relayStatus` props | The drop/restore seam: `close()` severs the live socket (phone leg drops, daemon stays up), `connect()` re-dials. Concrete class implements both `ConnectionStateSource` and `RelayConnectionController`. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RelayRepositoryCoordinator.kt` | 78 (class), 99 (`replayCursor`), 118-141 (`currentRepository`, Open-gated) | The reconnect detector: `currentRepository` is `null` between connections, non-null once the fresh pump reaches `Open`. The `ReplayCursor` lives here (survives connection churn) → the next `hello` re-advertises `last_event_id`. **The fold is rebuilt fresh on reconnect** — see Design. |
| `app/src/main/java/de/pyryco/mobile/data/network/ReplayCursor.kt` | 22 (class), 35 (`record`, strictly-greater + fail-closed `≤0`), 49 (`reset`) | The high-water dedup fold that makes a re-delivered event idempotent. Read-only context — shipped, not modified. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` | 92 (class) + `applyAssistantDelta`/`finalizeAssistantTurn` | The **connection-scoped** delta fold (#337): torn down on drop, **rebuilt empty** on reconnect. The ordering-preservation reasoning hinges on this — read Design. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | 60-107 | Confirms `get<RelayConnectionSupervisor>()` and `get<RelayRepositoryCoordinator>()` resolve (both registered `single`s); `last_event_id` is read live at hello-build off the coordinator's `replayCursor`. |
| `docs/e2e-interactive-stream.md` | "Scenarios (#454)" table 210-217, `reconnect` narrative 279-304, "Fixture format" 188-201, "Assumptions to confirm" 324-376 | Where the new scenario row + subsection + first-run assumptions land; the prose style to match. |

## Context

The deterministic e2e ladder (rung 4, #431) drives the **real** app on a headless emulator against the scripted `fakeclaude` backend over the real Noise/relay path. #476 (Layer 2b) added the `reconnect` *continuity* scenario and its reusable `severAndRestoreLink()` drop/restore primitive — proving a single in-flight reply survives a mid-turn link drop and renders exactly once.

But #476 left a gap, called out explicitly in its own code review and hand-off (`docs/knowledge/codebase/476.md` Lessons): its reply arrived **entirely after reconnect** (drop B was fenced on the phone's 2nd `send_message.enqueued`, which can only happen once the phone is back online), so the reply's `event_id` was strictly greater than the advertised `last_event_id` → it was **delivered once, never deduplicated**. The buffered-during-outage replay path — events that accrue on the daemon **while the phone is offline** and then replay on reconnect — was never traversed.

This slice (Layer 2d) closes that gap: a **sequence** of ≥3 distinct events is produced entirely while the phone's relay link is severed, accrues in the daemon's in-ring buffer, and on reconnect replays **in production order, each exactly once**. The failure mode under test is **ordering** (and loss/duplication on replay), distinct from #476's mere presence.

### Buildability verdict: BUILDABLE, no finding

The ticket's #431 "buildable-in-the-right-shape" gate asks: *does the in-ring replay deliver the buffered sequence in production order to the reconnected phone, given the fold is proven correct only within a single connection (and a reconnect opens a new connection)?*

**Yes — by construction, for the same reason #476's continuity held.** The reasoning:

1. The daemon replays buffered events in ascending `event_id` (= production) order — the pyrycode #647 unit oracle. Server side proven.
2. The mobile fold (`RemoteConversationRepository`) concatenates in **arrival** order. Since the daemon delivers in ascending `event_id`, arrival order == production order.
3. The fold is **connection-scoped**: torn down to `null` on drop, **rebuilt empty** on reconnect. The doc's "fold proven correct only within a single connection" caveat is about the case where the phone renders *partial* content before the drop — that content lives on the torn-down repo and is lost, while the rest folds into the fresh repo. **This slice sidesteps that exactly as #476 did:** nothing renderable is delivered before the drop (the held-open turn is `thinking`-only). The **complete** ordered sequence is produced *after* the sever and replays *after* reconnect into the fresh, empty repo — which folds it in arrival (= production) order. Cross-reconnect ordering holds by construction, not by trusting the daemon to re-deliver already-seen events.

The `ReplayCursor` (high-water) survives the connection churn (it lives on the coordinator, not the per-connection repo), so the new `hello` advertises the correct `last_event_id`; the buffered sequence is strictly above it and replays whole. **If end-to-end the replay does *not* arrive in production order, that is the buildability finding to surface — do NOT restart the daemon to force it (that trips #417's gap path this test must avoid).** Listed as a first-run assumption, consistent with every prior rung-4 scenario.

## Design

### Shape: the two-drop fence, with the sever/restore **straddling** the offline-window production

It forks the **shipped** `reconnect` scenario (`replySurvivesMidTurnReconnect`). The structural difference: #476 severs-then-immediately-restores in one atomic helper, and fences drop B on the phone's 2nd send (post-reconnect). **This slice splits the sever and restore so they straddle the event production**, and fences drop B on a **host-observable disconnect signal** that fires *during* the offline window — because a severed phone cannot send a 2nd `send_message` to trigger an enqueue fence (the ticket's central constraint).

Test method `interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect` (`SCENARIO=replay-order`):

1. `arriveInSeededThread()` (shared helper, verbatim) → land in the seeded `e2e-seed` channel thread, `Connected`.
2. `typeAndSend(SEND_PROMPT)` → **enqueue #1** → watcher drops **drop A** (`replay-order-open.jsonl`, a `thinking`-only line) → `turn_state(thinking)`, turn held open.
3. Wait for the thinking content-description (`cd_thread_thinking`) → **proves the turn is open and streaming at the moment we sever** (and is the synchronization fence: we do not sever until the turn is confirmed open). Nothing renderable has arrived yet (AC-critical — see "no partial text" below).
4. **`severLink()`** (new, half of the split primitive): `supervisor.close()` → await `coordinator.currentRepository.first { it == null }` (drop landed). Phone is now offline.
5. **Hold the offline window** for `OFFLINE_WINDOW_MS` (see "The offline window" below). During this window: the relay observes the phone leg vanish and logs it → the watcher, fenced on that **disconnect** token, drops **drop B** (`replay-order.jsonl`, ≥3 ordered `assistant_delta` lines + `end_turn`) → the producer emits the sequence into the daemon's in-ring buffer **while the phone is offline** (AC #1, by construction — drop B can only fire after the disconnect, which can only follow the sever).
6. **`restoreLink()`** (new, other half): `supervisor.connect()` → await `coordinator.currentRepository.first { it != null }` (fresh Noise pump reached `Open`). The new `hello` re-advertises `last_event_id` (read live off the surviving `replayCursor`) → the daemon replays the buffered sequence (all `event_id` > cursor) in ascending order → the fresh `RemoteConversationRepository` folds the deltas in arrival (= production) order.
7. `waitUntil` the full ordered assembled text is present, then assert **order** + **exactly-once** (below).

The 1st `send_message` opened the daemon turn; drop A holds it open (`thinking`, no `end_turn`) so the spinner is assertable and the turn is alive to receive drop B's continuation. Drop B's deltas share that turn's `turn_id`, so they fold into the one assistant message; the closing `end_turn` finalizes it. The sever/restore injects **no** `send_message`, so there is exactly **one** enqueue in this scenario (not two like `reconnect`) — the watcher fence for drop B is the disconnect, not enqueue #2.

### Splitting `severAndRestoreLink()` → `severLink()` + `restoreLink()`

#476's `severAndRestoreLink()` is atomic (`close()` → await-null → `connect()` → await-non-null) because continuity needs no gap. Ordering needs the gap. Introduce two private helpers, each fetching its singletons off Koin like `awaitConnected()` does (`GlobalContext.get().get<RelayConnectionSupervisor>()`, `…get<RelayRepositoryCoordinator>()`), each wrapped in `runBlocking { withTimeout(RECONNECT_TIMEOUT_MS) { … } }`:

- **`severLink()`** — contract: `supervisor.close()` then await `coordinator.currentRepository.first { it == null }`. `close()` cancels the supervision loop (no auto-redial racing), so the `null` phase is **stable** → the await is race-free.
- **`restoreLink()`** — contract: `supervisor.connect()` then await `coordinator.currentRepository.first { it != null }`. The fresh pump reaching `Open` proves the step-7 fold can receive replay.

**Recommended (optional) cleanup:** re-express #476's `severAndRestoreLink()` as `severLink(); restoreLink()` — identical behaviour, removes the duplication. This is the *only* edit that touches #476's shipped test; it is low-risk (byte-for-byte equivalent control flow) but **not required** — if it adds any doubt, leave `severAndRestoreLink()` untouched and accept the small duplication. Either way, do not change #476's `@Test` method body.

### The offline window (the one host-paced wait — why it is principled here)

Between `severLink()` and `restoreLink()` the test holds offline for a bounded `OFFLINE_WINDOW_MS`. This is **not** the harness's forbidden "fixed delay to catch a transient before it vanishes" anti-pattern. The distinction:

- **The buffered events are durable**, not transient. They sit in the daemon's in-ring buffer and replay whenever the phone returns. Erring **long** is therefore free — a slow host/emulator cannot lose them.
- **Creating an offline window inherently requires staying offline for a duration**, and the phone — being offline — has no causal signal from the host to fence on (the harness is one-directional: host watches `daemon.log`/`relay.log` and drops fixtures; there is no host→phone channel that survives the sever). So a bounded wait is unavoidable, and is the honest shape of "produce events while the phone is down."
- **The assertions are robust to the window length.** Order + exactly-once hold whether the deltas arrive as pure replay (window long enough — the intended path, AC #1) or as a replay/live mix (window too short — the producer still emitting when the phone returns). A too-short window only *under-exercises* AC #1's "entirely while offline"; it never false-greens (a reordering still breaks the ordered substring) and never false-reds. So the window is a determinism *quality* knob, tuned generously, not a correctness razor.

Suggested `OFFLINE_WINDOW_MS = 5_000L` — comfortably exceeds [relay disconnect-detect + watcher poll (0.5 s) + `fakeclaude` append + one producer tail cycle]. **This is the primary first-run tuning point** (below). Use a Compose-friendly wait that does not also pump the test clock unexpectedly; `runBlocking { delay(OFFLINE_WINDOW_MS) }` on the instrumentation thread is adequate (the app's own coroutines run on their real dispatchers).

### Why drop A carries no partial text (load-bearing, inherited from #476)

Drop A is `thinking`-only (no `assistant_delta`). On the sever the connection-scoped repo tears down (`currentRepository` → `null`) and the thread projection clears; the fresh repo on reconnect starts **empty**. If drop A had streamed partial text, that partial would live only on the torn-down repo and would be lost (events at or below `last_event_id` are not re-delivered by the gap-free path) — and the assembled order would be incomplete. By keeping drop A text-free and delivering the **whole** ordered sequence in drop B *after* the sever, "no missing segment, in order" holds by construction, independent of uncertain partial-turn replay semantics. This is the same choice that made #476 buildable; it is what keeps cross-reconnect ordering provable.

### Fixtures (2 new, same format as #454/#455/#476)

- `scripts/e2e-fixtures/replay-order-open.jsonl` — one `thinking`-only assistant line (held-open turn), shape-identical to `reconnect-open.jsonl`. Dedicated (not a `reconnect-open` reuse) so a future edit to the reconnect fixture cannot silently change this scenario.
- `scripts/e2e-fixtures/replay-order.jsonl` — the ordered sequence: **≥3** `assistant_delta` text lines, each with a **distinct** `message.id` (so the producer emits ≥3 envelopes with the same `turn_id` and ascending `seq`/`event_id`), the **last** carrying `stop_reason: "end_turn"`. The texts concatenate into a distinctive, unmistakably-ordered phrase whose segments do **not** substring-collide with anything else on screen (`e2e-seed`, `ping`, `Bash`, `streamed world`, `reconnected reply`). Mirrors `stream.jsonl`'s three-delta shape. Concrete example (developer may refine the words, keep the property):

  ```json
  {"type":"assistant","message":{"id":"order-1","content":[{"type":"text","text":"alpha "}]}}
  {"type":"assistant","message":{"id":"order-2","content":[{"type":"text","text":"bravo "}]}}
  {"type":"assistant","message":{"id":"order-3","stop_reason":"end_turn","content":[{"type":"text","text":"charlie"}]}}
  ```

  Assembled: `"alpha bravo charlie"`. The substring spans all three delta boundaries, so matching it requires the deltas to have assembled in production order; a reordered replay (`"bravo alpha charlie"`) fails the match.

### Script wiring (`e2e-emulator.sh`) — the new disconnect fence

Add a `replay-order)` arm to the SCENARIO `case` (beside `reconnect)`):

- `TEST_METHOD=interactiveTurn_seededChannel_missedEventsReplayInOrderAfterReconnect`
- `FIXTURE_FILE=${FIXTURES_DIR}/replay-order-open.jsonl` (drop A), `FIXTURE_FILE_2=${FIXTURES_DIR}/replay-order.jsonl` (drop B)
- Add it to the `case` error message and the usage / `SCENARIO` comment block (`ping | stream | spinner | tool | tool-failed | reconnect | replay-order`).

This scenario needs a **third watcher mode** (the existing enqueue-counting two-drop watcher fences drop B on enqueue #2, which never comes here). Gate it on a flag the `replay-order)` arm sets (e.g. `DROP_B_FENCE=disconnect`). Watcher contract (developer writes the bash; keep it a single backgrounded subshell so one `kill` reaps it on EXIT, like the existing watcher):

1. Wait for `send_message.enqueued` count ≥ 1 → `cp "${FIXTURE_FILE}" "${JSONL_TRIGGER}"` (drop A).
2. Capture a **baseline** count of the disconnect token, then wait until it **increases** → `cp "${FIXTURE_FILE_2}" "${JSONL_TRIGGER}"` (drop B). Counting against a baseline (not a bare `grep -q`) prevents a *stale* disconnect line from earlier connection churn from false-firing drop B before the phone actually severs.

**The disconnect token is the architect's call, and is the chief first-run unknown.** Best estimate: the **relay** terminates the phone's WebSocket, so `relay.log` (already captured at `${RELAY_LOG}`) is the most reliable place to observe a phone-leg drop — grep for the relay's client/session-disconnect line for the phone connection. `daemon.log` (the daemon's relay session noticing its client leg vanish) is the fallback. Pick one concrete token, wire it as an easily-overridable variable, and document it loudly as the line to confirm/adjust on first operator run (the relay/daemon source is in pyrycode, not verifiable from this repo).

## State + concurrency model

- No new app state, no new types, no ViewModel/Flow changes — this test drives **shipped** flows only.
- The test runs on the instrumentation thread; the two `currentRepository` awaits (`severLink`/`restoreLink`), the existing `awaitConnected`, and the offline-window `delay` use `runBlocking { withTimeout(…) { … } }` / `runBlocking { delay(…) }` (the established idiom at `:302-342`). Compose waits use `composeTestRule.waitUntil(timeout) { … }`.
- `close()`/`connect()` are `@Synchronized`/idempotent; `ProcessLifecycleOwner`'s `LifecycleConnectionDriver` fires only on lifecycle **edges** (none during a stable foreground instrumented run), so it does not race the explicit drive — listed as a first-run assumption.
- `StateFlow.first { predicate }` over `currentRepository`: race-free because `close()` makes the `null` phase stable (no auto-reconnect collapsing it before the `first` observes it).

## Error handling

Failure modes are **test failures** (timeouts), surfaced by the existing generous timeouts (`REPLY_TIMEOUT_MS = 90_000L`, `RECONNECT_TIMEOUT_MS = 30_000L`). There is no in-app error path to exercise: a clean, brief reconnect surfaces no `resync` (AC #4) — guaranteed structurally by the immediate test-triggered reconnect keeping the cursor in the bounded in-ring window, not by app code. `resync` is not cleanly UI-observable; the observable proxy is the exactly-once ordered render (a `resync`-driven full reload is a different code path) — confirm no `resync` is logged on first run.

## Testing strategy

- This **is** the test: one `@Test` method on `DeterministicInteractiveStreamE2ETest`, run as `…class=<class>#<method>` by `SCENARIO=replay-order`. Operator-run on a headless emulator + host daemon (not a CI gate — the org does not gate on Actions).
- Host-JVM verification before operator run (no device): the whole project still compiles incl. `./gradlew compileDebugAndroidTestKotlin` (per [[androidtest-not-compiled-by-mandatory-gates]] — `test`/`lint`/`assembleDebug` do **not** compile androidTest; run it explicitly), and both new fixtures are valid one-line-per-event JSON. `assertCountEquals` needs the explicit import `androidx.compose.ui.test.assertCountEquals` (already present from #476; it is an extension, unlike the member `onAllNodes`/`assertDoesNotExist`).
- **Tolerant assertions**, with the same one deliberate, scoped exception #476 established: the **order** check is `onAllNodesWithText(ORDERED_REPLY_SUBSTRING, substring = true)` non-empty (the ordered concatenation, e.g. `"alpha bravo charlie"`), and the **exactly-once** check is `.assertCountEquals(1)` on it. The ladder's "never assert on counts" rule targets **delta counts and streaming timing** (which vary harmlessly); a count of **1 on the final assembled, ordered reply text** is the load-bearing invariant — the whole point of the scenario — and is stable (any duplicate from replay/snapshot is a stable extra row, not a transient). Call this out in the method KDoc.
- What each assert proves, mapped to ACs (bullet form — developer writes the body in the project idiom):
  - *turn open at sever (sync fence)*: after send #1, `cd_thread_thinking` is displayed.
  - *drop landed*: `severLink()` observed `currentRepository == null` (the link truly dropped — not a no-op).
  - *events produced while offline* (AC #1): drop B is fenced on the disconnect token, which can only fire after the sever — so the sequence is produced entirely during the offline window, by construction. The `OFFLINE_WINDOW_MS` hold keeps the phone down until the producer has emitted (first-run tuning).
  - *reconnected*: `restoreLink()` observed `currentRepository != null` (fresh pump `Open`).
  - *replays in order* (AC #2): after restore, the ordered concatenation substring is present — a reordering breaks the match (explicit sequence, not mere presence).
  - *exactly once* (AC #3): `assertCountEquals(1)` on that substring — no segment lost, no row duplicated; the buffered-during-outage re-delivery folds idempotently (the path #476 did not reach).
  - *no resync* (AC #4): no separate assert (not cleanly UI-observable); the exactly-once ordered render is the proxy, and the brief in-window reconnect guarantees the gap-free path. Confirm no `resync` in `daemon.log` on first run.
  - *deterministic* (AC #5): scripted `fakeclaude`, fixed fixtures, no real claude; re-running back-to-back yields the same pass.

## Open questions / first-run assumptions (extend the doc's list)

- **Disconnect fence token (PRIMARY UNKNOWN).** Drop B fences on the relay/daemon logging the phone-leg drop. The exact token is unverifiable from this repo (relay/daemon source is in pyrycode). Wire a concrete best-guess token (grep `relay.log` for the phone-connection close; `daemon.log` as fallback) as an overridable variable, baseline-counted so a stale churn line cannot false-fire. **Confirm/adjust on first operator run** — if the token never appears, drop B never drops and the test times out at step 7.
- **Offline window length.** `OFFLINE_WINDOW_MS = 5_000L` must exceed [disconnect-detect + watcher poll 0.5 s + `fakeclaude` append + producer tail]. Erring long is free (durable buffer); erring short under-exercises "entirely while offline" but does not break the order/exactly-once verdict. Lengthen on first run if the replay arrives as a live mix rather than a clean post-reconnect batch.
- **Buffered turn continues + replays in production order after reconnect (fakeclaude).** After the phone re-attaches (new conn_id/Noise session), the relay routes the daemon's continued, buffered stream to the new connection in ascending `event_id`, and the fresh repo folds it in order. Shipped relay+daemon contract (#416/#647) but unverified end-to-end **with the whole sequence produced during the outage** — confirm on first run. **If the replayed order is wrong, that is the buildability finding to surface — do NOT restart the daemon to force it (that trips #417's gap path).**
- **Brief drop stays in the in-ring window → no `resync` (AC #4).** The reconnect is immediate (test-triggered), so `last_event_id` cannot age out of the daemon's bounded buffer → gap-free path runs, cursor never `reset()`. Confirm no `resync` is logged.
- **`ProcessLifecycleOwner` driver doesn't fight the explicit drive.** A stable foreground instrumented run emits no `onStart`/`onStop` edges. If an emulator focus blip fires one, prefer the real-transport-drop variant (`currentConnection.value?.close()` + `retry()`), which doesn't cancel the loop. (Same caveat as #476.)
- **Fixture-drop fence for drop A** unchanged from siblings (`send_message.enqueued`, count ≥ 1).

## Out of scope (follow-ups / future)

- Forcing the daemon to **re-deliver an already-seen event** (`event_id` ≤ advertised cursor) to exercise the high-water *drop* path directly: a correct daemon never does this on the gap-free path, so it cannot be triggered here without a daemon-side fault injection — not this slice's concern. This slice exercises the `message_id`/`turn_id` upsert fold on buffered+replayed deltas (the path #476 missed); the high-water *drop* remains defended-but-untriggered, same as #476.
- The auto-reconnect **backoff** state machine (#391/#392 own that) and a daemon-restart `resync`/gap path (#417) — both deliberately avoided here.
- A `make`/Gradle wrapper for the orchestration script — org-convention follow-up noted in the doc, not this slice.

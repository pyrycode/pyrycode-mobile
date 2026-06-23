# Spec #474 — Layer 1b: connection banner (connecting / reconnecting / offline) on the scripted-stream harness

**Ticket:** [#474](https://github.com/pyrycode/pyrycode-mobile/issues/474) · size **S** · no `security-sensitive` · no Figma.

Adds the **component-level** connection-banner coverage to the mobile e2e ladder: a scripted
`ConnectionState` sequence driven through the **real `ThreadViewModel.connectionState`** (the
`ConnectionStateSource.observe()` → `stateIn` flow, #307) → `ThreadScreen` → `ConnectionBanner` (#200/#201),
asserting the shipped banner affordances render for **connecting**, **reconnecting (with countdown)**, and
**offline**, and are **absent when connected**. It rides the **already-shipped #432 `ScriptedThreadHarness`**;
the only change is **one scripting method + lifting one field** in the harness (all `androidTest`). The banner's
text comes from a **separate connection-state source**, independent of the `FakeSessionPump` envelope stream
that drives the #472 tool rows / #432 deltas — so the harness extension here is on the connection seam, not the
pump.

**Zero production Kotlin changes.** The harness already injects `FakeConnectionStateSource`, whose
`emit(state)` test/preview seam (`FakeConnectionStateSource.kt:22-25`) already exists in `main/`. The ticket's
"a test affordance if the architect deems one necessary" resolves to: **no new production affordance is
necessary** — the harness only needs to hold a reference to the fake and forward `emit`.

## Design source

N/A — test ticket; asserts against the already-shipped `ConnectionBanner` (#200/#201, Figma-anchored when it
landed). No new visuals; the visual-fidelity check is intentionally not applicable. Matches #432 / #459 / #472.

## Why this is not `security-sensitive` (matches #432 / #472)

No new untrusted-input parse point is introduced. The connection-state source is a `main/` fake driven by
author-controlled test values; the banner is a pure `ConnectionState → Text` render. Exercising an
already-shipped, non-trust-bearing render path in a test is not a trust boundary. `security-sensitive` is
correctly omitted, so the § 3 security-review pass does not run.

## Files to read first

| Path (key lines) | What to extract |
| --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` (whole file, ~315 lines) | **The harness you extend.** Note the VM construction (`:72-80`) where `FakeConnectionStateSource()` is injected **inline** — you lift it to a field. Mirror the `push*` method shape (`:112-146`, each a thin one-liner) for the new `pushConnectionState`. `start()`/`awaitReady()` (`:86-107`, `:170-178`), `conversationId`, and `close()` are unchanged. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedToolRowTest.kt` (whole file, ~110 lines) | **The test idiom to copy** — the closest sibling (#472, same harness, same ladder rung): `createComposeRule`, `@Before start()` / `@After close()`, `private lateinit var harness`, `waitUntil { onAll…().fetchSemanticsNodes().isNotEmpty() }`, `TIMEOUT_MS = 5_000L`. Your banner cases swap content-description lookups for **plain-text** lookups (`onNodeWithText`). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionBanner.kt:32-52` | **The exact rendered strings the assertions key on** (the banner has no string resources — these are inline literals): `Connecting` → `"Connecting…"`; `Reconnecting(n)` → `"Reconnecting in ${n}s"`; `Offline` → `"Offline — tap to retry"`; `Connected` → **early `return`, nothing rendered**. Note the **non-ASCII glyphs**: `…` (U+2026 ellipsis) and `—` (U+2014 em-dash) — see § State + concurrency, Glyph hazard. |
| `app/src/main/java/de/pyryco/mobile/data/model/ConnectionState.kt` (whole file) | The sealed type you script: `Connected` / `Connecting` (data objects), `Reconnecting(secondsRemaining: Int)` (data class), `Offline` (data object). |
| `app/src/main/java/de/pyryco/mobile/data/repository/FakeConnectionStateSource.kt` (whole file) | `emit(state: ConnectionState)` is the `main/` test seam you forward to — it sets a retained `MutableStateFlow` (default `Connected`). **No production change needed.** |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:256-263` | `connectionState = source.observe().stateIn(WhileSubscribed(5000), initialValue = Connected)`. **Retained StateFlow** → robust to late subscription, unlike the `replay=0` `liveSessionEvents` (drives the no-new-readiness-gate decision below). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:97-98`, `:164` | `connectionState` / `onRetry` params and the single `ConnectionBanner(state = connectionState, onRetry = onRetry)` call site — already wired in the harness `start()`; nothing to change. |
| `docs/specs/architecture/472-tool-step-rows-component-e2e.md` (§ Design, § State + concurrency) | The immediately-prior sibling on this harness — the new-test-file / lower-merge-surface rationale and the "robust to late subscription" reasoning reused here. |
| `docs/e2e-interactive-stream.md` (Constraints, rung 2) | Tolerant-assert rule (presence/absence on rendered text; generous `waitUntil` timeouts; never on counts/timing). |

## Context

The mobile e2e ladder (`docs/e2e-interactive-stream.md`) Layer 1 covers the structured-stream render path at
the component level on the **#432 `ScriptedThreadHarness`**. #432 shipped the harness + text/spinner cases;
#459 added the interrupt affordance; #472 added the tool rows.

#435 (Layer 1b) was split 3-way: #472 (tool rows, **shipped**), #473 (divider, blockedBy the unshipped #336
fold), and **#474 (this — connection banner, was blockedBy #472)**. #472 has merged, so this is unblocked.

The connection banner is driven by a **separate connection-state source** on the ViewModel
(`ConnectionStateSource.observe()`), **not** by the scripted envelope stream that drives the tool-row / delta
cases. The #432 harness injects a `FakeConnectionStateSource` pinned to its `Connected` default with **no way
to drive it**. This ticket adds the one scripting seam to push `ConnectionState` values and asserts the banner
for each state.

The faithful production path the test reproduces:

```
test.pushConnectionState(state)
  → FakeConnectionStateSource.emit  (retained MutableStateFlow)
  → ThreadViewModel.connectionState  (observe().stateIn, #307)
  → ThreadScreen(connectionState = …)  → ConnectionBanner → rendered banner text (or nothing when Connected)
```

This path is **disjoint from** the `FakeSessionPump` envelope stream — confirming the banner reacts to its own
connection seam, which is exactly the regression class this rung guards.

## Design

### Harness change — one scripting method + a lifted field (all `androidTest`, zero production)

In `ScriptedThreadHarness.kt`:

1. **Lift `FakeConnectionStateSource` to a private field** so the harness can drive it. Today it is constructed
   inline at the VM call site (`connectionStateSource = FakeConnectionStateSource()`). Replace with a field
   (`private val connectionStateSource = FakeConnectionStateSource()`) and pass that field to the VM. Hold the
   **concrete `FakeConnectionStateSource`** type (not the `ConnectionStateSource` interface) — `emit` is on the
   concrete class, mirroring how the harness already holds the concrete `FakeSessionPump`.
2. **Add one scripting method**, grouped with the existing `push*` methods:
   - `fun pushConnectionState(state: ConnectionState) = connectionStateSource.emit(state)`
   - Add the `import de.pyryco.mobile.data.model.ConnectionState`.

No new builder, no envelope, no constructor/`start()` change — the connection-state seam is a direct method
call, simpler than the #472 envelope builders. ktlint's single-public-class rule is unaffected (no new
top-level type).

### New test file — `ScriptedConnectionBannerTest.kt` (same package/dir)

A dedicated per-concern test class (like #472's `ScriptedToolRowTest.kt`), **not** additions to
`ScriptedThreadRenderTest.kt`. Rationale: keeps this ticket's diff isolated from sibling **#473** (divider),
which rides the same harness next and would otherwise collide in a shared test file. The harness file is the one
unavoidable shared edit point; the per-ticket test files keep merge surface minimal. Structure copies
`ScriptedToolRowTest.kt` verbatim in shape: `@get:Rule createComposeRule()`, `private lateinit var harness`,
`@Before { harness = ScriptedThreadHarness(composeRule); harness.start() }`, `@After { harness.close() }`,
`TIMEOUT_MS = 5_000L`.

Assert on the **rendered banner text** via `onNodeWithText(substring = true)` — no production test tags or
content-descriptions (the ticket's explicit preference; the banner renders plain `Text`).

### Test scenarios (one `@Test` per state — maximum isolation, no conflation risk)

Each `@Test` gets a fresh harness (`@Before`), so the `Connected` default is the clean starting point and there
is no cross-test bleed. Assert **tolerantly** (presence/absence of the distinctive rendered string, generous
`waitUntil`), never on timing.

- **`connecting_showsConnectingAffordance`** (AC #1): `pushConnectionState(ConnectionState.Connecting)`;
  `waitUntil` an `onAllNodesWithText("Connecting", substring = true)` set is non-empty; assert displayed.
- **`reconnecting_showsCountdownAffordance`** (AC #2): `pushConnectionState(ConnectionState.Reconnecting(secondsRemaining = 12))`;
  `waitUntil` then assert `onNodeWithText("Reconnecting in 12s", substring = true).assertIsDisplayed()`. The full
  literal (all-ASCII) **proves the countdown value `12` is surfaced**, which AC #2 explicitly requires — assert
  the whole `"Reconnecting in 12s"`, not just `"Reconnecting"`.
- **`offline_showsTapToRetryAffordance`** (AC #3): `pushConnectionState(ConnectionState.Offline)`; `waitUntil`
  then assert `onNodeWithText("tap to retry", substring = true).assertIsDisplayed()` (all-ASCII; proves the
  retry affordance and dodges the `—` em-dash — see Glyph hazard).
- **`connected_hidesBannerAfterDisconnect`** (AC #4): use a **present→absent fence** (mirrors the spinner
  test's shape) so the assertion is not a trivially-absent false-green:
  1. `pushConnectionState(ConnectionState.Connecting)`; `waitUntil` `"Connecting"` present.
  2. `pushConnectionState(ConnectionState.Connected)`; `waitUntil` the `"Connecting"` node set is now **empty**;
     then assert `onNodeWithText("Connecting", substring = true).assertDoesNotExist()` (the banner removed itself
     on reconnect — `ConnectionBanner` early-returns for `Connected`). This proves the banner *appears then
     disappears*, which is stronger than asserting absence from the never-shown default.

AC #5 (drives the harness's connection-state source, extended to be scriptable; passes the existing androidTest
gates) is satisfied structurally by the harness change + the cases running under `connectedAndroidTest`.

## State + concurrency model

Unchanged from #432 — instrumented `createComposeRule`, real Android main looper, no `Dispatchers.setMain`, no
virtual clock. All synchronization via `composeRule.waitUntil` / `waitForIdle`.

- **Connection state is robust to the subscribe-before-push race** (the #432 `liveSessionEvents` flake source
  does **not** apply here). `connectionState` flows through `FakeConnectionStateSource.observe()` — a retained
  `MutableStateFlow` — into `stateIn(WhileSubscribed(5000), initialValue = Connected)`. A value `emit`-ted
  before the VM's collector subscribes is **retained and re-emitted** to the new subscriber (StateFlow
  semantics), exactly like the #472 tool rows' `messagesByConversation` projection and unlike the `replay = 0`
  `liveSessionEvents`. **No new readiness gate is needed.** Still keep every `pushConnectionState` **after**
  `start()` (which runs `awaitReady()`): `awaitReady` proves the screen composed and is collecting
  `connectionState`, so the thread is rendering (not loading/empty) before the first banner assertion.
- **StateFlow conflation is a non-issue here** because each `@Test` drives exactly one transition before
  asserting (and the AC #4 fence waits for `"Connecting"` to render before pushing `Connected`). Never push two
  states back-to-back without a `waitUntil` between them, or conflation may skip the intermediate value.
- **Glyph hazard (the one real gotcha).** `ConnectionBanner` uses inline non-ASCII glyphs: `"Connecting…"`
  (U+2026 ellipsis) and `"Offline — tap to retry"` (U+2014 em-dash). Assert on **ASCII-only substrings that sit
  before the glyph** — `"Connecting"`, `"Reconnecting in 12s"`, `"tap to retry"` — so the match never depends on
  reproducing the exact glyph. (Same class of pitfall as #431's `ping`/`seed` substring lesson — assert on
  unambiguous, faithfully-typeable text.) Within a single-state test only one banner is on screen, so the
  capital-`C` `"Connecting"` substring cannot collide with `Reconnecting`'s lowercase `connecting`; no
  case-sensitivity reasoning is load-bearing.
- **Cleanup.** `@After { harness.close() }` cancels the scope, as in `ScriptedToolRowTest`.

## Error handling

Not a product surface — additive test infrastructure, no failure modes to surface to a user. The only
"failures" are flaky/timed-out assertions; the mitigations are the tolerant-assert rule, the ASCII-substring
glyph guard, and pushing after `start()`. No `try`/`catch`, no result types. `onRetry` is wired to `{}` in the
harness `start()` (the banner's tap target); this rung asserts the offline affordance is **shown**, not that a
tap fires retry — the retry-tap wiring is the VM's concern (`ThreadViewModel:432`), already covered elsewhere
and out of scope here.

## Testing strategy

- **The deliverable is the tests.** `ScriptedConnectionBannerTest` runs under `./gradlew connectedAndroidTest`
  (device/emulator required), alongside `ScriptedToolRowTest` / `ScriptedThreadRenderTest`.
- **No new production code → no production unit tests.** Verify locally (no device): run
  `./gradlew compileDebugAndroidTestKotlin` **explicitly** — `test` / `lint` / `assembleDebug` skip `androidTest`
  (the `androidtest-not-compiled-by-mandatory-gates` lesson). `onNodeWithText` / `onAllNodesWithText` /
  `assertIsDisplayed` / `assertDoesNotExist` are `ComposeTestRule` members (the imports already in
  `ScriptedThreadRenderTest` / `ScriptedToolRowTest` cover them). Also run `./gradlew test lint spotlessCheck` to
  confirm nothing else moved (they cannot regress without a production change, but confirm).
- **Fakes vs real:** real `ThreadViewModel` + real `ThreadScreen` + real `ConnectionBanner` (the integration
  point under test); `FakeConnectionStateSource` (already in the harness, now driven via the new method) the
  only seam; the `FakeSessionPump` / `RemoteConversationRepository` graph is present but **inert for this
  ticket** (no envelopes pushed — the banner path is disjoint from the pump).
- Assertions check only the **distinctive rendered banner substring** per state (and the `12s` countdown value
  for reconnecting) — never timing, never the pump stream, per the ladder doc's tolerant rule.

## Open questions

1. **Seconds-remaining literal.** The spec uses `12` for the reconnecting countdown — any positive int works;
   the assertion must match whatever the test scripts (`"Reconnecting in ${n}s"`). Keep them in sync.
2. **One-test-per-state vs a single walk-through.** This spec recommends four `@Test`s for isolation and
   readability. A single test walking `Connecting → Reconnecting → Offline → Connected` with a `waitUntil`
   between each push is defensible (and exercises live recomposition across states), but the per-state files
   read more cleanly and isolate failures. If the developer prefers the walk-through, keep a `waitUntil` between
   every push (conflation guard above). Flag the choice in the PR.

# Spec #472 — Layer 1b: tool-step rows (running → done + failed) on the scripted-stream harness

**Ticket:** [#472](https://github.com/pyrycode/pyrycode-mobile/issues/472) · size **S** · no `security-sensitive` · no Figma.

Adds the **component-level** tool-step coverage to the mobile e2e ladder: a scripted `tool_use` /
`tool_result` stream driven through the **real `RemoteConversationRepository` fold** (#387 correlation)
→ `ThreadViewModel` → `ThreadScreen`, asserting the shipped tool-row states (#388) render
**running → done** and **failed**. It rides the **already-shipped #432 `ScriptedThreadHarness`**; the
only production-adjacent change is two new scripting methods + two envelope builders **inside the
harness file** (all `androidTest`). **Zero production Kotlin changes.**

This is the Layer-1 component twin of **#455** (Layer-2c, the emulator-level tool-step scenarios,
already shipped). #455 drove the same render path over a real emulator + Noise/relay + `fakeclaude`;
this ticket exercises it on the in-process harness with no device backend or network. The test design
mirrors #455's tolerant assertions and absence-triad "done" check, but the transient-observation fence
is **simpler here** (see § Design — the running→done fence) because the harness pushes envelopes
imperatively from the test thread instead of through `send_message`.

## Why no `## Design source` (no Figma)

This is a test-harness ticket. It asserts against the **already Figma-anchored** tool rows
(node `16-28`, shipped + reviewed via #388); it introduces no new visuals, so there is no Figma node to
echo and the visual-fidelity check is intentionally not applicable. The running-spinner / failed-glyph
affordances are design-owed in `ToolCallRow.kt` (Material 3 idiom until the frame lands) — that is
pre-existing and out of scope here. Matches #431 / #432 / #454 / #455.

```markdown
## Design source

N/A — test ticket; asserts against the already-shipped, Figma-anchored (16-28, via #388) tool rows. No new visuals.
```

## Why this is not `security-sensitive` (matches #432 / #455)

No new untrusted-input parse point is introduced. The decode substrate (#385) and the
`tool_use`↔`tool_result` correlation fold (#387) were the trust boundary when introduced and were
security-reviewed then. The scripted envelopes are author-controlled test inputs; exercising an
already-reviewed fold in a test is not a new trust boundary. `security-sensitive` is correctly omitted,
so the § 3 security-review pass does not run.

## Files to read first

| Path (key lines) | What to extract |
| --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` (whole file, ~260 lines) | **The harness you extend.** Note the `push*` method shape (`:112-126`, each a thin `pump.push(builder(...))`), the private envelope builders (`:201-249`), the `FakeSessionPump` (`:189-199`), `conversationId`/`TS`/`CAPABILITY_INTERACTIVE` already in scope, and `awaitReady()` (`:150-158`). Your two new methods + two builders slot in alongside these. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt` (whole file, ~137 lines) | The test idiom to copy: `createComposeRule`, `@Before start()` / `@After close()`, `onAllNodesWithContentDescription(cd).fetchSemanticsNodes().isNotEmpty()` inside `waitUntil`, reading a CD from a string resource via `InstrumentationRegistry…getString` (`:34-44`), the spinner test's present→absent shape (`:82-101`) — **the template for running→done**. |
| `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt:3859-3893` | `toolUseEnvelope` / `toolResultEnvelope` builders — **port these two verbatim** into the harness file (drop the `id` param; the harness builders use the fixed `id = 1L` like its existing ones). They depend only on `main/` `Envelope`/`MobileJson`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:135-163` | `ToolCallStatusIcon`: the three states' rendered affordances + CDs your assertions key on. **Running** = `CircularProgressIndicator`, CD `cd_tool_running`. **Failed** = `ErrorOutline` icon, CD `cd_tool_failed`. **Done** = per-tool icon, `contentDescription = null` (no positive CD → assert indirectly). The collapsed header (`:110-126`, `buildSummaryAnnotated` `:206-219`) shows `toolName · input`, so the **tool name renders verbatim even in the running state**. |
| `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:644-714` | `applyToolUse` / `applyToolResult` — the #387 fold the test exercises. Key facts that drive the scripts: row keyed by `tool_use_id`; **`tool_result` with no prior `tool_use` is dropped** (use must precede result); `is_error == true` → `Failed`, else `Done`; `name` carried **verbatim**. The fold writes the row into `messagesByConversation` (a retained `StateFlow` projection), **not** the `replay = 0` `liveSessionEvents` — so tool rows are robust to late subscription (see § State + concurrency). |
| `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:55-73` | `ToolUse(conversationId, turnId, toolUseId, name, inputSummary)` / `ToolResult(conversationId, turnId, toolUseId, isError, resultSummary)` — the field names the envelope JSON keys map to. |
| `app/src/main/res/values/strings.xml:63-64` | `cd_tool_running` ("Tool call running") / `cd_tool_failed` ("Tool call failed") — source of truth for the CD strings the test reads. |
| `docs/specs/architecture/455-tool-step-e2e.md` (§ Design, § Done assertion) | The emulator twin's running→done + failed design and the absence-triad "done" formulation. Reuse the assertion *shape*; ignore the fixture/host-script machinery (emulator-only). |
| `docs/e2e-interactive-stream.md` (Constraints, rung 2) | Tolerant-assert rule (presence/absence + verbatim tool name; generous `waitUntil` timeouts; never on counts/timing). |

## Context

The mobile e2e ladder (`docs/e2e-interactive-stream.md`) Layer 1 covers the structured-stream render
path at the component level on the **#432 `ScriptedThreadHarness`** — the harness drives a scripted
sequence of structured envelopes through the real `RemoteConversationRepository` fold + `ThreadViewModel`
and renders the assembled `ThreadScreen`, with no daemon/network/emulator-host. #432 shipped the harness
+ the text and spinner cases; #459 added the interrupt-affordance case on top.

#435 (Layer 1b: tool rows, session divider, connection banner) was split 3-way: **#472 (this — tool
rows)**, #473 (divider, blockedBy the unshipped #336 fold), #474 (banner, blockedBy #472). This ticket
is the first child and is unblocked: the tool-row render path it exercises is **already shipped and
reviewed** — `tool_use` → running row, `tool_result` → done, error `tool_result` → failed (#387
correlation fold, #388 tool-row status UI). This ticket **exercises** that path through the harness; it
does not modify it.

The faithful production path the test reproduces (identical to #432's, with the tool fold arm
exercised):

```
scripted tool_use/tool_result Envelope
  → FakeSessionPump.inbound → RemoteConversationRepository
      (decode #385 + correlation fold #387 → messagesByConversation → observeMessages)
  → ThreadViewModel (state = combine(…, threadItems, …))
  → ThreadScreen → MessageBubble routes Role.Tool → ToolCallRow → rendered status icon + name
```

## Design

### No new harness mechanism — only scripting affordances + one test file

Two changes, both `androidTest`, zero production:

1. **`ScriptedThreadHarness.kt` — add two scripting methods + two private envelope builders.** Mirror
   the existing `pushAssistantDelta` / `pushTurnState` exactly (each a thin `pump.push(builder(...))`).
   The builders are ported verbatim from `RemoteConversationRepositoryTest` (drop the `id` param;
   reuse the harness's `TS` constant and fixed `id = 1L`):

   - `fun pushToolUse(turnId: String, toolUseId: String, name: String, inputSummary: String)`
     → `pump.push(toolUseEnvelope(conversationId, turnId, toolUseId, name, inputSummary))`
   - `fun pushToolResult(turnId: String, toolUseId: String, isError: Boolean, resultSummary: String)`
     → `pump.push(toolResultEnvelope(conversationId, turnId, toolUseId, isError, resultSummary))`
   - private `toolUseEnvelope(conversationId, turnId, toolUseId, name, inputSummary): Envelope` —
     `type = "tool_use"`, payload `{"conversation_id":…,"turn_id":…,"tool_use_id":…,"name":…,"input_summary":…}`.
   - private `toolResultEnvelope(conversationId, turnId, toolUseId, isError, resultSummary): Envelope` —
     `type = "tool_result"`, payload `{…,"tool_use_id":…,"is_error":<bool>,"result_summary":…}`.

   Keep them grouped with the existing `push*` / builders. The new public methods on `ScriptedThreadHarness`
   are fine for ktlint's single-public-class rule (the class is the file's sole public top-level type;
   the builders stay private top-level — see the `ktlint-filename-rule-single-class` lesson). **No third
   file, no harness constructor/wiring change** — `ThreadScreen` already routes `Role.Tool` →
   `ToolCallRow`, so the existing `start()` render surface covers tool rows unchanged.

2. **New test file `ScriptedToolRowTest.kt`** (same package/dir as `ScriptedThreadRenderTest.kt`),
   two `@Test` methods (below). **Placement rationale:** a dedicated per-concern test class keeps the
   tool-row scenarios cohesive and **decouples this ticket's diff from sibling #474** (banner), which
   rides the same harness next and would otherwise collide in `ScriptedThreadRenderTest.kt`. The harness
   file is the one unavoidable shared edit point, and the #472→#474→… blocking chain serializes those
   edits. (Adding the methods to `ScriptedThreadRenderTest.kt` instead is defensible — it's where #432/#459
   put their cases — but the separate file is the lower-merge-surface choice given the sibling fan-out.)

### The running→done fence — imperative push, not a two-drop dance (AC #1, #2, #4)

AC #4 requires the **running** state be asserted **while the turn is in flight** — a single fixture
racing straight to the terminal result must not false-green the running assertion. In #455 (emulator)
this needed a two-drop causal fence keyed on the **2nd `send_message.enqueued`**, because the test
could only inject indirectly by sending messages. **Here the fence is trivial and deterministic:** the
harness pushes envelopes imperatively from the test thread, so the test simply:

1. `pushToolUse(...)` — the fold opens a `Running` `Role.Tool` row, which **persists** (it flips only on
   a correlated `tool_result`).
2. `waitUntil { cd_tool_running node set is non-empty }`, then assert it `isDisplayed()` **and** the
   tool name is shown. **This is the in-flight running assertion** (AC #1 + AC #4) — the result has not
   been pushed yet, so there is no race to done.
3. **Only after** the running assertion passes, `pushToolResult(isError = false)` — the fold flips the
   same row to `Done`.
4. `waitUntil { cd_tool_running node set is now empty }`, then assert the done-triad (below).

The sequential push ordering **is** the causal fence; `waitUntil` between the two pushes guarantees the
running window was observed. No timing dependency, no `send_message` indirection. This mirrors the
spinner test's present→absent shape (`ScriptedThreadRenderTest.kt:82-101`) — `pushTurnState("thinking")`
→ assert present → `pushTurnEnd` → assert absent — applied to `pushToolUse`/`pushToolResult`.

**Correlation is load-bearing:** the `tool_use`'s `toolUseId` MUST equal the `tool_result`'s `toolUseId`
(use the same literal in both calls) or the fold drops the result (`applyToolResult` no-ops on no match)
and the row never resolves → `waitUntil` timeout.

### The "done" assertion is indirect — the absence triad (AC #2)

`ToolCallStatus.Done`'s icon has `contentDescription = null` (`ToolCallRow.kt:148-154`) — there is **no
positive CD for done**. So "resolved to done" is asserted tolerantly as the triad #455 used:

- the **running** CD that was present is now **absent** (`onAllNodesWithContentDescription(cd_tool_running)…isEmpty()`),
- the **failed** CD `assertDoesNotExist()` (it resolved to done, not failed),
- the **tool name is still displayed** (the row resolved in place, did not vanish).

That triad uniquely identifies a `Running → Done` resolution and never keys on timing. Do **not** add a
test tag or a new CD to `ToolCallRow` (out of scope — no production change).

### Failed needs no fence — one terminal sequence (AC #3)

The `Failed` end state is stable (it does not auto-resolve), so it needs no held-open fence. The test
pushes `tool_use` then an error `tool_result` (`isError = true`) back-to-back, then asserts only the
terminal `Failed` CD is present + the tool name shown. The `tool_use` must still precede the
`tool_result` (correlation), and use a **distinct `toolUseId`** from the running→done test (clean
per-test isolation — each `@Test` builds a fresh harness via `@Before`, so cross-test bleed is
impossible, but distinct ids keep the two scripts independently readable).

### Test scenarios (`ScriptedToolRowTest.kt`)

`@get:Rule val composeRule = createComposeRule()`; read `cd_tool_running` / `cd_tool_failed` as instance
fields via `InstrumentationRegistry…getString(R.string.…)` exactly as `ScriptedThreadRenderTest` reads
`thinkingDescription` (`:34-44`). `@Before` builds + `start()`s the harness; `@After` `close()`s it.
Use a tool name that does **not** collide with the seed channel name `"Harness channel"` — `"Bash"` is
safe (and is what the repo unit tests + #455 use). Assert **tolerantly** (presence/absence + verbatim
name; generous `waitUntil` timeout, reuse the file's `TIMEOUT_MS = 5_000L`).

**`toolStep_runningWhileInFlight_thenDoneOnResult`** (AC #1, #2, #4):
- `harness.pushToolUse(turnId = "t1", toolUseId = "tu1", name = "Bash", inputSummary = "ls -la")`.
- `waitUntil` the `cd_tool_running` node set is non-empty; assert its first node `isDisplayed()` **and**
  `onNodeWithText("Bash", substring = true).assertIsDisplayed()` (tool name verbatim, in flight).
- `harness.pushToolResult(turnId = "t1", toolUseId = "tu1", isError = false, resultSummary = "files")`.
- `waitUntil` the `cd_tool_running` node set is now empty; then the done-triad: `cd_tool_running`
  absent (just waited on), `onAllNodesWithContentDescription(cd_tool_failed)…isEmpty()` /
  `onNodeWithContentDescription(cd_tool_failed).assertDoesNotExist()`, and
  `onNodeWithText("Bash", substring = true).assertIsDisplayed()` (row resolved in place).

**`toolStep_errorResult_rendersFailed`** (AC #3):
- `harness.pushToolUse(turnId = "t1", toolUseId = "tuf", name = "Bash", inputSummary = "false")`.
- `harness.pushToolResult(turnId = "t1", toolUseId = "tuf", isError = true, resultSummary = "exit 1")`.
- `waitUntil` the `cd_tool_failed` node set is non-empty; assert its first node `isDisplayed()` **and**
  `onNodeWithText("Bash", substring = true).assertIsDisplayed()`.

Two `@Test` methods cover all five ACs (AC #5 — rides the #432 harness, no production change, passes the
androidTest gates — is satisfied structurally by the ticket's shape). A third "running-only" test is
unnecessary: the running assertion lives inside `toolStep_runningWhileInFlight_thenDoneOnResult` before
the result is pushed, which **is** the AC #1 + AC #4 coverage.

## State + concurrency model

Unchanged from #432 — instrumented `createComposeRule`, real Android main looper, no `Dispatchers.setMain`,
no virtual clock. All synchronization via `composeRule.waitUntil` / `waitForIdle`.

- **Tool rows are robust to the subscribe-before-push race** (the #432 flake source). Unlike
  `turn_state`/`isThinking` (which flow through the `replay = 0` `liveSessionEvents` SharedFlow), the
  tool fold writes into `messagesByConversation` — a `MutableStateFlow` projection that **retains** and
  re-emits to late subscribers — and `FakeSessionPump`'s inbound `Channel` is `UNLIMITED` (a push before
  the collector subscribes is buffered, not dropped). So a tool row pushed at any time after construction
  survives. Nonetheless, **keep all `push*` calls after `start()`** (which runs `awaitReady()`), matching
  the existing cases: `awaitReady` proves the screen composed + the conversation seeded, so the thread is
  rendering (not in a loading/empty state) before the first assertion. No new readiness gate is needed.
- **Conversation-id match.** Every scripted envelope's `conversation_id` must equal the harness
  `conversationId` (`applyToolUse`/`applyToolResult` filter on it). The harness threads its own id into
  the builders (the `push*` methods take only `turnId`/`toolUseId`/…); the test never passes a conv id.
- **Cleanup.** `@After { harness.close() }` cancels the scope (inbound collector + DataStore scope), as
  in `ScriptedThreadRenderTest`.

## Error handling

Not a product surface — additive test infrastructure, no failure modes to surface to a user. The only
"failures" are flaky/timed-out assertions; the mitigations are the tolerant-assert rule and the
imperative push fence above. The dominant risk is a **mis-correlated script** (a `toolUseId` mismatch
between the use and the result) — the fold silently drops the result and the row never resolves →
`waitUntil` timeout. The same-literal-`toolUseId` requirement prevents it. No `try`/`catch`, no result
types.

## Testing strategy

- **The deliverable is the tests.** `ScriptedToolRowTest` runs under `./gradlew connectedAndroidTest`
  (device/emulator required), alongside `ScriptedThreadRenderTest` / `ThinkingIndicatorTest`.
- **No new production code → no production unit tests.** Verify locally (no device): `./gradlew
  compileDebugAndroidTestKotlin` compiles the new test + harness methods — **run this explicitly**;
  `test` / `lint` / `assembleDebug` skip `androidTest` (the `androidtest-not-compiled-by-mandatory-gates`
  lesson). `onAllNodesWithContentDescription` / `onNodeWithContentDescription` / `onNodeWithText` /
  `assertDoesNotExist` / `assertIsDisplayed` are members (the imports already in `ScriptedThreadRenderTest`
  cover them). Also run `./gradlew test lint spotlessCheck` to confirm nothing else moved (they cannot
  regress without a production change, but confirm).
- **Fakes vs real:** real `RemoteConversationRepository` + real `ThreadViewModel` + real `ThreadScreen` +
  real `ToolCallRow` (the integration point under test); `FakeSessionPump` (already in the harness) the
  only seam; real `AppPreferences`/`DataStore`; `FakeConnectionStateSource` (from `main/`).
- Assertions intentionally do **not** check `input_summary` / `result_summary` text (server-derived,
  may be summarized) — only status CDs + the verbatim tool name, per the ladder doc's tolerant rule.

## Open questions

1. **Tool name substring fidelity.** `"Bash"` renders inside the collapsed header's
   `buildSummaryAnnotated` (`toolName · input`) as plain text → `onNodeWithText("Bash", substring = true)`
   matches and does not collide with the seed name `"Harness channel"`. If a future case needs a tool
   name that is a substring of other on-screen text, switch to an exact match — out of scope here.
2. **Test-file placement.** This spec recommends a new `ScriptedToolRowTest.kt` (lower merge surface vs
   sibling #474). If the developer finds the harness's two new methods read more naturally validated
   beside the existing cases, adding the two `@Test`s to `ScriptedThreadRenderTest.kt` is acceptable —
   the harness change is identical either way. Flag the choice in the PR.

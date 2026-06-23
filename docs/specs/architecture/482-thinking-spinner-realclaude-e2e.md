# #482 — Layer 3 (real claude): thinking spinner during a real turn

Adds **one** real-claude emulator e2e scenario on the **shipped #421 harness**
(`InteractiveStreamE2ETest`, rung 3 / **Layer 3** of the mobile interactive-stream e2e ladder): a
constrained prompt makes **real claude think for a beat**, and the test asserts the **thinking spinner is
displayed while the turn is active**. It is the **real-claude twin** of #454 (rung 4 / Layer 2a, the
deterministic scripted spinner coverage) and #432 (Layer 1a, the in-process component twin) — the
`turn_state` → `isThinking` spinner render path is now exercised at **all three ladder layers**.

The render path it exercises — `turn_state(thinking)` → `ThreadViewModel.isThinking` → `ThinkingIndicator`
(#406) — is **already shipped and reviewed**. This ticket *exercises* it; it does **not** modify it. No
production Kotlin, no new files, no new types: one additive `@Test` method + one private helper val + one
companion constant in the existing test, plus a coverage/assumptions update in the developer-owned
`docs/e2e-interactive-stream.md`.

This is the **flakiest** scenario on the ladder and the spec leans into that: it lands **`@Ignore`-gated by
default** (a documented manual case, per AC #3), because rung 3 has **no lever to hold the turn open** and
the spinner — unlike #481's verbatim `"Bash"` tool name — leaves **no durable artifact** once the turn
moves on. See [The central design decision](#the-central-design-decision).

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` (whole file, ~240 lines) —
  **the harness this rides.** Lift the connect → create-discussion → type → send flow verbatim:
  `waitUntil(LIST_TIMEOUT_MS)` the FAB (`:54`) → `awaitConnected()` (`:60`, helper at `:197-205`) → tap FAB
  → `waitUntil(THREAD_TIMEOUT_MS)` the send button (`:65`) → `performTextInput` + tap send (`:70-71`). The
  companion block (`:207-239`) is where the new constant goes; #481's tool-use `@Test` (`:131-160`) is the
  closest in-file precedent for an additive scenario. **Do not disturb** the shipped ping `@Test`, the
  tool-use `@Test`, or their `@Ignore`d negative controls.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt:78-176` — **the
  closest twin; lift two things from it.** (1) The `thinkingDescription` private val (`:84-88`): reads
  `R.string.cd_thread_thinking` via `InstrumentationRegistry.getInstrumentation().targetContext.getString(…)`
  — copy this read pattern exactly. (2) The deterministic `spinner` presence-assert idiom (`:158-176`):
  `waitUntil { onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty() }`
  then `.onFirst().assertIsDisplayed()`. **Take the presence half (`:162-167`); drop the two-drop
  absence half (`:169-175`)** — that absence assertion depends on the scripted two-drop fence, which does
  not exist at rung 3 (see Design).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt:40-68` — the spinner
  affordance. The `cd_thread_thinking` content-description is merged onto the `Row`
  (`semantics(mergeDescendants = true)`, `:54`); the composable **early-returns when `!isThinking`** (`:45`),
  i.e. the node exists **only while the turn is in its thinking phase**. This is what makes the assertion
  transient and is the whole reason for the `@Ignore` default.
- `app/src/main/res/values/strings.xml:54-55` — `thread_thinking_label` = `"Thinking…"`,
  `cd_thread_thinking` = `"Agent is thinking"`. The assertion keys on the **content-description**, not the
  label. Keep the constant comment in sync with these.
- `docs/knowledge/codebase/481.md` — the sibling slice's design rationale (durable-vs-transient signal; why
  rung 3 cannot hold a turn open; the `@Ignore` negative-control precedent). The single most useful read for
  *why* this spec defaults to `@Ignore`.
- `docs/e2e-interactive-stream.md` — the developer-owned harness doc. Read **"What rung 3 is made of"**
  (`:76-92`, the lines the developer extends), the deterministic **`spinner`** scenario contrast
  (`:249-262`), the **Assumptions** list (`:381+`, where the new first-run-unknown entry goes), and the
  **Constraints** (`:500-504`, the "never on timing" rule this scenario must honour).

## Context

**Problem.** The mobile e2e ladder proves the live interactive-stream render path at three layers. The
thinking spinner (`turn_state(thinking)` → `isThinking` → `ThinkingIndicator`) is covered deterministically
at rung 4 (#454, scripted) and as an in-process component at rung 1 (#432). The **screen-sourced** nature of
the signal — pyrycode's daemon derives `turn_state` by watching real claude's live TUI screen — means the
**only** layer that proves the matching pattern still holds against the *actual* screen text is rung 3, real
claude over the real Noise/relay path. The scripted backend emits a fixed `turn_state` and is structurally
**blind** to claude changing its own screen. This ticket adds that rung-3 scenario.

**Why now.** Blocker #481 (the sibling tool-use slice) shipped (PR #483, commit `4bfab52`), clearing the
same-file `@Test` serialization. Its merged `@Test` + `docs/knowledge/codebase/481.md` are this spec's
starting point — the connect/create/send flow, the tolerant `waitUntil` idiom, and the `@Ignore`d-manual
precedent are all already in the file.

## Design source

N/A — test-harness ticket exercising already-shipped, already-reviewed, already-Figma-anchored UI (the
thinking spinner, thread frame `16-8`). No new visuals; the visual-fidelity check is intentionally skipped.

## The central design decision

**Assert the transient spinner's *presence mid-turn*, tolerantly, and ship `@Ignore`-gated by default —
there is no durable signal and no fence to make it reliable.**

This is where Layer 3 diverges from both twins. Its two twins each have a lever to hold the turn open so the
transient `thinking` state is observable in flight:

- **Rung 4 (#454, scripted)** uses a **two-drop causal fence**: drop A is a `thinking`-only fixture that
  leaves the turn open with `isThinking == true` *indefinitely*; the test asserts presence; a 2nd message
  then fires drop B (end-of-turn), clearing it. The thinking window is arbitrarily long → presence *and*
  absence are both deterministically assertable.
- **Rung 1 (#432, component)** drives `pushTurnState` imperatively in-process — it can pause between
  `thinking` and the resolution.

**Neither lever exists at rung 3.** There is no scripted backend to hold the turn, and the test cannot
imperatively pause real claude between `turn_state(thinking)` and the first `assistant_delta`. The moment
claude emits its first token the daemon flips `turn_state` to `responding`, `isThinking` goes false, and
`ThinkingIndicator` early-returns — **the node disappears with no trace.** #481 solved its analogous problem
by keying on a **durable** terminal signal (the verbatim `"Bash"` that survives turn-end in the resolved
tool row). **The thinking spinner has no durable equivalent**: once the turn moves on, nothing of it remains
on screen. `docs/e2e-interactive-stream.md` already flags racing the spinner away as exactly this risk, and
`codebase/481.md` records it as established prior evidence.

Therefore:

1. **Presence-only, mid-turn.** The single load-bearing assertion catches the spinner content-description
   while the turn is in its thinking phase, using the same tolerant idiom as the twins:
   `waitUntil(REPLY_TIMEOUT_MS) { onAllNodes(hasContentDescription(thinkingDescription)).fetchSemanticsNodes().isNotEmpty() }`
   then `onAllNodes(hasContentDescription(thinkingDescription)).onFirst().assertIsDisplayed()`. Tolerant,
   never on counts or timing (AC #1, AC #2).
2. **No absence-after-end assertion.** Asserting "the spinner cleared" would need the two-drop fence to make
   the transition deterministic; at rung 3 a second real-claude turn would itself re-enter `thinking` and
   re-show the spinner, so an absence assertion would flake. AC #2 / the ladder's "never on timing" rule
   forbid that — drop it (the AC explicitly authorizes *not* asserting absence-after-end if it proves
   flaky).
3. **`@Ignore`-gated by default (AC #3).** The developer cannot verify reliability — green is operator-gated
   on real infra (no device in the dev loop). Given the strong prior evidence that the window is transient
   and unfenceable, the safe landing is a documented **manual** case: ship the positive `@Test` `@Ignore`d
   with a one-line un-ignore instruction (mirroring #421's negative-control and #481's sibling precedent),
   *not* an unproven always-on test that would inject flake into the operator suite. The operator un-ignores
   on first run; **if** the spinner proves reliably catchable (see Open questions on the screen-sourced
   window), the operator *may* promote it to always-on — but the default ships gated.

**The assertion target is a production content-description, not a claude-output substring.** `cd_thread_thinking`
("Agent is thinking") is emitted only by `ThinkingIndicator`. This is materially simpler than #481/#421:

- **No prompt-collision concern.** #481/#421 assert on claude *output* substrings (`"Bash"`, `"ping"`) that
  could collide with the echoed prompt or auto-derived title, so they engineer the prompt to omit the token
  (or baseline-count it). Here the matched token is a UI string that never appears in any user bubble, title,
  or claude output — so there is **nothing the prompt must avoid**, and the prompt's *only* job is to widen
  the thinking window.
- **No negative control needed (deliberate divergence from the sibling pattern).** #481/#421 each ship an
  `@Ignore`d negative control to prove their *substring matcher is selective* (it waits for a token claude
  never produces → must time out). There is no analogous selectivity to disprove here: the assertion keys on
  a unique production content-description, not a claude-output substring. The `@Ignore`d positive test *is*
  the manual case. **Document this divergence in the test KDoc** so code-review does not read the missing
  negative control as a gap.

### The prompt

A **pure-reasoning** prompt that maximizes time-to-first-token and **invokes no tool**:

- **No tool → the #481 permission-modal unknown does not apply.** #481's chief first-run risk was the #428
  permission modal interposing on a real tool call (the rung-3 daemon spawns claude with no bypass flag). A
  prompt that keeps claude *thinking* with no tool call sidesteps that surface entirely. The prompt must
  therefore explicitly forbid tool use, so claude does not reach for one (which would both re-introduce the
  modal risk and shorten the thinking window).
- **Widen the thinking window.** The spinner shows during `turn_state(thinking)` — the gap between send and
  the first `assistant_delta`. A prompt that compels a beat of internal reasoning before any output widens
  that gap. Recommended shape (developer tunes on first run): a single instruction that asks claude to reason
  before answering and to answer with only a short token, **without using any tools** — e.g.
  `"Without using any tools, take a moment to reason this through, then reply with only the single word: ready. <a small reasoning task>"`.
  The exact wording is the developer's to tune; the design depends only on "no tool call" + "some
  pre-output thinking beat". **Correctness does not depend on the window being reliably catchable** — that is
  precisely why the test ships `@Ignore`d.

Store the prompt as one companion constant (e.g. `THINK_PROMPT`) alongside `PING_PROMPT`/`TOOL_PROMPT`,
documented as the determinism lever and a first-run tuning point.

## What the developer writes

One method + one helper + one constant in `InteractiveStreamE2ETest.kt`, plus two imports:

- **`@Ignore("manual — transient spinner; un-ignore to attempt promotion, see KDoc") @Test
  fun interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn()`** — reuses the ping/tool-use flow
  exactly (FAB wait → `awaitConnected()` → tap FAB → send-button wait → `performTextInput(THINK_PROMPT)` →
  tap send), then the **presence-only** assertion from [the central decision](#the-central-design-decision).
  KDoc covers: why `@Ignore` (transient + unfenceable at rung 3), the un-ignore/promote path, why no
  absence assertion, and why no negative control.
- **`private val thinkingDescription: String`** — `InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_thread_thinking)`,
  copied verbatim from `DeterministicInteractiveStreamE2ETest:84-88`.
- **`const val THINK_PROMPT`** in the companion (the pure-reasoning, no-tool prompt above).
- **Two new imports:** `androidx.test.platform.app.InstrumentationRegistry` and `de.pyryco.mobile.R`.
  Everything else (`hasContentDescription`, `assertIsDisplayed`, `onFirst`, `Ignore`, `performTextInput`,
  `hasSetTextAction`) is already imported in the file. (`onAllNodes` is a `composeTestRule` member — no
  import.)

Reuse `REPLY_TIMEOUT_MS = 90_000L` for the presence wait (generous; a real claude turn over the relay can
take many seconds to even *start*). Reuse `LIST_TIMEOUT_MS` / `CONNECT_TIMEOUT_MS` / `THREAD_TIMEOUT_MS`
unchanged.

## State + concurrency model

None new. The test reuses the shipped harness's flow and `awaitConnected()` (a `runBlocking { withTimeout(…) {
ConnectionStateSource.observe().first { it is Connected } } }`, `:197-205`). The live path under test —
`liveSessionEvents` SharedFlow → repo fold → `ThreadViewModel.isThinking` `StateFlow` →
`collectAsStateWithLifecycle` → `ThinkingIndicator` — is entirely production code, unchanged. The only
concurrency the test owns is Compose's `waitUntil` polling against the merged-semantics spinner node.

## Error handling / failure modes

- **`waitUntil` times out (spinner never observed).** Two causes, distinguished on first operator run:
  (a) the thinking window was real but too short to catch between Compose layout passes — the irreducible
  transience this `@Ignore` exists for; or (b) the screen-sourced `turn_state(thinking)` never reached the
  phone for this prompt (real claude answered instantly, or the daemon's screen-derivation changed) — **that
  is the drift Layer 3 exists to surface**, recorded as a finding, never papered over by weakening the
  assertion. The `@Ignore` default means neither outcome reds the operator suite by surprise.
- **Permission modal interposes.** Should not occur — the prompt invokes no tool (see Design). If it somehow
  does, the prompt is wrong (it reached for a tool); tighten the no-tool wording rather than tap the modal.
- **No phone-side parse risk.** The test feeds an author-controlled prompt to real claude on the operator's
  host; nothing new is parsed on the phone. Hence **not** `security-sensitive` (matches #421/#431/#454/#481).

## Testing strategy

- **The deliverable *is* the test** — no unit test of it. Host-JVM gates the developer runs (no device, per
  the rung-3 precedent): `./gradlew compileDebugAndroidTestKotlin spotlessCheck test lint assembleDebug`.
  The explicit `compileDebugAndroidTestKotlin` is **required** — the mandatory gates do not compile
  androidTest ([[androidtest-not-compiled-by-mandatory-gates]]). Two new imports are added; confirm they
  resolve. Run a single unit class via `./gradlew testDebugUnitTest --tests "<FQCN>"` if needed (bare
  `test` is the aggregate task and rejects `--tests`).
- **Green is operator-gated** via `bash scripts/e2e-emulator.sh` (the default rung-3, real-claude path),
  exactly as #421/#481. Because the method ships `@Ignore`d, the operator runs it explicitly:
  `… -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_thinkPrompt_showsThinkingSpinnerDuringTurn`
  (after temporarily removing `@Ignore`, or via a runner arg that includes ignored tests). No script change.
- **First-green prototype, not a hardened gate.** Expect to tune the prompt and confirm the screen-sourced
  window on first run, exactly as #421/#431/#454 did.

## Docs the developer updates

`docs/e2e-interactive-stream.md` only (developer-owned evergreen harness doc; the `codebase/482.md` note is
owned by the documentation phase — **do not** write it, and **do not** add it as an AC):

- **"What rung 3 is made of"** (`:86-92`) — note rung 3 now covers **three** scenarios (ping, tool-use,
  thinking-spinner); add that the spinner scenario is the **flakiest** and ships **`@Ignore`-gated / manual**
  (transient state, no durable artifact, no fence at rung 3), keyed tolerantly on the `cd_thread_thinking`
  affordance.
- **The ladder rung-3 line** (`:20-23`) — add the spinner scenario beside ping + tool-use.
- **Coverage / Follow-ups** (`:488-494`) — add "Layer-3 (real claude) thinking spinner — shipped (#482),
  `@Ignore`-gated manual", showing the spinner path now covered at all three layers (component #432, rung-4
  deterministic #454, rung-3 real-claude #482).
- **Assumptions to confirm on first run** (`:381+`) — one new entry: **the screen-sourced thinking window**
  (whether a pure-reasoning, no-tool prompt yields a `turn_state(thinking)` window long enough to observe
  over the relay; if real claude answers too fast to catch, keep the test `@Ignore`d per AC #3 and record
  that the promote attempt failed — a valid documented outcome, not a build failure). Note the prompt is the
  developer's to tune.

## Acceptance criteria mapping

- **AC #1** (new `@Test` asserts spinner displayed while turn active, tolerantly) → the presence-only
  assertion; tolerant `waitUntil` + `onFirst().assertIsDisplayed()`, never on counts/timing.
- **AC #2** (observe mid-turn without racing turn-end; keep claude busy long enough) → the no-tool
  reasoning prompt widens the thinking window; presence-only (no absence assertion that would race a 2nd
  turn).
- **AC #3** (flakiest → ship tolerant, `@Ignore`-gated with documented un-ignore if not reliable) → ships
  `@Ignore`d by default with KDoc un-ignore/promote instructions; the design's correctness does not depend
  on reliable catching.
- **AC #4** (real Noise/relay path, real claude, ≤ one Max-sub turn per run) → reuses the #421 harness
  verbatim; one prompt → one turn.
- **AC #5** (docs the new Layer-3 spinner scenario + its flaky/manual status) → the
  `docs/e2e-interactive-stream.md` edits above.

## Open questions / first-run unknowns

- **Does a no-tool reasoning prompt produce a catchable `turn_state(thinking)` window?** The screen-sourced
  signal depends on how pyrycode's daemon maps claude's pre-output TUI state. If real claude answers too fast
  for the spinner to lay out and be polled, the test stays `@Ignore`d (AC #3) — a documented manual case, not
  a failure. The developer tunes the prompt toward a longer reasoning beat on first run; the operator decides
  whether to promote.
- **If claude streams its reasoning as visible text**, `turn_state` flips to `responding` immediately and the
  spinner clears at once. Framing the prompt to answer with only a short token after thinking (rather than
  narrating the reasoning) is the lever; tune on first run.

## Out of scope

- Real tool-use rendering — #481 (shipped, the other half of #434's split).
- Deterministic / scripted spinner coverage — #454 (Layer 2a). Component-level — #432 (Layer 1a).
- Any change to the phone-side spinner render (`ThinkingIndicator`, #406's `turn_state` → `isThinking` fold)
  or the #421 harness seams.
- `docs/knowledge/codebase/482.md` — owned by the documentation phase, written after merge.

# Spec #481 — Layer 3 (real claude): tool-use renders

**Ticket:** [#481](https://github.com/pyrycode/pyrycode-mobile/issues/481) · size **S** · no `security-sensitive` · no Figma.

Adds **one** real-claude emulator e2e scenario on the **shipped #421 harness**
(`InteractiveStreamE2ETest`): a constrained prompt that makes **real claude run a tool**, asserting the
resulting **tool step renders** in the thread — asserted **tolerantly** (presence of the shipped
tool-row affordance, keyed on the verbatim tool name; generous timeout; never on counts or timing).

This is the **Layer-3 (real claude) twin** of #455 (Layer 2c, deterministic scripted tool-step
coverage — shipped) and #472 (Layer 1b, in-process component twin — shipped). The render path it
exercises — `tool_use` → a running/done tool row (#387 correlation fold, #388 tool-row status UI) — is
**already shipped and security-reviewed**. This ticket **exercises** it end-to-end against real claude;
it does **not** modify it.

**Test + docs only — zero production Kotlin, zero new files, zero new types.** It is one additive
`@Test` method + a small companion-constant block in the existing `InteractiveStreamE2ETest.kt`, an
optional `@Ignore`d negative-control twin, and a coverage-list update in `docs/e2e-interactive-stream.md`.

## Files to read first

| Path (key lines) | What to extract |
| --- | --- |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` (whole, 150 lines) | **The harness you ride.** The connect → create-discussion → type → send flow (`:52-86`), `awaitConnected()` (`:121-129`), the tolerant `waitUntil` + node-presence idiom, the `@Ignore`d negative control (`:88-112`) to mirror, and the companion constants block (`:131-149`) where the new tool constants go. The new `@Test` slots in alongside `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:135-163` | `ToolCallStatusIcon`: the three states' affordances + CDs. **Running** = `CircularProgressIndicator`, CD `cd_tool_running`. **Failed** = `ErrorOutline`, CD `cd_tool_failed`. **Done** = per-tool icon, `contentDescription = null` (**no positive CD** — drives the assertion choice below). |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:206-219` | `buildSummaryAnnotated`: the collapsed header renders `toolName · input` as a single `Text`. The **tool name is carried verbatim and renders in all three states** (running, done, failed) — this is the **durable identifier** the test keys on. |
| `app/src/main/res/values/strings.xml:63-64` | `cd_tool_running` ("Tool call running") / `cd_tool_failed` ("Tool call failed") — only needed if the developer adds the *best-effort* running-CD observation; the load-bearing assertion needs neither (see § Design). |
| `docs/specs/architecture/472-tool-step-rows-component-e2e.md` (§ "done" assertion) | The component twin's `onNodeWithText("Bash", substring = true).assertIsDisplayed()` idiom — the exact selector to reuse for the verbatim-tool-name assertion. |
| `docs/specs/architecture/455-tool-step-e2e.md` (§ Design, § Done assertion) | The deterministic twin's reasoning: why "done" is asserted tolerantly via the tool name + absence (Done has no CD). **Reuse the assertion *shape*; ignore the fixture / two-drop / host-script machinery** — that is rung-4-only and cannot exist here (rung 3 has no scripted backend to hold a turn open). |
| `docs/e2e-interactive-stream.md` ("The ladder" rung 3; "What rung 3 is made of"; Constraints; Assumptions) | The ladder doc you extend (AC #5). The **Constraints** section is the binding tolerant-assert rule (substring/presence, generous timeout, **never on delta counts or timing**); the **Assumptions** section is where the new real-claude tool-use assumptions land. |
| `scripts/e2e-emulator.sh:342-348` | The rung-3 (real claude) daemon invocation. **No `-pyry-claude` override, no permission flag** — drives the permission open question (§ Open questions #2). #421's ping path never ran a tool, so this is freshly relevant. |

## Context

The mobile e2e ladder (`docs/e2e-interactive-stream.md`) has three reliability layers. **Layer 3 / rung
3** is the real app on a headless emulator → host `pyry` daemon spawning **real claude** → the real
Noise/relay path (`bash scripts/e2e-emulator.sh`). It runs rarely, after Layers 1–2 pass, and is the
**only** layer that catches claude changing its own output — the scripted backend (rung 4) is pinned to
a fixture and is blind to that drift.

The happy-path Layer-3 test already exists (#421's `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`:
a "reply with exactly the word: ping" prompt → the streamed reply renders, plus an `@Ignore`d negative
control). This ticket adds the **tool-use** scenario on that same harness: a constrained prompt that
makes real claude run a tool, asserting the tool step renders.

The render path is **already shipped and reviewed**: `tool_use` → a running tool row, `tool_result` →
done (#387 correlation fold, #388 tool-row status UI). This ticket exercises that path against real
claude; the only seam that changes is **the prompt + the asserted tool-row markers**. Do **not** touch
the phone-side fold/render or the #421/#431 harness seams beyond what a new prompt + assertion needs.

## Why no `## Design source` (no Figma)

This is a test-harness ticket — it asserts against the **already Figma-anchored** tool rows (node
`16-28`, shipped + reviewed via #388). It introduces no new visuals, so there is no Figma node to echo
and the visual-fidelity check is intentionally not applicable. Matches #421 / #431 / #455 / #472.

```markdown
## Design source

N/A — test-harness ticket; asserts against the already-shipped, Figma-anchored (16-28, via #388) tool rows. No new visuals.
```

## Why this is not `security-sensitive` (matches #421 / #431 / #455 / #472)

No new untrusted-input parse point is introduced. The decode substrate (#385) and the
`tool_use`↔`tool_result` correlation fold (#387) were the trust boundary when introduced and were
security-reviewed then. This test sends an author-controlled prompt and reads already-rendered UI;
exercising an already-reviewed decode/correlation/render path in a test is not a new trust boundary, and
there is no credential / crypto / auth change and a single interactive phone. `security-sensitive` is
correctly omitted (the ticket invited the architect to re-add if a surface was missed — none was: the
prompt text is the only new input and it flows to *real claude on the operator's own host*, not to a
parser on the phone). The § 3 security-review pass therefore does not run.

## Design

### The determinism lever — a prompt that compels a predictable tool call (AC #1, #2)

Mirror #421's "reply with exactly the word: ping (nothing else)" lever, adapted to force a tool call.
The most predictable tool for real claude is the **shell tool** (claude names it `Bash`): a direct
imperative to *run a command* reliably triggers it, where "what does X output?" might be answered inline
without a tool. Suggested constant (the developer may tune the exact wording on first operator run):

```kotlin
// Constrained so real claude runs ONE shell tool, predictably, while the whole path stays real.
// Deliberately contains neither "Bash" nor "bash" (see § the assertion) so the asserted tool-name
// substring can only come from the rendered tool row, never from the echoed prompt or the thread title.
const val TOOL_PROMPT = "Run this exact shell command with your tools, then stop without commentary: echo pyry481"
const val TOOL_NAME = "Bash"   // claude's verbatim shell-tool name; renders in the tool-row header
```

`echo <fixed string>` is the safest possible command: read-only, no filesystem/network side effects,
trivially fast, and harmless on the operator's host. The "then stop without commentary" clause mirrors
#421's "nothing else" — it keeps claude's surrounding prose minimal, reducing the chance prose mentions
the tool name (see the prose-collision note below). The assertion tolerates whatever prose claude does
emit (AC #2).

### The assertion — the durable terminal signal, not the transient running state

This is the central design decision and where Layer 3 differs from its scripted twins (#455, #472).

The tool row has three status affordances, but only the **transient Running** (`cd_tool_running`) and
**Failed** (`cd_tool_failed`) states carry a positive content-description; **Done has
`contentDescription = null`** (`ToolCallRow.kt:148-154`). #455 (rung 4) and #472 (component) catch the
**running** state *while in flight* by **holding the turn open** — #455 via a two-drop causal fence on
the scripted backend, #472 via imperative `pushToolUse` then a `waitUntil` before `pushToolResult`.
**Neither lever exists in rung 3:** there is no scripted backend to hold the turn, and the test cannot
imperatively pause real claude between its `tool_use` and `tool_result`. Racing to catch the transient
running spinner over a real relay turn is exactly the "never on … timing" / "never race a transient"
failure the ladder doc forbids (it is why #454/#455 needed the two-drop fence in the first place).

So the **load-bearing assertion is the durable terminal signal: the verbatim tool name renders in the
tool row.** `buildSummaryAnnotated` (`ToolCallRow.kt:206-219`) renders `toolName · input` as the
collapsed header in **all three states**, and the tool `name` is carried **verbatim** through the #387
fold. After the turn completes, the tool row sits in `Done` state still showing `Bash` — present and
stable, no CD needed, no timing dependency. The assertion reuses #472's exact idiom:

- After arriving in the thread (reuse `awaitConnected()` + the create/type/send flow verbatim) and
  sending `TOOL_PROMPT`, `waitUntil(REPLY_TIMEOUT_MS)` that
  `onAllNodesWithText(TOOL_NAME, substring = true)` is **non-empty**, then assert its first node
  `assertIsDisplayed()`.
- This is a **presence** check, not a count: because `TOOL_PROMPT` contains neither "Bash" nor "bash",
  the substring is **absent from everything on screen before claude responds** (the echoed user-message
  bubble, the auto-derived thread title, the thinking spinner). A non-empty match can therefore only
  come from the rendered tool row. This is *stricter* than #421's baseline-count trick (#421 needed the
  count only because "ping" was already in its own prompt) and keeps the test cleanly inside the
  "never on counts" constraint.

**This satisfies "presence of the shipped tool-row affordance + an identifying substring" (AC #1):** the
tool-row affordance is the rendered `ToolCallRow`, and the identifying substring is the verbatim tool
name it carries.

**Do not assert on `input` / output text.** Per the ladder doc's tolerant rule (and #455/#472), the
producer-derived input/output summaries may be summarized or truncated — assert only the verbatim tool
name. **Do not add a test tag or new CD to `ToolCallRow`** (out of scope — no production change; the
ticket is explicit).

**Prose-collision note (the one tolerated weakness).** A bare tool-name substring could in principle
match claude's prose (e.g. "I ran the bash command…") rather than the row. Three things bound this to
acceptable: (a) `TOOL_PROMPT` is a *direct imperative to run* the command, so claude runs the tool —
when it does, the row renders the name regardless of prose; (b) the prompt omits the token, so the only
*other* possible source is claude's own prose *about* a tool it engaged with; (c) Layer 3 is
operator-run and tolerant by design, with the negative-control proof below. A genuinely robust
row-only-for-Done signal would require a production test tag, which is forbidden here. This residual
tolerance is the deliberate, documented trade-off — the same shape #455/#472 accepted when they keyed
"done" on the verbatim tool name.

### Optional best-effort running observation (not load-bearing)

If the developer wants a secondary signal, a non-blocking *opportunistic* check for `cd_tool_running`
may be added (read the CD from `strings.xml:63` the way #421 reads its constants). It must **not** be a
blocking `waitUntil` and must **not** fail the test if absent — running is transient over a real turn
and may have already flipped to done by the time Compose lays out. Recommendation: **omit it.** The
durable tool-name assertion above is sufficient and the doc forbids depending on the transient. Mention
this in the PR if added.

### Negative-control / `@Ignore` posture (AC #4)

Follow #421's precedent exactly:

- **The positive test is always-present in the rung-3 suite** (the whole suite is operator-run, so
  "always-on" simply means it runs when the operator runs rung 3 — like #421's positive ping test). It
  burns **at most one** Max-subscription claude turn per run (AC #3): one prompt → one tool-using turn.
- **Add a `@Ignore`d negative control** mirroring `negativeControl_wordClaudeNeverSays_isNeverDisplayed`
  (`InteractiveStreamE2ETest.kt:88-112`): same prompt, but `waitUntil` for a tool name claude is **never
  asked to use** (e.g. `"Edit"` — the prompt only asks for a read-only shell command, never a file
  edit). On a correct build this **times out → the test FAILS**, proving the `TOOL_NAME` substring
  matcher is not matching everything and the positive assertion genuinely observes a rendered tool row.
  Left `@Ignore`d so it does not burn a claude turn on every suite run; document inline that the
  operator un-ignores it once to confirm, then re-ignores. (Choose a control token that is itself a
  real, distinct tool name so the matcher's selectivity is what is proven — not merely a nonsense
  string.)
- **If the tool-use turn proves unreliable across runs** (claude sometimes answers inline without the
  tool, or permission friction makes it flaky — see § Open questions #2), gate the **positive** test
  with `@Ignore` + an inline manual-switch comment too, exactly as the negative control is gated —
  rather than leaving a flaky always-on test. Document the gating inline.

### Test method (`InteractiveStreamE2ETest.kt`) — scenario, not full body

Add **one** `@Test` (write the body in the file's existing idiom — reuse the list-wait, `awaitConnected()`,
create/type/send, and tolerant `waitUntil` exactly as the ping test does):

**`interactiveTurn_toolPrompt_rendersToolStepInThread`** (AC #1, #2, #3):
1. `waitUntil(LIST_TIMEOUT_MS)` the "New discussion" FAB is present (reuse the ping test's step 1).
2. `awaitConnected()` (reuse verbatim).
3. Tap "New discussion" → `waitUntil(THREAD_TIMEOUT_MS)` the send button is present (reuse step 3).
4. `performTextInput(TOOL_PROMPT)` into the editable field; tap send (reuse step 4).
5. `waitUntil(REPLY_TIMEOUT_MS) { onAllNodesWithText(TOOL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty() }`,
   then `onAllNodesWithText(TOOL_NAME, substring = true).onFirst().assertIsDisplayed()`.

Plus the `@Ignore`d negative control above and the two new companion constants (`TOOL_PROMPT`,
`TOOL_NAME`). No new imports beyond what `InteractiveStreamE2ETest.kt` already has
(`onAllNodesWithText`, `onFirst`, `assertIsDisplayed`, `hasContentDescription`, `performTextInput`,
`performClick`, `hasSetTextAction` are all already imported).

## State + concurrency model

Unchanged from #421. The test drives the **real** app over the real Noise/relay path; the only backend
is real claude on the host. `awaitConnected()` gates on `ConnectionState.Connected` (read off the Koin
`ConnectionStateSource`) before any interaction. All synchronization is `composeTestRule.waitUntil` with
the existing generous timeouts (`REPLY_TIMEOUT_MS = 90_000L` — a real claude tool turn can take many
seconds end to end; keep it). No new dispatcher, scope, or flow is introduced.

## Error handling

This is a test. Failure modes surface as `waitUntil` timeouts:

- **Claude does not run a tool** (answers inline) → the `TOOL_NAME` wait times out. Mitigation: the
  direct-imperative prompt; tune wording on first run; `@Ignore`-gate if irreducibly flaky (AC #4).
- **Producer does not emit `tool_use`/`tool_result` for real claude as expected** → no row → timeout.
  This is precisely the drift Layer 3 exists to catch — surface it as a buildability finding, do not
  paper over it (see § Open questions #1).
- **A permission modal interposes** and the tool never runs without approval → timeout (§ Open
  questions #2).

Assertions are tolerant (substring presence + verbatim name; generous timeout) so claude's natural
prose variation does not flake the test.

## Testing strategy

- **Verified in this run (host JVM, no device):** `./gradlew compileDebugAndroidTestKotlin` compiles the
  new `@Test` + constants (the [androidtest-not-compiled-by-mandatory-gates] lesson — `test` / `lint` /
  `assembleDebug` skip `androidTest`; compile it explicitly). `onAllNodesWithText` / `onFirst` /
  `assertIsDisplayed` are members already imported in the file. Run `./gradlew test lint spotlessCheck`
  to confirm nothing else moved (no production change → they cannot regress, but confirm).
- **Operator-run (needs infra):** `bash scripts/e2e-emulator.sh` runs the **default** (rung-3, real
  claude) path. The new method joins the existing ping method in the same `InteractiveStreamE2ETest`
  class. **Note:** the script currently runs the whole class for rung 3; if the operator wants to run
  just this method, scope it with
  `-Pandroid.testInstrumentationRunnerArguments.class=…InteractiveStreamE2ETest#interactiveTurn_toolPrompt_rendersToolStepInThread`
  (no script change required — this is an operator invocation note, not a code change).
- This is a first-green prototype, not a hardened gate — expect to tune the prompt and confirm the
  assumptions below on first run, exactly as #421/#431/#455 did.

## Docs update (AC #5) — `docs/e2e-interactive-stream.md`

Document the new Layer-3 tool-use scenario in the ladder's coverage list (test + docs change land in
this one ticket, as #421/#431/#454/#455 each did for their own rung). Concretely:

- **Rung 3 description / "What rung 3 is made of":** add a line that rung 3 now also covers a **tool-use**
  scenario (constrained prompt → real claude runs a shell tool → the tool step renders), alongside the
  ping happy path.
- **"Coverage" follow-up line:** add "Layer-3 (real claude) tool-use renders — **shipped (#481)**"
  beside the existing rung-4 tool-use (#455) / component (#472) entries, so the ladder shows the
  tool-use path covered at all three layers.
- **Assumptions section:** add the two real-claude tool-use assumptions from § Open questions below
  (producer emits `tool_use`/`tool_result` for real claude; the prompt reliably triggers the shell tool;
  permission behaviour). This is where #421/#455's "confirm on first run" items live.
- **Negative-control note:** extend the existing negative-control bullet to mention the tool-use negative
  control (a never-used tool name must time out).

Keep edits scoped to the coverage list / assumptions / negative-control prose — do not restructure the
doc. (This file is the harness's own coupled doc, updated within each rung's ticket; it is **not** the
documentation-phase-owned `docs/knowledge/codebase/<N>.md`, which is out of scope for the developer.)

## Open questions (confirm on first operator run — fold into the doc's "Assumptions" section)

1. **Real claude emits the tool step the phone expects.** The producer must emit a `tool_use` envelope
   (with `name` = `"Bash"`, carried verbatim) and a correlated `tool_result` for a real-claude shell
   tool, the same shape #455's fixtures simulate. This is the standard Anthropic transcript shape and
   matches pyrycode's tui-driver extractors (`ParseToolUse`/`ParseToolResult`; cf. pyrycode #382/#671),
   but is **unverified end to end with real claude** — if real claude's tool-use output differs (a
   different tool name, a changed envelope shape), the assertion fails, and **that is the finding Layer 3
   exists to surface.** If `"Bash"` is not the rendered name on first run, adjust `TOOL_NAME` (and/or the
   prompt to target whichever tool claude reliably uses) — do not weaken the assertion to a generic
   match.
2. **Tool permission behaviour (the chief first-run unknown — #421 never hit this).** The rung-3 daemon
   (`scripts/e2e-emulator.sh:342`) spawns **default real claude with no permission-bypass flag**. If a
   real tool call interposes the mobile **permission modal** (#428 epic), the tool will not run until
   approved → the tool-row assertion times out. Resolve in this order of preference on first run:
   - **(a) Daemon-side bypass (preferred):** run the e2e daemon's claude in a non-interactive /
     auto-approve permission mode (a host/daemon-side config or flag — *pyrycode-side, not a mobile
     change*) so `echo` runs without a modal, keeping the test focused on tool-row render. If the daemon
     already auto-approves for this path, nothing is needed; confirm on first run.
   - **(b) Approve in-test:** if a modal appears and (a) is not available, the test taps the approve
     affordance before asserting the tool row — but this couples the test to the permission-modal UI
     (#437/#438) and is the more fragile option. Prefer (a). Flag whichever path is taken in the PR and
     record it in the doc's Assumptions.
3. **Prompt reliability across runs.** "Run this exact shell command … echo pyry481" should make real
   claude run the shell tool every run. If it sometimes answers inline (no tool), tighten the wording, or
   `@Ignore`-gate the positive test per AC #4 and the negative-control precedent. The exact wording is
   the developer's to tune on first operator run; the design above does not depend on the specific string
   beyond "compels one shell tool call" + "omits the asserted tool-name token".

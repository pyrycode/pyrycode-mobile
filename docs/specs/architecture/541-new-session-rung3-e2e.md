# #541 — rung-3 real-claude e2e: "New session" → session-boundary delimiter

**Size:** S (clone of #566, simpler — no picker, no recents re-open). Zero production code: one new
always-on `@Test` in `InteractiveStreamE2ETest.kt`, ~3 companion constants, one appended entry in the
`scripts/e2e-emulator.sh` LIVE `class#method` list, and a doc note in `docs/e2e-interactive-stream.md`.

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — **the clone target.**
  Method `interactiveTurn_createWorkspaceFolder_usableAsLiveSessionWorkspace` (lines 301–371) is the
  structural template; the companion object (lines 390–445) holds every reused constant + helper
  (`PING`, `PING_PROMPT`, `CD_NEW_DISCUSSION`, `CD_SEND_MESSAGE`, `pingNodeCount()`, `awaitConnected()`,
  `LIST_TIMEOUT_MS`, `CONNECT_TIMEOUT_MS`, `THREAD_TIMEOUT_MS`, `REPLY_TIMEOUT_MS`). The ping happy-path
  method (lines 64–99) is the minimal tail you reuse for the "prove it's live" turn.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedSessionBoundaryTest.kt:46–125`
  — the **render-path precedent** (rung 2). It already asserts the exact `EXPLANATION`
  (`"Claude doesn't remember messages above this line"`, line 121) and the colliding `CLEAR_LABEL_PREFIX`
  (`"New session"`, line 124). Confirms the two strings and the tolerant-assert idiom.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt:44–118` —
  the delimiter render. **Line 92** is the exact hardcoded explanation literal your load-bearing matcher
  targets (`"Claude doesn't remember messages above this line. Install a memory plugin to preserve context. "`).
  **Line 114** is the colliding `BoundaryReason.Clear` label (`"New session — $time"`) you must NOT match on.
  The explanation is **reason-independent** (rendered for every boundary reason), so the matcher is robust
  even if the daemon's `session_transition` reason differs from `clear`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` — the "New session"
  item (`thread_overflow_new_session`) is gated **only** on `mutationsSupported`, **not** on `isPromoted`.
  Confirms the affordance is reachable on a plain **discussion** (what the "New discussion" FAB creates),
  firing `ThreadEvent.NewSession` on tap and dismissing the menu.
- `app/src/main/res/values/strings.xml:10,65` — the exact selector strings:
  `cd_more_actions` = `"More actions"`, `thread_overflow_new_session` = `"New session"`.
- `scripts/e2e-emulator.sh:452–482` — the LIVE curation block. `TEST_TARGET` (line ~462) is the
  comma-separated `class#method` list you append the new method to. Lines 9–11, 55, 454–455, 478–479 carry
  the "ping + create-workspace-folder (2 turns)" prose that becomes a trio (3 turns).
- `docs/e2e-interactive-stream.md` — where #566 was documented (lines 29–32, 88–124, 176–266, 617–641);
  mirror the same treatment for New session and update the turn-count prose (see § Doc note below).
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt:1450–1457` —
  context only (not edited): `mutationsSupported = true` in relay mode, the flip that made this reachable.
- Memory lesson `androidtest-not-compiled-by-mandatory-gates` — `./gradlew test`/`lint`/`assembleDebug`
  do **not** compile androidTest. The build-gate for this ticket is
  `./gradlew compileDebugAndroidTestKotlin`.

## Context

Org policy (2026-07-08): an operator-facing happy-path flow ships with a real-claude e2e that runs in
the pre-ship gate. This is the #534 "New session" follow-up, filed separately so the wiring tickets
(#539/#540) stayed size S. The reachability gate that parked this ticket in Inbox — the "New session"
affordance hidden behind `mutationsSupported == false` (a #507 placeholder) — was cleared by PR #572,
which flipped the flag to `true` now that every co-gated sibling wire shipped (#530–#536 / #549 / #560 /
#564 / #565; daemon pyrycode #820–#826). A rung-3 e2e drives the **real** overflow menu, so the scenario
is now reachable end to end. Its only native blocker (#540) is closed.

This is a **test-only** ticket. It drives existing, shipped UI; there is no new design surface, so there
is no `## Design source` section (the twin #566 established this shape).

## The scenario

With a live, exercised session, trigger "New session" from the thread overflow menu → the daemon runs
`/clear` → broadcasts `session_transition` (`reason: "clear"`) → the thread folds a
`ThreadItem.SessionBoundary` (#336, canonical in `RemoteConversationRepository`) → `SessionBoundaryDelimiter`
renders it. `new_session` is **fire-and-forget** (pyrycode#831, #540 wire), so the assertion is on the
**durable delimiter that appears after the broadcast**, never on an ack.

## Design

One new `@Test` method, `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (name is the
developer's to finalize), cloning #566's method structure but simpler (no picker, no recents). Steps, as
tolerant-assertion scenario bullets — not code to paste:

1. **Land on the channel list** — `waitUntil(LIST_TIMEOUT_MS)` for `CD_NEW_DISCUSSION` present. (Reused
   verbatim from the ping method.)
2. **`awaitConnected()`** — the relay session must be Open before the create round-trips to the daemon.
3. **Create a discussion** — a **regular tap** (not long-press) on `CD_NEW_DISCUSSION` → navigate into
   the thread; `waitUntil(THREAD_TIMEOUT_MS)` for `CD_SEND_MESSAGE`. A plain discussion suffices — the
   "New session" item is gated on `mutationsSupported` only, not promotion.
4. **Prove the session is live (AC-3)** — type `PING_PROMPT`, tap `CD_SEND_MESSAGE`, then the ping tail
   verbatim from the ping method: `waitUntil { pingNodeCount() >= 1 }`, snapshot `baseline`,
   `waitUntil(REPLY_TIMEOUT_MS) { pingNodeCount() > baseline }`. This completes **one** real claude turn
   (the ping) and leaves de-emphasized above-delimiter content. `/clear` spends **no** turn — total
   real-claude cost is one turn.
5. **Absence guard (AC-2, deterministic — no extra turn)** — before opening the overflow menu, assert the
   delimiter explanation is **absent**: `onAllNodesWithText(DELIMITER_EXPLANATION, substring = true)`
   `.assertCountEquals(0)`. This makes the delimiter's later appearance attributable to the New-session tap.
6. **Drive the real overflow menu** — `onNode(hasContentDescription(CD_MORE_ACTIONS)).performClick()`;
   `waitUntil(THREAD_TIMEOUT_MS)` for `NEW_SESSION_ITEM` to render; then
   `onAllNodesWithText(NEW_SESSION_ITEM).onFirst().performClick()`. `NEW_SESSION_ITEM` is used **only** to
   locate/tap the menu item — never as the durable assertion (see § Token collision).
7. **Assert the durable delimiter (AC-1, AC-2)** —
   `waitUntil(REPLY_TIMEOUT_MS) { onAllNodesWithText(DELIMITER_EXPLANATION, substring = true).fetchSemanticsNodes().isNotEmpty() }`,
   then `onAllNodesWithText(DELIMITER_EXPLANATION, substring = true).onFirst().assertIsDisplayed()`.
   Tolerant: substring, generous timeout, presence — never a delta count or timing.

**Always-on, not `@Ignore`d.** The delimiter is a **durable** artifact that survives the turn (unlike
#482's transient thinking spinner, which leaves no trace once `turn_state` flips to `responding`), so it
belongs in the always-on gate — matching #481's durable tool-name row, not #482's `@Ignore`d spinner.

**No negative control needed.** `DELIMITER_EXPLANATION` is a production render literal that never appears
in any user bubble, auto-derived title, thinking spinner, or claude output — there is nothing for a
negative control to disprove (same reasoning the #482 spinner test documents). The absence-before-tap
guard (step 5) already proves the token is not matching pre-existing content.

### Companion constants to add

```kotlin
const val CD_MORE_ACTIONS = "More actions"                                   // R.string.cd_more_actions
const val NEW_SESSION_ITEM = "New session"                                   // R.string.thread_overflow_new_session — tap target ONLY
const val DELIMITER_EXPLANATION = "Claude doesn't remember messages above this line"  // load-bearing durable matcher
```

Keep the `// Keep in sync with res/values/strings.xml` comment convention already used for
`CD_NEW_DISCUSSION` / `CD_SEND_MESSAGE` / `CD_BACK`.

### Token collision — the one real gotcha

`boundaryLabel(BoundaryReason.Clear)` renders `"New session — <time>"`, whose prefix is byte-identical to
the overflow menu item `thread_overflow_new_session` (`"New session"`). Matching on `"New session"` is
therefore **not** selective at rung 3 (it would match the menu item and the label prefix, not just the
delimiter). The load-bearing matcher must be `DELIMITER_EXPLANATION`, which can **only** come from the
rendered delimiter (SessionBoundaryDelimiter.kt:92). This is the same token-selectivity discipline as
#481 (`TOOL_PROMPT` omits `"Bash"`) and #566 (unique per-run `folderName`).

## State + concurrency model

None new. The test drives the shipped MVI surface through Compose/Espresso synchronization
(`waitUntil` + `assertIsDisplayed`) and reads the relay connection via `awaitConnected()`'s existing
`ConnectionStateSource` observe (companion helper, lines 380–388). No coroutine, dispatcher, or
`StateFlow` shape is introduced. The render path (`session_transition` → `SessionBoundary` fold →
`SessionBoundaryDelimiter`) is the canonical `RemoteConversationRepository` fold (#336) — the e2e proves
it against real claude + a real daemon `/clear`, exercising nothing the Fake synthesizes.

## Error handling

Test-side only: every wait is `waitUntil(<timeout>)`, which throws `ComposeTimeoutException` on the
generous timeout — the correct failure mode for a genuinely stuck flow. No new production error paths.
Real-claude variance is absorbed by tolerant matchers (substring, presence, generous timeout) per the
`docs/e2e-interactive-stream.md` Constraints.

## Testing strategy

Instrumented (`androidTest`), run by `scripts/e2e-emulator.sh` on a headless emulator against a host
`pyry` + relay + real claude (rung 3), and by the `LIVE=1` pre-ship gate against the production relay.
**Not** covered by `./gradlew test` (unit) — this is an emulator-host orchestration. Build-gate:
`./gradlew compileDebugAndroidTestKotlin` (androidTest is not compiled by the mandatory `test`/`lint`/
`assembleDebug` gates). `assertCountEquals` needs `import androidx.compose.ui.test.assertCountEquals`;
the other selectors (`onAllNodesWithText`, `onFirst`, `hasContentDescription`, `performClick`,
`assertIsDisplayed`, `onNode`/`onAllNodes`/`waitUntil`) are already imported or are rule members — let
the compile gate resolve the final import set.

## `scripts/e2e-emulator.sh` change (LIVE curation → trio)

Append the new method to the LIVE `TEST_TARGET` comma-separated `class#method` list (line ~462), so the
gate runs a **trio**: ping + create-workspace-folder + new-session (three real claude turns). The #481
tool-use test stays LIVE-excluded for cost — this is **additive**, replacing nothing. Update the
turn-count prose that currently says "2 turns" / "curated pair" / "ping + create-workspace-folder"
(header lines 9–11, usage line 55, run-block comment lines 454–455 and 459–462, PASS log lines 478–479)
to reflect the trio (3 turns). Grep to find every spot so none drifts:

```bash
grep -n 'curated pair\|2 turns\|two curated\|two real claude\|ping + create-workspace-folder\|2nd curated turn' \
  scripts/e2e-emulator.sh docs/e2e-interactive-stream.md
```

## Doc note (`docs/e2e-interactive-stream.md`)

Mirror how #566 was wired in: add a New-session scenario paragraph to "What rung 3 is made of" / the
rung-3 scenario list (around lines 98–124), note it in the LIVE mode "What it runs" (lines 217–221) and
the pre-ship gate section (lines 187–198), and update the Cost lines (197–198, 249) and the ladder entry
(29–32) plus the Coverage follow-ups (617–641) from a pair to a trio. Key facts to state: the durable
delimiter (explanation line) is the load-bearing signal; the scenario is always-on (durable artifact,
unlike the `@Ignore`d spinner); fire-and-forget so no ack is asserted; total cost is one real claude turn
(the ping — `/clear` spends none).

## Open questions / first-run assumptions (grounded, unverified end to end)

- **Daemon runs `/clear` and broadcasts `session_transition` on `new_session`.** This is the pyrycode#831
  / #540 fire-and-forget contract, but unverified end to end against real claude. If the daemon does not
  broadcast the transition, the delimiter never renders and step 7 times out — that is the drift Layer 3
  exists to surface. Do **not** weaken the assertion; record the finding. (Analogue: #481's tool-emission
  assumption.)
- **`state.mutationsSupported` propagates to the thread in relay mode.** The E2e app is relay-backed, and
  `RemoteConversationRepository.mutationsSupported = true`, so the "New session" item should render. Confirm
  on first run that the overflow item is present (if absent, the menu-item wait times out).
- **New session needs no permission modal.** Unlike #481's shell tool, `/clear` is not a tool call, so the
  #428 permission modal should not interpose. Confirm on first run; if a modal appears, that is a finding,
  not a reason to weaken the test.

## Acceptance criteria (developer deliverable)

- [ ] New always-on `@Test` in `InteractiveStreamE2ETest` (rung 3, real claude) drives the **real**
      overflow menu (`cd_more_actions` → "New session") and asserts a **durable** session-boundary
      delimiter renders, keyed tolerantly on `DELIMITER_EXPLANATION` (substring, generous timeout, never on
      counts/timing). Not `@Ignore`d.
- [ ] The load-bearing matcher is `DELIMITER_EXPLANATION` (delimiter-only), **not** `"New session"`
      (menu-item / Clear-label collision). The delimiter's absence is asserted **before** the New-session
      tap (`assertCountEquals(0)`), a deterministic guard with no extra claude turn.
- [ ] The scenario completes one real claude turn (`PING_PROMPT`) **before** New session, so the cleared
      session is genuinely live with above-delimiter content. Total real-claude cost: one turn.
- [ ] The scenario is added to the LIVE curated `class#method` list in `scripts/e2e-emulator.sh` (now a
      trio) and documented in `docs/e2e-interactive-stream.md`, mirroring how #566 was wired in.
- [ ] `./gradlew compileDebugAndroidTestKotlin` is green (androidTest is not compiled by the mandatory
      gates).

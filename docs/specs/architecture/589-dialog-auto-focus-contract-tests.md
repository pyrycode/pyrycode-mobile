# #589 — Cover the dialog auto-focus contract

**Size:** `s` (confirmed — see § Size check)
**Type:** bug / test-gap
**Security-sensitive:** no (no label; dialog focus only — no auth, crypto, or untrusted input)

## Files to read first

| Path | What to extract |
| --- | --- |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt:59-68,80-98` | The auto-focus idiom under test: `remember { FocusRequester() }`, `LaunchedEffect(Unit) { focusRequester.requestFocus() }` at **line 67**, and the `.focusRequester(focusRequester)` modifier on the `OutlinedTextField`. Note the `LaunchedEffect` sits *outside* the `AlertDialog` call — that placement is the load-bearing detail if the contract turns out broken. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt:50-57,69-87` | Same idiom, `requestFocus()` at **line 56**. Difference that matters: the field is seeded **empty** (`TextFieldValue(text = "", …)`), so there is no pre-filled text to key an assertion on. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt:71-79,94-114` | Same idiom, `requestFocus()` at **line 78**. The field is one child of a `Column` that also holds `WorkspaceRadios` — the only one of the three where the dialog's `text` slot holds more than the field. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogTest.kt:20-48` | The house test shape to clone: `@RunWith(AndroidJUnit4::class)`, `createComposeRule()`, `setContent { PyrycodeMobileTheme { … } }`, snake_case test names. Line 47 shows `hasSetTextAction() and hasText(...)` already in use — **`and` is a `SemanticsMatcher` member, no import**. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialogTest.kt:25-39` | Same shape. Line 35 proves the label text merges into the field's semantics node, which is why the empty field is still addressable. |
| `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialogTest.kt:23-48` | **This file already exists** with 11 tests (see § Correction below). Add to it; do not create it. |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:544-546` | The exact live wait + follow-up this ticket is measuring. **Read only — AC-5 pins this file as unmodified.** The follow-up `onNode(...)` at 546 is as load-bearing as the wait at 544; see § Design. |
| `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt:805-815, 926-932, 1060-1066` | The KDoc stating *why* focus is the disambiguator ("the composer never requests focus"). This is the contract prose the new tests turn into an assertion. |
| `docs/e2e-interactive-stream.md` | Context only. **AC-5 pins it as unmodified.** |

`codegraph_context` was run for this ticket and returned unrelated Noise-crypto symbols (`Curve448.add`, `Curve25519.evalCurve`) — no useful hits. The surface above came from a tree-wide grep for `isFocused|assertIsFocused|requestFocus`, which is exhaustive: the three `requestFocus()` sites above and the five e2e wait sites are the *entire* focus surface of the codebase.

## Correction to the ticket body

The body's Technical Notes and the PO comment both state that **"a `SaveAsChannelDialogTest` does not exist yet"**. It does. `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialogTest.kt` has been on `main` since `f2e7d36` (2026-05-17, "feat(ui/dialogs): Save as channel dialog (#142)") and holds 11 tests covering render, pre-fill, radio default, Save enablement, both submit paths and cancel.

This **shrinks** scope: the ticket needs **zero new files**. All three assertions are additions to existing classes. No AC changes meaning — AC-1 asks that each dialog "have an instrumented test asserting" the contract, which an added method in the existing class satisfies. Proceeding without a PO bounce.

## Design source

- **Rename Dialog** — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-14
- **Create Folder Dialog** — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-44
- **Save as Channel Dialog** — https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-24

All three are M3 `AlertDialog` surfaces on a dark rounded container: headline, a single full-width `OutlinedTextField`, and a trailing `Cancel` / confirm `TextButton` pair; Save-as-channel adds a two-option radio group below the field. **No frame draws a focused field** — Rename and Save-as-channel show a filled field with a floated `Name` label, and Create-workspace shows its label in the *resting* position, i.e. the state of an empty, **unfocused** field. Figma therefore cannot settle this contract, exactly as the ticket states; the frames are the design source for the surfaces under test and stay as drawn. **This ticket changes no visual output** — the deliverable is an assertion, plus a behavioural fix only if the assertion goes red.

## Context

Five `InteractiveStreamE2ETest` LIVE scenarios open a dialog over the thread and then wait for a focused editable field before typing. Four were observed timing out at that wait on two consecutive runs (2026-07-30); the fifth (`interactiveTurn_saveAsChannel_promotesToChannelTier`) joined the octet in `6b24f68` after those runs and carries the same exposure untested.

The wait targets focus deliberately: the dialog opens over the thread, whose composer is also editable, so `hasSetTextAction()` alone is ambiguous. Focus is the disambiguator, and it is not holding live.

**Nothing in the tree asserts that contract.** A grep for `isFocused|assertIsFocused|requestFocus` across `app/src` returns exactly the three production `requestFocus()` calls and the five e2e wait sites — no component-level focus assertion exists anywhere. The three dialogs implement the contract with one shared idiom, so a defect in one is likely a defect in all three.

Closing the gap is also the cheapest available discriminator between the two candidate causes ("client broke its contract" vs "harness is looking in the wrong place"): a component-level assertion isolates the dialog from the relay, the daemon, navigation and the composer, and costs no claude turns. The API-33 comparison is carved out to #615, which is written to read this ticket's recorded outcome.

## Design

### Shape: mirror the live wait, then mirror the live selection

Each of the three test classes gains **one** test. Both lines of the live sequence are reproduced, in order:

```kotlin
composeTestRule.waitUntil(2_000L) {
    composeTestRule.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
}
composeTestRule.onNode(hasSetTextAction() and isFocused()).assertIsDisplayed()
```

That is the whole assertion body. Three decisions in it are load-bearing:

**1. The predicate is copied verbatim from the live test, not paraphrased.** AC-1 requires satisfying "the same `hasSetTextAction() and isFocused()` predicate the live scenarios wait on". A paraphrase — `onNodeWithText(name).assertIsFocused()` — would assert a *related* property and leave the actual live predicate unmeasured, which is the failure mode this ticket exists to avoid. `and` is a `SemanticsMatcher` member; it needs no import, but `isFocused` and `hasSetTextAction` do (`androidx.compose.ui.test.*`).

**2. A bounded wait, not a bare assertion.** `requestFocus()` runs from a `LaunchedEffect`, and focus dispatch is not guaranteed to have settled at the moment the *parent* composition goes idle. A bare `assertIsFocused()` risks a red that reads "contract broken" when the truth is "focus landed one frame later" — and since this ticket's entire output is a verdict that #615 will act on, a false red is the worst possible outcome. The wait removes that class of false negative while still being the live shape.

**3. The bound is 2 s, not the live `THREAD_TIMEOUT_MS` (30 s).** Two reasons. A broken contract fails in 2 s per test rather than 30 s. More importantly it makes the result *informative*: the live wait already allowed 30 s and still timed out, so latency is not the live cause — anything slower than a couple of frames is a distinct finding worth reporting rather than quietly absorbing. Inline the literal at its single use per file (no shared constant — see § Rejected alternatives).

**4. The follow-up `onNode(...)` is not redundant.** `onNode` throws on multiple matches, so it asserts *uniqueness* — the disambiguation property the live scenarios actually depend on when they call `.performTextReplacement()` on that same selector at `InteractiveStreamE2ETest.kt:546`. Asserting only the wait would cover half the contract. This also sidesteps having to key the assertion on field text, which is awkward for `CreateFolderDialog`'s empty field.

### Per-dialog placement

| Class | Add to | Dialog invoked as |
| --- | --- | --- |
| `RenameDialogTest` | existing file | `RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})` |
| `CreateFolderDialogTest` | existing file | `CreateFolderDialog(onCreate = {}, onDismiss = {})` |
| `SaveAsChannelDialogTest` | existing file | `SaveAsChannelDialog(initialName = "New channel", onSubmit = { _, _ -> }, onDismiss = {})` |

Use each class's existing `setContent { PyrycodeMobileTheme { … } }` wrapper and its existing argument conventions verbatim — the arguments above are lifted from the sibling tests in each file. Name the tests in the established snake_case style, e.g. `field_reports_focus_once_dialog_composes`.

Each test carries a short KDoc naming what it guards: the live wait at `InteractiveStreamE2ETest.kt:544` (and 689 / 882 / 981 / 1145) depends on this, and the 2 s bound is deliberate. That comment is the only thing preventing a future reader from "simplifying" the wait into a bare assertion.

### Rejected alternatives

- **A shared assertion helper across the three classes.** It would need a new file (each test file's name is pinned to its single class by the ktlint filename rule), to save nine lines across three call sites. Three self-contained classes is the existing house shape. Rejected — Simplicity First.
- **A `private const val FOCUS_TIMEOUT_MS`.** One use per file; the KDoc carries the rationale better than a name would. Rejected as ceremony.
- **Asserting focus by node text** (`hasSetTextAction() and hasText("old name")` then `assertIsFocused()`). Doesn't work uniformly — `CreateFolderDialog`'s field is empty — and drops the uniqueness property. Rejected.
- **Reusing `THREAD_TIMEOUT_MS` (30 s).** Turns a broken contract into a 90 s test run and hides latency findings. Rejected.

## State + concurrency model

No production state changes in the expected branch. The relevant concurrency is entirely inside the dialogs and unchanged: `LaunchedEffect(Unit)` launches on the composition's coroutine context, `requestFocus()` is a synchronous call into the `FocusOwner`, and the resulting semantics change propagates on the next frame. The tests drive it through `createComposeRule()`'s default auto-advancing clock; `waitUntil` polls the semantics tree against the recomposition idle signal. No dispatcher, no `viewModelScope`, no repository, no relay.

## Error handling / failure branches

This ticket has exactly two outcomes, and **both are deliverables** — #615 is written to read whichever lands.

**Branch A — all three hold (expected).** Record on the issue, per AC-4, that the auto-focus contract is sound in isolation on API 35 / `Pixel_8`, and that the live stall therefore originates in the scenario context rather than the dialogs. State the per-dialog result explicitly (AC-2). No production change; the PR is test-only.

**Branch B — one or more fail.** Then AC-3 governs: the fix lands here, and the PR description quotes the **observed** failure — the actual exception and message, not a reconstruction — with the test shown red on the pre-fix tree and green after.

Diagnose before fixing; do not apply a remedy speculatively. The failure text discriminates:

- `IllegalStateException: FocusRequester is not initialized` — the `requestFocus()` call ran before the `.focusRequester(…)` modifier node attached. Note that all three `LaunchedEffect(Unit)` blocks sit in the **parent** composition, outside the `AlertDialog` call, while the field composes in the dialog window's **sub-composition**. Moving the `LaunchedEffect` inside the `text = { … }` slot alongside the `OutlinedTextField` puts effect and modifier in the same composition, which is the "one shape applied three times" the ticket anticipates.
- `ComposeTimeoutException` with no exception from the effect — `requestFocus()` ran without throwing but the `Focused` semantics never landed. Capture whether the field is focused *visually* (the M3 label floats and the indicator recolours) before choosing a remedy; a focused field that doesn't report focus is a different defect from one that never focuses.
- Assertion failure on the second line (multiple matching nodes) — focus works but the selector is not unique even with a single dialog on screen. That would invalidate the live disambiguation strategy itself and is a finding for #615, **not** something to fix by editing the e2e selectors here (AC-5).

Keep any fix to the minimum shape that turns the observed failure green, applied uniformly across whichever dialogs failed. Do not restructure the dialogs otherwise.

## Testing strategy

Instrumented (`androidTest`) — `isFocused()` requires a real focus owner and a real window; there is no JVM-unit equivalent. Per the repo's standing note, **the mandatory gates do not compile `androidTest`**: run `compileDebugAndroidTestKotlin` before assuming a green build.

Scenarios (one per class, as bullet points — the developer writes the code):

- **RenameDialog** — compose `RenameDialog` with a non-empty `initialName`; within 2 s exactly one node matches `hasSetTextAction() and isFocused()` and is displayed.
- **CreateFolderDialog** — compose `CreateFolderDialog` (field seeded empty); same predicate, same bound, same uniqueness. The empty seed is the point: it proves focus does not depend on the field having content.
- **SaveAsChannelDialog** — compose `SaveAsChannelDialog` with a non-empty `initialName`; same predicate. The uniqueness half carries extra weight here — the `text` slot also holds the radio group, so this is the one case where a sibling could plausibly match.

Verification, on the connected API-35 `Pixel_8` (a worktree has no `local.properties`, so `ANDROID_HOME` must be passed explicitly):

```bash
ANDROID_HOME=~/Library/Android/sdk ./gradlew compileDebugAndroidTestKotlin
ANDROID_HOME=~/Library/Android/sdk ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.components.RenameDialogTest,de.pyryco.mobile.ui.conversations.components.CreateFolderDialogTest,de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialogTest
```

Running the whole classes (not just the new methods) makes the 26 pre-existing tests a free regression check. **The FQCNs must be exact** — all three classes are in `de.pyryco.mobile.ui.conversations.components`; a wrong FQCN in a `class=` filter surfaces as an `initializationError` **failure**, not as a skipped test, so a green run with a typo is not possible but a confusing red is. Do not pipe the Gradle invocation through `tail`; it masks the exit code.

Then the standard gates: `test`, `lint`, `assembleDebug`, `spotlessCheck`.

## Scope guard (AC-5)

`InteractiveStreamE2ETest.kt`, `scripts/e2e-emulator.sh` and `docs/e2e-interactive-stream.md` must be unmodified. Verify deterministically before opening the PR:

```bash
git diff --name-only origin/main...HEAD
```

None of those three paths may appear. In Branch A the changed set is exactly the three `androidTest` files plus this spec; in Branch B, plus the dialog(s) fixed.

If the measurement suggests the live selectors are wrong, that is a **finding to record**, not a change to make here — adjusting live selectors before the contract is measured is precisely what the original report warned against.

## What this measures — and what it deliberately does not

Worth stating for #615, which consumes this result. The component test isolates the dialog from the relay, the daemon, navigation, and the thread composer. It does **not** reproduce one property of the live environment: with a dialog open over the thread there are **two Compose roots** (the thread window and the dialog window), whereas the component test has one.

So Branch A ("contract sound in isolation") narrows the live suspects to context that a single-root test cannot exercise — multi-window semantics-root selection, window focus on a headless emulator, or the composer holding focus contrary to the KDoc's claim. Branch A is therefore evidence that the client's contract is intact, **not** proof that the harness is at fault; naming that limit in the recorded comment is what makes the result usable rather than merely reassuring. Leave the multi-root question to #615 — it is out of scope here and pinned shut by AC-5.

## Open questions

- None blocking. The one judgement call the developer owns is the Branch B remedy, which is deliberately deferred to the observed failure text rather than pre-chosen here.

## Size check

Not split. Every red line is clear by a wide margin, and the `SaveAsChannelDialogTest` correction moves it further clear:

| Red line | This ticket |
| --- | --- |
| > 3 new files | **0 new files** — all three test classes exist |
| > ~600 LOC total written | ~45 LOC expected (3 × ~15 incl. KDoc); ~60 worst case with a fix |
| > 5 new exported types / composables | **0** |
| > 10 consumer call sites | **0** — additive test methods, no signature touched |
| > 5 AC of work | 5 AC, but AC-3/AC-4 are mutually exclusive branches of one measurement and AC-5 is a negative assertion verified by one `git diff` |
| ≥ 10 reject branches | **0** |

Production source files with new or modified content: **0** expected, **≤ 3** worst case — under the ≥ 5 commit gate either way.

Size stays `s` rather than dropping to `xs`: the instrumented device run and the conditional fix branch are real turn cost beyond a pure test addition. No upward revision is available or needed.

**File-overlap check:** `git fetch origin --prune` then a branch-diff scan of all 18 `origin/feature/*` branches against the six candidate paths (three test files, three dialogs) — **no overlap**. No blocker wired.

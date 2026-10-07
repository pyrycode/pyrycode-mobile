# Selection Copy recurrence evidence (#1931)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/FinishedReplySelection.kt`: `assertFinishedReplySystemCopy` and `assertSelectedWordOnClipboard` retain no clipboard outcome on timeout.
- `app/src/androidTest/java/de/pyryco/mobile/FinishedReplyClipboardTest.kt`: delayed replacement, invalid attribution and negative clipboard controls use the real device service.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_finishedReply_systemCopyCopiesSelectedWord` requests the finalized known reply.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_systemCopyCopiesSelectedWord` shares the assertion.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/FocusRecordListener.kt`: failure-listener focus records happen after activity teardown and cannot establish focus during Copy.
- `docs/knowledge/features/message-bubble-testing.md`: real toolbar selection, activity clipboard attribution and the historical evidence boundary.
- `docs/specs/architecture/1854-selection-copy-reliability.md`: synchronous native writes invalidate the previously hypothesized suspend-dispatch explanation.
- `docs/e2e-interactive-stream.md`: “Finished-reply partial Copy (#1674)” requires measured selection, one platform Copy and independent exact clipboard checks.
- `scripts/android-test-gate.py`: retains per-test logcat and counted XML for scripted and live gates.

## Context

The retained #1918 full gate XML reports 65 executed, 2 failed, 0 skipped. Its stderr reports a 90000 ms timeout after the Copy action in the selected-word result fence. The paired rerun reports 2 executed, 0 failed, 0 skipped, including this method, on mobile revision `38048ef4fd3af2a0cc04f365fc15e262342e0cc3`. Evidence files are `$AGENTS_REPO_PATH/logs/2026-10-07T20-49-22-497Z_real-claude-gate{,-rerun}_#1918.{log,stderr.log}`. The original gate worktree is no longer present. The retained evidence does not identify the selected range, clipboard value or focus at the click; a post-teardown DESTROYED activity is not evidence of focus loss then.

The demonstrated defect available for repair is the loss of actionable evidence: all incorrect clipboard outcomes yield the same generic timeout. The issue explicitly permits bounded diagnostics when retained evidence cannot establish the trigger. Do not claim to have repaired the historical platform trigger without a reproduction. No production or visual changes, new dependency or decision record.

Overlap: remote #1766 adds only the older result fence already superseded by #1854; it is not a dependency. Changes remain local to the two test files.

## Design

Keep the finalized assistant gate, unrelated independently verified baseline, layout-measured long press on `cobalt`, real Espresso platform popup and exactly one Copy action. Add content-free bounded observations at the measured press, immediately before and after the actual ViewAction, and on clipboard timeout. Record body/press and menu screen geometry, activity window focus and lifecycle flags at those checkpoints, and any publicly available text selection range (explicitly unknown when absent).

Read each clipboard snapshot once on the UI thread. Classify absent clips, zero items, non-text items, unchanged baseline, exact expected word, whole reply, other spans of the known reply and other text. Record item count and text length; unknown text gets a hash, never raw contents. Retain at most a small fixed number of outcome transitions plus observation count and the last result. Timeout includes these diagnostics and preserves its original exception as cause. Do not retry selection or Copy, increase the timeout, substitute services/providers, accept incorrect values or catch clipboard exceptions.

Estimated total written work: about 450 lines including plan and device regression edits; zero exported product types, no simultaneous consumer migration, four criteria and no new state machine. Both e2e callers remain unchanged. Codegraph returned no caller edges; source search confirms these callers and the existing device controls.

## State and concurrency model

Diagnostics are test-local, collected synchronously on the UI thread with the existing Compose rule and Espresso ViewAction. Clipboard polling uses the existing deadline and yields between observations. Only the first bounded transitions and latest snapshot survive, so a 90-second failure cannot flood logcat. No new coroutine job or production state.

## Error handling

Wrong outcomes remain failures. Only ComposeTimeoutException is enriched and rethrown with its original cause. Clipboard and platform click exceptions propagate. Failure diagnostics are obtained before activity teardown; no shell dumps, daemon text or unrelated UI text are logged.

## Testing strategy

First extend the real-device negative controls to require distinct, bounded diagnostic messages for unchanged baseline and whole reply; watch those fail against the current helper. Add missing/non-text/other-word/long unrelated text cases and verify privacy, final-state capture and bounded history. Retain delayed replacement and attribution controls. Real Android service reads and writes require device-only coverage rather than the JVM clipboard shadow.

Run the affected device class and scripted `selection-copy`, inspecting fresh XML executed/failed/skipped counts and the named deterministic method. Run existing `MessageBubbleSelectionTest`, lint, assembleDebug, Android-test compilation and forced formatting checks. After the final main merge, push, run the full unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle`.

Dispatcher handoff: a fresh full live suite (`all`) must include and pass `InteractiveStreamE2ETest#interactiveTurn_finishedReply_systemCopyCopiesSelectedWord`; record full executed/failed/skipped counts separately from deterministic proof. A recurrence must be investigated using the new in-step evidence before claiming its cause or applying a speculative repair.

## Open Questions

- Historical trigger remains unestablished by retained evidence; diagnostics are the authorized evidence-insufficiency path, not proof of a platform repair.
- Resolved: the finished selectable body exposes no public `TextSelectionRange` in the passing scripted and focused live runs. Record `range=unknown`, and retain actual press geometry and observed clipboard outcome rather than invent selection offsets.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/message-bubble-testing.md`, “Testing”, and `docs/e2e-interactive-stream.md`, “Finished-reply partial Copy (#1674)”, with the evidence boundary, bounded diagnostic fields and counted deterministic and dispatcher-produced full live evidence. No historical cause claim without demonstrated evidence.

## Revisions

### 2026-10-08 — distinguish popup root geometry from screen geometry

Inspection of the passing scripted trace shows Android's `getGlobalVisibleRect` uses the popup root coordinate system, so that rectangle cannot be compared directly with the body window rectangle. Record the menu's actual screen rectangle through `getLocationOnScreen` as well as its root-visible rectangle, and name the body coordinate system explicitly. Record the menu's shown/visible state after the click too, so a recurrence can distinguish a still-open menu from one dismissed without the expected clipboard result. The platform click itself stays unchanged. The focused live reproduction with the two preceding scenarios passed all three selected methods; it does not establish the historical trigger or replace full dispatcher live acceptance.

### 2026-10-08 — visibility alone is not dismissal evidence

The final merged-branch scripted pass recorded `shown=true` and `visible=true` after Copy while the exact selected word was on the clipboard. Withdraw the interpretation that these flags alone distinguish an open popup from a dismissed one: a retained View tree can still report them. Add `isAttachedToWindow` to both menu checkpoints. Record these as API observations, not proof that a callback ran or a popup was dismissed; the exact clipboard assertion remains the Copy success contract.

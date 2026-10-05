# #1703 — Identify the remaining question-answer timeout

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_questionAnswer_reachesTheAskingConversation`, `answerHostPeer`, `answerChat`, `awaitInlineQuestion`, `awaitNoInlineQuestion`, `awaitTurnEnd` and `peerStep` map the scenario's blocking operations.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `awaitQuestion`, `awaitQuestionDismissed`, `answerQuestion` and `awaiting` retain bounded waits and record received frames.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerDeviceKeyStore.kt`: the host/token identity survives peer recreation; #1686's repair is already present.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/PeerWait.kt` and `app/src/test/java/de/pyryco/mobile/e2e/PeerWaitTest.kt`: timeout diagnostics retain the original cause and do not extend deadlines.
- `docs/e2e-interactive-stream.md`: question-answer scenario and #1686/#1702 evidence distinguish admission, Compose and coroutine failures.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: deleted gate worktrees can remove the per-test logcat; retained stderr and isolated daemon logs remain usable.
- `scripts/android-test-gate.py`: the dispatcher captures fresh reports and logcats; the builder must not run the live suite.

## Context

The retained #1753 branch stderr reports a bare `TimeoutCancellationException` after 30000 ms on mobile `51dcf02e2b9e869b3c763ea835aa149f26d8b6fa`, daemon `65df98859f32e49ba59a42c4446d650b7625cf62`, Claude 2.1.280. Its base reports the same unnamed timeout on mobile `c77d9ca1a1e3a4e7ae1ae5f5f3f4ebe3bc0bfc55`, with the same daemon and Claude versions. Both app trees contain #1702's actions reveal and #1686's retained pairing identity.

The branch report is `2026-10-04T20-06-38-354Z_real-claude-gate_#1753.stderr.log`; the base is its `real-claude-gate-base` counterpart under the agents repository's `logs/`. The branch's retained `pyry-e2e.LVvrhI/daemon-answer.log` contains eight handshake accepts and no `static_key_mismatch`, `bound_to_other_key` or handshake-reject records. The removed gate worktree prevents correlating per-test logcat with the daemon log. These artifacts do not identify the failed operation. No remaining cause is asserted.

## Design

First make the failing wait identifiable, as the ticket explicitly requires when retained artifacts are insufficient. Add a test-only `QuestionAnswerStage` enum and an inline `questionAnswerStep(stage, linkState, block)` wrapper. Wrap setup, peer waits, phone selection/submission, disappearance checks and completed-turn waits in the named scenario. On coroutine or Compose timeout, report the fixed operation label and lazily read content-free peer link state, retaining the original exception as cause. Other failures and ordinary cancellation pass through unchanged. No payloads, ids, tokens, paths or answer labels enter the diagnostic.

The enum and wrapper live in `app/src/sharedTest/java/de/pyryco/mobile/e2e/QuestionAnswerStage.kt`, with pure JVM regressions in `app/src/test/java/de/pyryco/mobile/e2e/QuestionAnswerStepTest.kt`. A local adapter in the existing device scenario supplies `peer::linkState`. No ViewModel, UI state or event shape changes.

All waits retain their existing deadlines. Keep the scenario enabled, #1702's actions reveal, phone selection, remote/answered assertions, chosen-label-only reply, peer dismissal without a phone tap and both completed turns. No retries, production code or question UI changes are planned.

Overlaps: #1642, #1682, #1689, #1690, #1691, #1693 and #1695 touch other scenario bodies; the named-method edit and separate diagnostic file are local and independent.

Forecast: about 250 written lines including this plan, three test/harness files, one internal enum, no consumer signature changes, three acceptance criteria and two diagnostic timeout branches. This is within the ticket's estimate and builder limits. No decision record is needed.

## State and concurrency model

The synchronous inline wrapper owns no state, jobs, dispatchers or flows. It delegates to the existing operations on the test thread; their coroutine deadlines and Compose waits remain authoritative. `SecondClientPeer` retains its IO scope, recorded-frame StateFlow, redial supervision and cancellation through `close` in the scenario's `finally`. Phone connection lifecycle behavior is unchanged.

## Error handling

Raw `TimeoutCancellationException` and `ComposeTimeoutException` become an `AssertionError` identifying the operation, with the original throwable as cause. Existing helpers that already convert timeouts into named assertion failures retain those failures unchanged. Successful values, refusals and ordinary cancellation pass through without evaluating diagnostics. Failure text contains only test-authored stage labels and the peer's existing content-free status.

## Testing strategy

Write virtual-time regressions first against a pass-through diagnostic seam: each stage must identify an unanswered operation at the unchanged deadline, retain its timeout cause and inspect state only on failure. Check successful values, refusal and ordinary cancellation propagation, and a Compose timeout. Run these alongside `PeerWaitTest`, `PeerDeviceKeyStoreTest`, `RedialingLinkTest` and `LiveConnectionReadsTest`. Run lint, assembleDebug, compileDebugAndroidTestKotlin, formatting and one zero-Claude scripted scenario. The existing live scenario requires a real emulator, peer, daemon and Claude; the builder cannot execute it in isolation without the dispatcher harness.

## Open Questions

The underlying remaining flake is unresolved. A diagnostic-only pass is not proof of its repair. The dispatcher must retain the fresh full live run's stderr, XML and failing per-test logcat. If it fails, the operation label determines the repair and focused regression on builder re-entry. If the cause is upstream, file/link its owning issue rather than changing mobile assertions. A successful same-tree rerun is insufficient acceptance evidence.

## Live evidence handoff

Pending dispatcher execution: fresh full `python3 scripts/android-test-gate.py live`, executed/failed/skipped counts and explicit named-method execution/result. Preserve `needs-real-claude` and use `needs-live-artifacts` to return the captured evidence to the builder. On re-entry, record sanitized operation evidence and app/daemon revisions in this plan's Context/Revisions and on #1703, implement the implicated repair with its red/green regression, and rerun focused checks. Do not close out the flake based on instrumentation alone.

## Documentation handoff

Pending for the documentation stage after diagnosis and repair: `docs/e2e-interactive-stream.md`, question-answer scenario and Verification status; `docs/knowledge/features/development-verification-emulator-evidence.md`, Emulator and real evidence. Describe the identified operation, diagnostic labels and fresh live counts without copying private logs.

## Revisions

### 2026-10-05 — Repair the occluded phone answer target

The dispatcher’s fresh full gate on mobile `55e6b11f477fa25ce1e5cb824b0b6ac4d6f988c4`, merged with main `6bd48bc3cb52deb744c7522ba019bc2dffba8903`, ran 53 methods: 51 passed, 2 failed, 0 skipped. The named question-answer method failed at `AwaitPhoneDismissal`: `await phone answer's question_dismissed on peer`, after 30000 ms, with `session open (link 1, replaced 0×)`. The base comparison on main ran this method alone and failed (1 executed, 1 failed, 0 skipped), with an unnamed 30000 ms coroutine timeout. Both used daemon `65df98859f32e49ba59a42c4446d650b7625cf62` and Claude 2.1.280. The separate stop-running-turn failure passed on a same-tree rerun and is outside this ticket.

Evidence sources are the retained `2026-10-04T23-15-21-659Z_real-claude-gate_#1703.stderr.log` and its base counterpart. The branch answer-daemon log shows both handshakes accepted and no resolved question answer before teardown. The gate worktree and copied per-test phone logcat are absent, so those records do not establish the historical tap coordinates or distinguish an unsent answer from a daemon no-op on their own.

The focused short-thread reproduction establishes a mobile harness failure on the same submit path: after selection and the scenario's actions reveal plus final `performScrollToNode`, enabled Continue has bounds `(170, 443)-(228, 494)` with composer top `461` in the JVM fixture. Its tap center is `468.5`, inside composer chrome; an actual pointer tap emits no Continue event. One test executed and failed both on the missing submit event and on the explicit geometry assertion before the fix. This is the implicated test behavior repaired here; attribution of the retained live occurrence to this geometry is an inference, to be checked by the next fresh full live gate.

Additional files read:

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModalTest.kt`: `readableTextNode` and `large_text_actions_stack_and_pointer_edges_submit_only_this_batch` already account for the list drawing beneath header/composer chrome. Mirror that test-only positioning contract.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadInlineQuestionTest.kt`: `selected_option_can_reach_continue_when_the_actions_row_is_uncomposed` covers #1702's reveal, but its long empty-thread fixture does not expose the short-thread occlusion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadMessageList` uses the full drawing viewport; the header and composer overlays delimit the usable pointer region.
- `docs/knowledge/features/question-batch-modal.md`: inline placement and prompt protection stay unchanged.

Add `ComposeTestRule.questionAnswerTarget` in shared test code. It scrolls to the requested control, measures header and composer bounds, applies one `ScrollBy` adjustment when needed, and asserts that the pointer's center is within that readable band. It returns the target for a real pointer tap at that center. It neither invokes the control's semantic click nor retries a tap. The live scenario uses it for phone selection and Continue; #1702's container reveal and enabled wait remain. The short-thread regression uses this same helper and verifies selection, readable Continue tap geometry and exactly one submit event with the held generation. The list's newest resting position can leave a button edge beneath chrome, so requiring its full rectangle to clear chrome is unnecessary; the actual tap must clear it. No production code, UI design, wire fields, timeout or existing assertions change.

The remaining failure is later than #1702's enabled-Continue Compose wait, which already succeeds in the captured run. It is also later than #1686's identity fault: the peer open completed, the session stayed open with no replacement, and both retained daemon logs show accepted handshakes rather than key-binding rejects. Neither earlier repair is reverted or duplicated.

Updated forecast: roughly 450 written lines across the original diagnostic change, this revision, the positioning helper and regression; two internal test seams and one enum, no production files or signature migration. Overlaps #1735 and #1775 add separate live methods; the earlier listed overlaps remain local to other scenarios. No dependency blocks these edits.

Validation: run the new regression with the existing inline-question class and the original diagnostic/peer unit classes, compile device tests, lint, assemble and forced formatting checks. Run the new shared method on the managed device, plus one zero-Claude scripted scenario. The original long-fixture method's device tiebreaker stops before the tap at its existing uncomposed-row assertion; that Android/Robolectric composition difference is outside this repair and its JVM coverage remains unchanged.

The diagnostic-only handoff is superseded by this causal harness repair and committed sanitized operation record. After verification the dispatcher must run the fresh full live suite, record executed/failed/skipped counts and explicitly confirm the named method ran and passed. No builder claim is made that the fresh live acceptance already passed, and a same-tree rerun remains insufficient.

# #1830 — Reveal Stop task on open running rows

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt`: `TaskRow`, `TaskGroups`, `boundedText` retain grouping, complete text visibility and inert 4096-character rendering.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `backgroundTaskReading`, `state`, `onOverflowEvent` own destination-local reads and actions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt`: `ThreadUiState`, `ThreadEvent` carry task controls through the existing screen contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `BackgroundTaskPanel` hosting forwards state and events.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt`: `ThreadDestinationFactory.thread` captures the exact host bundle; demo stays unsupported.
- `app/src/main/java/de/pyryco/mobile/data/repository/BackgroundTaskStops.kt` and `RelayRepositoryCoordinator.kt`: `stopBackgroundTask`, `supportsBackgroundTaskStop`, `observeBackgroundTaskStopRefusals` are the merged #1829 contract; refusal values are correlated originating task keys.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ModelRefusalRow.kt`: `SwitchBackAction` supplies the small secondary surface and independent touch routing pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`: existing `ic_composer_send` is the exact round chevron asset.
- `app/src/main/res/values/strings.xml`: background-task labels and accessibility copy.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelBackgroundTasksTest.kt`: existing roster/count coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt` and panel layout/spacing/inset tests: read-only fixtures retain compatible defaults.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `allowPromptsUntil`, `runningToolPeer`, `openBackgroundTasks` provide isolated live staging.
- `scripts/e2e-emulator.sh`, `scripts/background-agent-fixture.py` and `DeterministicInteractiveStreamE2ETest.kt`: existing fixture lifecycle and curated selectors.
- `docs/knowledge/features/mobile-modal-callers.md`, Callers → BackgroundTaskPanel: keep untrimmed line boxes, distinct cut markers, plain text and roster omission behavior.
- `docs/specs/architecture/1041-background-task-panel-redraw.md` and `1295-background-task-panel-figma.md`: preserve card tokens and compact-width handling.
- `docs/e2e-interactive-stream.md`: rung-3/4 harness contracts.
- Sibling `pyrycode/docs/protocol-mobile.md`, Stop background task and Security model: wire source of truth, no success reply.
- Sibling `pyrycode/docs/knowledge/features/e2e-realclaude-roster-after-finish-capture-test-go.md`, Stop completion needs a held task and all terminal signals: join exact tool input/start metadata with the latest retained roster; omission can precede a terminal update.

In-flight overlap: #1682, #1689, #1690, #1691, #1693, #1695 and #1766 edit other live methods; #1766 also appends its curated selector. These are local additive changes, with no shared-block redesign or dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-890

Also inspected capped closed rows `568-932`, interaction notes `801-11729` and small Secondary buttons `347-6645`. The card keeps its 14/12 dp padding, 8 dp content gaps and existing theme roles. A 28 dp round send chevron follows the status tag with an 8 dp gap, rotated 180 degrees when closed. Opening adds only the small outlined Stop task surface at the foot: background/primary, modal-control shape, 16/7 dp padding and emphasized bodySmall text. The row text is one merged focus target; Stop task is a separate button.

## Context

Running background tasks outlive the current reply. A row tap must only reveal controls, never stop work. The composer interrupt stays separate. #1829 is merged and supplies destination-bound capability, local-send result and task-correlated refusals. No wire change or new dependency is needed.

Sizing: one verifiable behavior, five acceptance criteria, approximately 1200–1450 written lines including plan, UI/VM tests, live staging and deterministic twin; two new event declarations and private helpers, no required consumer migration (compatible defaults). Below 1600 lines, five exported declarations, ten simultaneous consumer updates and ten reject branches. Recount before committing implementation.

## Design

Add `BackgroundTaskToggle(taskId)` and `BackgroundTaskStop(taskId)` to `ThreadEvent`, with redacted string representations. Add supported, expanded-id and pending-id fields to `ThreadUiState`. A private ViewModel task-control reading holds the authoritative roster/count plus these sets. The destination factory injects the captured coordinator's capability flow, conversation-filtered refusal flow and stop function.

`TaskGroups` passes eligibility and per-id state to `TaskRow`. Finished and unsupported rows remain inert. Eligible row content receives a toggle click and client-owned expanded/collapsed state description. The 28 dp icon is decorative inside that merged node. Stop task is outside the toggle's clickable area and merged text subtree, with a minimum 32 dp visible surface and independent button semantics. Foundation's expanded touch target must not route to row toggle; test surface and neighboring edge taps. Closing the modal retains state; task identity changes do not transfer it.

## State and concurrency model

Collect roster, live count and support eagerly for the ViewModel lifetime, even while the screen is not subscribed. Reconcile expanded and pending ids to eligible running ids on every reading, clearing on missing roster/disconnect or lost support. Observe refusals before any send; remove only that task's pending marker and leave roster/expansion unchanged.

Use a synchronous guard and pending insertion before launching a `viewModelScope` send job. Keep an attempt identity per task so an older local failure cannot clear a newer attempt after cleanup/reappearance. Recheck that attempt before sending; finish/removal invalidates queued work. All mutation is serialized under one local lock; no lock crosses suspension. Jobs cancel with the ViewModel. No disk persistence, new dispatcher or connection ownership. Existing lifecycle driver and coordinator invalidate the owning connection.

## Error handling

Unsupported, closed, finished, missing and already-pending actions send nothing. `Result.failure` from the transport clears only its own pending attempt; cancellation propagates. A correlated refusal re-enables only its originating task. Neither outcome rewrites the roster or invents a terminal tag. Terminal updates and omission remain authoritative. Structured lifecycle logs contain static event/code and booleans only, never ids, descriptions, commands, error messages or payloads.

## Testing strategy

- Test-first focused ViewModel cases: eligibility, independent expansion, synchronous duplicate guard, pending through collapse/reopen, matching refusal, local failure, stale attempt, terminal/removal/null-roster and capability-loss cleanup; destination isolation with equal ids on separate hosts. Retain existing roster/count tests and destination factory coverage.
- Robolectric screen tests in `app/src/sharedTest`: closed/open content, independent rows, unsupported/finished rows, merged semantics and state description, separate disabled button, visible 28 dp chevron and 8 dp gap, real pointer surface/edge routing. Run existing panel tests and relevant thread task-pill coverage.
- Add the named rung-3 method to `InteractiveStreamE2ETest` and curated full live list. Use a unique scenario-local endpoint in the existing host fixture, held until explicit teardown release, with arrival status. Exact Bash command and `run_in_background=true` identify its launching tool id; start metadata and latest retained roster join the held task without description matching. Require arrival, live roster membership, no prior terminal event, open row and phone Stop tap. From the pre-tap frame boundary require matching stopped update or roster omission, verify phone running row/count gone, then a real same-conversation reply. Release in finally even on failure. This is device-only because it requires real daemon/Claude/network and phone actions. Dispatcher owns the full live run and its executed/failed/skipped counts.
- Add a rung-4 scripted held-task scenario using the existing fakeclaude stop-control behavior, so phone stop drives terminal/removal without a second-message release. Run that focused scripted scenario and inspect fresh counts.
- Run focused units/shared tests, lint, assembleDebug, compileDebugAndroidTestKotlin and forced Spotless. Commit/push before final main merge checks; run whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` after the last merge.

## Open Questions

None. Live execution remains an explicit dispatcher handoff, not a claimed pass.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/mobile-modal-callers.md`, Callers → BackgroundTaskPanel, record eligible expansion, independent pending state and authoritative removal. `docs/e2e-interactive-stream.md`, live coverage/evidence, record the named stop-background-task method's dispatcher full-suite result including executed, failed and skipped counts. Documentation records evidence and does not execute the live test.

## Security review

**Verdict:** PASS

- [Trust boundaries] Eligibility comes from decoded roster state and echoed connection capability. `TaskField`/`boundedText` remain the only daemon-text render path. Opaque ids route lookups only; descriptions never identify stop targets.
- [Tokens, secrets] No credential access, generation or storage changes. Neither event string representation nor logs expose routing ids or text.
- [Files and storage] Controls are memory-only; no daemon-derived paths or persisted state. Live fixture endpoint uses a test-generated numeric nonce, separate from daemon fields.
- [Android attack surface] No manifest/exported component, intent, WebView or clipboard changes. Separate button/toggle targets prevent a scrolling row tap from stopping work.
- [Cryptography] Existing Noise session and Keystore boundary remain unchanged; no new primitive or secret comparison.
- [Network and I/O] Reuse #1829 capability recheck and correlated refusal contract; no reply timeout pretends success. Held fixture is loopback-only, test-owned and released during teardown.
- [Errors, logs, telemetry] Static lifecycle codes only, debug logging through RelayLog; do not log Result exceptions, ids, daemon strings or commands.
- [Concurrency] SHOULD FIX: stale failure after task removal/reappearance must not release the new attempt. Per-attempt identity checks plus synchronous locking enforce this; test the race. Eager cleanup and pre-send identity recheck prevent queued sends to removed tasks.
- [Threat model] Malicious relay delay/drop cannot manufacture completion or expose plaintext; missing completion remains pending until authoritative disconnect cleanup. Hostile daemon text remains bounded inert text. Disk token theft is unchanged and owned by existing Keystore policy. Accessibility exposes display text and enabled state but no ids; existing screenshot/overlay policy is unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-06

## Revisions

- 2026-10-06: The fakeclaude replay path does not populate its stop-control lookup. The deterministic twin uses its existing canned-roster rider (one retained task, no natural finish) and existing stop_task handler instead. No sibling code or new fake flag is required. The live hold uses a keyed loopback fixture with explicit arrival/status and teardown release; the fixture has no natural release timer.
- 2026-10-06: Pointer coverage includes the card padding. Make the entire card the merged toggle target, with the stop button as a nested independent clickable semantics node (Compose excludes that node from the parent's merge). Enabled and disabled button surface/extension taps must never bubble to the card. This replaces the narrower text-subtree toggle so every row tap opens the row.
- 2026-10-06: The full-card nested approach failed the disabled-button extension probe: its tap reached the ancestor toggle. Keep a separate toggle block that includes the closed card's padding and all text; an open button sits outside that block with an 8 dp gap accommodating its touch extension. This preserves read-only merged bounds and prevents both enabled and disabled button taps from toggling.
- 2026-10-06: Extend the existing destination-factory collision test to exercise the real #1829 sends/refusal through AppModule. Add one synthetic device capture to BackgroundTaskPanelCaptureTest for the open-row screenshot; real pixels are the device-only reason. No existing capture expectations change.
- 2026-10-06: Implementation recount is approximately 1400 added/deleted source, test, script and plan lines, with two new event declarations, compatible defaulted consumers and seven guarded action/cleanup branches. The open-row emulator capture agrees with the Figma reference; preserve the synthetic image and metadata as review evidence.
- 2026-10-06: Verifier rework: `BackgroundTaskStopPanelTest` captures the composition density and converts chevron bounds and the tag gap from pixels to dp before using `assertDpEquals`. Pixel-snapping tolerance preserves exact density-1 checks and supports the managed Pixel 2's density 2.625; no product contract changes.

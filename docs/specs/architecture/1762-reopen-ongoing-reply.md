# Reopen an ongoing reply without replaying its prefix (#1762)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `arriveInSeededThread`, `typeAndSend`, and `interactiveTurn_seededChannel_toolStepRunsThenCompletes` establish the isolated held-turn pattern.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: conversation creation, naming, navigation and live repository helpers.
- `scripts/e2e-emulator.sh`: scenario selection and `DROP_B_FENCE` release after the second `send_message.enqueued`.
- `scripts/android-test-gate.py`: `SCENARIOS`, curated live selection and counted XML evidence.
- `scripts/e2e-fixtures/stream.jsonl`, `tool-open.jsonl`, `tool-done.jsonl`: raw stream-json fragments and result-based completion.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `AssistantMessage` initial reveal, `StreamingAssistantBody`'s stable 33 ms clock and bubble semantics.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadStreamingRevealTest.kt`: paused composition-frame assertions and sufficiently long prefixes.
- `docs/specs/architecture/1753-streaming-reveal-on-thread-open.md`: shipped opening-time contract, including #1754's word reveal merge.
- `docs/knowledge/features/thread-screen.md`, `streaming-assistant-turns.md`, `message-bubble-testing.md`: first arrival versus reopening, retained prefixes, and the approximately 495 ms catch-up budget.
- `docs/e2e-interactive-stream.md`: What rung 3 is made of and Deterministic mode (rung 4), the harness seams and evidence limits.

## Context

The shipped local rendering assertions do not prove navigation through the real daemon/Noise/relay stack. This ticket adds one reopen observation with a deterministic twin. No decision record is needed.

## Design

Add `interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately` and register `reopen-stream` in the harness and `SCENARIOS`. Fragment A contains a long plain assistant prefix and no turn-ending result. Await that exact streaming repository row and its displayed prefix, leave for the list, and verify the old reply unmounts. Pause Compose time before reopening the same seeded channel. Observe the first streaming body (bubble-scoped caret semantics) with a bounded composition-frame budget smaller than reveal catch-up, then immediately assert the entire prefix is displayed once. Assert the row remains streaming and the turn phase remains non-idle at this checkpoint, with no suffix present.

Only after that checkpoint restore clock advancement and send the second inert prompt. The existing enqueue fence releases fragment B with a suffix and completion. Require the combined reply, its original prefix and exactly one final assistant row. Fixtures contain no prompt or title text that could satisfy the reply assertion. No production state, signatures or visuals change.

Add `interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately` to the real-Claude class. Capture a substantial non-empty plain-text assistant prefix, navigate away and reopen with the same paused-clock observation, checking streaming state and non-idle phase before and after the displayed assertion. This scenario ships `@Ignore`: Claude can finish during navigation, and freezing Compose time cannot hold the backend. A longer prompt reduces that race but cannot fence it. A completed reply explicitly fails the manual test. Exclude the ignored method from the curated live list; record manual, unproven live coverage. Manual promotion requires un-ignoring, running the named live method with nonzero executed/zero failed/zero skipped counts, and establishing an open turn at the checkpoint.

Overlaps #1682, #1689, #1690, #1691, #1693, #1695, #1729, #1766 and #1827 affect separate scenarios or additive selection entries; preserve their blocks and keep edits local. Forecast: approximately 350–550 written lines including plan and evidence, zero production files, no new exported production types or updated consumers, three acceptance criteria and no production reject branches; within every sizing limit.

## State and concurrency model

Existing activity, repository and ViewModel lifetimes remain unchanged. Test reads use bounded `runBlocking`/flow awaits; real network work continues while Compose's reveal clock is paused. Frame stepping permits composition without unbounded reveal catch-up. Restore automatic clock advancement in `finally`. Fragment B cannot arrive before the test's explicit second send.

## Error handling

Timeouts, absent/finished prefixes and idle turns are assertion failures, never passing or skipped evidence. Only the explicitly ignored live method remains manual. No production error handling changes.

## Testing strategy

Device-only because these tests exercise a real emulator, asynchronous Noise/relay I/O and host daemon child streaming that Robolectric cannot supply. Write the scenario and fixtures first. Temporarily replace only `StreamingAssistantBody`'s initial state with the pre-#1753 zero initialization, run focused `scripted reopen-stream`, and require failure at the immediate-prefix assertion. Restore the exact production source before the current-code run and require one executed case, zero failures/skips. No production modification lands.

Run the current-code focused scenario, existing `ThreadStreamingRevealTest`, Python harness/gate tests, lint, assemble, androidTest compilation and Spotless. After the last main merge and push run the whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle`. Read fresh counted device XML. Full scripted/device suites and any later live execution belong to the dispatcher; the ignored live method supplies no passing live evidence.

## Open Questions

None. The real-Claude transient window is deliberately manual under the issue's permitted fallback.

## Documentation handoff

Pending for the documentation stage: update `docs/e2e-interactive-stream.md`, “What rung 3 is made of” and “Deterministic mode (rung 4)”, with both method names, `reopen-stream`'s prefix-without-result / second-send-release / suffix-and-result sequence and observed counted evidence. Document the ignored live transient-window reason and manual un-ignore/named-run procedure. Documentation records supplied evidence and does not generate live proof.

## Verification evidence

2026-10-06: replacing only `StreamingAssistantBody`'s initial reveal value with zero executed the selected `reopen-stream` method and failed its first reopened full-prefix display assertion: 1 executed, 1 failed, 0 skipped. Evidence: `build/dispatcher-tests/scripted-59mzwb_6/dispatcher.xml`. The exact production source was restored immediately; no production diff remains. The earlier two device-busy exits executed no cases and are not evidence. Current-code focused and final gate results are recorded in the PR.

## Revisions

2026-10-06 — Verifier finding 1: a suffix and terminal result delivered together let finalization mask a temporary prefix reset. Keep reveal time paused through the second send and suffix arrival. Await the repository's combined text, require the same row to remain streaming and its turn non-idle, then observe the first appended word in the reply bubble within 128 ms of composition frames and assert the full original prefix remains displayed once. The appended word witnesses that the updated body actually composed; repository content alone is insufficient.

The existing second-enqueue fence still releases fragment B. Because fakeclaude supports two replay fragments, a scenario-local test-only stdout wrapper forwards the suffix unchanged but holds its raw terminal `result` until a separate third-enqueue signal. Only after the suffix-arrival display checkpoint does the test restore automatic clock advancement and send the third inert prompt, releasing completion. The wrapper owns and cleans up only its fakeclaude child; the isolated daemon identity and production code remain unchanged. Test the wrapper's hold/release and child cleanup with a subprocess fake, rerun focused `scripted reopen-stream` with fresh counted XML, and carry the revised delivery sequence to the pending documentation handoff. Rework overlap with #1729, #1766 and #1827 remains in separate scenarios or additive harness entries.

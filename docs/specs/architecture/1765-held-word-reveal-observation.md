# Held word-reveal observation (#1765)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_reopenOngoingReply_showsArrivedPrefixImmediately`, `hostRepository`, and the formatted-markdown scenario supply isolated live setup and reply selectors.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread` and the spinner/reopen scenarios supply the causal enqueue fence and repository checkpoints.
- `scripts/e2e-emulator.sh`: the `stream` scenario arm, `REPLAY_ENV`, and second-fragment watcher already support an enqueue-fenced terminal fragment.
- `scripts/e2e-fixtures/stream.jsonl`: three assistant fragments assemble `Hello, streamed world`, followed by the terminal result.
- `scripts/test_e2e_held_result.py`: `test_host_releases_suffix_on_second_send_and_result_on_third` exercises the host watcher without a device.
- `scripts/android-test-gate.py`: scripted selection and live selection leave manual ignored methods outside curated evidence.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `StreamingAssistantBody` and `AssistantMessage` separate progressive and finalized rendering.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `Message.id`, `isStreaming`, and `segment` identify the same assistant reply across settlement.
- `docs/knowledge/INDEX.md`, `docs/knowledge/features/thread-screen.md`, and `docs/knowledge/features/streaming-assistant-turns.md`: reveal is composition-scoped; finalization renders all final text immediately.
- `docs/e2e-interactive-stream.md`: rung-3 manual observations and rung-4 fixture evidence have separate claims.

## Context

The local step tests prove cadence and bounded catch-up, but the existing end-to-end stream case observes only a completed substring. This ticket adds a displayed-text checkpoint during an open reply and retains a complete final-body checkpoint. No production renderer, wire contract, or visual behavior changes. No decision record is needed.

Current main includes #1766's parser implementation and live markdown scenario. Its abandoned `feature/1766` fixture changes are not dependencies of this ticket. Shared-file overlaps also exist with #1682, #1689, #1690, #1691, #1693, #1695, #1729, #1827 and #1833; their edits concern other methods/scenario entries, so additions remain local.

## Design

Keep the existing scripted method name and gate mapping. Split `stream.jsonl` after the first two multi-word arrivals into a held initial fragment and `stream-end.jsonl`, retaining the exact assembled final text. Configure the existing second-fragment enqueue fence. The test first observes the arrived prefix in a displayed bubble, checks that its repository assistant row is still streaming and its turn non-idle, captures its identity, then explicitly sends the second prompt to release completion. Await that same row with the complete expected text and `isStreaming == false`, plus idle turn phase, before checking the displayed final body without a caret.

Add one ignored manual live method with a tool-free long plain-paragraph prompt. The prompt names a topic rather than quoting the reply. Capture a nonempty streaming assistant row, observe its prefix displayed within a reply bubble, and recheck its identity and streaming state after display. Await the same row's settlement and idle phase, then match its entire final source in the displayed settled bubble without a caret. Generous presence timeouts apply; no cadence or delta timing assertions. The manual method remains outside the curated selector and is never counted as routine live evidence.

## State and concurrency model

No new application state or jobs. Tests read the real host repository flows using bounded `runBlocking`/`withTimeout`, following existing device-test practice. Compose remains on its normal advancing clock. fakeclaude holds its second fragment until the host watcher sees the explicit second enqueue; elapsed time never releases it. Harness teardown owns watcher, daemon and relay cleanup.

## Error handling

An early completion, wrong reply identity, missing displayed prefix, lost final text, non-idle phase, or leftover caret fails the test. A blinking caret's absence is never the completion oracle. A manual run missing the transient window fails rather than being reported as evidence. No production failure modes change.

## Testing strategy

- First add a host-only regression that resolves the real `stream` scenario configuration, verifies its initial fixture is nonterminal and its assembled text is intact, then exercises the actual watcher: one enqueue stays held and the second releases completion. Watch it fail before wiring the split fixture.
- Run the host regression and existing held-result coverage, plus shell syntax validation.
- Run focused `StreamingRevealStepTest` and `StreamingMarkdownTextTest` to retain the existing local motion/settlement contract.
- Run `python3 scripts/android-test-gate.py scripted stream`, inspect fresh XML, and record selected method, executed/failed/skipped counts and evidence path. These tests require the device because they exercise real transport/background I/O, isolated host processes and the app's actual repository.
- Run required lint, assembly, Android-test compilation and forced formatting checks, then the whole unit/shared suite and `scripts/pre-verify.py --gradle` after merging main.
- Dispatcher owns `scripted-all` and the full curated live gate, including their fresh counts. The ignored manual observation is not executed by this builder or routine live gate.

## Open Questions

None. Forecast: about 300 written lines including this plan, no production files or exported types, one existing selected method, three acceptance criteria and no new state-machine reject branches; within all builder limits.

## Documentation handoff

Pending for documentation stage: update `docs/e2e-interactive-stream.md`, “What rung 3 is made of” and scripted scenarios coverage, to name the manual observation and held `stream` twin. Document removing the method's `@Ignore` for a named isolated-harness run and restoring it afterward, and that routine live-gate success does not execute it. Record any supplied manual executed/failed/skipped counts separately from automated results; documentation supplies no live evidence.

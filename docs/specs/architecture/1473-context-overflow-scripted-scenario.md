# #1473 — rung 4 `context-overflow` scripted scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_failedMcpServerPillOpensChannelInfo` (the #1457 shape to mirror), `arriveInSeededThread`, `typeAndSend`, the companion timeouts.
- `scripts/e2e-emulator.sh`: the `mcp-failed)` arm of the `case "${SCENARIO}"` block, the usage comment, the `SCENARIO` comment and the `die` list.
- `scripts/android-test-gate.py`: `SCENARIOS`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt`: `turnRecoveryNotice` and `TurnOutcomeIndicator`, two `NoticePill`s whose content description is their text (`thread_recovery_context`, `thread_recovery_compact`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the `onCompact` lambda is `null`, so the pill has no click, when `ComposerAction.CompactSession` is in `absentActions`. `absentComposerActions` reads the published menu.
- `ThreadViewModel.onComposerCommand`: clears the turn outcome and sends `/compact`.
- `app/src/sharedTest/.../ScriptedTurnOutcomeTest.kt`: the rung 2 twin, which asserts on content descriptions.
- pyrycode `36acd04c`, `internal/e2e/internal/fakeclaude/main.go`: `initializeCommands` publishes `compact`, so the pill stays clickable. `writeStreamResponse` echoes the prompt verbatim as one assistant line plus a `success` result. `internal/streamsup/parser.go` `resultStopLine` decodes `is_error` and `terminal_reason`.

## Change

Test and harness only; no production file changes. Add the scripted scenario `context-overflow`:

- a fixture `scripts/e2e-fixtures/context-overflow.jsonl`, an assistant line (`Prompt is too long`, the text real claude emits) then a `result` with `subtype` `success`, `is_error` true and `terminal_reason` `prompt_too_long`;
- a `context-overflow)` arm in `scripts/e2e-emulator.sh` naming `interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon` and that fixture, plus the usage line, the `SCENARIO` comment and the `die` list;
- `"context-overflow"` appended to `SCENARIOS` in `scripts/android-test-gate.py`;
- the `@Test`: open the seeded channel, send one prompt, wait for the context notice and a clickable Compact node, both matched by content description. Assert that no `/compact` text exists yet, then tap Compact. The phone's own sent row and fakeclaude's echo both read exactly `/compact`, and no row carries a role tag, so the wait is for two exact-text `/compact` nodes. The second one exists only if the daemon's child answered. Then assert the context notice is gone.

## Testing strategy

The new method is the test. Focused evidence: `python3 scripts/android-test-gate.py scripted context-overflow` with one test executed; `python3 -m unittest scripts/test_android_test_gate.py` over the extended `SCENARIOS`; `./gradlew compileDebugAndroidTestKotlin`. Device-only by nature: it needs the real daemon, relay and fakeclaude behind an emulator. No rung-3 twin is possible, because real Claude cannot be driven to `prompt_too_long` on demand.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`, "Deterministic mode (rung 4)" scenario table: add `context-overflow` beside `mcp-failed`.

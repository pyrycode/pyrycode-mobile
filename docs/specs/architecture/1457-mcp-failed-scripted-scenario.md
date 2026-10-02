# #1457 — rung 4 `mcp-failed` scripted scenario

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel` (the #1360 shape to mirror), `arriveInSeededThread`, `typeAndSend`, the companion timeouts.
- `scripts/e2e-emulator.sh`: the `refusal)` arm of the `case "${SCENARIO}"` block, the usage comment and `REPLAY_ENV`, which already sets `PYRY_FAKE_CLAUDE_MCP_STATUS=1` on every deterministic run.
- `scripts/android-test-gate.py`: `SCENARIOS`; `scripts/test_android_test_gate.py` iterates it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`: the `mcp` `NoticePill`, clickable through `onOpenMcpFailure`, text `thread_mcp_server_failed`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt`: the `SectionHeader(text = "MCP servers")` above `McpServersSection`.
- `InteractiveStreamE2ETest.interactiveTurn_channelInfo_listsBuiltInMcpServerAfterShowBuiltIn`: waits for the sheet's MCP content, then `performScrollTo`.

## Change

Test and harness only; no production file changes. Add the scripted scenario `mcp-failed`:

- a fixture `scripts/e2e-fixtures/mcp-failed.jsonl`, one assistant reply line with the marker text `mcp checked`;
- an `mcp-failed)` arm in `scripts/e2e-emulator.sh` naming `interactiveTurn_seededChannel_failedMcpServerPillOpensChannelInfo` and that fixture, plus the usage line and the `die` list;
- `"mcp-failed"` appended to `SCENARIOS` in `scripts/android-test-gate.py`;
- the `@Test` on `DeterministicInteractiveStreamE2ETest`: open the seeded channel, send one prompt, wait for the scripted reply, then wait for a clickable node whose text starts with the format's literal prefix (`"MCP server "`, read from `thread_mcp_server_failed` with the argument cut off, so the trailing space keeps the sheet's "MCP servers" header from matching). Tap it, then wait for the "MCP servers" section header and assert it displayed after `performScrollTo`. Fakeclaude's first `mcp_status` answer is `pyry_mcp_test` / `failed`; the sheet's own later ask gets `connected`, so the failed name is never asserted inside the sheet.

If the daemon's automatic report never reaches the open thread, the third acceptance criterion applies: file a pyrycode fakeclaude-knob ticket and route back, rather than weakening the assertion.

## Testing strategy

The new method is the test. Focused evidence: `python3 scripts/android-test-gate.py scripted mcp-failed` with one test executed; `python3 -m unittest scripts/test_android_test_gate.py` still passes over the extended `SCENARIOS`; `./gradlew compileDebugAndroidTestKotlin`. Device-only by nature: it needs the real daemon, relay and fakeclaude behind an emulator.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/development-verification-emulator-evidence.md`: add `mcp-failed` to the scripted scenario list and correct the scenario count.
- `docs/knowledge/features/thread-top-overlay.md`, the #1345 pill's verification: name the scenario and record why a live rung-3 test is not possible (the daemon's `--strict-mcp-config` children load only `pyry_approve` and `pyry_files`; pyrycode #2272 is the real-Claude pin).
- `docs/e2e-interactive-stream.md` scenario table, alongside the `refusal` row.

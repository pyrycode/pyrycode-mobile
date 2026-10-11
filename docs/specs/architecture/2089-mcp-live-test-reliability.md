# #2089 — Investigate and repair the live MCP scenario

## Files read
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the named MCP scenario, `awaitConnected`, `createChat`, and its historical predecessor attachment and Stop scenarios.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/PingReplyAssertions.kt`: `awaitDisplayedPingReply` requires an actual displayed reply bubble.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenMcpServersTest.kt`: real-report, built-in filter and display assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelMcpServersTest.kt`: sheet-open asks and report propagation.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionRegistry.kt`: `observe` follows compatibility selection, while `connectionFor` addresses one host.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowConnection.kt`: existing exact-host readiness pattern; evidence is required before adopting it here.
- `docs/knowledge/features/channel-info-sheet.md`: the MCP report and built-in display contract.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: correlate phone and daemon evidence; a timeout or teardown focus snapshot does not establish causation.
- `docs/e2e-interactive-stream.md`: real-Claude harness and evidence retention.

## Context
The original HP failure and same-tree rerun logs were recovered from the configured build host. The named failure is a coroutine 30-second timeout. The only coroutine deadline on its drive is `awaitConnected`, before `createChat`; the MCP waits use Compose assertions. The immediately preceding attachment scenario also reported its peer link ending and redialing. The original per-test phone and temporary daemon logs no longer exist at their recorded paths. This identifies the failing operation but does not establish why the connection was unavailable. Standalone and three-method predecessor replays passed; a 62-method historical-prefix investigation is pending.

## Design
First retain fixed, content-free checkpoints on the named scenario only: list arrival, connection readiness, chat setup, ping delivery, sheet opening, report arrival, and built-in row display. A small shared-test helper makes each checkpoint independently regression-testable. It wraps a failure with its fixed stage and lazily sampled connection facts while preserving the original cause. Snapshot failures become an unknown reading and never replace the scenario failure. Log only static stage/status labels, target/selection booleans, link state class names and repository presence; never pairing data, daemon text or conversation identifiers.

The existing scenario operations, order, deadlines, real ping, report-required Show built-in control and displayed `pyry_approve` assertion remain intact during investigation. Do not introduce retries or change connection routing on a hypothesis. After a captured failure establishes a cause, record the repair and its red/green regression under Revisions before changing behavior. A repair in a sibling repository requires a searched, linked native blocker.

Overlapping remote branches #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032 and #2057 edit other scenario blocks; keep the change local to the named MCP method. No design depends on their additions.

Sizing: one investigation/repair deliverable, three acceptance criteria, approximately 400–500 written lines including diagnostics, regressions, evidence and plan revisions; two internal helper declarations, one live consumer, no production state-machine branches. Recheck sizing when the cause is known.

## State and concurrency model
The helper runs each existing block synchronously on the test drive. It creates no job, flow, retry or deadline. Connection snapshots read the registry's current values without waiting. Production lifecycle and thread state are unchanged.

## State transitions and identity reuse
None: the checkpoint wrapper owns no lifecycle or identity state. Each invocation independently reports Started then Completed or Failed. Regression tests cover repeated invocation, every fixed stage, preserved timeout causes and an unavailable diagnostic snapshot.

## Error handling
Keep every existing test failure fatal. Attach a fixed stage and content-free snapshot with the original throwable as the cause. A failing snapshot reports unknown without masking the primary failure. A diagnostic checkpoint is not evidence of a repaired flake.

## Testing strategy
Write shared JVM regressions for stage attribution, success behavior, lazy snapshot sampling and diagnostic failure preservation before implementing the helper. Run existing `ThreadScreenMcpServersTest` and `ThreadViewModelMcpServersTest` without changing their assertions. Compile the Android test sources and run the named instrumented live method after the diagnostic change. Its device-only reason remains the real relay, live daemon/Claude MCP report and phone interaction. A deterministic repair regression will be chosen from the captured cause, not from the final assertion's wording. Run focused lint/build/format and final pre-verification checks. The dispatcher owns fresh full-live acceptance with the named method, counts and retained artifact paths; no baseline replay satisfies that criterion.

## Open Questions
- What caused the historical connection wait to remain unavailable? Lost phone/daemon evidence and passing short replays leave this unresolved.
- Does the historical-prefix replay reproduce the connection loss, and which side terminates first? Correlate retained phone and daemon timestamps if it does.
- Which test or product seam requires the repair? No behavior repair is selected until evidence answers this.

## Revisions
- 2026-10-11: Add an explicit sheet-anchor check within SheetOpening and a separate ShowBuiltIn checkpoint with a checked-state assertion. A successful menu tap alone cannot prove the sheet opened, and report arrival cannot prove the local filter changed. These diagnostic fences strengthen the existing contract without replacing either the real report or displayed built-in row assertions. All existing deadlines remain unchanged.

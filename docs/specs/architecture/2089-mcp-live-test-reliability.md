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
The original HP failure and same-tree rerun logs were recovered from the configured build host. The named failure is a coroutine 30-second timeout. The only coroutine deadline on its drive is `awaitConnected`, before `createChat`; the MCP waits use Compose assertions. The immediately preceding attachment scenario also reported its peer link ending and redialing. The original per-test phone and temporary daemon logs no longer exist at their recorded paths. This identifies the failing operation but does not establish why the connection was unavailable. Standalone and three-method predecessor replays passed. The historical-prefix investigation was stopped after an earlier ping scenario stalled; it never reached MCP. The instrumented suffix and final longer prefix both passed MCP, without establishing its original cause or claiming configured full-suite validation.

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
- 2026-10-11: The historical prefix completed 37 scenarios and then stopped progressing in the unchanged ping scenario tracked by #2010. Stop the owned run after retaining correlated logs, report its partial counts as interrupted, and investigate the remaining 24-method suffix with diagnostics. Excluding the stalled predecessor from this investigation does not satisfy full-live acceptance or establish the MCP cause.
- 2026-10-11: The instrumented suffix executed MCP successfully through all eight stages; its three other failures are outside this ticket. Retain counted XML and stage records, then make one final instrumented historical-prefix investigation excluding only the stalled ping drive. Keep cause and repair unresolved unless that run supplies causal evidence; none of these investigation selections replaces the configured full dispatcher gate.
- 2026-10-11: Final historical-prefix investigation completed 61 methods (54 passed, seven unrelated failures, zero skipped), and MCP again passed every checkpoint. Resolve the recurrence question negatively for these selections; keep the causal-repair questions open. Preserve this as a non-closing diagnostic checkpoint, not a reliability repair. A future failing named execution must retain stage/connection facts alongside phone and daemon observations.

### 2026-10-12: repair the demonstrated host-readiness predicate

The orchestrator recovery assigns the scoped readiness repair. Hashes of the recovered original failure/stderr and rerun match `mcp-live-2089/evidence.txt`; the failing operation remains `awaitConnected`, before chat creation. Its predicate observes compatibility selection, whereas `createChat` addresses `ARG_SERVER_ID`. `RelayConnectionRegistry.observe` can also accept a missing selection or Idle and does not require the daemon handshake. The encrypted registry controls retained by #1898 (`selected-host-before.xml`: two failures; `explicit-host-after.xml`: two passes) demonstrate both wrong-host timeout and false readiness. This establishes a defect in the failing operation, not the historical outage's root cause; historical attribution remains unresolved.

Extract `awaitHostConnection(registry, serverId, timeoutMs)` into test-only `HostConnection.kt`, reusing `awaitSendNowConnection`'s exact-host combined-status predicate. MCP calls it within the existing Connection checkpoint, with the same 30000 ms deadline and explicit host also passed to `createChat`. Send now's helper delegates to it, retaining its existing missing-host message. No other `awaitConnected` callers migrate. Missing host fails immediately with a static message; registered hosts wait until both relay and daemon are Connected. Neither helper selects, connects nor retries a host. Timeout/cancellation continues through the original cause-preserving diagnostic wrapper.

Use `RelayConnectionFactoryTest`'s existing real registry and encrypted `PeerTransport` fixture for five MCP-path controls: `mcpReadinessIgnoresStoppedSelectedHost` (ready A, unavailable selected B); `mcpReadinessWaitsForTargetDespiteReadySelectedPeer` (unavailable A, ready selected B, then target recovery); `mcpReadinessWaitsForTargetHandshake` (relay up, authenticated daemon held, then completion); `mcpReadinessRejectsIdleAtOriginalDeadline` (Idle remains pending at 29999 ms, times out at 30000 ms); and `mcpReadinessRejectsMissingTarget` (ready B cannot satisfy absent A). Assert selection remains unchanged. Run them red against the extracted original compatibility wait, then green with the shared exact-host predicate, alongside both existing Send now controls and all diagnostic/MCP coverage.

State/concurrency: the shared helper creates no job or retry; the caller owns its suspended `first` collection and timeout. It observes one registered bundle, like the existing Send now helper. Tests cover delayed handshake, recovery, Idle timeout and absence; production pairing replacement and lifecycle behavior are untouched. Existing stage tests retain repeated invocation and timeout causes. On successful Connection completion, emit the same content-free target/selection and both-leg snapshot used on failure, so fresh phone evidence records authenticated readiness before chat setup. Preserve all eight stages, real ping, required report and visible `pyry_approve` assertions.

Additional files read: `RelayConnectionFactoryTest` (`Fixture`, `PeerTransport`, both Send now controls), `SendNowConnection.kt` (`awaitSendNowConnection`), and the #1898 plan/evidence. New overlaps #2088, #2091 and #2092 change other methods; #2091 adds separate fixture consumers. Keep edits additive and local. Forecast including the diagnostic checkpoint: under 850 written lines, no production edits, one new internal function, two live consumers and no production state-machine branches; all sizing limits hold.

Retain red/green XML and a fresh focused MCP live run outside disposable worktrees, with source/APK/daemon revisions, phone logcat and daemon logs. Check both consumers with MCP and Send now focused live selection. Fresh configured full post-verifier acceptance remains dispatcher-owned; keep `needs-real-claude` and request return of its full/named XML and provenance before evidence completion. Prior investigation selections do not satisfy that gate.

### 2026-10-12: controlled repair and focused live evidence

All five MCP-path controls failed against the original compatibility predicate, including the 30000 ms virtual timeout for ready A/unavailable selected B. All five passed with exact-host authenticated readiness. Complete focused classes passed 61 executed, zero failures/errors/skips: encrypted registry 27 (including both existing Send now controls), MCP diagnostics 5, Send now diagnostics 6, MCP screen 15, MCP ViewModel 8. The original and current main `awaitConnected` bodies are identical, confirming the baseline predicate rather than attributing the lost historical outage.

The fresh MCP selection passed 1 executed, zero failures/errors/skips, on repair revision `2fe7f996657005a6700873687dcec98b90954b01`. Its phone log records all eight stages plus target/selection and Connected relay/daemon status before chat setup. Send now's separate focused live selection also passed 1 executed, zero failures/errors/skips with identical Kotlin source. Retain counted XML/static stages/provenance under `app/src/androidTest/assets/mcp-live-2089/`; raw XML/phone logs/daemon snapshots survive worktree removal at `/tmp/builder-2089/readiness-repair/`. The daemon binary reports `vcs.modified=true`; retain that fact and binary hash instead of assuming a clean revision. Snapshot polling may omit final daemon teardown; the retained limitation is explicit. Full configured dispatcher acceptance remains pending, with `needs-live-artifacts` requesting `dispatcher-full.xml`, `dispatcher-method.xml` and `dispatcher-provenance.json` at the same asset path. Builder must commit the returned artifacts; documentation stays pending with its stage.

## Documentation handoff

- Pending: `docs/e2e-interactive-stream.md`, MCP live scenario coverage: record exact-host authenticated readiness, unchanged deadline/assertions, controlled red/green evidence, and eventual dispatcher full-live execution/counts/provenance. Preserve the unresolved historical-outage attribution.

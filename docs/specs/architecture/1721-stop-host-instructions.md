# Isolate host instructions for the live Stop proof (#1721)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`, `STOP_HOLD_PROMPT`, `hostRepository` and the host-prompt scenario's save/restore establish the local setup seam.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: test-only helpers compile for both JVM and instrumentation without production changes.
- `app/src/test/java/de/pyryco/mobile/e2e/LiveConnectionReadsTest.kt`: small callback fakes demonstrate independent lifecycle tests.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `requestHostSystemPrompt` and `setHostSystemPrompt` preserve exact text and expose failures through `Result`.
- `scripts/e2e-emulator.sh`: `two_host_name_ok` restricts real-Claude instances to harness names; the supplied `serverId` selects that isolated daemon.
- `docs/knowledge/features/host-editor.md`: empty instructions are deliberate clear, distinct from an unread value; never include prompt text in diagnostics.
- `docs/knowledge/features/development-verification-test-scheduling.md`: preserve sequential prior-peer readiness and use counted XML rather than compilation as live proof.
- `docs/e2e-interactive-stream.md`: existing rung-3 Stop lifecycle and rung-4 coverage; no scripted replacement for the real-Claude proof.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: Daemon-wide host system prompt is the authoritative existing contract, with edits applying at the next session start.

## Context

The cause comment on #1721 records five real-Claude refusals before any tool permission prompt. Four follow daemon default instructions introduced by pyrycode `30fa13d6` to keep the foreground free, conflicting with the infinite foreground hold. The earlier #1689 refusal predates those defaults and interpreted the request as possible injection. Clear only this scenario's isolated host instructions and explain the controlled interrupt exercise. Production defaults and main sources stay untouched. No decision record is needed.

## Design

Add test-only `withClearedHostInstructions(read, write, block)` under `sharedTest/e2e`. Save the exact string before mutation; enter protected cleanup before clearing; write empty and confirm it with a fresh read before executing the block. Always write the saved value and confirm exact restoration afterwards, including when clear, confirmation or scenario assertions fail. Do not substitute the daemon default for the captured original. Empty, custom whitespace and default values all round-trip exactly.

Only the Stop method calls this helper. Its callbacks resolve `hostRepository(serverId)` per operation and use the existing bounded `runBlocking`/`withTimeout` calls with `getOrThrow`. Wrap fresh chat creation and the existing Stop proof inside the helper after phone readiness. Keep peer cleanup outside the instruction guard so it runs even if restoration fails. No shared live setup, signatures, wire types or dependencies change.

Revise `STOP_HOLD_PROMPT` to explain that this isolated harness intentionally tests the phone's Stop control, the peer will allow a real permission prompt once, and the phone will interrupt the foreground command. Retain the same Python event wait, unique held reply token, foreground execution and lack of a requested timeout. Preserve prior-peer readiness, once-only permission approval, cancelled turn end, Stop removal, actual same-thread ping reply, second turn end and absent held reply token. No retries or deadline changes.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1765, #1766, #1827 and #1833 touch other methods in `InteractiveStreamE2ETest`; changes stay local to the Stop method and its prompt.

Sizing: one deliverable, approximately 300 written lines including lifecycle regression tests and this plan, zero production files, one test-only exported function, no migrated consumer signatures, three acceptance criteria and fewer than ten failure branches. Both design and written plan remain within every builder sizing boundary.

## State and concurrency model

The instruction guard is synchronous on the instrumentation test thread; bounded repository operations run in independent `runBlocking` scopes like the existing host-prompt scenario. No new coroutine job or persistent state. Instructions are saved only in memory for this scenario. Sequential instrumentation and exact restoration make host-prompt scenario execution order irrelevant. Existing peers retain their own cancellation and close lifecycle.

## Error handling

Initial read failure prevents mutation. Clear/write/readback failures prevent session creation and still attempt restoration after capture. Scenario failures propagate unchanged; if restoration also fails, attach its exception as suppressed to the original failure so both remain visible. A restoration failure after success fails the test. Confirmation messages are static and never print instructions. Do not swallow repository errors or retry Claude refusals.

## Testing strategy

- First add callback-driven JVM regressions and run them red against a pass-through guard; then implement the guard and rerun green. Cover exact original restoration (including empty and whitespace), clear-confirm-before-block ordering, scenario assertion failure, initial read failure, failed clear write/readback, failed restoration and simultaneous scenario/restoration failures.
- Run focused lifecycle and existing peer readiness tests, lint, assembleDebug, androidTest Kotlin compilation, Spotless apply and forced check. Run one zero-Claude scripted reconnect scenario as the focused device harness check. The Stop scenario remains device-only because it requires a host daemon, relay and real Claude.
- After the final main merge, push and run the whole unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` against the PR body.
- Pending dispatcher: a fresh passing full live gate must explicitly execute and pass `InteractiveStreamE2ETest#interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`. Retain XML, tested commit and executed/failed/skipped counts. Focused or same-tree retries and compilation alone do not satisfy repair acceptance.

## Open Questions

None. The existing host API supplies exact current text, deliberate clear and durable acknowledgements.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, “What rung 3 is made of” stop-running-turn coverage: describe the isolated instruction clear/restore and revised controlled interrupt request.
- Pending documentation stage: the same file, “Verification status”: record the dispatcher-supplied counted fresh full-suite evidence, including the named passing Stop testcase and retained XML; documentation does not produce live evidence.

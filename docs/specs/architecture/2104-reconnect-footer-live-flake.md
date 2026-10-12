# #2104: localize and repair the reconnect-footer live flake

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the reconnect-footer scenario and `prepareChat`, `hostRepository`, `freshSettings`, `restoreSettings`, and `setHostLink`; several reads capture a connection-scoped repository.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` and `callOnLive` distinguish a replacement connection from retrying a failed operation on an unchanged connection.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/RememberedEffortStage.kt`: static operation labels and failure attribution provide the existing diagnostic pattern.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/thread-composer-footer-context-usage.md`: a settings-backed percentage alone does not prove delivery of context usage; preserve repository-level freshness checks.
- `docs/knowledge/features/thread-composer-footer-testing.md` and `docs/specs/architecture/1397-fresh-settings-skips-held-reading.md`: held settings must be skipped, inherited marking follows the announcement, and the second turn must complete before changing models.
- `scripts/e2e-emulator.sh` and the agents repository's `scripts/android-test-gate.py`: isolated live-daemon launch and retained per-method XML/logcat.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100 and #2102 edit the same live-test file. Their other scenario edits are independent; preserve #2099's additive `setHostLink` diagnostics when main is merged. No required dependency is established.

## Context

The #2009 and #2057 same-tree failures and passes have different unlabelled exception signatures. The retained dispatcher logs identify Mobile/daemon revisions, but their original temporary daemon and phone artifact directories are absent. Neither stack demonstrates the failing operation. This plan first creates operation-labelled evidence, then permits only the localized mechanism that evidence establishes. No decision record is needed.

## Design

Keep changes within the named scenario and a small portable shared-test diagnostic helper, with JVM regression tests. Wrap each potentially failing operation in a static stage that records start/completion/failure and the phone's content-free link/repository state. Split this scenario's fixture creation into the same create, rename and initial-settings operations as `prepareChat`, without migrating shared helper signatures. Preserve all existing assertions and both prompts. Keep cleanup labelled and preserve the original failing operation if cleanup also fails. Logs must omit prompts, settings payloads, keys and tokens.

A fresh diagnostic live run will retain its per-method XML/logcat, isolated daemon logs and revision metadata before any cleanup or next run. Append the demonstrated cause and exact repair contract under Revisions before implementing a behavioural repair. Do not infer a cause from a passing rerun. A discovered visual or reconnect/merge design change returns for refinement; a required daemon/relay repair becomes a native blocker.

## State and concurrency model

Diagnostics add no background jobs or production state. Each operation executes once on the existing instrumentation thread, with the existing coroutine timeouts and repository collectors. No retry-until-pass and no timeout extension. Any connection-following repair must distinguish source replacement and preserve its original acceptance predicate.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Initial authentication and fixture create/rename/settings | Label each separately in the live scenario; diagnostic JVM tests preserve the first failure and its cause. |
| Intentional cut | Existing equality against pre-cut context and held footer; labelled cut operation. |
| Reconnect | Existing fresh non-held settings, effort, permission, announcement-to-menu marking; labelled reads. |
| Second turn in the same conversation | Existing completed second reply and a context reading distinct from settled reconnect usage with tokens greater than pre-cut usage. |
| Published-model selection | Existing exact published value confirmed by fresh settings; labelled menu/pick/readback. |
| Cleanup after success or failure | Diagnostic regression ensures cleanup cannot hide the earliest operation; keep settings restoration and remembered-effort clearing. |

## Error handling

Wrap failures with static operation attribution and their original cause. Record only static labels and booleans/link-state names. A successful operation must return exactly its original value; a failure must not trigger retry. Keep cleanup diagnostics separate from the scenario's first failure.

## Testing strategy

Write JVM diagnostic regressions first, run them red and green, then perform a focused diagnostic live run for cause localization. Add a red/green regression for the demonstrated repair before implementing it. The existing live scenario needs real authenticated sockets and two real Claude turns, which Robolectric cannot provide. Retain counted selected-method XML and phone/daemon logs, with Mobile/daemon revisions. Run focused affected unit tests, lint, assembleDebug, compileDebugAndroidTestKotlin and forced Spotless. After final merge and push run assembleDebug and `scripts/pre-verify.py --gradle`.

The dispatcher owns the final fresh full live gate before documentation and merge: it must explicitly execute/pass `InteractiveStreamE2ETest#interactiveTurn_reconnect_footerReadingsAndModelChangeSurvive`, retain revisions/XML/logcat and executed/failed/skipped counts. Focused success is not that proof.

## Open Questions

- Which operation fails first, and what distinguishes failed and passing phone/daemon evidence? Resolve from a fresh labelled run because historical stacks are insufficient.
- Does the demonstrated repair stay within localized harness/non-visual Mobile scope? Record the answer before behavioural implementation.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, “Verification status”, with the established cause, repair, retained evidence and fresh counted dispatcher full-live result. Documentation records evidence; it does not produce the proof.

## Revisions

### 2026-10-12: retain diagnostic daemon evidence before successful cleanup

The harness removes `WORK_DIR` on success. Add scoped cleanup-time retention for live selections containing this scenario: copy only daemon logs and a static revision/exit/selection record to `build/reconnect-footer-evidence/<run>`, before removing temporary material. Pairing files, credentials and isolated HOME contents are excluded. The existing gate already retains method XML/logcat. Test retention on success and failure, and no retention for other selections.

### 2026-10-12: demonstrated daemon dependency; suspend behavioural implementation

At Mobile `7f04ed6d689db388b0bd64fe01a94c45935557c6` / daemon `46c980feab9109d37ec72bf74577b00924a78632` / Claude 2.1.280, the standalone method passed 1/1 and the immediate historical model-change predecessor plus target passed 2/2. Replaying the 23-method historical prefix failed only this target: 23 executed, 22 passed, 1 failed, 0 skipped. Fixture creation/settings, the first real turn, held context and the cut all completed. The earliest failed operation is `ReconnectedSettings`: the restored connection closes at 4413 immediately, then five further redials close at 4413 while the original fresh-settings wait expires. This is a connection failure, not a context assertion or cleanup failure.

The existing open owner issue `pyrycode/pyrycode#3214` demonstrates the matching reconnect-snapshot defect with a held-acknowledgment real-transport counterexample and explicitly includes this method in its live proof. Current daemon `drainOnce` passes ordinary pushes through `forwardEnvelopeSource` to `hostedEnqueue`; hosted admission closes at the shared 16-slot limit instead of holding ordinary reconnect snapshots upstream. The byte-ceiling overflow path would log `v2.push.ceiling`; no such event occurs in this failed run. This distinction matters because protocol close 4413 alone is not a precise mechanism. The matching owner reproduction plus the suite-prefix-only failure and repeated 4413 establishes a required daemon repair. Native blocked-by linkage from Mobile #2104 to daemon #3214 is confirmed. Do not broaden this Mobile ticket into daemon code or add retries to hide the retirement. Historical #2009's unlabelled stack remains unattributed; no claim that every prior failure is the same operation.

Retained counted XML, content-free phone stages/transport-close records, daemon event subset, tested revisions, original raw-artifact paths and SHA-256 hashes are under `app/src/test/resources/e2e/2104/`. The raw failed run is `build/dispatcher-tests/live-4ob9intu/`; daemon logs survive in `build/reconnect-footer-evidence/pyry-e2e.8G2Lsc/` and `/tmp/pyry-e2e.8G2Lsc/`. Raw clock offsets were not calibrated; correlate order and close categories rather than treating host/phone milliseconds as equal.

Open questions resolved for this run: the first failed operation is identified, and its required repair belongs to the daemon. Resume after #3214 lands: reproduce the historical prefix using the repaired daemon, decide whether any localized Mobile change is still needed, finish normal checks and obtain the explicitly counted dispatcher full-live proof. The preserved scenario still requires both real turns, held-versus-fresh distinctions, second-turn token growth and exact published-model readback. Documentation handoff stays pending.

### 2026-10-12: validate merged daemon capacity repair and hand off full-live artifacts

Daemon #3214 is closed and its repair merged in `47d00822cc702c896d6234b0447226096edab210` (PR #3232). Reading `drainOnce`, `reserveHostedPush` and `releaseHosted` confirms that ordinary snapshots now wait unsealed upstream when ordered delivery is full; completion releases capacity and wakes the drain. The owner's enabled `TestReadinessLegacyReconnectSnapshotCapacity` and capacity/retirement race regressions provide the mechanism's red/green coverage. No further Mobile behavioural repair is demonstrated or needed for this reproduction.

A single replay of the same 23-method historical selection at Mobile `83f053f587daccc805136e860ce871e2a2dca796` / daemon `47d00822cc702c896d6234b0447226096edab210` / Claude 2.1.280 passed: 23 executed, 23 passed, 0 failed, 0 skipped, process exit 0. The target executed and passed once. All 34 labelled operations completed, including `ReconnectedSettings`, both real replies, held context, second-turn context growth, exact published-model settings readback and both cleanup operations. Its phone log contains no 4413 record. Original assertion predicates and waits are unchanged; this is repair validation against the established daemon mechanism, not retry-until-pass diagnosis.

Retained evidence is `app/src/test/resources/e2e/2104/repaired-prefix.{json,xml}` and `repaired-prefix-phone.txt`, with source hashes and content-free daemon events. Raw XML/logcat remain in `build/dispatcher-tests/live-37sr41ka/`; daemon logs and revisions survived successful cleanup in `build/reconnect-footer-evidence/pyry-e2e.nw3PlK/`. Raw clock offsets remain uncalibrated. The earlier unattributed historical signatures are not retroactively assigned a cause.

The dispatcher must still run its fresh full live gate with this method explicitly selected and passed before documentation or merge. Keep `needs-real-claude` and `needs-live-artifacts`; request `all` in the PR's Live tests section. On the artifact return, the builder owns committing `app/src/test/resources/e2e/2104/full-live.json`, `full-live.xml` and `full-live-phone.txt`, retaining tested Mobile/daemon revisions, raw XML/logcat/daemon references and hashes, executed/passed/failed/skipped counts and explicit target success. There are no pending coupled production-reader changes. Remove only `needs-live-artifacts` after usable records are checked, committed and pushed; documentation then records this evidence in the pending Verification status handoff.

### 2026-10-12: verifier rework contracts

Finding 1 routes the scripted ping animator/Looper failure here. The initial focused ping reproduction passed both methods on Mobile `6a037419f` / daemon `e59635679`; it does not establish the intermittent crash's cause. Add `workerCompletionKeepsRippleCancellationOnMainThread` to the deterministic class and ping selection: hold a composition effect while a real Material button is pressed, complete its suspension on an owned worker, then assert both continuation and button disposal run on Main. This requires real device Looper/native ripple behavior. Use its red/green result to validate a local rule `effectContext = StandardTestDispatcher()` repair, following the established #2094 boundary; do not alter production dispatchers or disable ripples. Preserve every durable-gap assertion. The historical crash's exact interleaving remains unestablished.

Finding 2 restores one `withTimeout(THREAD_TIMEOUT_MS)` around separately labelled create and rename calls. Keep initial settings outside that deadline as in `prepareChat`. A virtual-time regression with two 20-second operations must fail at 30 seconds and retain `RenameChat` attribution.

Finding 3 adds a portable scenario/cleanup wrapper used by the live method. It preserves the original labelled exception when cleanup also throws, attaching the separately labelled cleanup exception as suppressed; a cleanup failure after a successful scenario still fails. Regressions cover settings-restoration and remembered-effort-clearing failures independently, their separate diagnostic records, original cause identity and exactly-once execution. Existing ordinary best-effort `restoreSettings` handling remains unchanged.

Run the controlled device regression red/green, focused ping, diagnostic JVM regressions, the selected reconnect live method, existing affected checks and final pre-verify. The dispatcher still owns fresh full scripted and full-live gates and the subsequent live-artifact return.

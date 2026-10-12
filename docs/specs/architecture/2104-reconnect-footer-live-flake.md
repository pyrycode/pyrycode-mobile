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

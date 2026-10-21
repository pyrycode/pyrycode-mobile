# Context-overflow Compact reliability (#2442)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon` requires a clickable notice, no pre-tap command, two command rows after the physical tap, and notice removal.
- `scripts/e2e-emulator.sh`: scenario selection, daemon revision discovery, prerequisite checks and isolated evidence retention.
- `scripts/e2e-fixtures/context-overflow.jsonl`: the first turn ends with `prompt_too_long`; later turns use the child's echo.
- `scripts/test_e2e_emulator_effort_default.py`: credential-free execution of extracted harness construction is the existing shell-test pattern.
- `docs/e2e-interactive-stream.md`, Deterministic mode and context-overflow: real Android/Noise/relay/parser path with a scripted child and zero Claude turns.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: counted native evidence and historical failures remain distinct from later passes.
- `docs/knowledge/features/turn-outcome-indicator.md`: the recovery offer is live state; restored history does not establish a fresh recovery event.
- Core `cmd/pyry/operator_message_history.go`, `operatorMessageHistory`, at merge `3d5379ec4b778c569c27b7639a2ae495b32cee0d`: embeds committed acceptance identity before delivery publication.
- Core `cmd/pyry/accepted_delivery_test.go`, `TestAcceptedDeliveryLiveRefusal`, and `testdata/accepted_delivery/`: actual producer/ForwardReader/Store/publisher regression, including a forced segment boundary and retained before/after results.

## Context

One behaviour slice: reliable Compact recovery following context overflow. Diagnosis, prerequisite enforcement and native proof belong together. Three acceptance criteria; forecast below 400 written lines, no exported types or consumer signature changes, and at most four prerequisite reject paths. No application production change is currently supported by evidence.

Core #3725 already repairs the accepted-message publication race. Before it, publishing delivery before its outcome can expose an identity later withdrawn by the join, forcing a baseline and retiring a live peer. The historical #2373 failure uses pre-repair daemon `647a4e7526f581f78d422195cb22b3e9b979e767`; its first send precedes `baseline_required`, teardown 4414 and reconnect, with no second send. Its exact rerun has two sends and no gap. The original assertion phase and publication boundaries are not yet established. Passing reruns cannot fill that gap in the evidence.

Core's controlled regression failed only the separated schedule before repair (2 leaves: 1 passed, 1 failed), then passed both schedules after repair. It proves this producer mechanism, rather than reconstructing either historical Mobile execution. Inspect the first sighting's exact stage bundle too and retain any unavailable evidence explicitly.

## Design

Reuse Core's repair and its committed regression. Add a scenario-specific harness prerequisite before daemon launch: deterministic context-overflow must use a versioned daemon whose revision contains Core #3725. Check the binary revision against the configured source repository, not merely source HEAD. Reject stale, unavailable or unverifiable binaries with an actionable prerequisite error; other scenarios retain their current selection. Do not change fixture bytes, UI, assertions, deadlines, retries or skips.

Retain checked content-free diagnosis, provenance and counted native summaries under `app/src/androidTest/assets/context-overflow-2442/`. Keep raw private logs outside Git in the persistent agents evidence volume, with hashes. Evidence is not executable fixture input.

Potentially overlapping branches: #1766, #2052, #2088, #2090, #2091, #2097, #2104, #2114, #2121, #2175 and #2367 touch the harness or test class. The prerequisite addition is local and independent of their scenario/selection edits. No shared block redesign is needed.

## State and concurrency model

No application jobs, flows or lifecycle state change. Each normal focused scenario owns fresh isolated daemon/relay state and the managed device hold. Core's regression supplies barriers for the publication race; native repetition supplies downstream integration evidence.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Delivery published separately from outcome | Reuse Core `TestAcceptedDeliveryLiveRefusal/separated_segment` before/after evidence. |
| Delivery and outcome coalesced | Reuse Core `TestAcceptedDeliveryLiveRefusal/coalesced`. |
| Fresh context overflow, physical Compact tap, child echo and clearing | Three fresh Linux executions of the unchanged named Android method. |
| Old or unknown binary despite newer sources | New credential-free harness prerequisite tests. |

## Error handling

The harness exits before starting the scenario when the binary revision is unavailable, either commit is unverifiable, or the binary lacks the repair. This is an environment prerequisite failure, never a passing or skipped scenario. Preserve every real scenario failure and its raw evidence.

## Testing strategy

Write and run failing shell prerequisite tests before implementation, then require green tests for repaired/descendant binaries, old/unknown binaries, newer-source/old-binary mismatch and unrelated selections. Reuse the committed Core regression evidence rather than duplicating its owning-repository repair.

Run `python3 scripts/android-test-gate.py scripted context-overflow` three consecutive times on Linux with isolated sources at the merged repair. Each run must execute the named method once, pass once and have zero failures/errors/skips. This device test requires the real daemon child, Noise/relay transport, Android UI and physical tap. Retain XML, instrumentation/phone/daemon/relay logs, device/SDK identity and source/binary hashes, including every failure. Complete scripted-all remains dispatcher-owned; macOS is outside scope.

Run focused script regressions, lint, assembly, formatting and final `scripts/pre-verify.py --gradle` after merging main. No Kotlin test code changes are planned.

## Open Questions

- Resolved: the exact #2333 stage bundle was recovered through authoritative stage records. Both sightings share the first-send / baseline-required / 4414 / reconnect pattern; both exact reruns record two sends without that gap. Neither bundle retains the failed assertion stack or original raw history. The supported producer mechanism remains distinct from the unknown historical assertion phase and publication schedule.
- No distinct Mobile cause has been established. Reuse Core's controlled regression and preserve the existing native scenario contract; complete configured coverage stays with the dispatcher.

## Revisions

- 2026-10-21: two setup attempts executed zero tests because the installed Go 1.26.9 VCS discovery recognizes a `.git` directory but not a linked worktree's `.git` file. Forcing VCS stamping did not resolve that discovery limit. Use an isolated standalone clone at the same Core merge instead; its binary reports the expected revision and `vcs.modified=false`. Both rejected setup attempts remain in persistent evidence. No scenario, assertion, deadline or fixture changes were needed.

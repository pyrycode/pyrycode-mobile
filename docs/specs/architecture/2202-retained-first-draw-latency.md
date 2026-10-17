# Retained fragmented first-draw latency investigation (#2202)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: `measureSavedThreads`, `awaitDeviceReadiness`, `Probe`, `repository`, `openAndMeasure`; existing readiness, worker and committed-frame probes supply the investigation.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/EmulatorCpuReadiness.kt`: `awaitEmulatorCpuIdle` and cancellable sampling preserve readiness and descriptor ownership.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: distinguish preparation, readiness, committed draw and complete versus focused execution.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw-comparisons.md`: retain misses and avoid attributing elapsed delays from worker CPU alone.
- Agents `scripts/android-test-gate.py`: configured UI selection, shared admission, owned compilation/cleanup, fresh XML and phone-log retention.
- #2163 retained verifier bundle and #2177 PR #2203 `comparison.json`: historical evidence limits and unchanged-source focused comparisons.

## Context

The historical #2163 configured UI gate measured delayed-projection fragmented
first opens at 1434/1436 ms against 1000 ms. Its retained XML/verdict do not contain
the contemporaneous host CPU or per-opening phase data needed for attribution.
The separate #2177 focused comparison passed after runner repair #2178; that does
not resolve the historical complete-gate failure. Its latest focused class also
failed readiness in the slow control, tracked externally by #2209. This Linux
worker must record its own complete baseline rather than assume either outcome.
No decision record or production change is proposed without a reproduced cause.

## Design

Run one configured complete, regular UI baseline from the current main source,
with no production or measurement edits. Force UI selection with `UI_GATE_FULL=1`
because the initial branch contains only this plan; leave `UI_DEVICE_ALL` unset
(the two-shard in-depth sweep is outside scope). Use the supported
`python3 scripts/android-test-gate.py ui` launcher, its shared host admission,
owned in-process Kotlin preparation, configured API 33 device and disabled
animations. Retain exact source and runner revisions and measurement-source hashes.

Observe timestamped host `/proc/stat`, load, and process CPU counters, plus device
CPU/load/configuration through read-only adb sampling approximately every five
seconds. Record observation overhead and missing samples. Do not alter device
settings or terminate other workloads. Existing phone logs supply per-opening
readiness and restore/snapshot/content/committed-draw phases and worker CPU/queue
times. Store raw reports, logs, observer script and verified manifests durably in
the agents evidence volume; commit only checked content-free summaries under
`app/src/androidTest/assets/latency-2202/`.

Compare the baseline with #2177 and the historical bundle, explicitly identifying
unavailable measurements and changed environment/selection. If no miss reproduces
and all controls execute, use the bounded disposition required by the ticket:
“historical failure retained; cause unresolved; not reproduced under the recorded
repaired-runner conditions.” Report phase/headroom limits without claiming repair.
If a local miss reproduces, diagnose before choosing a bounded repair, append a
revision and recheck size; changed logic requires a controlled red/green regression
and matching complete comparison. Readiness exhaustion before a Probe supplies no
draw result. Preserve any failed baseline, with no unchanged acceptance retries.

The overlap check found #2009 changes the measurement/sampler/test files; #2143
already ships its cancellation approach. This evidence-only design does not edit
those shared files or #2177's assets. Final configured acceptance remains owned by
the dispatcher, separately from this builder investigation.

## State and concurrency model

No application state or coroutine ownership changes. The observer lives only for
the foreground gate invocation, stops after it exits, and never controls admission.
Existing test scopes and cleanup remain untouched.

## State transitions and identity reuse

None: evidence-only work adds no lifecycle or identity state. Existing first/reopen,
offline/held-newest, negative-control and descriptor-cancellation cases remain intact.

## Error handling

Retain failed gates and missing samples explicitly. A reproduced runner dependency
is linked to its existing issue; no readiness, draw or cleanup failure becomes a
skip or is silently retried. Historical missing data remains unavailable.

## Testing strategy

The complete baseline must separately count the whole gate and all four saved-draw
methods, including sixteen positive openings and the slow-control phases. Preserve
the four 250 ms >=80% idle windows within 30000 ms, cold construction, persisted
fixtures, exact rows/markers/request counts, newest committed-frame endpoint,
1000 ms bound, 1100 ms projection hold, 3000 ms slow restore and held-pipe control.
Real disk, CPU counters, shell descriptors and committed Android frames require
native execution. No new test is needed for evidence-only work; existing assertions
are the acceptance. Check summary counts/phases/hashes against raw evidence, then
run required formatting, assembly, lint and final pre-verification checks on Linux.
Other operating systems are outside this ticket's verification scope.

## Open Questions

- Does this worker's complete gate reproduce a draw miss, readiness exhaustion, or
  neither? Resolve from retained fresh evidence before disposition.

## Documentation handoff

Pending documentation stage: update
`docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, section
“Readiness and sampling cancellation (#2143)”, with the historical failure, fresh
complete counts/phases, any evidenced repair and bounded disposition. Fold #2177's
readiness evidence first, then #2202's latency disposition without erasing either.
Documentation records evidence; it does not generate missing native results.

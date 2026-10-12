# #2108: HP historical device availability

## Files read

- `scripts/android-test-gate.py`, `scripts/agent-tool.py`: delegate to the agents-owned gate; Mobile does not own admission or custody.
- `scripts/e2e-emulator.sh`: `cleanup` and the managed-device invocation; teardown targets run-owned daemon processes.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/E2eInstrumentationRunner.kt`: `quietSystem` and `finish`; emulator settings and test assertions remain unchanged.
- `docs/knowledge/features/development-verification.md`: the HP SwANGLE policy and the distinction between device disappearance and an established native crash.
- `docs/specs/architecture/2088-phone-attachment-stall.md` on `feature/2088`: historical overlay, private capture retention and missing listener finishes.
- Agents `scripts/android-test-gate.py`: `device_hold`, `run_on_device` and `stop_emulator`; ownership remains in the agents repository.
- HP's installed `mobile-emulator.py`: renderer policy remains `-gpu swangle`.
- HP's existing diagnostic admission example `ticket-2051-focused-20261011/run.mts`: ordinary heavy-manager admission and child completion before reservation release.

## Context

The retained run selected 65 historical methods but recorded 55 executed, 52 passed, three failures and zero skips. Only 54 cases completed. The attention-pill case started at 2026-10-11T22:28:58.084Z; its guest log ends at 22:28:59.491Z and UTP marks interruption at 22:29:00.703Z. Attachment capture never started. All 13 current manifest artifacts verify. Emulator process exit, signal origin and custody completion are not established by that archive. No production mechanism is selected from `device offline`.

This is one investigation and demonstrated repair. Forecast remains below 750 written lines, five public declarations, ten simultaneous consumers and ten reject branches, with three acceptance criteria. Recount before any causal implementation. Current planned repository surface is this unique plan; no overlapping feature file requires redesign. No decision record is needed for capture-only investigation.

## Design

Preserve the original historical checkout, overlay and raw gate/UTP/logcat evidence in a private persistent HP archive before further execution. Build one isolated reconstruction from Mobile `2bd039944754f24c920feef0efaec36a8ea7f6db`, daemon `63aabec70dad3948c916332a139eb22533b8f4b8`, Claude 2.1.280 and the exact recorded capture overlay. Verify source, overlay, APK, gate, launcher and tool identities; record every difference.

Use the existing authenticated heavy-manager channel and ordinary delegated live gate, retaining device custody. A bounded capture-only observer records UTC process identity/state/disappearance, adb state, custody metadata and host/guest observations alongside the unmodified selected-method manifest and raw results. It neither owns nor alters renderer/custody policy. Maximum admitted execution duration is 1800 seconds, written before execution; timeout is unsuccessful, never a retry instruction. There is at most one fresh diagnostic reproduction before choosing the causal repair, not a repeat-until-pass loop.

Correlate observed events before selecting a mechanism. Unknown exit status or signal stays unknown. If repair belongs to a runner, host, launcher or daemon owner, search/file and natively link that owning issue, then wait for its repair. A Mobile repair requires a revision to this plan specifying its contract and a failing-before/passing-after regression. A passing baseline alone cannot establish a cause or fulfill repair acceptance.

After a demonstrated repair, obtain the required fresh historical HP control, with all 65 methods completed, zero skips, no unfinished case and no device loss. Report product failures separately; completion is not a clean live PASS. Publish paths, identities, counts and digests only. Private raw logs never enter source assets or a public report.

## State and concurrency model

Only the diagnostic coordinator owns its observer subprocesses. Heavy admission precedes device execution; ordinary gate custody covers boot, tests and teardown. Stop and reap only observer children. Preserve run exit status, capture artifacts before releasing the admitted reservation, and record cancellation explicitly. No application coroutine, flow or lifecycle changes are planned.

## State transitions and identity reuse

| Event | Evidence or regression |
| --- | --- |
| Device boots, goes offline, disappears or changes PID | UTC process/adb samples, process start identity and UTP interval correlation |
| Gate returns or exceeds the declared duration | Durable exit/timeout record and observer completion before admission release |
| Another gate queues for the same device | Existing custody metadata; observer cannot acquire or release that hold |
| Historical method identifier repeats | Preserve actual start/finish records and counted XML; an interrupted record is not completion |

## Error handling

Missing observations remain unknown. Capture failure does not replace the gate result, change assertions/deadlines, skip a method or trigger another run. A causal repair outside Mobile is a native blocker; missing access or inability to establish the required mechanism is an incomplete builder outcome.

## Testing strategy

Recheck retained checksums, raw XML and UTP completion counts. Verify observer bounds and source equality before the single diagnostic run. A demonstrated Mobile repair will add its production regression here before implementation and run focused existing coverage plus required build/lint/format/pre-verify checks. The affected HP control must complete the original 65 methods, explicitly including the attention-pill and phone attachment methods. The dispatcher subsequently owns the configured current-branch full live gate, including both named outcomes, suite counts, revisions and retained XML/logcat locations.

## Open Questions

- Which operation and causal mechanism made the device unavailable? Unknown pending correlated process, guest, adb and custody evidence.
- Does its repair belong to Mobile? Unknown; do not substitute a Mobile runner implementation for an owning-repository repair.

## Revisions

- 2026-10-12: independently preserved and verified 122 original raw gate/UTP/logcat and capture-input artifacts at HP's private `logs/e2e-evidence/ticket-2108-20261012/baseline/`. UTP establishes 55 starts and 54 finishes; an empty XML failure is unfinished. The available kernel core-limit event names Claude, not the emulator. Original emulator exit/signal, guest cause and admission/custody completion remain unknown.
- 2026-10-12: the first 600-second heavy-admission wait expired with zero tests executed; its receipt is retained as `admission-no-grant-1.json`. One further 900-second admission wait received an ordinary heavy grant at 03:23:53 UTC. The declared 1800-second execution bound remains unchanged. All 2878 compared source/assets files match the original capture tree; gate, launcher, overlay and toolchain identities also match. This single execution is a diagnostic baseline with external bounded observers, not a post-repair control or a causal-repair claim. No test is retried and no scenario assertion/deadline, renderer or custody policy is changed.
- 2026-10-12: the fresh diagnostic also interrupted: 56 executed, 53 passed, three failures, zero errors/skips, 55 completed. Attention pills passed; device loss interrupted `interactiveTurn_replySuggestion_longPressSends`, and phone attachments never started. Source, both APKs, daemon and Claude binary hashes match the original capture, as do recorded flags/selection. Effective daemon runner and home-configuration hash remain unknown in both records. Guest camera-watchdog SIGABRT at 03:38:00 UTC preceded the last online adb sample at 03:38:03 UTC. The observer then failed to spawn an adb query with EAGAIN; the container has a 2048-task limit/peak and 1212 cumulative max-limit events, with no producer or counter-at-failure retained. Neither observation establishes the emulator exit/signal or the device-loss cause.
- 2026-10-12: the observer's unhandled spawn failure released heavy admission at 03:38:05 UTC before the underlying gate finished at 03:38:33 UTC. Raw artifacts were recovered without another execution; no owned gate/emulator/observer remains. Next capture must handle EAGAIN observationally, hold admission until the full gate subtree exits, and retain emulator exit/signal plus PID-budget producer/counter chronology. The failed coordinator is retained as evidence, not shipped as a repair. Private archive `logs/e2e-evidence/ticket-2108-20261012/` contains 286 verified artifacts; its manifest SHA-256 is `a0c36bcef343a5112d0913ab6cd2ffc000cc3c4d64027c3ae43ab14670163962`. Product failures belong to existing #2114 and #2055. No external causal prerequisite, causal repair or successful 65-method control is established; acceptance remains incomplete.


### Recovery design, 2026-10-12

The failed `observe.py` and `run.mts` are preserved locally and in the verified private archive. The demonstrated supervision defect is Mobile's diagnostic wrapper, not a device-loss diagnosis. Add capture-only `scripts/e2e-capture-supervisor.py` and `scripts/e2e-capture-admission.mts`, with adjacent Python and Node regression tests. They continue to invoke the ordinary delegated gate; they do not implement admission, device custody or renderer policy.

The Linux container supervisor becomes a child subreaper before launching collectors or the gate. It records PID/start-tick identities, adopts and reaps orphan descendants (including a child that creates another session), and waits for the complete owned subtree. Gate exit is retained separately from capture errors. Observation exceptions cannot leave this wait path. Startup, observation, export and outcome errors are static classifications with unknown observations; payloads and exception messages stay private. Cancellation and the declared 1800-second execution limit send TERM to identified owned children, then KILL after a ten-second grace, followed by reaping. A second cancellation cannot interrupt cleanup. Export is attempted independently per named artifact directory, before an fsync-backed terminal receipt records gate exit, cancellation/timeout, capture errors and subtree completion. A failed export fails capture without converting a failed gate into success.

The host coordinator uses the existing authenticated `ManagedDispatch`. Its callback waits for the matching container terminal receipt rather than interpreting the host podman group as the container subtree. A transport failure requests cancellation through the run-specific private shared directory and waits for the supervisor receipt. SIGINT/TERM request the same cleanup. No matching receipt means no completion claim or reservation release; an unrecoverable supervisor/host failure remains an operator recovery requirement. The coordinator propagates nonzero gate/capture exits to its own process exit. Admission records the maximum duration before work starts, and only acknowledges completion after export and durable outcome. Tests inject a manager to assert the reservation ordering; actual HP work still uses ordinary authentication.

Prestarted `adb track-devices` and guest logcat collectors avoid periodic spawning under task pressure. A bounded procfs/cgroup sampler records UTC PID/start identities, parent/thread counts grouped by process producer, pids-current/peak/max and max-event deltas, and custody observations without spawning. Zombie exit codes are retained when observable; missing parent-owned emulator exit/signal evidence remains unknown rather than being inferred from disappearance. If establishing that evidence needs a runner or launcher change, it belongs to its owning repository and requires a native blocker.

Regression contracts: injected EAGAIN followed by delayed gate/setsid-descendant writes cannot release admission before their last write and exit; collector startup failure still waits; export failure still records gate status and attempts other exports; cancellation and a TERM-ignoring child escalate and reap; nonzero child status propagates; podman exit without a terminal receipt cannot finish the reservation. Python tests run on Linux to exercise the real subreaper. No Android test/assertion/deadline changes are planned.

Forecast for the recovery remains one capture lifecycle deliverable, approximately 900 total written lines including tests and plan, zero application exported types or simultaneous consumer updates, three acceptance criteria and nine failure classifications. The new uniquely named scripts have no in-flight branch overlap. No live capture is permitted until these supervision regressions pass. Device-loss causality and the required 65-method repaired HP control remain open.

- 2026-10-12 recovery: 286/286 retained artifacts reverified against the unchanged manifest. Seven Linux supervision regressions and four Node admission regressions pass on HP, including real EAGAIN injection with a detached delayed writer under a fake held reservation. These prove diagnostic lifecycle repair only. Authorize one newly supervised diagnostic capture under ordinary heavy admission with the same 1800-second declared execution bound; this supersedes the earlier one-diagnostic limit solely because the previous observer failed. No device-loss mechanism is yet selected and this is not a post-repair device control. Prestarted existing host syscall tracing, if permitted, observes the Gradle parents' wait/kill results without altering renderer policy; missing observations stay unknown.


### Recovery transition coverage

| Event | Executed Linux/host regression |
| --- | --- |
| Observer spawn/collection reports EAGAIN while gate and detached child continue | `test_eagain_does_not_abandon_delayed_gate_or_detached_descendant`; Node `EAGAIN cannot release fake reservation before detached last write` |
| Container or host collector cannot start | `test_collector_start_failure_keeps_gate_result_and_waits`; Node `host collector startup failure is unknown while gate status survives` |
| An artifact directory cannot export | `test_export_failure_still_attempts_other_exports_before_receipt`; Node `export failure propagates without replacing failed gate status` |
| Cancellation repeats or a child ignores TERM | `test_signal_cancel_and_repeated_signal_cannot_skip_cleanup`, `test_cancel_file_cleans_detached_child_after_gate_exit`, `test_timeout_kills_term_ignoring_child_and_reaps`; Node `host observer ignoring TERM is killed and reaped before completion` |
| Podman exits before container descendants finish, or an old/incomplete receipt appears | Node `container receipt, not podman exit, orders reservation completion`, `wrong run and incomplete receipt cannot release admission` |
| Gate cannot start | `test_gate_start_failure_is_durable_without_children` |
| Prestarted observer emits bytes | `test_prestarted_collector_has_utc_byte_intervals` |

- 2026-10-12: the final source adds bounded nonblocking collector drains with UTC byte intervals and unknown observation records, plus bounded host process-group cleanup and host collector startup handling. Eight Python and six Node regressions pass on Linux/HP. The actual diagnostic received its grant at 05:47:14 UTC before these final collector enhancements were installed; its preserved helper/source hashes, not the branch's later helper versions, identify the active inputs. Its optional existing host parent-wait tracer lacked noninteractive access; parent-owned emulator status/signal remains unknown. No privilege or host policy was changed, and the active inputs are left fixed.

# Retained session-error timeout investigation (#2338)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/SessionErrorRecoveryScenario.kt`: `run`, `runCase`, `diagnosticSnapshot` and `completed` preserve both arms and teardown identity.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `recorded` supplies ordered per-conversation observations without changing delivery.
- `scripts/e2e-session-error.py`: `Case.start`, `action`, `close` and `record` own isolated recovery daemons and independent child-input counts.
- `scripts/e2e-emulator.sh`: session-error setup and cleanup determine effective tagged daemon and retained evidence.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `routeInbound` clears session errors only for recognized non-idle turn state.
- `app/src/main/java/de/pyryco/mobile/data/repository/SessionErrorProjection.kt`: `apply`, `clear`, `reset` and `observe` hold connection-local errors.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositorySessionErrorTest.kt`: existing error validation and clearing contract coverage.
- `docs/knowledge/INDEX.md`, `features/remote-conversation-repository.md`, `features/remote-conversation-repository-state-errors-and-handoff.md` and `features/development-verification.md`: connection-local projection ownership and counted native evidence boundaries.
- `docs/e2e-interactive-stream.md`, Session-error recovery (#1731): assertions, control readiness, cleanup and evidence privacy.
- Agents `docs/working-practice.md`, `docs/ticket-scope-policy.md`, `dispatcher/docs/builder-test-scope.md`, `builder/device-tests.md` and `builder/security-review.md`: managed focused execution, evidence custody and scope.

## Design source

N/A: investigation and regression repair of an existing scripted assertion; no UI redesign. The refinement-evidence comment supplies this reason.

## Context

The retained arm rendered its reply but retained a recovery status beyond the existing 20-second deadline. Historical failures and a baseline pass have identical executable Mobile sources and do not establish a cause. The historical dispatcher revision is `1f8d5e30508561adb2fb7eb5c20e2dfc42fb67f9`; the current agents checkout is `e2f17c0ca3173e2991cfbd5d853fd3f6dc8239d0`. Neither substitutes for the other's execution identity.

One useful behaviour is reliable retained-backlog recovery, including the diagnosis and regression proving its repair. No independent implementation slice is justified before identifying the cause. Forecast remains approximately 700 written lines, zero new exported types or signature migrations, three criteria and fewer than ten diagnostic branches. No decision record is needed.

Remote branches #1947, #2175 and #2325 overlap the remote repository. Their separate state/route and host-allowance work is not needed for this investigation; any eventual repository edit remains local and additive.

## Design

First run the unchanged `session-error` scenario through `scripts/android-test-gate.py` under managed admission on Linux. This worktree starts without build/install outputs; a second bounded invocation compares reused build inputs while the managed device installation remains under gate control. Record actual task/cache and install behaviour, never infer reuse from a revision alone. The current container exposes readable/writable `/dev/kvm`, unlike the historical builder.

Retain XML, phone logcat, tagged daemon/control logs, source revisions, source/APK hashes, managed preparation and cleanup evidence beneath agents `logs/evidence/2338/`, with private permissions. Capture peer frame order through content-free allowlisted observations if existing diagnostics cannot distinguish the recovery events. Compare the phone's accepted non-idle state and session-error apply/clear logs with peer order and dedicated daemon output. Preserve every failed and passing attempt.

Select a repair only from that evidence. Missing non-idle delivery, later error delivery, stale UI projection and mismatched effective inputs are competing hypotheses. Assistant content and idle remain insufficient to clear a session error. No retry, timeout increase, skip, synthetic recovery injection or production-daemon change is allowed. If the owning cause is outside Mobile, file/reuse a native owner blocker and retain the diagnosis rather than implementing outside this repository. Record any concrete repair design under Revisions before implementing it.

## State and concurrency model

No new application state or job is planned. The repository's single inbound consumer owns frame ordering; `SessionErrorProjection` uses atomic map updates and connection teardown resets errors. Diagnostics observe immutable peer snapshots and do not drive readiness, consume frames or restart a Runner. Existing controller and managed gate retain process/cleanup ownership.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Failing child exits, retained queue recovers in the same daemon/session | Exact deterministic recovery method, retained arm, including delivery identity and child counts |
| Give-up drops backlog, fresh prompt recovers | Same method, dropped arm, including independent counts and no replay |
| Recognized thinking/responding arrives after error | `RemoteConversationRepositorySessionErrorTest` clearing assertions |
| Idle/content arrives, malformed state or another conversation progresses | Existing repository contract assertions preserve the error |
| Both fixture pairings are removed and preceding host resumes | Exact method's existing final pairing/connection restoration assertions |
| Same candidate runs with fresh then reused build inputs | Two bounded managed scenario runs with effective input and APK hashes |

## Error handling

Keep original scenario failures and their diagnostic causes, including secondary cleanup failures. Diagnostic publication never includes daemon-authored prose, prompt content, identifiers, pairing payloads or keys. Missing native artifacts or a zero-test run remain unverified. If bounded evidence cannot resolve sequence/load effects, submit retained failure references and the smallest proposed scope through recovery assessment; complete acceptance stays dispatcher-owned.

## Testing strategy

Run the exact method via `python3 scripts/android-test-gate.py scripted session-error`, retaining both normal arms and original assertions. A device is necessary for actual encrypted relay delivery, native UI projection, timing and host restoration. Confirm nonzero raw XML counts and inspect failures before selecting changes. Use affected Python/controller and Kotlin tests for any demonstrated repair, observing red before implementation and green afterward. Run focused repository session-error tests, lint, APK assembly, instrumentation compilation if touched, forced formatting and final `scripts/pre-verify.py --gradle` before PR handoff. Full UI/scripted gates and the later main sweep remain pending with the dispatcher.

## Open Questions

- Which frame ordering reaches the phone during retained recovery, and does a later error replace a clear?
- Do fresh and reused effective inputs differ, and does the named method reproduce the timeout?
- Does the cause belong to Mobile, the daemon, or managed infrastructure? Resolve through evidence before repair.

## Documentation handoff

Pending for documentation stage: `docs/e2e-interactive-stream.md`, “Session-error recovery (#1731)”: record demonstrated cause, repair boundaries, named Linux results and persistent evidence paths/hashes. Preserve historical failures and distinguish focused proof from pending complete acceptance. Documentation does not produce native evidence.

## Security review

Verdict: PASS for bounded diagnosis; any demonstrated repair requires review of its concrete boundary before implementation. Reviewer: builder self-review, 2026-10-20.

- Trust boundaries: keep `routeInbound` and existing decoders as admission boundaries; diagnostic snapshots never promote peer data into production state.
- Tokens and credentials: reuse fixture-generated credentials privately. Never publish controller authorization, pair codes, keys, raw frames or prompts.
- Files and storage: private persistent evidence directory and owner-only files; only checked content-free summaries enter Git. No new app storage or user-derived filenames.
- Android surface: instrumentation only; no exported component, intent, provider or permission change.
- Cryptography: use existing Noise transport without new primitives, nonce handling or handshake changes.
- Network/I/O: existing fixture authentication, bounded calls, capability gate and managed admission stay intact; no endpoint or TLS changes.
- Logs/telemetry: only static arm/phase/type/state outcomes; allowlist state values and never log untrusted error codes or text. Raw diagnostics remain private.
- Concurrency: observe immutable snapshots without suspension across production writes; existing scopes and cleanup remain owners. Do not retry denied cleanup operations.
- Threat model: relay delay/drop/reorder remains a diagnostic hypothesis, never grounds for weakening assertions. Hostile daemon frames retain existing decoding and text boundaries. Disk-token theft, release screenshots/accessibility and production resource exhaustion receive no new surface from this test-only investigation.

## Revisions

- 2026-10-20 controlled publisher result: a race-enabled temporary-overlay probe at daemon `f90990dd142f206422bfd4a24a8feedcc94f8274` executed two leaf cases (one pass, one failure, zero skipped). Immediate delivery preserved responding then idle; holding publisher enumeration after selection, admitting same-generation idle and releasing it delivered idle alone. This demonstrates a daemon publication defect, while fresh native phone correlation remains pending. Preserve the probe under `scripts/session-error-publisher-probe_test.go` with a small managed-admission launcher so its owning repository can reproduce it without modifying a live checkout. The probe deliberately asserts progress preservation and remains red until the external repair; it is an explicit diagnostic, outside Mobile's unit/device discovery. No Mobile clearing change is justified. Initial private probe/red-XML evidence is under agents `logs/evidence/2338/continuation-20261020/`, five-file manifest SHA-256 `b41eca6012ee4bba3af3148d445be910bf930b41ec8124ecd65114bfa2ff26a5`. Revised forecast remains below 800 written lines, one behaviour and no exported production types. Security review remains PASS: the probe uses static synthetic content and an ephemeral overlay through the existing scoped admission launcher, never production-daemon mutation, credentials, raw network publication or a new Mobile I/O boundary.
- 2026-10-20 recovery continuation: add authoritative `thread` + `interactive` coverage beside the legacy session-error tests. Drive supplied envelopes through `SessionLiveAdmission`, including same-session clears, recognized phases, malformed states, content, a later error and disconnect. Require enum-only diagnostic output on both paths and preserve the non-idle-only clearing contract. This remains one recovery behaviour with its diagnostic proof, approximately 550 written lines including the preserved checkpoint, no new exported types or migrated callers. Security review remains PASS: supplied identity is admitted by the existing boundary and only decoded enum phase reaches debug logging. The earlier two native failures and incomplete acceptance remain unchanged evidence.
- 2026-10-20 blocked handoff: the phone-phase regression's managed request waited at least 1,832 seconds without acquiring admission or starting a child. Canceled only this worktree's verified queued wrapper, confirmed its original process identity stopped, and read exit 143; this request executed zero tests. Other workers and reservations were untouched. No PR is open, no Mobile repair is selected, and the separate daemon publisher's current-revision filtering remains an unconfirmed hypothesis rather than an owning-repository diagnosis.
- 2026-10-20 verification status: `SessionErrorDiagnosticsTest` executed 12 tests with 10 passed, 2 failed, 0 errors/skips before its clear-flag fixture expectations were corrected; the corrected class has not run. The new phone-phase regression has not run and its production observation is deliberately still unimplemented pending that red execution. `spotlessApply` failed on the additional indentation's maximum line length; the queue await was wrapped and indentation corrected manually, but formatting has not rerun. Current diagnostic instrumentation, lint and pre-verify remain unverified. Earlier baseline APK assembly does not validate this diagnostic checkpoint.
- 2026-10-20 re-entry: first execute `RemoteConversationRepositorySessionErrorTest` and observe the new phase-diagnostic assertion fail, then add the enum-only debug observation in `routeInbound` and obtain 18/18 plus 12/12 focused class results separately. Complete formatting, lint, debug assembly and instrumented compilation. Commit/push the diagnostic candidate and run the exact native `session-error` method with persistent phone-phase and labeled legacy-peer ordering. Preserve both original arms and the 20-second status-clear assertion. Assign a repair only from that correlation, obtain controlled red/green repair evidence, and file/link an owning-repository native blocker only if an external cause is demonstrated. Full dispatcher acceptance, the later main sweep and documentation remain pending.
- 2026-10-20 final private evidence: agents `logs/evidence/2338/SHA256SUMS` covers 45 retained files, SHA-256 `e81cbe96d1dd3b113e44be7f586b5ec584c618d711f07d537f6d7e0fcaeba824`. It includes both native raw XML/logcat comparisons, dedicated recovery/control captures, revisions, effective APK inputs, copy verification, the earlier diagnostic unit failure, read-only admission observation and confirmed own-request cancellation. Raw captures remain private; only content-free findings and references are committed.
- 2026-10-20 checkpoint: managed host queues exceeded 30 minutes across checks. Preserve the diagnostic implementation and the test-first phone-phase assertion remotely while its red run remains queued. The phone-phase log is not implemented yet. Diagnostic green checks, formatting rerun, lint, traced native execution, diagnosis/repair and PR acceptance remain pending; this checkpoint claims no reliability repair. Verified private XML/logcat copies and all dedicated recovery evidence are under agents `logs/evidence/2338/`. Final comparison manifest SHA-256 values: fresh `a365f29d54b45c33b9874fda4ea24ef1da8df311b6fdcf7c79f576daefb33ae5`; reused `617287371b2c04dc6de49e59742ffe5293482cf944db341652446c6d8c706334`. Both ran Mobile `e64f0cd096aea1ee4793be9444122858ad17c763`, daemon `f90990dd142f206422bfd4a24a8feedcc94f8274`, current agents checkout `e2f17c0ca3173e2991cfbd5d853fd3f6dc8239d0`. Both used app APK `559bb82ff223cd701edbeb57aee671056d9ec6379a175815b911c4f8fc12847c` and test APK `15c26edeb46547a8a5dea31a25aa9afa08cf4fcf5bad4d2cd82bdcf7f689995e`. Each executed 1, passed 0, failed 1, skipped 0 at the unchanged retained status-clear deadline. The dropped arm and final restoration assertions were not reached. Fixture processes recorded by both controllers were absent after their gates returned. Independent child counts remain incomplete observations, not completed acceptance.
- 2026-10-20: add a debug-only `session_error_turn_state` observation at the existing `routeInbound` decode boundary, containing only the decoded enum phase. `turnStateDiagnosticsDescribeOnlyDecodedPhases` requires all three recognized phases and rejects arbitrary state/routing text. This closes the phone-receipt evidence gap without treating the legacy peer as the phone or changing clearing behaviour. The security review permits only enum-derived phase output, never raw frame fields; no new state, failure branch or type is introduced.
- 2026-10-20: source inspection confirms `SecondClientPeer` defaults to legacy interactive capability, whereas the phone negotiates supplied session state. Label the trace `observer=legacy`; its receipt order cannot establish phone receipt order. Correlate it with phone projection events and the daemon's separate supplied publisher before assigning ownership.
- 2026-10-20: unchanged-source Linux comparison reproduced the retained `reply_render` timeout in both managed-install and selected install-once paths, each 1 executed, 0 passed, 1 failed, 0 skipped. The install-once command used `scripted-all --tests` with only the exact named method; it executed no other scenario. App/test APK SHA-256 values were identical. Phone logs contain one error apply, no clear before disconnect, and no later apply. Existing logs do not retain peer frame order, so add `sessionErrorPeerTrace` beside the shared diagnostics helpers and emit it in `runCase`'s peer cleanup. Limit to the last 256 observations with original ordinals and an explicit omitted count. Allowlist frame types, turn phases, known error classifications and clear flags; never include payload text, routing identity or arbitrary codes. `peerTracePreservesRecoveryOrderingWithoutPrivateValues`, `peerTraceHandlesMalformedFieldsAndBoundsOutput` and `peerTraceKeepsClearFramesDistinctFromIdle` cover the publication boundary. This adds no production state or failure mode; logging failure cannot replace the original scenario result. Security review remains PASS with this explicit bounded allowlist. Raw first/second results are persistent under agents `logs/evidence/2338/{fresh,reused}-20261020/`; diagnosis and passing acceptance remain open.

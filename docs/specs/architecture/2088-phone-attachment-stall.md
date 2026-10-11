# #2088: phone attachment live stall investigation and repair

## Files read

- `scripts/e2e-emulator.sh`: `cleanup`, `run_installed_instrumentation` and the LIVE test selection; temporary daemon logs do not survive runner cleanup.
- `scripts/android-test-gate.py` and the delegated agents implementation: `run_on_device` copies fresh XML and logcat, but not daemon artifacts. The agents repository remains read-only.
- `scripts/test_e2e_emulator_cleanup.py`: existing status-preserving teardown regression.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`; preserve clipboard replacement, multi-chunk picker, completed turn and independent peer history/byte/isolation assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt`: `open`, `recorded`, `history`, `retrieveAttachment`; already redials and survives individual link drops.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/FocusRecordListener.kt`: method lifecycle and failure recording; JUnit failure notification occurs after scenario teardown.
- `docs/knowledge/features/development-verification-emulator-evidence.md` and `development-verification-test-scheduling.md`: diagnostic status must never replace the original test result; missing evidence is unknown.
- `docs/knowledge/features/attachment-upload.md`: connection-bound transfer and content-free observations.
- `docs/e2e-interactive-stream.md`: rung-3 attachment proof and live gate ownership.

## Context

The #1977 historical gate ran Mobile `2bd039944754f24c920feef0efaec36a8ea7f6db` merged with `ea3ab23e7e14aac63e59a3d05d28473e3905323f`, daemon `63aabec70dad3948c916332a139eb22533b8f4b8`, Claude 2.1.280. It reported 65/56/9/0 executed/passed/failed/skipped, then 9/6/3/0 on the same-tree rerun. The target stalled in `allowPromptsUntil` for 240 seconds with no permission prompt and a closed/redialing peer. Failed X persisted acceptance and verified fixture bytes but no delivery or main turn. Rerun X persisted delivery and terminal success. Original daemon/logcat disappeared; five later Mac target passes neither diagnose nor repair this occurrence. The recovery brief first requires repairing this capture defect, then one historical 65-method HP capture. Cause and repair remain open until evidence supports them; this is not a diagnostic closing PR. A decision record is not needed for test-harness capture.

Overlap: #1766 adds a LIVE list entry; #1682, #1689, #1690, #1691, #1693, #1695, #1869, #1879, #1896, #2009, #2032, #2057 and #2089 touch other portions of the live class. Keep additions local; no required redesign dependency was found.

## Design

Add `scripts/e2e-evidence.py` as the explicit export boundary, with script regressions beside it. The shell owns a private staging archive within its run directory and a unique private persistent archive under `E2E_EVIDENCE_ROOT` (default: the dispatcher agents logs/e2e-evidence directory). No archive is placed in the public PR or source assets. Capture before teardown, and again after owned processes terminate. Export only projected allowlisted records: daemon lifecycle/connection event names and safe correlation fields, attachment X/Y acceptance/delivery/terminal history metadata, phone/peer state, method intervals/order, effective nonsecret configuration, source/binary/APK hashes and a checksum manifest. Bound source reads, output size, file count and subprocess duration; record truncation or unavailable inputs explicitly. Unknown dynamic log messages/errors become hashes and static classifications, never raw text. Pairing outputs, credentials, device keys, arbitrary home files, attachment bodies and Claude transcripts are excluded.

Add best-effort target observations before its `finally` closes the peer or deletes fixtures. Record phase, X/Y IDs, phone repository/connection availability and counts of recognized peer frame types, without fetching history or extending a deadline. Add content-free start/finish/failure timestamps in the existing listener for actual method order. Observational failures report unknown and cannot throw into JUnit. All existing target assertions and deadlines remain intact.

The capture regression uses the real shell cleanup with synthetic owned artifacts, then removes the temporary tree and verifies the persistent manifest. After capture tests pass, reconstruct the historical source tree in isolated HP checkouts and apply only the reviewed capture overlay. Use the ordinary authenticated gate and managed-device admission, pinned daemon and Claude. Record historical source hashes, overlay hashes and every tooling/configuration substitution. A causal implementation and its red/green regression will be appended under Revisions only after a failing capture establishes the mechanism. If the fix belongs elsewhere, link its issue as a native blocker; do not claim repair from a passing capture.

## State and concurrency model

The host shell owns capture lifetime and bounded subprocesses; no application StateFlow/job changes. Per-method observations are synchronous snapshots on the test thread. Concurrent gate runs have distinct archive directories. EXIT/INT/TERM preserve incoming status. SIGKILL cannot run cleanup; completed staging checkpoints remain recoverable but no completion is claimed.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Test exits nonzero, then temporary directory disappears | `test_failure_archive_survives_temporary_removal` |
| INT/TERM cancellation | `test_cancellation_preserves_status_and_archive` |
| Export fails during a successful or failed run | `test_export_failure_keeps_staging_and_original_status` |
| Two runs reuse a daemon label | `test_runs_have_distinct_private_archives` |
| Missing or truncated observations | `test_unavailable_and_bounded_inputs_are_explicit` |
| Secret-bearing files, symlinks or hostile log fields | `test_only_allowlisted_metadata_is_exported` |

## Error handling

Capture is observational: failure retains local staging/work directories, emits a static warning, and leaves the original assertion/process status unchanged. Each absent observation is unknown, distinct from an empty history or zero count. Publication is atomic per artifact with an integrity manifest; an interrupted export cannot be labelled complete. No retry of the target or increased deadline is introduced.

## Testing strategy

Write host regressions first and observe failure against the existing cleanup. Run the focused evidence, cleanup and Gradle-orchestration script tests. Compile the Android test capture additions and run relevant listener coverage. Instrumentation is device-only because its snapshot observes live Noise links and real daemon admission. The requested HP 65-method capture is investigation, not dispatcher acceptance; inspect counted fresh XML and explicit target outcome. Preserve the normal focused build/lint/format/pre-verify checks before a final PR. The fresh full dispatcher live gate remains acceptance criterion 3, with named pass, all counts, revisions and durable XML/logcat locations. No new scenario, dependency or visual design is introduced.

## Open Questions

- What prevented accepted X from delivery and turn opening? Unresolved pending a retained failing historical HP capture; no production repair is selected.

## Documentation handoff

Pending documentation stage: `docs/e2e-interactive-stream.md`, “What rung 3 is made of”, attachments-from-phone paragraph: record the established mechanism, regression and fresh full-live named pass once available. Describe persistent private harness archives and their retention limits in the harness evidence section. Do not publish raw private logs.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: `e2e-evidence.py` projects explicit fields instead of copying raw daemon logs/history; malformed or dynamic content is omitted or hashed.
- [Tokens] MUST FIX addressed in design: never enumerate pairing outputs, credential files, environment, device stores or transcripts. Test planted secrets in both forbidden files and allowed input records.
- [Files/storage] MUST FIX addressed in design: private unique run directories, no symlink inputs/exports, bounded reads and atomic artifact writes; failed export retains staging. This is private runner evidence, not app secret storage or backup.
- [Android surface] No exported component or manifest changes. Test observations use already initialized registry and peer state only.
- [Cryptography] SHA-256 is evidence integrity only; Noise keys, nonce use and Keystore remain unchanged.
- [Network/I/O] SHOULD FIX: enforce finite subprocess/read/output bounds and explicit truncation. Capture makes no new network request and cannot reserve or delay peer/phone readiness.
- [Errors/logs] MUST FIX addressed in design: only static events, UUIDs, enum states, counts and hashes cross the export boundary; unknown exception text stays private in the original delegated report, never in new evidence.
- [Concurrency] SHOULD FIX: snapshot before scenario teardown; preserve EXIT status and distinguish completion from interrupted/partial export. Separate run directories prevent cross-run overwrite.
- [Threat model] Relay delay/drop remains observable and unknown until diagnosed. Hostile frame text is never exported or rendered by capture. Rooted-device token theft and UI-side token entry are unchanged; no new app storage/input path exists.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

## Revisions

- 2026-10-12: capture implementation uses two shell-owned checkpoints (before and after owned process teardown). The before-teardown process sample is retained separately; only the target records its phone/peer state before scenario teardown. Six new host regressions passed within a 39-test focused harness selection, and 21 existing peer wait/redial tests passed. The evidence-lifetime negative control failed at the missing persistent archive before implementation. Scripted ping executed/passed 2 methods with no failures/skips and published a private archive with all 11 artifact checksums verified. Assembly, lint, forced formatting and `pre-verify.py --gradle` passed after merging main (already current). These validate capture only. The causal mechanism and its repair remain unresolved pending the historical HP capture; there is no closing PR.

- 2026-10-12: historical HP capture selected 65 methods but lost the device during the attention-pill method: 55 recorded/executed, 52 passed, 3 failed, 0 skipped, including one unfinished case with an empty failure body. The attachment target never started. The private archive survived and all 11 initial checksums matched. Actual UTP retention included 55 listener starts and two assertion failures, but no listener finishes: UTP closes per-method logcat before `RunListener.testFinished`. Replace that finish observation with `LiveMethodIntervalRule` under sharedTest, which emits inside the test statement's finally before runner completion. Its injected clock/sink regressions cover original-assertion preservation, observation failure, unknown clock and repeated method identity. Export marks absent intervals unknown. This repairs capture, not the unresolved attachment mechanism. Device loss is a separate capture prerequisite; no original-cause claim follows from it.

- 2026-10-12: a focused current-tree Mac target passed 1/1 at `0bda7850f` with daemon `8091a4730d42`, solely to validate capture instrumentation. Its raw 1,935,039-byte logcat contained 14,417 lines and all expected target cleanup/interval records, but the export silently scanned only its first 10,000 lines. The noisy-log regression failed for both a late record below the byte limit and a record after oversized input. Remove that independent line cutoff; scan the bounded bytes and retain complete head/tail log lines when the byte bound is exceeded, explicitly marking the omitted middle. Reproject the retained logcat to prove late records survive without rerunning the scenario. Native #2108 (Backlog, priority:low) blocks the historical HP capture and is linked to #2088; the original mechanism, causal regression/repair and fresh full dispatcher gate remain pending.

- 2026-10-12: final noisy-log host selection passed 41/41 (0 failures/skips); interval rule passed 4/4. Reprojection of the retained 1/1 Mac execution now includes `turn_completed`, `before_cleanup` (peer open, one terminal frame, phone repository available) and the exact rule interval. Its private corrected archive is `pyrycode-mobile-agents/logs/e2e-evidence/reprojected-c0o9txnx/`, with 12 verified artifacts; this was metadata reprojection, not a new test run. HP archive `logs/e2e-evidence/run-kx055b1f/` retains 55 actual starts, the 65-method selection, counted XML, source/tool substitutions and explicit unknown method ends/target history. #2108 is open, Backlog, priority:low and read back as #2088's native blocker. No causal-repair claim or closing PR is made.

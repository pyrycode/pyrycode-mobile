# Interrupted-upload readiness evidence (#2138)

The scenario addresses its explicit harness host but previously used `awaitConnected`, whose `ConnectionStateSource.observe` follows selection and accepts the legacy Connected projection. That projection includes deliberate Idle and relay-up before the Noise handshake finishes.

Controlled checks use `RelayConnectionFactoryTest.Fixture`: the real registry, supervisor, coordinator, and Noise session, with a stopped or held-handshake peer transport. The upload-used `awaitHostConnection` path was first run with the exact legacy selected-host predicate, then repaired to await both Connected legs on the addressed host. Neither selection, deadlines, nor retry counts change.

| Retained capture | Tests | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `legacy-readiness.xml` | 5 | 0 | 5 | 0 | 0 |
| `legacy-diagnostics.xml` | 7 | 1 | 6 | 0 | 0 |
| `fixed-readiness.xml` | 27 | 27 | 0 | 0 | 0 |
| `fixed-diagnostics.xml` | 7 | 7 | 0 | 0 | 0 |

The legacy readiness failures identify these supported defects:

- `interruptedUploadReadinessIgnoresStoppedSelectedHost`: the ready target waited until the unchanged 30-second virtual deadline because the selected host was stopped.
- `interruptedUploadReadinessWaitsForItsOwnHostEvenWhenSelectedPeerIsConnected`: an unavailable target was admitted by the healthy selected peer.
- `interruptedUploadReadinessWaitsForAuthentication`: a relay-up target with its Noise handshake held was admitted before authentication.
- `interruptedUploadReadinessKeepsDeadline`: intentional Idle admitted the upload immediately rather than waiting for the supplied deadline.
- `interruptedUploadReadinessPropagatesCancellation`: the legacy relay projection completed before a pending authentication wait could be cancelled.

The fixed class includes all five regressions and the existing Send now readiness checks. Seven diagnostic checks prove single execution, repeated-stage identity, preserved timeout budget and cause, Compose timeout and assertion causes, operation failures, and unchanged caller cancellation. Records contain fixed labels and safe link metadata; exception messages are excluded from published diagnostics, while the original exceptions remain causes privately.

Controlled runs used mobile base `b107f97a743a508fb9182fdc165b3e084180e250` plus this ticket’s test-only source changes, whose SHA-256 values are retained in private `controlled-provenance.json`.

Raw XML and the unavailable-history record are retained privately at `$AGENTS_REPO_PATH/logs/evidence/2138/controlled-readiness/`, with owner-only permissions and a verified `SHA256SUMS`. `private-artifact-sha256.txt` records hashes of the original XML. Committed XML preserves the actual method outcomes and timestamps but replaces baseline failure details with a fixed label.

## Historical attribution limit

The October 12 failure stdout/stderr and same-tree rerun were initially unavailable. They have since been recovered privately under `$AGENTS_REPO_PATH/logs/evidence/reliability-20261013/`, with six daemon diagnostic files under `original-daemons/`. All three gate records and all six daemon records match their retained manifests' SHA-256 values and byte counts; the session-error fixture log is empty. The original macbook inventory hashes are not independently available for comparison. `private-artifact-sha256.txt` records the verified recovered hashes.

The original counted XML reports 65 executed, 62 passed, 3 failed, 0 errors/skips; upload failed once. The same-tree subset reports 3 executed/passed, 0 failed/errors/skips, including upload once. The failure stderr directly confirms `host link reconnect timed out; repository present=false` for the named upload method, locating the failed operation at post-cut reconnect. Reported mobile was `0c9e9a6f49`, merged with main `427640d577`; retained stderr identifies daemon `9c9cd5a25c337666bdbed47c29202e695a172733`. Original instrumentation method XML and per-test phone logcat remain unavailable. Recovered daemon logs alone cannot correlate the phone's readiness at that step. The same suite also had question-answer phone pairing and attention peer-opening failures, making shared connection establishment an investigation lead. The controlled readiness defect is not claimed to explain that occurrence; the underlying historical cause remains unproven. Same-tree or later passing runs are not causal evidence.

## Builder-focused live result

The standard managed launcher executed the named method on 2026-10-13: **1 executed/passed, 0 failed/errors/skipped**, process exit 0. Mobile `20d13f3cf15a57ee56bb3d5f5ffd4e2e4d48daad`, daemon `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f`, Claude Code `2.1.280`. All 19 diagnostic stages started and passed once. `builder-focused-live.xml` preserves the counted result and `builder-focused-live.txt` records provenance and artifact limits. Raw method XML and correlated phone logcat are retained privately under `$AGENTS_REPO_PATH/logs/evidence/2138/focused-live-20261013T0802Z/`, with owner-only permissions and verified hashes. Matching daemon logs are unavailable after successful standard harness cleanup. This pass proves the existing interrupted-transfer assertions but does not establish the historical cause or replace full dispatcher acceptance.

## Returned dispatcher full live evidence

The dispatcher full live gate starting at 2026-10-13T10:20:14.762Z passed: **65 executed/passed, 0 failed/errors/skipped**, including the named upload method **1 executed/passed, 0 failed/errors/skipped**. Process exit was 0. Tested mobile was `732bf5756ca2d1be0d78526b654eb9a35bebe9fe`, merged with main `b107f97a743a508fb9182fdc165b3e084180e250`; retained stderr identifies daemon `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f`.

`dispatcher-full-live.xml` preserves the exact counted XML from retained stdout; it is not original instrumentation XML. `dispatcher-full-live.txt` records counts, revisions and artifact limits. Exact stdout/stderr/metadata, extracted XML, provenance and verified hashes now survive privately at `$AGENTS_REPO_PATH/logs/evidence/2138/dispatcher-full-live-20261013T102014Z/`, with owner-only directories/files. `private-artifact-sha256.txt` includes their hashes. Original per-test XML, correlated phone logcat and matching harness daemon logs were not retained before the gate worktree was removed; no hashes or stage outcomes are invented for them. No code consumes this resource directory, so no matching reader/schema changes are needed.

This full run is the returned evidence checkpoint, not final acceptance of the artifact commit. Review and the dispatcher live gate run again after publication; `needs-real-claude` remains. Documentation consumes these exact outcomes and gaps.


## Cancellation-boundary rework

The verifier's FAIL at `02bd70b05` identified caller-owned timeouts converted into assertions, wrapped coroutine/Compose timeouts classified as ordinary failures, and duplicated readiness implementations. The boundary now captures its caller coroutine context and checks cancellation before failure classification. Direct cancellation keeps the caught exception; cancellation hidden by an operation wrapper propagates from the cancelled caller. Operation-owned deadlines leave the caller active, retain the exact caught exception/wrapper as cause, and receive a fixed timeout stage. The identity-based cause walk terminates on cyclic chains and never emits exception messages. Both readiness entry points share `awaitHostConnection`; Send now preserves its missing-host message.

| Rework capture | Tests | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `rework-diagnostics-red.xml` | 16 | 8 | 8 | 0 | 0 |
| `rework-diagnostics-green.xml` | 16 | 16 | 0 | 0 | 0 |
| `rework-readiness-green.xml` | 31 | 31 | 0 | 0 | 0 |
| `rework-focused-live.xml` | 2 | 2 | 0 | 0 | 0 |

Baseline diagnostic source was `02bd70b05`. The initial probes precede a stack-trace-aware assertion adjustment: exact caller exception identity is checked immediately across the diagnostic boundary, before the enclosing `withTimeout` can recover a stack trace. This preserves the cancellation contract; the old boundary still produces an assertion. All eight failing paths are covered by green checks. Original private red/green XML, counts and candidate source hashes survive under `$AGENTS_REPO_PATH/logs/evidence/2138/rework-20261013T1325Z/`, with owner-only permissions and verified manifests.

The standard focused managed live run at mobile `177e342cfe1ab90de646d30196f33620aabd35f2` and daemon `eb3c757cac48a65f536bdc0dfdc92f0adbd82c3f` passed upload once and Send now once. All 19 upload stages started/passed once in correlated phone logcat. Original method XML, counted XML, both phone logs, emulator log, provenance and verified hashes survive under `$AGENTS_REPO_PATH/logs/evidence/2138/rework-focused-live-20261013T1332Z/`. Matching daemon logs had been removed by successful standard harness cleanup when an ordinary copy was attempted; the empty snapshot record reports that gap. No durable complete process stdout/stderr was captured. `rework-focused-live.txt` records the counts and limitations.

The existing controlled legacy readiness failures, historical post-cut reconnect phase and attribution limits remain valid; all 22 earlier committed private-artifact hashes were reverified. This focused pass exercises the preserved interrupted-chunk, retained composer/attachment, zero pre-retry messages, exactly one final message/attachment, real-turn completion and digest proof. It does not establish the historical underlying cause or replace renewed post-verifier full dispatcher live acceptance. Return fresh exact `dispatcher-full-live.xml` and `.txt`, extend the private hash manifest and this summary after that gate. Preserve available original XML, phone/daemon evidence and revision provenance privately before cleanup, with explicit gaps. Documentation remains pending.

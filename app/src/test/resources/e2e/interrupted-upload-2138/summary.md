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

Both October 12 paired outputs, `2026-10-12T21-36-36-886Z_real-claude-gate_#1896.log` and `2026-10-12T21-36-36-886Z_real-claude-gate-rerun_#1896.log`, are absent from the original macbook-shaped path and local agents logs. The reported candidate was `0c9e9a6f49`, merged with main `427640d577`. Original method XML, correlated original phone/daemon logs, daemon revision, and inventory hashes are unavailable locally. The original failed operation remains unlocated; the controlled readiness defect is not claimed to explain that occurrence. October 10 or later suite passes are not causal evidence.

## Dispatcher artifact handoff

After verifier review, the full dispatcher live gate must execute this named method without failure, error, or skip. Builder-focused execution does not replace that gate. Pending committed files are `dispatcher-full-live.xml` (reviewed counted XML) and `dispatcher-full-live.txt` (method/suite executed, passed, failed, error and skipped counts, tested mobile/daemon revisions, artifact availability, and hash provenance). Extend `private-artifact-sha256.txt` with the actual full-live XML, correlated phone logcat and harness-daemon hashes. Update this summary from those exact records on the builder artifact-return stage; do not reconstruct missing logs. No reader-code change is required.

# Development verification — device-gate artifact retention

Part of [Emulator and real evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence).

### Device-gate artifact retention

A counted stdout report proves method outcomes, but cannot replace per-test phone
logcat and matching daemon diagnostics. Before gate worktree and daemon cleanup,
retain fresh XML, phone logs, correlated daemon evidence, tested revisions and a
verified SHA-256 manifest privately on the persistent agents volume, under
`$AGENTS_REPO_PATH/logs/evidence/1896/<run>/` for this investigation. Keep directories
owner-only (0700) and files owner-only (0600). Publish separately reviewed
summaries containing only allowlisted method outcomes, counts, fixed stage labels,
safe stack evidence, revisions, hashes and artifact availability. Exclude keys,
tokens, pairing codes, credential homes and raw prompt/frame/clipboard content.
Preserving an original exception as cause does not make its nested message safe
to publish unreviewed.

The standard managed launcher supplies `AGENTS_REPO_PATH`; `scripts/e2e-emulator.sh`
uses the private retention helper introduced by #2137 and extended by #2145 on
both success and failure. Numbered `feature/<ticket>` branches default to
`$AGENTS_REPO_PATH/logs/evidence/<ticket>/harness/`; all other branches, including
main and detached comparisons, default to `$AGENTS_REPO_PATH/logs/evidence/harness/`.
Each capture creates a unique `fault-<random>` run directory. An explicit
`E2E_FAULT_EVIDENCE_DIR` overrides the default. The configured agents root must
already exist and be usable; retention does not create a substitute root. No
custom process-inspection wrapper or new live-Claude run is needed for this
cleanup facility.

Only regular, non-symlink `daemon.log`, `daemon-fault.log`, `daemon-b.log`,
`daemon-answer.log`, `daemon-bypass.log` and `relay.log` from the harness working
directory are eligible. The helper opens directories/files without following
caller-owned symlinks, checks regular-file status on opened descriptors and
copies this constant allowlist rather than archiving the tree. macOS's root-owned
top-level `/tmp` and `/var` aliases are resolved before pinning their canonical
directories; caller-owned evidence symlinks remain rejected. New evidence
ancestors, existing managed evidence-subtree directories and run directories are
0700; retained logs and metadata are 0600 from creation. Access is restricted to
the agents account on its trusted volume, not encrypted storage or protection
against compromise of that account. Credential homes, keys, token files, pairing
outputs and transcripts never enter this facility.

`inputs.json` records the validated Mobile and daemon binary revisions, SHA-256
of the frozen launch executable (including PATH selection), configured daemon
source revision separately, and the original `harness_exit_code`. Unknown or
invalid identities are explicitly `unavailable`; source HEAD is not a substitute
for binary identity. Every allowlisted name has an `availability` value:
`retained` after verified copying, `unavailable` for missing optional files,
symlinks or non-regular entries, and `failed` for copy/hash errors. Unavailable
optional logs do not fail retention. `SHA256SUMS` records verified log and metadata
hashes; these establish copy integrity, not authenticity.

Consumers must require `inputs.json`'s `retention_status=complete` before treating
the manifest as a completed set. The authoritative metadata is first written,
synced and verified as `partial`. The helper verifies `SHA256SUMS` against the
intended final metadata, then writes, syncs and verifies `inputs.pending.json`
before atomically replacing `inputs.json` as the completion commit. A partial
record can therefore coexist with a manifest hashing intended final metadata;
neither that manifest nor pending metadata establishes completion. Write,
verification, publication failure or interruption before the commit leaves the
authoritative claim partial. Safe failure output alone cannot prove durable
completion.

Cleanup captures the original exit result, terminates and waits/reaps owned
children, then retains diagnostics synchronously before disposing of harness
data. An original success still removes the temporary working directory even
when retention fails; an original failure keeps it under the existing policy.
Managed isolated credential homes are removed on every outcome, including
retention failure, and managed cleanup skips the separate bypass-transcript
copier. An original nonzero exit code is preserved exactly. Original success
becomes nonzero if configured retention fails, including an absent/unusable
agents root. Without `AGENTS_REPO_PATH` or an explicit destination, unmanaged
runs retain their existing success/failure disposal policy, including failed-run
isolated-home retention.

The helper outputs only an escaped destination and fixed completion/availability
fields; failed or partial retention is explicit, without raw logs or exception
payloads. Inspect raw diagnostics privately and publish only separately reviewed
safe summaries under the limits above; do not commit, post or echo raw content.
This capture does not retain gate XML or phone logcat automatically, and a
builder capture does not prove survival on the final dispatcher run. See
[Offline Retry artifact availability](../../e2e-interactive-stream.md#offline-retry-registration-evidence-2137).
The cleanup repair does not attribute the intermittent failures tracked by
\#2138, #2139 or #2140.

For [permission isolation](permission-modal-overlay-testing.md#held-per-conversation-diagnostics-1896),
the committed
[`permission-isolation-1896/`](../../../app/src/test/resources/e2e/permission-isolation-1896/)
keeps historical counted XML and the scheduler-only 30-second stack, builder
selections of **1/1, 3/3 and 2/2 passes** (each 0 failed/skipped), and
`dispatcher-full-live-pass.{xml,txt}` from the earlier 2026-10-12 full dispatcher
run (**65 executed/passed, 0 failed/errors/skipped**). The private source copies
and `SHA256SUMS` survive under
`$AGENTS_REPO_PATH/logs/evidence/1896/2026-10-12T20-20-20-895Z/`.
Its temporary `real-claude-gate-1896/build/dispatcher-tests/live-ki7gd6v9`
and `/tmp/pyry-e2e-ans.Z7u8UW` directories are gone; their paths are provenance,
not retrievable captures. Per-test XML/phone logcat and matching answer-daemon
evidence are unavailable, and surviving stderr has no permission-stage lines.
Do not reconstruct them from a pass or from aggregate stdout.

The renewed [full live acceptance](../../e2e-interactive-stream.md#permission-isolation-diagnostics-1896)
also passed **65 executed/passed, 0 failed, 0 skipped**, including the named method.
Its durable counted report is distinct from the committed earlier run. Neither
pass establishes the historical cause or an artifact-retention implementation
repair; this checkpoint documents the retention requirement and known limits.

# #2102 — incomplete channel-prompt recurrence investigation

The intermittent behavioural cause is **unproven**. This branch adds method-local operation
labels and content-free connection snapshots so a future failure is retained before cleanup.
It changes no production code, wait condition, timeout, functional assertion or live selection.
No behavioural repair, failing live reproduction, or dispatcher full-live acceptance is claimed.

The original #2009 Linux failure/rerun stderr files named on
[the issue](https://github.com/pyrycode/pyrycode-mobile/issues/2102) are unavailable on this Mac.
The issue records 65 executed/41 failed in the full gate and 41 executed/25 failed in the
failed-only rerun, both with zero skips. This method failed then passed. Those suite totals
cannot identify its operation or cause. #1897's existing host-scoped setup repair remains intact.

## Retained counted evidence

| Artifact | Executed | Failed | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: |
| `diagnostics-red.xml` | 5 | 5 | 0 | 1 |
| `snapshot-red.xml` | 1 | 1 | 0 | 1 |
| `diagnostics-green.xml` | 6 | 0 | 0 | 0 |
| `empty-host-green.xml` | 4 | 0 | 0 | 0 |
| `baseline-live.xml` | 1 | 0 | 0 | 0 |
| `labelled-live.xml` | 1 | 0 | 0 | 0 |

The first controlled red used a `channelScenarioStep` stub returning `block()` without
emitting diagnostics. Its tests failed for absent records. The second red proved a snapshot
exception could mask the original assertion; the repaired wrapper emits only
`snapshot=unavailable` and rethrows the original operation failure. These regressions prove
diagnostic contracts, not the live flake's cause. The final six tests cover success, assertions,
ordinary exceptions, cancellation, repeated stages, message redaction and snapshot failure.
The four unchanged `EmptyHostChannelSetupTest` cases retain #1897's readiness controls.

Both live XML files explicitly contain the passing
`InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack`.
The baseline ran on Mobile `cfc5911723bad305dcf2486283521915dce91b84`; the labelled run on
`d2203aba47c46d19255a64143fded994160bffb2`. Both used isolated daemon
`8091a4730d42f43029eb0b955442dc300106030f` and Claude Code 2.1.280. Revisions come from each
gate's command output, not XML annotations. The labelled method emitted 72 stage records,
all start/complete; two created conversations were deleted and two fixture channels restored.
`labelled-stages.log` retains only those content-free records. Original XML/logcat paths and
SHA-256 digests are in `provenance.json`; XML properties and output streams are omitted.

Commands: scoped `testDebugUnitTest --tests` for the two classes above, and the approved
`android-test-gate.py live --tests` selector for the single named method. The first live
device-acquisition attempt exited 75 with zero executed because another verifier held the
emulator; it is not a test result. The two actual live executions above are distinct diagnostic
runs, not failed-method retries or a full suite.

## Work still required

Obtain the original Linux stderr/artifacts or a fresh failing operation-labelled capture in
the recurrence environment. Correlate its timestamp with transport/daemon events before
cleanup; do not infer a cause from unrelated scenarios. Record the evidenced repair in the
plan, add a failing behavioural regression, implement and verify it. A required external
repair needs its owning ticket and native blocker. Then the dispatcher must run a fresh
passing full live gate and explicitly retain this method's pass, revisions, XML/logcat and
executed/failed/skipped counts before documentation and merge. The documentation stage owns
`docs/e2e-interactive-stream.md`, “Empty-host channel setup (#1897)”.

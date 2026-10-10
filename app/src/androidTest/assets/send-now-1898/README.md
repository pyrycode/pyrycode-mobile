# Send now timeout evidence (#1898)

The historical XML is copied unchanged from the dispatcher logs named in
provenance.json. The full run executed 64 tests with 13 failures, 0 errors and
0 skips; the named Send now method failed. The same-revision rerun executed
13 tests with 1 failure, 0 errors and 0 skips; Send now passed.

Both stderr records identify mobile a7a4b484d7260c4dc7418b85df9c10f2b7245398
and daemon 6019328b378cad587f69b7bc94de37febbdf8556. The recorded feature/main
revisions precede the actual tested merge. The retained failure has a bare
30000 ms coroutine timeout with no scenario call site. Its daemon log has
no Send now delivery event; the contrasting pass has one. The original
phone logcat and full method timing XML no longer exist in the removed gate
worktree, so attribution of that occurrence remains an inference.

The #1731 verifier subsequently diagnosed stopped recovery-daemon pairings
left saved in the shared instrumentation process. Registry selection follows
the latest saved host. Cleanup landed in 61a3a4bb55e7631d7b1526e3205371f0ab38ec2a.
Send now still waited on that global selection before creating its explicitly
addressed harness chat. The controlled tests reproduce this responsibility
mismatch using the real registry and the existing encrypted transport fixture.

selected-host-before.xml records both new readiness checks against the old
selected-host wait: 2 executed, 2 failed, 0 errors/skips, exit 1. One fails with
the same 30000 ms timeout; the other detects false readiness from another host.
explicit-host-after.xml extracts those same checks from the passing affected
class run: 2 executed/passed, 0 failed/errors/skips, exit 0. No connection
selection mutation, retry, skip or deadline increase is used.

New live stage records are bounded to fixed operation names and content-free
phone/peer link state. Diagnostic passes do not establish historical causation.
The fresh full dispatcher live gate after verification is still required.
On return, retain its complete counted XML, method-level result and mobile/daemon
revision metadata beside these assets; record errors and skips as well as failures.

scripted.xml retains the fresh deterministic send-now twin: 1 executed/passed,
0 failures/errors/skips, process exit 0. Its tested mobile commit and daemon
binary revision, dirty-build flag and hash are in provenance.json. This
scripted fakeclaude result does not replace the pending full live gate.

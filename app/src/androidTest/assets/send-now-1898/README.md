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
The returned full dispatcher live evidence is retained below. Historical
causation remains unresolved; the controlled defect and passing candidate are
separate evidence.

scripted.xml retains the fresh deterministic send-now twin: 1 executed/passed,
0 failures/errors/skips, process exit 0. Its tested mobile commit and daemon
binary revision, dirty-build flag and hash are in provenance.json. This
scripted fakeclaude result is separate from the real-Claude full live gate.

## Dispatcher full live run

`dispatcher-full.xml` is the exact normalized XML line in the durable
2026-10-10T02-03-00-622Z_real-claude-gate_#1898.log: 65 executed/passed,
0 failed/errors/skipped. `dispatcher-method.xml` selects the unchanged Send now
testcase into a one-case report: 1 executed/passed, 0 failed/errors/skipped.
It immediately follows interrupted upload in this shared-process full run.

`dispatcher-provenance.json` schema_version 1 names the command, source-file
and artifact SHA-256 hashes, extraction rules and counted suite/method outcomes.
Its `mobile` is the actual tested merge e463a39009544f7332091c692580c6e6d55d188a;
`feature` and `main` are the pre-merge inputs, not the tested mobile revision.
The matching stderr identifies daemon a536d17b1e182fb5398a5458e3afe6079b37a510
and Claude Code 2.1.280 with the stream-json runner. The process exited 0.
The source files remain in the agents repository's logs directory.

The provenance's suite and method objects each contain the artifact name,
listed/executed/passed/failed/errors/skipped counts and checksum; method also
names the qualified testcase. Sources pair a durable filename with its checksum.
Extraction and limitations describe what can be recovered from those sources.
No executable reader consumes these assets and no existing schema was changed.

The normalized report omits individual timing. Raw per-device XML, phone stage
logcat and daemon binary/hash/dirty-build metadata disappeared with the gate
worktree and are not invented here. This is passing full-live evidence for the
tested candidate, without proof of the historical operation or future reliability.
Review and the dispatcher live gate must run again after the artifact handoff;
needs-real-claude stays set.

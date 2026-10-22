# Caching conversation repository — testing

## Supported persistence proofs

`AuthoritativeThreadCacheTest` and the authoritative owned-upsert/window suites retain
saved items, epoch, checkpoint, coverage and retirement fences across fresh storage
instances. Held-I/O and worker probes distinguish admitted work, actual commit,
successful baseline publication and cleanup. Restore/reset/delete probes use exact
host/conversation, runtime generation and incarnation, including reused epoch text.
A successful return or in-memory count alone cannot establish durable fencing.
See [concurrency](caching-conversation-repository-concurrency.md).

Observation cancellation joins its writer and final-flush work. Failed writes must
leave the prior baseline retryable; deletion must prevent delayed/final writes from
recreating content. Bounded windows preserve parent/placement witnesses without
certifying unloaded history. Older hydration belongs to the active observation,
with cancellation and cache lifetime checked before forwarding demand.

Dedicated raw merge/coverage/alias/coalescing tests were retired with their runtime
in #2367. Dormant backend serialization tests remain for #2364. Surviving mixed
presentation tests use supplied facts or literal rows and retain viewport, grouping,
attachment, ownership and privacy assertions.

### Concurrent-acceptance flake retirement (#2379)

`OwnedLegacyCoverageConcurrencyTest.intentInvariant_concurrentAcceptanceSurvivesOlderSuccess`
is retired. [#2367 / PR #2378](https://github.com/pyrycode/pyrycode-mobile/pull/2378)
merged at [`af6721b314e4414034a7a8b58c5630ad0e42eecb`](https://github.com/pyrycode/pyrycode-mobile/commit/af6721b314e4414034a7a8b58c5630ad0e42eecb),
deleting the entire class and the repository's legacy snapshot/accepted-row writer
path. The current `ThreadWriter.accept` takes authoritative snapshots only; dormant
legacy storage is separate. A removed test is retired, never passed.

Both sightings remain historical failures: #2325 on build-fractal-2 at
`2026-10-20T13:42:18.790Z`, and #2334 on Linux/build-fractal-2 at
`2026-10-21T14:58:06.071Z`, revision `75868c31b0ffc803bce34e1d0fb3c48abe1f9d65`.
The latter JVM gate executed 6442: 6441 passed, 1 failed, 0 errors/skipped.
The method exceeded 180 seconds and failed with `InterruptedException` after
interruption. Its separate named rerun executed/passed 1, with 0 failed/errors/skipped
(4.765 seconds). Fail-then-pass leaves the timeout's root cause unproved.
[Refinement evidence](https://github.com/pyrycode/pyrycode-mobile/issues/2379#issuecomment-6319833356)
identifies fleet bundle #2334 run
`d42695f68700eefed16f1424a020c82707bc0cc5f7ee7505081f663b1ef9e641`:
`logs/verifier-gate__2334_4.stderr.log.failure-1792594664113.log` holds the failure,
`logs/2026-10-21T14-57-44-333Z_verifier-gate-rerun__2334.log` the rerun XML,
and `handoff/verifier-2334.md` the counted verdict. All 133 manifest entries were
verified by the verifier.

[Accepted Linux verification](https://github.com/pyrycode/pyrycode-mobile/pull/2445#issuecomment-6330134600)
at `82389892f76bcc8439b1255a9edb0b59d6d7fb09` ran
`python3 scripts/jvm-test-gate.py --with-build-checks`: 5341 executed/passed,
0 failed/errors/skipped. Within that full invocation, `AuthoritativeThreadCacheTest`
executed/passed 23, with 0 failed/errors/skipped; the retired class had zero cases.
Invocation metadata confirms fresh execution. Raw evidence was retained under
`$AGENTS_REPO_PATH/logs/evidence/2379/retirement-82389892f76b/` in
`jvm-joined.xml`, `authoritative-cache.xml`, `gradle.log`, `invocation.json`,
`jvm-wrapper.stderr.log` and `SHA256SUMS`. These are retained capture paths at the
time of verification. The UI gate intentionally executed no device tests for this
documentation-only change; macOS was outside this Linux ticket's scope.

`DurableGapProof` retains saved certification, actual recovery receipts, reset/page
checks and exact rendered order in both deterministic and live offline/reconnect
callers. [Counted acceptance](../../e2e-interactive-stream.md#authoritative-runtime-removal-2367)
distinguishes scripted/replay passes from genuine provider observations.
Attachment retrieval and daemon-delete refusal tests remain separate from content
certification; non-content observables pass through without restoring live prompts.

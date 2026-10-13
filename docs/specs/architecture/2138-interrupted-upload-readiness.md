# Interrupted-upload live readiness (#2138)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_interruptedUpload_retriesIntoOneMessageWithItsBytes`, `cutLinkOn`, `LinkCut`, `awaitComposerHolds`, `setHostLink`, and `awaitConnected`; preserve the interrupted-transfer proof and its deadlines.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowConnection.kt`: `awaitSendNowConnection` supplies the explicit-host relay-and-authentication contract.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SendNowStage.kt` and `PermissionIsolationStage.kt`: fixed stage labels and original timeout causes are the diagnostic analogue.
- `app/src/test/java/de/pyryco/mobile/di/RelayConnectionFactoryTest.kt`: `Fixture` and the Send now readiness checks exercise the real registry, supervisor, and Noise handshake.
- `docs/knowledge/features/attachment-upload.md`: the cut must occur synchronously after an intermediate chunk; either connection-loss or send-failure settlement is valid.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: private XML, phone logs, daemon evidence, hashes, and revisions must survive checkout cleanup; passing runs cannot establish historical causation.
- `$AGENTS_REPO_PATH/builder/device-tests.md`: focused live execution and durable private artifact retention.
- `origin/feature/2118`'s `HostConnection.kt`: use the same additive shared helper contract; unrelated Direct Share diagnostics remain outside this ticket.

## Context

The upload targets the harness host explicitly but setup and reconnect use `awaitConnected`, which follows selection and accepts a legacy relay-only signal (including deliberate Idle). This can admit an unavailable or unauthenticated target, or block a healthy target behind an unrelated stopped selection. October 12 failure/rerun logs are reported retained on macbook but are unavailable locally. Repair the controlled defect without claiming it explains an unlocated historical failure. No decision record is needed.

Remote overlaps in `InteractiveStreamE2ETest` include #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1947, #2009, #2032, #2052, #2057, #2088, #2089, #2090, #2091, #2092, #2098, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118, and #2119. Changes stay local to this method and additive registry tests. #2118 proposes the identical shared helper; it is not required to land first.

## Design

Add internal `awaitHostConnection(registry, serverId, timeoutMs, missingHostMessage)` under sharedTest, matching #2118's proposed helper and #1898's shipped contract. It observes the addressed bundle's combined status until relay and authenticated daemon are both Connected. Wire only the interrupted-upload setup and post-cut readiness waits. Leave selection and unrelated callers unchanged.

Add `InterruptedUploadStage` and `interruptedUploadStep` under sharedTest. Fixed stages cover fixture/setup, peer admission, channel list, target readiness, conversation creation/opening, attachment selection, chunk cut, composer retention, pre-retry history, reconnect, retry, real-turn completion, final history, and retrieval/digest verification. The wrapper executes each operation once, adds no timeout or retry, emits content-free start/pass/failure labels, and preserves original failures as causes. Caller cancellation propagates unchanged. Link snapshots include only presence/selection booleans, static relay/daemon class labels, repository presence, and peer link metadata. Never include prompt, filename, attachment ID, frame content, or digest in stage output.

Retain the existing synchronous intermediate-chunk cut and all zero-message, retained-composer, exactly-one-message/attachment, and digest assertions. Preserve the finally cleanup of link-cut sink, host link, activity monitor, fixture URIs, and peer. No production code or wire contract changes.

## State and concurrency model

The helper collects the target coordinator's existing StateFlow under the original `CONNECT_TIMEOUT_MS`; it owns no background job. Registry tests inject StandardTestDispatcher. Instrumented operations retain their existing runBlocking/Compose waits. The diagnostic wrapper is synchronous and does not collect or mutate connection state.

## State transitions and identity reuse

| Event | Check |
| --- | --- |
| Target ready while selected host is stopped | `interruptedUploadReadinessIgnoresStoppedSelectedHost` |
| Target unavailable while selected peer is authenticated; reconnect same host | `interruptedUploadReadinessWaitsForItsOwnHostEvenWhenSelectedPeerIsConnected` |
| Target relay Connected, daemon Handshaking | `interruptedUploadReadinessWaitsForAuthentication` |
| Idle target never becomes ready before deadline | `interruptedUploadReadinessKeepsDeadline` |
| Caller cancels target wait | `interruptedUploadReadinessPropagatesCancellation` |
| Chunk cut, failed draft retention, reconnect, retry of same conversation | Existing named real-Claude scenario, with unchanged proof |
| Repeated diagnostic invocation, failure or cancellation | `InterruptedUploadStageTest` verifies single execution, unchanged deadline, exact causes, and cancellation |

No process-death or screen-restoration state is added.

## Error handling

Missing target fails immediately with a static message. Readiness expiration retains the supplied timeout; no selected-peer fallback. Diagnostics classify timeout, assertion, and operation failure using static labels, retaining the original cause without publishing its message. Ordinary CancellationException is rethrown. Cleanup remains guaranteed by finally.

## Testing strategy

Write real-registry regression tests first and run them against the legacy selected-host wait to observe the wrong-host and handshake failures, then repair the shared wait and rerun the full affected registry class. Add JVM wrapper tests for one execution, value return, timeout budget/cause, assertion cause, Compose timeout cause, cancellation, and content-free records. Device-only reason: the existing live scenario requires real picker/IME, relay/Noise teardown, and real Claude; run only its named method with the live launcher and inspect fresh counted XML. Preserve private artifacts and commit reviewed content-free summaries under `app/src/test/resources/e2e/interrupted-upload-2138/`. Focused JVM checks, lint, assembleDebug, androidTest compilation, formatting, and final pre-verify are builder work. Post-verifier full live acceptance is dispatcher work.

## Open Questions

- Can the October 12 paired logs be recovered locally? Check their original paths and retained inventory; if unavailable, record that gap and keep historical attribution unproven.
- Will #2118 land before the final merge? Reconcile the identical helper additively; do not migrate its unrelated scenario.

## Documentation handoff

Pending for documentation: update `docs/e2e-interactive-stream.md`, interrupted-attachment-transfer coverage, with the evidenced readiness defect, historical attribution limits, controlled regression counts, and fresh full-live method outcome/revisions/artifact locations. Documentation consumes builder and dispatcher evidence; it does not obtain live proof.

## Sizing

One deliverable, four acceptance criteria, approximately 650 written lines including plan, five readiness tests, diagnostic tests and reviewed evidence; one new internal enum, two live consumer updates, and no production failure branches. All builder ceilings hold.

## Revisions

- 2026-10-13: checked both original macbook-shaped and local agents log paths; neither October 12 paired output is present, and original XML/phone/daemon artifacts and inventory hashes are unavailable locally. Historical stage/cause remains unproven. The controlled legacy wait failed all five readiness checks; explicit-host authenticated readiness passed the complete 27-test registry class. The diagnostic baseline failed six of seven checks, and the wrapper passed all seven after implementation. Raw XML and reviewed summaries are retained separately. The helper matches #2118's additive proposal without requiring that branch or migrating Direct Share.

- 2026-10-13 resume: the verified maintainer handoff locates the original failure at post-cut reconnect (`host link reconnect timed out; repository present=false`). The paired raw logs, original method XML, phone/daemon captures and inventory hashes remain unavailable here, so underlying historical causation stays unproven. Resume only the standard managed focused live command approved in that handoff; do not run the rejected capture wrapper. PR #2125 remains open and main has not advanced beyond the implementation base.

- 2026-10-13 validation: the approved standard focused live method passed 1/1 with zero failures/errors/skips at mobile `20d13f3cf` and daemon `eb3c757cac`. All 19 stages passed. Private counted XML, original XML, phone logcat, revisions and verified hashes survive on the agents volume. Successful harness cleanup removed daemon logs; that evidence gap is explicit. Full dispatcher acceptance and its exact committed artifact return remain pending under `needs-live-artifacts`.

- 2026-10-13 artifact return: retained dispatcher stdout supplies 65/65 full-suite passes and 1/1 named-method pass, all with zero failures/errors/skips, at mobile `732bf5756c`, main `b107f97a74`, daemon `eb3c757cac`. Commit its exact counted XML and reviewed provenance, and preserve stdout/stderr/metadata with verified private hashes. Original per-test XML, phone logcat and matching daemon captures are unavailable after gate worktree cleanup; report those gaps rather than reconstructing them. No reader consumes these evidence resources, so no schema change is required. Recovered historical failure/rerun records and six daemon records now match their retained hash manifests; historical stderr directly confirms post-cut reconnect with absent repository and daemon `9c9cd5a25c`. Original phone/method captures and independent macbook inventory hashes remain unavailable; underlying causation stays unproven. Remove `needs-live-artifacts` after publishing this return; review, renewed full live acceptance and documentation remain dispatcher/later-stage work.


- 2026-10-13 cancellation-boundary rework: the FAIL at `02bd70b05` exposed caller-owned timeouts and asynchronously cancelled operations being diagnosed as failures. `interruptedUploadStep` is now suspend/inline and captures `currentCoroutineContext` before its one operation. On any caught failure it checks caller liveness first: cancellation, including a caller timeout wrapped by an operation, propagates from that context without a failure snapshot. An operation-owned timeout leaves the caller active and becomes a stage assertion whose cause is the exact caught wrapper. Coroutine and Compose timeouts are classified through an identity-based, cycle-safe cause walk; no exception message enters diagnostics. The instrumented method's synchronous `step` bridges through its existing runBlocking pattern without adding a deadline, retry or background job. The eight existing consumers are the live bridge and seven diagnostic tests; no unrelated caller migrates.

  Counted regressions: `callerTimeoutOrNullReturnsNullWithoutFailureDiagnostics`, `callerTimeoutRetainsExactCancellation`, `wrappedCallerTimeoutStillReturnsNull`, and `asynchronousCallerCancellationPropagatesThroughAnOperationWrapper` cover caller lifetime; `timeoutPreservesDeadlineCauseAndSingleExecution`, `wrappedOperationDeadlineRetainsExactWrapperAndDeadline`, `multiplyWrappedCoroutineTimeoutRetainsEntireChain`, `composeTimeoutRetainsOriginalCause`, `wrappedComposeTimeoutRetainsExactWrapper`, and `multiplyWrappedComposeTimeoutRetainsEntireChain` cover operation deadlines and original cause identity. `cyclicOrdinaryCausesTerminateWithoutChangingTheChain` covers termination; existing success, assertion, ordinary failure, repetition and direct cancellation checks retain single execution and content-free records. All 16 ran against the old boundary: eight failed, eight passed, zero errors/skips.

  `awaitSendNowConnection` delegates to `awaitHostConnection`, preserving its existing missing-host message, selection and timeout argument. The real-registry controls now cover wrong-host readiness, held authentication, deadline and cancellation through both entry points, plus `readinessEntryPointsKeepTheirMissingHostMessages`. PR #2125 remains open with the identical generic helper; overlaps with #2089, #2091, #2100 and #2118 in shared readiness/tests stay additive and local. The interrupted chunk, retained composer/attachment, zero pre-retry messages, final one-message/attachment, real turn, digest and finally cleanup contracts are unchanged. Elevated risk remains appropriate; total written work remains below 1600 lines. Independent verifier review and renewed full dispatcher live acceptance remain required after this repair; the prior full pass does not exercise these failure paths.

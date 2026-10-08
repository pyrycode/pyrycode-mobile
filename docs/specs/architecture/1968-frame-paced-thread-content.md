# #1968 — Frame-paced complete thread content

## Files read

- `ui/conversations/thread/ThreadViewModel.kt`: `receivedThread`, `threadItems`, `threadContent`, `state` and `noteNewestBoundary` separate receipt, fold and display ownership.
- `ui/conversations/thread/ThreadFold.kt`: `reduce` and `render` are pure; incremental deltas must never be conflated before reduction.
- `data/repository/CachingConversationRepository.kt`: merged #1966 processes every reconnect/rebase snapshot on `processingDispatcher`; merged #1967 publishes before its independently coalesced row writer.
- `data/repository/ThreadReadEvidence.kt`: `presentedAs` and `checkpoint` bind claims to immutable versions and retain unknown/gap barriers.
- `di/AppModule.kt`: `ThreadDestinationFactory.thread` owns both demo and relay ViewModel construction.
- `ThreadHistoryProjectionWorkerTest`, `ThreadViewModelTest`, `ThreadReadSubscriptionTest` and `ThreadReadViewportTest`: worker, fold and exact-version qualification precedents.
- `docs/knowledge/features/thread-screen.md`: newest-candidate bookkeeping requires exact version/reveal/layout/lifecycle qualification; receipt alone cannot grant sight.
- `docs/specs/architecture/1912-foreground-content-read-checkpoints.md`: retained read evidence must travel with the content it proves.
- `docs/e2e-interactive-stream.md`, “The ladder” and “What rung 3 is made of”: existing live ping and scripted multi-delta scenarios remain integration proofs, not rate measurements.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`, “Security model”: existing authenticated decode, confidentiality and replay boundaries stay unchanged.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot. Opposing dark message bubbles, side actions, rule/label/rule delimiters, fixed translucent header and composer use existing Material 3 scheme and typography tokens. This change affects publication timing only; there are no layout or asset edits.

## Context

ThreadFold CPU work currently runs on main for every input, and the separate receipt/evidence arm can update state independently of its rows. Publish only complete accumulated content at actual display frames, keeping raw fold inputs ordered and read evidence bound to the published version. The diagnosis is source-based, not a measured performance claim. No decision record is needed.

One deliverable: frame-paced thread content with worker reduction and version-bound evidence. Forecast 1100 total written lines including tests and plan, one new exported scheduling type, two existing production construction sites requiring update, five acceptance criteria and fewer than ten reject branches. Existing constructors retain synchronous standalone/test defaults; the destination factory explicitly installs production scheduling in both paths. Repository/codegraph searches show constructor fan-out, but the optional additive parameter requires no other consumer migration. No overlapping in-flight feature branch touches the planned files. Final scope after rework: 1568 inserted/deleted lines across eight files, within the 1600-line boundary; exported types and required consumer migrations remain unchanged.

## Design

Add `ThreadContentScheduling` in the thread UI package, holding an injected worker dispatcher and suspend frame wait. Its production defaults use `Dispatchers.Default` and Compose's Android UI frame clock, which is backed by Choreographer rather than a timer. Both `ThreadDestinationFactory.thread` construction paths install the factory's injected scheduling; `hostConversationModule` binds real scheduling and JVM DI tests override it with a shared controllable frame clock. Standalone ViewModels retain unpaced defaults so existing semantic tests remain deterministic; new probes inject controlled scheduling.

Replace the list-only fold stream with an immutable folded reading containing rendered rows and the evidence of its most recent repository snapshot. Independent main collectors admit full snapshots and live events into the merged unbounded raw-input channel; a single sequential worker consumer reduces every admitted input. The adjacent `buffer(Channel.UNLIMITED)` fuses with `merge` itself, so neither source collector waits behind worker work. Each input reduces and renders under the worker dispatcher, without sampling, conflation or cancellation of individual reductions. Full snapshots including evidence-only changes are processed. `noteNewestBoundary` stays on main and runs at snapshot admission, before sending into the raw-input channel; matching live deltas establish its nonempty baseline at admission.

Carry the folded reading's evidence through `ThreadContent`, alongside queued rows, history tail and markers. Keep the existing cancellable history display projection on `projectionDispatcher`; local history demand and boundary mutations retain their current owner. Overlay coverage gaps only after `historySeed` completes, as #1912 requires. Remove the separate received-snapshot arm from `state`; confirmed read marks remain an independent responsive arm. Bind evidence to rendered rows with `presentedAs` so synthetic live text cannot inherit unrelated snapshot claims.

A cold frame-pacing operator keeps one latest complete pending content value. Its structured child drains upstream independently of the frame wait; only these complete values may replace one another. After a real frame arrives, consume the latest pending value and emit once. A finite source's last value is still delivered on a frame without another input. No timers, process-owned callbacks or growing pending display queues. Composer, dialogs, actions and confirmed read marks stay outside this pacer.

## State and concurrency model

`viewModelScope` owns state sharing and all nested collection/worker/frame jobs. Raw admission never waits for worker reduction: the merged channel is unbounded and preserves per-source order. Pure worker reduction completes sequentially. The raw backlog can grow while a worker is held; cancelling collection releases the queue and both intake collectors. Only completed display content is conflated and bounded to one pending value. Main owns boundary and history mutations. The frame wait uses the main Android UI context; cancellation removes the underlying frame awaiter. The state sharing stop timeout becomes zero so an uncollected destination immediately cancels pending publication and upstream collection. Recollection rebuilds from the repository's current snapshot; no prior destination frame callback survives. The lifecycle connection driver continues closing the socket on background.

## State transitions and identity reuse

| Event | Contract and probe |
| --- | --- |
| Initial empty/nonempty receipt and burst end | `completeStateInvariant_initialAndFinalDeliveryAt60And120Hz`: next frame delivers without another input. |
| Many deltas; repeated turn/message ids; foreign conversation | `rawOrderInvariant_allDeltasReduceBeforeDisplay` and `rawOrderInvariant_saturatedDroppingSourcePreservesEveryDelta`: cumulative text, replay guard, isolation and 1,000 deltas through the real 64-slot dropping/coordinator shape survive held worker work; saturated cancellation/recollection drops old inputs. |
| Finished message before/after turn end | `correlationInvariant_finalMessageAndTurnEndPermutations`: no duplicate or lost final text. |
| Session boundary between updates | `boundaryInvariant_skippedDisplayStillClearsOutcome` and `boundaryInvariant_heldWorkerCannotClearALaterTurnOutcome`: boundary effects survive skipped display states and 200 queued snapshots before a newer outcome. |
| Empty reconnect and overlapping held/cache/history rows | `heldMergeInvariant_reconnectAndOverlapMatchUnpacedFold`: preserve chronology, both held sides and row identity. |
| New receipt while old version is displayed; skipped evidence-only update | `readVersionInvariant_pendingAndSkippedEvidenceCannotGrantSight`: latest receipt cannot advance old content; later valid presentation qualifies without resubscription. |
| Unknown ids, gaps and restored rows | `readBarrierInvariant_pacingRetainsReceiptBarriers`: unchanged #1912 barriers grant no new sight. |
| Long fold held on worker | `mainProgressInvariant_heldFoldPreservesInputsAndAllowsActions`: composer/dialog main work progresses before worker release. |
| Collector exit, re-entry, rotation and ViewModel clear | `cleanupInvariant_cancelAndRecollectDropPendingFrames`: callbacks and pending content cancel; fresh collection has one subscription. Device probe exercises production factory wiring and real frame clock. |

## Error handling

No new network, persistence or user-facing error path. Cancellation propagates through worker and frame suspensions. Existing malformed/unknown identity and history-gap rejection stays conservative. Structured lifecycle logs use static event names/counts only; content, evidence ids, payloads, credentials and keys never enter logs.

## Testing strategy

Write failing controlled-frame ViewModel tests before implementation. Advance explicit 60 Hz and 120 Hz frames, not virtual fixed-delay timers. Compare event permutations against the real pure fold; test boundary effects, reconnect overlap, exact evidence and cancellation. A held dispatcher proves main progress and input preservation. Existing ThreadFold, ViewModel, history worker, read subscription/evidence/viewport and repository/cache tests retain their semantic assertions.

Add one device-only probe using `ThreadDestinationFactory` with an inert snapshot repository, real worker dispatcher and real Android frame clock. This belongs in androidTest because JVM virtual clocks cannot prove real Choreographer scheduling or real worker/main progress. Observe frame timestamps/publication bursts, complete final content and cancellation/recollection; compile and execute its focused method on the managed Android 13 device.

Preserve `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread` and `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread`. Run focused scripted `stream`; fresh full-live and scripted-all named-method counts/evidence are dispatcher-owned handoff. Run focused JVM tests, lint, assemble, Android-test compilation, formatting and forced Spotless. Merge main, push, then run final assemble and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None. Record deviations and probe-driven contract refinements under Revisions.

## Documentation handoff

Pending documentation stage: record dispatcher-produced evidence paths and executed/failed/skipped counts for the named live ping and scripted multi-delta methods in `docs/e2e-interactive-stream.md`, “Verification status”. Documentation consumes those artifacts; it does not execute the gates.

## Security review

**Verdict:** PASS

- [Trust boundaries] Exact `ThreadReadEvidence` travels with folded/paced rows; `presentedAs` excludes synthetic versions. Pending receipt and skipped states cannot confer sight. Existing conversation guard remains in `ThreadFold.reduce`.
- [Tokens] Scheduling has no credential input or storage; authenticated repository commands are unchanged.
- [Files and storage] Complete pending display content is bounded to one value; the destination-owned raw queue is unbounded to prevent dropping incremental inputs, and is released on cancellation. No new disk paths, cache writes, backup policy or secret persistence; #1967 retains cache ownership.
- [Android attack surface] No exported components, intents, providers, permissions or WebViews. Frame scheduling uses the existing Android Compose UI context.
- [Cryptography] Noise IK, nonce ownership, TLS and Keystore are untouched.
- [Network and I/O] Existing decode/length bounds and reconnect backoff remain authoritative. Frame pacing does not sample incremental events or create reconciliation traffic.
- [Errors and telemetry] SHOULD FIX: lifecycle/publication logs contain static events and counts only, never row text, durable ids, payloads, tokens or keys.
- [Concurrency] MUST FIX addressed: non-suspending main intake before sequential worker fold and conflation; main boundary side effects before raw enqueue, including a saturated backlog; structured frame child cancelled immediately on last collector exit. Re-entry cannot publish an old pending version.
- [Threat model] Relay delay/reorder/drop cannot manufacture read sight; hostile decoded daemon content retains existing text rendering/bounds. Floods can grow the destination-owned raw backlog while worker processing is behind; dropping raw events would violate accumulation and boundary contracts. Cancellation releases that backlog; only one complete display state remains pending. Existing wire size limits remain unchanged. Rooted credential theft and keyboard/screenshot/overlay leakage gain no new surface and remain governed by existing storage/UI policy.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

2026-10-08: The first regression executed once and failed because new receipt evidence accompanied an older displayed row. Bind evidence during the sequential fold and carry it through the paced content arm. The production-wired device fixture uses an inert decorated relay destination rather than the demo repository so it can hold complete streaming snapshot versions without any network connection. Add `ThreadPacedReadViewportTest.readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal` to prove the paced versions still pass the actual screen's lifecycle, overlay, viewport and reveal qualification.

2026-10-08 (initial repair; superseded by saturated admission repair below): A new shallow held-worker boundary probe executed once and failed: clearing after worker reduction lets an older boundary erase a later turn outcome. Run `noteNewestBoundary` on main when each snapshot enters its merge arm, before queueing reduction. Matching live deltas establish the same nonempty baseline as their synthetic rows. Worker rendering has no boundary mutation; all ordered raw inputs still reduce. `boundaryInvariant_heldWorkerCannotClearALaterTurnOutcome` initially held one earlier receipt and appeared to prove the newer outcome survived; the verifier's saturated probe disproved that general claim.

2026-10-08 (verifier findings 1–3): Saturated probes disproved the earlier admission claim: bounded `merge` sends delayed receipt bookkeeping and backed up the coordinator into the repository's dropping source. Fuse an unbounded raw-input buffer with `merge` before the worker suspension; preserve every admitted input and run boundary bookkeeping without worker pressure. Extend the source fixture to the production 64-slot `DROP_OLDEST` plus connection-switching seam, test 1,000 deltas and cancellation/recollection, and hold 200 snapshots before a boundary/newer outcome. Inject scheduling through `hostConversationModule` and `ThreadDestinationFactory`; drive explicit shared frames in the host-isolation DI test while retaining queue, content, actions and reconnect assertions.

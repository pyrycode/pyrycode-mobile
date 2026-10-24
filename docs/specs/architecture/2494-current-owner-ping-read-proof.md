# Current-owner scripted ping read proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread`, original read, unread and peer-read assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/OwnedReplyObservation.kt`: `OwnedReplyProbe.wrap`, preserving current replay readiness and producer recovery while adding a passive trace.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidenceOwner.matches`, strict ownership remains unchanged.
- Retained commit `8534e6ca2babec13ba8ac0981565590fb946dd19`: `PingReadProof`, its seven tests, native ping sampling, metadata trace and attention diagnostic.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: exact-host repository and attention ownership.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `completeAuthoritativeReadSummary`, `refreshUnavailableAuthoritativeReadFacts` and `invalidateWithdrawnReadSummary`; #2493's current correlation and revocable recovery are already wired and stay untouched.
- `docs/knowledge/INDEX.md`, `features/development-verification.md`, `features/development-verification-gates.md`: current-source evidence, strict settlement and focused native boundaries.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: attention precedence and authoritative current repository selection.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: bounded opening-summary recovery, revocable queued authority and independent daemon read facts from #2493.
- `docs/e2e-interactive-stream.md`, Phone read-mark proof (#1912), and `docs/recorded-tests.md`, Unavailable-summary recording integration (#2467): receipt/classification order and historical evidence limits.
- Configured Core `docs/protocol-mobile.md`, Security model: the authoritative wire/security contract; no wire change here.

## Design source

N/A: test proof and content-free diagnostic only; no visual behavior changes.

## Context

Continue PR #2468 under #2494 without modifying its original branch, worktree or evidence.
Prerequisite #2493 / PR #2499 merged through main at `90c0747af00780798f266d48543a5425fb455857`.
The starting main is `ba8c54c3d18a2d3a2f93acfa60b8c2004674a1e8` and includes that merge.
Reuse its metadata/replay/restart implementation; do not transplant older versions of shared helpers.
No decision record is needed.

The authoritative predecessor verifier bundle is run `18ddeeede50af3312b066b5571ec64d526f549729171adc788b23a62599b240c`,
digest `9c15a3b6b49c4423d57457adb506488a19c734fd2290ca637bf83999116e7028`.
Its two configured attempts on `4711539ab3da57ea23670fd46b0a9b48b9d2a478` each ran two methods: one pass, one final-read timeout, zero errors/skips.
Both terminal observations had current exact certification but unavailable shown metadata; confirmed positions differed (9 and 0).
This observation does not identify the cause. Opening-summary recovery and later focused passes do not retrospectively explain either failure.
Keep recovery receipt `1645eda4baf1d7e3bfb6eaff05f6530580dfe8fa123d6e050476668b53bd3081` as assessment-queued, zero executed.
No sequence/load or complete-suite builder invocation is authorized. #2469 retains further occurrence reconciliation.

Remote overlap: #1766, #1947, #2091, #2098, #2104, #2175, #2311, retained #2462, #2511 and #2514.
Their changes concern other methods, additive hooks or existing predecessors; #2514 owns durable-gap restoration.
Keep this extraction local to ping and additive diagnostics; no dependency on their unmerged work.

## Design

Restore internal `pingReadMarkConfirmed` and `PingReadProofTest` from the retained commit.
Keep the captured durable host/conversation/epoch/reply fixed; form a candidate owner using only the current supported connection and snapshot generation.
Require authoritative admitted evidence, exact raw reply, sufficient checkpoint, no repair/reset, available shown metadata and daemon-confirmed read coverage.
After sampling, require identity with a fresh immutable snapshot. No stale certificate or read position can prove another owner.
The native consumer resolves the current concrete repository each sample, reads its evidence and marks, then repeats snapshot, connection and support checks after bounded diagnostics.
Retain the controlled reconnect after initial reply certification and the original 90000 ms final wait.
Retain all original rendering, durable-gap, unread, peer-read, chronology and teardown assertions.

Add only `OwnedReplyProbe.readTrace` and its inbound/outbound callbacks to the current helper; retain `ThreadQueryReadiness.wrap`, `producerSnapshot`, `admittedIfCurrent` and existing receipt regressions.
Restore `HostConversationSource.attentionDiagnostic` without requests or mutation.
The native trace selects fixed frame kinds, bounded numeric metadata and hashed owner coordinates; never log payloads or text.
The callback is cleared in a nested `finally` even if terminal diagnostic sampling throws.

## State and concurrency model

No production jobs, flows or lifecycle state added. The existing synchronized attention diagnostic takes one read-only snapshot.
The passive callback is volatile, installed only around this native proof and cleared on exit.
Each sample may race replacement/reset; reject changed snapshot identity both inside the helper and after diagnostics/support checks.
Reconnect transfers neither sight nor certification; the replacement must independently certify the same exact reply.

## State transitions and identity reuse

| Event | Required proof / test |
| --- | --- |
| Replacement connection with retained durable reply | `replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner`; native controlled reconnect |
| Supported source changes during sampling | `replacementWhileSamplingCannotMixOwnersOrUseRetiredFacts` |
| Missing/behind shown or confirmed positions | `qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation` |
| Incomplete checkpoint, reset, repair or missing epoch | `certificationMustBeCurrentAndCoverTheExactReplyRevision` |
| Reply id reused across host/conversation/epoch or changed raw facts | `reusedReplyIdsCannotCrossHostConversationEpochOrChangedFacts` |
| Same connection reset with reused epoch | `reusedEpochResetDuringReadSamplingRequiresFreshExactReplyCertification` |
| Same connection reset with changed epoch, later certification or same-generation repair | `changedEpochResetDuringReadSamplingCannotProveOriginalReply` and reused-epoch test |
| Native proof exit or sampling error | nested `finally` clears the passive trace; normal scenario teardown remains |

## Error handling

Unavailable evidence returns false and retains the existing assertion deadline; no inferred shown/read values, pending writes or checkpoint-only fallback.
Diagnostics do not issue recovery requests. Product summary recovery remains entirely in #2493.
Retain failures as failures and keep distinct residual causes outside this proof repair after assessment.

## Testing strategy

Restore the seven controlled unit regressions first and execute the retained reset-sampling control without its final snapshot fence; expect the two reset cases to fail for withdrawn certification.
Then restore the reviewed fence and run `PingReadProofTest`, unchanged `ThreadEvidenceTest`, `OwnedReplyObservationTest`, `NegotiatedThreadActivationTest` and affected host-source tests.
The invariant probes above cover delayed facts, duplicate/held positions, foreign identities, replacement and same-connection reset.
Run focused managed `scripted ping` on macOS: both named ping and durable-gap methods must execute/pass, zero failures/errors/skips.
Native execution is necessary for real encrypted daemon/relay transport, foreground read qualification, reconnect and peer confirmation; Robolectric cannot establish these facts.
Preserve raw XML, phone/control/daemon/relay traces, effective source/APK/binary identities, cleanup and verified hashes in persistent private evidence.
Run lint, assembleDebug, instrumented Kotlin compilation, formatting and final `pre-verify.py --gradle` after merging main and pushing.
Dispatcher-owned configured scripted acceptance and independent configured gates stay pending; focused results cannot replace them.
Linux is outside this ticket's verification scope.

## Open Questions

- Historical configured timeouts: cause remains unestablished; retain the existing queued recovery receipt and all failures. No passing retry will be described as causal evidence.

## Documentation handoff

- Pending documentation stage: `docs/e2e-interactive-stream.md`, Phone read-mark proof (#1912): current-owner exact-reply certification, same-connection withdrawal, final snapshot fence, counted integrated proof and residual investigation outcome.
- Pending documentation stage: `docs/recorded-tests.md`, Unavailable-summary recording integration (#2467): integrated native evidence and its distinction from configured acceptance. Preserve historical failures, tailed durable-gap logs, differing protobuf copies and #2493's metadata/replay/graph-restart documentation.

## Security review

**Verdict:** PASS

- Trust boundaries: `pingReadMarkConfirmed` trusts only current supported authoritative evidence and exact raw reply equality; host/conversation/epoch remain fixed. Global `ThreadEvidenceOwner.matches` stays strict.
- Tokens/secrets: neither diagnostic reads credentials. The trace logs only fixed classifications and numeric facts; owner coordinates are hashed, and message/payload/keys/tokens are excluded.
- Files/storage: no application storage changes. Raw evidence stays private with owner-only permissions and hashes; no daemon-authored input becomes a filename.
- Android attack surface: no component, intent, provider, WebView or rendering change.
- Cryptography: existing Noise implementation, keys and counters are untouched; replacement evidence cannot borrow a retired certificate.
- Network/I/O: no new requests, dependencies, timeout increases or retries; #2493 owns bounded metadata recovery. Existing frame caps/TLS/transport behavior remain.
- Errors/logs: bounded native diagnostics, no arbitrary frame types or text. Production diagnostic returns only static flags and numeric positions and emits nothing itself.
- Concurrency: MUST FIX addressed in design: same-connection reset can retain confirmed marks while withdrawing certification; mandatory snapshot identity checks inside the proof and after diagnostics reject it. Callback cleanup uses nested `finally`.
- Threat model: delayed/reordered relay facts cannot qualify retired or changed evidence. Rooted-device token theft, hostile-frame parsing and screenshot/keyboard access remain with existing Keystore, codec and UI protections; this proof introduces no new access path.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-24

## Revisions

### 2026-10-24: required pair blocked before the final-read proof

Pushed implementation `59e233ac81fcf4495e340df6008b0d2e78aaf545` passed all six focused unit classes: 128 executed/passed, zero failures/errors/skips. The missing-fence control failed exactly the two reset cases (7 executed, 5 passed, 2 failed). Separate `spotlessApply` passed; combining it with compilation had failed before test execution on a changing class-file input. The multi-class launcher refusal also executed zero tests; subsequent unit invocations each selected one class.

Fresh macOS `scripted ping`, directory `build/dispatcher-tests/scripted-__jwg8tx`, exited 1: 2 executed, 0 passed, 2 failed, zero errors/skips. Durable-gap timed out in its deliberately offline production arm; the following ping logged `local_send_window state=closed reason=send_failed` and timed out at the initial reply-render wait. No `ping_read_proof` sample executed. This is not the historical configured final-read failure and does not explain it. The primary durable-gap timeout remains unestablished.

The current `interactiveTurn_seededChannel_durableGapCatchUp` calls `restoreLink` only through successful `proof.catchUp`; its outer `finally` closes only the peer after `severLink`. Open #2514 owns bounded restoration on exceptional exits and preservation of the primary failure. This fresh source/log evidence makes #2514 a real dependency for reaching and validating this ticket's proof, beyond the harmless file overlap assessed during planning. Do not import its unmerged repair or weaken either original method. After it lands, integrate main and rerun the unchanged original pair; a persistent primary durable-gap timeout still needs investigation in its owner, rather than attribution from the ping method name.

All failed native XML/protobufs, phone logs, control/daemon/relay traces, finished phases, build metadata and identities are retained at agents `logs/evidence/2494/builder-macos-20261024/native-failed/`: 23 copied entries verified, manifest SHA-256 `ef12edb8feab2276b40191864027a221d3c3527190db408a1ef4b56c387e0e37`; the original harness manifest verified 4/4. Core source was clean `64ef0e0fba71a723b7fdc97141cfc144f6355664`; effective daemon SHA-256 `3df9f8c510735f4e1f8834624c8fb2cbe6d96ca97c4b01afca292f74e699ff83` has no embedded revision. Relay build metadata names `72f40c5de9e851e6c6c1ca3c789761320e05ce51`, binary SHA-256 `17e24131cfd3f5a0bc48f972021db17925347311e3dcdf362f2aa8cfe48fc710`. Built app/test APK hashes are `685ba7a6d63ae5c1f0572fd3a1aaf217a4901a04213e67f6372fac3609e3d5da` / `f5e673169cff810ce5d220a85a2ced8a937006d42e337c1a8c450c1b5009c40d`; these are built-byte identities, not installed-byte readback.

Implementation, plan and historical evidence remain preserved. The required successful native pair, lint and final formatting/compile/pre-verification checks, PR publication and dispatcher-owned configured acceptance remain pending. No wider diagnostic was started; the retained historical recovery receipt still records assessment-queued, zero executed.

The approved `add-blocker 2494 2514` helper failed twice with `Shared GitHub unavailable` HTTP 503. Authoritative relationship readback after each attempt showed only closed #2493; #2514 was not saved. This service failure prevents the required waiting-on-blocker routing, so the run reports `blocked` with #2514 as the intended dependency. This was not an approval rejection. Retry the managed relationship operation when service is restored; do not bypass the bridge.


### 2026-10-24: bounded manual continuation plan

The earlier instruction to retry adding #2514 as a blocker is superseded. Fresh
operator readback shows #2494 -> closed #2493, #2469 -> open #2494, and #2514 ->
open #2469. Adding #2494 -> #2514 would create a cycle. Do not perform that write.
The manual hold stays until a supported independent review/documentation handoff.

Reuse the retained #2514 candidate `87135e971da5af043567eb7f9f30a53185697b2b`
as explicit integration input, preserving its commits, native isolation evidence,
and separate Linux 2/2/configured acceptance requirements. This does not accept,
close, transfer ownership of, or remove a dependency from #2514. Its full original
controlled scenario remains separate from the unchanged normal ping pair.

The #2494 native XML was independently recounted: 2 executed, 0 passed, 2 failed,
0 errors/skips. The first offline batch was posted, but no subsequent send or daemon
restart appears before the producer wait times out. The exact rejection boundary
is not present in the retained logs. Add a passive structural snapshot to that
producer observer and emit it in the native producer wait's finally, including
fixed phase, counts, pending/accepted/completed/rejected/current flags, checkpoint,
coverage and exact-post-present boolean. Never emit payloads, text, credentials,
raw exceptions, conversation identifiers or arbitrary frame/error strings. Preserve
the original exception and all waits/assertions. Test the snapshot for pending,
certified, rejected and retired states before implementing it.

Inspect one affected native pair on this materially changed integration to diagnose
the original missing boundary and avoid known secondary contamination. Preserve
failed evidence independently. If the result identifies a distinct repair, provide
an owning proposal rather than weakening proof, retrying for green, or silently
absorbing the repair. Core's accepted #3710/#3721 changes already occur in current
published `64ef0e0fba71a723b7fdc97141cfc144f6355664`: unavailable retirement evidence
remains a truthful refusal. Neither that source fact nor a focused pass diagnoses
the historical configured final-read failures.

Author security review of this continuation: PASS. The new observer read is
synchronized, passive and test-only, with fixed labels and scalar values. It grants
no authority and changes no requests, identities, deadlines or certification. The
reused cleanup preserves the primary throwable and independently closes resources;
its integration remains subject to independent review. Raw evidence stays private.

### 2026-10-24: bounded diagnostic result and owning proposal

The continuation preserves #2514 candidate `87135e971da5af043567eb7f9f30a53185697b2b`
and merges current main `00dbea8e033aa78b750d05b54dc56d0e5fdc46e3`. Native-tested
source is clean `7b755c27b0665f93b55d18b4f53cf90375b46155`; later evidence-only
commits do not represent another native execution.

The passive producer diagnostic's empty implementation failed 1/1 as intended.
The first implemented class run passed 20/21; its new test incorrectly compared
fresh evidence wrappers by equality. Correcting that assertion to require identity
of the underlying immutable snapshot produced 21/21 passes. Integrated
`DurableGapIsolationTest` passed 10/10. Both selector checks passed after the merge.
Baseline lint, debug assembly and instrumented compilation passed with unchanged
production sources. On the pushed merged source, `pre-verify.py --gradle` passed
Spotless and every Kotlin source-set compilation. All failed results are retained.

Fresh normal macOS `scripted ping`, gate `scripted-y5u1b3ku`, exited 1. Raw XML:
2 executed, 1 passed, 1 failed, zero errors/skips. At 12:41:29.324 local time,
durable-gap received a correlated query failure. Its terminal passive observation
at 12:42:59.130 reported `rejection=query_failed`, `last_query=3`,
`completed_query=2`, `items=2`, `checkpoint=2`, `completed_version=2`,
`post_observed=false`, `produced=false`, `rejected=true`, `retired=true`,
`pending=false`, `older=false`, `repair=false`, `reset=false`. This fixed category
maps only to wire `thread.query_failed`; it does not identify the Core refusal path.
The original 90000 ms timeout remains the primary failure.

Exceptional cleanup logged restoration attempt, restored, peer closed and probe
ended before the following ping. That ping passed, including the controlled
replacement, exact current reply certification, unread and peer-read assertions.
Its final read sample reported a retired captured connection, a different current
owned connection, checkpoint 12, exact reply id/revision 9/10, authoritative shown
9, confirmed 9 and `proved=true`. The former secondary send-before-render failure
was absent in this run. This focused pass does not explain the two historical
configured final-read failures and does not accept #2514's Linux requirements.

Private evidence is retained in this continuation workspace under
`work/evidence/native-diagnostic`: 27 manifest entries verified, plus all 4 nested
harness manifest entries. Manifest SHA-256:
`c487f00419d4fc427f75e689b932d5c28c687d1959502fb7136746474efc24e6`.
The bundle includes raw device XML/protobufs, both phone logs, daemon/control/relay
logs, gate phases ending in `finished`, source metadata, and both built APKs.
Built app SHA-256 is
`685ba7a6d63ae5c1f0572fd3a1aaf217a4901a04213e67f6372fac3609e3d5da`;
built instrumented APK SHA-256 is
`064cc5c5ad092b61c9c36f8aea35e1483c7a6e6d4493f03a7e222b3be5d6ec0e`.
These are built-byte identities, not installed-byte readback. Debug metadata is
fixed (`GIT_SHA=dev`, version code 999999); source is established by the clean Git
state and harness revision. Retained daemon/relay hashes and their differing
revision provenance remain exactly as recorded above; no product deployment occurred.

Owning proposal: correlate the durable producer's physical peer/query 3 and
checkpoint 2 with Core `admitThreadQuery`, `runThreadQuery`, `sendThreadReply` and
`cancelThreadQuery` in `internal/relay/v2session_catchup.go`. These paths share the
same fixed refusal code. Use a gated test of offline post publication overlapping
the next producer query, recording admission, provider usability/error,
cancellation and selected-reply/delivery cuts without payloads. The post command
return is not evidence that asynchronous publication has settled. Reuse existing
ordered-output barriers; preserve newer/foreign-epoch cancellation and truthful
unavailable guards. Establish failing-before/passing-after evidence in the owning
Core scope before proposing a repair. No unchanged native retry, deadline change,
failed read retry, stale certificate or weakened assertion is justified here.

Draft PR #2531 is the concrete handoff. The required native pair remains short of
2/2; manual hold, independent review, configured acceptance and documentation stay
pending. #2514 retains separate Linux 2/2 and configured acceptance. #2469 retains
occurrence reconciliation; no dependency edge or workflow label was changed.

### 2026-10-24: reviewed integration and normal verifier handoff

The existing owner resumed under explicit authority to integrate the reviewed
stack and hand back to normal independent review. PR #2535 at
`c991edc3823097fb2cc08ee12592a328a257c03e` is integrated by an ordinary merge into
PR #2531, preserving its original commits and #2514 provenance. Current main
`a9ddcb1f3204a463261be5bde6940d3e85d26c9c` is merged without conflicts. No GitHub
PR merge, production deployment or runtime pin change is part of this handoff.

Core #3857 is now closed after independent acceptance, documentation and PR #3858.
Its accepted contract distinguishes correct pre-capture live cancellation from a
consumer obligation to query again from the last completed checkpoint. The fresh
instrumented occurrence attributed query 3/checkpoint 2 cancellation to newer
same-epoch live progress before selection. It does not establish the cause of the
original uninstrumented failure or historical configured final-read failures.
Those failures, the planning MISSED disposition and unavailable identity/exclusive
cause facts remain retained. Core acceptance does not certify Mobile acceptance.

The integrated producer recovery recognizes only exact retryable
`thread.query_failed`, records the refusal, abandons partial decoder/store facts,
restores the last certified snapshot and requires a fresh current-physical-source
successful final marker. The original accepted send is retained; neither sends nor
`markRead` are retried. The original 90000 ms deadline and all proof fences remain.

Retained producer tests at the repaired source executed 25: the red run passed 22
and failed 3; the first green attempt passed 23 and failed 2 due to request-wrapper
equality assertions; payload comparisons corrected those test assertions and the
final run passed 25/25 with zero errors/skips. The deadline unit injects catch-up;
the actual peer loop has separate source, compile and native proof.

The repaired normal macOS pair at `c991edc3823097fb2cc08ee12592a328a257c03e` executed
2/2 passed, zero failures/errors/skips. It retained a real query-3 refusal, then
fresh query-21 certification at checkpoint 253 and durable phone recovery at 495.
Both original methods and every assertion passed. The integrated selected source,
production tree and relevant build/launcher inputs are unchanged from that run;
current-main differences concern other test scenarios and documentation. Reuse is
supported by the private `work/resume-source-proof.json` object comparison and
seven reverified evidence manifests; it is not a new native execution or a claim
that the whole instrumentation APK is identical after the main merge.

Native manifest: `912f13ae7391d400ff4bde542ba64e77c33c7ebfe72d83e1d61cf931bb321361`
(27 entries plus nested harness 4). Retained daemon SHA-256:
`3df9f8c510735f4e1f8834624c8fb2cbe6d96ca97c4b01afca292f74e699ff83`;
relay: `17e24131cfd3f5a0bc48f972021db17925347311e3dcdf362f2aa8cfe48fc710`.
The daemon has no embedded VCS binding; recorded Core checkout `64ef0e0f` does not
independently bind those bytes to source. Built app/test APK hashes:
`685ba7a6d63ae5c1f0572fd3a1aaf217a4901a04213e67f6372fac3609e3d5da` /
`df97e6404e31f54e2a21962871ba4bc5b80a0d8b375bfb7ae7ac9c8112d8b3c5`.
These identify built bytes, not installed readback. Debug metadata remains fixed
`dev`/999999; clean Git and the harness establish the recorded Mobile source.

The final author gate runs all five pre-verify checks, including current-main,
Spotless and every Kotlin compilation, through the unchanged checker functions.
Its GitHub label-read input is replaced only by a fresh protected operator Mobile
snapshot; no check is omitted or weakened. Exact checker/adapter hashes, source,
input revision and results belong in the final private receipt and PR description.
This is explicitly not an unmodified pre-verify CLI invocation, nor the earlier
four-entrypoint-only validation. The normal verifier still runs its own gates.

After required author checks pass, publish the exact integrated head and establish
normal independent verifier custody through the protected workflow. Release only
the obsolete #2494 manual takeover and active builder/escalation markers, retaining
error-retry history, closed prerequisite links, priority, claim and unrelated state.
Do not declare ticket PASS from author evidence. Normal configured acceptance and
actual documentation are mandatory. Documentation retains the Phone read-mark
proof (#1912), Unavailable-summary recording integration (#2467), and #2514 failure
isolation provenance. #2514's separate Linux acceptance and #2469's occurrence
reconciliation remain with their owners; this integration accepts neither.

### 2026-10-24: documentation prerequisite resolved

The original author pre-verify at `66288f5d0249e8a256a67f7e72223718dc98e2fb`
completed exit 0: all five unchanged checks, including forced Spotless and every
Kotlin source-set compile, passed through the documented protected-label adapter.
The separate docs guard remained a real inherited failure: the unchanged overview
was 50,922 bytes, over its 50,000-byte cap, on both the candidate and main.

Manual documentation repair PR #2540 is merged through current main
`099d9f36af6be0e02bd64df184c0fa7cb830a85e`. The existing candidate incorporates it
by ordinary merge. Only its four documentation files changed; production, test,
fixture, build and launcher inputs are unchanged. The actual unchanged docs guard
now passes on the integrated candidate. The prior failure and completed source
review remain retained; this handoff does not repeat the completed documentation
repair or certify independent Mobile acceptance. Required final author build and
pre-verify results are recorded in the PR and private handoff receipt.

The normal independent verifier remains responsible for configured acceptance and
counted scripted outcomes. Actual documentation pickup remains pending. Preserve
all closed prerequisite links, historical errors and failed evidence, #2514 Linux
ownership and #2469 occurrence reconciliation when transferring custody.

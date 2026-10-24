# Recover unavailable opening read metadata

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `observeConversations`, `observeConversationSnapshots`, `publishAuthoritativeCompletions`, `completionIsCurrent`, and summary refresh own the join.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: immutable snapshot generations fence a response dispatched before reset.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention` already collects `observeHostReadMarks`; `updateAttention` fences repository replacement. Do not duplicate the superseded #2467 wiring.
- `app/src/test/java/de/pyryco/mobile/data/repository/NegotiatedThreadActivationTest.kt`: retained live-summary regression and controlled opening-summary patch prove the production connection path.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: `drain` must release prerequisite-ready acknowledgements before termination.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteReadReceipts.kt`: retain intermediate captured snapshots and exact same-owner query prerequisites.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteReadReceiptsTest.kt`: retained receipt ordering and peer-notification regressions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `restartApp` must await process background before graph replacement and launch.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/NegotiatedAgentRestartProof.kt`: placement polling must tolerate a temporarily absent semantics list.
- `app/src/androidTest/assets/recorded/suite.json` and its five affected suite assets: genuine #2467 request contracts.
- `docs/knowledge/INDEX.md`, `features/remote-conversation-repository-reads-and-thread-store.md`, and `features/dependency-injection-host-conversation-source.md`: held content cannot certify checkpoints; unknown shown facts cannot authorize read or unread fallback.
- `docs/recorded-tests.md`: strict request matching, causal release, genuine capture and comparison requirements remain binding.
- Sibling `pyrycode/docs/protocol-mobile.md`, `conversations` and Security model: the [daemon summary contract](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#conversations) remains authoritative; no wire change or cryptographic admission change.
- Retained commits through `8534e6ca2babec13ba8ac0981565590fb946dd19` and controlled patch at `logs/evidence/2462/rework-unavailable-metadata-20261023/controlled-opening-summary-red/`: extraction excludes ping proof and unrelated merged history.

## Design source

N/A: internal metadata and replay correctness only; no composable, layout, style or interaction changes.

## Context

A replacement repository can certify held terminal items without a new live terminal. The existing retained live-summary recovery has no opening-list request to join against. Track that opening observation on its repository, preserving unknown metadata until the daemon responds. No new public API or decision record is required.

This is one behaviour: bounded recovery of unavailable daemon facts, with its required strict replay coverage. Preserve the existing public and wire contracts and isolate the unrelated ping-proof continuation.

Overlap scan found retained #2462 and older feature branches sharing these files. Current #2389 adds child demand, #2491 adds checkpoint placement inspection, #2476 changes rotation certification, and #2338 adds unrelated logging. Their blocks do not supply prerequisites to this repair; preserve local additive edits. The original #2462 branch/worktree and evidence remain intact.

## Design

Reuse the reviewed live-summary recovery and genuine #2467 assets, plus causal receipt/restart corrections through the retained source. Do not copy host-attention production changes already superseded on main or any ping-proof changes.

Opening list requests register request identity and the store generations observed at dispatch. Their correlated, decoded conversation rows create one recovery observation per row only when its generation has not changed. The summary supplies availability, never certification or epoch. The shared summary recovery checks current completion ownership, epoch, generation, repair/reset state and checkpoint before atomically consuming the observation and sending one follow-up. The follow-up is not itself registered for recovery. Available metadata, including zero, consumes authority without a request. Live completions retain the same bounded join.

Use private repository-owned records only. Keep `ThreadEvidenceOwner.matches` unchanged. Existing projection/ledger consumers apply actual daemon shown/read facts and retain waiting/running precedence. Recovery never marks read or transfers sight.

## State and concurrency model

The existing inbound collector serializes replies and certification. Scope-owned refresh coroutines recheck connection/generation and current certification before sending, and consume the exact queued observation identity at dispatch. Withdrawal can revoke that identity after observation consumption and before dispatch; later certification cannot revive it. Request observations are connection-local; collector completion clears them. Opening generations are captured before sending, not when a delayed response arrives. Reset invalidates old observations; withdrawn certification cannot trigger recovery. No timers, polling jobs or new flows are introduced.

## State transitions and identity reuse

| Event | Required test |
| --- | --- |
| Opening response before certification | `reconnectCertificationRefreshesUnavailableOpeningSummary` |
| Certification before opening response | `reconnectUnavailableOpeningSummaryAfterCertificationRefreshesOnce` |
| Duplicate response/repeated certification/unavailable follow-up | opening-summary bounded-input probes and retained `repeatedCertificationCannotRefreshTheSameLiveSummaryAgain` |
| Available/explicit zero shown metadata | opening-summary available/zero probes and retained live-summary available cases |
| Held content without certification, foreign response/owner/conversation | opening-summary isolation probes |
| Reset with reused epoch, retirement/replacement, withdrawn certification | opening-summary invalidation probes |
| Fresh opening observation after reset, before fresh certification | `newOpeningObservationAfterResetWaitsForFreshCertification` |
| Intermediate query batch withdraws completion before final recertification | `intermediateQueryWithdrawalCannotReviveOpeningAuthority` |
| Unavailable response queues recovery before buffered withdrawal/restoration/recertification | `queuedOpeningRecoveryCannotSurviveWithdrawalAndRecertification`; fresh observation remains eligible |
| Real metadata response and daemon-confirmed coverage | opening-summary read-facts/attention assertions; no `mark_conversation_read` |
| Captured query ordering and intermediate receipts | `coalescedReadCannotExposeItsSnapshotBeforeTheCapturedQuery`, `anIntermediateCapturedReadMustNotWaitForTheQueryItCauses` |
| Prerequisite readiness before owner termination | `readyReadAcknowledgementsPrecedeTheirOwnersInboundEnd` |
| Peer progress cannot select a future snapshot | `peerReadProgressCannotChooseAFutureSnapshotForAnEarlierPhoneRead` |
| Guarded peer notifications remain required | `peerProgressReleasesTheCapturedPhoneNotificationWithoutALocalRead` |
| Graph restart and settlement reconnect | `RecordedMobileSuiteTest.interactiveTurn_earlierSessionAgent_survivesRestartAndSettlementReconnect` |

## Error handling

Malformed or uncorrelated replies cannot authorize recovery. Refused sends discard their observations. An unavailable follow-up remains unknown and cannot loop. Existing query errors, expiry and connection retirement preserve their established behaviour. No new UI errors or exception paths.

## Testing strategy

Write/import regressions first and observe the two opening-summary failures before implementation. Run affected `NegotiatedThreadActivationTest`, `SuiteReadReceiptsTest`, relevant tape/close tests and existing host-attention precedence coverage. Preserve counted JVM XML and failed attempts.

Run the five named affected recorded methods and the restart method on this macOS candidate, with the original assertions, strict matching and deadlines. These existing native tests need real lifecycle, storage and rendering. No new device-only class. Retained genuine captures must cover the outgoing contract; otherwise use the documented genuine capture/strict comparison workflow and retain its actual disposition. One IDENTICAL/four CHANGED historical comparisons stay historical. Full configured acceptance belongs to the dispatcher; paired final-read native acceptance belongs to #2494. Linux is outside this ticket's verification scope.

Run focused tests, lint, assemble, instrumented compilation, formatting and final `pre-verify.py --gradle` after merging main and pushing. Retain XML, APK/source identities, raw traces and cleanup receipts in private persistent evidence.

## Open Questions

None. If native replay exposes a changed request absent from genuine assets, capture it before claiming acceptance.

## Revisions

2026-10-23: the store exposes no complete generation capture. Add `AuthoritativeThreadStore.generations`, an internal read-only snapshot used only by opening requests, so a delayed response cannot adopt a reset generation. This adds four lines in a second production file without a public API or consumer migration.

2026-10-23: `anotherSubscriptionCannotStealTheOpeningResponse` failed against a single latest-opening slot. Track each outstanding opening request until its response is consumed or the repository retires. Each conversation still has one pending recovery observation; a current-generation observation replaces stale-generation state without displacing a live summary awaiting its own response.

2026-10-23: the first expanded run executed 38 tests, passed 35 and failed three. The subscription failure was causal; two withdrawal failures used a live event that legitimately requests a summary and remain fixture-defective diagnostics. Corrected query-based withdrawal then executed 38, passed 37 and failed only `recertificationCannotReviveWithdrawnOpeningAuthority`. Invalidate answered observations and mark the conversation withdrawn on every outstanding opening request when previously published completion facts lose current authority, including a query that now certifies no completion. Later recertification cannot revive that request; a newly dispatched opening request remains eligible. Apply the same invalidation to live changes and recheck certification in the scheduled follow-up before sending.

The invalidation remembers only the generation in which this repository actually published certification, and clears that private map on retirement. Old-generation completion facts cannot poison an opening request dispatched after reset; live callbacks check their captured generation before invalidating observations.

2026-10-23: `newOpeningObservationAfterResetWaitsForFreshCertification` executed once and failed at the missing recovery follow-up. The response-side withdrawal guard also needs the generation-aware invalidation path: old completion facts must leave a fresh-generation observation waiting for its own certificate. Reuse that path, then verify the observation still owns its slot before attempting recovery.

2026-10-23: the first `intermediateQueryWithdrawalCannotReviveOpeningAuthority` attempt executed once and failed before certification because its batches changed captured version. Retain it as fixture-defective diagnostics. The corrected probe holds version 14 throughout while revising the supplied terminal item from 13 to 14.

The corrected constant-version probe executed once and failed at `nonqualifying observations must not refresh`. Detect withdrawal after each accepted authoritative query frame, before a later batch can restore the terminal and certify it. Consume the generation marker once when invalidating, so a newly dispatched observation is not repeatedly invalidated by the same withdrawn certificate.

## Documentation handoff

Pending documentation stage:

- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, repair/checkpoints: opening-response/certification correlation, retirement and consumed follow-up.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`, attention: unknown shown facts and waiting/running precedence.
- `docs/recorded-tests.md`, “Unavailable-summary recording integration (#2467)”, “Captured read snapshot prerequisites (#2467)”, and “Explicit graph-restart readiness (#2347)”: carry forward reviewed genuine captures, strict dispositions, causal acknowledgement ordering, peer notification guards, process-stop fence and counted evidence from retained source. Preserve historical failures, publication limitations and one IDENTICAL/four CHANGED result; identify fresh revision and macOS evidence separately.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design: only a decoded same-request row on the same repository and captured generation can join current completion certification. Foreign or unsolicited metadata cannot create authority.
- Tokens: no credential generation, storage or exposure changes; request identities are existing repository-local counters.
- Files/storage: no production persistence changes. Reuse reviewed genuine assets; raw traces remain private evidence, never fixtures without review.
- Android surface: no components, intents, providers, WebViews or UI text added.
- Cryptography: existing Noise transport and key lifecycle unchanged.
- Network/I/O: MUST FIX addressed in design: consume each observation before sending and never register the follow-up; missing facts cannot create a polling loop. Existing frame limits and reconnect backoff remain.
- Errors/logs: retain content-free static refresh outcome logs; never log message bodies, payloads, tokens, keys or decrypted bytes.
- Concurrency: MUST FIX addressed in design: capture generation at request dispatch and invalidate on reset/retirement; recheck certification at consumption. Existing scope cancellation owns all work.
- Threat model: relay delay/reorder is handled by correlation and generation checks; malformed daemon rows cannot supply certification. Rooted-device theft and screenshot/keyboard leakage gain no new surfaces; existing Keystore and UI policies remain responsible.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-23

## Manual continuation — 2026-10-23

The builder timed out after pushing `f5a29a43d9ef3b75f2b443a030a9def052aa711c`.
No active worker/support task or remaining matching child process was present at
manual takeover. Exact-ticket hold accepted revision 23; original source, failures
and private evidence remain intact. The final activation XML passed 40/40. The
native run completed after timeout with 6 executed, 4 passed, 2 failed, zero
errors/skips: earlier-session Agent failed at an absent scroll node and model-change
at terminal close before test-end. These are failed results, not missing execution.

Current main `597122c667ebfb5d8fc42a18ac6a07e27009a91a` merged cleanly as
`5ec1012dfb1f76de6b6edcba818afd28ed6d62e7`, including the later #2491 terminal
lifecycle repair. The manual continuation introduces no additional implementation
or recording change. Fresh configured acceptance, all six named replays and
independent security/source review on this integration remain pending. Historical
focused passes keep their original identities; #2494 still owns paired final-read
native proof. Do not repeat the old diagnosis or a genuine capture merely to replace
a failed historical result.

Remove the obsolete written-line/reject-branch/public-migration forecast: it adds
no behavioral acceptance and no longer describes the retained extraction. Keep
correlation, certification/generation/retirement fences, unknown metadata, bounded
follow-up, strict replay and the original assertion/deadline requirements.

Documentation now describes the bounded opening join, withdrawal/reset handling,
existing attention consumers, genuine capture comparison dispositions, causal
receipt/restart fences and the actual retained counts in the two owning feature
topics and recorded-test runbook. Reconcile pending integrated acceptance there
when normal independent review finishes.

Retained evidence authentication: all 199 entries in recovered #2467 bundle
`5fffd2acc9468d76fbd5756a5d519ce065c6b42f1db4d9909275f2cd2bc0ac15`
match their manifest. Eight earlier focused reports total 141 passed, zero
failures/errors/skips. All five promoted assets match retained source bytes.
The post-timeout native directory currently has XML only; its effective APK
identities, phone/replay logs, command exit and cleanup receipts remain unproved.
Fresh dispatcher acceptance must supply those receipts before a pass claim.
Documentation guard, whitespace and normal pre-verification passed on the integrated
candidate. The initial documentation continuation repeated no native or JVM tests; the later scoped repair is recorded below.

## Independent review rework — 2026-10-23

The independent review of `52e60852785d8fdb3c7003843bb532a10fc2011f`
accepted the configured gates: 5439 JVM passes, all six required methods in the
original complete UI run, one unrelated UI failure passing its exact rerun, and
24 scripted passes. It returned FAIL for a concrete authority-lifetime gap:
consumption removed the observation before queuing its send, so intervening
withdrawal could not revoke it and later certification passed the send-time check.
These green results remain valid for that source and are not acceptance of this
subsequent repair.

Add `queuedOpeningRecoveryCannotSurviveWithdrawalAndRecertification` before
implementation. Buffer the unavailable opening response, withdrawal batch,
restored terminal and final certification in the real inbound collector before
running the queued refresh coroutine. The unfixed source executed one test and
failed at `nonqualifying observations must not refresh`, zero errors/skips. The
same probe requires a genuinely fresh opening observation to recover afterward.

Keep a consumed observation as `recoveryQueued` in the existing private request
map until dispatch. Repeated callbacks cannot queue it twice. The coroutine must
still own that exact record and current certification before removing it and
sending. Withdrawal removes the queued record; restoration/recertification cannot
recreate it. Cleanup removes only its own record, so it cannot erase a newer
observation. Opening and live-summary recovery share this helper. Existing
connection/generation, reset, retirement, strict read facts, bounded follow-up and
no-mark-read rules remain intact; no public API or protocol changes.

The five promoted index entries now use a revision-qualified GitHub report URL
at retained `8534e6ca2babec13ba8ac0981565590fb946dd19`. Its declared SHA-256
`6e5aba904081b6035dd074b567d7a80aa78f9352bdf4006860ed7dc0f9c9d343` matches
the retained report. Genuine capture files and one IDENTICAL/four reviewed CHANGED
comparison dispositions are unchanged. This resolves the review's missing-report
traceability finding without importing unrelated historical implementation.

Security concurrency self-review: retain revocable ownership through scheduling,
not only a current-certification check. Revocation is permanent for the old
request identity; a new qualifying request has a different identity. The
regression tests that stale authority is rejected and fresh authority remains
usable. Independent source/security re-review and complete configured acceptance
on the repaired candidate remain required; no author verification is a verifier PASS.

Focused macOS red/green: the exact buffered-order test executed once and failed
on unfixed source, then the affected activation class executed/passed 41/41,
zero failures/errors/skips, exit 0 after repair. The passing new case includes
fresh-observation recovery after the stale queued send is rejected. Spotless
application passed. On committed `51738b973b036c29ae9c9f302feb56f2c7286013`,
focused recorded model-change passed 1/1 and scripted ping passed 2/2, zero
failures/errors/skips and exit 0. The native and scripted gate phases finished;
complete private evidence is at agents `logs/evidence/2493/manual-rework-20261023/`.
Effective test BuildConfig identifies `51738b973`; built app/test APK SHA-256 values
are `6f0306dcbbf23cb7f79bf49f802d388b63358223ea1b5cfa08f482966b36fdd5` /
`ec7e21e988464bf68926d09864174bafc2baab5a8c5e4c6fc9a7cc6f1a716fed`.
Scripted harness inputs identify daemon/source
`fbd5f2c9cd99c2c5864f4772c4e85f74d7a8dbfe`, binary SHA-256
`4d62d5ad4c5189315c46dd9c11232b6f8133759d17b483185aa8e5cbe15bc534`.
Android lint passed, exit 0. Final pre-verification initially stopped because
origin/main advanced with #2390 shared store/query changes. That failure is
retained; main merged cleanly as `a456e5b8e36670a3b17e9e405f11b999f785d3ee`.
On this integration the affected activation class executed/passed 41/41, zero
failures/errors/skips, and lint passed, command exit 0. The repair and its
regression source bytes are unchanged. Earlier native/scripted receipts remain
bound to `51738b973`; they are not new device acceptance of this integration.
A second final pre-verification stopped on the later #2351 diagnostic-test main
advance. It merged cleanly as `404c51225970d5bd6242ebcce53b3526111a6913`;
the recovery implementation and activation regression bytes are unchanged.
Final `pre-verify.py --gradle` then passed, exit 0: current main merged, Spotless
clean, all Kotlin source sets compiled, and security/theme/live/operator-file
checks passed. Earlier scoped runtime checks keep the revisions stated above.
Normal independent source/security re-review and complete configured acceptance
remain dispatcher-owned; no author check is an independent PASS.

## Navigation acceptance rework — 2026-10-23

The independent review of integration `6c487ab17d4aa79b03c3b6b1ee35cf54998ea9d6`
accepted the previous authority and provenance repairs. It accepted the exact JVM
retirement-test and SavedThreadFirstDraw reruns under normal dispatcher policy,
retaining their original failures. All six required recording replays passed in
the original UI run. The remaining background-Agent navigation recording failed
twice; scripted-all was not reached after that UI failure. These results remain
bound to that integration, with the original evidence retained.

The finished-marker helper already resolves current rendered identity, but
`verifyAgentRunNavigation` retained its initial Agent/run/child family after
readiness. Before implementation, two controlled real-screen regressions replaced
that family after readiness and during marker geometry. Both executed and failed,
zero errors/skips: the first lost the exact owned expansion action; the second
reported `tap target left the projected list`. The test-only resolver seam was
deliberately unused by the baseline. This demonstrates both stale-identity
mechanisms; it does not reconstruct the historical phone interleaving. This plan
amendment records the repair after those probes and implementation were prepared;
the separate pre-implementation red patch is retained in private evidence.

Opt the affected native caller into resolving the complete current rendered family
from one admitted snapshot of the same raw Agent, settled reply and exact late tool
children. At navigation and run-reveal boundaries, re-resolve that family, then
validate the exact current key and dispatch its scroll together on the UI thread.
Use one monotonic budget for navigation and each existing open/close transition;
resampling cannot restart the budget. Fixed-key callers retain strict missing-key
failure. Closed/open membership, positive attributed prose visibility, chrome
geometry and one physical pointer tap per transition remain required.

The scope is the shared navigation proof, its current-family tap adapter, the
affected native caller and the two controlled screen regressions. No production
recovery code, genuine recording bytes, strict request matching, authority/read
invariants, assertions or original deadlines are relaxed. The requirements remain
relevant; the earlier numeric forecast removal remains in effect.

Verify the repaired pair first, then the affected screen class, instrumented
compilation and the exact previously failing native recording. Preserve all
attempts and actual XML/source/APK/cleanup limits. Reuse prior accepted evidence
only at its own source. Resolve the five prior scripted failures with matched
effective inputs and existing selectors; do not repeat unrelated green gates.
Final configured acceptance and source/security disposition belong to a new normal
independent review, followed by actual normal documentation pickup.

Security self-review: the resolver consumes the existing admitted, source-bound
thread evidence and correlates the same raw owner. It does not manufacture
authority or transfer it across sources. No credential, protocol, persistence,
production Android surface or read-state change is introduced. Evidence continues
to use hashed rendered identities and private raw receipts.

Focused repair attempts are retained separately. The first stopped at compilation
because the final closed-state confirmation lacked its shared deadline argument;
no test executed. The corrected pair executed twice and passed both original
stale-identity boundaries, then both cases failed the positive attributed-prose
visibility assertion. The compact fixture supplied no exact prose reveal key.
Supplying its existing `ownedChildKey` input as `prose-$agent`, including the
initial-family argument, retained the viewport, replacement stimuli, assertions,
timing and physical taps. The next fresh invocation executed/passed 2/2, zero
failures/errors/skips, exit 0. Its receipt is private
`2493-ui-rework-evidence/green-v3/jvm-fg0wtoh7/`; the pre-implementation red and
both failed repaired attempts remain retained. The v2 regression-byte-identity
receipt applies to v2; v3 includes the explicit fixture reveal-key correction.
These are author checks of the dirty repair over `6c487ab`, not independent
acceptance or proof of the historical phone interleaving.

After formatting, the affected `BackgroundAgentBlocksScreenTest` freshly
executed/passed 30/30, zero failures/errors/skips, exit 0, including both new
generation boundaries and existing missing-list, foreign-Agent, removed-marker,
reset and collapse-isolation controls. Instrumented Kotlin compilation passed,
exit 0. Private counted XML is `affected/jvm-lvhux16q/dispatcher.xml`, SHA-256
`7c2f7db9adb02d29a1651070189138fea257f9a620a56152dce1c87695e0a546`.
On committed `d18cfe8b3d2f094e07b1602a3430d5e5423bfc34`, the exact twice-failed
`interactiveTurn_backgroundAgent_followsBottomUntilFinished` recording freshly
executed/passed 1/1, zero failures/errors/skips, exit 0. Raw phone pointer proof,
XML, finished phases, built APK hashes and app/test BuildConfig are retained in
private `native/ui-otn5y02f/`. Built hashes are not installed-byte readback; this
recording does not identify a current daemon or reconstruct the old interleaving.
No separate independent per-process cleanup audit is claimed.

The normal documentation stage should fold the reviewed navigation-boundary
lesson and its actual evidence into
`docs/knowledge/features/thread-screen-agent-navigation-evidence.md`, preserving
the earlier finished-marker repair and its historical evidence.


## Scripted runtime disposition and final handoff — 2026-10-23

The five unresolved scripted methods ran on `d18cfe8b3`: five executed, four
passed, one failed, zero errors/skips, exit 1. Both background-Agent methods and
both ping methods passed. In the session-error retained arm, the reply was
already displayed; the subsequent original 20-second error-pill-clear assertion
failed at `SessionErrorRecoveryScenario.kt:224`. The broad `reply_render` phase
name must not be interpreted as failure to render the reply. Four retained
harness bundles verified all four declared manifest entries each.

A single exact session-error comparison on main `d87abdc13` passed 1/1 with the
same inputs. The scenario, diagnostics, selected method and harness sources were
byte-identical. That one pass establishes neither inheritance nor a cause. After
merging that main as `65b91873c2dd84669c095e63cbf39dc71dbd9abd`, the exact method
failed again at the same status-clear assertion, one executed/failed, zero
errors/skips. All three runs used the dedicated `e2e_realclaude` recovery daemon
built from stale Core `fbd5f2c9cd99c2c5864f4772c4e85f74d7a8dbfe`, SHA-256
`aa8594b761c2d2d11cf47a26ab4f50801c43e9dbcdbef5f468ad064a7a2441e3`.
This is a separate daemon from the harness's outer binary.

That Core source lacks the already merged Core #3711 publisher repair. The phone
received idle progress while the independent peer also recorded responding, the
same signature documented by finished Mobile #2344. Reuse that completed repair
for isolated test inputs; do not change Mobile's admitted-progress status rules,
reopen #2344, or infer the original verifier failures' cause. No assertion or
deadline changed. No deployed binary, consumer pin or service changed.

With clean guarded Core source `2c68b595440dd7e8e843af9cff0d9a708bcc81a7`, the
exact method freshly passed 1/1 on `65b91873`, zero failures/errors/skips, gate exit
0. Both retained and dropped arms completed their child-delivery, queue, reply,
status restoration and original identity assertions. The unchanged harness built
its tagged recovery daemon from that same isolated `PYRYCODE_SRC`. Its context
reports revision `unavailable` and binary SHA-256
`15a3488e326966beb8605ad5f862ee3a30fdb8dfc3d7c87479486954d31b1769`;
the guarded build-source record supplies provenance, not an embedded revision
claim. Its hash differs from the outer daemon. One harness manifest verified 4/4
entries; raw phone, both daemon/control/child traces and finished phases remain
private under `scripted-repaired-runtime/`. No installed-byte readback or extra
independent cleanup audit is claimed. These focused author checks do not replace
complete configured acceptance or the independent source/security verdict.

Conditional pre-verification then stopped before Gradle because main advanced
with #2508's saved-file fixture retention repair. That failure is retained; main
`4a5459864` merged cleanly as `fd10cb88a5ca32d96c8d5074021f44976e308064`.
The navigation repair and authority-regression bytes remain unchanged. Earlier
native and scripted checks keep their actual revisions and runtime identities;
they are not device acceptance of this later integration. Final `pre-verify.py --gradle` passed, exit 0: current main merged, Spotless clean,
and all Kotlin source sets compiled, with security/theme/live/operator-file
checks green. An initial sandbox attempt failed to open the protected admission
lock before any check ran; the authorized protected invocation completed. Normal
independent configured review and actual normal documentation pickup remain
required. Independent scripted acceptance must use
a test Core containing #3711 for both outer and separately built recovery daemons
and authenticate their actual inputs. No author check is an independent PASS.

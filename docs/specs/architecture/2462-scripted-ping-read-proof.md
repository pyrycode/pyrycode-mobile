# Stabilize scripted ping's final read proof

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` captures a repository before the final read wait; durable-gap restarts the shared daemon.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/ThreadEvidence.kt`: `ThreadEvidenceOwner.matches`, `harnessThreadEvidence` and `authoritativeThreadEvidence` retain exact ownership and authoritative facts.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt`: `acknowledgeAuthoritativeReadCheckpoint` keeps qualified sight on its producing connection.
- `app/src/main/java/de/pyryco/mobile/data/repository/ReadCheckpointRetries.kt`: retries never migrate sight to a replacement owner.
- `docs/knowledge/features/thread-screen-testing-foreground-read-tracking.md`: qualified sight is insufficient without certified coverage and daemon confirmation.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: the host-owned store survives connection churn, while support and confirmation remain connection-owned.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: managed capture retention and effective binary identities.

## Design source

N/A: test evidence only, with no visual change requested.

## Context

The retained failed #2344 paired capture shows transport failure during the final wait, then fresh qualified sight and a daemon-applied read mark. The test still polls the repository captured before that failure. Diagnose the individual predicate conjuncts before selecting the repair. No product defect is established by the earlier capture alone, and no decision record is needed.

## Design

First add bounded content-free diagnostics around the existing final predicate, preserving all assertions, deadlines (including 90000 ms), order and teardown. Report connection ownership, strict evidence-owner matching, certification, read support, shown position and confirmed position separately. Correlate owners with bounded hashes of host/conversation/epoch and process-local connection identity; never print daemon text or payloads. Retain the managed ping pair's failed captures and effective inputs privately.

If replacement is confirmed, repair only this Mobile test's observation seam: re-observe the exact selected reply on the current negotiated repository, require current authoritative certification under that owner, and consume daemon-confirmed marks from that same repository. Never transfer old sight, globally weaken `ThreadEvidenceOwner.matches`, infer acknowledgement from pending work, or accept an unrelated reply. Record the demonstrated cause and precise replacement contract under Revisions before implementing it. If evidence instead identifies an external cause, retain acceptance and route to its owning blocker.

One behaviour slice: final proof of the rendered scripted reply, with its regression. Forecast at most 600 written lines including plan, diagnostics, controlled tests and integration; zero exported production declarations, one existing consumer, three acceptance criteria, fewer than ten rejection categories. Overlaps #1766, #2091, #2104 and #2175 affect separate methods or shared rule setup; keep edits local to ping, with no dependency on those branches.

## State and concurrency model

No new production state, jobs or flows. Compose's existing polling wait continues to drive frames; each sample reads current support and rechecks connection ownership after evidence and marks are sampled. Test-only diagnostic output is capped and includes a terminal sample on failure. Repository retirement and host retry cancellation stay unchanged.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Stable connection, rendered reply, delayed certification/acknowledgement | `replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner`, `qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation` and paired ping |
| Connection replacement during final wait | `replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner`; controlled native reconnect |
| Epoch/generation replacement or reused item ID | `certificationMustBeCurrentAndCoverTheExactReplyRevision`, `reusedReplyIdsCannotCrossHostConversationEpochOrChangedFacts` |
| Foreign host/conversation, unsupported route, local pending write only | `reusedReplyIdsCannotCrossHostConversationEpochOrChangedFacts`, `qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation` |
| Replacement while sampling | `replacementWhileSamplingCannotMixOwnersOrUseRetiredFacts`; recheck coordinator and support after sample |

## Error handling

Unready samples remain false within the original deadline. The original assertion failure propagates; diagnostics add no retries or longer timeout. Missing acknowledgement remains distinct from retired ownership. No new production error path.

## Testing strategy

Write a controlled regression before the repair and observe its failure. Test strict attribution, authoritative certification and confirmed marks with deterministic supplied snapshots and repositories. Run affected JVM evidence classes, lint, assembly, instrumented Kotlin compilation and forced formatting. Run managed `scripted ping` on macOS: both named methods together, 2 executed/passed and zero failures/errors/skips required. Device execution is needed for the real transport, daemon acknowledgement and foreground sight path. Preserve raw XML, phone/fixture/control/daemon logs, source and effective binary hashes and cleanup receipts privately under the agents evidence directory. Complete configured acceptance is dispatcher-owned; Linux is outside this ticket's scope.

## Open Questions

- Resolved: `ownsConnection()` stays false after replacement; the controlled capture below distinguishes it from missing acknowledgement.

## Security review

**Verdict:** PASS

- Trust boundaries: `ThreadEvidenceOwner.matches` stays strict. Fresh certification and exact reply identity are required; unadmitted or foreign facts cannot establish a read.
- Tokens: no credential access or storage changes. Diagnostics exclude tokens, keys, pairing data, prompts, reply text and raw frames.
- Files/storage: only test code and this plan enter Git. Private captures use the managed owner-only persistent evidence store; publish checked content-free summaries only.
- Android surface: no manifest, component, intent, clipboard or UI changes.
- Cryptography: existing Noise and TLS paths are unchanged; standard SHA-256 is used only for bounded diagnostic correlation, never authentication.
- Network/I/O: retain existing deadlines, admission and teardown; no extra queries or product retry path.
- Logs: SHOULD FIX in implementation: cap samples, hash arbitrary routing strings, use fixed labels/numbers/booleans, and never interpolate a DTO or exception message.
- Concurrency: current-owner checks bracket sampling; a replacement cannot inherit old sight or confirmation. No new coroutine scope.
- Threat model: delayed/reordered relay traffic still needs authoritative certification; hostile daemon text never enters diagnostics. Rooted-device token theft and UI screenshot exposure are unchanged by this test-only repair.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-22

## Revisions

### 2026-10-22 — Controlled connection replacement

The first diagnostic managed pair passed 2/2 on `5ea831bdc4098fcdeb2e5836bcec3dc16734dc2a`: certification was already 12 while shown was 9 and confirmed was 0; after qualified sight, confirmed became 9 on the same connection. Raw evidence is retained at agents `logs/evidence/2462/diagnostic-stable-5ea831bdc/`. This stable run does not erase the retained #2344 failure, where the connection ended and a replacement applied read 9 while the wait remained bound to the retired repository.

`replacementRequiresFreshCertificationAndAcknowledgementWithoutReusingOldOwner` executed once and failed exactly at fresh replacement completion against the extracted original predicate. To make the managed diagnostic causal rather than dependent on intermittent timing, use the existing `severAndRestoreLink` after the original certification wait and before the original 90000 ms final wait. Retain this controlled transition as the native regression; both methods still run together and all original assertions and teardown remain. The repair itself is still pending the diagnostic result.

### 2026-10-22 — Demonstrated stale captured connection

The controlled pair on `27ff7e3cc4eab1b1d5c1a3b1e67a06e6b24c3d75` executed both methods: durable-gap passed and ping failed at the original 90000 ms final wait. Terminal diagnostics show `captured_owned=false`, `owner_match=true`, checkpoint 12, admitted authoritative facts, supported shown 9 and confirmed 9 on the current connection. Captured connection identity 153744155 differs from current 13692088, with equal hashed host/conversation/epoch and generation 0. The daemon also records the read advance to 9. Missing acknowledgement is therefore not the cause. Raw failed evidence and effective inputs are retained at agents `logs/evidence/2462/diagnostic-replacement-27ff7e3cc/`; the earlier intermittent failure remains retained under #2344.

Repair contract: each final-wait sample reads the coordinator's current repository and its authoritative evidence and confirmed read facts. Require the route still supports that exact repository after sampling. Rebind the selected target only within its original host, conversation, authoritative kind and epoch, to the sample's connection and certified generation. Strict `ThreadEvidenceOwner.matches` validates this fresh owner, and the exact original reply DTO must still be present with checkpoint at least its revision, admitted and without repair/reset flags. Missing or clamped acknowledgement remains false. Generation reuse cannot substitute another reply, and a changed epoch cannot prove the original target. No old sight or pending write is reused. Production matching, retries and transport remain unchanged.

Security re-review: PASS. This is a test-only re-observation, not an ownership relaxation or a new command. Keep bounded content-free diagnostics, now using full SHA-256 routing correlation; all raw DTO equality checks remain in memory. Final forecast remains below 600 written lines with no exported production declarations or migrations.

### 2026-10-22 — Read proof repaired; later unread-post acceptance blocked

The repaired controlled pair on `15be1fe05ebf87f63116d90e724164831ed15446` passed both methods with zero errors/skips. `PingReadProofTest` passed 5 tests and unchanged `ThreadEvidenceTest` passed 17. After a whitespace-only correction, the paired run on `df4cc71fd8a4b79665e95ac301ef6e1ca82c8f0c` again completed the repaired final proof: the current connection had the exact reply, certification 12, shown/confirmed 9/9, while the retired repository remained at confirmed 0. It then failed the unchanged later unread-row wait after the new durable post. Durable-gap passed; overall counts were 2 executed, 1 passed, 1 failed, zero errors/skips, exit 1.

This is a distinct observable behaviour from final reply read confirmation. Its cause and inherited/regression classification remain unproved; do not extend this repair into attention projection or daemon metadata. Follow-up #2467 is open in Backlog with priority low and is linked as the blocker for the required paired acceptance. Keep every original assertion, deadline and teardown active, including the failing unread-row assertion. No retry or skip supplies acceptance. Before verifier PASS, resolve #2467 and supply a fresh full paired pass on the resulting candidate.

Raw current failure and effective identities are retained at agents `logs/evidence/2462/repaired-followup-df4cc71fd/`; the prior repaired pass is at `repaired-15be1fe05/`. App, daemon and relay binaries are byte-identical across those two captures. Forced formatting, final debug assembly and `pre-verify.py --gradle` passed after the whitespace correction. The open PR preserves the coherent repair while this ticket waits on #2467.

### 2026-10-23 — Validate the independently reviewed integration

PR #2472 is merged into this branch at `8f9066f4d2db6b4ffdd17e5363c27aaf0012bd49`, including reviewed candidate `cfcb4083fb1425576ed18265309fd88f0fb7d2a1`. Both #2344 and #2467 are closed. Preserve both repairs; this continuation adds no implementation. The retained branch already merged current main at `4e503995d745ba6721c4bd94e12785978e117ed4`.

Integration inspection confirms that `pingReadMarkConfirmed` still requires the exact reply DTO, current strict owner, original host/conversation/epoch, admitted certification and daemon-confirmed marks. Coordinator and negotiated support are rechecked after sampling. `ThreadEvidenceOwner.matches` is unchanged. The original controlled reconnect, 90000 ms waits, unread/peer-read assertions and teardown remain active.

The separate metadata repair joins the original correlated summary response with current completion certification in either order. `refreshUnavailableAuthoritativeReadFacts` consumes one connection/generation-owned trigger only when shown metadata is unavailable; certification never supplies shown/read values. Available zero and missing facts do not trigger a refresh. Retirement clears the ledger. `SuiteReadReceipts` retains intermediate captured snapshots and their same-owner query prerequisites; `RecordedSuiteTape` still requires every query and rejects unmatched/duplicate requests. Waiting/running attention precedence is unchanged.

Validation: rerun `PingReadProofTest` (5), `ThreadEvidenceTest` (17), `NegotiatedThreadActivationTest` (23), `SuiteReadReceiptsTest` (17) and `RecordedSuiteTapeTest` (49), then the original managed `scripted ping` pair on macOS. Retain fresh counted XML, complete traces, effective input identities and cleanup receipts. Linux evidence from #2467 remains historical and does not supply this macOS acceptance. A residual failure would need separate diagnosis rather than weakening either repair.

Before launching validation, the Mac manager reported only this admitted builder for #2462 and no retained pre-verification run; workflow revision 47 has no `approvalState` hold. Mobile admission is taking work, with no manager attention item. No foreign workflow operation is part of this continuation.

Security re-review: PASS. The integrated summary query uses the existing encrypted transport and owned scope, adds no credential or storage surface and is bounded to one consumed follow-up. Strict connection/generation fencing, independent daemon read authority and content-free capped diagnostics remain intact. No new security-sensitive implementation is needed. The combined retained diff is 1420 written lines before this revision; forecast remains below 1600, with no further types, call-site migrations or acceptance criteria.

### 2026-10-23 — Integrated macOS proof

On pushed `6bbc74832003cb582a5529baf7dd07c0d987fe77`, `python3 scripts/android-test-gate.py scripted ping` passed both original methods together: 2 executed/passed, zero failures/errors/skips, exit 0. The terminal reply sample has current ownership, exact reply, checkpoint 12 and shown/confirmed 9/9 while the retired repository remains confirmed 0. The separate list sample has one current-owned row, shown/read 13/9, projected/rendered Unread, and no competing waiting/running state. The original peer-read-to-idle assertion and teardown passed. No native retry was needed.

All five planned focused JVM classes passed in separate single-class invocations: 111 executed/passed, zero failures/errors/skips. The combined selection was refused before admission with zero tests, then corrected to the launcher's supported scope. Complete raw native/JVM XML, phone/control/daemon/relay traces, effective APK/binary and source identities, admission/cleanup receipts and verified manifests are retained privately at agents `logs/evidence/2462/integrated-macos-6bbc74832/`. Native/fixture copies match all 20 original files and the five JVM XML copies match their sources. Historical failures and remote publication limitations remain unchanged; no retrospective cause is claimed for unlogged historical post-summary values. Complete independent configured acceptance remains dispatcher-owned.

### 2026-10-23 — Verifier replay rework

Finding 1 exposes a source-ordering defect in `RecordedSuiteTape.drain`: a newly ready modeled acknowledgement follows ordinary deliveries, including its owner's `inbound_end`. Release only prerequisite-ready acknowledgements before ordinary deliveries, preserving same-owner query matching and every mandatory request. Extend the authoritative fixture in `SuiteReadReceiptsTest` with termination, covering delayed prerequisites and immediately ready reads before implementing this change. No new state, production API, deadline or retry is needed.

Findings 2 and 3 are branch-confirmed native regressions, not established consequences of finding 1. Inspect retained request progress and reproduce each original method; add a controlled regression for any demonstrated shared cause before repairing it. Preserve restart/placement, isolation, strict replay, deadlines and cleanup. Run focused JVM classes, both named native methods and managed ping; complete acceptance remains dispatcher-owned. Reassess scope if diagnosis requires an independently useful behaviour. Current retained work is 1444 inserted/deleted lines; allow at most 150 further lines including this revision. Overlaps #2325 (query catalog) and #2360 (terminal cleanup helpers) are separate from the pending-acknowledgement block; keep edits local.

Security re-review: PASS. Prerequisite checks remain same-owner exact requests; acknowledgements retain captured payloads and correlation, and cannot gain authority from a connection's termination. No transport, credential, storage or Android surface changes; diagnostics remain content-free and private captures retain owner-only permissions. Test-only ordering remains synchronized, without a new coroutine or cancellation path. New transition coverage: prerequisite matching then inbound termination, and immediately qualified read then termination, in `SuiteReadReceiptsTest`.

## Documentation handoff

Pending documentation stage: update the #2462/#2467 evidence discussion in `docs/e2e-interactive-stream.md` and `docs/recorded-tests.md` with the integrated macOS outcome and durable evidence path. Preserve historical failed outcomes and their previously reviewed publication limitations; complete configured acceptance remains dispatcher-owned.

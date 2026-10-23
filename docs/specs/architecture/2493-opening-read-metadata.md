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

This is one behaviour: bounded recovery of unavailable daemon facts, with its required strict replay coverage. Forecast: at most 1550 written lines including retained tests/assets and this plan, zero exported declarations or public caller migrations, four acceptance criteria and fewer than ten reject branches. Recheck the final patch before handoff.

Overlap scan found retained #2462 and older feature branches sharing these files. Current #2389 adds child demand, #2491 adds checkpoint placement inspection, #2476 changes rotation certification, and #2338 adds unrelated logging. Their blocks do not supply prerequisites to this repair; preserve local additive edits. The original #2462 branch/worktree and evidence remain intact.

## Design

Reuse the reviewed live-summary recovery and genuine #2467 assets, plus causal receipt/restart corrections through the retained source. Do not copy host-attention production changes already superseded on main or any ping-proof changes.

Opening list requests register request identity and the store generations observed at dispatch. Their correlated, decoded conversation rows create one recovery observation per row only when its generation has not changed. The summary supplies availability, never certification or epoch. The shared summary recovery checks current completion ownership, epoch, generation, repair/reset state and checkpoint before atomically consuming the observation and sending one follow-up. The follow-up is not itself registered for recovery. Available metadata, including zero, consumes authority without a request. Live completions retain the same bounded join.

Use private repository-owned records only. Keep `ThreadEvidenceOwner.matches` unchanged. Existing projection/ledger consumers apply actual daemon shown/read facts and retain waiting/running precedence. Recovery never marks read or transfers sight.

## State and concurrency model

The existing inbound collector serializes replies and certification. Scope-owned refresh coroutines recheck connection/generation before sending. Request observations are connection-local; collector completion clears them. Opening generations are captured before sending, not when a delayed response arrives. Reset invalidates old observations; withdrawn certification cannot trigger recovery. No timers, polling jobs or new flows are introduced.

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

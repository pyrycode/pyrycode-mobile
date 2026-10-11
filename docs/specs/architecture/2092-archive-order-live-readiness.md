# #2092 — Diagnose and repair Archive order live readiness

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_archiveTwoChats_listsSecondArchivedFirst`, `awaitConnected`, `hostConversationIds`, `createChatOn`, `archivedIds` and preceding `interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`; preserve the archive proof and fixture cleanup.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/LiveConnectionReads.kt`: `firstOnLive` follows replaced repositories and rejects stale-source results.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/CollisionRenameDiagnostic.kt`, `PeerWait.kt`, `RememberedEffortStage.kt`: existing failure-preservation and static-stage diagnostic patterns.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `HostConversationSnapshot.rowsLoaded` distinguishes a delivered list from a missing host or unloaded snapshot.
- `app/src/main/java/de/pyryco/mobile/data/model/ConnectionStatus.kt`, `RelayLinkStatus.kt`, `PyrycodeLinkStatus.kt`: distinguish socket readiness from authenticated daemon readiness without emitting dynamic error fields.
- `docs/knowledge/features/development-verification-test-scheduling.md`: teardown focus cannot diagnose failing-stage lifecycle; retain bounded, content-free evidence before cleanup.
- `docs/knowledge/features/relay-repository-coordinator.md`: repository ownership is connection-scoped and publication follows authenticated pump readiness.
- `docs/knowledge/features/archived-discussions-screen.md`, `archived-discussions-screen-how-it-works.md` and `docs/e2e-interactive-stream.md` (Archive order): preserve host-owned archive membership and newest-archived ordering.
- Retained #1977 and #2009 branch/retry/base gate stderr, counted outcomes and metadata; #2101 issue and builder verdict: no shared causal repair is established.

## Context

#1977 and #2009 branch failures stop at the initial `hostConversationIds`, before fixture A creation. #2009 base exposes only a 30-second coroutine timeout. The original phone/daemon artifact directories are unavailable; cleanup-time launcher records do not establish lifecycle state during the wait. The target method ran last, immediately after offline-retry, in the original full selections. Connection loss timing is not recoverable from retained summaries. The #2009 base offline-retry predecessor failed in its initial list read, before `DaemonFaultControl.stopUntilRetryWindow`; its intentional restart cannot explain that already-missing list. Branch/#1977 predecessor timeouts are unlocalized.

`awaitConnected` reads compatibility selection, whereas the list read owns the harness's specified host. This is an observation gap, not yet a demonstrated cause. Diagnose from fresh bounded target-host observations before choosing a localized repair. No archive-order algorithm or visual change is scoped; no decision record is needed.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088 and #2089 edit other scenarios in the same live class. Their diff hunks do not change this scenario or its list-read helpers; keep edits local. #2101 has no demonstrated dependency and is parked after a live-capture approval rejection.

## Design

Add test-only static stage evidence around this scenario if baseline evidence still cannot establish the cause. Name list arrival, compatibility readiness, target-host list delivery, A/B creation and rename, archive B/A, membership delivery and row-order observation separately. Record monotonic elapsed time, target registration, fixed relay/daemon status codes, repository presence/replacement, and loaded-list booleans/counts. Never log a name, conversation payload, pairing material, raw error, key or token. Bound retained observations; preserve the original exception and attach safe diagnostic evidence before fixture cleanup. Observe state only: diagnostics must not reconnect, send extra requests, retry the scenario or extend deadlines.

Use a shared test-only helper so deterministic JVM regressions can exercise stage attribution, observation bounds and original-error preservation. Keep the live method enabled and its create A then B, unique naming, archive B then A, both membership waits, A-before-B and first-row assertions unchanged. Cleanup still attempts both partially created fixtures. Use the fresh capture to select the smallest evidenced test-readiness or non-visual Mobile repair; record its precise contract and regression under Revisions before implementation. A daemon/relay-owned cause requires an owner issue and native blocker instead.

## State and concurrency model

No product lifecycle/state change is planned without causal evidence. Any diagnostic collector belongs to a scenario-owned coroutine scope and is cancelled before cleanup; state observations must not hold a stale repository as a source of list results. `firstOnLive` remains the authoritative list read. Test-controlled dispatchers drive any new collector regression. Failure snapshots do not suspend or initiate network I/O.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Selected host is connected but the specified host is absent/unready | `ArchiveOrderProbeTest.initialListFailureRetainsTargetHostStateBeforeCleanup`; live list delivery remains required. |
| Repository is removed or replaced during setup | `ArchiveOrderProbeTest.repositoryLossAndReplacementRemainSeparateFromHeldRows`; existing `LiveConnectionReadsTest` covers stale-source rejection. |
| A stage fails before cleanup | `ArchiveOrderProbeTest.initialListFailureRetainsTargetHostStateBeforeCleanup`. |
| Observation or logging itself fails | `ArchiveOrderProbeTest.diagnosticFailureCannotReplaceOrExposeOriginalFailure` and `cancellationAndUnavailableClockPreserveFailureIdentity`; static unavailable code only. |
| Repeated status events exhaust observation capacity | `ArchiveOrderProbeTest.observationsAreBoundedAndRetainTheFinalFailure`; no unbounded diagnostic retention. |

The scenario has no graph restart, process restoration, or reused fixture identifier: A and B are fresh, uniquely named fixtures on one host.

## Error handling

Keep existing bounded waits, exception identity/cause and meaningful assertions. Diagnostic failures must never replace the scenario failure or expose their dynamic messages. A connection/read failure is distinct from archive acknowledgement, membership or ordering. Do not claim a diagnosed cause from a passing retry or from coincident #2101 failures.

## Testing strategy

Run the unmodified selected live method to retain a fresh baseline, then a failing deterministic regression for the demonstrated condition, followed by the repair and focused existing affected tests. Instrumentation remains necessary because this existing scenario needs a real daemon/relay, Keystore identity and background connection scheduling. Compile Android tests; run lint, assemble, formatting and `scripts/pre-verify.py` after merging main. The fresh passing full live gate explicitly executing this method, with tested Mobile/daemon revisions, raw XML/log references and executed/failed/skipped counts, is dispatcher-owned and must precede documentation. A focused rerun is diagnostic evidence only.

## Open Questions

- What target-host connection/repository condition prevents initial list delivery? Resolve using fresh pre-cleanup observations and compare the preceding offline-retry scenario when needed.
- Is the repair owned by Mobile or by a sibling repository? Establish before implementing a production repair or linking a blocker.

## Security review

**Verdict:** PASS

- [Trust boundaries] Diagnostics accept static stages and typed status/boolean/count observations only; no daemon-authored text is rendered or logged. Existing repository decoding and authenticated routing remain authoritative.
- [Tokens and credentials] The approved Mobile live launcher reuses its builder-only login and strips account credentials from children. Tests do not fetch, store or print credentials, keys, pairing material or Noise transcripts.
- [Files and storage] New work is confined to test helpers/regressions and this plan. Retained evidence must be reviewed for content before copying; no secret storage, filename input or backup rule changes.
- [Android attack surface] No exported component, intent, provider, WebView or permission change.
- [Cryptography] Existing vendored Noise handshake and Keystore identity are unchanged; diagnostic identifiers must not include key material.
- [Network and I/O] Observation cannot send requests or alter deadlines, authentication, TLS, frame bounds, redial or backoff. Relay drop/delay remains a bounded failure rather than a retry-until-pass.
- [Errors/logs] SHOULD FIX: preserve original failures when observation/logging fails, with static unavailable evidence rather than raw diagnostic error text. Bound event history and numeric counters.
- [Concurrency] SHOULD FIX: every diagnostic collector must have scenario-owned cancellation before cleanup and thread-safe bounded retention; a reconnect must not leak a stale repository into result validation.
- [Threat model] A malicious relay can still drop/delay but cannot cause unbounded evidence growth. Token theft, hostile frame decoding and UI leakage protections are unchanged because the design adds no payload/credential/UI path.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-11

## Revisions

### 2026-10-11 — Retain evidence before selecting a repair

The first unmodified focused live attempt compiled both APKs but executed zero tests: exit 75, device held by the historical #2088 live run, and a 300-second admission wait expired. This is an environment wait, not a scenario result. Original #1977/#2009 phone/daemon directories are absent. Implement the planned diagnostic seam while waiting for fresh evidence; no behavioural cause or repair is claimed yet.

`ArchiveOrderProbe` retains at most 32 events and admits at most 32 background samples, with fixed stage/status codes, bounded monotonic time and repository appearance counts. A local scenario-owned collector samples only the specified host's status and repository; cancel it before fixture cleanup. Every stage samples again synchronously, so capped background evidence cannot suppress the final failing-stage snapshot. Observations are explicitly non-atomic: held rows are not proof of a live connection. JVM regressions cover stage attribution, bounds, dynamic-field exclusion, original-error/cancellation identity, unavailable diagnostics and repository loss/replacement. New internal types: three; consumer edits: one scenario; no production repair yet.

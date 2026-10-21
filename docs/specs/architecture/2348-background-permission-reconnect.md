# Background permission replay across reconnect

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the background permission scenario, `cycleHostLink`, and `setHostLink` retain the original notification assertions.
- `app/src/androidTest/java/de/pyryco/mobile/recording/RecordedSuiteNative.kt`: `beforeHostLinkClose` selects the current exact phone owner.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/SuiteConnections.kt`: `beforeClose`, `awaitIdle`, and `ReplayPump.inbound` currently distinguish scheduled delivery from neither buffering nor application consumption.
- `app/src/sharedTest/java/de/pyryco/mobile/recording/RecordedSuiteTape.kt`: full-payload matching, `closeReady`, and delivered reply accounting remain strict.
- `app/src/test/java/de/pyryco/mobile/recording/SuiteConnectionsTest.kt`: existing close and negative admission coverage.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: its single inbound collector synchronously invokes `onInbound`.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: `accept` certifies only a separately consumed terminal reply; `dispatch` reads the certified checkpoint.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: batches retain facts without advancing the resumable checkpoint.
- `docs/recorded-tests.md`: What replay proves, Capture and compatibility, Complete native suite; no changed production request contract or fixture is planned.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: certification must precede resume; never substitute received batches for completed queries.
- `docs/knowledge/features/push-messaging-service.md` and `lifecycle-connection-driver.md`: preserve payload-free wake, private preview/public redaction and alert deduplication.

## Context

The original macOS sweep at `77b5717d6d3a1dba9f1a92a251162e7f9b3770e2` failed after 29 matched requests. The first rejected request on `phone/host-0/3` has checkpoint zero; all three eligible catch-ups match owner, type, conversation and epoch but require checkpoints 12, 12 and 18. The first required catch-up is tape index 153. The prior connection's last catch-up at index 110 receives a batch and terminal at indices 111 and 112 before close at 113. Permission-answer and teardown failures follow the sticky first mismatch.

Fresh focused current-main execution at `3be8ab916` passed once (1 executed/passed, zero failed/skipped, exit 0). This does not establish suite independence. `beforeClose` waits for the delivery worker, but `ReplayPump.deliver` merely queues envelopes in an unlimited channel. The original test can cancel the repository after a batch applies and before the terminal certifies checkpoint 12. This is a replay consumption-boundary defect, to be demonstrated with a suspended real repository collector before implementation. It requires no product or recording replacement.

One deliverable: preserve the captured pre-close application prefix through the test-driven reconnect, with its regression. Forecast: about 450 written lines including plan, tests and evidence summary; no exported types or production changes, no consumer signatures changed, three acceptance criteria, no new product reject branches. The #2305 analogue required substantially more scaffolding and documentation. Branch #2347 adds restart orchestration beside `beforeClose` and tests; its additions are independent, and edits here stay local.

## Design

Keep all requests, lifecycle matching, causal fences, observation checkpoints and tape bytes unchanged. Track enqueued versus successfully emitted inbound frames per `ReplayPump`. A flow emits each real envelope to its existing downstream collector and records completion only after that collector returns. `beforeClose` first drains scheduled work, then waits for the exact selected owner's queued prefix to finish downstream, within its existing timeout. Do not wait on peers or a later replacement owner, and never mark cancelled or failed emission as consumed. The barrier supplies no reply, store mutation or certificate itself. No new application types, dependencies or UI changes.

Use a real `RemoteConversationRepository` and shared `AuthoritativeThreadStore` in a controlled regression. Hold the captured terminal between the replay pump and repository, after its batch applies. Exercise the actual close barrier and replacement catch-up: the old implementation permits cancellation and sends zero; the repaired implementation waits, then resumes at 12 with exact tape matching. Add failure/cancellation and owner-isolation checks; existing strict tape rejection checks remain required. Adapt the existing close test to consume its reply before expecting application readiness.

## State and concurrency model

All additions are test-source only. The hub's existing scope owns replay delivery; the repository's connection scope owns collection. Per-pump monotonic counters identify queued and successfully processed envelopes. Waiting never holds the hub monitor across suspension. Cancellation cannot acknowledge unfinished processing. Hub close cancels replay jobs as before; no detached jobs or extra subscriptions.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Close after batch but before terminal consumption | `SuiteCloseConsumptionTest.closeWaitsForTerminalConsumptionBeforeReplacementResumes`: held checkpoint remains zero and close remains pending; after release, replacement sends 12. |
| Cancellation while terminal is held | `SuiteCloseConsumptionTest.cancelledCollectorCannotAcknowledgeTheClosePrefix`: existing timeout fails instead of claiming completion. |
| Another owner has unconsumed frames | `SuiteCloseConsumptionTest.closeWaitsForTerminalConsumptionBeforeReplacementResumes`: peer buffering does not satisfy or block the selected phone prefix. |
| Changed checkpoint, epoch or owner | Existing `RecordedSuiteTapeTest` strict request and identity rejection tests, plus the replacement request goes through the real tape. |
| Repeated reconnect and prompt replay | Original focused native background permission method: one active private alert, public redaction and unchanged post time, original resolution/cleanup. |

## Error handling

Use the existing 30-second close timeout and sticky tape/delivery errors. A failed or cancelled collector cannot release the barrier. Do not extend deadlines, omit work or retry away failures. Diagnostics use counts, booleans, structural field names and revisions only; no payload text or credentials.

## Testing strategy

Write the held-terminal regression first and retain its failing executed result. Run `SuiteCloseConsumptionTest`, `SuiteConnectionsTest`, `RecordedSuiteTapeTest`, and `AuthoritativeThreadQueriesTest` with the focused JVM gate. Run the original fully qualified recorded background permission method through the focused UI gate on this Mac. Real Android notification/lifecycle behavior needs the existing device scenario; no new device-only test is added. Preserve raw XML, phone diagnostics, revisions and hashes privately outside the worktree. Run lint, APK assembly, instrumented Kotlin compilation, Spotless and final `pre-verify.py --gradle` after merging main. Full configured UI coverage remains dispatcher-owned and must include the named method before verifier PASS. Linux is outside scope. Replay supplies no new FCM, encryption or live transport certification.

## Open Questions

None. If the controlled regression contradicts the consumption-boundary diagnosis, investigate before changing admission and record the revised design here.

## Security review

**Verdict:** PASS

- Trust boundaries: successful downstream emission is only a close-readiness signal. `RecordedSuiteTape.send` still validates the complete request and exact connection, while the production decoder/store alone certify history. No synthetic success or relaxed admission is introduced.
- Tokens and credentials: no registration, permission answer, generated identity binding or secret storage changes. Diagnostics and public evidence contain no payload values, tokens or prompt text.
- Files and storage: recordings remain byte-identical. Raw XML/logs stay in owner-only private evidence storage; only checked content-free summaries are committed. No daemon-derived path is opened.
- Android surface: no production components, permissions, intents, push processing or notification rendering changes. The original scenario retains preview/redaction and unchanged post-time checks.
- Cryptography: no crypto, randomness, key or Noise changes; replay remains an offline test boundary.
- Network/I/O: no producer launch, new transport or timeout extension. Missing consumption fails under the existing bounded close deadline.
- Errors/logs: counters carry no content; sticky request mismatch and full tape consumption checks remain intact.
- Concurrency: acknowledge after successful emission only, scoped per exact pump; cancellation and foreign-owner regressions prevent false release. No suspension under the hub lock.
- Threat model: malicious relay, rooted-device token theft and hostile daemon frames remain governed by unchanged production validation/encryption/storage. Test-only scheduling cannot admit changed wire data. UI leakage is guarded by the original notification privacy assertions.

**Reviewer:** builder self-review per `builder/security-review.md`.
**Date:** 2026-10-21.

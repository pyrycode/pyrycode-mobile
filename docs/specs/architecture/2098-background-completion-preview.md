# Background completion preview evidence (#2098)

## Files read

- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `completionPreview` owns bounded enrichment; `readCompletionPreview` certifies attribution, completion, continuity and retained rows.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt`: `post` separates private preview from public fixed copy and identifies a completion by tag and checkpoint.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionPreview.kt`: `completionReply` selects the exact top-level completed turn; `notificationPreview` sanitizes and bounds text.
- `app/src/test/java/de/pyryco/mobile/di/AttentionPreviewSourceTest.kt`: the source harness supplies raw pages without merging them into local rows.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: the background completion method fetches independent peer history; `awaitAlert` currently checks channel, count and private text only.
- `docs/knowledge/features/push-messaging-service.md`: “Private reply and action previews (#1725)” requires encrypted raw history, safe fallback and no preview logging/persistence.
- `../pyrycode/docs/protocol-mobile.md`: “A history entry”, “Joining a page to the live stream” and “Security model” own the wire contract. Merged daemon PR #3231 adds validated remote prompt-answer receipts to that contract.
- Daemon PR #3231: `legacyRuntimeReceipt`, `validPromptAnswerFact`, `recordPromptAnswer` and `startRelayV2` preserve the original answer ID in page/live/replay receipts. Implementation `90535a755232ff31a9efdf5ee42dd312fe0bf7e6`, merge `e59635679fe668f18b199944410c9ca1544956b9`.

## Context

The retained incident used Mobile `b44067bbd991b7d68e0418f88cc2a9e721671c61`, daemon `8091a4730d42f43029eb0b955442dc300106030f`, Claude 2.1.280 and managed `pixel2Api33Atd` (Android ATD built for arm64). XML records 6 executed, 4 passed, 2 failed, 0 skipped. The named background completion method failed on private text; the other failure belongs to #2095.

Host `ce4aaae1-3b4c-4653-9f42-12c0b6df3578`, conversation `c63ba78c-fc18-4252-8e4e-c88e23e36de3`, turn `f521b6a0-d78d-483c-b0f8-1dae80aed1d3`: reply delta ID 19 and completion ID 20 follow prompt answer ID 15. The real incident-revision `newHistoryPager` projects 22 stored entries to 21, omitting only ID 15 and leaving IDs 16/14 adjacent. Attribution and exact completion pass; continuity fails. The earlier diagnostic executed/passed 1 test with 0 failed/skipped. The native external blocker, daemon #3222, is now merged via PR #3231; no daemon edits belong here.

Phone/host clocks differ: host push wake at 00:11:50.557 corresponds to phone wake at 00:11:48.784. The independent peer history request is host request 3 at 00:11:50.584. Phone requests 6/7 at host 00:11:51.955/00:11:51.970 each return 21 entries, at_start true. Phone enrichment/fallback/post occur at 00:11:50.015/.067/.072. This is a fast rejection, not evidence of timeout. `awaitAlert` reads the original private extras, not `publicVersion`; previews have no configuration switch separate from notification enablement. The original notification tag/checkpoint and exact rejected page were not retained, so historical direct notification-object attribution remains unverified. Fresh identity assertions address that evidence gap without claiming a historical live pass.

Private artifacts: `/tmp/builder-2098/evidence/diagnosis.md`, `fresh-lifecycle-dispatcher.xml`, `fresh-lifecycle-0-TEST-pixel2Api33Atd-_app-.xml`, `fresh-lifecycle-daemon.log`, `fresh-lifecycle-3-logcat-de.pyryco.mobile.e2e.InteractiveStreamE2ETest-interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread.txt`, `projected-history.json`, and the copied conversation history under `daemon-instance/`. Diagnostic source: `/tmp/builder-2098/daemon-diagnostic/cmd/pyry/preview_2098_diagnostic_test.go`. These are private diagnostic artifacts, not notification metadata.

One deliverable, three criteria; forecast under 350 written lines, zero exported types, two `awaitAlert` consumers, no new state-machine branches. Shared live-test-file overlaps: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1869, #1879, #1896, #2009, #2032, #2057, #2088, #2089, #2090, #2091, #2092, #2099, #2100, #2102, #2104, #2112, #2114, #2117, #2118 and #2119 edit other methods; keep changes local.

## Design

Keep all certification guards and publication behavior. `readCompletionPreview` reports static rejection codes for attribution, completion, continuity and retained-row failures. Existing unavailable and overall fallback events remain. No identifiers, checkpoint, payload, exception text or preview enters these logs.

Add source regressions with a tool/prompt-answer/reply/completion sequence: a missing answer receipt rejects with `code=continuity`; the daemon's five-field empty info banner at the same durable ID permits the reply without becoming display text. Foreign/malformed receipts, a mismatched completion checkpoint and an unrelated hole still fall back.

In the enabled live completion method, independently fetched history must contain the exact completion envelope ID and turn and must be contiguous. `awaitAlert` takes an optional identity assertion, invoked before text comparison. The completion caller checks the actual notification's length-prefixed host/conversation digest tag, id zero and decimal completion checkpoint. Preserve exactly-one-alert, real background completion/FCM wake, public redaction and actual owning-thread tap. Use content-free failure text for private preview equality; never replace observed state or retry a mismatch.

## State and concurrency model

No new state, jobs or persistence. Existing source-owned enrichment, three-second bound, repository retirement cancellation and notifier mutex remain. Assertions read the same single Android snapshot; they do not filter away wrong alerts or wait for replacement content.

## State transitions and identity reuse

None added: this change diagnoses existing certification and strengthens observations. Existing `repositoryReplacementAndHostRemovalCancelPendingLookups`, `localSequenceGapTriggersOneHistoryAskAndTimesOutAtThreeSeconds` and notifier ordering/deduplication tests remain focused coverage. The live method asserts the new conversation's exact host and completion identity, preventing a stale or different alert from satisfying equal preview text.

## Error handling

Missing/uncertifiable history retains fixed copy. A static rejection code identifies the first failed guard. The live gate fails immediately on wrong identity or private text and never prints the reply; full proof stays pending until dispatcher execution.

## Testing strategy

Write the continuity-rejection log regression first and execute it red before adding classified logging. Run the complete `AttentionPreviewSourceTest`, `AttentionPreviewTest`, `AttentionNotifierTest` and `AttentionNotifierSourceTest` classes. The daemon's own real-store red/green regressions and 294 focused race passes are external repair evidence, not a Mobile test claim.

Compile instrumentation changes. The device-only reason is real FCM, process backgrounding and Android posted notification/tap state. Acceptance explicitly assigns fresh full live proof to the dispatcher, so no separate builder live retry is run. The gate must name `InteractiveStreamE2ETest#interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`, record full-suite executed/passed/failed/skipped counts, Mobile/daemon/Claude/device revisions and XML/logcat locations. Deterministic green is not that proof.

## Open Questions

None. Historical notification identity cannot be reconstructed from absent metadata; record that limit and obtain direct attribution in the fresh dispatcher gate.

## Documentation handoff

Pending for the documentation stage:

- `docs/e2e-interactive-stream.md`, “Private push preview proof (#1725)” and “Verification status”: record established cause, daemon repair, historical attribution limit, and counted named-method/full-suite dispatcher evidence.
- `docs/knowledge/features/push-messaging-service.md`, “Private reply and action previews (#1725)”: fold in prompt-answer receipt accounting and static certification rejection codes. Safe fallback/privacy behavior remains unchanged.

## Security review

**Verdict:** PASS

- Trust boundaries: retain raw attribution, exact completion, continuity and retained-row certification in `readCompletionPreview`. An empty receipt accounts for an ID; it supplies no preview or permission authority.
- Tokens/secrets: no credential generation, access or storage changes. Test fixtures contain synthetic text only.
- Files/storage: no new production file or persisted metadata; private incident artifacts remain outside the worktree. The existing digest-only ledger remains unchanged.
- Android surface: no new exported component; existing immutable tap stays. Identity assertions inspect the actual private notification before content comparison and retain separate public-redaction checks.
- Cryptography: no changes to Noise or keys; previews still require encrypted repository evidence.
- Network/I/O: no new requests, retries or deadlines; the single newest-page lookup and three-second bound remain.
- Errors/logs: static rejection codes only. SHOULD FIX addressed by design: replace preview-printing JUnit equality failure with a boolean equality assertion and fixed failure text in `awaitAlert`.
- Concurrency: no new mutable state or scopes; existing cancellation and ordering tests remain required.
- Threat model: malicious relay delay/drop yields bounded fallback; hostile daemon pages retain all certification guards and text sanitization. Rooted-device token theft and UI screenshot/accessibility exposure are unchanged protocol residual threats outside this repair; no new mitigation is claimed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-12

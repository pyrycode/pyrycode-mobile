# #1989 — Verify repaired runtime receipts on the mobile read path

## Files read

- `ThreadReadEvidence.kt`: `checkpoint`, `received`, `understoodNonvisualEntry` keep exact-version claims and receipt-hole barriers.
- `HistoryPageReducer.kt`: `reduceOrderedHistoryPage` classifies decoded info banners as nonvisual without manufacturing content.
- `ThreadProjection.kt`: `recordReadEnvelope`, `mergeHistoryPage`, `observeSnapshot` join live and history identities.
- `HistoryPayloads.kt`: `HistoryPagePayloadDto.toHistoryPage` preserves unsigned IDs and timestamps.
- `ConversationReadMarksTest.kt`: correlated write, replay, host/conversation isolation and malformed-receipt fixtures.
- `ThreadReadClaimsTest.kt`: content-version, unknown/malformed, missing identity and stored/numeric-gap assertions.
- `docs/knowledge/features/remote-conversation-repository.md`: **Daemon conversation read marks** distinguishes receipt evidence from sight and confirmation.
- Daemon PR #3028: `legacyRuntimeReceipt`, `legacyHistoryReader`, runtime producers and serialized real-store examples establish the repaired contract. The authoritative wire document is the sibling `pyrycode/docs/protocol-mobile.md`.

## Context

The previous run established daemon ownership and linked daemon #3026. At daemon `b799ba5afb8d86b79f1d1eb20c737c15a632db5f`, runtime-enabled production history omitted durable ID 2 (`main_turn_opened`), serving 1,3,4,5,6. Mobile correctly blocked the numeric hole despite zero stored gaps. The disabled control served 1,2,3,4,5 and reached its watermark. Historical live logs lack IDs, so that exact historical sequence remains unproven.

#3026 is closed, merged by daemon `425d7f5e4ee02deb7bd3c7103c996c48754898e0`. Its real-store outputs certify known runtime facts using existing empty info-banner receipts with original IDs/timestamps. No mobile production workaround is warranted. This branch adds permanent mobile compatibility regression coverage; fresh unchanged mobile live proof remains dispatcher-owned. No decision record is needed.

## Design

Store the two serialized real-store/HandleFor examples from daemon PR #3028 under `app/src/test/resources/daemon-contract/`. Add `ThreadReadRuntimeReceiptsTest` using the real DTO decoder, ordered reducer, read evidence and thread projection. Add one integration test beside `ConversationReadMarksTest`'s correlated-write assertions. No new production types, signatures, dependencies, UI, wire shape or lifecycle changes.

The enabled fixture has receipt ID 2 and checkpoint 6, reaching legacy watermark 4; disabled has checkpoint 5 and watermark 3. Removing only the receipt reproduces the pre-repair null checkpoint, two content rows, zero malformed/unidentified/stored gaps. Neither history nor receipt-only delivery sends a read command. The completed reply's exact presented version permits a request, whose correlated daemon reply alone confirms the clamped watermark.

Sizing: one deliverable, three criteria, approximately 450 total written lines including fixtures, tests and plan; zero new exported production declarations, consumer migrations or error branches. All hard limits hold. No in-flight numeric feature branch touches the planned test files; production overlap with #1968 is avoided.

## State and concurrency model

Production ownership is unchanged: the connection-scoped repository collector and atomic thread/list projections own receipt facts; receipt evidence is not persisted as sight. Integration tests use `runTest` and `backgroundScope`, cancelled by the test scope. No new jobs or flows.

## State transitions and identity reuse

| Event | Test coverage |
| --- | --- |
| Completed history, runtime enabled/disabled; omitted receipt | `runtimeReceiptRepairsTheDiagnosedHoleWithoutChangingContent` |
| Unknown/malformed/unaccounted hole; receipt-only page | `invariantOnlyUnderstoodIdentifiedReceiptsAccountForTheHole` |
| Reordered receipt, replay, overlapping page at start/middle/end | `invariantReplayAndOverlappingHistoryPreserveExactClaims` |
| Reconnect, missing receipt identity, history identity resolution | `invariantMissingLiveIdentityNeedsHistoryAndFreshConnectionHasNoSight` |
| Changed/unseen content version | `invariantReplayAndOverlappingHistoryPreserveExactClaims`, existing `ThreadReadClaimsTest` |
| Read send and correlated clamped confirmation; other host/conversation | `runtimeReceiptHistoryRequiresCorrelatedDaemonReadConfirmation`, existing `ConversationReadMarksTest` and `ReadCheckpointRetriesTest` |

## Error handling

Unknown/malformed receipts and numeric/stored gaps retain existing conservative barriers. Missing live identity remains unresolved until matching ordinary history arrives. No new error result or UI surface; existing sanitized `Result<ULong>` confirmation failures remain covered.

## Testing strategy

First run the completed-reply assertion with the pre-repair enabled page and observe its expected failure, then replace only that fixture with the daemon's shipped bytes and rerun green. Retain the omitted-receipt case as a negative control. Run the new class plus existing read claims, marks, retry and subscription tests; then lint, debug assembly, Spotless and final `scripts/pre-verify.py --gradle` after merging main and pushing. No screen/device source changes.

Dispatcher owns the fresh full live gate including unchanged `de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_attentionDot_followsARealTurn`. Report mobile/daemon revisions, Claude version, full executed/failed/skipped counts and the named outcome. No separate focused authenticated live run is required. #1969 retains its subsequent full live proof.

## Open Questions

None: repository ownership was established by the preceding controlled reproduction and repaired in daemon #3026.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/remote-conversation-repository.md`, **Daemon conversation read marks** and **Testing** — compatible runtime receipts account for IDs without weakening sight or barriers; name permanent mobile regressions.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, **Verification status** — record the fresh dispatcher full live result with both revisions, Claude version, counts and unchanged named method outcome.

## Security review

**Verdict:** PASS

- [Trust boundaries] Fixtures enter `HistoryPagePayloadDto.toHistoryPage` and production reducers. Tests certify only strictly decoded existing info banners; raw runtime and unknown/malformed receipts remain barriers. No new trust or allowlist in mobile.
- [Tokens] Synthetic daemon examples contain no credentials; no token generation, access, storage or logging changes.
- [Files and storage] Classpath fixtures have fixed paths and synthetic content. Production persistence, path handling, backups and ephemeral sight remain unchanged.
- [Android attack surface] No component, permission, intent, WebView, UI or push change. Live permission assertions remain unchanged.
- [Cryptography] Noise authentication, Keystore keys, AEAD and nonce ownership are untouched.
- [Network and I/O] No new frame, timeout, retry, transport allowance or request side effect. Tests assert fetching/receiving alone sends no read request.
- [Errors and logs] No new production logging or error path. Existing content-free confirmation logs remain; no daemon text or decrypted payload is logged.
- [Concurrency] Real projection folds and `runTest` cover overlap/replay; scope cancellation and host/conversation isolation remain unchanged.
- [Threat model] Relay omission/reorder/replay cannot create sight; negative controls retain the hole and exact-version barriers. Hostile daemon unknown/malformed frames remain blocked. Disk token theft and screenshot/accessibility/keyboard leakage surfaces are unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-09

## Revisions

### 2026-10-09 — A second daemon compatibility defect blocks completion

The repaired enabled receipt example passes the four new evidence/projection tests, including the original omitted-ID negative control. The pre-repair completed-reply assertion executed once and failed with expected checkpoint 6, actual null; swapping in the shipped receipt bytes fixes that hole without production changes.

The repository integration then exposed a separate mismatch: `requestHistory` raises mobile latest to normal `turn_end` ID 5. Daemon #3026's real-store handoff reports watermark/clamp 4 because `historyEntryShown` now marks successful `turn_end` hidden. After actual request correlation with the documented clamp, the test fails with expected `ConversationReadMarks(readUpTo=4, latestEntryId=4)`, actual `ConversationReadMarks(readUpTo=4, latestEntryId=5)`. One test executed, one failed, zero skipped. Presentation reached checkpoint 6 and the correlated write succeeded; unread still cannot clear.

Ownership remains daemon compatibility: `legacyHistoryReader.LatestDisplayableEntryID` uses explicit stored `shown`, while `newHistoryPager` does not transmit it and preserves eligible payloads unchanged. Identical legacy payloads can represent absent/false/true stored visibility and different clamps. Inferring those facts from payloads on mobile would change the existing contract and cannot distinguish those cases. Preserve raw metadata and repair legacy projection/target agreement in [daemon #3029](https://github.com/pyrycode/pyrycode/issues/3029), filed in Backlog and linked as a native blocker of this issue. The failing JVM integration is retained with `@Ignore` naming that blocker per the out-of-scope bug handoff; no live source/assertion is changed or ignored.

The reordered probe moves only the nonvisual opening receipt around the content sequence: history content lifecycle order is daemon-authoritative, so arbitrary reversal of `turn_end` and deltas is not a valid equivalent page. Start/middle/end overlap, duplicate live receipt, missing identity/history resolution, fresh connection and exact-version probes all remain green.

Security review of this revision: PASS. No production classifier, state, payload allowance, barrier, logging or transport changes. Four permanent evidence probes remain active; the one deferred confirmation test is explicitly linked to its daemon owner. The issue stays `needs-real-claude`; unchanged full live proof and documentation remain pending after the blocker closes. Approximately 420 written lines remain within the sizing limits; no new production declarations or migrations.

This run ends waiting on #3029 with a committed/pushed recovery branch, rather than an open PR claiming a repair. Resume against refreshed daemon fixtures after that issue merges, re-enable the integration and finish the normal builder checks/PR and dispatcher live handoff.

Recovery validation: focused `ThreadReadRuntimeReceiptsTest`, `ThreadReadClaimsTest`, `ConversationReadMarksTest`, `ReadCheckpointRetriesTest` and `ThreadReadSubscriptionTest` ran 49 tests: 49 passed, zero failed, one skipped (the explicitly deferred daemon regression). `assembleDebug` and forced `spotlessCheck --rerun-tasks --console=plain` passed. This is not acceptance or a live pass; lint, final pre-verification and dispatcher full live execution remain for the resumed successful handoff.

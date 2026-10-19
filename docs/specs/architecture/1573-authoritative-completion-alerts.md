# Authoritative completion alerts (#1573)

## Files read
- `data/repository/RemoteConversationRepository.kt`: `onInbound`, `authoritativeLiveEnabled` and `currentReadMarks`; authoritative input bypasses the legacy event producer.
- `data/repository/AuthoritativeThreadRoute.kt`: `applyOutcome`; only fully assembled, accepted live mutations may publish completion facts.
- `data/repository/AuthoritativeThreadQueries.kt`: `accept`; batches upsert facts, while the terminal successful response certifies recovery.
- `data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `certifyQuery`, `current`; revision merging and generation fences remain the admission authority.
- `data/repository/RelayRepositoryCoordinator.kt`: `liveRepository`; connection retirement invalidates the capability supplier synchronously.
- `di/HostConversationSource.kt`: `launchAttention`, `completionPreview`, `alert`; the existing completion producer observes only legacy events.
- `notifications/AttentionNotifier.kt`: `handle`; persistent ledger, gates, enrichment, read cancellation and tap construction remain shared.
- `notifications/AttentionPreview.kt`: `notificationPreview`; bounded inert private text and public redaction remain unchanged.
- `notifications/AttentionNotifierSourceTest.kt`, `AttentionNotifierCoordinatorTest.kt`, `AttentionPreviewSourceTest.kt`: real repository/source/notifier harness patterns and ownership coverage.
- `docs/knowledge/features/push-messaging-service.md`, `dependency-injection-host-conversation-source.md`, `remote-conversation-repository.md`, `relay-repository-coordinator.md`: ledger-before-gates, confirmed reads, supplied-route negotiation and retained-owner lessons.
- Sibling `pyrycode/docs/protocol-mobile.md`: daemon thread updates, full thread item, thread query messages and Security model are the wire authority.
- `docs/recorded-tests.md`: genuine capture, fresh replay, strict comparison and failed-artifact preservation.

## Design source
Existing notification presentation and navigation are retained. The refiner explicitly records no visual design change and no Figma requirement for this repair.

## Context
The demonstrated API-33 Linux reproduction received FCM, reconnected, and recovered a completed authoritative item. Its first missing step is publication: `thread_items` returns after query admission without producing the legacy `TurnEnd` on which `launchAttention` depends. A running edge is neither necessary nor sufficient completion evidence. This does not diagnose the historical OnePlus incident. No decision record is needed.

One independently useful behaviour: an admitted authoritative completion reaches the existing notification publisher exactly once. Its regression, privacy checks and ownership probes belong with the repair. Forecast: about 1,150 written lines including this plan, production and tests; one new evidence type, no existing signature consumers requiring simultaneous migration, four acceptance criteria, fewer than ten reject classes. This remains below all ceilings.

Overlap: #1947, #2098/PR #2129, #2175 and #2245 touch these files. Their changes are local/additive; #2245's completion callback and query consumer additions need merging alongside this repair, not production activation here.

## Design
Add an internal redacted `AuthoritativeCompletion` evidence object and a connection-local held completion flow on the remote repository. A successful catch-up publishes from the newest merged store snapshot only after terminal certification; accepted live terminal changes request that recovery boundary. A batch, idle reading, raw legacy event, rejected mutation or stale owner cannot publish authoritative completion evidence. Retain the merged revision, epoch, generation, host and conversation ownership and recheck them before delivery/enrichment.

`HostConversationSource` switches completion collection with its exact remote repository and passes candidates directly to the existing notifier, independently of the legacy running/counting fold. Completion identity uses the authoritative epoch and permanent item ID, scoped by the existing host/conversation digest. The persistent ledger supplies reconnect/process-restart deduplication. Saved-host read summaries initiate named catch-up once per changed unread watermark, so recovery does not require an open screen. These requests neither advance reads nor activate production negotiation.

Private preview reads only the current authoritative store. Certification must cover the completed item and the selected reply range; the reply must be a settled shown main-thread assistant item with matching nonempty session/turn and placement preceding that completion. Unknown, partial, active, mismatched or uncertifiable evidence falls back to the existing fixed body. Never request legacy history on this route. Reuse `notificationPreview` and the notifier's separate fixed public version. Publication logs use static admission/source/outcome codes only.

Reject a retired alert before adding it to the ledger, as well as immediately before posting, so delayed work cannot spend a replacement connection's completion identity. All existing foreground, notification switch, mute, permission, confirmed-read and replacement rules remain.

## State and concurrency model
The repository's inbound collector owns admission and updates a held `StateFlow`; source collectors belong to `Held.job` and switch through `collectLatest` on repository replacement. No new global scope, dispatcher, background timer or persisted plaintext. The source monitor checks repository identity; evidence additionally checks negotiated live ownership and store epoch/generation/revision. Preview uses current certified facts within the existing bounded enrichment lifetime. No query callback acquires the source monitor synchronously, and callbacks do not take the live-route lock while holding a query lock in the opposite order. Socket close cancels the repository scope; foreground takeover and the existing wake window stay owned by the lifecycle driver.

## State transitions and identity reuse
| Event | Focused regression/probe |
| --- | --- |
| Completion recovered with no running edge or open screen | `AuthoritativeCompletionNotifierTest.recoveredCompletionPostsCertifiedPrivatePreviewAndExactTap` |
| Reconnect with a nonzero catch-up beyond the held paging window | `reconnectCatchUpCertifiesNewReplyBeyondTheHeldWindow` |
| Batch without terminal success, rejection or unknown request | `partialAndRejectedRecoveryCannotPost` |
| Query/live overlap, old/new revisions and duplicate completion | `overlapKeepsNewestAdmittedRevisionAndPostsOnce` |
| Partial catch-up followed by an older live state | `partialCatchUpCannotBecomeCompletionViaOlderLiveState` |
| Disconnect/reconnect and repeated wake | `reconnectAndRestartRetainLedgerIdentity` |
| Process/notifier restart over the same ledger | `reconnectAndRestartRetainLedgerIdentity` |
| Host or repository retired before delayed delivery | `retiredOwnerCannotSpendReplacementCompletion` |
| Confirmed read before completion / after post | `confirmedReadsSuppressAndCancelOnlyTheirCompletion` |
| Same item, turn and conversation on different hosts/conversations | `identitiesRemainIsolatedAcrossHostsAndConversations` |
| Missing, active, child or mismatched preview facts | `uncertifiedPreviewUsesSafeFallback` |
| Foreground takeover and closed notification gates | Existing `AttentionNotifierTest` gate/recheck coverage |
| Activity recreation | No activity-local state added; repository/notifier ownership and persistent ledger are unchanged. |

## Error handling
Existing decoder/store reject outcomes remain authoritative. Incomplete or failed query work emits no recovered completion. Missing preview proof returns null and uses fixed copy. Retirement cancels/drops work; it never posts fallback from a replacement. No daemon text or exception message enters diagnostics, and no preview enters ledger metadata.

## Testing strategy
Write the real repository-to-source-to-Android regression under `app/src/test` first and observe a missing-notification failure. Add invariant probes through actual query/live admission for overlap, retirement, read suppression, host/conversation isolation and persistent dedupe. Reuse existing affected notifier/source/coordinator/preview classes. Run focused JVM tests, lint, APK assembly, formatting and final `pre-verify.py --gradle` after merging main.

Linux native acceptance requires the two original `InteractiveStreamE2ETest` push methods, preserving all exactly-once, private-preview/public-redaction and owning-thread tap assertions. Compose with the preserved activation candidate for native proof and record both source identities without shipping activation here. Retain counted XML, logcat and correlated transport evidence privately. A changed recording requires genuine capture, fresh replay, strict comparison and explicit difference review; never promote the failed trace. Complete regression/recorded/scripted gates remain dispatcher-owned. Required native artifacts remain pending until counted results exist.

## Open Questions
None about the product contract. Native composition and capture execution must use the managed launcher and preserve the activation candidate's routing.

## Documentation handoff
Pending documentation stage: record demonstrated cause, repair and counted focused live/capture/replay evidence in `docs/knowledge/features/push-messaging-service.md`, “Attention alerts and the tap route (#685)”, and `docs/e2e-interactive-stream.md`, “Verification status”. Distinguish this authoritative-route reproduction from the unresolved historical OnePlus incident. Documentation does not produce live evidence.

## Security review
**Verdict:** PASS

- Trust boundaries: completion publication follows decoder/store acceptance, with terminal query certification for recovered facts; no push payload or legacy content supplies evidence.
- Tokens/secrets: no acquisition, credential storage or rotation changes; no credential or preview logging.
- Files/storage: reuse the existing atomic app-private no-backup digest ledger; new evidence stays in memory and redacts `toString`. No new path derived from daemon data.
- Android surface: no exported component change; existing immutable owning-thread pending intent and separately built public notification remain.
- Cryptography: existing authenticated Noise transport and SHA-256 ledger identity remain; no new cryptographic primitive.
- Network/I/O: existing bounded codecs, query timeout and reconnect limits remain. Only named saved-host conversations may be queried.
- Errors/logs: only static source/admission/outcome codes; no item, session, epoch, text, path, token or credential in diagnostics.
- Concurrency: MUST FIX addressed in design: check owner before ledger insertion and again at posting; only newest store revisions can authorize evidence. Repository/source cancellation owns all new work.
- Threat model: a malicious relay cannot supply authenticated completion plaintext; delayed/reordered input remains subject to codec/store fences. Hostile daemon text stays bounded inert private notification text. Rooted-device credential theft and third-party screenshots/accessibility remain existing platform threat-model limitations, unchanged by this repair.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-19

## Revisions
2026-10-19: The retained native trace uses a nonzero catch-up whose completed reply lies after the held paging window. Preview proof now carries the successful query's exact source interval and captured version, rather than inferring later ordered coverage from a batch maximum or extending paging coverage. Newly created tail items can be certified by that interval; older parent/active extras outside coverage cannot. Pending live previews wait for the matching successful certificate, including when store publication resumes a collector before the query callback. Evidence is branded with its exact repository owner. The integrated fixture performs main's opt-in reset exchange before asserting certified recovery; an initial fixture that omitted this exchange was rejected before reaching the intended failure.

2026-10-19: The existing `authoritativeCompletionUsesShownAvailabilityInsteadOfLegacyEntryCoverage` regression pins the supplied route's read semantics: confirmed reads cover available `lastShownVersion`, while unavailable shown metadata proves nothing. The new source probe preserves that rule, including retaining the alert while newer shown activity remains unread. Legacy known-entry suppression remains unchanged. No local read inference was added.

2026-10-19: `partialCatchUpCannotBecomeCompletionViaOlderLiveState` executed once and failed because a terminal row held by an unfinished query reached the ledger through a later older live state. Live updates now request certified catch-up without publishing a candidate themselves. Every `AuthoritativeCompletion` carries a non-null successful query certificate; no incomplete query can spend notification identity. The probe retains its original ledger assertion and verifies fresh recovery still posts once.

2026-10-19 (native continuation): Isolated composition `e95c4d6ba363d6dce1d3f55563186ada652db958` merges repaired branch `9bbae783b1ff769531f82cc1b415116156aeb426` with activation candidate `80b2efa999f2a3560048d8e13c9de9041b34c8f9`. It retains activation's consumer leasing and summary refresh alongside the repair's certified completion publication; activation remains outside this PR. The original native test bodies are unchanged. A verified Git bundle and exact patch preserve the composition privately. The actual repair commit is `10733c13be1fa445d8e2f18fc7caa7bbd0c828e8`; corrected earlier evidence metadata retains its original JSON and manifest.

## Native verification
Linux API-33 completion method `InteractiveStreamE2ETest#interactiveTurn_backgroundTurnEnd_pushPostsOneAlertThatOpensThread`: 1 executed, 1 passed, 0 failed/errors/skipped. The real daemon sent its wake at `11:33:43.598Z`; the phone received it, reconnected, admitted authoritative catch-up at `11:33:44.341`, and posted once at `11:33:44.377`. Four later turn decisions were duplicates. Original private-preview, public-redaction and owning-thread tap assertions passed. Raw XML, logcat, transport diagnostics and source composition are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/1573/builder-20261019-native-composed/`.

The adjacent prompt/reconnect native method executed 1, passed 0, failed 1, skipped 0 on daemon binary `daadf3c6798e88a418b68542e902daf47b934409`: push receipt and reconnect occurred, but no prompt alert reached publication. Failure XML/logcat are under `live-5b2ze976/` and transport diagnostics under `harness/fault-0a4362e45fad21c1d77a57ebc01832a3/`. Diagnostic composition `4a06c43f8c1987244c64f0fe5c2a36819515046e` adds only content-free session-admission metadata; its focused rerun with the same binary also executed 1, passed 0, failed 1, skipped 0. Its artifacts are under `live-m3w01dss/` and `harness/fault-f1559ff2c55c892a77faa8b254d24858/`.

The diagnostic identifies a separate producer defect: a supplied `modal_shown` clear carries provenance at `11:57:43.483`, but the fresh `modal_shown` omits it at `11:57:43.494`. `SessionLiveAdmission.admit` correctly rejects it as missing after the established binding. [Core #3636](https://github.com/pyrycode/pyrycode/issues/3636) owns that producer repair; no Mobile trust-boundary relaxation belongs here. Checked content-free evidence is committed at `app/src/test/resources/attention-1573/native-evidence.json`; its hash references the complete private summary and all raw artifacts. The 42-file private manifest was verified, and the original failed trace and these fresh failures remain intact. At that handoff, verifier PASS remained pending Core #3636 and a passing adjacent rerun.

2026-10-19 (prompt recovery): After Core #3636 merged as `5fd0d600b629c9cc3637fc42eb353c8e965f9fde`, the unchanged `InteractiveStreamE2ETest#interactiveTurn_backgroundPrompt_pushPostsExactlyOneAlertAcrossReconnect` executed 1, passed 1, failed/errors/skipped 0 on Linux API 33 with Claude 2.1.280. The preserved diagnostic composition `4a06c43f8c1987244c64f0fe5c2a36819515046e` differs from the original composition only by six content-free admission-log lines; assertions and production routing remain intact. Isolated daemon binary SHA-256: `8539e88af07aa24a37f1a138486ee94354f98cfe814bb2cb9eb5661c41ffffec`.

Phone logcat records push receipt at `13:36:59.867`, fresh provenance-bearing modal admission at `13:37:00.516`, one prompt post at `13:37:00.557`, then duplicate suppression across reconnect. The host records its `modal_shown` wake at `13:37:00.488Z`; phone and host clocks are retained separately. Raw XML, logcat, emulator log and transport diagnostics are retained under `/work/Projects/pyrycode-mobile-agents/logs/evidence/1573/builder-20261019-prompt-recovery/`, with 13 private manifest entries verified. The committed evidence summary retains both failed prompt attempts alongside this pass. Both required focused native methods now have passing evidence. Recordings remain unchanged; #2245's activation and capture/replay/comparison holds remain separate. Complete Linux gates and documentation remain with their owning stages.

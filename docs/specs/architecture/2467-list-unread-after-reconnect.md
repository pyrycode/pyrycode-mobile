# Diagnose and repair list unread after connection replacement

## Files read

- `docs/specs/architecture/2462-scripted-ping-read-proof.md`: retained final-proof design and failed later unread acceptance; preserve its exact-owner proof and reconnect.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_streamsScriptedPingReplyIntoThread` waits for unread after admitting a durable post, then peer-confirmed idle without reopening.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `updateAttention` and `currentReadMarks` join current repository facts with transient attention.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState.resolve` preserves waiting, running, unread precedence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `applySnapshot` and `observeHostReadMarks` publish read facts independently of row equality.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `refreshAuthoritativeReadFacts` requests daemon summaries after admitted live completion; query certification publishes completion facts separately.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadRoute.kt`: `applyOutcome` invokes effects only for applied live updates.
- `app/src/test/java/de/pyryco/mobile/data/repository/NegotiatedThreadActivationTest.kt`: `admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark` drives the real negotiated repository with controlled supplied frames.
- `app/src/test/java/de/pyryco/mobile/data/repository/ShownVersionReadMarksTest.kt`: unavailable shown metadata must never reuse a held watermark or legacy receipt.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md` and `channel-list-viewmodel-projection.md`: attention joins independently of list equality; connection generations cannot inherit authoritative confirmation.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`: certified content and connection-owned read facts have separate authority.

## Design source

N/A: nonvisual metadata/attention diagnosis and repair; preserve the unread dot and all existing row semantics. The refinement comment explicitly identifies this as nonvisual work.

## Context

One useful behaviour: a list-only post remains unread after the rendered reply was confirmed on a replacement connection, until peer confirmation covers the post. Diagnosis and regression ship with that repair. No decision record is needed.

Read both named #2462 captures from managed bundle `8edb493b472ac9e00e68cdfb6409a124d2fb261350303e5ab2d2d0e39477151a` (bundle digest `089310a0b5015277e07ff3811510c37b237ded093c4bddd6bb071b722dacedeb`). The passing capture executed/passed 2; the failed capture executed 2, passed 1, failed 1, with zero errors/skips in both. Both complete final proof with checkpoint 12, shown/read 9/9 on the current connection and retired confirmed 0. Both admit the post after thread release and request a summary refresh; only the passing phone trace records later changed read facts. This narrows investigation but does not select a cause. Retain original manifests: the downloaded publication contains tails in place of two full durable-gap logs and two protobuf hash differences; do not claim original byte completeness. Receipts preserve effective APK, daemon and relay identities.

Base refreshed to retained `origin/feature/2462` at `9ac27ec8a`; it is already an ancestor. Target the reviewed repair PR at `feature/2462`. Sharing paths with #1766, #1947, #2091, #2098, #2104, #2175, #2325, #2338, #2463 and #2464 creates no dependency: current edits stay local to ping and the demonstrated attention/read seam. #2462 supplies the intended base; never add a reciprocal blocker.

## Design

First instrument the unchanged unread wait with bounded content-free samples and a terminal sample. Separate expected source row and rendered row presence, current repository ownership, authoritative read/shown availability and values, waiting/running/busy precedence, attention projection and rendered unread/idle/running/waiting semantics. Correlate host/conversation with SHA-256 and connections with process-local identity. A synchronous internal source diagnostic reads existing state under its monitor, sends nothing and exposes no daemon text.

Select the repair only after these facts and controlled repository tests establish the failing boundary. Keep changes within Mobile's current host/conversation/connection read projection; do not infer read confirmation from local sight, content receipt, pending writes or cached facts, or shown availability from a first row emission. Record the demonstrated cause and repair contract under Revisions before implementing it. An external cause requires its owning native blocker and no sibling implementation.

Forecast 600 total written lines including plan, diagnostics and tests, at most two production files, zero new exported types or signature migrations, three criteria and fewer than ten rejection branches. Reassess against the actual repair before committing it.

## State and concurrency model

Keep existing host collectors and connection cancellation. Diagnostic sampling adds no job, network request or state to product behavior. Compose polling retains its original 90000 ms deadline; terminal sampling runs in `finally` without retrying. Any repaired asynchronous projection must remain owned by its repository scope and reject retired generations.

## State transitions and identity reuse

| Event | Required coverage |
| --- | --- |
| Confirmed rendered reply, controlled reconnect, thread exit, post admission | Existing paired native ping including final proof, unread and peer-idle assertions |
| Read metadata becomes unavailable before recovery completes | `unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement` |
| Duplicate/replayed facts and one-row summaries | `unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement`, `admittedTurnEndRefreshesDaemonReadFactsWithoutInventingAMark`; empty snapshots in `HostConversationSourceTest` |
| Host/conversation identifiers reused on another connection | `unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement`, `collidingHostsKeepSeparateStoresAndVerdicts`, `retiredConnectionCannotRestoreItsVerdictOrSendQueries` |
| Unavailable shown metadata, pending/local read only | `unavailableMetadataNeverReusesAHeldShownWatermark`, `productionSummaryReceiptCannotSendReadAndConfirmedReplyDoesNotReplaceShown`, `qualifiedSightAndPendingWritesCannotSubstituteForDaemonConfirmation` |
| Competing prompt/running activity, then peer read | `daemonMarksKeepPromptRunningUnreadPrecedenceAndSurviveDisconnect`, `unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement`, paired native ping |
| Original summary answers before or after certification | `certifiedRecoveryWaitsForItsPendingAvailableSummary`, `unavailableSummaryAfterCertificationRefreshesOnceOnItsOwner`, both earlier summary variants |
| Another certification while the follow-up remains unanswered | `repeatedCertificationCannotRefreshTheSameLiveSummaryAgain` |

## Error handling

Unready facts keep the original predicate false. Diagnostics use fixed labels, booleans, numeric marks and hashed attribution only. Propagate the original timeout/failure unchanged. No new retry, timeout extension, skip, teardown change or wire contract.

## Testing strategy

Write and execute a controlled failing regression before the repair, then pass it against production logic. Run affected repository/attention tests, lint, assembly, instrumented compilation, Spotless and final `pre-verify.py --gradle` after merging main. The existing native scenario requires real Noise/relay transport, daemon state and foreground read qualification, so remains device-only. Run Linux managed `scripted ping` with both original methods together: 2 executed/passed, zero errors/failures/skips. Preserve raw XML, phone, fixture/control/daemon/relay traces, exit/cleanup receipts, exact revisions and effective input hashes privately in agents `logs/evidence/2467/`. Historical macOS failure stays failed; macOS execution is outside scope. Complete configured acceptance belongs to the dispatcher.

## Open Questions

- Resolved for the controlled reproduction: current authoritative shown metadata remains unavailable after successful post recovery; attention correctly stays idle. Historical logs lack numeric post-summary values, so their exact metadata contents remain unobserved, not retroactively certified by the new run.

## Security review

**Verdict:** PASS

- Trust boundary: only current daemon-supplied read and available shown facts may classify attention. Strict final-proof ownership and negotiated capability checks stay unchanged.
- Tokens/credentials: no credential access or storage changes. Diagnostics must exclude tokens, keys, pairing payloads, prompts, message bodies and DTO rendering.
- Files/storage: private evidence stays outside Git in the persistent owner-only evidence directory; retain original manifests and publication discrepancies. No product storage changes.
- Android surface: no component, permission, intent, clipboard, accessibility or visual changes.
- Cryptography: SHA-256 is diagnostic correlation only; existing Noise and TLS remain unchanged.
- Network/I/O: diagnostics issue no requests; preserve deadlines and managed cleanup. A repair may refresh authoritative metadata only through the existing connection-owned request path.
- Logs: SHOULD FIX in implementation: cap samples, always retain a terminal sample, use fixed labels and hashes, never exception text or raw routing strings.
- Concurrency: source diagnostics hold the existing monitor; ownership must bracket sampled repository facts. No new scope; a retired repository cannot establish current confirmation.
- Threat model: dropped, reordered or duplicate relay traffic cannot substitute receipt or missing metadata for read authority. Hostile daemon text is never interpolated. Rooted-device theft and screenshot exposure remain unchanged by this repair.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-22

## Revisions

### 2026-10-22 — Recover summary availability after certification

The diagnostic Linux pair passed 2/2 with unchanged assertions. Its terminal unread sample has one expected rendered row, current host/repository ownership, supported shown/read 13/9, source projection Unread, no running/busy/prompt/batch and exactly one unread semantic row. Raw evidence is in agents `logs/evidence/2467/diagnostic-linux/`. This pass does not reclassify the historical failure or prove its unlogged metadata values.

The controlled `unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement` reproduces the metadata boundary with real production negotiation, connection replacement, thread release, live post and completion, then an authoritative summary with unavailable shown metadata before successful recovery. It fails before the repair: recovery makes no subsequent summary request (expected 2, actual 1). The current repository retains read 9 and unavailable shown metadata; completion certification itself must not invent shown/read facts. This is a Mobile-owned liveness gap: its one live-triggered request may observe the protocol's valid temporary unavailability, and its successful recovery has no metadata refresh edge. The failed historical phone trace likewise has no later changed read facts after recovery, but no retained raw post-summary values establish whether its first reading was absent or stale.

Repair contract: after successfully publishing certified authoritative completions, request a fresh daemon summary through `refreshAuthoritativeReadFacts` on the same connection and generation. Keep the earlier live-completion refresh. Neither certification nor request submission changes read/shown values; only a supplied daemon response may restore availability. A later mark covering the post clears attention, without reopening or writing a phone read. Duplicate live facts still cause no new refresh; retirement/generation fencing remains in the existing helper and query owner. No polling, extra retry or new timeout. Protocol authority remains sibling `pyrycode/docs/protocol-mobile.md`, `conversations` and `conversation_updated`.

Security re-review: PASS. The additional request uses the existing empty-payload list request and exact connection scope; it grants no metadata or read authority. Forecast remains below 600 lines, two production files, zero signature migrations. Preserve the red XML at agents `logs/evidence/2467/unit-red/`.

### 2026-10-22 — Correct regression admission and strengthen source coverage

The first supplied terminal inherited `status=running` from the generic item helper, so it was not an admitted completion. Preserve that failure as fixture-defective diagnostic evidence rather than causal proof. The corrected terminal explicitly has done status and inactive state, and the test asserts `isCompletion()` before delivery. Rerun it against the original production callback for the causal red. Drive `HostConversationSource` itself: the current row stays present with unavailable metadata and idle attention until a response to the post-certification request supplies shown 13/read 9; duplicated/older summaries preserve unread, and peer read 13 yields idle without a phone read command. The existing generic turn-end test still expects only its original refresh because its terminal intentionally remains non-complete.

The corrected pre-fix run executed one test and failed exactly at the missing post-certification summary request (expected 3 including the host list subscription, actual 2). The row-presence and unavailable-metadata/idle assertions passed first. Preserve this causal red separately at agents `logs/evidence/2467/unit-red-corrected/`; the earlier fixture-defective result remains retained.

The repaired `NegotiatedThreadActivationTest` class executed/passed all 19 tests on Linux. The regression verifies the response boundary through the real host source, replays post/completion facts in reverse order, and supplies late retired-connection metadata; none can manufacture a read or restart the completed refresh. The final implementation remains one behaviour slice: approximately 320 written lines including plan, diagnostics and regression, two production files, no new exported types, no consumer migrations, three criteria and no new rejection branches. Existing checks for waiting/running precedence and missing shown metadata remain active.

### 2026-10-22 — Restrict certification refresh to unavailable metadata

Verifier finding 1 demonstrated that unconditional certification refresh changes every recorded completed-query request sequence, including initial history reads. The controlled cause requires only recovery of explicitly unavailable shown metadata. Narrow the additional refresh to a current connection's existing, thread-supported read facts whose shown watermark is unavailable. Publishing completion facts remains unconditional on metadata; the original live-completion refresh remains unchanged. An absent summary is not evidence of temporary unavailability and does not trigger this additional request. Available metadata, including explicit zero, keeps the original request contract. No replay matcher or captured response changes are planned.

Extend the existing real-host regression with `unreadPostWithAvailableSummaryNeedsNoCertificationRefresh`: after post admission the daemon already supplies shown 13/read 9, so certification must preserve unread without an extra request. Keep the unavailable case and its correlated response, peer-read idle, duplicate and retired-owner probes. Run the new case red before narrowing production. Run focused strict native replay, genuine managed recording compatibility for the affected permission exchange, and the Linux scripted ping pair. Inspect the retained timeout failures and use the managed investigation route for any wider sequence evidence that focused checks cannot establish. Complete UI and scripted-all gates remain dispatcher-owned. Forecast remains below 600 written lines and two production files.

Security re-review: PASS. Only the existing current repository facts select the extra request; no inferred authority, synthetic reply, extra scope, polling, timeout or recording exception is introduced. Full-payload and connection ownership checks stay unchanged.

The new available-metadata case executed once before the guard and failed on the extra summary request (expected 2, actual 3); retained XML is agents `logs/evidence/2467/rework-red/`. After the guard, `NegotiatedThreadActivationTest` executed/passed all 20 tests, with zero failures/errors/skips. Both availability branches use the same real host source and preserve the original ownership, unread and peer-idle assertions.

### 2026-10-22 — Let the outstanding live summary answer before refreshing

The genuine permission capture and fresh replay each passed once, but strict old replay failed at step 12 and comparison remained CHANGED: a second summary request was sent before the original live-completion summary answered. Both genuine replies supplied shown/read 18/0. The available-metadata guard alone therefore does not solve the request race. Preserve this failed compatibility attempt at agents `logs/evidence/2467/permission-first-compat-a59096aa4/`. Focused replay also still failed ping-footer's request contract, while the two selected historical timeout methods passed.

Track only live-completion summary request identities with their conversation and store generation in this repository. Register before scheduling the existing send, remove on refusal, correlated response or connection retirement. Certification waits while such a request is pending. After applying its actual correlated response, request the single post-certification refresh only if current shown metadata remains unavailable and a current completion certificate covers all applied progress. If the response precedes certification, certification supplies that edge; if it follows certification, the response supplies it. The follow-up is not tracked as another live trigger, so temporary unavailability cannot create a polling loop. Existing full-payload replay matching and response correlation remain untouched.

Add `certifiedRecoveryWaitsForItsPendingAvailableSummary` and `unavailableSummaryAfterCertificationRefreshesOnceOnItsOwner` to the shared real-host regression, covering both response orders. Preserve the red results before implementation. Connection/generation retirement and duplicate live facts retain their existing probes. This remains one availability repair, below 750 written lines, two production files, no exported declarations or consumer migrations. Security re-review: PASS; the request ledger is connection-local, cleared at retirement and never grants read authority. Genuine compatibility and focused native replay must be rerun on the new committed candidate; no failed capture is promoted.

Both response-order probes failed before coordination at the premature summary (expected 2, actual 3), retained in `pending-summary-red/`. The repeated-certification probe then demonstrated an additional follow-up (expected 1, actual 2), retained in `repeated-certification-red/`. Keep the ledger entry's answered state until availability or certification resolves it, then atomically consume it before requesting the follow-up. `repeatedCertificationCannotRefreshTheSameLiveSummaryAgain` certifies another genuine query while the follow-up remains unanswered. A duplicate response or further certification cannot reuse the consumed trigger. Entries are bounded to the latest live request per conversation, and retirement clears them; no new scope or deadline is introduced.

The final coordinated implementation passed all 23 `NegotiatedThreadActivationTest` methods on Linux, with zero failures/errors/skips. It uses one private immutable ledger-entry type, no exported declarations or consumer migrations, and remains within the 750-line forecast and the state-machine branch ceiling. No recording, replay matcher, timeout, native assertion or sibling implementation was changed.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`, repair/checkpoint discussion: explain the connection-owned live-summary response and certification edges, one consumed follow-up, and independent metadata authority.
- Pending documentation stage: `docs/knowledge/features/dependency-injection-host-conversation-source.md`, attention discussion: preserve unavailable shown metadata as unknown and waiting/running precedence; carry forward #2462's separate final-read proof.
- Pending documentation stage: `docs/recorded-tests.md`, Capture and compatibility: retain failed compatibility evidence and record the final genuine capture, old/fresh strict replay counts and comparison disposition. Do not reclassify historical failures or claim dispatcher acceptance.

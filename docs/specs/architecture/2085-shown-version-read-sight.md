# Shown-version read sight (#2085)

## Files read

- `data/network/ConversationsPayload.kt`, `ConversationResponseDto.kt`, `ReadMarkIdSerializer.kt`, `ThreadUpdatePayloads.kt`: summary facts and strict safe-integer parsing.
- `data/repository/ConversationListProjection.kt`, `ConversationReadMarks.kt`: atomic fact ledger; legacy facts are monotonic but current shown availability must replace.
- `data/repository/RemoteConversationRepository.kt`: connection-scoped negotiated capabilities and summary routing.
- `data/repository/AuthoritativeThreadStore.kt`: `checkpoint` alone certifies complete state; `completedVersion` does not.
- `data/repository/StableConversationRepository.kt`, `ReadCheckpointRetries.kt`, `ConversationCommands.kt`: host-owned qualified retries and correlated confirmation, retained unchanged.
- `di/ConversationAttention.kt`, `HostConversationSource.kt`, `notifications/AttentionNotifier.kt`: shared attention and notification proof consumers.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`, `ThreadScreen.kt`, `ThreadReadViewport.kt`, `ThreadUiState.kt`, `ThreadViewModel.kt`: supplied renderer, exact row readiness and composition ownership.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-testing-foreground-read-tracking.md`, `remote-conversation-repository.md`, `mobile-protocol-v2-wire-layer.md`, `dependency-injection-host-conversation-source.md`: retain exact row callbacks, owner-qualified retry, and fresh connection facts.
- `../pyrycode/docs/protocol-mobile.md` (conversations, marking read, Security model) and `../pyrycode/docs/knowledge/decisions/042-daemon-built-thread.md` (Read marks): wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: dark thread with overlay app bar, alternating blue message surfaces, rule/label/rule session divider, and bottom composer/status area. Existing Material theme colours and body typography remain; this change adds evidence and callbacks without changing geometry, assets or follow-to-latest controls.

## Context

Delivery, complete rendered coverage and sight are separate. Thread unread compares current usable daemon shown metadata with confirmed shared read state. This supplied-input slice does not activate thread capability advertisement or sync/cache integration (#1988/#1984) and does not change queue settlement (#1986). No decision record is needed.

## Design

Add defaulted shown metadata and thread-support facts beside `ConversationReadMarks`. Summary DTOs tolerate omitted/null metadata; numeric metadata is accepted only in the protocol safe-integer range. Malformed shown metadata becomes unavailable, rather than leaving an older usable reading in place. List snapshots and updates replace shown availability while confirmed read and legacy entry facts retain monotonic merges. Legacy live receipt never raises shown metadata.

A common usable unread watermark chooses shown metadata on thread connections and latest entry on legacy connections. Attention precedence and local fallback remain unchanged; unavailable thread metadata grants neither an unread verdict nor notification coverage.

`AuthoritativeThreadPresentation` carries the store-certified checkpoint, generation and shown row revisions indexed by the scoped list key, independent of displayed value equality. Generation joins its host/conversation/epoch scope. Qualification requires usable summary metadata, complete coverage at least through the shown watermark, no repair/reset, and no rendered revisions beyond certified coverage. The checkpoint sent is bounded by the shown watermark; item IDs, revisions and batch versions never become that watermark. Every shown row revised after the confirmed read mark requires sight of its exact scoped key and revision, including intermediate appends below the proposed watermark. Revisions are conservative sight requirements, never completion proof; supplied row evidence must still reach the shown watermark.

Extend the existing `NewestContentPresented` event with optional presentation, composition binding, newest scoped row key and a map of seen scoped keys to exact revisions, preserving existing constructors. Candidate selection and row callbacks match list keys rather than `ThreadItem` equality, including equal-content banners, dividers, compactions and refusals. A composition-local ledger admits required rows only while resumed, unobscured and inside the measured message viewport, and requires newest content currently visible before dispatch. Replaced rows and generation changes retire readiness. The ViewModel validates the active presentation/binding, host/conversation, newest key/content and the carried sight proof before forwarding to the existing retry facade; it never substitutes all held rows for seen rows. The host-availability guard applies to authoritative sight; legacy/local fallback keeps its established semantics. Summary receipt cannot dispatch sight. Stateless screen state gains defaulted read facts; production capability selection and authoritative input collection remain #1988.

## State and concurrency model

Existing StateFlow summary projections and ViewModel scope remain. Readiness and sight are composition-local; `LaunchedEffect` owns viewport observation and cancellation on exit. The action publisher's binding fences retired callbacks. Host-qualified retries retain their application-owned scope and cancellation through `dispose`; lifecycle background socket closure does not erase already-qualified work. No new dispatcher, mutex or I/O path.

## State transitions and identity reuse

| Event | Required probe |
|---|---|
| Latest shown exceeds coverage; partial then complete | `AuthoritativeReadSightTest` supplied partial snapshot, certified completion and withheld checkpoint |
| Shown append; hidden/state-only delivery | `AuthoritativeReadSightTest` watermark bounds and exact content sight |
| Intermediate offscreen append between confirmed and shown | `unseenIntermediateAppendMustWithholdRead`, `offscreenIntermediateAppendRequiresItsOwnSightBeforeNewestCheckpoint` |
| Equal displayed values under distinct scoped identities | `equalContentNonMessageRowsMustNotShareSight`, `equalBannersMustNotShareNewestSight`, production ViewModel proof validation |
| Omitted, invalid and explicit-zero summary | `ShownVersionReadMarksTest` replacement availability, strict range, legacy independence |
| Background/foreground, covered view, scrolled away | `AuthoritativeReadViewportTest` production screen lifecycle/dialog/scroll probes |
| Text received before reveal; replacement callbacks | `AuthoritativeReadCandidateTest` production observer readiness probes |
| Epoch, host, conversation or generation reuse; retired composition | `AuthoritativeReadSightTest` scope and `ThreadViewModel` binding guards |
| Duplicate/overlapping delivery and recomposition | supplied viewport and store duplicate probes; no additional acknowledgement |
| Disconnect/closure, failure/clamp/no-op, peer confirmation | existing `ReadCheckpointRetriesTest`, `ConversationReadMarksTest` plus authoritative retry probes |
| Session replacement | shown-fact ledger retains confirmed read; no session-keyed durable mark |
| Legacy/local fallback while host unavailable | unchanged `ThreadPacedReadViewportTest.readVersionInvariant_pendingAndSkippedContent_waitForFrameLifecycleAndReveal` |

## Error handling

Invalid or unavailable shown metadata withholds qualification and authoritative verdicts. Missing coverage, incomplete reveal, unseen required content, retired bindings and wrong owners reject sight locally. Existing network failures and lower confirmations keep pending work; only confirmed daemon facts advance the read mark. Diagnostics contain static event/outcome codes only.

## Testing strategy

Write failing focused tests first, then implementation. Unit probes drive summary projection, presentation qualification, ViewModel dispatch and the actual stable facade/retry path. Supplied Compose tests under sharedTest use the production screen/viewport for lifecycle, scroll, overlay and delayed reveal. Retain legacy read, attention and viewport tests. Run focused nonzero-count Robolectric/unit suites, lint, assembleDebug, sharedTest Android compilation, forced Spotless and final pre-verify after merging main. No new device-only tests or scripted scenario; #1988 owns the named rung-3 and combined daemon acceptance.

## Open Questions

None. Supplied authoritative rendering remains dormant in production until #1988.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: malformed/omitted shown metadata must invalidate availability, not preserve an older watermark. Strict safe-integer parsing occurs in the summary boundary; existing bounded text rendering remains inert. `readCheckpoint` requires exact sight of all shown row revisions after the confirmed mark, so an intermediate unseen append cannot be skipped when advancing to a later watermark. Equal displayed values never identify rows or collapse their evidence; scoped list keys and exact revisions cross the renderer/event boundary.
- [Tokens] No credentials are added or copied. Existing Keystore ownership and revocation stay unchanged.
- [Files/storage] No file paths or persistent content are added. Qualified retries retain existing memory-only host ownership.
- [Android attack surface] No exported component, intent, permission or WebView changes. Sight depends on focused destination and unobscured measured viewport.
- [Cryptography] Existing Noise_IK_25519_ChaChaPoly_BLAKE2s and key/nonce lifecycle are untouched.
- [Network/I/O] Existing command/clamp, timeouts and reconnect backoff remain. Dropped/reordered relay delivery cannot certify coverage; only store checkpoint does.
- [Errors/logs] Static event/outcome diagnostics only; never log identifiers, payloads, text, tokens, paths, decrypted bytes or exceptions.
- [Concurrency] MUST FIX addressed in design: retired composition/host/epoch/generation cannot reuse sight. Optional event binding and current presentation validation fence dispatch; already-qualified host work survives closure without creating new sight. The seen ledger, newest candidate and retired callbacks retain scoped keys and revisions; ViewModel validation uses the carried proof rather than manufacturing sight from receipt. Host availability withholds authoritative sight without disabling the separate legacy fallback route.
- [Threat model] Compromised relay delay/reorder is coverage-fenced; malformed daemon metadata is unavailable. Rooted-device token theft and screenshot/accessibility/keyboard leakage remain outside this metadata-only change under existing product protections.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-11

## Revisions

- 2026-10-11: Reconnect probing required a connection-identity fence in addition to the presentation generation. Capture the facade's current connection when a composition publishes and reject new sight after that owner changes. Qualified pending work remains in the existing retry path. Reset the bounded sight ledger when the presentation instance changes so removed/reintroduced rows cannot inherit evidence from an older rendered view.
- 2026-10-11 (verifier findings 1–3): The watermark-only revision threshold omitted unseen intermediate appends, and `ThreadItem` value equality merged distinct non-message rows. Require sight of every shown row revision after confirmed read, indexed by scoped list key and exact revision throughout projection, candidates, ledger and event validation. Preserve the coverage/summary bounds. Restrict the host-availability guard to authoritative sight so the existing legacy/local fallback test retains its assertions. Supplied long-thread fixtures now set the confirmed mark for previously read filler rows; separate probes withhold advancement until every unread row is seen.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/thread-screen.md` (Foreground read checkpoints), `thread-screen-testing-foreground-read-tracking.md` (Foreground read tracking), `remote-conversation-repository.md` (Daemon conversation read marks), `mobile-protocol-v2-wire-layer.md` and `dependency-injection-host-conversation-source.md` (owning read/attention sections) for separate shown availability, confirmed read and legacy activity facts; certified coverage and foreground sight; scoped row/composition/connection fences; and durable qualified retries. Record the dormant supplied-input seam and #1988's activation/live-acceptance ownership.

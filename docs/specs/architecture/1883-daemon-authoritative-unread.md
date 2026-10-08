# #1883 — Derive unread from the shared daemon read mark

## Files read

- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt`: `HostAttentionState` owns local positions, completion deduplication and attention precedence.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt`: `launchAttention`, `markOpened` and `Held` own per-host collection, restoration and publication.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `observeReadMarks` exposes connection facts; add a defaulted host-wide observer beside it.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt`: `observeReadMarks`, `applySnapshot`, `upsertConversation` and `recordLatestEntry` already merge confirmed marks and received durable ids.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: read observers delegate to the existing projection.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationReadMarks.kt`: nullable unsigned fields distinguish unavailable facts from zero; reuse without another merge.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt`: the row-open path calls `markOpened`; its contract remains compatible.
- `app/src/test/java/de/pyryco/mobile/di/ConversationAttentionTest.kt` and `HostConversationSourceAttentionTest.kt`: pure folds, persisted fallback, frame-driven integration and two-host fixtures.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRowsTest.kt`: existing visual and accessibility assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`, `DeterministicInteractiveStreamE2ETest.kt` and `SecondClientPeer.kt`: live attention, phone acknowledgement, scripted ping and peer history seams.
- `docs/knowledge/features/dependency-injection-host-conversation-source.md`: attention and alert bookkeeping are independent; retained bundle guards alone do not reject superseded repository callbacks.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-contract.md`: host-keyed local positions retain their existing format and app-private, backup-excluded custody.
- `docs/knowledge/features/channel-list-viewmodel.md`: attention joins existing host-qualified consumers without changing row presentation.
- `docs/e2e-interactive-stream.md`: rung-3 and rung-4 harness ownership and evidence.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: “Marking a conversation read”, durable history identity and “Security model” are the wire contract.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Read design context and screenshot on 2026-10-08. The host/channel tree has compact indented rows, bodySmall channel names and a 6dp leading dot. Preserve the existing green Unread dot, hollow Idle dot, Running/Waiting states, theme tokens and accessibility descriptions; this changes the facts selecting the state, with no geometry or asset changes.

## Context

#1881 and #1882 landed confirmed host-local shared marks and latest durable identity. Attention still clears through local tokens and destination opening. The newest known durable entry must stay unread until the daemon confirms a mark covering it. Notification cancellation belongs to the dependent slice. No decision record is needed.

Forecast: approximately 650–850 written lines including tests and this plan, five small production edits, no new exported types, one additive observer and one production consumer. Three acceptance criteria and fewer than ten conditional branches. The nearest analogue `cabf8b95` added 281 and removed 7 lines; the extra work here covers mixed versions, unsigned comparisons and peer evidence. No consumer migration or declaration removal is required.

Overlap: #1682, #1689, #1690, #1691, #1693, #1695, #1766, #1879 and #1888 edit other live methods in `InteractiveStreamE2ETest`; changes here stay local to the attention method and additive peer helpers.

## Design

Add `ConversationRepository.observeHostReadMarks(): Flow<Map<String, ConversationReadMarks>>`, defaulting to an empty map. The remote implementation delegates to a distinct projection of the existing `ConversationListProjection` map. It issues no request, adds no inbound consumer and performs no new identity/checkpoint fold. Attention uses exact unsigned `latestEntryId > readUpTo` when that conversation has a present confirmed mark. An unknown latest id produces no durable unread assertion, never a guess from local tokens.

`HostAttentionState` holds the supplied map and includes its keys in `resolve`. Its modern branch ignores local positions. `opened` and `rowsAdded` do nothing to local positions for supported conversations; `completed` still records bounded counted turns for notification candidates but updates local positions only for fallback conversations. Legacy paths and persisted format remain unchanged. No UI state, Event or composable shape changes.

`HostConversationSource` collects the host map under the entry job. Disconnect retains last known modern attention while clearing Running/Busy as today. Each non-null repository replacement clears previous read facts before accepting its own map, so an old daemon cannot inherit support from a previous connection or cached rows. Guard read-fact callbacks with current repository identity as well as bundle identity. Cache restoration never supplies modern facts. Keep existing open calls so legacy behavior remains intact.

## State and concurrency model

Reuse the source's injected dispatcher, owning `SupervisorJob`, hot state outputs and synchronized mutation/publication. The new cold observer reads the existing repository StateFlow and uses `distinctUntilChanged`. `collectLatest` cancels old read-fact collection on replacement; identity checks reject late callbacks. Entry removal/bundle replacement and `dispose` cancel all jobs. Lifecycle socket closure remains the driver's responsibility. No suspension occurs inside the source monitor and no new locks or retry jobs are introduced.

## Error handling

No new I/O or result type. Existing typed decoding rejects malformed read fields; confirmed replies/pushes and durable identity reach attention through repository facts. A legacy/default empty map selects local fallback. Do not issue optimistic marks, turn ids into durable ids, or log daemon identifiers, numeric checkpoints or content. Emit only a static read-facts lifecycle event from the source.

## Testing strategy

Write failing attention tests first, run them red, then implement. Pure tests cover unsigned boundaries, missing latest/zero mark, local-open/view/completion immunity, precedence and duplicate completion. Source tests drive real remote frames for lists, peer pushes, received newer durable identity, replay, same-id hosts and replacement with an older daemon. A controllable repository/cache proves local positions remain fallback-only and survive restart. Existing row tests verify unchanged dot descriptions and visuals; existing thread/notification attention tests check other consumers.

Extend the named rung-3 attention method: preserve A's phone-to-peer read assertion and B's permission checks; then the peer reads B's real reply from history, confirms its mark and the phone list dot clears while remaining on the list. Add the peer's correlated read command helper. Extend scripted `ping` with a durable channel post after leaving the thread and a peer history/read acknowledgement clearing the list dot. These remain device tests because they require the real Noise/relay/daemon, storage and background dispatchers. Run scripted `ping` as the focused rung-4 check, compile instrumentation, and leave fresh full real-Claude execution to the dispatcher.

Run focused unit/shared checks, lint, assembleDebug, instrumentation compilation and forced Spotless. Commit and push before final verification; merge main once, then run the full unit/shared suite, assembleDebug and `scripts/pre-verify.py --gradle` with the concrete PR body.

## Open Questions

None. Facts already merged in the repository remain the single authority; absence of read-field support on a replacement connection selects fallback.

## Revisions

- 2026-10-08: The first scripted run rejected the new post prefix because `post_batch` restricts fixture names to `e2e1833-`. Reuse that existing allowed prefix; the peer-read contract and control boundary remain unchanged.

- 2026-10-08 (rework, finding 1): Independent replacement collectors did not order the read-fact reset before rows/completions. Move generation establishment into synchronized `updateAttention`, record the exact repository on `Held`, reset facts only once per replacement, and guard row callbacks by repository identity. Reverse-order dispatcher tests force replay rows and completion before the read collector and prove legacy unread plus duplicate immunity.
- 2026-10-08 (rework, finding 2): The daemon protocol explicitly omits `history_entry_id` from channel-post pushes. The scripted twin now waits for the completed post to arrive, then requests a fresh list to obtain its durable latest id before the unchanged unread/peer-clear assertions. Peer history must contain that exact post. No production wire or identity fold changes.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/dependency-injection-host-conversation-source.md`, Attention state: daemon-authoritative unread, legacy-only local positions and per-connection support/lifetime.
- `docs/knowledge/features/conversation-cache.md` and `conversation-cache-contract.md`, read-position family: `ReadPosition` remains the older-daemon fallback without a format replacement.
- `docs/e2e-interactive-stream.md`, verification status: record fresh dispatcher full live-gate executed/failed/skipped counts and confirmation that `interactiveTurn_attentionDot_followsARealTurn` ran and passed.

## Security review

**Verdict:** PASS

- [Trust boundaries] Consume only typed `ConversationReadMarks` from authenticated repository decoding. MUST FIX addressed in design: cached rows and previous connections must not negotiate support; reset facts on a new repository and guard callbacks by its identity. No daemon text enters new render paths.
- [Tokens] No credentials or security tokens are created or changed. Legacy UUID row tokens remain equality keys and never become durable ids.
- [Files/storage] No new files or cache format. Local positions remain bounded in the existing app-private `noBackupFilesDir` cache with atomic writes; its accepted content-at-rest posture is unchanged. Shared marks are not restored from disk.
- [Android attack surface] No manifest, exported component, intent, provider, push or WebView changes. Opening a destination cannot fabricate modern read proof.
- [Cryptography] Reuse Noise_IK_25519_ChaChaPoly_BLAKE2s and existing Keystore credentials; no new cryptographic operation or nonce lifecycle.
- [Network/I/O] Reuse existing bounded transport, unsigned decoder, request timeouts and reconnect backoff. No additional request or subscription at the wire boundary. Unknown latest ids never become permission to mark content read.
- [Errors/logs] New logs contain only static event names. Never log marks, ids, tokens, keys, pairing records, frames, message bodies or peer history.
- [Concurrency] MUST FIX from re-review addressed: `updateAttention` establishes the current repository generation under the monitor before any event, row or read-fact mutation; every replacement resets prior read facts exactly once. Repository-qualified row/read callbacks reject superseded sources. This prevents rows or completions from consuming prior-daemon support and losing fallback positions. All jobs are cancelled through entry/source ownership; no monitor is held across suspension.
- [Threat model] A malicious relay can delay/drop but cannot forge authenticated marks through Noise. A hostile daemon can misstate its own facts; unsigned comparison uses no arithmetic, scheduling or file paths. Rooted disk token theft and UI screenshot/accessibility/keyboard leakage remain governed by existing Keystore/UI controls, unchanged by a content-free state projection. Existing busy/event callback races outside read-fact collection remain out of scope.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

# Retain cached segments beside same-key user rows (#1979)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeRows` rejects raw key collisions after typed admission; `legacyDeltaMatches`, `withUniqueMessageKeys` and `ThreadRowAnchors` already separate overlap, key allocation and placement.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `ordinaryId` and `reconciliationId` preserve ordinary identity independently of aliases.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot` passes last-drawn owners and rebases settled content at disconnect; `writeHistoryPosition` uses the same merge.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryMessageIdentityTest.kt`: ownership, alias replay and real-file restore patterns.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryReconciliationTest.kt`: neighbour placement and legitimate legacy reconciliation; three collision tests currently expect distinct incoming identities to be discarded.
- `app/src/test/java/de/pyryco/mobile/data/repository/AssistantParentAttributionTest.kt`: `collidingTurnKeys_doNotOverwriteHeldLaneAttributionOrGainAuthority` must retain distinct identities and their lane-specific parents without granting users assistant authority.
- `app/src/test/java/de/pyryco/mobile/data/repository/UnsignedHistoryTest.kt`: durable positions, unsigned boundaries and collision placement coverage.
- `app/src/test/java/de/pyryco/mobile/data/repository/CoalescedThreadWritesTest.kt`: ignored direct-merge/observer/reconnect/fresh-cache regression.
- `docs/knowledge/features/caching-conversation-repository.md`, “How the restore merges with live rows”: owner claims affect keys only, not admission or placement; merges must remain indexed.
- `docs/knowledge/features/data-model.md`, `Message`: aliases never define wire identity or get decoded by stripping suffixes.

## Context

Cached assistant deltas and ordinary user rows can share renderer keys without sharing reconciliation identity. Two raw-key admission guards drop either incoming row before the allocator runs. Remove those obsolete guards so identity admission and renderer allocation each enforce their own contract. No decision record, UI, wire, storage-format or scheduling change is required.

One deliverable: lossless typed merge admission. Forecast approximately 350 written lines including plan and tests, one production file, no new exported declarations, no signature/caller migrations, three acceptance criteria and two removed reject branches. No in-flight feature branch overlaps the planned files.

## Design

`mergeRows` admits rows through its existing typed `positions`, `admitted` and demonstrated `legacyMatches` checks. Remove `heldMessageIds` and both `keyTwin` rejection branches. Keep all placement calculations and atom joining unchanged. `withUniqueMessageKeys` continues to reserve surviving displayed owners, then receiver claims, then allocate unused newcomer aliases. Ordinary aliases retain `reconciliationId`; segment identity remains `(turnId, seq)`.

Add direct unsigned-cache probes in `HistoryMessageIdentityTest` for both receiver directions, replay permutations, empty/one-row input, duplicate input, overlap at each neighbouring position, unsigned durable bounds and occupied alias candidates. Cover the shared history lane as well. Strengthen the three old collision-discard tests in `HistoryReconciliationTest` to require retained content/keys and distinct incoming identities, while preserving held-row assertions. Enable the existing coalesced reconnect probe without changing its assertions.

## State and concurrency model

The reducer stays pure and introduces no state, jobs, dispatchers or flows. The cache observer still processes snapshots sequentially on its injected processing dispatcher; its connection-boundary base, owner claims and collection-owned coalescing writer are unchanged.

## State transitions and identity reuse

| Event | Required probe |
| --- | --- |
| Same renderer key arrives as a user row or cached delta, in either direction | `identityInvariant_unsignedCacheCollisionRetainsBothArrivalDirectionsAndReplay` |
| Same typed identity is duplicated or replayed in different input orders | `identityInvariant_unsignedCacheCollisionRetainsBothArrivalDirectionsAndReplay` |
| Overlap at start, middle or end introduces distinct content on either side | `placementInvariant_unsignedCacheCollisionKeepsNeighboursAndDurableBounds` |
| Lifecycle evidence arrives before or after the aliased ordinary row | `placementInvariant_unsignedCacheLifecycleAnchorsUseOrdinaryIdentityBesideSameKeySegment` |
| Lifecycle evidence overlaps an original segment whose sequences now span reordered retained rows | `placementInvariant_lifecycleUsesCombinedSplitSegmentRange_inHistoryAndCacheReplay` |
| Alias candidates already belong to ordinary rows | `ownershipInvariant_unsignedCacheCollisionPreservesDisplayedSegmentAndOccupiedAliases` |
| Empty disconnect and reconnect while persistence is pending | `identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect` |
| Successful write and fresh-instance file restore | `identityInvariant_collidingRendererKeysKeepBothIdentitiesThroughPendingReconnect` and `reconnectInvariant_unsignedCacheCollisionSurvivesFreshRestoreAndReplay` |

## Error handling

No new failure mode or I/O boundary. Distinct-key collisions become allocated aliases rather than rejected content. Existing duplicate and demonstrated whole-turn overlap suppression remains authoritative; existing cache errors and static logs stay unchanged. The pure merge adds no payload diagnostics.

## Testing strategy

Write and run the probes red before removing production guards. Run `HistoryMessageIdentityTest`, `HistoryReconciliationTest`, `UnsignedHistoryTest` and `CoalescedThreadWritesTest`, plus affected existing cache, alias, lifecycle and assistant-parent tests. Inspect executed counts. Run lint, debug assembly, Spotless apply and forced check, then final assembly and `scripts/pre-verify.py --gradle` after the last main merge and push. No screen/device or real-Claude scenario is needed for this data-layer admission fix.

## Open Questions

None. Collision-discard expectations describe the obsolete guards, not genuine typed duplicates or demonstrated legacy overlap; replace them with stronger lossless assertions.

## Documentation handoff

- Pending for documentation: `docs/knowledge/features/caching-conversation-repository.md`, "How the restore merges with live rows", and `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, "History pages fold into the same thread": explain typed admission despite renderer collisions, ordinary identity under aliases, displayed key ownership and retained lifecycle-neighbour ranges.
- Pending for documentation: the same sections should record the enabled reconnect/persistence probe and direct collision/lifecycle regressions; alias allocation cannot decide admission, and atomizing an anchor must not narrow its represented neighbour range.

## Revisions

- 2026-10-10: After admission was repaired, `placementInvariant_unsignedCacheLifecycleAnchorsUseOrdinaryIdentityBesideSameKeySegment` failed because `ThreadRowAnchors` indexed a segment under the ordinary row's raw renderer key. Use `mergeIdentity` for its identity index and fallback lookup, retaining the existing sequence and legacy-whole-turn anchor paths. The probe checks markers before and after the user, history/cache lanes and replay without moving retained rows. This is part of the ticket's neighbour-placement invariant, in the same production file; no new state, exported type, signature migration or error branch is introduced.
- 2026-10-10: The unchanged `wholeTurnArrivingOverRetainedSegments_anchorsFinishAfterWholeTurnInHistoryAndReconnect` caught a regression in that typed lookup: a fully recovered legacy whole turn no longer found its surviving segments via a raw id. Feed the already-reconciled `incomingAtoms` to lifecycle placement so exact legacy records anchor through their proven sequences. No text-based or raw-key fallback is added; the existing whole-turn assertion stays unchanged.
- 2026-10-10: Final sizing is approximately 250 written lines across one production file, three test files and this plan, with no new exported declarations or consumer migrations. The three acceptance criteria and two removed admission branches remain within the sizing limits.
- 2026-10-10 (verifier finding 1): Supersedes the earlier `incomingAtoms` lifecycle-placement approach. Preserve `attributedIncoming`'s original segment boundaries and attach `legacy.records` to ordinary assistant rows using their ordinary identity before lifecycle placement. A leading marker then uses the minimum retained neighbour represented by the entire segment; a trailing marker uses the maximum. Keep typed identity lookup in `ThreadRowAnchors`. The combined-range probe covers leading/trailing evidence and replay in both unsigned merge lanes; the existing legacy whole-turn lifecycle assertions remain unchanged.
- 2026-10-10 (verifier finding 2): Replace the two obsolete discard expectations in `collidingTurnKeys_doNotOverwriteHeldLaneAttributionOrGainAuthority` with lossless assertions in both merge lanes and arrival directions: held keys, distinct keys, exact content, typed replay identity and lane-specific attribution. A colliding user retains its original row and never receives an assistant parent.
- 2026-10-10: Rework sizing is approximately 340 written lines across one production file, four test files and this plan, with no new exported declarations or consumer migrations; the three acceptance criteria and two removed admission branches remain within the limits.

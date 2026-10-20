# Open and browse saved legacy history in local pages (#2266)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/LegacyRoomStorage.kt`: `LegacyThreadPage`, `LegacyPageEvidence`, `page`, `mutateRows`; bounded evidence and snapshot-bound tokens.
- `app/src/main/java/de/pyryco/mobile/data/cache/MigratingConversationCache.kt`: import-first page wrappers, owned writes, scoped retirement and host admission.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: `observeThreadSnapshot`, `ThreadWriter`, `writeHistoryPosition`, `rememberObservedCoverage`, `delete`; preserve owned persistence.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadSnapshotSource.kt`: atomic row/suppression observation.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryCoverage.kt`: receipt validation, cursor walks and exact row claims.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryDisplayProjection.kt`: `projectDisplay`; optional marker preparation cannot hold first readable content.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `historySeed`, `receivedThread`, `threadContent`, demand/retry/gap handlers and newest-arrival handoff.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: committed-frame measurement, renderer bound and retained file controls.
- `docs/knowledge/features/caching-conversation-repository.md`, `thread-screen-oldest-end-history-demand.md`, `thread-screen-testing-saved-first-draw.md`: preserve movement-only demand, delayed readiness schedules and optional projection independence.
- `docs/knowledge/decisions/0009-sqlite-room-conversation-storage.md` and `docs/specs/architecture/2161-import-saved-threads-room.md`: Room, migration and retirement contracts.
- `OwnedLegacyCoverageTest`, `ThreadNewestPageHandoffTest`, `ThreadScreenHistoryTest`: production-backed test patterns.

Overlaps: #1947 changes unrelated settings projection; #2009 changes retained first-draw readiness; #2175's route wiring is already incorporated by merged #2245. Keep changes local and additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot: dark thread with opposite-side message bubbles, rule/label/rule session delimiters, overlay app bar and bottom composer. Existing Material 3 body/title roles and scheme colours remain; use the existing history loading, offline and retry rows. No geometry, assets or theme changes.

## Context

Whole-thread row/history restoration defeats indexed storage's first-draw bound. A bounded opening must also expose older saved content, otherwise it hides readable history offline. Those form one usable consumer of the existing page API. Internal-gap local retrieval remains #2267. Negotiated hosts retain their exclusive route. No new decision record is needed; ADR 0009 owns the storage choice.

## Design

- Add an internal saved-history capability beside `ThreadSnapshotSource`, implemented by the caching repository. The ViewModel uses it for bounded scalar walk seeding and explicit oldest-end continuation. File-cache compatibility keeps its existing path.
- A caller-owned `LegacyLocalWindow` shares the initial migration-aware newest page between seeding and observation. It retains rows, local continuation, exact window evidence and captured retirement identity. Each pull reads at most 200 older persisted rows; it never spends a daemon page or changes the network walk.
- Snapshot-local saved evidence is a display projection, never a full `HistoryPosition`. Merge its actual spans, gaps, row claims and anchors with received network evidence for display without inventing gaps between omitted spans. Preserve the saved unknown edge explicitly when projecting markers. Only real network receipts can join an actual gap or close unknown coverage.
- The caching observer combines local-window arrivals with delegate snapshots on its processing dispatcher. Live rows and suppression win, renderer identities remain held, and deliberate observed removals cannot reappear from a delayed page. Restore-only rows extend the writer's observed baseline without becoming new write intents. Existing owned row and coverage saves remain responsible for persistence.
- Rejected continuation tokens recover against the current ordinal of the oldest surviving held identity, with a bounded newest fallback when no anchor survives. Recovery never reads the full row or evidence tables.
- Cache retirement identities fence local results after conversation/host removal, including re-admission under the same host id. Durable tombstones and existing owned-save fences remain intact.
- The ViewModel attempts local continuation before offline/in-flight guards on older demand and explicit retry. A selected oldest marker takes that same path; internal markers retain existing daemon demand. Open, reconnect, readiness and slot release create no local older demand. Newest refresh and delayed-readiness/busy-slot schedules stay unchanged.

## State and concurrency model

Window initialization and continuation use a per-window mutex and caller cancellation, with no application scope or autonomous hydration. Immutable window state feeds the observer. Live reconciliation stays serialized within collection; suspended disk reads cannot block live updates. Repository history-write mutex remains above cache/storage locks. Retirement changes are recorded before pending results can publish. ViewModel requests belong to `viewModelScope`; collection exit cancels its writer and discards the window. Background socket closure retains settled rows and continuation without requesting older content.

## State transitions and identity reuse

| Event | Focused probe |
| --- | --- |
| Fresh offline/held-newest open and restart | `LegacyLocalHistoryTest.boundedOpeningInvariant_fragmentedRoomWindow` and database device method |
| Repeated oldest-end demand; exhaustive 18000 rows | `LegacyLocalHistoryTest.continuationInvariant_visitsEverySavedRowOnce` |
| Continuous span with unrelated old cursor/walk cells | `LegacyLocalHistoryTest.boundedEvidenceInvariant_continuousSpan` |
| Reconnect and held newest; local page independent of network slot | `LegacyLocalHistoryTest.demandInvariant_offlineAndHeldNewest` plus unchanged `ThreadNewestPageHandoffTest` |
| Live/page overlap, duplicate identities and removal | `LegacyLocalHistoryTest.mergeInvariant_liveAndRemovalWin` |
| Stale token after owned save/replacement | `LegacyLocalHistoryTest.continuationInvariant_staleTokenRecoversFromHeldAnchor` |
| Conversation/host retirement during read/save; exact ownership and fresh instance | `LegacyLocalHistoryTest.retirementInvariant_pendingWorkCannotResurrectOrCrossHosts` |
| Oldest boundary marker selected by physical reader pull | focused addition beside `ThreadScreenHistoryTest` |
| Collection cancellation and reopen | continuation/retirement probes and database device method |

## Error handling

Empty/unreadable storage keeps the normal newest daemon path. A failed or retired local read publishes nothing and creates no automatic retry. A busy local read consumes no additional demand. Invalid tokens recover only on explicit demand, from current held anchors. Logs contain static event/outcome codes and counts, never owners, cursors, message text, proofs or payloads.

## Testing strategy

Write the production-wired Room opening regression first and observe the existing unbounded restore fail it. Use real on-disk Room through `MigratingConversationCache`, repository and ViewModel for the full fragmented fixture, read/decode budgets, exact exhaustive traversal and ordering/retirement probes. Preserve existing file cache and owned-save tests, and newest handoff schedules. A shared screen test proves the physical oldest-marker gesture.

Add `SavedThreadFirstDrawDeviceTest.databaseThreads_firstNewestDrawWithinBound_offlineAndHeldNewest_firstOpenAndReopen`: real on-device database and committed frames require native execution. Seed ordinary/full fragmented fixtures outside timing; every case constructs fresh database/cache/repository/ViewModel state inside timing. Reuse renderer policy and frame observer; log every construction/restore/snapshot/content/frame phase and read count. Keep all four compatibility methods and their exact fixture/assertion contracts unchanged. Builder runs the new method; dispatcher must execute the full configured UI gate and retained compatibility methods with counted XML and raw evidence before verifier PASS. Linux only.

Focused checks: new integration class, affected cache/repository/ViewModel and shared gesture classes, lint, APK assembly, instrumented compilation, Spotless and final `pre-verify.py --gradle`. No complete builder suite.

## Open Questions

None. Forecast: approximately 1500 written lines including tests and plan, three new exported/internal companion declarations, four changed production consumers, four acceptance criteria and eight rejection paths. The family is already at the split-depth limit (#2068 → #2251 → #2266).

## Documentation handoff

Pending for documentation stage:
- `docs/knowledge/features/thread-screen-oldest-end-history-demand.md`: local backward continuation and oldest-boundary gesture, separate from daemon coverage.
- `docs/knowledge/features/caching-conversation-repository.md`: bounded restoration, owned saves and local continuation versus network walks.
- `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`: measured database-path evidence alongside retained file proof; dispatcher full configured acceptance remains required.

## Security review

**Verdict:** PASS

- [Trust boundaries] Window pages retain validated legacy types and exact case-sensitive host/conversation ownership. The negotiated route guard precedes legacy initialization; saved rows cannot activate a forbidden route.
- [Tokens] No credential handling changes. Local continuation is opaque, owner/generation bound and distinct from daemon cursors; neither enters logs.
- [Files and storage] ADR 0009's plaintext app-private `noBackupFilesDir` Room database and transactional owned writes remain. No new file paths, external storage or backup surface. Rooted-device plaintext access remains the existing accepted cache threat model.
- [Android surface] No exported components, intents, providers, WebViews or new permissions. Existing inert message rendering remains.
- [Cryptography] No changes to Noise, keys, nonces or TLS.
- [Network and I/O] Local pages are capped at 200; no autonomous full hydration or network request. Existing transport limits and timeout/backoff policy remain. Partial evidence never reaches a whole-cache replacement.
- [Errors and logs] SHOULD FIX: preserve only static outcome/count diagnostics, with no owner, payload, cursor, text or proof logging.
- [Concurrency] SHOULD FIX: capture retirement identity before reads and verify after suspension, including same-id host re-admission. Preserve the writer's observed removals when pages arrive; no delayed read may restore a removed row. Use caller scopes and serialize only state mutation, never hold live delivery behind disk.
- [Threat model] Malicious relay delay/reordering remains handled by existing authenticated transport and generation fences; hostile saved daemon text stays inert and bounded by existing codecs. Phone token theft and keyboard/screenshot leakage gain no new surface. Deletion/restart probes cover stale plaintext resurrection and host isolation.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-20

# Bounded authoritative opening (#2333)

## Files read

- `data/cache/ConversationCacheDatabase.kt`: `AuthoritativeItem`, `AuthoritativeDao` and the additive migration chain; authoritative selection currently counts raw facts.
- `data/cache/AuthoritativeRoomStorage.kt`: `page`, `metadata`, `decode`, `importThread` and `upsertAuthoritativeThread`; preserve owned writes and committed certification.
- `data/cache/MigratingConversationCache.kt`: import admission, retirement, authoritative pages and diagnostic counters.
- `data/repository/CachingConversationRepository.kt`: `restoreAuthoritative`, `observeAuthoritativeThread`, `persistAuthoritative` and the negotiated route; disk restoration currently reads everything.
- `data/repository/AuthoritativeThreadStore.kt`: `restore`, revision merge, runtime generations and retirement fences.
- `data/repository/NegotiatedThread.kt`: observation and reader-demand ownership.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`: shown ordered facts, queued feedback, active tasks, agent placement and parent closure.
- `ui/conversations/thread/ThreadViewModel.kt`: negotiated observation, older demand and retry.
- `di/AppModule.kt`: `ThreadDestinationFactory.repository` and `thread`, the production destination binding.
- `androidTest/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: readiness, renderer policy and committed-frame measurement.
- `test/data/repository/AuthoritativeOwnedUpsertTest.kt`: bounded owned writes and durable metadata baselines.
- `test/di/RelayConnectionFactoryTest.kt`: production destination, colliding owners, held restoration and recovered identity probes.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md`, `docs/specs/architecture/2149-authoritative-thread-cache.md`, `docs/specs/architecture/2161-import-saved-threads-room.md`: non-destructive storage and restore/retirement contracts.
- `docs/knowledge/features/caching-conversation-repository.md`, `conversation-cache-contract.md`, `caching-conversation-repository-wiring.md`, `thread-screen-testing-saved-first-draw.md`: partial views must not become replacement writes; first-frame evidence must include cold construction.
- Sibling `/work/Projects/pyrycode/docs/protocol-mobile.md`: thread queries and security model remain the wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read context and screenshot through Figma MCP. The screen has a title/back/overflow header, alternating message bubbles, labelled session rules and a bottom composer/status area. Preserve existing Compose components, Material typography and colour roles; this work changes saved input selection only.

## Context

One behaviour: a cold production authoritative destination opens its newest saved display window independently of network completion. Indexing, its sole reader and the native proof cannot ship usefully apart. The temporary reader-triggered full restore preserves older access until the paging sibling lands. No new decision record is needed.

Forecast: approximately 1900 written lines including generated schema and deterministic/native proofs, four or fewer new exported declarations, five production consumer updates, four criteria and at most nine rejection branches. This exceeds the 1600-line ceiling by approximately 300 lines. The verification floor retains the index/consumer/proof together; parentage is #2068 → #2252 → #2333, so further splitting is barred. Record `needs-human:sizing` while continuing implementation.

Overlaps: #2009 and #2175 retain already merged source changes; #2266 changes legacy selection and cache retirement. Keep authoritative edits local and additive. Its legacy DAO additions and observer replacement do not supply this design.

## Design

Add non-destructive authoritative selection indexes maintained atomically with each accepted replacement/upsert. Select visible display contributors independently of hidden/state-only facts, then include active renderer inputs and recursively resolve required parent identities by indexed lookup. Preserve held order and validate selected payloads against their indexed fields. Unknown raw fields and explicit clears remain untouched. Select only relevant first-opening evidence; active/parent extras never extend coverage.

Expose an import-aware bounded authoritative-opening capability. Keep raw-fact paging and full compatibility reads available with their existing contracts. The production caching route uses a 200-display-row opening, preserving normal negotiated newest/catch-up ownership and missing-data recovery. File compatibility and unbound repository tests retain full restoration.

Serialize concurrent opening callers without waiting for network replies. A successful disk window initializes the existing committed baseline with precisely its hydrated facts and saved metadata. Owned upserts retain all omitted disk facts. Durable certification is independent of hydrated display state: hydration changes neither checkpoint nor continuous coverage, and subsequent owned mutations retain the existing baseline checks.

Reader movement and explicit retry may launch the compatibility full restore inside the active observation's scope. Opening, reconnect and page arrival never launch it. Merge by newest accepted revision, retaining live facts, stable identities, held ties and required parents. A reset, retirement, cancelled observation or confirmed deletion invalidates pending work; reused ids/epoch strings cannot readmit it. No local position becomes a daemon continuation.

## State and concurrency model

Disk selection uses the cache IO dispatcher and one Room transaction. Decode/merge stays on the injected processing dispatcher. The observation owns compatibility work and cancellation; no global scope or detached job is added. Lock order remains repository `historyWrites`, migrating-cache mutex, storage mutex/Room transaction. Capture runtime generation and exact cache retirement ownership before suspension and recheck before merge/save. Started writes and successful-baseline publication keep their existing `NonCancellable` lifetime. Connection/background teardown remains owned by the coordinator and lifecycle driver.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Cold offline open and cold reopen; fewer/zero/one display row | `AuthoritativeWindowRestoreTest.openingInvariant_coldWindowIsBoundedAndExact` |
| Held newest/catch-up; page arrival; reconnect without reader movement | `AuthoritativeWindowRestoreTest.demandInvariant_networkCannotBlockOrExpandSavedOpening` |
| Reader movement and explicit retry; repeated collection | `AuthoritativeWindowRestoreTest.demandInvariant_onlyReaderHydratesOlderSavedFacts` |
| Hidden facts, outside active facts and recursive parents | `AuthoritativeWindowRestoreTest.closureInvariant_hiddenFactsDoNotConsumeDisplayWindow` |
| Live overlap, duplicates, reordered revisions, explicit clears and held ties | `AuthoritativeWindowRestoreTest.identityInvariant_overlapKeepsNewestRawFactsAndOrder` plus retained #2255 writer probes |
| Reset/reconnect during held restore, including reused epoch | `AuthoritativeWindowRestoreTest.generationInvariant_resetFencesHeldRestoration` |
| Exit while restoration/compatibility hydration is held | `AuthoritativeWindowRestoreTest.lifetimeInvariant_exitCancelsPendingHydration` |
| Loaded removal; conversation/host deletion during held/final save; colliding second host | `AuthoritativeWindowRestoreTest.retirementInvariant_freshReopenCannotResurrectOrLoseOtherHost` plus retained #2256/#2149 writer probes |
| Hydration followed by owned saves; fragmented saved certification | `AuthoritativeWindowRestoreTest.certificationInvariant_hydrationNeverChangesCommittedCertificate` |
| Existing database upgrade | `AuthoritativeWindowRestoreTest.migrationInvariant_existingFactsAndIndexesSurviveUpgrade` |

## Error handling

Retain graceful null reads and reporting mutation results with static codes. Invalid selected payload/index ownership restores nothing without repairing disk. Unavailable import remains retryable and cannot admit an unobserved replacement. Cancellation propagates. Existing offline/history/retry presentation remains; no new UI error mode.

## Testing strategy

Write failing deterministic probes first over a real on-disk Room database, fresh cache/repository/ViewModel instances and production negotiated destination selection. Persist 18000 displayed rows with hidden facts, fragmented evidence, active extras, outside parents and a colliding second host. Assert exact raw identities/revisions/order, selected content and parent closure, numeric SQL result/query and payload-decode counts, and certification before/after hydration and owned saves. Controlled dispatchers and deferred boundaries exercise lifecycle races. Retain affected `AuthoritativeOwnedUpsertTest`, `AuthoritativeOwnedRemovalTest`, `AuthoritativeThreadCacheTest` and storage/migration regressions.

Add one native database-opening method beside the unchanged file first-draw methods. Real disk and committed Android frame timing require `androidTest`; Robolectric cannot establish that latency. Reuse readiness, exact text/viewport containment, frame-commit callbacks and unchanged `firstDrawBoundMs`. Start before cold database/cache/repository/ViewModel construction; retain restore/snapshot/content/frame times and bounded read/decode counts for offline/held-newest first opening and cold reopening. No warm-up. Builder runs the new method only; dispatcher retains complete configured UI and named compatibility/negative-control acceptance, with counted XML, revisions, renderer and durable raw evidence. No new real-Claude capture.

Run affected deterministic classes, lint, debug assembly, instrumented Kotlin compilation and formatting. Push before final post-main assembly and `scripts/pre-verify.py --gradle`. Full suites remain dispatcher-owned and pending until counted evidence exists.

## Open Questions

None concerning product scope. Validate display-contributor indexing against the existing agent/queued presentation while implementing; any necessary change to this contract must be recorded under Revisions with its proof.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/caching-conversation-repository.md` and `docs/knowledge/features/conversation-cache-contract.md`, authoritative restore contracts: bounded restoration, required extras, durable certification versus hydrated state and temporary reader-triggered compatibility hydration.
- Pending documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, saved-thread first draw: measured database-path acceptance alongside file compatibility, preserving counted full-gate evidence and every timing.

## Security review

**Verdict:** PASS

- [Trust boundaries] SHOULD FIX: selected disk payloads must pass existing full-item validation and match exact owner/epoch/index identity. Cached facts grant no negotiated support. Unknown daemon fields stay inert.
- [Tokens] No credentials or credential storage change; selection stores existing conversation facts only.
- [Files and storage] Keep the app-private no-backup database/sidecars and additive migration. Existing plaintext cache threat model remains; rooted-device content access is unchanged. Failed reads never rewrite or delete content.
- [Android attack surface] No component, intent, provider or WebView change. Existing bounded text rendering remains.
- [Cryptography] No primitive, key, nonce or Noise change.
- [Network and I/O] SHOULD FIX: held network queries must not hold local restoration or start older hydration. Existing transport limits, deadlines and capability verdicts stay authoritative.
- [Errors/logs] SHOULD FIX: numeric counts/timings and static lifecycle/outcome codes only; never ids, epochs, raw fields, text, credentials, cursors or exception contents.
- [Concurrency] SHOULD FIX: capture exact runtime/cache lifetime before IO; check after every suspension before publish/save. Observation cancellation owns reader hydration; confirmed deletion fences final writes. Retain atomic mutation/baseline publication and lock ordering.
- [Threat model] Relay delay/reorder cannot widen certification or stop saved opening. Hostile cached facts remain validated and inert. Cross-host collisions stay isolated. Token theft and UI screenshot/accessibility leakage are unchanged by this local-storage behaviour.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-20

## Revisions

### 2026-10-20: validated restoration boundary after second review

The three findings remain repairs of bounded opening and its verification floor, under the existing grandchild sizing exception. An unordered family's indexed key contains its historical anchor and held tie witness. Resolve those witnesses by bounded index lookup alongside selected payloads and recursive parents. They are renderer inputs, never selected rows or continuous coverage. Carry their complete facts through the existing window/store merge so projection can retain historical placement and recompute it for winning structural live revisions. The 18000-row omitted-sibling fixture compares exact ordered rows through a fresh production destination and a cold storage read, retaining query/result/decode bounds.

Supplemental resolution has explicit validated, unavailable and stale outcomes. Only the exact atomically applied validated snapshot reaches delivery or the writer; unavailable reads retain the previous delivered snapshot. Retry only the bounded supplemental read with capped backoff in the observation's child lifetime, independently of another mutation and without a full-history read. A changed capture repeats validation; reset, cancellation and retirement terminate stale work. Final flush may capture a reset or new fences but must not scoop unvalidated content into a candidate. Null and classified-failure probes cover retired replay and outside-parent reparenting, automatic recovery, successful writer baselines, final flush and fresh reopen.

Run validation, winner maps, sorting, committed-identity preparation and baseline bookkeeping on `processingDispatcher`. Preserve IO admission, cancellation, `historyWrites` and atomic successful mutation publication. Re-run affected restoration, presentation, owned upsert/removal and store checks, focused native database first draw, assembly, lint and final pre-verification on Linux. Full configured acceptance and documentation remain with their existing later stages.

Security review addendum (second review): PASS for the revised design. [Trust boundaries] MUST FIX addressed by withholding unavailable supplemental captures from UI and persistence, including final flush; durable retirement cannot be bypassed by a successful no-op upsert. [Concurrency] MUST FIX addressed by returning the exact validated capture under owner/epoch/incarnation/runtime-generation/cache-lifetime checks; bounded retries belong to the observation and are cancelled on exit. [Storage/availability] SHOULD FIX addressed by indexed anchor/tie witnesses and worker-dispatched CPU work. No credential, cryptographic, transport, component, telemetry or content-rendering boundary changes. Diagnostics remain numeric/static and payload-free.

### 2026-10-20: opening capability and observation custody

`AuthoritativeWindowCache` is the small optional opening/lifetime capability, implemented by `MigratingConversationCache`. Keeping it distinct from full reads lets native instrumentation retain the production path while timing the completed disk read. `AuthoritativeItem` gains indexed display/extra flags through the additive version 5→6 migration; ordinary accepted upserts update those flags with their raw payload. The display predicate mirrors the presentation's shown ordered facts; active and unordered queued/lost inputs are selected separately, followed by indexed recursive parent lookup. SQL result counts include duplicate rows returned by the extra query even when identity decoding is reused.

Concurrent production open/acquire/observe callers share the same initial projection. Reader hydration uses the observation's child scope, with hydration jobs keyed by that scope so an older collector cannot cancel a newer collector's work. Cancellation never suspends cleanup before the existing writer's final flush. Hydration extends only the committed raw-fact baseline, excludes already retired identities and preserves the successful metadata baseline; it never records merged unsaved live facts as committed.

The initial missing-API red was followed by a concrete route red: `demandInvariant_onlyReaderHydratesOlderSavedFacts` restored 18000 rather than 200 items. The three initial probes then passed after route integration. Additional production factory, lifecycle and migration probes are in the same class. `SavedAuthoritativeDestination` shares the actual registry, coordinator, negotiated cache binding and destination factory across deterministic and native checks; only transport replies and frame scheduling in deterministic tests are controlled.

`identityInvariant_overlapKeepsNewestRawFactsAndOrder` also covers reconnect while restoration is held. Loaded removal and conservative certification are combined in `certificationInvariant_hydrationNeverChangesCommittedCertificate`; unchanged certification without removal is checked by the opening and demand probes. The native method is `SavedThreadFirstDrawDeviceTest.databaseSavedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_coldOpenAndReopen`. The existing compatibility fixture, optional-projection twin and slow negative control remain unchanged.

The opening returns its own `AuthoritativeThreadWindow`, carrying exact owner and committed partial facts. Reusing `AuthoritativeThreadPage` with a null continuation would incorrectly imply local exhaustion despite omitted older facts. Raw-fact paging and its continuation contract therefore remain unchanged.

The first expanded factory probes timed out because they awaited the legacy `ThreadUiState.items` arm. Production publishes its content in `authoritativeThread`; the probes and native content-phase measurement now use that arm. The retained failed XML records ten executed cases with five failures; it is not passing evidence. The frame listener also checks horizontal containment alongside its existing vertical containment and frame-commit requirements. No timing threshold or readiness requirement changes.

The native diagnostic run restored and projected all 200 selected rows, with exact newest text placed wholly inside the viewport, but received no commit callback after registering during `OnDraw`. The listener now requests a subsequent frame after registration, recording only its real commit callback. This conservatively includes another frame in the measured latency; readiness and renderer bounds remain unchanged. Retain the failed run and rerun the shared native class, including its slow negative control.

### 2026-10-20: verifier repair of bounded opening

All four findings repair this same opening contract; no independently useful behaviour is added. The existing grandchild sizing exception remains necessary. Add renderer placement keys for content and launch contributions with an additive Room migration. Accepted writes update affected descendant indexes using indexed parent and identity metadata, without decoding unrelated payloads. Window selection budgets actual contributions in renderer order, including hidden/settled/nested background families and launch markers. Backfill happens only during migration.

Reader hydration forwards its original demand to the negotiated route when the disk read is null, fails, or cannot apply under the captured generation. A nonzero reset generation bypasses compatibility hydration. Cancellation and deletion still fence forwarding. Production-route probes cover both unavailable reads and reset-then-demand; rerun the verifier's recorded offline/reconnect method.

After reading the window, resolve missing parents of the winning saved/live revisions through the same import-aware cache capability. Recheck owner, incarnation, generation, retirement lifetime and current live snapshot after suspension, repeating closure if live facts changed. Only committed disk facts extend the persistence baseline; supplemental reads never widen the initial certificate.

Opening loads retirement evidence only for selected identities and required extras, including current live overlap. Count every returned retirement in evidence results and all selection/identity queries. A fresh database probe with 20000 unrelated retirements proves bounded evidence and unchanged durable certification. The overlap probe now reparents a winning live child to an inactive hidden outside agent rather than only clearing its parent.

Security review addendum: PASS after these repairs. Selected payload/index validation and exact owner fencing remain mandatory. Relevant retirement lookups reject stale overlapping identities without retaining unrelated history. New metadata and sort keys are inert local indexes; no content, identity or key is logged. Failed hydration cannot suppress network recovery, and parent-resolution IO publishes only after lifetime/generation rechecks. No UI, transport, credential or cryptographic boundary changes.

The additive version 6→7 migration backfills `SavedAuthoritativePlacement` metadata and keys without rewriting raw payloads. Two indexed candidate queries each return at most the requested row count; their combined newest 200 contributions select at most 200 distinct payloads before renderer extras. Diagnostic result counts include candidates discarded during this merge and duplicate launch/content results. Ordinary text-only revisions retain placement keys; structural changes update affected descendants and ancestor-dependent anchors. `placementInvariant_settledNestedAgentsAndLaunchesUseRenderedRows`, `placementInvariant_launchAndContentEachConsumeOneRow`, and `placementInvariant_unorderedParentsKeepHeldChildTies` compare selection with the actual renderer; the first also checks an incremental lifecycle-placement update. Migration retains an outside hidden recorded parent.

`retirementInvariant_openingReadsOnlyRelevantEvidence` and `retirementInvariant_liveOverlapLoadsItsFenceWithoutHistoricalEvidence` use 20000 historical fences. The latter proves a replayed unloaded identity is rejected while only its relevant fence enters memory. `demandInvariant_failedOrNullHydrationForwardsToDaemon` and `demandInvariant_resetThenOlderForwardsToDaemon` use production negotiated destinations and real query terminal replies before the pull. Supplemental committed progress may increase applied progress but never the opening certificate. Hydration extends the committed raw/fence baseline, with retired facts excluded. The store atomically compares the captured live snapshot before restoration; a changed snapshot repeats closure resolution instead of publishing incomplete attribution.

Launch and content may be far apart even for one fact. The opening therefore carries transient selected row contributions into `AuthoritativeThreadSnapshot.openingRows`; required parent facts remain complete without inventing their unselected content or launch rows. Active extras remain explicitly included. The existing projector filters delivered rows, block membership and first-draw read evidence by that selection. Accepted live revisions/batches admit their rows; reader compatibility hydration clears the selection. The selection is scoped by its containing owner/generation snapshot, resets with that snapshot, and is never serialized or used as certification. The launch probe separates launch and ending orders to prove a fact's unselected second row cannot double the 200-row budget. This changes input selection only, preserving existing layout/components.

The first expanded run executed 18 tests with 16 failures because new index validation compared cleared nullable `Long` fields against boxed `Int` zero fallbacks. Bytecode inspection confirmed the mismatch; explicit `0L` comparisons restore valid rows. The next run passed 16/18; its two demand fixtures incorrectly combined catch-up and window certification fields in one terminal. They now send the protocol's mutually exclusive shapes. Both failed XML reports remain retained, rather than counted as passes.

Reassessed scope remains one repaired opening behaviour and its verification floor. Before final formatting the whole branch measured 3036 inserted and 36 deleted lines across 14 files, including both generated schemas. The existing grandchild sizing exception and `needs-human:sizing` remain applicable; this repair adds no independently shippable behaviour. Final verification includes the renderer selection lifetime, exact parent closure, relevant retirement evidence and demand forwarding.

The bounded observation also resolves new identities and missing parents after opening, before delivery to its ViewModel. It reuses the same committed-baseline restoration and atomic snapshot/lifetime checks, without a compatibility full read. Existing selected row contributions remain selected while supplemental parent facts stay unrendered. The overlap probe reparents again after opening; the large-retirement probe replays a second retired identity after opening and verifies both fences with zero full reads. The reset-demand fixture now injects a real `thread_reset` terminal through the production pump, rather than invoking the cache seam's local reset helper.

The launch budget probe covers both coincident launch/content placements (100 facts produce the newest 200 rows) and separate placements (200 facts with their unselected launches withheld). Query-plan inspection showed that `key != ''` can walk the owner's entire index when a category is empty. Both selection queries now use the equivalent positive `key > ''` range; the migration probe requires a key range in both SQL plans, preserving bounded index traversal as well as bounded returned payloads.

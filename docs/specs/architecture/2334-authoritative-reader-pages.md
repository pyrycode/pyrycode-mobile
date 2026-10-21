# Bounded authoritative reader pages (#2334)

## Files read

- `data/cache/AuthoritativeRoomStorage.kt`: `newestDisplayWindow`, `parentClosure`, `beforePage`, counters and owned upserts; reuse validated renderer selection and closure.
- `data/cache/ConversationCacheDatabase.kt`: `AuthoritativeDao` placement indexes and identity lookups; add bounded before-key queries without a schema change.
- `data/cache/AuthoritativeUpsertCache.kt`: `AuthoritativeWindowCache` and `AuthoritativeThreadWindow`; distinguish local display continuation from daemon coverage.
- `data/cache/MigratingConversationCache.kt`: import-first reads and `authoritativeLifetime`; preserve retirement admission.
- `data/repository/CachingConversationRepository.kt`: negotiated route, reader custody, `applyAuthoritativeRestore`, committed baselines and final saves.
- `data/repository/AuthoritativeThreadStore.kt`: `restore`, contribution selection, revision/order merge and runtime generations.
- `data/repository/AuthoritativeThreadQueries.kt`: `older` and certified query dispatch; local demand must not occupy its network slot.
- `data/repository/NegotiatedThread.kt`: host-scoped reader entry and observation lifetime.
- `ui/conversations/thread/ThreadViewModel.kt`: `onDemandOlderHistory`, `onRetryOlderHistory` and negotiated presentation.
- `test/data/cache/AuthoritativeWindowRestoreTest.kt`, `sharedTest/di/SavedAuthoritativeDestination.kt`, `androidTest/ui/conversations/thread/SavedThreadFirstDrawDeviceTest.kt`: production destination, real database and native viewport evidence.
- `docs/knowledge/features/caching-conversation-repository.md`, `conversation-cache-contract.md`, `thread-screen-oldest-end-history-demand.md`, `thread-screen-testing-saved-first-draw.md`: partial hydration never replaces unloaded facts; input contribution selection and native timing remain distinct proofs.
- `docs/knowledge/decisions/0009-room-for-indexed-conversation-cache.md`, `docs/specs/architecture/2149-authoritative-thread-cache.md`, `2161-import-saved-threads-room.md`, `2333-bounded-authoritative-restoration.md`: storage, ownership, certification and opening prerequisites.
- Sibling `/work/Projects/pyrycode/docs/protocol-mobile.md`: existing thread queries remain the wire authority; no wire change.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read context and screenshot through Figma MCP. Preserve the title/back/overflow header, alternating inset bubbles, session rule, bottom status/composer and Material typography/colour roles. Reuse the existing loading/offline/retry presentation; this work changes which saved contributions are available.

## Context

Replace the temporary reader-triggered full authoritative restore with one bounded local page per demand. Opening remains the newest 200 display rows. Hidden facts consume no display budget; active inputs and required parent/placement witnesses remain extras. This has one production consumer and one independently useful behaviour, so storage plumbing and its proof stay together. No new decision record is needed.

Forecast: approximately 1850 written lines (550 production, 1100 deterministic/native tests and expectation updates, 200 plan/diagnostics), at most three new exported declarations, at most eight consumer updates, four criteria and nine reject branches. The nearest prerequisite grew beyond its original forecast after renderer and validation repairs. This grandchild (#2068 → #2252 → #2334) cannot split again; record `needs-human:sizing` and retain the verification floor.

Overlaps: #2266 changes legacy paging in the same cache, DAO and ViewModel files; #1947 changes session-settings selection; #2175's negotiated binding is already merged. Keep edits local and additive. None supplies a prerequisite for this design.

## Design

Extend the optional window capability with a before-window operation. Keep its display cursor separate from raw-fact rank tokens and daemon continuation. Capture exact host/conversation, authoritative epoch/incarnation, disk generation and boundary contribution identity (content or launch). Storage owns one content-free seek bound per live cursor in a weak-key registry. During each owned write's existing affected-component metadata traversal, classify old contribution positions against the pre-write reader bound and extend that bound to include their new placement. Publish the new bound/generation only after the same database transaction commits; failure preserves the old receipt. A cursor whose receipt does not match the committed header is unavailable/retry, including writes by a different storage owner. This avoids guessing from a fixed sample of predecessors, restarting at newest or adding a reader-side whole-thread scan.

Before-key index seeks select at most 200 new display contributions, resolving payloads and active/parent/witness extras with existing validation. Skip admitted content and launch contributions independently. Each successful page mints its next cursor at the selected oldest contribution. Obsolete cursor state is weakly owned and retains no payloads, metadata snapshot or accumulated identity list. Writer tracking reuses old/new placement metadata already visited; it adds no SQL or payload decoding. Exact scope/incarnation/generation custody keeps reset, retirement, cancellation and fresh reopen safe.

The repository owns local continuation and a separate local request slot within the active observation. Reader movement and explicit retry admit one page. A held local read drops repeated movement instead of queuing it. Completion updates the local boundary only after validated atomic application and never launches the next page. Unavailable reads keep the failed boundary and held content for explicit retry. Local exhaustion forwards that demand to the existing authoritative coordinator, which alone selects the certified continuous daemon boundary; local exhaustion cannot mutate server coverage or imply server exhaustion. Reconnect and newest/catch-up responses do not generate local demand.

Reuse the existing revision, retirement, held-order and parent-closure merge. Union selected contributions rather than clearing the opening selection: extras must not reveal unselected rows. Hydration extends only committed raw facts/fences in the successful baseline. It cannot advance checkpoint or coverage; owned writes preserve all omitted durable facts and their certificates. Supplemental resolution compares already validated committed facts so a later page does not reread all hydrated payloads as apparent live changes. Cached content never establishes negotiated support.

Expose content-free local loading/retry state through the negotiated route and map it to the existing history tail in the ViewModel. No new text, layout, theme token, dependency or daemon request type.

## State and concurrency model

The observation's child scope owns the page job; exit cancels it before final writer cleanup. Reads use cache IO admission and a coherent Room transaction; validation, merge and baseline maps use `processingDispatcher`. Lock order remains repository `historyWrites`, migrating cache mutex, storage mutex/Room transaction. Capture runtime generation and cache retirement lifetime before suspension; recheck exact epoch/incarnation and ownership before application, local cursor publication and saves. Existing `NonCancellable` commit/baseline and final-flush custody remains. Background socket close stays with the lifecycle driver; local disk demand does not await the network coordinator.

## State transitions and identity reuse

| Event | Required proof |
| --- | --- |
| Fresh 18000-row database, offline opening/reopening and exact complete local walk | `AuthoritativeReaderPagingTest.localWalkKeepsExactFactsAndCertificate` |
| Hidden facts, fragmented ranges, active extras, outside parents, separate content/launch contributions | `AuthoritativeReaderPagingTest.displayBudgetCountsContributionsAndClosesParents` |
| Held newest/catch-up, page completion and rest | `AuthoritativeReaderPagingTest.demandIsIndependentAndNeverWalksAtRest` |
| Failed local demand and explicit retry preserving content/boundary | `AuthoritativeReaderPagingTest.retryKeepsTheReaderBoundary` |
| Local exhaustion versus daemon older-exists/continuation | `AuthoritativeReaderPagingTest.localExhaustionPreservesDaemonCoverage` |
| Owned save invalidates token; loaded boundary removed; no restart or omitted older rows | `AuthoritativeReaderPagingTest.ownedWritesReselectAtTheReaderBoundary` |
| Boundary retirement followed by ancestor movement or regenerated preorder ranks, offline demand and reopen | `AuthoritativeReaderPagingTest.retiredBoundaryRecoversAfterAncestorPlacementRebuild` |
| Retired boundary is the only admitted child of a component, followed by standalone successors; ancestor moves later | `AuthoritativeReaderPagingTest.retiredBoundaryKeepsItsOlderComponentAcrossStandaloneSuccessors` |
| Live/page overlap, newest revision, raw clears, duplicate identities and held ties | `AuthoritativeReaderPagingTest.overlapKeepsWinningFactsAndOrder` plus retained writer probes |
| Held hydration across reconnect/reset with reused epoch, or exit | `AuthoritativeReaderPagingTest.staleAndCancelledReadsCannotApply` |
| Confirmed conversation/host deletion during held saves, cold reopen, colliding host ids | retained `AuthoritativeOwnedRemovalTest`, `AuthoritativeOwnedUpsertTest`, `AuthoritativeThreadCacheTest` and paging retirement probe |
| Real production destination, offline/held-newest upward demand/retry and cold reopen | new focused method in `SavedThreadFirstDrawDeviceTest` |

## Error handling

Classified null/IO/security failures retain held rows and cursor and expose the existing retry affordance. Cancellation propagates. Wrong owner, epoch, incarnation, retirement lifetime or runtime generation rejects stale work without forwarding a stale demand. Exhaustion is a successful local outcome distinct from failure and from daemon exhaustion. Diagnostics contain static outcomes and numeric query/result/decode counts only.

## Testing strategy

Write failing production-route deterministic probes over fresh on-disk Room instances before implementation. Reuse `SavedAuthoritativeDestination` and controlled transport/dispatchers. Compare exact full raw identity/revision/order, projected identities, coverage/checkpoint before and after every page and subsequent owned writes; retain numeric per-page work, no full reads and unchanged other-host state. Probe every invariant, including empty/one-row edges and overlapping/replayed revisions. Update prerequisite expectations only where full reader hydration is intentionally replaced; retain its opening, compatibility and first-draw assertions.

Add one focused native method through the actual negotiated destination and real database with the 18000-row fixture, upward demand, retry and cold reopen. Real Android database/restart, pointer/viewport and committed-frame evidence require `androidTest`. Reuse readiness and unchanged renderer-specific `firstDrawBoundMs`; do not change the 1000 ms target, full-file fixture, slow control or delayed projection proof. Run that method on this admitted Linux managed device and preserve counted XML, source revision and raw bounded-read evidence. Full configured native/JVM acceptance and the retained named opening/file methods remain dispatcher-owned and pending, not inferred from focused evidence. Route activation live acceptance remains #2245/#2246.

Run affected deterministic classes including #2255/#2256/#2149 writers, query/store and #2333 opening, lint, assembly, instrumented compilation and forced formatting. Push before the final merge-main assembly and `scripts/pre-verify.py --gradle`.

## Open Questions

None concerning product scope. Record any change to boundary recovery or supplemental validation under Revisions with the regression that requires it.

## Documentation handoff

- Manual maintenance updated `docs/knowledge/features/caching-conversation-repository.md` and `conversation-cache-contract.md` for local continuation, demand/retry, fragmented ranges and committed reader-bound custody, removing the temporary compatibility-hydration contract.
- `docs/knowledge/features/authoritative-reader-paging.md` holds the detailed contract and counted paging/reopen evidence. The cache contract and `thread-screen-testing-saved-first-draw.md` link to it to retain their 50000-byte caps; index/catalog entries expose it.
- Final documentation stage must record the current repaired candidate's complete configured acceptance, counted native paging/database-opening/file-compatibility methods, source identities and durable raw evidence. Earlier green gates do not accept the manual repair.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: selected disk payloads retain full-item/index validation; local cursors validate exact host/conversation/family/epoch/incarnation and cannot become daemon cursors or capability evidence. Parent/active facts confer no continuous range.
- [Tokens] Local positions are nonsecret redacted records; no credential generation, storage or exposure changes.
- [Files and storage] Keep the existing app-private no-backup plaintext Room/sidecar threat model. Reads use transactions and never rewrite invalid data. Owned upserts/retirement remain atomic; omission never deletes unloaded facts. No new paths or migration.
- [Android surface] No component, intent, provider, WebView, keyboard or screenshot policy change. Existing inert bounded text rendering remains.
- [Cryptography] Noise, Keystore and encrypted transport are untouched; positions are local indexes, never cryptographic material.
- [Network and IO] MUST FIX addressed in design: held newest/catch-up cannot own the local slot; one demand loads one page without an automatic network or disk walk. All network requests stay within the existing coordinator and frame limits.
- [Errors and logs] SHOULD FIX: record static lifecycle/error codes and numeric work only; never payloads, text, epochs, tokens or exception prose. Retry retains rows and position.
- [Concurrency] MUST FIX addressed in design: capture and recheck runtime generation, incarnation and cache retirement ownership; cancel observation-owned reads on exit; only a validated applied page advances its cursor. Changed-generation recovery requires a continuous storage-owned generation receipt: each committed owned placement mutation updates its live readers from the old/new component metadata already visited. Stage bounds under the storage mutex and publish only after commit; rollback cannot alter them. The weak registry retains one key per live cursor, scoped by exact owner/epoch/incarnation, and never payloads or an unbounded predecessor list. A missing receipt, external replacement or unknown intervening generation retains unavailable/retry, never local exhaustion or daemon forwarding. Fresh reopen mints a new cursor from current committed placement. Confirmed deletion and reused epoch text cannot revive content or a final save.
- [Threat model] Delayed/replayed hostile transport cannot authorize stale local work. Malformed saved daemon content still passes the existing decoder and text renderer. Rooted-device plaintext access and accessibility/screenshot exposure remain the established cache/UI threat model; this adds no new secret or exported surface.

**Reviewer:** manual maintainer (self-review; independent verifier still required)
**Date:** 2026-10-21

## Revisions

### 2026-10-21: manual committed placement-bound recovery

The tested tree committed as `e189309842e7d4eeea08fbea36f65c1efb1a14a9` passed all 12 paging methods, including 200/220/420-standalone variants and the complete fragmented 18000-row walk. Ordinary pages record exactly 200 payload decodes, 200 metadata results and six queries; each mixed-component child page records 201 payload decodes, 200 metadata results and seven queries. The 420-row variant failed against the bounded-predecessor implementation before this replacement. Durable XML and raw records are under the ticket's `manual-20261021/` evidence directory. Writer/native and independent complete acceptance remain required.

A bounded sample of older identities is insufficient: with 420 standalone rows, all 200 captured predecessors belong to unmoved components while the children move beyond the surviving reader boundary. The manual regression includes this case in addition to 200 and 220 standalones. Replace predecessor guessing with a storage-owned reachability bound for each live cursor. Keep one content-free key/generation record per cursor in a weak-key registry, under the existing storage mutex. During the writer's already-required affected-component metadata traversal, compare old contribution keys with each reader's pre-mutation bound and extend the bound to include their new placement. Stage these changes and publish them only after the owning database transaction commits. A rollback never changes reader state. Each bounded read advances to its newly selected oldest contribution; weak cursor ownership releases obsolete positions without retaining thread payloads or a full metadata snapshot.

Tie the registry receipt to the exact prior and resulting disk generation, owner, epoch and incarnation. A replacement, reset or write by a different storage instance breaks the receipt chain and makes the old cursor unavailable/retry; neither a stale key nor an unregistered cursor can authorize daemon work. Current production readers/writers share the same cache storage owner. Fresh reopen obtains a new cursor from current committed placement. No new schema, whole-thread reader scan, payload lookahead or automatic old-history walk is needed. The writer uses metadata it already visits; payload decode and page-size bounds remain unchanged. Native timing, retained ownership/writer regressions and independent complete acceptance remain required.

### 2026-10-21: initial manual frontier repair (superseded by committed-bound recovery)

Review on `2b4001820` showed that a valid boundary identity does not locate older components after they move. Resolve the full bounded predecessor frontier on every changed generation, independently of the boundary lookup. Use the largest valid current predecessor key as an inclusive seek bound, retaining any larger original/current boundary. Scanning every captured identity also covers nearer unmoved predecessor components ahead of the moved one. Existing admitted-contribution filtering prevents duplicate delivery; lookahead remains metadata-only and bounded to 200 identities, with no newest-window restart or automatic demand. Exact owner, epoch, incarnation and observation fences remain unchanged.

`survivingBoundaryRecoversMovedOlderComponents` runs the review's 600 children/200 standalone case and a 220-standalone variant whose first 20 older frontier entries do not move. Both demand the children offline, assert exact identity/text/order, certification and request independence, numeric per-page bounds, owned-write durability and complete fresh-reopen traversal. The existing retired-boundary, reranking, fragmented-range and native proofs remain required. This repairs the same paging behavior and retains its recorded sizing exception and full verification floor.


### 2026-10-21: mixed-component retirement recovery

Verifier finding 1 on `75868c31b` disproves successor-only recovery: the only admitted child may retire while its older component moves past every captured standalone successor. Replace the accumulated newer-successor frontier with at most 200 immediately older contribution identities. Capture it using existing indexed metadata seeks in the same page transaction, and resolve it by identity after a mutation. Include the recovered predecessor itself when selecting the next page, with content/launch deduplication unchanged. Keeping the prior upper bound also preserves peers when that component moves earlier. If the boundary and all captured predecessors disappear, return unavailable/retry; their absence cannot grant daemon work. No payload lookahead, newest restart, certification change, new schema or new UI behavior is involved. The two extra metadata seeks per window are measured separately from unchanged payload/decode bounds; native first-draw thresholds remain unchanged.

The production-route regression uses the verifier's 600 children plus 199 standalone rows, retires child 600, commits its hidden agent's move from order 10000 to 20000, then demands all 599 older children offline. It checks exact delivered identity/text/order, bounded numeric work, unchanged certification/requests at rest, durable retirement and a complete fresh-reopen walk. Existing same-component movement/reranking and writer probes remain required. Trust boundaries, private storage, cryptography, Android surface and logging remain as reviewed above; the cursor adds only scoped, redacted metadata identities.

Scope reassessment: this repairs the same reachability invariant, so no independently useful behavior splits off. Forecast approximately 1700 written lines after the regression and recovery repair, exceeding 1600; #2068 → #2252 → #2334 is already a grandchild. Retain the full verification floor and record `needs-human:sizing` while continuing.

The exact regression first failed against unchanged production with 1 executed/1 failed and no older children delivered. The first repaired class run had 11 executed/10 passed/1 failed: content delivery was repaired, but the new probe incorrectly expected pre-retirement coverage. `SavedAuthoritativeThread.toSnapshot` requires every explicit retirement fence to carry checkpoint zero and null coverage. The probe now asserts that existing retirement contract before and after hydration/reopen, so paging cannot resurrect the prior certificate. Both failed XML reports are retained.

### 2026-10-21: verifier retirement/reranking and fragmented-range repairs

Finding 1 invalidates the retained-key fallback below: component rebuilds change ancestor prefixes and preorder ranks even after the boundary identity is gone. Capture each window's ordered contribution identities in its cursor, followed by the previous window's successors. Active/parent extras never enter this recovery frontier. Resolve the surviving boundary's current contribution key; if it retired, seek the first surviving captured successor through metadata identity batches of at most 200. The current key extends the seek when placement moved later; the preceding upper bound still prevents a backwards jump across saved peers when it moved earlier. No payloads or newest-window restart are involved. If every captured contribution is gone, retain retry rather than claiming exhaustion from an obsolete key. Exact owner/epoch/incarnation admission, transactional generation capture and observation application fences remain mandatory; malformed/missing recovery cannot grant network work or certification. The production-route regression retires the oldest child, moves its ancestor in both directions and separately increases preorder ranks without moving its order, demands offline with a separate older active extra, walks the remaining facts and checks a fresh reopen.

Finding 2 changes the 18000-display-row walk to separated saved groups with genuinely absent identities/orders, hidden facts and revised raw fields. Every demand compares exact raw facts/order and certification, records and bounds numeric reads/decodes/queries, retains the daemon continuation and request count, and proves rest does not admit another page. Owned revision writes and fresh reopen must retain all saved groups and the second host. These are repairs to the existing paging invariant, not independent deliverables. Forecast remains below 1600 written lines with no new exported type or consumer signature migration.

The production-route retirement probe failed before implementation with 1 executed/1 failed: demand settled with 200 rather than 400 visible rows after the committed ancestor move. The failed XML is retained. Its older active extra also guards against recovering from the oldest global admitted row instead of the reader's captured window frontier.

The retirement probe checks applied repository selection before awaiting the exact projected rows: local loading settlement is not a ViewModel projection barrier. Keep the first repaired-class result (9 passed/1 failed) alongside the original red run; no content or bounded-read assertion is relaxed by waiting for delivery.

The subsequent 9/10 runs confirmed bounded recovery (202 payload decodes, 399 metadata results, nine queries), but exposed probe errors: `openingRows` can retain retired identities and both contribution flags from a live update; raw shown facts also include the unselected oldest child retained as a placement witness. Count held facts intersected with the selected content contributions before awaiting the 400 exact projected rows. Assert the witness separately as an unselected extra. Neither selection-set cardinality nor raw-fact cardinality is delivered content. Retain these failed XML results too.

### 2026-10-21: concrete display continuation and retry contract

The display continuation is separate from the unchanged raw-fact `AuthoritativeThreadPage.Continuation`. Before-key metadata seeks skip already admitted content/launch identities in bounded chunks; only selected payloads cross the codec boundary. Owned mutations resolve the boundary identity under the same epoch/incarnation, retaining the preceding key if the identity retired and avoiding a backwards jump when its placement moved. Runtime generation and cache retirement fences still reject reset/deletion.

The original regression first read state before disk delivery; after awaiting delivered rows it failed because compatibility hydration cleared `openingRows` instead of extending it. Retain both red XML results. Unavailable local reads now retain the demand for explicit retry instead of forwarding it to the daemon: otherwise a failed local read could download already saved content. Update that temporary prerequisite expectation while preserving opening/file compatibility checks. Later pages combine their committed facts with the existing successful baseline before supplemental validation; only changed live identities need new retirement evidence. This avoids rereading every hydrated payload as a new live fact.

`savedTieOrderSurvivesPageOverlap` failed on equal-order disk peers: the general restoration merge appends saved identities behind held facts. Local paging now inserts new older peers before their next committed held peer, preserving the relative order and winning revisions of already held/live facts. The committed baseline retains this same disk ordering; full opening and file restoration keep their existing merge. A reconnect may cancel the old observation's held page; it cannot replay that demand in its replacement. The probe demands again explicitly. Retiring an observation clears only its own in-flight loading state, never a newer reader's slot.

The reopen assertion then exposed synthetic placement moves from hydration: applying those moves as owned mutations reordered unloaded equal-order disk peers. Local restoration now preserves the prior runtime move map, because these facts and their placement are already committed. Only actual live moves remain writer intents. The exhaustion probe completes the held daemon query after proving local exhaustion preserves certification; only that daemon completion advances its expected durable checkpoint.

The measured branch after formatting and the ordering repair is 1169 inserted plus 56 deleted lines (1225 written), before this measurement note. Reuse of the production destination and existing closure/writer probes kept it below the initial 1850-line forecast and the 1600-line ceiling; the temporary sizing exception is no longer needed. There are two new internal declarations and no consumer migration above ten sites.

The retained `placementInvariant_ownedWitnessRemovalResolvesReplacementBeforeDelivery` then found that narrowing retirement lookups to changed live identities skipped replacement-witness validation when removal changed no surviving raw item. Observation validation now explicitly resolves its unordered placement roots even with no changed raw identities. Local page application still validates only its selected inputs and actual live overlap; it does not reread every hydrated parent on each demand. The existing removal/placement regression remains unchanged.

### 2026-10-21: native demand gesture stays inside the reading area

The first native paging run timed out before any local demand. Its default swipe started against the list's full drawing bounds, which extend beneath the header and composer. Reuse the measured clear-reading-area gesture contract from `ThreadScreenHistoryTest.pullInReadingArea`, keeping a real pointer drag and every demand/retry/viewport assertion. Retain the failed native XML and log numeric gesture and older-row bounds alongside bounded-read counts. No product behavior or first-draw threshold changes.

That repair passed both offline openings, then the held-newest arm exposed the same chrome-underlap constraint for its newly inserted Retry row: the native click did not reach its callback. Explicitly reveal the retry item and assert it is displayed before clicking; semantic positioning must keep the attempt count at one. Offline already had a notice in that slot, so its replacement stayed reachable. Retain this second failed run, including its two successful offline viewport/read records.

### 2026-10-21: allow the retained full-retention fixture to finish on Linux

`AuthoritativeOwnedUpsertTest.retentionInvariant_trimClearsCertification` hit the default one-minute coroutine deadline both in its affected-class run (16 other passes) and alone. This functional probe seeds 100000 rows and repeatedly reopens the complete database; it has no product latency assertion. Give only this probe a two-minute test deadline and content-free phase timings, preserving its fixture, every mutation/certification assertion and all native first-draw limits. The existing Room deep-storage probes likewise use explicit longer functional-test deadlines. Retain both failed XML results and require the complete writer class to pass.

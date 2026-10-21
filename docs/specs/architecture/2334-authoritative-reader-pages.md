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

Extend the optional window capability with a before-window operation. Keep its display cursor separate from raw-fact rank tokens and daemon continuation. Capture exact host/conversation, authoritative epoch/incarnation, disk generation, boundary contribution identity (content or launch) and placement key. Before-key index seeks select at most 200 new display contributions, resolving payloads and active/parent/witness extras with the existing validation. A saved mutation reselects at the captured reader identity rather than restarting at newest; a retired boundary retains its preceding placement boundary. Skip already admitted contributions independently for content and launch, so parent/active extras cannot hide their older visible contribution. Candidate processing stays bounded in chunks and never decodes unrelated payloads.

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

- Pending documentation stage: `docs/knowledge/features/caching-conversation-repository.md` and `conversation-cache-contract.md`, authoritative local continuation, demand/retry, fragmented ranges and stale-token handling versus durable certification; remove the prerequisite's temporary compatibility-hydration description.
- Pending documentation stage: `docs/knowledge/features/thread-screen-testing-saved-first-draw.md`, measured native database paging/reopen evidence alongside database opening and file compatibility.

## Security review

**Verdict:** PASS

- [Trust boundaries] MUST FIX addressed in design: selected disk payloads retain full-item/index validation; local cursors validate exact host/conversation/family/epoch/incarnation and cannot become daemon cursors or capability evidence. Parent/active facts confer no continuous range.
- [Tokens] Local positions are nonsecret redacted records; no credential generation, storage or exposure changes.
- [Files and storage] Keep the existing app-private no-backup plaintext Room/sidecar threat model. Reads use transactions and never rewrite invalid data. Owned upserts/retirement remain atomic; omission never deletes unloaded facts. No new paths or migration.
- [Android surface] No component, intent, provider, WebView, keyboard or screenshot policy change. Existing inert bounded text rendering remains.
- [Cryptography] Noise, Keystore and encrypted transport are untouched; positions are local indexes, never cryptographic material.
- [Network and IO] MUST FIX addressed in design: held newest/catch-up cannot own the local slot; one demand loads one page without an automatic network or disk walk. All network requests stay within the existing coordinator and frame limits.
- [Errors and logs] SHOULD FIX: record static lifecycle/error codes and numeric work only; never payloads, text, epochs, tokens or exception prose. Retry retains rows and position.
- [Concurrency] MUST FIX addressed in design: capture and recheck runtime generation, incarnation and cache retirement ownership; cancel observation-owned reads on exit; only a validated applied page advances its cursor. Confirmed deletion and reused epoch text cannot revive content or a final save.
- [Threat model] Delayed/replayed hostile transport cannot authorize stale local work. Malformed saved daemon content still passes the existing decoder and text renderer. Rooted-device plaintext access and accessibility/screenshot exposure remain the established cache/UI threat model; this adds no new secret or exported surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-21

## Revisions

### 2026-10-21: concrete display continuation and retry contract

The display continuation is separate from the unchanged raw-fact `AuthoritativeThreadPage.Continuation`. Before-key metadata seeks skip already admitted content/launch identities in bounded chunks; only selected payloads cross the codec boundary. Owned mutations resolve the boundary identity under the same epoch/incarnation, retaining the preceding key if the identity retired and avoiding a backwards jump when its placement moved. Runtime generation and cache retirement fences still reject reset/deletion.

The original regression first read state before disk delivery; after awaiting delivered rows it failed because compatibility hydration cleared `openingRows` instead of extending it. Retain both red XML results. Unavailable local reads now retain the demand for explicit retry instead of forwarding it to the daemon: otherwise a failed local read could download already saved content. Update that temporary prerequisite expectation while preserving opening/file compatibility checks. Later pages combine their committed facts with the existing successful baseline before supplemental validation; only changed live identities need new retirement evidence. This avoids rereading every hydrated payload as a new live fact.

`savedTieOrderSurvivesPageOverlap` failed on equal-order disk peers: the general restoration merge appends saved identities behind held facts. Local paging now inserts new older peers before their next committed held peer, preserving the relative order and winning revisions of already held/live facts. The committed baseline retains this same disk ordering; full opening and file restoration keep their existing merge. A reconnect may cancel the old observation's held page; it cannot replay that demand in its replacement. The probe demands again explicitly. Retiring an observation clears only its own in-flight loading state, never a newer reader's slot.

The reopen assertion then exposed synthetic placement moves from hydration: applying those moves as owned mutations reordered unloaded equal-order disk peers. Local restoration now preserves the prior runtime move map, because these facts and their placement are already committed. Only actual live moves remain writer intents. The exhaustion probe completes the held daemon query after proving local exhaustion preserves certification; only that daemon completion advances its expected durable checkpoint.

The measured branch after formatting and the ordering repair is 1169 inserted plus 56 deleted lines (1225 written), before this measurement note. Reuse of the production destination and existing closure/writer probes kept it below the initial 1850-line forecast and the 1600-line ceiling; the temporary sizing exception is no longer needed. There are two new internal declarations and no consumer migration above ten sites.

The retained `placementInvariant_ownedWitnessRemovalResolvesReplacementBeforeDelivery` then found that narrowing retirement lookups to changed live identities skipped replacement-witness validation when removal changed no surviving raw item. Observation validation now explicitly resolves its unordered placement roots even with no changed raw identities. Local page application still validates only its selected inputs and actual live overlap; it does not reread every hydrated parent on each demand. The existing removal/placement regression remains unchanged.

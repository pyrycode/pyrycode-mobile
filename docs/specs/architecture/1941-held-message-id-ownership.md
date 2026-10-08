# Keep surviving held message ids during reconciliation (#1941)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `mergeRows`, `deltaRows`, `legacyDeltaMatches`, `withJoinedSegments`, `withUniqueMessageKeys` separate admission, logical overlap and renderer allocation.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `Message` and `AssistantSegment` carry portable row metadata.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt`: `CachedMessage`, `toRecord`, `toDomain` persist message metadata independently of wire DTOs.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt`: `mergeHistoryPage` supplies durable placement and first-evidence exceptions.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt`: snapshot and list-only restoration both use the shared allocator path.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryReconciliationTest.kt`: hostile collisions, legacy text matching and cache observation fixtures.
- `app/src/test/java/de/pyryco/mobile/data/repository/UnsignedHistoryTest.kt`: first-evidence placement and reconstruction collision probes.
- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`: delta identity is independent of segment boundaries; adjacency alone cannot dedupe reconnect text.
- `docs/knowledge/features/caching-conversation-repository.md`: keep merges indexed, preserve receiving-list ownership and test the real cache boundary.
- `docs/knowledge/features/data-model.md` and `conversation-cache-layout.md`: optional domain metadata must round-trip through the cache-local record.
- Sibling `pyrycode/docs/protocol-mobile.md`, Security model: authenticated daemon data remains untrusted; no protocol change.

## Context

Ordinary-first allocation can transfer a held segment's id to a late unmatched whole-turn row. Atomization also resets suffixes. The fix changes only ids of admitted rows, keeping reconciliation and placement intact. No decision record is needed. Remote feature branches have no overlap with the planned files.

## Design

Add trailing optional `Message.reconciliationId`, used only for an ordinary row whose emitted id differs from its original ordinary id. Persist it as an optional `CachedMessage` field with a null default. Never infer logical identity by parsing renderer aliases: real daemon ids can spell those aliases. Existing constructors and wire types are unchanged.

The reducer uses the original ordinary id for overlap, legacy-turn lookup, parent and attachment hints, and canonical atom collision admission. Segments continue matching by `(turnId, seq)`. Atomization reconstructs canonical ids for existing admission rules; it clears ordinary-only reconciliation metadata when legacy content becomes a segment.

After joining, `withUniqueMessageKeys` receives the original held rows. Index their emitted ids by logical row identity: original ordinary id, or segment `(turnId, firstSeq)`. Reserve every matching surviving held row's exact emitted id before allocating newcomers. Preserve current newcomer priority (ordinary rows, opening segments, other segments); an unmatched late legacy row uses the explicit `#0` alternative and then an unused `~n` suffix. Keep an incoming row's already usable emitted alias where possible. Allocation cannot drop any admitted row. Do not change hostile-collision rejection, text matching, joining, multiplicity, row order or first-durable-evidence placement.

Identity continuity applies to separate surviving rows. Existing replacement, splitting, joining and absorption may change identity. Lookup and allocation remain indexed rather than scanning all held rows per newcomer. No new exported type or migrated caller is required; the allocator has one caller in `mergeRows`.

## State and concurrency model

All metadata travels with immutable rows. Each merge builds local indexes; no global alias registry, jobs, flows, dispatchers or connection-lifetime state is added. Existing projection atomic updates and cache writer ownership remain unchanged. Disk restore retains ordinary replay identity across process death.

## Error handling

The allocator consumes already admitted rows, not raw frames. Existing domain errors, collision rejection and content-free lifecycle/error logging remain authoritative. Alternative ids are checked against all surviving held claims and allocated newcomer claims. No ids, message content, metadata or cache bytes are logged.

## Testing strategy

Add `HistoryMessageIdentityTest` under the repository unit-test package. First run the late-legacy regression red under the existing allocator, then implement. Assert complete rows after every transition, including id uniqueness, order, text, streaming state and logical multiplicity.

Probe both history and cache entry points and both arrival orders, replaying original unaliased pages and empty pages. Include occupied `#0` and `~n` alternatives, preexisting segment suffixes, reconstruction with an unrelated page, and admitted split-key collisions. Cover prefix/middle/suffix overlaps, empty and one-row input and reconnect between steps. Exercise a real file-cache round-trip and a re-created projection before further history. Existing hostile and legacy reconciliation tests and unsigned placement probes must remain green. No Compose/device/live scenario is required for this data-only change.

Run focused `HistoryMessageIdentityTest`, `HistoryReconciliationTest`, `UnsignedHistoryTest`, reducer, cache and parent-attribution suites; then lint, assembly, formatting and the final full unit/shared suite plus `scripts/pre-verify.py --gradle` after merging main.

## Open Questions

None. Forecast: approximately 450 written lines including plan, production and probes; zero new exported types, zero required constructor migrations, two acceptance criteria, no new reject branches. The extra optional model/cache fields are necessary to avoid ambiguous alias parsing.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] Preserve canonical admission in `mergeRows`. Daemon ids may deliberately spell aliases; explicit `reconciliationId` avoids parsing them into another row's identity. Probe hostile keys and occupied alternatives.
- [Trust boundaries, rework] Ordinary logical ids must also drive wire updates, queue ownership and parent/lifecycle joins. A shared internal `Message.ordinaryId` accessor excludes segments, so a renderer key cannot impersonate an ordinary row. `withMessage` prefers an ordinary logical match and preserves its emitted id/metadata. Existing canonical hostile-collision rejection remains unchanged. Read evidence compares logical identity and all represented content/state independently of emitted aliases; a different content version receives no claim.
- [Trust boundaries, reconnect/delivery rework] Renderer claims are supplied separately from admission and placement. Only identities still present after reconciliation reserve ids; prior displayed/cache claims precede live-receiver claims. Provisional own-echo metadata enters the admitted input before allocation, so post-allocation replacement cannot create duplicate keys. Empty and overlap-only merges apply the same claims. No alias parsing or relaxation of hostile canonical collision rejection is introduced.
- [Trust boundaries, delivery-clock rework] Capture original delivery clocks by logical identity only for substituted provisional rows. These clocks affect incoming placement and its run minimum, never held receiver clocks, content, admission, renderer allocation or durable identity. Unsubstituted rows and first-evidence assistant relocation retain their existing clocks. No timestamp becomes authority or enters a log, path or wire field.
- [Tokens] No token, credential or key enters these message-id indexes or the added cache field.
- [Files and storage] `CachedMessage` adds inert optional identity metadata inside the existing app-private atomic thread document. Ids never become paths. Existing message storage/backup policy is unchanged; this ticket introduces no secret storage.
- [Android attack surface] No component, intent, provider, permission or rendering surface changes.
- [Cryptography] No Noise, randomness, key, nonce or comparison changes.
- [Network and I/O] No new verb or changed DTO, frame bound, URL or connection policy. The cache addition is local and backward-readable via its null default.
- [Errors, logs and telemetry] No new logging of ids or text; existing static merge lifecycle logging remains. Alternative allocation is local, with no exception exposed to UI state.
- [Concurrency] All claims are local to one pure merge; immutable metadata survives atomic projection updates and existing cache serialization.
- [Threat model] Hostile daemon ids are handled by unchanged rejection guards plus unique allocation. Relay delay/reorder is covered by replay/arrival-order probes; Noise authentication and transport limits remain authoritative. Rooted-device token theft and UI screenshot/accessibility exposure are unaffected because this change handles neither credentials nor rendering.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-08

## Revisions

### 2026-10-08 — verifier findings 1 and 2

The initial design confined ordinary logical identity to the merge/cache boundary. The verifier identified read-checkpoint binding and wire-correlation consumers that still treated emitted ids as daemon ids. Extend the design with an internal nullable `Message.ordinaryId` accessor: ordinary rows return their explicit original id or emitted id; segments return null and keep matching by their segment metadata. This does not add a type, migrate a signature or change admission/ordering rules.

Use the accessor for tool progress/result/denial and repeat-use lookup, ordinary message updates, legacy turn settlement, own-echo placement/removal/suppression and cache suppression. `ThreadProjection` uses ordinary identities for queue and wire correlation. `toolNestingDepths` walks logical parent ids but emits depths under renderer keys. `foldQueuedRows` joins logical ids and keeps renderer ids as queued list keys. `foldBackgroundAgentBlocks` joins logical tool/lifecycle ids while its marker, block and navigation identities remain renderer ids. `forBackgroundAgentRows` binds lifecycle read evidence by logical tool id. `ThreadFold` retains renderer collision guards and also recognizes an ordinary logical turn id. Read evidence normalizes renderer-only metadata while retaining content, role, attachments, parent and tool state checks.

Add `HistoryAliasCorrelationTest` to exercise the production projection/reducer and pure thread folds: aliased legacy checkpoint plus original-page replay and changed-content negative control; tool progress, result, denial and repeat use; user push/upsert and queued renderer key; nested aliased tools; background lifecycle placement, root/run keys and presentation evidence; legacy turn-end settlement. Existing collision, cache, ordering and read-version suites remain required. No UI layout or wire contract changes are made. `origin/feature/1954` overlaps additively in `ThreadRow`, adding a separate content-type function; its changed block is independent of these correlation helpers.

Measured after rework formatting: under 1,250 total written lines including the original implementation, plan and tests; zero new exported types or signature migrations, two acceptance criteria and no new reject branches. The eleven new correlation probes also cover alias-only restoration, queue pass-over/suppression/delivery, real-file cache suppression at observation and history-write boundaries, and synthetic-turn suppression. Security re-review passes with the additional trust-boundary contract above; credentials, storage, components, crypto, network, logging and concurrency remain as reviewed.

### 2026-10-08 — second verifier findings 1 and 2

Merge receiver ownership is insufficient when a cache wrapper has already displayed rows that a fresh, unseeded projection does not contain. Add optional renderer-owner rows to the unsigned history/cache merge entry points, separate from reconciliation inputs. The allocator reserves surviving explicit owners first, then surviving receiver rows, then newcomers. Apply these claims on empty and overlap-only input as well. `observeThreadSnapshot` supplies its prior drawn rows; `writeHistoryPosition` supplies its selected drawn/file base. Keep the fixed cache merge base, suppression, live admission, content precedence and placement unchanged; owner rows never contribute content or resurrect removed rows.

For provisional first history delivery, replace the incoming ordinary delivery with its held object before merging, and supply the original held list as renderer owners. Continue removing the provisional row from the receiving placement list so the first-delivery exception still moves it. Remove post-allocation substitution, which could restore an id already given to an admitted newcomer.

Add real-file cache observer probes for both arrival orders, unseeded reconnects, original-page replay, empty pages/emissions, persisted rows and coverage saves; independently probe the coverage writer's file fallback without an observer. Add a history-delivery probe with an occupied provisional alias, complete row/state/metadata assertions, unique list keys and repeated original/empty pages. Security re-review passes: claims are local indexed identity lookups, and all existing storage, wire, credential, logging and concurrency boundaries remain unchanged. No in-flight branch overlaps the three production files. Measured after formatting: 1,275 written lines across the complete branch diff (insertions plus deletions), no new types, three updated production call sites, two acceptance criteria and no new admission/reject branches.

### 2026-10-08 — third verifier finding 1

Replacing provisional own-echo objects before allocation also replaced their incoming delivery clocks with send clocks. The differing-clock regression reproduced `[assistant, user, tool]` instead of `[assistant, tool, user]`; a two-echo probe additionally moved the whole incoming run and its fresh neighbour before held content.

Keep the pre-allocation metadata substitution and renderer claims. Capture each substituted row's original timestamp under `mergeIdentity` and pass this optional placement-only map through `mergeUnsignedHistoryRows` to `mergeRows`. Incoming per-row insertion and the incoming run's minimum use these clocks; base clocks still come from held rows. This restores original placement without changing admission, full retained metadata, allocation or the assistant first-evidence exception. Replay and empty pages retain established slots.

Add two `HistoryAliasCorrelationTest` invariant probes: a single aliased delivery with distinct send/delivery clocks and no shared or durable held neighbours; two provisional deliveries with a fresh newcomer before, between and after them. Assert full rows, ids, logical multiplicity, unique list keys, suppression settlement and original-page/empty replay after every merge. Both probes failed before repair. Security re-review passes with the placement-only contract above; storage, credentials, crypto, network, logging and concurrency remain unchanged. No in-flight branch overlaps the touched files. Measured after formatting: 1,415 written lines across the complete branch diff (insertions plus deletions). The optional internal parameter has one updated production caller and no required test/call-site migration, with no new exported type, acceptance criterion or reject branch.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/remote-conversation-repository-assistant-reply-segments.md`, segment keys/seam reconciliation: surviving held id ownership, alias replay and reconnect behavior, and the exception for removed/replaced/split/absorbed rows.
- `docs/knowledge/features/data-model.md`, `Message`: distinguish emitted renderer ids from explicit ordinary reconciliation ids and their wire/read/grouping consumers.
- `docs/knowledge/features/conversation-cache-layout.md`, cache-local message record: optional backward-readable reconciliation metadata and process-death replay continuity.
- `docs/knowledge/features/caching-conversation-repository.md`, restore merges: receiving-list ownership and logical user-echo suppression.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store-history-paging.md`, History establishes modern queued delivery: original delivery placement clocks remain separate from held own-echo metadata and renderer claims, including multiple provisional rows in one incoming run.

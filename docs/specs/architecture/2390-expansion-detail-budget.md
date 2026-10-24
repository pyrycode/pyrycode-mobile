# Per-host retained expansion budget

## Files read

- `data/repository/AuthoritativeThreadStore.kt`: `enrichBody`, `enrichChildren`, `publishSnapshot` and `expansionItems` own fetched details independently of full facts.
- `data/repository/AuthoritativeThreadQueries.kt`: `bodies`, `children`, `pruneBodies` and `retire` own demand and the serialized request lane.
- `data/repository/NegotiatedThread.kt`: existing `BodyLoad.Retry` is the inline failure contract.
- `ui/conversations/thread/ThreadScreen.kt`: expanded body demand must outlive body loading; `ExpansionCapacityPressure.kt` supplies bounded replies for mounted protection and reopen regressions.
- `ToolBodyQueriesTest`, `AgentChildrenQueriesTest`, `MissingToolBodyScreenTest` and `AgentChildrenScreenTest`: reuse production-query and mounted-consumer fixtures.
- Rework: `QueueRecoveryAgentEvidence.settledAgentAfterRecovery`, `RecoveredAgentFamilyTest` and `DeterministicInteractiveStreamE2ETest.backgroundAgentProof` own the failing scripted settlement boundary and its controlled proof.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md` and `conversation-cache.md`: stale presentations may withdraw demand; fetched details are transient and never certify saved history.
- Parent #2376 archived scope and allocation: subtree invalidation belongs to #2391; activation to #2431–#2433; genuine capture to #2393.
- `../pyrycode/docs/protocol-mobile.md`, Bounded expansion reads and Security model: wire authority, separate completion and existing decoder bounds.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read context and screenshot through Figma MCP. Keep the current nested expanded Tool use/body surface and inline Retry, using existing bodySmall/label typography and primary/on-surface theme tokens. No geometry, asset or pagination-control change is required.

## Context

Fetched body and accumulated child-page maps currently retain without a host bound. One useful behavior is a shared inclusive 16,777,216-byte/1000-summary budget, with protected demand and recoverable admission failure. No persistence change or decision record is needed. Branch #2493 overlaps the store with an additive generation accessor; it is not a dependency.

## Design

Add a small internal retention ledger beside the store. Keys include host, conversation, epoch, generation/incarnation, item/revision and body versus children. Costs measure UTF-8 serialized content objects, each raw child-summary object and the current cursor object, including unknown fields. A page's accumulated enumeration is one atomic entry. Cache measured costs; successful admission and newly protected reopen update deterministic recency; repeated demand/publication does not.

Store admission validates existing ownership first, computes the candidate cost, then selects unprotected LRU entries across both kinds and all host conversations. Commit evictions only if the entire candidate fits both inclusive limits. Remove evicted availability, cursor and transient load claims; retain source facts and all history/read certification. Replacement subtracts the old entry once. Reset/publication reconciles ledger membership with retained maps, including currently unreachable maps.

Queries keep available bodies in consumer demand while skipping their requests. Synchronize the union of current consumer targets into the store; protect retained ancestor enumerations needed to reach an open descendant. Multiple consumers share accounting and protection. Release/abandon withdraw protection through existing paths. A capacity rejection returns failed admission through existing sticky Retry plumbing; explicit Retry uses the owned lane and can succeed after other demand closes. Keep failed page extensions' previous rows/cursor unchanged.

## State and concurrency model

No new jobs or flows. The query monitor calls the store monitor; the store never calls queries. Budget admission, eviction and demand changes run under the store monitor without suspension. Existing query scope, expiry, abandon and abandoned-response draining remain responsible for cancellation. Details remain in-memory only across process death; foreground/background connection retirement releases protection.

## State transitions and identity reuse

| Event | Focused proof |
| --- | --- |
| Exact limit / one byte or summary over | `ExpansionDetailBudgetTest.byteBoundary`, `summaryBoundary` |
| Combined kinds, host conversations and closed pressure | `combinedBudgetAndLru` |
| Recomposition, reopen, multiple consumers | `reuseAndConsumerProtection` |
| Open descendant after ancestor closes | `ancestorProtection` |
| Failed page extension, collapse, explicit capacity retry | `pageCapacityRetry` |
| Colliding hosts, reset/reused IDs, retired connection | `hostAndResetIsolation` plus existing ownership query probes |
| Mounted body/agent capacity Retry and recovery | additions to existing missing-body/agent-child screen fixtures |

## Error handling

Capacity is failed detail admission, never empty success or history repair. Reuse `BodyLoad.Retry`; no new UI state or untrusted error text. Emit only static loaded/evicted/capacity event codes. Do not log content, raw IDs, cursor, payload or credentials.

## Testing strategy

Write a failing production-lane regression before implementation. Build totals from bounded replies through the real decoder, with measured JSON UTF-8 sizes, multibyte/escaped and unknown fields. Add invariant probes for byte/count boundaries, cross-kind LRU, shared consumers, ancestry, host isolation, failed extension and retry. Run the affected query/store and mounted Compose classes on Linux; shared screen tests run under Robolectric and compile for Android. Run the existing scripted tool scenario; full configured acceptance remains dispatcher-owned. Record actual source/APK identities and counted XML results. No new device-only test or genuine recording is assigned here.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update expansion/retention sections in `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/remote-conversation-repository.md` and `docs/knowledge/features/conversation-cache.md` with accounting, inclusive bounds, protection, LRU and retry/reopen behavior. Add this child's counted focused evidence and source identities to #2376's handoff in `docs/e2e-interactive-stream.md`; integrated and genuine-pair acceptance remain with their assigned tickets.

## Security review

**Verdict:** PASS

- Trust boundaries: `ThreadQueryDecoder` still validates untrusted replies before store admission. Measure raw retained objects, including unknown fields, rather than rendered text. No new rendering interpreter.
- Tokens: no credential creation, storage or change; never include payloads/identifiers in budget diagnostics.
- Files/storage: ledger and fetched detail maps are transient. Existing full-fact app-private no-backup cache and atomic writes remain unchanged; no new path or backup surface.
- Android attack surface: no component, intent, provider, permission or WebView change.
- Cryptography: unchanged authenticated Noise transport and Keystore ownership; no new crypto.
- Network/I/O: retain frame/assembly/time limits. Generate large test totals from individually bounded valid replies, not widened decoders.
- Errors/logs: static capacity/eviction codes only; existing generic inline Retry cannot expose daemon error text.
- Concurrency: query then store lock order, no callback in reverse; atomic admission preserves open data on rejection. Keys include generation/incarnation; releases cannot admit late replies.
- Threat model: relay delay/reorder remains fenced by authenticated owned query completion; hostile daemon retention is bounded across open/closed entries. Rooted-device token theft and UI token-entry leakage are unchanged existing boundaries, with no new credential surface.
- SHOULD FIX implemented by design: protect loaded bodies and ancestor pages, and reconcile stale ledger entries on reset so bookkeeping cannot retain retired capacity or evict a live descendant's ancestry.
- Rework security review: protection must follow the mounted consumer after availability changes, and ancestor-page membership follows child identity even when a newer full revision wins. Exact demand revision, host, epoch, generation/incarnation and retained parent revision checks still fence protection; this does not authorize a reply or change admission, decoding, storage, logs or certification.
- Recovery-boundary review: require explicit current `ThreadSupport.Supported` ownership for authoritative settlement evidence. A connection replacement can reconcile only the captured family under the same host/conversation/epoch, nondecreasing generation and certified current coverage. Same-generation document replacement, retired/foreign evidence and unverified/missing support fail. Ordinary owner matching, production admission, storage, crypto and logs remain unchanged; this test-only boundary creates no jobs or I/O.
- OUT OF SCOPE: subtree freshness repair #2391; compact activation #2431–#2433; genuine paired capture #2393. Existing holds and historical failures remain.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-23

## Scope measurement

Forecast: about 1250 written lines (350 production, 800 regression/helpers, 100 plan); three internal declarations, two existing admission consumers, four acceptance criteria, one additional capacity rejection category. One independently useful budget behavior, below all numeric ceilings.

## Revisions

2026-10-23: Exact byte review found that `decodeThreadSummary` decorates a summary with DTO-only `summary` and null `content` fields. Preserve the original validated summary object in `ThreadItemDto` through a dedicated internal factory, and account that object rather than the decorated full-item shape. This adds small local edits to `ThreadQueryPayloads.kt` and `ThreadUpdatePayloads.kt`, without changing wire validation, limits, full-fact decoding or existing factory signatures. The original object also preserves unknown fields for exact accounting. The byte-boundary probe measures the actual transmitted summary object plus cursor.

Final probe names live in `ExpansionDetailBudgetTest`: `byteBoundary`, `oneByteOverIsRejectedAndCapacityRetryIsExplicit`, `summaryBoundaryAndPageCapacityRetry`, `combinedByteBoundaryIncludesRawSummariesAndUnknownCursorFields`, `combinedBudgetAndLru`, `reuseAndConsumerProtection`, `ancestorProtection`, `hostAndResetIsolation` and `recompositionDoesNotRefreshLru`. Mounted capacity methods are added to the existing body/agent screen classes. Fixture roots and captures use revisions above every generated ID, preserving `valid` and decoder constraints.

2026-10-23, verifier rework: `ThreadScreen` must retain visible expanded body targets after loading, allowing `AuthoritativeThreadQueries.bodies` to skip available requests while preserving protection and recording retained reopen use. Add mounted `loadedExpandedBodyStaysProtectedUnderHostPressureAndRetriesAfterCollapse` and `retainedBodyReopenRefreshesLruThroughMountedConsumer` probes: load through the production consumer before same-host pressure, preserve open contents with sticky incoming Retry, then release demand and recover; a retained reopen must change the closed-entry LRU victim without refetch. The existing bounded-reply pressure fixture accepts a measured total so mounted contents plus pressure reach the exact host limit.

The same review found that `protectedDetails` required the child's summary revision to match the newer full winner, although `projectThread` uses page membership by ID to place order-zero children. Protect that membership under the current exact parent revision and existing owner fences. Extend `ancestorProtection` to cover matching and newer full child revisions, release direct parent demand, then assert page identity and projected child visibility under pressure. These repairs restore the planned invariant without adding a deliverable, state or failure mode. The repair remains below 1600 written lines for the whole branch. In-flight #2266 overlaps `ThreadScreen` in history pulling and #2493 adds a store generation accessor; neither changes these blocks or supplies a dependency.

2026-10-23, scripted settlement rework: retained verifier run `182fe94393d39f610e2a1f8bdf188c3867afb41cbb3119582d55f931e6c21ce2` failed one of 24 scripted cases. Its harness records producer-gap teardown and a replacement connection; `settledAgentAfterRecovery` rejects any replacement before examining the otherwise settled family. Add an explicit current support argument, require its connection to own the reading, then reconcile the captured durable family with the existing identity, attribution, revision, content, order and certified coverage checks. Validate captured membership and snapshot ownership; reject document replacement without a generation advance. Keep ordinary `ThreadEvidenceOwner.matches` strict. The mounted consumer reads support at each settlement check and again after selection; graph/cache restart and settlement-reconnect assertions remain intact.

Controlled proof: `RecoveredAgentFamilyTest` adds replacement-connection success, strict ordinary-owner rejection, unverified/missing support, retired/foreign owners, document replacement and captured-family negatives alongside the existing certification/content matrix. Run that affected JVM class and the existing `scripted background-agent` route (both independent cases, with real device storage/transport required), retaining counted reports and source/binary identities. Run the routine scripted tool scenario and final compile/format/lint gates. Request the bounded merged-main comparison through dispatcher investigation because this role operates in one feature worktree; full configured acceptance remains dispatcher-owned and pending. Preserve the original failure and the two zero-execution selector attempts.

Scope remains one recovery-invariant repair with its regression: approximately 180 additional written lines across three test files and this plan, no exported production declaration or new rejection category, four existing caller functions. Total branch remains below 1600 lines. In-flight #1766, #2091, #2104, #2175 and #2462 overlap the scripted class in other scenarios/setup; edits here stay local to `backgroundAgentProof` and do not depend on them.

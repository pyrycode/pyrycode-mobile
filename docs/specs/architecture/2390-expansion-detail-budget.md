# Per-host retained expansion budget

## Files read

- `data/repository/AuthoritativeThreadStore.kt`: `enrichBody`, `enrichChildren`, `publishSnapshot` and `expansionItems` own fetched details independently of full facts.
- `data/repository/AuthoritativeThreadQueries.kt`: `bodies`, `children`, `pruneBodies` and `retire` own demand and the serialized request lane.
- `data/repository/NegotiatedThread.kt`: existing `BodyLoad.Retry` is the inline failure contract.
- `ToolBodyQueriesTest`, `AgentChildrenQueriesTest`, `MissingToolBodyScreenTest` and `AgentChildrenScreenTest`: reuse production-query and mounted-consumer fixtures.
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
- OUT OF SCOPE: subtree freshness repair #2391; compact activation #2431–#2433; genuine paired capture #2393. Existing holds and historical failures remain.

**Reviewer:** builder self-review per `builder/security-review.md`  
**Date:** 2026-10-23

## Scope measurement

Forecast: about 1250 written lines (350 production, 800 regression/helpers, 100 plan); three internal declarations, two existing admission consumers, four acceptance criteria, one additional capacity rejection category. One independently useful budget behavior, below all numeric ceilings.

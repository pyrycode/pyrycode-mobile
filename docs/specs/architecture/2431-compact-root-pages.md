# Compact initial and older root pages

## Files read

- `data/network/ThreadQueryPayloads.kt`: `ThreadQueryRequest`, `decodeThreadQueryReply`, `decodeThreadSummary`; extend the existing bounded wire boundary compatibly.
- `data/network/ThreadQueryDecoder.kt`: `decode`, `assemblyMetadata`, `metadata`; preserve correlation, assembly and separate terminal ownership.
- `data/repository/AuthoritativeThreadQueries.kt`: `dispatch`, `accept`, `retire`; reuse the single serialized request lane and abandoned-request draining.
- `data/repository/AuthoritativeThreadStore.kt`: `applyBatch`, `merge`, `certifyQuery`, `AuthoritativeThreadSnapshot`; retain complete facts independently of compact metadata.
- `data/repository/ExpansionDetailValidity.kt`: `observeDetails`; source revision and subtree freshness are independent observations.
- `data/repository/RemoteConversationRepository.kt`: authenticated support and query wiring; supply opt-in test selection without changing production advertisement.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectThread`; consume current root metadata and retain deferred contents/read barriers.
- `data/cache/SavedAuthoritativeThread.kt`: `toSavedAuthoritativeThread`; only existing complete facts and certificates cross the cache seam.
- `ToolBodyQueriesTest`, `DeferredAuthoritativeScreenTest`, `deferredLargeSnapshot`: reuse production demand and generated 8333-item proof.
- `docs/knowledge/features/remote-conversation-repository.md`, `remote-conversation-repository-reads-and-thread-store.md`, `conversation-cache.md`, `authoritative-reader-paging.md`: receipt is not sight, omitted detail is not empty success, and complete coverage cannot certify a compact join.
- Wire authority: supplied Core `docs/protocol-mobile.md`, **Compact projection**, **Bounded expansion reads**, and **Security model**.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read structured context and its screenshot with the Figma skill. Preserve the dark thread, existing compact tool headlines/chevrons and expanded inline content, Material typography and scheme tokens. No component, asset or geometry changes are needed.

## Context

Root query replies currently decode only full items and certify complete coverage. This slice admits mixed compact initial/older replies while complete-host delivery remains usable. #2432 owns nonzero catch-up/live; #2433 owns advertisement, production activation and reconnect selection; #2393 owns genuine paired capture/replay. No decision record is needed.

Overlap scan found historical/shared remote edits on #1947, #2175, #2325, #2462 and #2493; #2493 adds generation capture and metadata recovery, not compact ingestion. Keep remote wiring additive and local; no design dependency.

## Design

Add defaulted projection selection to root requests only; compact pages additionally carry their certified epoch. Existing callers remain complete. Decode full non-tool roots and bounded tool/agent summaries through existing validators; reject headers on complete replies, nonroot compact rows and wrong projection on every physical or assembled reply. Only separate empty terminal markers carry coverage.

Store compact headers separately from `items`, which retains full facts for persistence. Expose one newest-revision identity through `expansionItems`, with compact tuple ordering and immutable kind/parent validation. At equal revisions current compact metadata owns freshness, while exact retained full bodies remain available only if detail validity permits. Feed source observations through `observeDetails` in both directions, including same-revision freshness changes. Preserve summary unknown/zero/false/empty fields verbatim.

Keep compact root coverage/checkpoint separate from complete coverage/checkpoint. Compact markers certify only their window; older pages require matching compact epoch/continuation, merge contiguous windows and never advance complete certificates or detail completion. Root receipt and assembly completion do not certify any window. Save only the already retained full facts and complete certificates, with no schema change.

The repository supplies an internal default-off compact-root switch gated by current authenticated thread/interactive/expansion/compact support. The serialized lane selects version-zero compact initialization and explicit older pages. Repeated synchronization in this slice uses version zero rather than implementing #2432 nonzero catch-up. No automatic older walk or closed-detail demand is added.

## State and concurrency model

No new coroutine, dispatcher or background downloader. Reuse synchronized store mutations, connection-owned query lane, current inbound collector and expiry child. Cancellation/background socket closure retires the lane and drains abandoned work through existing fences. Compact metadata and certificates live in the host/conversation runtime snapshot and reset with its generation/incarnation.

## State transitions and identity reuse

| Event | Required focused proof |
| --- | --- |
| Initial mixed/reordered/duplicate roots | Repository ordering probe, one newest row per identity, zero closed detail traffic/conversion |
| Repeated revisions, newer full facts and same-revision freshness changes | Retention probe preserves full source/cache facts while invalidating obsolete detail independently |
| Explicit older demand, tied boundary, terminal empty page | Paging probe checks exclusive continuation, merged root coverage and bounded request count |
| Wrong conversation/request/projection/epoch | Decoder/repository attribution probes leave certificates unchanged |
| Interrupted oversized non-tool assembly, failure/cancel/disconnect | Fragment and retirement probes leave root/detail certificates unchanged |
| Reset or replacement owner with colliding IDs | Existing generation/connection fences plus focused compact owner probe |
| Recollection/closed rows and explicit expansion | Mounted generated fixture has no unopened detail transfer/decode/conversion; existing body/child and sight tests retain expansion coverage |

## Error handling

Reuse static malformed/attribution/coverage/capacity/sequence/application/timeout/cancelled/disconnected classification and retry/drain behavior. Invalid data certifies nothing. No raw daemon text, identities, cursors or payloads enter logs; existing inline retry/offline styling remains.

## Testing strategy

Write causal red repository tests before production changes, then add invariant probes and a mounted production-delivery method using `deferredLargeSnapshot`. Run affected decoder/repository/detail/presentation classes, the affected shared method on Android, and scripted `tool` on Linux. Device execution is required by the ticket; no new device-only source is planned. Retain original XML, logs, candidate/source/binary identity and executed/passed/failed/skipped counts outside the worktree. Run lint, assembly, instrumented compilation, formatting and final pre-verify after merging main. Dispatcher owns complete configured acceptance; #2393 owns paired evidence.

Forecast: about 1450 written lines (roughly 400 production, 950 tests/helpers and 100 plan), <=2 new exported types, <=10 changed consumers, four criteria and existing reject categories. Compatible defaults avoid a fixture migration.

## Open Questions

None. Scope excludes compact nonzero/live/reconnect activation, local-history storage work and new background detail fetching.

## Documentation handoff

Pending documentation stage, supplied to #2393: compact initial/older selection, tuple merge, full-fact retention and independent root/item/body/child/fragment completion for `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/remote-conversation-repository.md` and `docs/knowledge/features/conversation-cache.md`. Supply counted focused/native/scripted outcomes, candidate/source/binary revisions and raw-evidence locations for `docs/e2e-interactive-stream.md`. Documentation cannot replace missing evidence.

## Security review

**Verdict:** PASS

- Trust boundary: `decodeThreadQueryReply` and `ThreadQueryDecoder` enforce projection, root shape, bounded summary text and request/epoch attribution before store admission. MUST FIX addressed in design: a complete marker or assembled payload cannot certify compact scope.
- Tokens: no new credentials; exact unsigned query tokens retain the existing numeric decoder and are never logged.
- Files/storage: existing cache seam persists full app-private facts only; compact summaries/certificates and expansion buffers remain runtime state. No new path, schema, backup policy or write mechanism.
- Android surface: no component, intent, provider, WebView or external action changes; summary text uses existing inert row presentation.
- Cryptography: reuse authenticated Noise session and existing vendored primitives unchanged.
- Network/I/O: preserve envelope/assembly bounds, timeout and serialized admission; reject compact data before full conversion. Relay delay/reorder cannot manufacture a matching successful marker.
- Errors/logs: reuse static content-free codes; never log payloads, decrypted bodies, headlines, request scope or credentials.
- Concurrency: existing synchronized host/conversation generation/incarnation fences, collector cancellation and abandoned-request draining own all work. No additional jobs or locks.
- Certificate ownership: MUST FIX addressed in the final design. `certifyQuery`, `older` and `dispatch` bind compact root coverage to the current query connection; a replacement cannot reuse or union the old lane's certificate before successful initialization. Child/restore merges also validate immutable identities against retained compact roots.
- Threat model: malicious relay remains bounded by existing Noise/framing/timeout controls; hostile daemon summaries are validated and inert. Rooted-device theft and UI screenshot/accessibility leakage are unchanged existing platform concerns, outside this root-page slice.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23

## Revisions

2026-10-23, source inspection before implementation: existing read/attention consumers use the current snapshot checkpoint and coverage. Give that active certificate an explicit projection, retaining the complete checkpoint/coverage separately when compact selection starts; `toSavedAuthoritativeThread` uses only the retained complete certificate. This implements separate root/complete certification without migrating read consumers or extending persistence. `expansionItems` supplies tuple ordering to existing presentation; no UI source edit is needed. The authenticated lane's default-false compact supplier is wired from granted support, which production cannot grant until #2433 changes advertisement. No additional repository constructor flag is required. Revised forecast remains below 1500 written lines across six production files.

2026-10-23, causal repository probe: six executed, five passed, one failed because `observeDetails` ignored a higher source revision in a second batch at the same captured query version. Admit higher revisions at equal capture versions, while retaining replay suppression for equal/older revisions. Prefer newer held full facts over an older stored observation when comparing a later header, including cache restoration between batches. Keep these regressions with compact ingestion; this adds a small seventh production edit and stays within the admitted line boundary. Removal clears the retained complete certificate as well as the active one, preserving durable retirement fences.

2026-10-23, ownership review: compact root certificates also carry the query lane's connection identity. Replacement lanes cannot demand older pages from another connection's certificate or union its window before their own successful version-zero initialization. This is incoming-page ownership, leaving activation and reconnect resume selection to #2433. The replacement-connection probe covers the fence. Child-reader and restore identity checks include held compact roots, so omitted full facts cannot bypass immutable kind/parent checks.

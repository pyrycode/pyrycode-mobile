# Missing tool bodies on expansion

## Files read

- `data/network/ThreadQueryPayloads.kt`, `ThreadQueryRequest` and `ThreadQueryReply`: extend the bounded query contract.
- `data/network/ThreadQueryDecoder.kt`, `decode`: retain correlation, routing and capture validation.
- `data/network/ThreadPayloadAssembler.kt`, `accept`: reuse byte, part, digest and lifetime limits.
- `data/network/ThreadUpdatePayloads.kt`, `ThreadItemDto`: preserve raw inert fields.
- `data/repository/AuthoritativeThreadQueries.kt`, `dispatch`, `retire`, `isTerminal`: one connection-owned lane and abandoned-request draining.
- `data/repository/AuthoritativeThreadStore.kt`, `publish`, `current`: enrich an exact held revision without certifying history.
- `data/repository/NegotiatedThread.kt`, `acquire`: exact-connection consumer entry.
- `data/repository/RemoteConversationRepository.kt`, `authoritativeQueries`: authenticated support enforcement and sole inbound routing.
- `data/repository/RelayRepositoryCoordinator.kt`, `verifiedRepository`: fence the connection selected by the destination.
- `data/repository/CachingConversationRepository.kt`, `negotiatedThread`: forward body demand through the existing decorator.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`, `demandRows`, `matchesReadContents`: missing details cannot supply read sight.
- `ui/conversations/thread/ThreadScreen.kt`, `AuthoritativeThreadActionPublisher`: visible expansion and presentation binding.
- `ui/conversations/thread/ThreadViewModel.kt`, `presentAuthoritativeThread`, `retireAuthoritativeThread`: destination lifetime.
- `ui/conversations/components/ToolCallRow.kt`, `ExpandedBody`: existing outlined tool and code rendering.
- `docs/knowledge/features/thread-screen.md`: closed summaries do not prove sight; exact rendered revisions do.
- `docs/knowledge/features/remote-conversation-repository.md`: connection-confined repository and portable data layer.
- Sibling `pyrycode/docs/protocol-mobile.md`, “Bounded expansion reads” and “Security model”: wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read the design context and screenshot with the figma-design-to-code skill. Reuse the existing outlined Tool use surface, bodyMedium header, primaryContainer border and monospace body/code rendering. Loading, generic failure with Retry, and offline text occupy the expanded body; no layout or asset redesign and no child controls.

## Context

One behaviour ships: an expanded, visible tool can obtain missing details through its actual production consumer. Decoding and the sole expansion consumer stay together. Complete delivery remains the default; compact advertisement and summary application belong to #2431–#2433. Supplied omitted-detail fixtures exercise demand before activation. No decision record is needed.

## Design

Add a body request and body reply to the existing query sealed types. The request carries conversation, epoch, unsigned item ID and expected revision; expected kind is local validation. Replies retain unsigned capture fields and inert object contents. A data reply is staged by its query; only a matching separate marker publishes its contents.

The existing lane queues body intents alongside catch-up/page intents. Per-destination consumer sets coalesce equal targets, including queued work. Targets retain epoch, generation, incarnation, ID, revision and kind. Admission checks the current supplied snapshot, and publication rechecks current held facts and at least one current consumer. Collapse, removal and teardown remove demand and abandon unneeded active work using the existing drain-before-reuse path. Failure is sticky until explicit Retry; ordinary demand cannot restart it.

The store records transient per-revision body loading/failure state separately from coverage. Successful enrichment updates only the held item's content and omitted-detail flag, retaining placement and all other metadata. It never changes checkpoint, completed version, coverage or read marks. Complete retained bodies bypass demand, including offline/reopen. Existing complete facts cannot be replaced by incomplete replies.

ThreadScreen samples visible expanded tool rows after demandRows has hidden descendants. A bound event reaches the ViewModel's current presentation only. The ViewModel forwards its stable destination consumer through NegotiatedThread, the cache decorator and exact verified connection. The remote repository independently requires thread, interactive and thread_expansion on that authenticated connection. No capability is advertised here. ExpandedBody receives inline state through the existing ToolCallExpansion composition local, preserving existing callers.

Overlapping remote branches: #1947, #2175, #2266, #2325, #2338 and #2462. Their historical/additive changes are not causal prerequisites; edits here remain local to body demand and leave their other functions intact.

## State and concurrency model

Use the existing synchronized query lane and store methods. No new coroutine scope or dispatcher. UI demand uses the existing ViewModel and composition lifetimes; query expiry remains in the remote repository's owned scope. Background socket closure abandons body work. Transient loading state grants no retained availability. Process restore reuses only successfully enriched full facts.

## State transitions and identity reuse

| Event | Required focused proof |
| --- | --- |
| Expand/recompose/equal queued demand | `ToolBodyQueriesTest` coalescing and complete reuse probes |
| Data, then separate marker; authoritative empty body | `ToolBodyQueriesTest` separate-completion probes |
| Fragmented data and unsigned/unknown fields | `ToolBodyDecoderTest` assembly and token probes |
| Missing data, unfinished/invalid assembly, mismatched capture/kind/routing | `ToolBodyDecoderTest` rejection probes |
| Collapse, navigation and multiple consumers | `ToolBodyQueriesTest` cancellation and draining probes |
| Revision, epoch, deletion or host/connection replacement | `ToolBodyQueriesTest` owner-collision probes |
| Refusal, send failure, timeout, explicit Retry | `ToolBodyQueriesTest` failure probes |
| Mounted expansion, loading, success, failure, offline and retained reopen | `MissingToolBodyScreenTest` production consumer route |
| Existing complete-delivery tools | existing `tool` scripted scenario and affected presentation/row tests |

## Error handling

Reuse assembler rejection codes and correlated error decoding. Body failures produce a generic inline Retry state without setting history repair. Cancellation never publishes staged contents. The lane retains abandoned correlation until a validated terminal reply drains it, or drain timeout closes the connection. Offline missing details display offline text; support does not arise from disk.

## Testing strategy

Write failing focused decoder/lane tests first, then implement. Use real decoder, store, query lane and fake SessionPump; retain original assertions. Add a mounted shared Compose test with AndroidJUnit4 and Robolectric; no new device-only test. Run affected decoder/query/presentation/ViewModel/tool-row classes, scripted `tool`, lint, assembly, shared instrumented compilation and forced Spotless. Final merge/push precedes final assembly and pre-verify. Linux is this ticket's verification OS. Dispatcher owns complete configured gates; #2393 owns genuine paired compact capture/replay.

Forecast: approximately 1,500 written lines including tests and plan, four new exported declarations, fewer than ten simultaneous consumer updates, and existing rejection categories. One behaviour with its necessary consumer/proof; splitting decoder plumbing from its only consumer would violate the verification floor.

## Open Questions

None. Summary application and compact selection remain allocated follow-ons.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/remote-conversation-repository.md`, relevant demand/retention/query sections: missing-body demand, retained reuse, separate completion and failure states. Supply focused counted evidence and source identities to #2376's original handoff. Documentation must not substitute for missing evidence.

## Security review

**Verdict:** PASS

- Trust boundaries: body decoding validates exact correlation, capture, epoch, item, revision, kind and envelope routing before typed contents reach the store. MUST FIX addressed in design: data alone must never publish availability.
- Tokens: no credential generation, storage or exposure is added. Unknown JSON and operator text are never logged.
- Files/storage: no new file I/O or storage format. Existing app-private cache retains enriched full facts through its existing atomic path; transient query states are not certification.
- Android attack surface: no component, intent, provider or WebView is added. Body/code rendering remains inert text with existing display bounds.
- Cryptography: existing authenticated Noise session and primitives remain unchanged; expansion support is required on that exact connection.
- Network/I/O: reuse existing payload/aggregate/part/digest/deadline limits; preserve unsigned JSON tokens. No fallback provider or automatic retry loop.
- Errors/logs: static lifecycle/error codes only; generic local strings in the row. No body, path, correlation identity, secret or daemon error message enters logs.
- Concurrency: synchronized lane precedes synchronized store access; no store callback acquires the lane. Destination retirement removes only its demand; an abandoned request drains before the next send.
- Threat model: a hostile relay can delay/drop/flood ciphertext; existing bounds/deadlines and Noise protect availability/confidentiality. Hostile authenticated payloads require exact validation and inert rendering. Rooted-device token theft and UI screenshot/accessibility leakage retain the existing product posture; this ticket creates no token surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-22

## Revisions

- 2026-10-22: ownership inspection found a replacement-connection window before the next projection is published. Carry the producing connection in the presentation and reject demand unless it is still the current grant. A cleared ViewModel also rejects late demand. Two exhaustive shared evidence observers now explicitly ignore body replies: expansion must not certify their history/read receipts.
- 2026-10-22: the source-fact preservation regression failed (one executed, one failed) because enrichment replaced the retained DTO. Superseding the original content-mutation design, retain completed bodies in the host snapshot's separate ID/revision map and overlay them only during expanded display conversion. The original DTO list, metadata and full saved facts stay intact; unknown body fields cannot create a task roster or alter placement. The sibling Store.ToolBody implementation confirms content uses the complete renderer, so preserve its object shape, including existing input/result fields and unknown JSON. Fetched bodies are retained in memory across unchanged reopen/offline; existing disk persistence of complete full facts is unchanged, with no schema or migration added. Reset clears the map; changed revisions cannot read old entries. This addresses the trust-boundary finding; security verdict remains PASS.
- 2026-10-22: the cancellation probe failed (one executed, one failed): a fresh drain decoder accepted a marker for a different captured version. Preserve the body's bounded capture/routing metadata after abandonment and seed drain validation from it; staged content is still discarded. Matching terminal frames alone release the slot. Index presentation body targets by scoped key to keep the existing 8,333-row deferred proof linear.
- 2026-10-22: the mounted production consumer exposed the missing inbound type dispatch for `thread_tool_body`; route it through the same owned lane as other queries. Keep rendered string input/output interpretation confined to fetched body conversion so complete delivery without expansion retains its existing presentation.
- 2026-10-22: the existing decoder suite executed 33 tests with 22 failures against an added envelope-conversation equality guard. Its retained `route` fixture and the protocol's variable routing metadata show that envelope routing is distinct from payload conversation identity. Remove that guard, preserve all original assertions, and probe stable variable routing followed by changed-route rejection on body replies. Payload conversation still matches the request exactly; envelope metadata must match across data, fragments, completion and draining.

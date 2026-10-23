# Expanded detail validity

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadStore.kt`: snapshot overlays, `applyBatch`, `enrichBody`, `enrichChildren`, and retention admission.
- `app/src/main/java/de/pyryco/mobile/data/repository/AuthoritativeThreadQueries.kt`: consumer union, staged data, `bodyCurrent`, terminal draining and Retry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: deferred conversion, ancestor visibility and exact read sight.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: expanded demand and inline child controls.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt`: existing body Loading/Retry/offline surface.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: connection and destination binding fences; retain these paths.
- `app/src/test/java/de/pyryco/mobile/data/repository/AgentChildrenQueriesTest.kt` and `ToolBodyQueriesTest.kt`: controlled lane fixtures and ownership probes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/AgentChildrenScreenTest.kt`: mounted production repository/ViewModel consumer.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md`, and `conversation-cache.md`: retain stale entries in the existing inclusive host budget; Loading is no certificate and held presentations can only withdraw.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: “Compact projection”, “Bounded expansion reads” and “Security model” are wire authority.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read context and screenshot using the Figma design-to-code skill. Keep the existing outlined Tool use rows, background/primary-container border, body-medium header and monospace body-small details. Inline Loading/Retry/offline and history-style Load more remain inside the expanded parent; no geometry or asset changes.

## Context

Retained presence currently suppresses reads even after ancestor freshness changes. Source revision and subtree freshness are independent. This repair connects existing bounded demand to source observations without implementing compact wire selection or persistence. It needs no decision record.

One useful behaviour: an expanded current owner presents and refreshes invalidated details truthfully. Freshness plumbing has only this consumer and stays with its regression. Forecast: about 1400 written lines including tests and this plan, two supporting state declarations, no required public consumer migration, four acceptance criteria, and existing reject categories. Recount before handoff. Overlap: #2493 adds a separate store metadata helper; keep edits local.

## Design

Snapshot detail validity records accepted source observations and per-item invalidation serials separately from body/page data and Loading/Retry. A new source observation with a changed revision, changed freshness, or unknown freshness invalidates the affected retained subtree. Known zero compares as a value. Older observations cannot undo a newer observation; ordinary load publications do not count as source observations. Apply this at validated source batch/change and child-page admission, without rewriting full equal-revision facts.

Retained stale bodies/pages stay charged and visible in the same owner, including across source revision changes. Availability for read sight, empty completion and continuation requires current validity. Successful replacement clears only its own invalidation. First pages atomically replace enumerations; later pages retain existing immutable identity/revision and cursor-capture checks.

Query keys capture the item's invalidation serial in addition to existing host/connection, epoch, generation/incarnation, identity, kind and revision. Child continuations capture the retained consumed version too. Invalidation retires old work through existing staged-data discard and terminal drain. New work starts only from current expanded demand. Retry and Load more remain explicit and current-owner bound; failure is sticky through recomposition. A stale page chooses page one, retaining displayed rows until validated success.

Deferred presentation distinguishes usable read contents from retained display contents. Only expanded visible rows convert full bodies. Stale retained bodies render beside the existing inline state; stale pages cannot show authoritative empty or Load more. Offline stale and absent details remain distinguishable by retained rendered contents plus offline status.

## State and concurrency model

Reuse synchronized store admission and query-lane methods. No new coroutine, dispatcher, persistence or lock ordering. Snapshot StateFlow publications feed existing ViewModel scheduling and mounted demand. Existing connection scope cancellation, destination binding retirement, and lifecycle effects withdraw demand. No callbacks from the store into the lane while holding the store lock.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Equal parent revision with changed/zero/missing freshness, nested open child | Repository freshness probes and mounted refresh test |
| Source revision changes with unchanged freshness | Repository revision probe |
| Recomposition after replacement or Retry failure | Repository repeat-demand probe and mounted sticky Retry |
| Collapse/hidden ancestor while queued or between data and marker | Existing ownership probes plus invalidation drain probe |
| Two consumers, one withdraws | Existing coalescing probe plus invalidation consumer probe |
| Stale cursor/refused continuation, replacement, empty and tied later pages | Child restart probes and mounted restart |
| Reset/reused epoch, conversation/host removal or collision | Existing ownership probes plus retained invalidation retirement probe |
| Background/navigation/reconnect/connection replacement | Existing mounted stop proof plus mounted replacement test and retired-lane probe |
| Unchanged reopen including offline | Existing mounted reopen and repeat-demand freshness probe |
| Data then invalidation then terminal, late old capture after replacement | Controlled repository drain probe |

## Error handling

Keep existing bounded decoder failures, capacity refusal, timeout and disconnected retirement. Failed refresh retains stale data with sticky inline Retry; explicit Retry uses current targets and starts child enumeration at page one. Abandoned work drains under the existing timeout and connection-retirement rules. No daemon text or identifiers enter diagnostics; add only static validity event codes.

## Testing strategy

Write a causal equal-revision ancestor-change regression first and retain its red XML before implementation. Extend focused repository probes for independent revision/freshness, nested demand, replacement, capture fencing and consumer withdrawal. Extend the mounted production-consumer shared Compose fixture for refresh failure/Retry, stale-cursor restart and lifecycle replacement; run it under Robolectric and as one focused native class. Reuse unchanged large-fixture and retention tests, running affected classes rather than a complete suite. Run one existing `tool` scripted scenario on Linux. Preserve raw reports, source/binary identities and executed/passed/failed/skipped counts, including red evidence. Complete configured acceptance and genuine paired capture/replay (#2393) stay with their owners.

Run focused JVM classes, lint, assembleDebug, instrumented Kotlin compilation, Spotless apply/check, then merge main, push, and run assembleDebug and `scripts/pre-verify.py --gradle` before opening the PR.

## Open Questions

None. Unknown freshness invalidates once per accepted source observation, never once per recomposition.

## Revisions

- 2026-10-23: The earlier child-owner regression expected a revised ancestor to hide all retained grandchildren immediately. The ticket explicitly requires stale rows to remain until validated replacement, so its assertion now proves retained visibility with invalid page/body and no read sight in either page insertion order. Missing bodies still avoid full conversion. This changes display retention, never source identity or freshness certification.
- 2026-10-23: Lifecycle review identified a second asynchronous publication boundary: an old lane's final retirement could clear a replacement lane's sticky Retry under colliding IDs. Bind load-state writes to their query owner in `detailLoad`, retain serial/generation checks and prune owner bookkeeping with snapshot publication. The controlled replacement probe also collides request IDs. The mounted connection switch explicitly reopens after the existing destination-local expansion reset.

## Documentation handoff

Pending for the documentation stage and #2393's relocated handoff: supply invalidation, retry/reopen and lifecycle behaviour, counted outcomes, source/binary revisions and durable raw evidence for `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/remote-conversation-repository.md`, `docs/knowledge/features/conversation-cache.md` and `docs/e2e-interactive-stream.md`. Preserve historical failures and privacy/approval holds; documentation cannot supply missing checks.

## Security review

**Verdict:** PASS

- [Trust boundaries] Validated `ThreadQueryDecoder` data plus a separate matching success marker remain required. MUST FIX addressed in design: capture invalidation serials so delayed data/markers cannot certify newer source state.
- [Tokens and credentials] No credential creation, storage or diagnostic changes. Existing authenticated connection identity remains part of demand admission.
- [Files and storage] Expansion remains memory-only and charged to the existing inclusive host budget. No paths, files, backups or cache schema changes.
- [Android attack surface] No component, intent, provider or WebView changes. Retained daemon text uses existing bounded inert Compose text conversion.
- [Cryptography] No primitive or Noise session changes; existing encrypted framing remains authoritative.
- [Network and I/O] Reuse bounded expansion decoder, serialized lane, query deadline and connection retirement on drain timeout. No background full download or new network route.
- [Errors and telemetry] Only static invalidated/loaded/failure codes; no payload, cursor, identifier, plaintext, secret or stack trace logging.
- [Concurrency] MUST FIX addressed in design: source observations and detail admission use the store lock; query keys fence staged data and terminal markers, retaining remote-slot drain before reuse. No new jobs or locks.
- [Threat model] Delayed/reordered relay frames cannot cross request and source captures. Hostile daemon summaries retain existing size, identity and inert rendering checks. Rooted-device token theft and UI screenshot/accessibility exposure are unchanged existing platform concerns, outside this memory-only repair; genuine capture/privacy acceptance stays with #2393.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23

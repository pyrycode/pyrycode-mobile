# Direct agent children on explicit expansion

## Files read

- `data/network/ThreadQueryPayloads.kt`, `ThreadQueryRequest`: bounded requests and separate completion.
- `data/network/ThreadQueryDecoder.kt`, `decode`, `drain`: exact correlation, capture and routing.
- `data/network/ThreadUpdatePayloads.kt`, `ThreadItemDto`: strict numeric/optional metadata and inert JSON.
- `data/repository/AuthoritativeThreadQueries.kt`, `bodies`, `dispatch`, `retire`: serialized connection lane, coalescing and drain ownership.
- `data/repository/AuthoritativeThreadStore.kt`, `AuthoritativeThreadSnapshot`, `enrichBody`: separate expansion retention without history certification.
- `data/repository/NegotiatedThread.kt`, `bodies`: exact connection forwarding contract.
- `data/repository/RemoteConversationRepository.kt`, `demandToolBodies`, `onInbound`: authenticated capability enforcement and sole reply routing.
- `data/repository/RelayRepositoryCoordinator.kt`, `verifiedRepository`: connection identity fence.
- `data/repository/CachingConversationRepository.kt`, `negotiatedThread`: decorator and deleted-conversation fence.
- `ui/conversations/thread/AuthoritativeThreadPresentation.kt`, `projectThread`, `demandRows`: defer hidden conversions and preserve exact read sight.
- `ui/conversations/thread/ThreadScreen.kt`, `AuthoritativeThreadActionPublisher`: visible expansion and composition lifetime.
- `ui/conversations/thread/ThreadViewModel.kt`, `demandToolBodies`, `retireAuthoritativeThread`: destination binding and withdrawal.
- `ui/conversations/thread/ThreadUiState.kt`, `ThreadEvent`: bound demand events.
- `ui/conversations/components/ToolCallRow.kt`, `ToolCallExpansion`: inline expanded surfaces.
- `docs/knowledge/features/thread-screen.md`, `remote-conversation-repository.md`: stale held presentations may withdraw but cannot add demand; summaries never prove sight.
- Sibling `/work/Projects/pyrycode/docs/protocol-mobile.md`, “Bounded expansion reads”: sole wire authority.

## Design source

Figma: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read context and screenshot using figma-design-to-code. Reuse outlined closed/expanded Tool use surfaces, existing chevrons, bodyMedium headers, theme colors and nesting. Inline child status and history-style Load more follow each parent's loaded children. No asset or screen redesign.

## Context

One behavior ships: explicit agent expansion enumerates bounded direct summaries through the existing lane and mounted consumer. The reader has only this consumer, so splitting them violates the verification floor. Complete delivery stays usable; compact activation, retention budgets, subtree invalidation and genuine captures remain with the named siblings. No decision record is needed.

## Design

Add `Children` request/reply variants. Request limit is 50; replies validate strict direct-parent membership, summary-only shape, required zero/false fields, optional known/unknown facts, ascending order/id and bounded count/bytes. Preserve raw unsigned capture/cursor tokens and inert metadata. Stage at most one data batch; only its matching separate marker commits a page. Marker-only more=false is authoritative empty. More=true requires a cursor naming exactly the last delivered tuple and captured owner/version. Continuation requires the same captured version, even with unchanged parent revision.

Keep pages and loading/failure state separate from complete DTOs, bodies, fragment assembly and history coverage. Projection overlays admitted child summaries without replacing newer or complete held facts or moving an identity to another parent. Order-zero children remain visible under their expanded parent. Nested agents are closed summaries until individually expanded. Opening a group reveals only its member summaries. Summary conversion supplies no read evidence.

Child intents reuse the serialized query lane, using independent consumer target sets and the existing drain-before-reuse machinery. Exact host, connection, conversation, epoch, generation/incarnation, parent/revision and current presentation binding gate admission and publication. Retained first pages suppress refetch; only explicit Load more selects a certified cursor. Failures are sticky. Retry starts at the first page and replaces enumeration only on validated success. Existing body demand remains independent.

## State and concurrency model

Reuse synchronized lane/store methods and existing repository expiry scope; no new coroutine scope, dispatcher or background walk. Screen sampling requires visible individually expanded agents and RESUMED lifecycle. The ViewModel forwards current bindings to the exact granted connection. Held presentations can withdraw their prior demand before Loading reaches a display frame; they cannot admit additions or Retry. Retirement releases only that consumer. Background/disconnect abandons the lane; no staged page survives into a replacement connection. Successfully loaded pages are in-memory, usable offline; no disk schema or process-restore promise.

## State transitions and identity reuse

| Event | Focused proof |
| --- | --- |
| First expansion, recomposition, equal consumers | `AgentChildrenQueriesTest` coalescing probes; `AgentChildrenScreenTest` mounted expansion |
| 50-row/tied pages, short more=true, last page/empty | `AgentChildrenDecoderTest` boundary/completion probes |
| Load more, changed consumed version, cursor refusal and Retry | decoder/lane cursor probes and mounted controls |
| Collapse before Loading frame, navigation, lifecycle retirement | lane stale-withdrawal/drain and mounted lifecycle probes |
| Reused parent ID across host/conversation/epoch/revision/connection | lane ownership probes |
| Malformed, oversized, duplicate, foreign parent or marker | decoder rejection probes |
| Nested/group opening, retained offline reopen | mounted consumer and existing 8,333-item deferred conversion/read-sight probes |

## Error handling

Use content-free existing rejection categories, generic inline Retry and offline feedback. Partial data/refusal/send failure/timeout never completes enumeration or advances recovery/read certification. Abandoned requests retain captured routing/version for terminal draining; timeout closes the connection. Retry retains prior rows until validated first-page success.

## Testing strategy

Write decoder/lane failures before implementation. Reuse fake pump, generated 8,333-item fixture and mounted production-consumer patterns. Shared Compose tests run under Robolectric; no device-only test is added. Run affected decoder/lane/presentation/read-sight/body and mounted classes, existing scripted `tool`, lint, assembly, shared instrumentation compilation and forced Spotless. Push before final merge checks and `pre-verify.py`. Record counted outcomes and raw evidence on Linux. Complete configured acceptance is dispatcher-owned; genuine paired compact capture/replay stays with #2393.

Forecast: ~1,500 written lines (650 production, 750 tests/helpers, 100 plan/logs), <=5 new exported declarations, <=10 simultaneous consumers and <=10 rejection categories. All four criteria prove the same enumeration behavior. Overlapping #1947, #2175, #2266, #2325, #2338 and #2462 touch separate blocks or historical seams; keep changes local.

## Open Questions

None. Expansion support is required on the exact connection and compact-v1 remains unadvertised.

## Documentation handoff

Pending documentation stage: `docs/knowledge/features/thread-screen.md` and `docs/knowledge/features/remote-conversation-repository.md`, direct-child demand, separate page completion, cursor restart and inline controls. Supply counted focused outcomes, source identities and raw-evidence paths to #2376's original handoff including `docs/e2e-interactive-stream.md`. Documentation cannot produce missing evidence.

## Security review

**Verdict:** PASS

- Trust boundaries: MUST FIX addressed in design: `ThreadQueryDecoder` validates summary-only content, exact parent/capture/routing and matching separate completion before the store admits any page. Headline/error text is bounded inert text; unknown JSON never supplies actions or descendant inventory.
- Tokens: preserve unsigned tokens without signed/floating conversion; no credentials created, stored or logged.
- Files/storage: in-memory expansion maps only; existing full-fact app-private persistence remains unchanged, without migration or new file paths.
- Android attack surface: no new exported component, intent, provider or WebView; Compose text and existing tool controls only.
- Cryptography: unchanged authenticated Noise session; require current thread, interactive and expansion grants.
- Network/I/O: enforce hard row ceiling including ties, aggregate byte bounds, existing timeout and serialized draining; no automatic retry loop or fallback host.
- Errors/logs: static lifecycle/error codes only; no payload, headline, daemon error, cursor or secret in logs.
- Concurrency: lane lock precedes store lock, no reverse callback. Current binding/connection fences additions and publication; stale snapshots can only withdraw prior targets. Separate consumers retain independent demand.
- Threat model: relay delay/drop/reorder/flood is bounded by Noise and existing deadlines/limits. Hostile authenticated frames fail closed. Rooted-device credential theft and screenshot/accessibility exposure retain existing product policy; no new credential surface.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-23

## Revisions

- 2026-10-23: a summary headline is independent of rendered tool input. Forward it through the existing `ToolCallExpansion` local to the header, preserving it after a body arrives without fabricating input fields or body contents. Required `shown: false` remains a valid summary fact; projection withholds that row while enumeration and cursor accounting retain it.

- 2026-10-23 sizing remeasurement after formatting: 1,619 inserted/deleted production, test and plan lines, exceeding 1,600. The child reader and its only production expansion consumer prove one behavior; splitting their plumbing would violate the verification floor. Confirmed ancestry is #2200 → #2376 → #2389, already at the split-depth limit. Record `needs-human:sizing` and continue building the complete slice, preserving all assertions. Five new production types and fewer than ten consumer updates remain within their ceilings.

- 2026-10-23: share expansion request identity through `ThreadQueryRequest.Expansion`, while retaining independent body/child consumer sets and completion state. Child controls attach after the last currently displayed descendant without adding a new timeline-row variant or altering existing row callers. Summary adapters retain raw optional/unknown metadata and synthesize only the internal empty content carrier; raw wire summaries still forbid bodies/inventories. Snapshot expansion overlays walk reachable exact parent revisions and exclude retirements; saved full facts remain untouched. Retry cannot replace a newer retained page capture. Count reserved scalar envelope stamps in the combined child-page budget. Security review rechecked: no additional trust or storage boundary; verdict remains PASS.

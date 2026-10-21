# Deferred closed thread presentation

## Files read

- `AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread`, `displayRow`, placement and exact-revision qualification.
- `ThreadViewModel.kt`: `negotiatedPresentation` owns the production observation and worker projection.
- `ThreadScreen.kt`: tool/run expansion, action publication, read candidates and agent navigation.
- `ThreadRow.kt`: `foldToolRuns` needs tool identity/status and block metadata, not bodies.
- `BackgroundAgentBlocks.kt`: `carryRunExpansion` transfers open-run intent on placement changes.
- `ToolCallRow.kt`: `HeaderRow` consumes headlines; `ExpandedBody` alone needs full tool fields/output.
- `AuthoritativeThreadFixtures.kt`, `AuthoritativeThreadScreenTest.kt`: supplied-fact fixtures and mounted interaction patterns.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-subagent-tool-rows.md`: retain exact scoped keys, chronological launch markers, counts and scroll-only navigation.
- `docs/knowledge/features/development-verification-gates.md`: focused shared tests and native evidence boundaries.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot. Preserve the dark thread, translucent header/composer, 20dp gutters, outlined tool headlines and existing expanded bodies. Existing Material theme roles and typography remain in use; no visual redesign or new assets.

## Context

The production projection currently adapts every shown fact before expansion filters rows. One useful behavior is demand-driven presentation of retained contents; retention, transport and completeness remain independent. No decision record is needed.

Scope forecast: approximately 1100 written lines including this plan, production, shared fixtures and tests; no more than five new exported declarations, three production consumers, three acceptance criteria and no new network reject branches. Complete-snapshot overloads retain their behavior. Overlap with #1947, #2175, #2266 and #2334 is local/additive; their settings, activation and history changes do not supply a new prerequisite.

## Design

Add a deferred production entry beside the complete projector. Its rows contain only structural/headline metadata; a presentation-owned resolver adapts a demanded row from the same winning held revision. The existing full projector remains the default for observers/tests. Keep all held facts in `sourceSnapshot`, all shown revision requirements and placement/depth/task metadata, including hidden descendants.

`ThreadScreen` folds structural rows with the existing saved run expansion. Before producing read candidates or composing rows it filters closed ancestor contents and resolves only demanded prose and open tool bodies. Closed tool headlines retain the inputs needed by `toolHeadline` and current result-detail/status, without output/expanded input adaptation. Nested closure wins over a descendant's retained open intent. Group headers use summary tools for counts. Expansion is keyed by the existing host/conversation/epoch/generation scope.

## State and concurrency model

The ViewModel's existing worker projection and frame pacing publish one immutable snapshot-owned presentation. No new jobs, subscriptions, I/O or mutable global cache. Expansion remains composition-local/saveable and scope-keyed. Demand derives synchronously from the current presentation and expansion, so old revisions cannot win an asynchronous race. Disposal drops display ownership; background socket behavior stays with `LifecycleConnectionDriver`.

## State transitions and identity reuse

| Event | Required probe |
| --- | --- |
| Closed → open → closed → reopen | `DeferredAuthoritativePresentationTest`: conversion counts, latest text and unchanged held facts/revisions |
| Nested and grouped expansion | Unit and `DeferredAuthoritativeScreenTest`: outer closure overrides inner intent, run counts and demanded conversions |
| Agent finish while closed and repeated snapshot | Unit/screen probes: headline, launch marker, placement and running/finished counts update without hidden adaptation |
| Host/conversation/epoch/generation replacement with colliding IDs | Unit/screen probes: scoped keys retire expansion and old read candidates |
| Reconnect, re-collection and recreation | Existing authoritative read/action/activation tests plus scope replacement probes; held coverage never comes from display filtering |
| Hidden or replaced revision | Read probes retain every shown revision requirement; only demanded rendered rows become candidates |
| Agent-start tap | Mounted probe reaches the root without opening bodies or descendants |

## Error handling

No new I/O outcomes. Unknown/malformed facts keep the existing bounded summary fallback. Actionable controls, question/approval streams and pending sends remain sourced from authoritative facts, independently of display demand. Read qualification remains conservative when hidden revisions have not been seen.

## Testing strategy

Write a failing focused regression first. Add conversion instrumentation at the actual full adaptation boundary, supplied only by tests. A shared generated 8333-item fixture exercises production deferred projection and a mounted real `ThreadScreen`; count hidden body/descendant conversions and mounted row measurements before open, after nested/grouped opening and after collapse. Cover lifecycle changes, scoped collisions and exact read sight. Run affected projection/read/action/activation tests, existing screen expansion/navigation coverage, the new mounted scenario on the managed Android device, and scripted `tool`. Shared tests run under Robolectric and also compile as instrumented Kotlin. Run lint, assembly, forced Spotless and final `pre-verify.py --gradle` on macOS. Dispatcher owns complete acceptance gates.

## Open Questions

None.

## Documentation handoff

Pending documentation stage:
- `docs/knowledge/features/thread-screen.md`: explain closed presentation is deferred independently of authoritative retention and wire delivery.
- Owning thread testing topic: record the supplied focused/native/scripted counts and durable evidence paths.

## Security review

**Verdict:** PASS

- Trust boundaries: retain `displayText` bounds and existing typed adaptation for daemon text. Structural metadata must not copy expanded inputs/output. A hidden revision remains a read requirement, never evidence of sight.
- Tokens: no credential access, generation or storage is added.
- Files/storage: no persistence, paths, backup rules or cache schema changes; the resolver references the admitted in-memory snapshot only.
- Android attack surface: no exported components, intents, providers or WebViews are added. Existing text/markdown rendering remains unchanged.
- Cryptography: Noise and key lifecycle are untouched.
- Network/I/O: no requests or negotiation changes; the existing complete snapshot and certified checkpoint remain authoritative.
- Errors/logs: existing content-free lifecycle logs remain. Test conversion probes receive item identity only; no bodies, tokens or decrypted bytes enter logs.
- Concurrency: MUST FIX addressed in design: demand and candidates must bind to the same presentation/revision; never reuse an adapted-body cache across replacement owners. Scope-keyed expansion prevents colliding IDs from opening another destination.
- Threat model: hostile relay delivery remains fenced by existing admission; hostile daemon parent cycles must terminate using existing guarded ownership traversal. Rooted-device token theft remains handled by existing Keystore storage. Hidden contents must never reach composition/accessibility; no new screenshot or keyboard exposure is introduced.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-21

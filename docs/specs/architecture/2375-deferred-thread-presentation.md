# Deferred closed thread presentation

## Files read

- `AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread`, `displayRow`, placement and exact-revision qualification.
- `ThreadViewModel.kt`: `negotiatedPresentation` owns the production observation and worker projection.
- `ThreadScreen.kt`: tool/run expansion, action publication, read candidates and agent navigation.
- `ThreadRow.kt`: `foldToolRuns` needs tool identity/status and block metadata, not bodies.
- `BackgroundAgentBlocks.kt`: `carryRunExpansion` transfers open-run intent on placement changes.
- `ToolCallRow.kt`: `HeaderRow` consumes headlines; `ExpandedBody` alone needs full tool fields/output.
- `ThreadReadViewport.kt`, `AuthoritativeReadSightTest.kt`: accumulate resolved revision sight independently of newest-row eligibility, then validate snapshot-owned contents at acknowledgement.
- `ToolRowFormat.kt`: share pure headline field selection with deferred JSON lookup.
- `AuthoritativeThreadFixtures.kt`, `AuthoritativeThreadScreenTest.kt`: supplied-fact fixtures and mounted interaction patterns.
- `RelayConnectionFactoryTest.kt`: collision/settlement contents and `ProductionDestinations.sight` must demand the published presentation's exact revisions.
- `NegotiatedThreadViewModelTest.kt`: `authoritativeBurstsPublishOnlyAtFramesAndMissingDoesNotWaitForOne` retains frame identity and immediate Missing checks when contents are deferred.
- `ThreadViewModelRefusalOfferTest.kt`: `refusalRowsInTheThread_neverArm` distinguishes a retained resolved refusal from a live recovery offer.
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
- `docs/knowledge/features/thread-screen-testing.md`, deferred presentation coverage: record the supplied focused/native/scripted counts and durable evidence paths.

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
- Rework audit: validate the demanded newest item through its snapshot-owned resolver and exact scoped revision, preserving binding, connection and replacement fences. Collect older visible revisions even when no newest candidate exists; closed bodies still supply no sight. Parent-link traversal is iterative, memoised per demand and cycle-guarded, with linear retained metadata. No new I/O, credential, storage, Android surface or logging boundary is introduced.
- Threat model: hostile relay delivery remains fenced by existing admission; hostile daemon parent cycles must terminate using existing guarded ownership traversal. Rooted-device token theft remains handled by existing Keystore storage. Hidden contents must never reach composition/accessibility; no new screenshot or keyboard exposure is introduced.

**Reviewer:** builder self-review per `builder/security-review.md`
**Date:** 2026-10-21

## Revisions

2026-10-21: `foldToolRuns` and the existing agent navigation proof establish that an Agent root's body toggle is independent of its child-run toggle. Preserve that interaction: demand follows existing folded membership, and ancestor filtering checks omitted ancestors rather than inventing a new root-collapse control. A closed tool's own body stays deferred while its existing child-run headline remains usable. Hidden descendants cannot be read candidates even when another placement exposes their metadata.

2026-10-21: `ThreadMessageList` can subcompose old offscreen boundary rows during agent relocation. Deferred presentations measure only currently demanded rows and current body state; omitted descendants supply no new measurement composition. The mounted composition probe counts measurement slots too. Complete-snapshot/legacy relocation keeps its existing renderer.

2026-10-21 rework: verifier findings 1–4 retain the same deferred-presentation behaviour slice. `qualifiesAuthoritativeSight` validates resolved contents and the carried exact revision against the current snapshot resolver, rather than comparing against a summary. `ThreadReadViewport` records all eligible visible revisions before requiring a newest candidate for acknowledgement. Regressions cover successful deferred assistant/tool/banner acknowledgement, rejection of summary/stale/binding evidence, and reading older rows while the newest tool is closed without revisiting them after opening. Replace transitive ancestor sets with parent links and memoised visibility; probe an 8333-item closed chain. Share subject field selection with `ToolRowFormat` and assert deferred/full headline parity. Forecast remains below 1600 written lines, with no new exported types and one constructor caller updated. Additional overlap #2367 removes the legacy read fallback in a separate block; this repair remains local and does not depend on it.

The mounted read regression observes the viewport's actual content-free revision ledger through an optional composition-local test probe. It asserts older sight before opening and absence of hidden-body sight. A sending-feedback tail keeps the newest ordered tool above the composer without supplying read evidence; opening then qualifies without revisiting older rows. The probe performs no copying or callback when absent in production. Parent ownership/depth calculation also memoises shared walks so the deep-chain regression avoids quadratic transient work as well as retained ancestor metadata.

2026-10-21 test-caller rework: the seven dispatcher regressions in `RelayConnectionFactoryTest`, `NegotiatedThreadViewModelTest` and `ThreadViewModelRefusalOfferTest` inspected structural summaries as full display contents. Resolve demanded rows through their published presentation before asserting text/type or submitting sight. The sight helper carries the resolved row's scoped key and its exact revision from that same presentation. Keep all collision, settlement, replacement, queued-attempt, generation, frame-pacing and retained-refusal assertions. This repairs callers of the existing deferred contract without changing production or its security boundaries. Existing summary/stale/binding rejection probes must still pass. One behaviour slice remains; approximately 1150 total written lines, no new exported declarations or production consumer changes. Other in-flight branches share these test files through earlier activation/harness changes; #2367 changes separate legacy-observation assertions. Keep this repair local and additive. Run all three affected JVM classes, deferred read/presentation regressions, the mounted deferred Android class and scripted `tool`, then the required static/final gates on macOS. Complete suites remain dispatcher-owned.

2026-10-22 manual integration: main `ecad147dd8a2` removed legacy history helpers;
retain `foldToolRuns` followed by presentation-owned `demandRows`. The integrated
JVM gate exposed six new `AuthoritativeReaderPagingTest` caller failures. Exact
comparison: six fail on `27ea5e29d`, all six pass on that merged main. Bounded repair
#2475 resolves asserted messages from the same published presentation, including
streaming and reopened paging assertions. Preserve exact content/identity/order,
certification, retirement, request and work-budget assertions; no production change.
Run the affected class, then complete configured acceptance on the committed repair.

2026-10-22 native caller rework under #2475: the full UI gate executed 333,
with six further deferred-content caller failures plus inherited model selection
(#2351), and one additional skipped capture. All seven failed on the same-tree
selection. On merged main, the six caller methods passed and only model selection
failed. Resolve the compatibility fixture's asserted contents, the design capture's
canonical refusal, and the permission replay's final settled reply through their
current presentation. Retain exact facts/counts, live-refusal identity, both
permission confirmations, strict replay completion and every existing deadline.
Production and JVM sources are unchanged from accepted `4c5ccd369`.

The first native compatibility caller adaptation retained every resolved display
model beside the full file fixture and hit the emulator's 192 MiB heap limit;
retain that failed run. Compare identical ordered content in batches of 200 from
the same presentation, then assert the complete count. This bounds assertion-only
memory without changing production, first-draw measurement, facts or deadlines.

The complete-suite bounded-helper run retained one further compatibility OOM:
the stack identifies `demandRows` rebuilding a whole-window HashSet inside the
assertion. Validate each expected complete root message through the presentation's
snapshot-owned `matchesReadContents` resolver instead. This retains exact ordered
content, type, identity/revision and total-count checks while holding one resolved
model at a time, without a whole-window visibility allocation. A wrong-content
negative control guards the equality path. Keep the failed batched run too.

Keep the same complete raw-fact equality, typed fixture id uniqueness and order-
island checks in a single streaming pass as well. Avoid assertion-only full-file
map/distinct/zip collections beside the mounted fixture. Every source fact and
every displayed expected message is still compared; counts and certification stay
explicit, and first-draw timing/production are untouched.


2026-10-22 allocation repair #2477: the final streaming assertions pass in the
focused class, but the complete candidate UI sequence still exhausts the normal
192 MiB heap during query lease release. The complete identical main sequence
passes both full-file compatibility methods. Preserve those reports and exact
APKs. The deferred projector retains empty root prose models and repeatedly
creates separate complete models for their always-open content. Adapt admitted
root user/assistant messages once per immutable presentation and share that same
row between structural rows, demand and exact-content qualification. Keep null
and zero root parents equivalent; negative or positive parents remain deferred.
Unadmitted opening-window rows remain deferred. Partition the resolver index so
root models replace their DTO entries rather than adding another whole-file
index. No mutable cache, cross-snapshot sharing, I/O, wire, heap, fixture size,
assertion weakening, deadline or teardown-order changes are permitted.

Security audit for #2477: PASS on this design. Root model sharing stays within
one scoped immutable presentation and uses existing bounded typed adaptation.
Current exact revision, presentation/binding and connection checks remain the
read boundary; sharing a model alone establishes no viewport sight. A root moved
under an ancestor is deferred by its new presentation. Closed tool/agent models
and their descendants remain summaries. New probes cover identity reuse,
revision/scope/parent replacement and opening-window exclusions. Run them red
before the fix, then all affected read/closure classes, fresh configured JVM/build,
the saved-thread and deferred native classes, complete UI and scripted acceptance.

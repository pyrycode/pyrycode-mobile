# Stable thread row keys (#1940)

## Files read

- `docs/knowledge/features/thread-screen.md`: foreground read candidates use the same list identity as anchoring and following; #1953 is merged.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md`: joined Agent roots remain visible and expansion follows run ids.
- `docs/knowledge/features/queued-backlog-section.md`: snapshot replacement and one-to-one echo correlation remain render-time contracts.
- `docs/knowledge/features/development-verification-gates.md`: shared screen tests need an Android-visible wrapper for the routine UI gate.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `foldQueuedRows`, `foldToolRuns`, `ThreadRow.listKey` own projection and identity.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `foldHistoryToolRuns` exposes marker targets without changing delivered identity.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: list items, follow-newest, read candidate and end-spacing compensation all call `listKey`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ToolRunFoldTest.kt`, `ThreadRowsTest.kt`, `BackgroundAgentBlocksTest.kt`: existing fold, queue and expansion contracts.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ToolRunCollapseTest.kt`, `ThreadReadViewportTest.kt`: real-screen fold coverage and the existing list-state observation seam.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewportDeviceTest.kt`: Android-visible shared probe pattern.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: dark thread, inset alternating message surfaces, session rule, overlaid title bar and composer. Existing Material 3 colour and typography tokens, geometry, assets and interaction remain unchanged; this is an identity correction.

## Context

A lone tool currently loses its `msg:` key when another tool makes a collapsed run. Marker splitting causes the inverse transition. Unmatched queued keys depend on the whole display list's position. These transitions remove a reverse-layout reader's anchor. Data-layer message-id changes remain #1941's responsibility. No decision record is needed.

Overlap: #1954 adds `contentType` beside `listKey` in `ThreadRow.kt`; its additive helper is independent of this key correction.

## Design

Keep fold contents and order intact. A collapsed `ToolRun` adopts `msg:<runId>`, the first tool's delivered key. An expanded header uses `tool-run:<runId>` while its children retain their existing message keys. A marker-exposed tool already carries the correct message key, so `foldHistoryToolRuns` needs no change. Agent roots remain delivered and expansion still uses `runId`.

Add a default-zero occurrence ordinal to `ThreadRow.Queued`. `foldQueuedRows` counts each queued id's occurrences in snapshot order, including matched entries. Unmatched keys encode queued id plus occurrence. This is independent of delivered/history positions and other folds, distinguishes repeated queued ids, and remains deterministic for held inputs after reconnect. Matched rows continue using their echo's message key. Replacement and clear retain the existing stateless projection semantics.

Keep the indexed `listKey` signature to avoid migrating its four screen consumers and existing callers; its index becomes unused. Document that compatibility parameter and the updated uniqueness argument. No new exported types or dependencies, screen API changes, or lifecycle logs: this pure projection introduces no lifecycle or error event.

## State and concurrency model

No new state, jobs or flows. Occurrence counting is local to each O(items + queued) projection; existing screen `remember` caches, cancellation and connection lifecycle remain unchanged.

## Error handling

No new failure branches. Duplicate queued ids are legal and get distinct occurrence keys. Message ids continue relying on repository upsert uniqueness. Collapsed headers consume the first tool; expanded headers use a separate namespace, preventing duplicate lazy-list keys.

## Testing strategy

Write failing unit probes for singleton growth, marker splits at start/middle/end and rejoin, expansion, unique keys, repeated render/reconnect inputs, and queued duplicates under history prepends and unrelated delivery insertion. Assert order, contents, marker targets and unconsumed message identities independently. Retain queue clear/replacement and matched-delivery tests. Update only existing assertions of old literal keys.

Add `ThreadRowAnchorTest.loneToolGrowth_preservesBottomAnchorAndOffset` in shared tests using the real `ThreadScreen`, collapse enabled, overflowing history and multiple unmatched queued rows below the lone tool. Observe its actual lazy state through `LocalThreadListCompositionObserver`, scroll away from follow mode, and make the lone tool the bottom-most visible anchor. After appending the second adjacent tool, assert the same representative key and scroll offset within one physical pixel. Run it red before implementation, then green on JVM and device. An Android-visible subclass selects this shared method in the routine UI gate; it exists to satisfy the explicit cross-platform anchoring acceptance criterion.

Run existing tool fold/collapse, background Agent fold/expansion, queue and read viewport coverage, focused device probe and scripted `tool`. Run lint, assemble, Android-test compilation and forced Spotless. After final main merge and push, run the whole JVM/shared suite, assemble and `scripts/pre-verify.py --gradle` with the PR body.

Preserve `InteractiveStreamE2ETest.interactiveTurn_toolPrompt_rendersToolStepInThread`; request `all` under Live tests to satisfy the full live-gate acceptance. Dispatcher owns the fresh live gate after verification, including executed/failed/skipped evidence; pending execution is explicitly handed off.

## Open Questions

None. Sizing: approximately 500–650 written lines including probes and this plan, one production file, no new exported production type, no consumer migration, five acceptance criteria and no new reject branches; within all limits.

## Revisions

- 2026-10-08: focused coverage exposed an old literal-key assumption in `AgentRunNavigationProof.childIndexes`: it expected every collapsed child's message key absent. With the planned representative policy, the first tool's key belongs to the closed header. Update membership checks to require that key present and every other child absent when closed, and all children present when open. Retain pointer taps, owned-prose visibility and expansion assertions. This is an assertion-policy correction, with no change to folding or navigation. Include the affected background-Agent live scenario in the dispatcher handoff.
- 2026-10-08: final main merge brought in #1954's `ThreadRowContentTypeTest`, whose key lookup fixture spells the old unmatched-queue and collapsed-header literals. Update those literals to the planned keys; keep every content-type, cardinality and interaction assertion intact. The additive `contentType` production helper merged unchanged.

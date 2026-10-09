# Preserve the viewport when background Agent blocks settle (#1955)

## Files read

- `ThreadScreen.kt`: `ThreadScreen`, `ThreadMessageList`, `ThreadRowContent`; folded rows, keyed lazy layout and measurement callbacks.
- `ThreadListFollow.kt`: `ThreadListViewport`, `FollowNewestEnd`, `followStep`; #1942 row-height/padding correction and follow bookkeeping.
- `BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks`, `carryRunExpansion`; completion moves blocks while preserving displayed identities and expansion.
- `ThreadRow.kt`: `listKey`, `foldToolRuns`; collapsed and expanded representatives.
- `BackgroundAgentBlocksScreenTest.kt`, `ThreadReaderGeometryTest.kt`, `ThreadReaderGeometryDeviceTest.kt`, `ThreadListFollowTest.kt`: real-screen fixtures, native rendered-frame sampling and routine device selection.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-how-it-works-list-and-status-row.md`: reversed geometry, chrome reservations and #1942 compensation.
- `docs/knowledge/features/development-verification-gates.md`: shared/device test selection and physical-pixel assertions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected design context and screenshot: dark message area behind translucent header and composer, alternating message bubbles and side actions, session rules and tool rows. Preserve existing Material colour, typography and shape tokens, 20dp gutters and resting gaps. No layout or styling changes.

## Context

#1942 / PR #2002 is merged and present in this worktree. Its raw size/padding correction protects readers against geometry changes, but a stable keyed running row still becomes the lazy list's anchor when its block moves into history. One deliverable is preserving the location the completed block vacates, including the follower's first rendered update frame.

## Design

Extend the remembered `ThreadListViewport` to observe displayed-row transitions before lazy measurement. Detect newly finished Agent markers and derive moving keys from their previous block membership, including collapsed/expanded child-run representatives. Do not change fold placement, internal order, identity or expansion.

Transfer the lazy anchor before measurement. A follower requests index zero. A history reader uses a visible stationary row's old geometry and its current index; a moving key must never be the transferred anchor. When every visible row belongs to the moving blocks, use the nearest stationary row on their older side at the vacated older boundary, with normal newest-end clamping when remaining content cannot fill the vacancy. Preserve stationary geometry through simultaneous newest-end padding changes using the existing placement correction. Record relocation separately from raw geometry displacement so follow bookkeeping does not treat the anchor transfer or clamp as reader input. A repeated terminal update does not transfer again. Ordinary row growth continues through #1942's raw compensation.

No in-flight numeric feature branch overlaps either production file after fetching origin. Forecast: 700–1100 total written lines including plan, focused logic/screen tests and Android probe overrides; at most three new internal types, one screen consumer, four acceptance criteria and fewer than ten decision branches. No dependency or public API migration.

## State and concurrency model

All transition and geometry bookkeeping is owned by the remembered list composition, on the UI thread. Capture the old lazy layout without adding composition snapshot reads, and request the new anchor before measurement. Apply remaining relative geometry displacement in placement before drawing. Existing layout/send effects cancel on screen exit; no new coroutine scope, dispatcher, repository state or wire contract.

## State transitions and identity reuse

| Event | Regression |
| --- | --- |
| Running block finishes beneath a following reader | `followerCompletion_keepsNewestEveryRenderedFrame` |
| Multiple blocks finish, or another block stays running | `followerCompletion_withAnotherRunningBlock_keepsNewestEveryFrame`, `multipleCompletions_keepStationaryReader` |
| Moving block is bottom-most visible with collapse enabled or disabled | `visibleCompletion_preservesStationaryRows_collapsed`, `visibleCompletion_preservesStationaryRows_uncollapsed` |
| Moving block is offscreen | `offscreenCompletion_preservesStationaryRows` |
| Moving block fills the viewport with no visible stationary anchor | `fullViewportCompletion_fillsVacancyAndClamps`, `fullViewportCompletion_retainsOlderBoundaryAgainstRemainingBlock` |
| Multiple blocks finish under a follower | `followerMultipleCompletions_keepNewestEveryFrame` |
| Stationary anchor grows in the same publication as completion | `visibleCompletion_withStationaryGrowth_preservesTopEveryFrame` |
| Newest receipt settles B behind still-running A | `newestReceiptAcrossRunningBlock_followerKeepsNewestEveryFrame`, `newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_collapsed`, `newestReceiptAcrossRunningBlock_readerKeepsStationaryRows_uncollapsed` |
| Direct jump or restore into an unmeasured tall child | `fullViewportCompletion_withColdMeasurements_retainsOlderBoundary` |
| Replayed completion under the same Agent id | screen completion cases repeat terminal state; no second relocation |
| Anchor changes due to relocation while reader is not following | focused `ThreadListFollowTest` compensation regression; subsequent growth must remain unfollowed |
| Conversation exit/reopen, rotation and accepted sends | composition-local state resets with list; existing `ThreadScreenFollowTest` restoration/send cases remain passing |

## Error handling

No I/O or new UI errors. Missing stationary content falls back to the newest end. Removed keys are never requested. Log only static relocation event, moving-row count and follower flag; never message text or identifiers.

## Testing strategy

Write failing real-`ThreadScreen` completion tests first. Use overflowing main history and an early terminal position followed by newer stationary messages, so completion genuinely relocates keyed rows. Capture native View draws at each explicitly advanced frame from update through settlement; assert follower index/offset and stationary pixel coordinates with a one-physical-pixel bound. Cover collapse on/off, offscreen moves, moving bottom-most anchors, full-viewport blocks and another running block. Add Android-visible overrides selecting the same shared methods into the routine UI gate, as #1942 does; platform frame proof is explicitly required by acceptance.

Run focused new cases plus existing `BackgroundAgentBlocksTest`, `BackgroundAgentBlocksScreenTest`, `ThreadListFollowTest`, `ThreadScreenFollowTest` and #1942 geometry tests. Run focused Android probes, the relevant scripted `background-agent` scenario, lint, assembly, Android-test compilation, formatting and final pre-verify after merging main. Preserve both existing E2E methods. Dispatcher owns fresh full live/scripted gates and their named-method executed/failed/skipped evidence; list `all` under PR Live tests to require the full live acceptance gate.

## Open Questions

- Resolved: logical offsets transfer correctly on both platforms. Ten named viewport methods each executed once with zero failures or skips on JVM and managed Android 13, including full-viewport boundary reuse and simultaneous stationary growth. All 79 focused placement, follow and streaming-geometry methods passed.

## Revisions

- 2026-10-09: Capture the old lazy layout in `relocationFor` during screen composition without snapshot observation, then apply `ThreadAnchorTransfer` in the side effect before measurement. Lazy item offsets remain logical, increasing toward the older end, even under reverse layout; transfer uses their negation. Exclude wholly newest-side chrome-hidden rows from stationary anchor selection. The initial negative control executed seven cases, failing four, including loss of visible stationary membership and an unfilled full-viewport vacancy.
- 2026-10-09: The relocation generation is snapshot-observable to the follow collector, which ignores intermediate layout bookkeeping while transfer is pending. Publish the new generation in placement so following consumes a complete corrected layout rather than treating a different key or a clamp as reader input. Capture a selected stationary row's old height in the transfer and apply its measured height delta along with padding correction: the simultaneous-growth probe failed before this integration. Completion with unchanged displayed key order needs no transfer and leaves #1942 compensation intact.

- 2026-10-09: Preserve named-method platform evidence at `/tmp/builder-1955/viewport-jvm.xml` and `/tmp/builder-1955/viewport-device.xml` before the scripted scenario overwrites the device result directory. Each contains the ten viewport methods, 10 executed, 0 failed, 0 skipped. Final written work remains below 800 lines, with one new internal production type and one screen consumer.

- 2026-10-09 (verifier rework, findings 1–2): Finished roster controls and frame-paced terminal receipts publish independently. Detect a completed root's changed placement against common stationary rows on every publication, including already-finished blocks; unchanged terminal replays and ordinary inserts remain inert. Retain displayed-key measurements within the list composition so the full-viewport fallback includes offscreen older children and the root in the vacated boundary. The extended boundary probe keeps only `tall-a` measured at completion and retains the tall running `b` block to avoid clamping. New delayed-receipt probes publish the finished roster separately, then receipts within history and at the newest end, covering followers and collapsed/uncollapsed readers on JVM and Android. Negative controls reproduce a 320-pixel stationary-row shift, missing stationary membership, and a 118-pixel boundary error.

- 2026-10-09 (second verifier rework, findings 1–2): Include common block roots in the placement comparison, preserving relative block order as well as stationary-row placement. A newest-end B receipt crossing still-running A now transfers before measurement; ordinary inserts and terminal replays remain inert. New collapsed/uncollapsed reader probes assert the exact newest-end clamp and retained non-following state; the follower probe samples every rendered frame.
- 2026-10-09 (second verifier rework, finding 2): Supplement matching old-row geometry with unplaced subcomposition measurement of previously unmeasured intervening boundary rows before measuring the relocated LazyColumn. Use the shared row renderer, identical item constraints and each old chronological neighbour for joined-tool spacing. Measured old rows retain their actual local tool expansion; cached geometry is reused only when the row and chronological neighbour match. Unplaced measurements never draw, receive input or publish read/attachment presentation. This computes the actual old boundary even when no older child/root has been measured, without a temporary scroll or additional coroutine. The cold-cache probe calibrates in a retired composition, then creates a fresh list directly inside `tall-a`; only that child appears in its initial layout, and the row-measurement observer explicitly rejects any older A measurement before completion. Negative controls failed both block-swap readers (96/118 physical pixels) and the cold boundary (118 physical pixels). The original warmed-boundary probe remains intact. Written work remains below the 1600-line ceiling, with one internal production transfer type and one screen consumer.

- 2026-10-09 (second rework verification): All 19 named viewport methods passed once on JVM and managed Android 13 with zero failures/skips, including the cold-measurement assertion and B crossing A at the newest end. Required focused classes passed 88/0/0; focus, overlay and resting-gap coverage passed 40/0/0. Evidence is preserved under `/tmp/builder-1955/rework2/`. The deterministic background-agent scenario passed 1/0/0. Dispatcher-owned full UI, scripted and live gates remain pending on the repaired head.

## Documentation handoff

Pending for the documentation stage, from verifier review:
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, reader geometry and newest-end follow: explain split roster/receipt and block-to-block relocation detection, stationary-anchor selection, full-block boundary fallback including previously unmeasured rows and interaction with #1942 compensation and follow bookkeeping.
- `docs/knowledge/features/thread-screen-testing.md`, reader geometry coverage: record shared/Android frame regressions and final named-method evidence, carrying forward reverse-layout offsets, chrome-hidden anchor exclusion and the post-placement generation lessons.
- `docs/e2e-interactive-stream.md`, background-agent acceptance evidence: record the dispatcher-owned fresh full live/scripted gates and the named rung-3/rung-4 executed/failed/skipped counts after verification.

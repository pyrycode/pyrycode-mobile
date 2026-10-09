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
| Moving block fills the viewport with no visible stationary anchor | `fullViewportCompletion_fillsVacancyAndClamps` |
| Replayed completion under the same Agent id | screen completion cases repeat terminal state; no second relocation |
| Anchor changes due to relocation while reader is not following | focused `ThreadListFollowTest` compensation regression; subsequent growth must remain unfollowed |
| Conversation exit/reopen, rotation and accepted sends | composition-local state resets with list; existing `ThreadScreenFollowTest` restoration/send cases remain passing |

## Error handling

No I/O or new UI errors. Missing stationary content falls back to the newest end. Removed keys are never requested. Log only static relocation event, moving-row count and follower flag; never message text or identifiers.

## Testing strategy

Write failing real-`ThreadScreen` completion tests first. Use overflowing main history and an early terminal position followed by newer stationary messages, so completion genuinely relocates keyed rows. Capture native View draws at each explicitly advanced frame from update through settlement; assert follower index/offset and stationary pixel coordinates with a one-physical-pixel bound. Cover collapse on/off, offscreen moves, moving bottom-most anchors, full-viewport blocks and another running block. Add Android-visible overrides selecting the same shared methods into the routine UI gate, as #1942 does; platform frame proof is explicitly required by acceptance.

Run focused new cases plus existing `BackgroundAgentBlocksTest`, `BackgroundAgentBlocksScreenTest`, `ThreadListFollowTest`, `ThreadScreenFollowTest` and #1942 geometry tests. Run focused Android probes, the relevant scripted `stream` scenario, lint, assembly, Android-test compilation, formatting and final pre-verify after merging main. Preserve both existing E2E methods. Dispatcher owns fresh full live/scripted gates and their named-method executed/failed/skipped evidence; list `all` under PR Live tests to require the full live acceptance gate.

## Open Questions

- Validate the anchor-transfer offset against real reversed-list layout and both platforms before handoff; record any changed design under Revisions.

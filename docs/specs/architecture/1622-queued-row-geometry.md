# Queued row geometry (#1622)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt`: `QueuedMessageRow` already reserves action space using a weighted bubble (#1642).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: shared bubble shape, 20/16 dp padding and 20 dp gutter.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: queued row callbacks and `ordinaryMessageRestAdjustment`, whose 8 dp queued bottom compensation stays valid.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/QueuedBacklogTest.kt`: existing queue identity and independent action pointer coverage.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `queuedAndToolRowFramesAt412By892`, landed through #1852 replacing closed PR #1627.
- `docs/knowledge/features/queued-backlog-section.md`: preserve stateless rendering, inert text and per-row callback binding.
- `docs/knowledge/features/development-verification-compose-evidence.md`: copy only this ticket's captures, retaining real-bar metadata and fresh executed XML counts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-4677

Inspected design context and screenshot on 2026-10-06. End-aligned queued rows have a decorative 16 dp waiting glyph, an 8 dp glyph gap, a dimmed user bubble with 6 dp theme corners and `bodyMedium` text, and a 48 dp close target centered at x=368 within the 20 dp end gutter. The wrapping bubble is 200 dp wide with 20/16 dp padding; rows are 16 dp apart. Existing Schedule and Close vectors match these Material glyphs. Preserve the authorized optional Send now extension from #1642.

## Change

Cap the weighted queued bubble at 200 dp, keeping fixed action targets measured before the bubble and allowing it to shrink when Send now is present. Add 8 dp top padding to the existing 8 dp bottom padding so successive queued bubbles have a 16 dp gap without changing the newest row's bottom compensation. Shared bubble padding already matches the frame. No signature, state, data flow, failure mode, dependency or logging changes are needed. The original disappearance was already repaired by #1642; pin its behavior with regression coverage. Only stale `origin/feature/1619` overlaps the evidence files; its replacement has landed and no implementation depends on that closed branch. Estimated written work: under 250 lines, no new exported production declarations, no consumer changes and two acceptance criteria.

## Testing strategy

Add a shared Robolectric geometry test at forced 412 × 892 with native text measurement. Assert the 200 dp bubble cap, 20/16 dp padding, 16 dp gap, 48 dp drop bounds centered at x=368, and center/edge pointer callbacks for short, wrapping and long unbroken text. Also cover a wider viewport so the width cap cannot pass merely because available space happens to equal 200 dp. Run existing `QueuedBacklogTest`, `ThreadFrameTest` and `ThreadScreenFollowTest`. Recapture the two queued states using the existing full API 35 method with real bars; retain PNGs, metadata, comparisons and fresh XML counts. This existing device test is required for real screenshot pixels; no new device test is needed. Live queue/drop remains #849 as the ticket specifies. Run focused checks and the final whole unit suite, assemble and pre-verify gates.

## Documentation handoff

Pending for the documentation stage: update `app/src/androidTest/assets/design-1220/thread/index.md`, the queued-messages verdict for `696:4677`, using the new captures and comparisons. Record that #1642 supplied the weighted action reservation and this ticket pins width and spacing. Preserve unrelated frame differences and their existing ownership.

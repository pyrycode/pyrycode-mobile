# Natural message height with compact side actions (#1982)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer` owns the surface/row floors and `MessageActions` owns glyph and target geometry.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownText` and `StreamingMarkdownText` use content-sized columns and preserve source separately from rendered text.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt`: `MessageAttachments` contributes content height before the body.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageReplyTargetsTest.kt`: extend real pointer ownership and target geometry for both arrangements and same-role neighbours.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: retain tall side geometry, source-copy bounds and streaming coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentRestGapTest.kt`: add short-message rest regressions while retaining its 12dp expectation.
- `docs/knowledge/features/message-bubble.md` and `message-bubble-testing.md`: preserve content hugging, streaming fill, attachment-only spacing, glyph assets and source-copy semantics; the ticket supersedes the 96dp floor.
- `docs/knowledge/features/thread-screen.md` and `development-verification-gates.md`: rows own spacing and shared tests need forced viewports/native text measurement.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1577

Read design context and screenshot, including `813:7238`. Copy is an unbacked 11×12dp glyph and Reply 13×12dp, with stacked centres 25dp apart and the pair centred beside the bubble. Keep existing drawable assets and `colorScheme.primary`; apply the ticket's approved compact exception, Copy then Reply left-to-right, centred in adjoining 48dp targets.

## Context

Actions currently enlarge short surfaces and rows to 96dp. The 2026-10-08 product decision requires natural height for all content, including two-line messages. One deliverable: remove action-driven height while retaining accessible, independently routed controls. No decision record is needed.

## Design

Remove the surface and row height floors. Obtain the natural intrinsic height of the surface at the existing stacked maximum width (307dp at 412dp, 215dp at 320dp). Select compact below 96dp, stacked at or above it; measure the surface once at the selected width. Compact reserves 96dp plus the existing 12dp gap and 40dp opposite inset. Subsequent compact wrapping cannot change the arrangement selection.

The row height equals the measured surface height. Compact targets are 48×48dp, centred vertically, with glyphs centred within each. Stacked keeps the 13dp visual lane, existing surface widths and 25dp glyph-centre spacing. Position its invisible targets outward so their near edges meet the surface edge without entering content; keep glyph positions unchanged. Preserve 16dp row spacing, timestamp behavior, selection, attachments, streaming reveal and exact immutable-source callbacks.

No exported API changes or new types. Overlap: remote #1766 changes the streaming renderer in this file; it is already present in main and does not restructure the container/action layout.

## State and concurrency model

No new state, jobs or flows. Arrangement is derived in measurement from current content and constraints. Existing composition-owned streaming jobs and latest timestamp callback remain unchanged; intrinsic queries must not duplicate composition or invoke callbacks.

## State transitions and identity reuse

| Event | Coverage |
| --- | --- |
| Repeated edge taps on adjacent messages | `MessageReplyTargetsTest`: exact source/identity once per pointer, no neighbour action or timestamp toggle. |
| Compact text rewraps past 96dp | `MessageReplyTargetsTest`: keep compact selection based on original width. |
| Timestamp becomes visible | Natural-height shared test and existing `MessageMetaRowToggleTest`: content grows without an action floor. |
| Streaming updates/finalizes | Existing `MessageBubbleTest.streamingSideCopy_readsLatestMarkdownSource_andRetainsTheClipboardBound` and timestamp/streaming suites; retain streaming target test. |

No new lifecycle or identity state is retained across measurement, rotation or remount.

## Error handling

No new failure paths or I/O. Copy keeps the existing bounded clipboard helper and structured event; reply keeps the current callback.

## Testing strategy

Write the natural-height and compact-target assertions first, run the focused shared tests red, then implement. Native graphics with forced 320dp/412dp viewports covers short/two-line/tall messages, an exact 96dp typography-controlled boundary, compact rewrap, attachments, visible timestamp and streaming. Real pointer taps cover shared and outer target edges for consecutive User/User and Assistant/Assistant messages and compare exact owning sources and IDs. Assert surface containment, screen containment, target separation, unchanged tall geometry, content-plus-padding height and 16dp visible gaps.

Add short user/assistant newest-message regressions to `BackgroundAgentRestGapTest`; keep its 12dp expectation. Run affected bubble, metadata, selection, attachment, palette and side-copy tests. Compile shared tests for androidTest, lint, assemble, format, and final pre-verify after merging main. Existing live/scripted copy/reply scenarios remain the operator-flow coverage; dispatcher owns their full execution. No new device-only test or backend behavior.

## Open Questions

None.

## Documentation handoff

Pending for documentation stage: update `docs/knowledge/features/message-bubble.md`, side-action geometry, and `docs/knowledge/features/message-bubble-testing.md`, Testing, replacing the 96dp minimum with compact/stacked selection and adjacent-message pointer coverage.

Sizing: approximately 600 written lines including plan/tests/deletions, zero new exported types, zero consumer API updates, four acceptance criteria and zero new error branches; within the ticket limits.

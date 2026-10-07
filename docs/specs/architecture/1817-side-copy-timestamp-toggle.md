# #1817 — Side copy and timestamp-only toggle

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer`, role bodies and `MetaRowControl` share bubble geometry and non-merging nested gestures.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt`: `MessageMetaRow`, `CopyTextControl` and `setBoundedText` own localized metadata and bounded clipboard writes.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: fenced code's `CopyTextControl` remains unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt`: consumes the existing `MessageRoleInset`; keep its 100dp value for queued rows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: screen-hoisted message selection already excludes streaming timestamp toggles.
- `app/src/main/res/values/strings.xml`: bubble action labels become Show time / Hide time.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRowToggleTest.kt`: selection, nested gestures and accessibility regressions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: role geometry, copy and streaming coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageAttachmentsTest.kt`: attachment geometry follows the new bubble lane.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameCaptureTest.kt`: compact text and frame captures.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: extend the existing ping method with real platform clipboard assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: held streaming fixture provides deterministic copy proof.
- `docs/knowledge/features/message-bubble.md`: retain selection boundaries, short-message hug, localized timestamps and clipboard bounds; metadata must not introduce a fill-width constraint.
- `docs/knowledge/features/thread-screen.md`, `docs/knowledge/features/development-verification-gates.md`, `docs/e2e-interactive-stream.md`: screen ownership, forced test widths, native font measurement and live/scripted harness contracts.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1577

Read design context and screenshot, plus role containers `620:1775` / `620:1777` and shared Message Actions `808:12242`. Copy uses existing `ic_copy` at 11×12dp with `MaterialTheme.colorScheme.inversePrimary` on the thread background; the action column is 13dp wide, 12dp from the bubble and stretches to its height. The screenshot shows assistant actions on the right and user actions on the left, with 20dp gutters and 40dp opposite insets; reply is deferred to #1818, so copy alone is vertically centered here.

## Context

#1621 hid copy with the timestamp. Copy now stays available for every user and assistant message, including the current streaming content. This is one presentation/action deliverable with five acceptance criteria, estimated at roughly 650–800 written lines including tests and this plan, no new public type or composable, fewer than ten coordinated callers and no new reject branches. No dependency or decision record is needed.

#1827 / PR #1836 has merged; its attributed-reply changes are in this checkout. Overlapping branches #1682, #1689, #1690, #1691, #1693, #1695, #1766 and #1833 touch the shared live-test files (and #1766 streaming rendering); this ticket changes the ping/stream assertions and container locally without restructuring their blocks.

## Design

Keep `MessageBubble`'s host contract and timestamp-selection wiring. `MessageContainer` measures the bubble against the full content width minus the 40dp far-side inset, 12dp gap and 13dp column: at 412dp the bubble maximum is 307dp, at 320dp it is 215dp. Finished short bodies continue to hug content and streaming bodies retain their existing fill/reveal behavior. Preserve the existing 100dp `MessageRoleInset` for queued consumers; a separate private inset serves delivered bubbles.

Use a local custom layout to measure the bubble first, stretch the action column to its height, and place both at the role's edge. The column contains a centered 48×48dp copy target whose overflow is neither clipped nor included in bubble measurement. It is placed after the bubble so overlapping touch pixels route to copy. Its icon remains 11×12dp; tests measure column, glyph and target separately. `setBoundedText(message.content)` copies the latest source without touching timestamp selection. The separate side button reports its label and Button role; remove the bubble custom copy action while retaining its hidden-timestamp description and time-only toggle.

`MessageMetaRow` becomes only localized `bodySmall` timestamp text, with no copy reservation. Keep the existing metadata contrast treatment, code-block `CopyTextControl` behavior, text selection and attachment/link gestures. Guard timestamp visibility and toggling for streaming messages in the container as well as the screen.

## State and concurrency model

No new state, flow, job or dispatcher. Existing screen selection remains authoritative and existing streaming producers retain their cancellation paths. Compose reads the current message for copy, so appended source is copied even before progressive display catches up.

## Error handling

No new failure mode or I/O contract. Clipboard writes use the existing 100,000-character bound. Logs for copy and timestamp-toggle lifecycle events contain static event names only, never message content or identity.

## Testing strategy

Write changed/new shared-test assertions first and observe failures before production edits. Update timestamp-only visibility, independent copy accessibility, hidden and shown copy behavior, streaming completion and nested targets. Measure exact 412dp and 320dp geometry, short-message hug, 13dp column and 11×12dp glyph; pointer-test all edges of the 48dp target and overlap into the bubble, in light and dark schemes. Cover markdown source, streaming appends and clipboard bounds.

Run the affected shared classes, attachment and selection regressions, markdown copy coverage and existing thread layout/interaction coverage. Update `ThreadFrameCaptureTest` compact-width assertions and run its affected method on the device for real pixels. Extend `InteractiveStreamE2ETest.interactiveTurn_pingPrompt_streamsPingReplyIntoThread` to compare sent-user and received-assistant side copy to repository source with timestamps hidden. Extend the rung-4 held `stream` scenario to prove copy during streaming and after completion; run `scripts/android-test-gate.py scripted stream`. Device tests are needed here for real platform clipboard and daemon/relay traffic, not basic Compose geometry.

Run lint, assembly, Android test compilation and forced Spotless checks. Push before final whole unit/shared suite, assembly and `scripts/pre-verify.py --gradle`. Dispatcher owns the fresh full live-suite execution after verification; its evidence must name the ping method and executed/failed/skipped counts.

## Open Questions

None. #1818 owns reply and the midpoint split between its overlapping targets.

## Documentation handoff

Pending for documentation stage:
- `app/src/androidTest/assets/design-1220/README.md`, #1621 row: timestamp alone hides until tap; copy always beside the bubble, citing #1817.
- `docs/knowledge/features/message-bubble.md`: side copy placement, streaming availability and timestamp-only toggle.
- Record fresh dispatcher full live-suite evidence for the named ping method, including executed, failed and skipped counts.

## Revisions

- 2026-10-07: Shared tests exposed metadata assumptions in attachment, selection and palette fixtures. Those assertions now identify timestamp text directly, while side-copy geometry and tint are checked separately. Added `SideMessageCopy.kt` as a shared device assertion for live ping and held streaming, and a non-merging `message-row` tag to scope copy to its own source. Forced-size geometry is measured inside the configured viewport, avoiding the outer Robolectric window's different density. The behavior and state contracts are unchanged.

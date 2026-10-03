# #1621 — Hide a message's meta row until the message is tapped

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` — `MessageBubble`, `MessageContainer` (places `MessageMetaRow` under the body), `UserMessageBubble`, `AssistantMessage`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt` — `MessageMetaRow`, `CopyTextControl` (the bounded clipboard write), `formatShortDateTime`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` — links are `LinkAnnotation.Url` spans and the code block copy is a `CopyTextControl` (its own `clickable`), so both consume their own taps before a parent sees them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — the `itemsIndexed` body that calls `MessageBubble` for `ThreadItem.MessageItem`.
- `app/src/sharedTest/.../components/MessageBubbleTest.kt`, `MessageBubblePaletteTest.kt` — mount `MessageBubble` directly and expect the meta row (palette test counts three timestamps, one of them streaming).
- `app/src/sharedTest/.../thread/ThreadFrameCaptureTest.kt` — `compactWidthAndEnlargedText_keepFrameControlsReachable` taps the copy control in the real thread.
- `docs/knowledge/features/message-bubble.md` — lesson carried: `MessageBubble` was pure rendering; keep the new state out of it and hoist it to the host.

Overlap: #1635 also edits `ThreadScreen.kt` (tool-run folding beside `rows`) and `strings.xml`; edits here stay additive and local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4446

The `Message` component's `Meta row` (`132:4446`, user instance `132:4435`) is unchanged: `bodySmall` timestamp and the 11×12 copy glyph, 8dp apart, at 0.8 of the bubble's content colour. Per the ticket (Juhana, 2026-10-03) there is no new frame; the app draws the row only for the one tapped message. That visibility rule is the single deliberate difference from the frame, which always draws it.

## Context

Every bubble spends a meta row of height on each message. The ticket hides it until the bubble is tapped, one message at a time, and keeps it hidden while a reply streams. No decision record needed; the ticket is the reference.

## Design

`MessageBubble` gains two parameters, forwarded to `MessageContainer`:

- `metaRowVisible: Boolean = true` — default keeps every existing host (previews, component tests, captures) drawing the row as before.
- `onToggleMetaRow: (() -> Unit)? = null` — `null` means the bubble has no tap gesture.

`MessageContainer`:

- Draws `MessageMetaRow` only when `metaRowVisible`. Column spacing collapses with it, so a hidden row costs no height.
- When `onToggleMetaRow != null`, the bubble `Surface` gets a `pointerInput` tap detector (`detectTapGestures(onTap)`), not `clickable`. `clickable` would merge descendants into one semantics node, which changes TalkBack granularity and breaks the existing text-bounds tests. A child that handles its own tap (link span, attachment, code block copy) consumes the down event, so `detectTapGestures` never sees it — AC3 without special-casing.
- Accessibility on the bubble `Surface` via `semantics` (non-merging):
  - `onClick(label = show/hide string) { onToggleMetaRow(); true }` when a toggle is supplied.
  - `customActions = [CustomAccessibilityAction(cd_thread_copy_message) { copy }]` whenever the row is hidden, copying the same bounded `message.content` as the meta row's button.
  - `contentDescription = "Sent <formatted timestamp>"` whenever the row is hidden, so the timestamp is reachable.

`MessageMetaRow.kt`: extract the clipboard write from `CopyTextControl` into `internal fun ClipboardManager.setBoundedText(text: String)` and the remembered formatting into `@Composable internal fun rememberFormattedTimestamp(timestamp: Instant): String`, both reused by `MessageContainer` so the a11y copy and the timestamp cannot drift from the button's.

`ThreadScreen`: `var metaRowMessageId by rememberSaveable { mutableStateOf<String?>(null) }` beside `rows`. Per `MessageItem`:

- `metaRowVisible = !message.isStreaming && metaRowMessageId == message.id`
- `onToggleMetaRow = if (message.isStreaming) null else { toggle(message.id) }`, where toggle sets the id or clears it if it is already the one shown. Setting a different id hides the first: at most one row (AC1). A streaming bubble has no gesture and never shows the row; the first tap after `isStreaming` flips false reveals it (AC2).

New strings: `cd_thread_message_sent` ("Sent %1$s"), `thread_message_show_details` ("Show time and copy"), `thread_message_hide_details` ("Hide time and copy").

## State and concurrency model

One `rememberSaveable` `String?` in `ThreadScreen`, UI-local; no ViewModel, flow or coroutine changes. Survives rotation; reset on leaving the screen.

## Error handling

None new. The clipboard write keeps `MAX_CLIPBOARD_CHARS`.

## Testing strategy

New `app/src/sharedTest/.../components/MessageMetaRowToggleTest.kt` (Robolectric), hosting `MessageBubble`s with a local `remember` id that mirrors ThreadScreen's rule, and also mounting `ThreadScreen` for the real wiring:

1. AC1 — in `ThreadScreen` with a user and an assistant message: no copy control / timestamp; tap a bubble shows one; tap again hides; tap the other moves it (count stays ≤ 1).
2. AC2 — streaming assistant message: tap shows nothing; flip to finished; first tap shows the row.
3. AC3 — tapping a markdown link and the code block copy does not reveal the row; code block copy node is present while the row is hidden.
4. AC4 — hidden row: the bubble node carries the `cd_thread_copy_message` custom action, invoking it writes `message.content` to a recording clipboard, and the bubble's content description contains the formatted timestamp.

Existing: `MessageBubbleTest`, `MessageBubblePaletteTest`, `MarkdownLinkTapTest`, `MessageAttachmentsTest` stay unchanged on the `true` default. `ThreadFrameCaptureTest.compactWidthAndEnlargedText_keepFrameControlsReachable` taps the bubble first to reveal the row, then keeps its copy pointer assertion. Attachment tap no-toggle is covered by structure (attachments are `combinedClickable`); not separately tested.

Not operator-facing in the rung-3 sense (no daemon interaction), so no real-Claude scenario.

## Documentation handoff

Pending for the documentation stage: add a row to the `### Messages and tools` table in `app/src/androidTest/assets/design-1220/README.md` recording the hidden-until-tap meta row against `132:4446`, `132:4435`, status `no separate frame`, decision on #1621.

## Open Questions

- Does `detectTapGestures` on the `Surface` see taps that land on non-link markdown text? Expected yes (a `LinkAnnotation` only consumes on its span); test 1 taps body text to prove it.

## Revisions

### 2026-10-03 — rework after review (MUST FIX on AC3)

- **Non-actionable attachments swallow a tap.** The plan's claim that attachments are `combinedClickable` held only for `Ready` and deferred ones. In `MessageAttachmentItem`, the Loading, NotFound and Failed states had no pointer handler, so their tap reached the bubble's detector and toggled the row. The `else` branch of `actions` now gives them `pointerInput { detectTapGestures() }`: a tap is consumed and does nothing, and no click semantics are added, so TalkBack announces no dead button. The Failed row's Retry button still consumes its own tap first.
- **Streaming bubbles carry no copy action.** The copy custom action is now set only when the row is hidden and the bubble has a toggle, so a streaming reply cannot copy partial text. The "Sent …" description still applies whenever the row is hidden.
- **Test host.** `MessageMetaRowToggleTest` mounts only `ThreadScreen` (the real wiring) rather than also a local `remember` host. It adds a case that taps a loading image, a not-found file and a failed file and asserts no row appears, and a case that drives the bubble's `SemanticsActions.OnClick`, checking its show/hide label and that it toggles the row.
- **Open question resolved.** A tap on non-link markdown text reaches the bubble's detector; the AC1 test taps body text.

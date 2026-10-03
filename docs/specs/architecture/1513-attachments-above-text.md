# #1513 — Message bubble draws its attachments above its text

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` — `MessageContainer` (draws `body()`, then the attachments slot, then `MessageMetaRow`) and its KDoc; `Message.hasNoBody`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageAttachmentsTest.kt` — the #984 bubble tests the new order test sits beside.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameCaptureTest.kt` — the emulator frame captures at 412 × 892, dark; the capture for `16:8` is added here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread frame's user photo bubble (`I533:1956;132:4567`) and assistant PDF bubble (`I533:1956;132:4608`) carry the shared `Message` component's `Slot`: a 160 × 160 image with rounded corners, or the `File field` row. The frame places that slot between two paragraphs, which `Message` (one `content`, unpositioned `attachments`) cannot express; the decision on the issue is attachments above the text for both roles. Size, corner radius, colours and spacing are unchanged. The frame's 222 px photo bubble width is out of scope, per the issue.

## Change

In `MessageContainer`, the attachments slot moves ahead of `body()`: attachments, then text, then the meta row, for both roles, since both render through this one container. The `BubbleContentSpacing` column spacing still separates the three children, so an attachment-only message (`hasNoBody`, empty body) and a text-only message (no slot) lay out as before. The KDoc sentence that places the slot "between the body and the meta row" is updated to say it comes before the body. Nothing else moves: the slot's content, its guard on `message.attachments.isNotEmpty()` and the meta row's copy text are untouched.

## Testing strategy

- `MessageAttachmentsTest` gains one test, run for a user and an assistant message each with text and an image attachment: the image's top is above the text's top, and the text's bottom is above the copy button's top. It fails on the current order.
- The existing `MessageAttachmentsTest` methods cover the attachment-only and file-row layouts.
- `ThreadFrameCaptureTest` gains a method that shows a user photo message and an assistant PDF message at 412 × 892 and captures the frame on the managed device; `scripts/design-compare.py` compares it with the `16:8` export, and the remaining differences go on the PR.

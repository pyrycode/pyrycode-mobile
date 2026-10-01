# #1328 — Attached files need message text to send

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendMessage`, `sendWithAttachments` — the blank-text refusal sits below the attachments branch.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar` — `stopping` and `buttonEnabled` both read `hasAttachments`; the IME action calls `onSend` unconditionally, so the view model's refusal is what makes it send nothing.
- `app/src/test/.../ThreadViewModelAttachmentTest.kt`, `ThreadViewModelAttachmentRetrievalTest.kt` — send pending files with `""`; switch those to carry text.
- `app/src/sharedTest/.../ThreadInputBarStyleTest.kt` → `pendingAttachmentSendsWhileBusy`, `sendingAttachmentDisablesDuplicateTap`; `ComposerAttachmentStripTest` → `attachmentsWithBlankText_enableSend_andSendingTheBlankDraft` — assert the old contract.
- The e2e attachment scenario (`interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`) already sends with `PING_PROMPT`; unaffected.

Overlap: `feature/1305` edits `ThreadViewModel.kt` (question modal, different block) and `feature/1326` edits the attachment view-model tests; neither is a dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 — behaviour only; the disabled Send state already exists, no visual change.

## Change

Match desktop's `submitMessage`: in `sendMessage`, `text.isBlank()` returns before the attachments are read, so pending files stay in the draft store for the next send. In `ThreadInputBar`, `stopping` becomes `isBusy && text.isBlank()` and `buttonEnabled` becomes `stopping || (!sending && text.isNotBlank())`; `hasAttachments` is then unread, so the parameter goes, with its one production call site in `ThreadScreen` and the style test's `attachments` argument. The KDoc lines that say attachments alone are enough to send are corrected. `sendWithAttachments` KDoc drops "Blank text is allowed here".

## Testing strategy

- `ThreadViewModelAttachmentTest`: replace `send_withBlankTextAndAttachments_isSent` with a test that a blank send with a pending file sends nothing, uploads nothing and keeps the file, then a send with text carries the file. Existing blank-text sends in the other tests take a non-blank text.
- `ThreadInputBarStyleTest`: busy + attachments + blank shows Stop (not Send); typed text shows enabled Send.
- `ComposerAttachmentStripTest`: attachments with blank text keep Send disabled; typing text enables Send and sends it. The IME action still calls `onSend` with the blank draft, and the view-model test proves that send does nothing.

## Revisions

- The `ThreadInputBarStyleTest` busy + attachments + blank-shows-Stop bullet above was not implemented, and could not be as specified: this plan's own Change also drops `hasAttachments` from `ThreadInputBar`, so nothing reaching that composable can distinguish "blank, no attachment" from "blank, with attachment" any more — both show Stop while busy whenever text is blank, by construction of `stopping = isBusy && text.isBlank()`. AC2 ("pending files no longer change whether a blank composer shows Send or Stop") is therefore structural at this composable, not something a `ThreadInputBarStyleTest` case can additionally demonstrate. A `ComposerAttachmentStripTest` case one level up (busy screen + attachment + blank draft shows "Stop the running turn") would still be able to exercise this, but was not added in this ticket; it remains open if a future ticket wants the coverage explicit.

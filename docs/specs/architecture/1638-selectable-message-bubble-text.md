# #1638 — Selectable message bubble text

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer`, where both roles call `body()`, and `UserMessageBubble`, whose body emits one `Text` per paragraph straight into the container's `Column`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: links are `LinkAnnotation.Url` spans built with `withLink`, and `CodeBlock`'s copy is `CopyTextControl`, a `clickable`. Both keep working inside a `SelectionContainer`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRow.kt`: `CopyTextControl` and `MAX_CLIPBOARD_CHARS`. This ticket does not change them.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt`: the `SelectionContainer` around the fingerprint, which is the analogue.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: the paragraph-gap and hug guards, which must stay green, and the place for the new test.
- `docs/knowledge/features/message-bubble.md`: the hug lesson. A wrapper must not add `fillMaxWidth()`, or the finished bubble stops shrink-wrapping its content.

Overlap: #1621 (in review) also edits `MessageContainer`, adding a `pointerInput` tap on the `Surface` and making the meta row conditional. This change wraps only the `body()` call, so the two edits are additive. Whichever ticket lands second checks that a tap still toggles the meta row.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=114-3558

There is no separate frame. The selection handles and the Copy toolbar are system UI. At rest the assistant bubble (`114:3558`) and the user bubble (`114:3559`) look the same as before, with no selected state drawn. The visual check is skipped on purpose.

## Change

In `MessageContainer`, the `body()` call is wrapped in `SelectionContainer` unless `message.isStreaming` is true. Inside the wrapper there is a `Column(verticalArrangement = Arrangement.spacedBy(BubbleContentSpacing))`. It is needed because `SelectionContainer` lays its children on top of each other, which would stack the user body's paragraph `Text`s. The column keeps the existing 12dp paragraph gap and takes no width modifier, so the hug is unchanged.

Attachments and the meta row stay outside the wrapper. An attachment's long press still saves the file. The meta row's copy button still copies the whole bounded source. Each bubble has its own `SelectionContainer`, so a selection cannot cross into another bubble. A streaming reply stays unselectable, so a selection does not move while text arrives. The five inert daemon-text rows are not touched. The KDoc on `MessageContainer` notes that the system Copy action does not apply `MAX_CLIPBOARD_CHARS`.

## Testing strategy

A new test goes in `MessageBubbleTest` (`app/src/sharedTest`, Robolectric). It provides a fake `TextToolbar` through `LocalTextToolbar`, which records the `onCopyRequested` callback. The test long-presses one word of a finished assistant body that contains a fenced code block, and it does the same for a user body. It invokes the recorded Copy action and reads the platform clipboard. The clipboard must hold the selected text: non-empty, part of the message, and shorter than `message.content`. A second test long-presses a streaming bubble. It asserts that no toolbar is shown and nothing reaches the clipboard. The existing `MessageBubbleTest`, `MarkdownLinkTapTest`, `MarkdownTextTest`, `MessageAttachmentsTest` and `MessageBubblePaletteTest` are rerun unchanged. Together they cover links, the code block copy, attachments, the meta row copy, the paragraph gap and the hug.

## Revisions

### 2026-10-03: the selection test moved to its own class and fakes the new context-menu toolbar

- **What changed:** the new tests live in `MessageBubbleSelectionTest`, beside `MessageBubbleTest`, under `@GraphicsMode(NATIVE)`. A Robolectric `@Config` shadow turns the platform `Magnifier` into a no-op. The fake toolbar is a `TextContextMenuProvider` supplied through `LocalTextContextMenuToolbarProvider`, not a `TextToolbar` supplied through `LocalTextToolbar`. Copy is pressed through the open menu's `TextContextMenuKeys.CopyKey` item.
- **What drove it:** under Robolectric's legacy graphics, the selection handles' vector cache cannot allocate a bitmap. On the native canvas, dismissing the platform magnifier hits a null surface. Moving the existing class to native graphics would have changed the mode its geometry guards run under. Compose foundation 1.10.4 enables `ComposeFoundationFlags.isNewContextMenuEnabled` by default, so `SelectionContainer` no longer calls `LocalTextToolbar`.
- **New contract:** the production change is the same. The assertions are those in the Testing strategy: a long press on a finished bubble opens a toolbar whose Copy puts only the pressed word on the platform clipboard, for prose, a fenced code block and a user paragraph. A long press on a streaming bubble opens no toolbar. A device run ignores the shadow and the graphics mode.

### 2026-10-03: a message with no body skips the wrapper (verifier rework)

- **What changed:** `MessageContainer` takes the plain `body()` path when `message.hasNoBody()` is true, as well as when the message is streaming. `MessageAttachmentsTest` gains a geometry guard that the attachments of an attachment-only bubble sit one `BubbleContentSpacing` above the meta row. `MessageBubbleSelectionTest` gains a test that the code block's copy button inside a finished bubble still copies the whole block.
- **What drove it:** the verifier's MUST FIX on PR #1658. An attachment-only message's `body()` emits nothing, but the `SelectionContainer` around it is still a zero-height child of the bubble column, which spaced both sides of it and doubled the gap above the meta row to 24dp. The code-block test closes the verifier's NIT that no test pressed that button through the bubble.
- **New contract:** the wrapper is added only when there is selectable text: a finished message that is not body-less. The language-label NIT (a select-all on a fenced block copies the label) is left as is; fixing it needs `DisableSelection` in `MarkdownText.kt`, outside this ticket's one-file change.

### 2026-10-03: real-thread selection coverage follow-up (verifier rework)

- **What changed:** operator-flow coverage is explicitly tracked in [#1674](https://github.com/pyrycode/pyrycode-mobile/issues/1674), following #481/#482. It requires one rung-3 `InteractiveStreamE2ETest` scenario that selects a word in a finished real-Claude reply through the actual Android Copy menu and reads the platform clipboard, plus a rung-4 deterministic twin.
- **What drove it:** the latest verifier MUST FIX on PR #1658. A local gesture still requires operator-flow coverage; the recording context-menu provider in `MessageBubbleSelectionTest` proves the component contract but does not exercise the real thread/system-menu path.
- **New contract:** the implementation and shared tests remain unchanged. #1674 owns the device scenarios and dispatcher-owned live evidence after #1638 merges; this PR does not claim that live selection has passed. #1621 is now merged, and `selectableBodies_keepTheMetaRowTap_andLongPressDoesNotToggleIt` verifies both roles with actual pointer gestures. The original documentation handoff was completed by the documentation stage in `2722ce33`.

## Documentation handoff

- Completed by the documentation stage in `2722ce33`: `app/src/androidTest/assets/design-1220/README.md` has the `no separate frame` row for #1638, in the same shape as the #1578 row.
- Pending for the documentation stage: carry the [#1674](https://github.com/pyrycode/pyrycode-mobile/issues/1674) real-thread/system-menu coverage follow-up into `docs/knowledge/features/message-bubble.md`, section "Body selection (since #1638)". Scenario implementation, harness documentation and live evidence belong to #1674.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. The bubble body is daemon-authored (assistant) or user-authored text. It already renders as `Text`/`MarkdownText` and is already copyable in full through `MessageMetaRow`'s `CopyTextControl`. Selection exposes a subset of the same `Message.content`, and the text stays text. The five inert rows (`UnrecognizedMessageRow`, `ThreadPermissionModal`, `BannerNoticeRow`, `StoppedTurnRow`, `ModelRefusalRow`) keep their no-`SelectionContainer` contract, because the wrap is in `MessageContainer` only.
- [Tokens] No findings. Bubble bodies carry no tokens or keys, and the pairing fingerprint is a different screen.
- [Files and storage] Not applicable. No paths and no storage are involved. The clipboard is the only sink, and it is the system clipboard the meta row already writes.
- [Android attack surface] No findings. No component, intent, WebView or deep link is involved. `LinkAnnotation.Url` links keep their existing handler, and the wrap adds none.
- [Cryptography] Not applicable. No crypto code is touched.
- [Network and I/O] SHOULD FIX (documentation). The system Copy action writes the selection without the `MAX_CLIPBOARD_CHARS` bound that `CopyTextControl` applies. A select-all on a very long assembled reply could overflow the Binder clipboard transaction. The ticket accepts this for a selection the user chose. Inbound frames are capped at `MAX_INBOUND_FRAME_CHARS`, and the pre-existing unbounded assembled content belongs at the decode layer, as #644's review recorded. Phase B states the gap in the `MessageContainer` KDoc.
- [Errors, logs and telemetry] No findings. Nothing new is logged, and selected text never reaches a log.
- [Concurrency] No findings. `SelectionContainer` keeps its own composition-scoped selection state, and the wrap adds no coroutine, flow or scope. The streaming arm is excluded, so a selection never holds offsets into text that is still growing.
- [Threat model] A hostile daemon frame still renders as text only, and the one new effect is that part of it can be copied, which the whole-message copy already allowed. On UI leakage, the clipboard is readable by the IME and by the foreground app, as it already is for the meta row copy. Nothing on this path is a secret.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-03

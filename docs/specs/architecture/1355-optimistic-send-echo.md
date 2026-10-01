# #1355 — Show my message the moment I tap Send, trimmed as desktop sends it

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` — `sendMessage(conversationId, text, attachments)`: the message is built, recorded and minted only after `requests.sendAndAwaitReply`. The three writes move ahead of the await.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` — KDoc on `mintedMessageIds` and `recordMinted` says the id is recorded after the ack; KDoc only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `sendMessage` (untrimmed text, draft cleared after the send returns, #789) and `sendWithAttachments` (draft cleared after the send).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` — `foldQueuedRows` already correlates a queued item with its echo by message id only; unchanged.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` — the `sendMessage_*` tests that assert "projections unchanged" on failure flip; the `dropQueuedMessage_*` tests prove the drop still removes the echo.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`, `ThreadViewModelAttachmentTest.kt` — the #789 draft tests.
- Desktop rule: `submitMessage` in `src/renderer/src/screens/conversation/composerSend.ts`.

## Design source

Figma node 16-8 (Conversation Thread Screen, Message area). No visual change: the existing user bubble appears earlier. No visual check needed beyond existing coverage.

## Context

Mobile draws the operator's own message only after the daemon's ack, and keeps the text in the composer until then; a slow or refused send looks ignored. Desktop trims, mints one id, sends and draws the echo regardless of outcome, and clears the composer at once. Mobile copies that. This drops #789's restore-on-failure, which desktop has no equivalent for.

## Change

**`MessageCommands.sendMessage`.** Build the `Message` (same id, timestamp, cleaned attachment references) before the await, then `conversationList.recordLastMessage`, `threadProjection.appendMessages` and `threadProjection.recordMinted` with that object, then `requests.sendAndAwaitReply(request)`, then return the message. The await stays, so a refusal still throws to the caller; the echo stays in place. The too-many-attachments refusal still runs first and draws nothing. A minted id from a failed send stays in the ledger; no queued item carries it, so it can never remove a row. The ack appends nothing, so no second bubble; the queue fold keys on message id, so a message sent during a running turn renders once as its queued row, and a confirmed drop still removes it through `settleDrops`.

**`ThreadViewModel.sendMessage`.** `val trimmed = text.trim()`, then the blank check on `trimmed` (whitespace-only sends nothing and leaves the draft). Text path: `onDraftChange("")` synchronously, then launch the guarded send of `trimmed`. A failed send no longer restores the text. Attachment path: `sendWithAttachments(trimmed, text, attachments)` — sends `trimmed`, and after every upload succeeds, before the send, clears the draft only if the store still equals the untrimmed `text` as typed (so text typed during the uploads survives), and removes the snapshot's entries. A failed read or upload still returns before any of that, keeping text and every entry. KDoc on both is rewritten to the new contract.

**`ThreadProjection`.** KDoc on `mintedMessageIds` and `recordMinted`: recorded when the send is issued, not after the ack; a failed send's id is harmless.

`onComposerCommand` already sends through `repository.sendMessage`, so it echoes the same way with no change.

Overlapping in-flight branches on `ThreadViewModel.sendMessage`/`sendWithAttachments`: #1311 (wraps the send call) and #1348 (moves the attachment clear into a callback). Edits here stay local to those two functions.

## Testing strategy

Unit tests, all under `app/src/test/`:

- `RemoteConversationRepositoryTest`: a send with no reply yet has drawn the thread row and last message, and the `send_message` frame's `message_id` equals the row id (AC1). The existing server-error / not-found / not-connected tests flip to "echo stays, exception still thrown" (AC3 at the data layer). After the ack the thread still holds one row with that id. A send whose queue_state arrives before the ack: thread holds one row with that id, and a confirmed drop removes it (AC2). `foldQueuedRows` correlation is already covered by `ThreadRowsTest`.
- `ThreadViewModelTest`: `sendMessage("  hi \n")` against a repository whose `sendMessage` never returns — the repository received "hi" and the draft is already empty (AC1); whitespace-only leaves the draft; an Actions menu command reaches `repository.sendMessage` (existing coverage). The #789 refused-send test flips: text is not restored (AC3).
- `ThreadViewModelAttachmentTest`: after uploads succeed and with a never-completing send, the draft text and entries are cleared and the send carries the trimmed text and the uploaded references (AC4); failed read/upload keeps text and entries (existing).

AC5 (live suite) is the dispatcher's post-verifier `live` run; the two named `InteractiveStreamE2ETest` methods already exist, so no new rung-3 scenario is added.

## Documentation handoff

Pending for the documentation stage: the composer send section of `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md` — optimistic echo, trim, and the dropped restore-on-failure from #789.

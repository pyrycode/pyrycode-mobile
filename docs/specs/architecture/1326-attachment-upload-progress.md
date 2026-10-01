# #1326 — Report attachment upload progress per chunk

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.uploadAttachment` — gains the optional `onProgress` parameter, default no-op.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `MessageCommands.uploadAttachment` — the chunk loop that reports.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `RemoteConversationRepository.uploadAttachment` — passes it through.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `StableConversationRepository.uploadAttachment` — passes it through to the repository live at entry.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryAttachmentTest.kt` — the existing `MessageCommands` upload tests (driven through `RemoteConversationRepository`); new cases go here.
- Test doubles overriding `uploadAttachment` — `StableConversationRepositoryTest`, `ThreadViewModelAttachmentTest`, `ThreadViewModelAttachmentRetrievalTest`: an override must restate the new parameter, so each gains it (no behaviour change).
- Desktop `src/main/transport/attachmentTransfer.ts` → `reportProgress` / `drive` — the reference: report after the chunk reaches the wire and before the yield, nothing once settled.

## Design source

N/A — data layer only; nothing is rendered.

## Change

`ConversationRepository.uploadAttachment` gains a trailing `onProgress: (sentChunks: Int, totalChunks: Int) -> Unit = {}`. `RemoteConversationRepository` and `StableConversationRepository` forward it (Stable does not report on its own no-repository refusals). In `MessageCommands.uploadAttachment`, after a successful `send` and its log line, and before the `yield`, the loop calls `onProgress(index + 1, plan.totalChunks)` unless `transfer.isSettled` — the same flag the loop re-reads, so a report and a chunk stop for one reason, as on desktop. A failed or throwing send fails the transfer and breaks before reporting, so the failed chunk is never reported; a refusal settled between chunks stops both further chunks and further reports. The callback is synchronous and called on the upload's coroutine; it must not throw (documented on the interface — the only caller is ours). The default no-op leaves every existing caller's behaviour and result unchanged. No `ThreadViewModel` wiring here; the composer consumes it in a later ticket.

## Testing strategy

New cases in `RemoteConversationRepositoryAttachmentTest`:

- a 3-chunk upload reports `(1,3)`, `(2,3)`, `(3,3)` in order, each report seen after that chunk was recorded by the pump (the report records `pump.sent.size` at call time).
- a refusal pushed during chunk 2's send and settled at the following yield: reports `(1,3)`, `(2,3)` (chunk 2 did reach the wire) and nothing after.
- a refusal settled *while* a chunk's send is in flight (the inbound collector on an unconfined test dispatcher, so the settle lands inside `send`): that chunk is not reported — the `isSettled` guard.
- a refused send (`sendResult = false`) and a throwing send report nothing.

The existing tests, which pass no callback, prove the default path is unchanged.

## Documentation handoff

None named by the ticket.

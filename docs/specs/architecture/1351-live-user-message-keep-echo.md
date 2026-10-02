# #1351 — Draw another device's messages live and keep my own echo

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_MESSAGE` arm — the live `message` arm this ticket changes.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` → `appendMessages` (upsert via `withMessage`), `appendSessionBoundary` (skip-if-held pattern the new fold mirrors), `observe`.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withMessage` (id-only, role-agnostic upsert), `storedAttachmentReferences` (private today), `withHistoryEntry`'s `TYPE_MESSAGE` arm (user-only attachment references).
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `sendMessage`'s confirmed insert — the held row that carries attachment names and send time; still upserts through `appendMessages`.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `MessagePayloadDto.attachmentIds`, `toMessage`.
- `../pyrycode/docs/protocol-mobile.md` § Application message types, `message` row (pyrycode#2699) — the daemon pushes a role-`user` `message` to every interactive conn, the sender's included; the client de-duplicates its own echo by `message_id`.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → the tests that push live assistant `message` envelopes as thread setup.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Existing user bubble; no visual change. The ticket changes which rows the data layer emits, not how they render.

## Context

Since pyrycode#2699 the daemon pushes each delivered user message live. Today the live arm upserts every `message` through `appendMessages`, so the pushed copy of the phone's own send replaces the confirmed row and loses its attachment names and send time; a peer's attachments are dropped because `toMessage` carries none; and an assistant `message` (v1 / dispatch-leg only, never minted on the v2 path) would draw a row. Desktop draws role `user` only.

Overlap: `feature/1326` also edits `RemoteConversationRepository.kt` (`uploadAttachment` signature); different function, no dependency.

## Change

- `HistoryPageReducer.kt`: `storedAttachmentReferences` becomes `internal` so the live arm reuses the history arm's id filter (shape check, dedup, bound). No behaviour change.
- `ThreadProjection`: new `fun appendLiveMessage(conversationId: String, message: Message)` — one atomic `update`; if the conversation's thread already holds a `ThreadItem.MessageItem` with `message.id` (role-agnostic, the `withMessage` identity), the map is returned unchanged (no re-emit); otherwise the row is appended at the end. `appendMessages` is unchanged, so the send's confirmed insert and `message_chunk` keep their upsert.
- `RemoteConversationRepository.onInbound` `TYPE_MESSAGE`: decode as today; a `Role.User` message takes `attachments = storedAttachmentReferences(dto.attachmentIds)` and goes through `threadProjection.appendLiveMessage`. Any other role draws no thread row. `recordLastMessage` stays as is for every role (the list preview is a separate projection, out of scope).

Race note: if the push arrives before the send's ack, the pushed row is appended first and the confirmed insert's upsert then replaces it with the named, send-timed row — the phone still ends with its own copy.

## Testing strategy

- New `ThreadProjectionTest`: a held row with the same id (the phone's own confirmed echo, with a named attachment) is left unchanged and nothing re-emits; a new id is appended at the end of that conversation only.
- `RemoteConversationRepositoryTest`: a live user `message` with `attachment_ids` for a new id adds one user row carrying those references (invalid-shape ids dropped); a live assistant `message` adds no row. Existing tests that used a live assistant `message` only as a thread row flip the fixture role to `user`; the two upsert tests (backfill-then-live, replay-then-live) now assert the held row is kept.
- Run with `./gradlew testDebugUnitTest --tests` on those two classes plus `HistoryPageReducerTest`.

No operator-facing flow is added (the rendering path is the existing user bubble), so no rung-3 scenario; the dispatcher's live gate covers the stream.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the thread-screen / conversation-repository overview may note that the live `message` arm is user-only and keep-held.

## Revisions

- 2026-10-02 (rework, verifier MUST FIX): `RelayConnectionFactoryTest.destinationBindingsKeepCollidingIdsOnTheirHostAcrossSelectionAndReconnect` also fed a live assistant `message` as its only thread row; its fixture role flips to `user`, as the Testing strategy already did for `RemoteConversationRepositoryTest`. Comment-only follow-ups from the nits: the `threadByConversation` KDoc now separates `appendMessages`'s upsert from `appendLiveMessage`'s keep-held rule, the `MessagePayloadDto` KDoc (`MessagePayload.kt`) names the live arm as a reader of `attachmentIds`, and the replay-then-live test is renamed `…_keepsHeldRowNoDuplicateRow`. The production contract is unchanged; the optional shared DTO-to-`Message` helper is not taken, keeping the shared surface to `storedAttachmentReferences` as designed.

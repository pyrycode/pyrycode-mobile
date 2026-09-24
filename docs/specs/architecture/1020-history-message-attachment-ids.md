# #1020 — History replay keeps a user `message` entry's attachment ids

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withHistoryEntry` (the `TYPE_MESSAGE` and `TYPE_SEND_MESSAGE` arms), `storedAttachmentReferences` — the arm to change and the #983 validation it must reuse unchanged.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `MessagePayloadDto`, `MessagePayloadDto.toMessage`, `SendMessagePayloadDto` — the DTO gains the optional key with `SendMessagePayloadDto`'s name and type; `toMessage` stays as is so the live `message` and `message_chunk` lanes do not change.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → the live `message` decode in `onInbound` — the other `MessagePayloadDto` consumer; it keeps ignoring the new field.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt` → the #983 block and the `messagePayload` helper — the tests to mirror.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`, `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`, `awaitCachedSentAttachmentIds`, `userMessages` — the two e2e methods AC 2 and AC 3 touch.
- `scripts/e2e-emulator.sh` → the LIVE `TEST_TARGET` list and the header counts; `scripts/android-test-gate.py` → `LIVE_MINIMUM`.
- `../pyrycode/docs/protocol-mobile.md` § *A history entry* — "A stored `role: "user"` entry's `payload` carries `attachment_ids` when the turn named any (#2596)": omitted when none, same key and element shape as `send_message`'s, never a path.

## Design source

N/A — data-layer fix. Rendering and retrieval of a reference already exist (#984); the thread gains no new visual.

## Context

pyrycode#2596 (closed) makes the daemon store `attachment_ids` on a `message` history entry with `role: "user"`. The reducer's `message` arm maps `MessagePayloadDto` through `toMessage` and reads no ids, so a replayed user message — the peer's turn, or the phone's own after its cache dropped — never names a file. Only the `send_message` arm reads ids (#983).

## Change

1. `MessagePayloadDto` gains `@SerialName("attachment_ids") val attachmentIds: List<String>? = null`, declared exactly as on `SendMessagePayloadDto`. Optional with a default, so every existing payload (live `message`, `message_chunk`, old history entries) decodes as today; `toMessage` does not read it, so the live lanes are unchanged. KDoc records that only the history reducer reads it, and only on a user row.
2. In `withHistoryEntry`'s `TYPE_MESSAGE` arm, after `toMessage`, a `Role.User` message is copied with `attachments = storedAttachmentReferences(dto.attachmentIds)`; any other role keeps `toMessage`'s empty list whatever the payload says. Same filter (lowercase UUIDv4 shape), dedup (first occurrence) and cap (`MessageAttachmentIds.MAX`) as the `send_message` arm, because it is the same function. `storedAttachmentReferences`'s KDoc widens from "a stored `send_message`" to "a stored user turn".
3. Nothing is logged, matching the rest of the file.

Why nothing else moves: the merge's hint-filling (`withHintsFrom` / twin lookup) keys on message id and attachment id, not on the entry type, so a replayed user `message` row gets its filename from a cached twin or from retrieval exactly as a `send_message` row does.

## e2e (AC 2, AC 3)

- `interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`: remove `@Ignore` and the "Ignored … until #1020" KDoc paragraph; correct the KDoc's "whose `send_message` entry keeps the id" to the `message` entry the host actually logs.
- `interactiveTurn_attachmentsFromPhone_arriveAtPeerWithTheirBytes`: replace `awaitCachedSentAttachmentIds` with the ids read from the peer's one history call for X — a private helper `userMessageAttachmentIds(history): List<List<String>>` returning each user entry's `attachment_ids` (a `message` with `role: "user"` or a `send_message`). Assert exactly one user message, and that it names two distinct ids; then fetch digests as today. `awaitCachedSentAttachmentIds` loses its only caller and is deleted. KDoc drops the #1020 caveat.
- `scripts/e2e-emulator.sh`: append the peer method to the LIVE list with a `#1020` comment; header becomes thirty methods, thirty-two turns; the #1016 comment stops saying it stays out.
- `scripts/android-test-gate.py`: `LIVE_MINIMUM += 1` with a `#1020` comment.

## Testing strategy

Unit, in `HistoryPageReducerTest` (wire-JSON fixtures, a `messagePayload` helper gaining an optional `ids`):
- user `message` with `attachment_ids` → one reference per id in wire order, no hints;
- non-conforming ids dropped, repeats deduplicated, over-bound list capped at 32;
- user `message` without the key, and with `"attachment_ids":null` → exactly today's row (no references);
- assistant `message` carrying `attachment_ids` → no references.

Scoped: `testDebugUnitTest --tests "*HistoryPageReducerTest"` and `*MessagePayloadTest`, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`. The rung-3 methods run in the dispatcher's post-verifier live gate (`needs-real-claude`); they need the real daemon with #2596 and cannot run in a builder session.

## Documentation handoff

Pending for the documentation stage: in `docs/e2e-interactive-stream.md`, record the peer-attachment reload scenario (`interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`) as live and update the curated-list count (thirty methods, thirty-two turns).

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the ids are replayed, client-authored (for a peer's turn, another paired client's) content. They cross into the thread only through `storedAttachmentReferences`, the one existing check: the `isAttachmentIdShape` lowercase-UUIDv4 filter, dedup and the 32 cap, so a hostile entry cannot put a path, markup or an unbounded list into `MessageAttachment`. No filename or MIME comes from the entry; hints come only from the existing twin merge or retrieval.
- [Trust boundaries] No findings — role gating: an assistant (claude-authored) `message` carrying `attachment_ids` yields no references, so claude cannot inject a reference through a stored assistant message; unit-tested. The id is not a capability either way: retrieval re-validates it against the conversation on the daemon (`request_attachment`, confinement).
- [Tokens] No findings — no secrets touched.
- [File / storage] No findings — an id never becomes a local path here; retrieval and save paths are #984's, unchanged.
- [Inter-process] No findings — no Android component, intent or provider changes. The e2e changes are test-only.
- [Crypto] No findings — none touched.
- [Network & I/O] No findings — decode goes through `MobileJson` behind the existing page decode; a malformed `attachment_ids` (non-array, non-string element) throws `SerializationException` and costs one entry via the existing `IllegalArgumentException` drop, never the page. The envelope cap already bounds the raw list size before decode.
- [Logs] No findings — nothing logged; ids are not logged anywhere new.
- [Concurrency] No findings — pure function, no new coroutine or shared state.
- [Threat model] No findings — hostile daemon frame: covered by the shape filter and one-entry drop. Malicious relay: content-blind, unchanged.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

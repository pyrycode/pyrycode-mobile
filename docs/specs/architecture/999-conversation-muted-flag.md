# #999 — Conversation rows carry the muted flag

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` → `ConversationSummaryDto`, `toConversation` — the list-row decode; gains `is_muted`.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` → `ConversationResponseDto`, `toConversation` — the `conversation_updated` / `conversation_created` decode; gains `is_muted`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation` — gains `muted`, next to `archived`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `upsertConversation` — replaces the whole row with the decoded record, so the record's `muted` already wins on every fold. No change.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `conversationUpdatedEnvelope`, `conversationUpdated_unsolicitedPush_foldsRecordAndLabelInPlace` — the fold-test pattern.

## Design source

N/A — data layer only; no UI.

## Change

Mirror `isArchived` exactly. `ConversationSummaryDto` and `ConversationResponseDto` gain `@SerialName("is_muted") val isMuted: Boolean = false`, and each `toConversation` maps it to a new `Conversation.muted: Boolean = false`. The `false` default covers an older daemon that omits the key and keeps it notifying. `ConversationListProjection.upsertConversation` swaps in the whole decoded `Conversation`, so the record's value is what the projection holds after each fold with no change there; a snapshot likewise replaces every row. Nothing reads `muted` yet — the mute checkbox and alert gate are separate tickets. No wire field is sent by the phone.

## Testing strategy

- `ConversationsPayloadTest`: a row with `"is_muted":true` maps to `muted = true`; the existing fixture without the key maps to `muted = false`.
- `ConversationResponseDtoTest`: `"is_muted":true` → `muted = true`, explicit `false` → `false`, absent → `false`.
- `RemoteConversationRepositoryTest`: `conversationUpdatedEnvelope` gains an `isMuted` parameter (default `false`, always serialized like `is_archived`); a new test folds an unsolicited push with `is_muted:true` over a snapshot row, then a later push with `is_muted:false`, and asserts the projection follows each record.

## Documentation handoff

None named by the ticket.

## Revisions

### 2026-09-24 — the conversation cache carries `muted` (verifier finding on PR #1005)

`isArchived` also round-trips through `FileConversationCache`, which the first Files read list missed. `HostConversationSource` publishes the cached rows on start before the first live list, so a cache that dropped the field would read a muted channel as unmuted until the snapshot arrives, which is the window the #1001 alert gate reads from. `CachedConversation` gains `val muted: Boolean = false`, and `Conversation.toRecord` / `CachedConversation.toDomain` map it both ways, next to `archived`. The `false` default keeps a document written before this change readable. Proof: `FileConversationCacheTest.conversation()` sets `muted = true`, so `metadata round-trips field-for-field through a fresh instance` covers it, and `nullable fields round-trip as null` resets it to `false` beside `archived`.

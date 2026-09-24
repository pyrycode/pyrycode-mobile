# #1000 — send `set_conversation_muted`

## Files read

- `data/network/ArchiveConversationPayloadDto.kt` → `ArchiveConversationPayloadDto` — the encode-only DTO shape the new payload sits beside.
- `data/repository/ConversationCommands.kt` → `sendArchiveToggle`, `rename` — the encode → `RelayRequests.sendAndAwaitReply` → `ConversationResponseDto` decode → `ConversationListProjection.upsertConversation` template.
- `data/repository/RelayRequests.kt` → `mapError` — `conversation.not_found` maps to `IllegalArgumentException`, every other code to `RelayErrorException`.
- `data/repository/ConversationRepository.kt` → `createChannel`, `delete` — throwing interface defaults that keep inline test doubles compiling.
- `data/repository/RemoteConversationRepository.kt` → `archive` hand-off and the `TYPE_*` companion constants.
- `data/repository/StableConversationRepository.kt` → `setSystemPrompt` / `archive` — hand-written forwarding to `live`.
- `data/repository/FakeConversationRepository.kt` → `archive`, `unknown` — the fake's flip-in-place and unknown-id refusal.
- `data/model/Conversation.kt` → `Conversation.muted` (#999).
- `../pyrycode/docs/protocol-mobile.md` § `set_conversation_muted`, § `conversation_updated` — the wire contract; not restated here.
- Tests mirrored: `RemoteConversationRepositorySystemPromptTest` (small sibling pump), `StableConversationRepositoryTest` → `RecordingConversationRepository`, `FakeConversationRepositoryTest` archive tests.

No in-flight feature branch touches these files.

## Design source

N/A — data layer only; the Edit channel checkbox is #1001.

## Change

- New `SetConversationMutedPayloadDto(conversationId: String, muted: Boolean)` in `data/network/`, `@SerialName` `conversation_id` / `muted`, no defaults, so `muted = false` is always encoded.
- `ConversationRepository.setMuted(conversationId: String, muted: Boolean): Unit` with a throwing default (`error(...)`), like `createChannel`.
- `RemoteConversationRepository.TYPE_SET_CONVERSATION_MUTED = "set_conversation_muted"`; `setMuted` hands off to `ConversationCommands.setMuted`, which is `sendArchiveToggle`'s body with the new DTO: send one frame, await the correlated `conversation_updated`, decode through `ConversationResponseDto`, then `upsertConversation`. A failure throws before the fold, so the list keeps its previous value. The uncorrelated push the daemon also sends goes through the existing uncorrelated `conversation_updated` fold; both are upserts by id, so one row remains.
- `StableConversationRepository.setMuted` forwards to `live`. `CachingConversationRepository` delegates with `by` and needs nothing.
- `FakeConversationRepository.setMuted` copies `muted` onto the record in `state`; an unknown id throws `unknown(id)` inside the update, so nothing changes.

**Refusal types.** A daemon refusal surfaces as `RelayErrorException` (e.g. `protocol.malformed`). An unknown id is refused as `IllegalArgumentException`, because the existing `RelayRequests.mapError` maps `conversation.not_found` that way for every verb (archive, rename, set_system_prompt); the fake throws the same type so #1001 tests against the real shape. #1001 should treat both as a failed save.

## Testing strategy

- `SetConversationMutedPayloadDto` encoding: payload is exactly `{conversation_id, muted}` with `muted` present for `true` and `false` (in the remote test, asserting the sent payload for both values).
- New `RemoteConversationRepositoryMuteTest` (sibling pump, as in the system-prompt test): one `set_conversation_muted` frame per call with the exact payload; the correlated reply folds `is_muted` into `observeConversations`; reply + uncorrelated push leave one row with the new value; `protocol.malformed` → `RelayErrorException` and list unchanged; `conversation.not_found` → `IllegalArgumentException` and list unchanged.
- `StableConversationRepositoryTest`: `setMuted` forwards verbatim when live, throws `IllegalStateException` while absent.
- `FakeConversationRepositoryTest`: set then clear flips `muted` on the row; unknown id throws and leaves the list unchanged.

## Documentation handoff

None named by the ticket. Pending for the documentation stage only as routine: the conversation-repository overview may note the new verb.

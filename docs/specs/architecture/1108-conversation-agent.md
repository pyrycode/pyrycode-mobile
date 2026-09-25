# #1108 — read each conversation's agent

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation` — gains `agent`; new `ConversationAgent` enum beside it.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` → `ConversationSummaryDto`, its private `toConversation` — the `conversations` row decode; gains nullable `agent` and the shared wire mapping.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` → `ConversationResponseDto`, `toConversation` — the one DTO for `conversation_created` and `conversation_updated`; gains nullable `agent`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `upsertConversation` — the fold that must keep the stored agent when a record omits it.
- Every `upsertConversation` caller, each of which decodes a `ConversationResponseDto` and upserts its `toConversation()`: `ConversationCommands` (`createDiscussion`, `promote`, archive/unarchive, `setMuted`, rename), `WorkspaceCommands` (change workspace), `SessionSettingsCommands` (set system prompt), `RemoteConversationRepository.onInbound`'s `conversation_updated` arm. Eight sites, all the same shape.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryMuteTest.kt` — the nearest analogue (#1000 added `is_muted` to both DTOs and the domain); its pump and `conversation_updated` fixture are the pattern the new repository test follows.
- `../pyrycode/docs/protocol-mobile.md` § `conversations`, § `conversation_created`, § `multi_agent` — `agent` is `"claude"` | `"codex"`, `omitempty`, present only for a `multi_agent` client.

## Design source

N/A — data-layer only; nothing renders the field yet.

## Context

The daemon reports which agent runs each conversation (pyrycode#2643, #2647, #2669). The thread will use it to offer that agent's models and show a switch. This ticket only reads and stores it. The mobile does not yet negotiate `multi_agent`, so today the key never arrives and every conversation reads Claude; negotiating the capability is out of scope here.

## Design

- `enum class ConversationAgent { Claude, Codex }` in `data/model`, and `Conversation.agent: ConversationAgent = ConversationAgent.Claude` (defaulted, like `muted`, so no constructor call site changes).
- `ConversationSummaryDto.agent: String? = null` and `ConversationResponseDto.agent: String? = null`, kept as the raw wire string so an absent key stays distinguishable from `"claude"` at the fold.
- One mapping, `internal fun conversationAgentOf(wire: String?): ConversationAgent` in `data/network`: exactly `"codex"` → Codex; anything else, including null, → Claude. Both `toConversation` mappers use it.
- `ConversationListProjection.upsertConversation` changes from taking a `Conversation` to taking the decoded `ConversationResponseDto` and returning the `Conversation` it stored. Inside the existing CAS `update`: when the record's `agent` is null and a row with that id exists, the stored row keeps its `agent`; otherwise the record's mapped agent wins. A new id with no agent key inserts as Claude.
  - Why take the DTO rather than a `Conversation` plus a flag: every caller already holds the DTO, and the absent-vs-present rule then lives in one place instead of eight.
  - The return value lets `createDiscussion`, `promote` and rename return the row as stored, so a caller never sees Claude for a Codex conversation the daemon merely omitted the key for.
- Callers collapse `decode → toConversation() → upsertConversation(conversation)` to `upsertConversation(decode(...))`.

## State + concurrency model

Unchanged: the fold is still one atomic `MutableStateFlow.update`; the kept agent is read from the `current` value inside the lambda, so a concurrent snapshot retry-merges. The stored value is captured from the lambda's last (committed) run.

## Error handling

No new failure mode. `agent` is optional and a string of any value decodes; unknown values read Claude. Decode stays the single failure surface. `agent` is not logged anywhere (nothing in these paths logs).

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`):

- `ConversationsPayloadTest`: a row with `"agent":"codex"` reads Codex; `"claude"`, an unknown value and an absent key read Claude.
- `ConversationResponseDtoTest`: a record with `"agent":"codex"` maps to Codex; absent maps to Claude.
- New `RemoteConversationRepositoryAgentTest` (mute-test pump shape):
  - a `conversations` snapshot row carrying `codex` is stored as Codex; a row without the key reads Claude;
  - a `conversation_created` reply carrying `codex` (via `createDiscussion`) stores and returns Codex;
  - an unsolicited `conversation_updated` carrying `codex` switches a Claude row to Codex, and one carrying `claude` switches it back;
  - a `conversation_updated` without `agent` leaves a stored Codex row Codex while its other fields update;
  - a correlated reply without `agent` (via `setMuted`) keeps Codex too — the caller-side upsert path.

No Compose or device test: nothing renders. No rung-3 scenario: not an operator-facing flow.

## Documentation handoff

The ticket names no documentation requirement. Pending for the documentation stage: the conversation-repository overview may record that `upsertConversation` now takes the response DTO and keeps the stored agent on a record without one.

## Open questions

None.

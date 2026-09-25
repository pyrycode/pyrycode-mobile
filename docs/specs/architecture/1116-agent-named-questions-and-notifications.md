# #1116 — Name the conversation's agent in the questions dialog and notifications

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` — the agent from #1108, Claude when the daemon omits it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt` → `QuestionModalState` — gains `agent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the `questionBatch` collector in `init` that builds `QuestionModalState`; `state`, which already reads `repository.observeConversations(ConversationFilter.All)`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt` → `QuestionBatchModal` title.
- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt` → `AttentionNotifier.post`, `List<HostConversationSnapshot>.isMuted` — the host-snapshot lookup the agent lookup mirrors.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttentionNotifier` single, which wires `isMuted` from `HostConversationSource.snapshots`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `observe` filters nulls, so the list emits nothing until the first list response arrives; the agent flow must start from a default or the modal would wait for it.
- `app/src/main/res/values/strings.xml` → `question_modal_title`, `notification_turn_completed`, `notification_prompt`, `notification_channel_attention_description`.

Overlap: feature/1109, 1112, 1113 and 1114 also touch `strings.xml` and (1112–1114) `ThreadViewModel.kt` by adding `ThreadUiState.agent` in `state`. No dependency: this ticket reads the agent inside the question collector, not from `ThreadUiState`. String additions follow their `_codex` twin convention and are appended locally.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369

The gate-modal shell (`MobileGateModal`): a headline title top-left over a divider, scrolling body, Cancel / primary action at the foot. Only the title's text changes, from a fixed Claude title to the conversation's agent; layout, tokens and typography stay as the shell draws them. Notifications have no frame.

## Change

**Dialog.** `QuestionModalState` gains `agent: ConversationAgent = ConversationAgent.Claude`. In `ThreadViewModel`'s question collector, while a batch for this conversation is held, `flatMapLatest` onto `repository.observeConversations(All)` mapped to this conversation's agent (Claude when absent), seeded with Claude via `onStart` and `distinctUntilChanged` so the modal never waits on a cold list. The fold keeps the held picks (`held.copy(agent = …)`) for the same batch and starts fresh for any other, exactly as today. No batch → no list subscription, so a thread without questions issues no extra `list_conversations`. `QuestionBatchModal` picks `question_modal_title` for Claude and the new `question_modal_title_codex` ("Codex has questions") for Codex.

**Notifications.** `AttentionNotifier` gains `agentOf: (serverId, conversationId) -> ConversationAgent?`, wired in `AppModule` from a new `List<HostConversationSnapshot>.agentOf` beside `isMuted` (host first, then channels + chats; null when the host or row is missing). `post` picks the text from kind × agent: Claude → the existing strings unchanged; Codex → `notification_turn_completed_codex` "Codex finished a reply" / `notification_prompt_codex` "Codex is waiting for your answer"; null → `notification_turn_completed_neutral` "A reply finished" / `notification_prompt_neutral` "An answer is needed". `notification_channel_attention_description` becomes "When a reply finishes or an answer is needed". Agent names stay client-owned copy; nothing daemon-authored reaches the notification.

## Testing strategy

- `ThreadViewModelQuestionTest`: a batch in a Codex conversation (repository delegating to `FakeConversationRepository` with `observeConversations` overridden) yields `agent == Codex`, and picks survive the agent arriving after the batch; existing tests cover the Claude default.
- `AttentionNotifierTest`: `agentOf` lookup reads only the alert's own host and returns null for a missing row; a Codex conversation's turn and prompt post the Codex copy; an unknown conversation posts the neutral copy; existing tests (wired with a Claude lookup) keep asserting today's Claude copy; the channel description reads neutrally.
- Title selection is a one-line `when` in the composable; no new screen test.

## Documentation handoff

None named by the ticket.

# #1114 — Name the conversation's agent in the live status screen-reader labels

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` — the #1108 source; Claude when the daemon does not say.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState` — has no agent today; gains one field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the `ThreadUiState(...)` construction in the state `combine` — copies conversation fields (`isPromoted`, `workspaceLabel`) the same way.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `StatusReading` — the only production call sites of the three indicators.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — `cd_thread_tool_running_elapsed`, `cd_thread_tool_running`, `cd_thread_thinking_progress`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ApiRetryIndicator.kt` → `ApiRetryIndicator` — `cd_thread_api_retry`, `cd_thread_api_retry_unknown`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CompactingIndicator.kt` → `CompactingIndicator` — `cd_thread_compacting`.
- `app/src/main/res/values/strings.xml` — the six Claude-naming descriptions.
- `app/src/sharedTest/.../thread/RunningToolIndicatorTest.kt` → the `ThreadScreen` harness the new screen test mirrors.

Overlap: `feature/1109` also appends to `strings.xml` (a different block); siblings #1112/#1113/#1115 may add the same `ThreadUiState.agent` field. Neither is a dependency; a later merge may touch those files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

N/A for visual fidelity — only screen-reader text changes; nothing drawn changes.

## Change

`ThreadUiState` gains `agent: ConversationAgent = ConversationAgent.Claude`, set by `ThreadViewModel` from `conv?.agent` (Claude while the conversation is unknown). `ThreadScreen` passes `state.agent` through `ThreadStatusArea` → `StatusReading` into `ThinkingIndicator`, `ApiRetryIndicator` and `CompactingIndicator`, each of which gains a trailing `agent: ConversationAgent = ConversationAgent.Claude` parameter and picks its description resource with an exhaustive `when (agent)`. Six new `_codex` strings sit beside the originals (`cd_thread_thinking_progress_codex`, `cd_thread_tool_running_codex`, `cd_thread_tool_running_elapsed_codex`, `cd_thread_api_retry_codex`, `cd_thread_api_retry_unknown_codex`, `cd_thread_compacting_codex`) with "Codex" where the originals say "Claude". The Claude strings are not edited, so a Claude conversation's labels read exactly as today and no existing test changes. The agent name is client-owned resource text; no daemon string reaches it. Whole per-agent strings rather than an agent-name format argument keep the Claude strings byte-identical and leave a translator free to inflect. Visible labels and `cd_thread_thinking` ("Agent is thinking") already name no agent and stay.

## Testing strategy

- `app/src/sharedTest/.../components/AgentStatusLabelsTest.kt` (Robolectric): each of the three indicators with `agent = Codex` exposes the Codex description for every arm (tool+elapsed, tool, token reading, retry counter, retry unknown, compacting), and with the default agent exposes the literal Claude text shipped today.
- One `ThreadScreen` case in the same file: a `ThreadUiState(agent = Codex)` with `isCompacting = true` shows the Codex compacting description — proves the screen wiring.
- `ThreadViewModelTest`: a conversation with `agent = Codex` yields `state.agent == Codex`.

## Documentation handoff

None named by the ticket.

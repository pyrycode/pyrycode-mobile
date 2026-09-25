# #1112 — Name the conversation's agent in the reset line and session boundary

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` — the source (#1108); defaults to Claude.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState` — gains the agent.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadViewModel.state` — the combine that already copies `isPromoted` / `workspaceLabel` off the conversation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `StatusReading`, the `ThreadItem.SessionBoundary` arm of the row fold — where the two composables are called.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ResettingIndicator.kt` → `ResettingIndicator`, `resettingLabelRes` — the wrap-up label choice.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` → `SessionBoundaryDelimiter`, `SessionBoundaryDelimiterContent` — the hardcoded explanation sentence.
- `app/src/main/res/values/strings.xml` → `thread_resetting_wrapping_up`.
- Tests that pin today's Claude wording and must keep passing unchanged: `ResettingIndicatorLabelTest`, `SessionBoundaryDelimiterScreenTest`, `ScriptedResettingTest`, `ScriptedSessionBoundaryTest`, `ThreadTopOverlayTest`, `InteractiveStreamE2ETest`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=119-3843

The thread's `Session reset` row: a centred body-small `primary` label between two hairline rules. Layout, tokens and spacing are unchanged; only the agent name inside the wrap-up line and the explanation under the boundary changes.

## Context

`Conversation.agent` (#1108) reaches the data model but nothing in the thread reads it, so a Codex channel's reset line says "Claude is writing a handoff note…" and every boundary says "Claude doesn't remember…".

## Design

- `ThreadUiState.agent: ConversationAgent = ConversationAgent.Claude`, set in `ThreadViewModel.state` from `conv?.agent`, falling back to Claude while the conversation is unknown (the initial value keeps the default).
- `ResettingIndicator(status, modifier, agent = ConversationAgent.Claude)`; `resettingLabelRes(status, agent)` returns a new `thread_resetting_wrapping_up_codex` ("Codex is writing a handoff note for the next session") for a Codex wrap-up and the existing resource otherwise. The restarting labels name no agent and stay shared. Two closed-set resources rather than a `%1$s` format keep `thread_resetting_wrapping_up` a plain string, so every existing `getString(R.string.thread_resetting_wrapping_up)` assertion still resolves to today's text.
- `SessionBoundaryDelimiter(boundary, modifier, agent = ConversationAgent.Claude)` passes the agent to `SessionBoundaryDelimiterContent`, whose sentence becomes `"<Name> doesn't remember messages above this line. Install a memory plugin to preserve context. "` via an internal `agentDisplayName(agent)` (`Claude` / `Codex`) in the same file. The Install affordance is untouched and still under every delimiter.
- `ThreadScreen` passes `state.agent` to `ThreadStatusArea` → `StatusReading` → `ResettingIndicator`, and to `SessionBoundaryDelimiter` in the row fold.

Defaulted parameters keep every other caller (previews, tests) compiling and reading Claude.

## State + concurrency

No new flows or jobs; the agent rides the existing `state` combine.

## Error handling

None new. Unknown or absent wire agents already decode to Claude (`conversationAgentOf`).

## Testing strategy

- `ResettingIndicatorLabelTest` (unit): Codex wrap-up → `thread_resetting_wrapping_up_codex` for every handoff; Codex restarting → the shared restarting resources; the existing Claude assertions stay.
- `SessionBoundaryDelimiterScreenTest` (shared, Robolectric): a Codex boundary shows the sentence beginning "Codex doesn't remember messages above this line" plus Install; existing Claude test unchanged.
- `ThreadViewModelTest`: a Codex conversation yields `state.agent == Codex`; the default conversation yields Claude.
- New `ThreadAgentNameTest` (shared): `ThreadScreen` with a Codex `ThreadUiState` holding one boundary and a wrap-up `resetting` shows the Codex wrap-up and Codex explanation, and not the Claude ones — proves the screen wiring.

Not operator-facing flow change requiring a rung-3 scenario: the reset and boundary flows already exist; this changes a name in fixed copy.

## Open questions

None.

## Documentation handoff

None named by the ticket.

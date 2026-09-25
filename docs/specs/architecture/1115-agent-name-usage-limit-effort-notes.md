# #1115 — Name the conversation's agent in the usage-limit line and effort notes

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` — the agent #1108 added; Claude when the daemon does not say.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState` — gains `agent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → the `state` combine that builds `ThreadUiState` from the observed `Conversation` — fills `agent`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/UsageLimitIndicator.kt` → `usageLimitLabel` — resolves the two usage-limit lead strings.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt` → `ThreadTopOverlay` — the only caller of `usageLimitLabel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `EffortNote.textRes`, `ThreadComposerFooter` — the footer's effort-button state description.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — calls the overlay and the footer, and resolves the Status sheet's `effortNote`.
- `app/src/main/res/values/strings.xml` → `thread_usage_limit_label`, `thread_usage_limit_label_no_status`, `thread_effort_note_default_unavailable`, `thread_effort_note_not_reported`.
- New: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/AgentName.kt` → `ConversationAgent.nameRes()`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread screen's Top overlay pill, the footer's effort button and the Status sheet keep their layout, tokens and typography; only the agent name inside four client-owned strings changes, so there is no visual change to reproduce.

## Change

Four strings take the agent name as a new first argument: `%1$s reports usage-limit status: %2$s`, `%1$s reported a usage-limit update`, `%1$s's default applies. …` and `%1$s reports no effort parameter.`. Two new untranslatable strings, `agent_name_claude` ("Claude") and `agent_name_codex` ("Codex"), are the client-owned names, picked by `@StringRes fun ConversationAgent.nameRes()` in the UI layer so `data/` stays free of resources. `ThreadUiState` gains `agent: ConversationAgent = Claude`, filled from the observed conversation's `agent` (Claude while the conversation is not yet known). `ThreadScreen` passes `state.agent` to `ThreadTopOverlay(agent)`, which hands it to `usageLimitLabel(reading, agent)`, and to `ThreadComposerFooter(agent)`. A new `@Composable EffortNote.text(agent)` next to `textRes` resolves a note with the agent name; the footer and the Status sheet's `effortNote` argument in `ThreadScreen` both use it, so they cannot disagree. `thread_effort_note_selected_unavailable` names no agent and ignores the extra argument. The overlay's and footer's new `agent` parameters default to Claude, matching `Conversation.agent`'s own default, so previews and existing callers read as today. A Claude conversation's text is byte-identical to today's.

Other strings that still say "Claude" (thinking, tool running, retry, compacting, turn outcome) are outside this ticket.

## Testing strategy

- `ThreadViewModelTest`: a Codex conversation from `fixedRepo` gives `state.agent == Codex`; the default stays Claude.
- `ThreadTopOverlayTest` (shared, Robolectric): the existing label helper resolves with "Claude" and keeps passing; a new case with `state.agent = Codex` shows "Codex reports usage-limit status: …".
- `ThreadComposerFooterTest` (shared): the existing not-reported case asserts the literal "Claude reports no effort parameter."; a new Codex case asserts the footer's state description names Codex and that the opened Status sheet shows "Codex's default applies. …".
- `ScriptedUsageLimitTest` and `InteractiveStreamE2ETest` resolve the changed strings with the Claude name, so their expectations are unchanged.
- No rung-3 scenario: this changes copy on already-covered surfaces, not a new operator flow.

## Documentation handoff

None named by the ticket. Pending for the documentation stage only if it chooses to note, in the thread-screen overview, that these four strings name `Conversation.agent`.

## Revisions

- **Merge of `main` (#1113 landed first).** #1113 added the same `agent_name_claude` / `agent_name_codex` strings and a `@Composable agentName(agent)` in `AgentName.kt`. Two definitions of the same strings would fail the resource merge, so this ticket drops its own copies and its `ConversationAgent.nameRes()`. `usageLimitLabel` and `EffortNote.text(agent)` now resolve the name through `main`'s `agentName`. The strings and behaviour are unchanged.

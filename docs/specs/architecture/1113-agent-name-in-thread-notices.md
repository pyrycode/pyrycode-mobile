# #1113 — Name the conversation's agent in notices, refusals and turn outcomes

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` (#1108): the source of truth, Claude when the daemon does not say.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadViewModel.state`: the `combine` that already copies `conv?.isPromoted` / `conv?.workspaceLabel` into `ThreadUiState`; `agent` joins it there.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadUiState`: gains the field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`'s delivered-row `when`, `ThreadStatusArea`, `StatusReading`: the three call sites.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/BannerNoticeRow.kt` → `BannerNoticeRow`: attribution span + warning icon description.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ModelRefusalRow.kt` → `ModelRefusalRow`, `ModelRefusalRowContent`, `attributedBanner`: expand/collapse click labels + attribution span.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt` → `TurnOutcomeIndicator`: the "Claude reports" tail.
- `app/src/main/res/values/strings.xml` → `thread_banner_attribution`, `cd_thread_banner_warning`, `cd_thread_refusal_expand`, `cd_thread_refusal_collapse`, `thread_turn_outcome_claude_reports`, `thread_turn_outcome_claude_reports_error`. Only `values/` exists, so no translations move.
- Tests that name these strings or rows: `BannerNoticeRowTest` (sharedTest), `ScriptedTurnOutcomeTest` (sharedTest), `ModelRefusalRowTest` (androidTest), `ThreadViewModelTest` (the `ConversationRepository by FakeConversationRepository()` delegation idiom).
- `docs/knowledge/features/banner-notice-row.md`, `model-refusal-row.md`: the security note that the attribution is a **client-owned separate styled span** — the agent name must stay client-owned (a string resource chosen from the enum), never daemon text.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread screen: none of the three rows is drawn on 16:8 (each borrows existing tokens, per its KDoc), so layout, tokens and typography are unchanged; only the agent name inside the client-owned copy follows the conversation.

## Change

`ThreadUiState` gains `agent: ConversationAgent = ConversationAgent.Claude`, set in `ThreadViewModel.state` from `conv?.agent` (Claude when the conversation is not yet known). `ThreadScreen` passes `state.agent` to `BannerNoticeRow`, `ModelRefusalRow`, and through `ThreadStatusArea` → `StatusReading` to `TurnOutcomeIndicator`. Each of the three public rows takes a **required** `agent: ConversationAgent` parameter, so no future call site can silently credit Claude.

A new small file `components/AgentName.kt` holds `@Composable fun agentName(agent: ConversationAgent): String`, an exhaustive `when` over the enum resolving `agent_name_claude` ("Claude") / `agent_name_codex` ("Codex"). The name is always client-owned.

Strings become format strings taking the name as `%1$s`: `thread_banner_attribution` `"%1$s: "`, `cd_thread_banner_warning` "Warning from %1$s", `cd_thread_refusal_expand` / `_collapse` "Show / Hide %1$s's explanation". The turn-outcome pair is renamed to `thread_turn_outcome_agent_reports` `" · %1$s reports %2$s"` and `thread_turn_outcome_agent_reports_error` `" · %1$s reports an error"`, since the old names say "claude". With Claude every string renders byte-for-byte as today. The attribution stays its own medium-weight span.

Out of scope: `thread_usage_limit_label*` ("Claude reports usage-limit status") and other Claude-naming copy not listed by the ticket; `TurnOutcomeReport.claudeReports` keeps its name (data field, not UI text).

## Testing strategy

- **Unit** (`ThreadViewModelTest`): a repo delegating to `FakeConversationRepository` whose `observeConversations` marks the conversation Codex → collected `state.agent` is Codex; the plain fake → Claude.
- **Screen** (new sharedTest `ThreadAgentAttributionTest`, Robolectric, through `ThreadScreen` so the wiring is proven): a Codex state holding a warning `Banner` and a `ModelRefusal`, plus a failed `turnOutcome` → "Codex: …" text, "Warning from Codex" description, refusal click label "Show Codex's explanation" then "Hide Codex's explanation" and "Codex: …" after the click, outcome description "Turn failed · Codex reports prompt_too_long"; the same state with Claude → the literal Claude strings of today. A Codex `Failed` outcome with no details → "· Codex reports an error".
- Existing `BannerNoticeRowTest`, `ModelRefusalRowTest`, `ScriptedTurnOutcomeTest` pass `ConversationAgent.Claude` / the renamed resource and keep asserting today's literal Claude copy — the "reads exactly as today" guard.
- Not operator-facing new flow (copy only), so no rung-3 scenario.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/banner-notice-row.md`, `model-refusal-row.md` and the turn-outcome overview should record that the attribution / labels name the conversation's agent (`agentName`) rather than a fixed "Claude".

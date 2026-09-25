# #1110 — List only the conversation's own agent in the model menu

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ModelListPayloads.kt` → `ModelListRowDto`, `toMenu` — the decode boundary that gains `agent` and `family`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ModelMenu`, `ModelMenuRow` — the domain row that carries the tags, defaulted so existing fixtures need no edit.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.agent`, `ConversationAgent` (#1108) — the conversation's own agent.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` → `conversationAgentOf` — the conversation-row mapping; deliberately *not* reused for model rows (an unknown row agent must match neither conversation, where an unknown conversation agent reads Claude).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfigFlow`, `state`, `runConfig`, `MAX_RENDERED_MODEL_CHOICES`, `toChoice` — where the rows become `ThreadModelChoice`s and where `effortRecall.offer` reads the config.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.effortChoices`, `modelLabel` — with no saved model the effort lookup keys on a `default` row; a Codex-only choice list has none, so AC 3 follows from the filter with no change here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.offer` — refuses a level `effortChoices` does not publish, so AC 5 follows from the filter.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `observeConversations` — sends `list_conversations` on every subscription, so the ViewModel must not add a second subscription.
- `../pyrycode/docs/protocol-mobile.md` § `model_list` (`agent`, `family`, the merged-list paragraph) and § capability `multi_agent` — wire SSOT; not restated here.
- `app/src/test/java/de/pyryco/mobile/data/network/ModelListPayloadsTest.kt`, `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt` → fixture shapes the new tests mirror.

In-flight overlap: #1112, #1113, #1114, #1115 each add one `agent =` line to the `state` combine in `ThreadViewModel.kt` and tests in `ThreadViewModelTest.kt`. Not a dependency; this ticket keeps its `ThreadViewModel` edits local and puts its run-config tests in a new class so a later merge stays short.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (Input footer 110-3494, Options overlay 533-1958, Status Sheet 20-100)

No layout, token or component change: the footer model/effort menus and the Status sheet's Model section render exactly as today, fed a shorter row list. The overlay node renders empty through the MCP screenshot (it is an invisible-by-default overlay), which is immaterial because nothing visual moves.

## Context

With `multi_agent` negotiated (#1119, next), every `model_list` carries Claude's rows then Codex's, each tagged `agent`/`family`, identical for every conversation. The daemon refuses a model or effort outside the session's agent. The phone must list only the conversation's own agent's rows. `footerMenu` / `FooterControl.Model` and the Status sheet both read `ThreadRunConfig.choices`, so one filter upstream of `runConfig` covers both.

## Design

**Decode (`ModelListPayloads.kt`).** `ModelListRowDto` gains `agent: String? = null` and `family: String? = null` (both `omitempty` on the wire; the frame stays all-or-nothing on the required fields). `toMenu` maps `agent` through a new `internal fun modelRowAgentOf(wire: String?): ConversationAgent?`: absent/`null`/`"claude"` → `Claude`, `"codex"` → `Codex`, anything else → `null` (belongs to neither conversation). `family` is copied verbatim.

**Domain (`ConversationRepository.kt`).** `ModelMenuRow` gains trailing defaulted fields `agent: ConversationAgent? = ConversationAgent.Claude` and `family: String? = null`, with KDoc: `null` agent is one this client does not know and is listed in no conversation; `family` is daemon text under the row's existing SECURITY paragraph and is never parsed or rendered by this ticket.

**ViewModel (`ThreadViewModel.kt`).**
- `private val conversations: Flow<List<Conversation>>` = `repository.observeConversations(ConversationFilter.All)` shared with `shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)`, so the `state` combine and the agent read ride one upstream subscription (one `list_conversations` request, as today). The `state` combine's first arm reads it instead of the repository directly.
- `private val conversationAgent: Flow<ConversationAgent>` = `conversations` mapped to this conversation's `agent`, `ConversationAgent.Claude` while it is absent (the `Conversation.agent` default), `distinctUntilChanged`.
- `runConfigFlow`'s menu arm becomes `repository.observeModelMenu(conversationId).combine(conversationAgent) { menu, agent -> menu?.forAgent(agent) }` — the chained combine, since the five-arm combine is at Kotlin's typed ceiling. `runConfig` is unchanged, so the filter lands before `MAX_RENDERED_MODEL_CHOICES` and `hiddenChoices` counts the filtered list; `effortRecall.offer` in `state` already receives this config.
- `private fun ModelMenu.forAgent(agent: ConversationAgent): ModelMenu` — rows whose `agent == agent`, daemon order kept; `droppedModels` kept for Claude, `0` for Codex (it counts only Claude's tail cut).

A saved value no row names keeps the existing inert fallback in `ThreadRunConfig.label`; `toChoice` is untouched (display name verbatim, `resolved_model` as detail, `value` as the write argument).

## State + concurrency model

No new jobs. `conversations` shares within `viewModelScope`; `WhileSubscribed()` with no stop timeout so it stops with `state`'s own `WhileSubscribed(5_000)` upstream. All flows cold until `state` is collected; cancellation is the existing scope.

## Error handling

No new failure mode. A malformed tag type (e.g. `agent: 7`) fails the whole frame at the existing `MobileJson` decode, as any wrong-typed row member does today. An unknown agent string is not an error; it is a row listed nowhere.

## Testing strategy

Unit tests only (no UI change):
- `ModelListPayloadsTest`: a tagged merged frame decodes `agent`/`family` per row; a row without tags reads `Claude` with `family == null`; an unknown `agent` string decodes to `null`; the existing untagged fixtures still compare equal (AC 1).
- New `ThreadViewModelAgentModelMenuTest` over one merged menu (Claude rows incl. `default`, Codex rows, one unknown-agent row, `droppedModels > 0`), a wrapper repo that stamps the conversation's agent:
  - Claude conversation: choices are Claude rows in order, `droppedModels` unchanged (AC 2, 4).
  - Codex conversation: choices are Codex rows in daemon order, `droppedModels == 0`, display name verbatim (AC 2, 4).
  - Codex conversation, saved model `""`: `modelLabel == "default"`, `effortChoices` empty (AC 3).
  - `hiddenChoices` counts the filtered list (a Codex conversation beside > 32 Claude rows hides nothing).
  - An untagged menu in a Claude conversation yields today's choices (AC 1).
- `ThreadViewModelEffortRecallTest`: in a Codex conversation, a remembered level the selected Codex row publishes is written; one only a Claude row publishes is not (AC 5).

Rung-3/4 e2e: the live proof waits for #1119 (the phone does not advertise `multi_agent` yet), so no scenario lands here.

## Open questions

- None blocking. Whether a conversation absent from the list should read Claude or suppress the menu: reads Claude, matching `Conversation.agent`'s default and today's behaviour.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the one boundary stays the `MobileJson` decode of `ModelListRowDto`. `agent` is daemon text that is collapsed to `ConversationAgent?` by `modelRowAgentOf` at that boundary through exact comparison with two literals; nothing downstream holds the raw string, and an unrecognised value (including a case variant such as `Codex`) fails closed to `null`, which no conversation lists. `family` is carried verbatim under `ModelMenuRow`'s existing SECURITY paragraph and is never rendered, logged, parsed or used as a key by this ticket.
- [Trust boundaries] No findings — the filter in `forAgent` is a menu-shaping aid, not an authorization control: a hostile daemon can already publish any menu it likes, and every model/effort write is still re-validated daemon-side against the session's agent (protocol § `model_list` property 3). A mis-tagged row can at worst hide or show a row the daemon then refuses through the existing refusal path.
- [Trust boundaries] No findings — displayed text is unchanged: rows still render through `toChoice`, whose `inert()` bound applies to `display_name`, `resolved_model` and effort levels exactly as today; no name is built or reformatted on the phone.
- [Tokens, secrets] Not applicable — no token, key or credential is read, stored or produced.
- [File / storage] Not applicable — nothing is written to disk; the remembered effort store is read and written only through its existing `EffortRecall` path.
- [Inter-process] Not applicable — no Activity, intent, deep link, push or WebView change.
- [Crypto] Not applicable — no primitive touched.
- [Network & I/O] No findings — no new verb or request. Sharing `conversations` through `shareIn` keeps `list_conversations` at one request per thread subscription rather than adding a second (`RemoteConversationRepository.observeConversations` sends on every subscription).
- [Logs] No findings — no new log call; row text and tags never reach `RelayLog`.
- [Concurrency] No findings — the shared flow lives in `viewModelScope`, is scoped to one ViewModel (one conversation id), and stops with its last subscriber; the per-conversation read is filtered by id, so no other conversation's data is exposed beyond what the `state` combine already reads.
- [Threat model] OUT OF SCOPE — advertising `multi_agent` (and so receiving Codex rows and Codex conversations at all) is #1119.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-25

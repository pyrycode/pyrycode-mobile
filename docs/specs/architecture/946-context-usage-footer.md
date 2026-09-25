# Show the reported context usage in the composer footer and Status sheet (#946)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `observeContextUsage`, `ContextUsage` — #945's reading: `null` is unavailable, `percentage` is Claude's own number, never negative, never derived.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → `observeContextUsage` — a reconnect or host switch heads the flow with `null`; `RemoteConversationRepository.observeContextUsage` → `ContextUsageProjection` clears on `session_transition`, leaves a reject at `null` and asks on subscription. So every "no reading" case in AC 2 already reaches the VM as `null`; the VM tracks no staleness of its own.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `runConfigFlow` (five-arm `combine` at the typed ceiling, then `.combine(runningModel)`), `runningModel` — #891's wiring this ticket follows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig` (`running`) — the one surface both render sites read.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` → `ThreadComposerFooter`, `FooterButton` — the row, its `Spacer(weight(1f))` before the Status opener, the body-small label style.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `StatusSheetContent`, `ContextWindowSection` (hard-coded "Context usage unavailable" plus caption), `RUNNING_MODEL_TEST_TAG`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → the `StatusSheet(...)` call.
- `docs/specs/architecture/891-status-sheet-running-model.md` — the analogue, including its Revisions entry: the live `TEST_TARGET` list and `LIVE_MINIMUM` must move with a new rung-3 scenario.
- `docs/specs/architecture/945-context-usage-reading.md` — the reading's contract and its Security review ("rendering the percentage belongs to the footer ticket").
- Tests: `ThreadViewModelRunningModelTest` (`ScriptedRepo`, `collectedVm`), sharedTest `ThreadComposerFooterTest` (`setThread`, hosted on `ThreadScreen`) and `StatusSheetTest` (`setSheet`, `renders_context_window_section_as_unavailable_with_header_and_caption`), `e2e/InteractiveStreamE2ETest.interactiveTurn_pingPrompt_statusSheetShowsRunningModel`, `scripts/e2e-emulator.sh` LIVE `TEST_TARGET`, `scripts/android-test-gate.py` `LIVE_MINIMUM`.

In-flight overlaps: `feature/878`, `feature/883` (`strings.xml`, `ThreadScreen.kt`), `feature/932` and `feature/957` (`ThreadViewModel.kt`, `ThreadUiState.kt`, `ThreadScreen.kt`, `InteractiveStreamE2ETest.kt`, `strings.xml`). None touches `runConfigFlow`, `ThreadRunConfig` or the live list; edits here stay additive.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=110-3497 (in `Input footer` 110:3494 of 16:8)

The footer segment is plain `Cxt: 84%` text after the Effort button, `M3/body/small` in `Schemes/Primary` — no chevron, not a button. The Status sheet's Context-window node (20:151) reads `73% used (146K of 200K tokens)` over a severity bar and the caption. This ticket shows only `N% used` in the sheet: the bar and the token figures are a deliberate divergence (the ticket shows Claude's percentage only; the bar's severity colours are out of scope). Figma has no unavailable variant; the footer reuses its body-small style in `onSurfaceVariant`, the colour a disabled footer button already uses.

## Context

#945 made Claude's reported context percentage available per conversation. This slice shows it in the footer and the sheet from one `ThreadRunConfig` field, so the two cannot disagree. No ADR warranted.

## Design

### State (`ThreadUiState.kt`)

- `ThreadRunConfig.contextPercent: Int? = null` — Claude's `percentage` verbatim; `null` is the unavailable state. Never derived from token totals or `SessionSettings.usedTokens` / `windowTokens`.

### ViewModel (`ThreadViewModel.kt`)

- `runConfigFlow` gains one more link: `.combine(repository.observeContextUsage(conversationId)) { config, usage -> config.copy(contextPercent = usage?.percentage) }`. No sixth arm; `runConfig(...)` unchanged. The VM is per `conversationId`, so another conversation's reading is never subscribed.

### UI

- `ThreadComposerFooter`: after the Effort button, a private `ContextSegment(percent: Int?)` — a `Text` in `bodySmall`, `maxLines = 1`, ellipsis:
  - `percent != null` → `thread_footer_context` ("Cxt: %1$d%%"), `primary`, `contentDescription` = `cd_context_usage` ("Context usage %1$d%%").
  - `null` → `thread_footer_context_unavailable` ("Cxt: n/a"), `onSurfaceVariant`, `contentDescription` = `cd_context_usage_unavailable` ("Context usage unavailable").
  - It carries `Modifier.weight(1f)` and replaces the row's `Spacer`: it takes the leftover width and pushes the Status opener to the end. As a weighted child it is measured after the buttons and the opener, so on a narrow row it ellipsizes instead of squeezing the opener. `testTag(CONTEXT_USAGE_TEST_TAG)` (a static constant) for the live scenario.
- `StatusSheet` / `StatusSheetContent` gain `contextPercent: Int? = null`; `ContextWindowSection(contextPercent)` shows `status_sheet_context_used` ("%1$d%% used") or `status_sheet_context_unavailable` ("Context usage unavailable", moved from the literal), same style, and keeps its caption. No bar.
- `ThreadScreen` passes `contextPercent = state.runConfig.contextPercent`.
- `strings.xml`: the five strings above.

## State + concurrency model

No new jobs or scopes. The reading is one more cold flow inside the existing `state` `stateIn(viewModelScope, WhileSubscribed(5_000))`. Every repository emits a first value (`flowOf(null)` default, `StableConversationRepository` heads with `null`, the projection's `StateFlow.map`), so the added `combine` never stalls `state`. Subscribing is what makes the repository send its `request_context_usage`; unsubscribing on screen exit cancels it with `state`.

## Error handling

No new failure modes. Absent, rejected, cleared by a transition, or dropped by a reconnect are all `null` from the repository, rendered as the explicit unavailable state — never `0%` or blank.

## Testing strategy

- **Unit, ViewModel** (new `ThreadViewModelContextUsageTest`, `ScriptedRepo` shape with a per-conversation `MutableStateFlow<Map<String, ContextUsage>>`):
  - no reading → `contextPercent == null`;
  - a reading → its `percentage`, including one that disagrees with `totalTokens / maxTokens` (verbatim, not derived);
  - a replaced reading → the new percentage; a cleared reading (session transition) → `null`;
  - a reading only for another conversation → `null`;
  - `SessionSettings.usedTokens` / `windowTokens` set with no reading → still `null`.
- **Screen** (sharedTest, Robolectric): `ThreadComposerFooterTest` — available shows `Cxt: 84%` with its description; absent shows `Cxt: n/a` with "Context usage unavailable" and no `%` text; replacing the state's reading re-renders the new value; the Status opener still works. `StatusSheetTest` — available shows `84% used` in place of the unavailable text and keeps the caption; the existing unavailable test stays.
- **Rung 3** (`InteractiveStreamE2ETest.interactiveTurn_pingPrompt_footerShowsContextUsage`): send the ping turn, await the reply, then wait until the `CONTEXT_USAGE_TEST_TAG` node's text matches `Cxt: \d+%`. Appended to the LIVE `TEST_TARGET` list in `scripts/e2e-emulator.sh` (counts move to fifteen methods, ten turns), `LIVE_MINIMUM` → 15. No rung-4 twin: whether the scripted `fakeclaude` path makes the daemon publish `context_usage` is not established here.

## Open questions

- Does the footer's `ContextSegment` fit the 320dp Robolectric screen beside the three buttons? It ellipsizes by design; if a screen test needs the full text visible, use `DeviceConfigurationOverride.ForcedSize`. Resolve in Phase B.

## Documentation handoff

The ticket has no Documentation handoff section. Suggested, pending for the documentation stage: `docs/knowledge/features/thread-composer-footer.md` (the `Cxt:` segment, its unavailable state, the weighted-slot layout), `docs/knowledge/features/status-sheet.md` (Context-window section now shows the reading), `docs/e2e-interactive-stream.md` coverage list (the new rung-3 scenario).

## Revisions

### 2026-09-24 — Phase B

- **Open question resolved, no design change.** On the 320dp Robolectric screen the weighted `ContextSegment` still shows `Cxt: 84%` beside Actions, the model and the effort buttons (`assertIsDisplayed` passes), so no `ForcedSize` override was needed.

### 2026-09-24 — Rework 1: the phone stops sending `request_context_usage`

- **Finding (verifier, PR #970):** the scripted `reconnect` scenario timed out on this PR and passed at the merge base. This PR is the first production subscriber of `observeContextUsage`. After the mid-turn reconnect, the new connection's subscription sent `request_context_usage`. The daemon's `handleRequestContextUsage` waits for the open turn to end on the connection's serial `appFrameWorker`, so the phone's next `send_message` queued behind it. The held turn ends only when that message arrives, so the connection deadlocked. Real use hits the same stall whenever the phone subscribes during a running turn, including one another client started, and an `interrupt` would wait too.
- **Route chosen: mobile side.** The phone cannot know whether a turn is open when it subscribes. A fresh connection has seen no `turn_state`, and no summary carries one. Asking only at known-idle moments would race the next queued turn. So `ContextUsageProjection` sends no ask at all: `observe` no longer asks on its first collector, `onSessionTransition` only clears, and the observer count, `ask`, and the constructor's `send` / `negotiatedCapabilities` / `nextRequestId` are removed. The reading now comes from the daemon's post-turn `context_usage` push alone. The daemon fix is filed as pyrycode#2563. Once it lands, a mobile ticket can restore #945's ask from git history. `RequestContextUsagePayloadDto` and `TYPE_REQUEST_CONTEXT_USAGE` stay as wire documentation, marked unsent.
- **Contract after the change:** a conversation shows the unavailable state until its next turn ends on the current connection, including when an idle conversation is first opened. AC 2's cases are unchanged: a new thread, a transition and a reconnect all read `null`, and a reject can no longer occur. The rung-3 scenario needs no change, because it waits for the post-turn push after a real turn.
- **Tests:** `RemoteConversationRepositoryContextUsageTest` now asserts that subscribing, re-subscribing, an empty id, a session transition and a reconnect send nothing. The ask and reject cases are removed. The § State + concurrency sentence "Subscribing is what makes the repository send its `request_context_usage`" no longer holds.

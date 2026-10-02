# #1357 — Recovery advice and Compact after a stopped turn

## Files read

- `ui/conversations/components/TurnOutcomeIndicator.kt` — `TurnOutcomeReport`, `turnOutcomeReport`, `inertOutcomeToken`, `TurnOutcomeIndicator`: the arm being narrowed. The type, rule and composable are replaced in place.
- `ui/conversations/components/NoticePill.kt` — `NoticePill`'s `onClick` and `isError` variants, reused unchanged.
- `ui/conversations/thread/ThreadViewModel.kt` — `turnOutcome`, `nextTurnOutcome`, `threadItems`, `thinkingProgress`, the `init` collectors over `repositoryAvailable` and `liveSessionEvents`, `sendInLocalWindow`, `sendMessage`, `sendWithAttachments`, `onComposerCommand`.
- `ui/conversations/thread/ThreadScreen.kt` — `ThreadScreen`'s `turnOutcome` and `onComposerCommand` parameters, `ThreadStatusArea`, `statusArm`, `StatusReading`.
- `ui/conversations/thread/ThreadComposerFooter.kt` — `ComposerAction.CompactSession` (`/compact`).
- `data/repository/ThinkingProgressProjection.kt` — the reading is removed on `turn_end` and `session_transition`, so any later non-null reading is a new one.
- `data/repository/RemoteConversationRepository.kt` `observeMessages`, `CachingConversationRepository.observeMessages` — subscribing sends a backfill request and drives cache writes, so the boundary signal must ride the existing `threadItems` subscription, not a second one.
- `ThreadItem.SessionBoundary` (in `ConversationRepository.kt`) — value identity `(previousSessionId, newSessionId, occurredAt)`, appended in arrival order.
- Desktop `reduceTimeline` (`latestTurnEnd`) and `ComposerErrorSlotControl` — the rule and the three copies mirrored here.
- `docs/knowledge/features/turn-outcome-indicator.md`, `stopped-turn-row.md` — the arm's current contract; since #1356 the thread row carries the outcome, so the arm narrows to advice.

Overlaps: #1340, #1346, #1354, #1355 touch `ThreadViewModel.kt` / `ThreadScreen.kt` / `strings.xml` in unrelated blocks; edits here stay local.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (status area `111:3525`), tappable pills https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910

The 24dp status band keeps its glyph and one reading. The notice is the existing error `NoticePill` (`errorContainer` / `error`, `bodySmall`, `ErrorOutline` icon, two lines). For the context notice a second `NoticePill` reading "Compact" follows it, tappable through `onClick` like the overlay's Offline · Retry pill; it uses the Default variant (`primaryContainer` / `onPrimaryContainer`) so the action reads apart from the error. No new component.

## Context

Since #1356 the stopped-turn row tells the story of a stopped turn, so the status arm repeated it. Desktop uses this slot only for recovery advice; mobile copies that. No decision record needed: this is parity with desktop's existing rule.

## Design

**Rule** (`TurnOutcomeIndicator.kt`, replacing `TurnOutcomeReport` / `turnOutcomeReport` / `inertOutcomeToken`):

```kotlin
enum class TurnRecoveryNotice { ContextTooLong, BillingError, AuthenticationFailed }
internal fun turnRecoveryNotice(event: LiveSessionEvent.TurnEnd): TurnRecoveryNotice?
```

`null` when `stopReason == "cancelled"`, or when neither `isError` nor a non-empty, non-`success` `outcome` holds. Otherwise `terminalReason == "prompt_too_long"` → `ContextTooLong`, else `errorCategory == "billing_error"` → `BillingError`, else `errorCategory == "authentication_failed"` → `AuthenticationFailed`, else `null`. Daemon tokens are compared, never rendered, so the sanitizer goes.

**Composable:** `TurnOutcomeIndicator(notice: TurnRecoveryNotice?, agent: ConversationAgent, onCompact: (() -> Unit)?, modifier)`. Early return on `null`. Copy is client-owned (`thread_recovery_context`, `thread_recovery_billing`, `thread_recovery_auth`, the last two formatted with `agentName(agent)`; `thread_recovery_compact` = "Compact"). The Compact pill shows only for `ContextTooLong`; `onCompact == null` gives it no click action. The six `thread_turn_outcome_*` strings are removed.

**Screen:** `ThreadScreen`'s `turnOutcome` parameter becomes `TurnRecoveryNotice?` (name kept, so `MainActivity` and the scripted harness stay as they are). `ThreadScreen` builds `onCompact = { onComposerCommand(CompactSession) }`, or `null` when `CompactSession in state.absentActions`, remembered on both, and threads it through `ThreadStatusArea` → `StatusReading`. `statusArm` is unchanged, including the `localSendPending` suppression.

**ViewModel:** `turnOutcome` becomes a `MutableStateFlow<TurnRecoveryNotice?>` exposed read-only, written by `setTurnOutcome` / `clearTurnOutcome(reason)`:

| Signal | Where |
|---|---|
| `TurnEnd` → rule result (set or clear) | new `init` collector over `liveSessionEvents`, through `nextTurnOutcome` |
| non-idle `TurnState`, `AssistantDelta`, `ToolUse`, `ToolResult` → clear; `Idle`, `ReplayGap`, other conversation → keep | same reducer |
| non-null `observeThinkingProgress` reading → clear | new `init` collector, `filterNotNull()` |
| new newest `SessionBoundary` → clear | `onEach` on `threadItems`, see below |
| user send → clear | `sendInLocalWindow` (covers `sendMessage`, `sendWithAttachments`) and the bare-command path of `onComposerCommand`, at hand-off, before the repository call |
| reconnect → clear | the existing `repositoryAvailable … drop(1)` collector |

**Boundary edge:** a private `noteNewestBoundary(items)` keeps the newest `SessionBoundary` seen. An empty list (the `scan` seed, a not-yet-loaded thread) sets no baseline. After a baseline exists, a non-null newest boundary that differs from the stored one clears. Older history pages prepend, so the newest never changes for them. The tracker lives on the ViewModel, so a `WhileSubscribed` restart compares against what was seen before rather than re-baselining.

Logs: `event=turn_recovery_notice state=shown notice=<code>` and `state=cleared reason=<code>`, static codes only, once per change.

## State and concurrency model

All writers run on `viewModelScope` (`Dispatchers.Main.immediate`), so the `MutableStateFlow` needs no lock. The live-event, thinking and reconnect collectors are eager `init` launches, like the #1311 local-send collector, so the notice holds while the screen is not collecting; the old `WhileSubscribed` fold lost a `turn_end` that arrived while nothing collected. The boundary edge runs inside `threadItems`, so it runs only while `state` is collected; a boundary that arrives while unsubscribed is caught on resubscription by the stored comparison. All cancel with the ViewModel.

Ordering: the repository applies `thinking_progress`, `session_transition` and `turn_end` on one inbound collector in wire order, removing the thinking reading on `turn_end` before the event fans out; the VM collectors resume on the main dispatcher in emission order. A reading that predates the `turn_end` is therefore delivered first or conflated away, never after.

## Error handling

No I/O of its own. A send that throws still cleared the notice (desktop clears on the optimistic echo too). A Compact tap goes through `onComposerCommand`, whose existing connection, absent-action and attachment guards apply.

## Testing strategy

- **JVM** `TurnRecoveryNoticeTest` (replaces `TurnOutcomeReportTest`): table over the rule — the three notices, precedence of `prompt_too_long` over a category, `is_error` alone and non-success `outcome` alone both raising, cancelled with each notice's fields giving none, clean turn with a stale category giving none, other failed/early-stopped turns (`error_max_turns`, `refusal`, unknown category) giving none.
- **JVM** `ThreadViewModelTurnRecoveryTest` (replaces the #805 `turnOutcome_*` group in `ThreadViewModelTest`): set by `turn_end`; survives `Idle` and `ReplayGap`; one test per clear signal — non-idle `turn_state`, `AssistantDelta`, `ToolUse`, `ToolResult`, thinking reading, new session boundary (and not on an older page or a re-emission), text send, attachment send, bare command, reconnect; other conversation ignored.
- **sharedTest** `ThreadRecoveryNoticeTest` through `ThreadScreen`: context notice plus Compact pill; tapping calls `onComposerCommand(CompactSession)`; no click action when absent; billing and auth notices name a Codex agent; no Compact pill for them.
- **sharedTest (rung 2)** `ScriptedTurnOutcomeTest` rewritten to the new contract on the real repository: `prompt_too_long` shows notice and Compact; cancelled shows nothing; next turn clears; clean turn shows nothing; compaction wins the slot.
- Mechanical fixture updates in `ThreadAgentAttributionTest`, `ThreadTopOverlayTest`, `ThreadStatusBandTest`, `RunningToolIndicatorTest`, `ThreadActivityIndicatorVisualTest`, and device-only `ThreadActivityIndicatorCaptureTest` (existing screenshot captures; real pixels are its device-only reason).
- Rung 3: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` asserted the Interrupted label, which a cancelled turn no longer shows; its step 4 keeps the cancelled `turn_end` and the Stop control leaving. The Compact pill's live proof needs a real `prompt_too_long`, which a real turn cannot reliably reach; a follow-up ticket in the #481 / #482 shape covers a deterministic scenario.

## Open Questions

- Compact pill variant: Default chosen (see Design source). Resolve against the screenshot during implementation.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/turn-outcome-indicator.md`: the arm shows only the three recovery notices, the Compact pill and its availability, and the signals it clears on. Revise "Classification & sanitization" and "How this differs from the stopped-turn row (#1356)" to match.
- `docs/e2e-interactive-stream.md`: `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` no longer asserts an Interrupted label (method name kept for the references to it).

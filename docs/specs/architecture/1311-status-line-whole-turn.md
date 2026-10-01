# #1311 — keep the status line up for the whole running turn

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `StatusReading`, `openToolCall` — the band's one-slot `when`, whose order changes, and the open-call finder the running-tool label keeps using.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — the thinking arm; its early return is why `responding` draws nothing. Its KDoc's "one `Row`, one icon" rule is why the new labels ride the same call site.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `isThinking`, `isBusy`, `isStalled`, `turnOutcome`, `sendMessage`, `sendWithAttachments`, the `repositoryAvailable` collector in `init` — where the local-send window lives and closes.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the thread destination — collects the VM flows for `ThreadScreen`; `isStalled` is collected by nothing today.
- `app/src/main/java/de/pyryco/mobile/data/repository/StallProjection.kt` → `StallProjection` — onset on `stall`, cleared by any decoded live-session event. Unchanged; the stall arm rides it.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `onInbound` arms for `tool_progress`, `tool_denied`, `stall` — what the scripted harness needs to push.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt` → `NoticePill` — the turn-outcome arm's error colour is `colorScheme.error`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `stopping` — the stop control shows iff `isBusy` and the draft is blank; the rung-3 scenario uses it as its "turn is busy" proxy.
- `app/src/sharedTest/.../thread/ScriptedThreadHarness.kt` — the rung-2 harness; gains `tool_progress`, `tool_denied`, `stall` pushes and the new screen parameters.
- `app/src/sharedTest/.../thread/RunningToolIndicatorTest.kt` → `denial_removesTheLabel_andARespondingBandGoesEmpty` — asserts the old empty responding band; this ticket changes that expectation to "Working…".
- `app/src/sharedTest/.../thread/ScriptedResettingTest.kt` → `apiRetry_winsOverResetting` — asserts the old order; flips to Reset-wins (AC4).
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_toolPrompt_rendersToolStepInThread`, `TOOL_PROMPT` — the shape the new rung-3 scenario copies.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/ConversationScreen.tsx` → `workingIndicatorState`, `workingIndicatorStateWithLocalSend`, `STALL_COPY`, `WORKING_COPY` — the order and copy mirrored here.
- `docs/knowledge/features/thinking-indicator.md`, `thread-screen-how-it-works-list-and-status-row.md` — the arm order as documented today.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

`Status area` (`I533:1957;111:3525`): one 24dp band above the input field holding one reading — the 14×16 pulsing status glyph and a `bodySmall` label in `colorScheme.primary`. "Working…" is the same treatment as "Thinking…"; the stall reading is the same row with its label in `colorScheme.error` (the colour the turn-outcome pill already draws its text in). No layout change. The Figma MCP was not authorised in this session, so the summary is taken from the ticket's Figma section and the shipped thinking arm, which is the node's existing implementation.

## Context

On mobile the band only draws while `isThinking` holds or a tool row is `Running` during `isBusy`, so a busy agent writing text, between tools, or after a denial shows nothing; the send-to-first-`turn_state` gap is dark; `isStalled` is never collected. Desktop keeps one label up for the whole running turn. This ticket ports desktop's `workingIndicatorState` order and its local-send window.

## Design

### One order, one pure function

Beside `StatusReading` in `ThreadScreen.kt`:

```kotlin
internal enum class StatusArm { None, Connection, Resetting, ApiRetry, Compacting, Stalled, TurnOutcome, Thinking, Working, RunningTool }

internal fun statusArm(
    connectionState: ConnectionState,
    resetting: Boolean,
    apiRetrying: Boolean,
    isCompacting: Boolean,
    isStalled: Boolean,
    hasTurnOutcome: Boolean,
    isThinking: Boolean,
    isBusy: Boolean,
    localSendPending: Boolean,
    hasOpenTool: Boolean,
): StatusArm
```

Order, top wins: Offline → `None` (unchanged: the Top overlay owns offline); not Connected → `Connection`; `Resetting`; `ApiRetry`; `Compacting`; `Stalled`; `TurnOutcome` (only when no local send is pending — see below); then the turn arms: `isBusy && hasOpenTool` → `RunningTool`; `isThinking` → `Thinking`; `isBusy` → `Working`; `localSendPending` → `Thinking`; else `None`.

Resetting moving above api-retry is the ticket's AC4 and matches desktop. Stall, compaction, api-retry and reset do not need a running turn.

**The local send hides a stale turn outcome.** A turn outcome lingers after a failed turn until the next `thinking`/`responding`. Sending again opens the local window; without this rule the old "Turn failed" would sit over the new send until the daemon speaks, which breaks AC2 ("the band reads Thinking…"). The user's new send is the start of a new turn, so the outcome is stale. An `idle` answer closes the window and the outcome shows again, as `nextTurnOutcome` keeps it on `idle`.

`StatusReading` takes the arm and draws. The three turn arms share **one** `when` branch calling `ThinkingIndicator`, so the glyph keeps its composition identity (and its pulse) across Thinking → Working → Running tool. `Stalled` rides the same branch for the same reason.

The `waitingForAnswers` arm in `ThreadStatusArea` stays above everything, unchanged.

### `ThinkingIndicator`

Gains two defaulted parameters, `isWorking: Boolean = false` and `isStalled: Boolean = false`. It draws when any of `isThinking`, `isWorking`, `isStalled`, `runningTool` holds. Label precedence inside: stalled → running tool → thinking (token reading as today) → working. The stall label is drawn in `colorScheme.error`; every other label stays `primary`. Existing callers compile unchanged.

New strings: `thread_working_label` "Working…", `cd_thread_working` "Claude is working", `cd_thread_working_codex` "Codex is working", `thread_stalled_label` "The turn seems to have stalled…", `cd_thread_stalled` "The turn seems to have stalled". Client-owned; nothing daemon-authored is interpolated.

`StatusReading` passes `progress` only on the `Thinking` arm while `isThinking` holds, so a leftover token reading from the previous turn never decorates the local-send "Thinking…".

### Local-send window (`ThreadViewModel`)

- `private val _localSendPending = MutableStateFlow(false)`; `val localSendPending: StateFlow<Boolean>`.
- Opens immediately before `repository.sendMessage(...)` in both `sendMessage` and `sendWithAttachments` — after the blank guard, the `attachmentsSending` guard and the uploads, so a blank, refused or upload-failed send never opens it.
- A throw from `repository.sendMessage` closes it and rethrows into `launchGuardedRepoCall`'s existing catches (failed send).
- An `init` collector over `liveSessionEvents` closes it on any `TurnState` for this `conversationId`. A collector in `init` rather than a `stateIn` pipeline, because the close must happen even when the screen is not collecting.
- The existing `repositoryAvailable` collector (already `distinctUntilChanged().drop(1)`) closes it on every availability change after the opening one: a drop and the reconnect both close it.
- Log: `event=local_send_window state=open|closed reason=<turn_state|send_failed|reconnect>` via `RelayLog.d`, static codes only.

### Wiring

`ThreadScreen` gains `isStalled: Boolean = false` and `localSendPending: Boolean = false`, passed into `ThreadStatusArea` → `statusArm`. `MainActivity`'s thread destination collects `vm.isStalled` and `vm.localSendPending` and passes them. `isThinking` keeps meaning the `thinking` phase.

## State + concurrency model

All new state is on `viewModelScope` (`Dispatchers.Main.immediate`): the send paths and both closing collectors run there, so open/close cannot interleave mid-statement. `localSendPending` is a hot `MutableStateFlow` exposed read-only. The two closing collectors live as long as the ViewModel; both are cancelled with `viewModelScope`.

## Error handling

A failed send (thrown `IllegalStateException`, `RelayErrorException`, `UnsupportedOperationException`, cancellation) closes the window before the existing guard swallows the error. No new UI error surface.

## Testing strategy

- **Unit, `StatusArmTest`** (`app/src/test/.../thread/`): the full order table — Reset + api-retry together → `Resetting` (AC4); stall over thinking/working/running tool; api-retry, compaction and reset over stall; turn outcome over thinking; local send over a stale outcome; local send → `Thinking`; busy without phase thinking → `Working`; open tool only while busy; offline → `None`.
- **Unit, `ThreadViewModelLocalSendTest`**: opens on an accepted text send and on an attachment send; stays open until a `turn_state`; closes on `turn_state` for this conversation (each phase) but not for another conversation; closes on a failed send; closes on reconnect; blank send and a send refused while attachments are sending never open it; a failed upload never opens it.
- **Scripted, `ScriptedStatusLineTest`** (`app/src/sharedTest/`, Robolectric, real repository fold via `ScriptedThreadHarness`): the AC1 sequence thinking → responding + deltas → `tool_use` → `tool_progress` → `tool_result` → responding → denied call → idle, asserting the label at every step; the AC3 stall onset, precedence (over a running tool; under api-retry, compaction, reset) and clear by the next live event, including the stall label's error colour via the text layout's style.
- **Updated**: `RunningToolIndicatorTest.denial_removesTheLabel_andARespondingBandGoesEmpty` now expects "Working…" (AC1 changes that behaviour); `ScriptedResettingTest.apiRetry_winsOverResetting` becomes `resetting_winsOverApiRetry` (AC4).
- **Rung 3**: `InteractiveStreamE2ETest.interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` — a prompt that runs `echo` then answers in one sentence; from the tap on Send it samples inside `waitUntil` until the stop control has been seen and gone; any sample with the stop control present and no reading (the status glyph, or the compaction/api-retry reading) is recorded, and the list must be empty. Non-vacuity: at least one busy sample must have been taken. No rung-4 twin: the scripted Robolectric test above already proves the sequence deterministically.

## Documentation handoff (pending — documentation stage)

- `docs/knowledge/features/thinking-indicator.md` — the working label, the stall label, the local-send window.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` — the new arm order and `statusArm`.
- `docs/e2e-interactive-stream.md` — record the rung-3 scenario.

## Open questions

- Whether the live daemon emits `turn_state{responding}` before `tool_use` in every turn: irrelevant to correctness, since `isBusy` covers both phases.

Overlapping in-flight branches: #1308, #1309 (ThreadViewModel, e2e test), #1341, #1359 (ThreadScreen). None changes the status band's order or the send paths; a later merge may touch those files.

## Revisions

- **2026-10-01, verifier rework.** The rung-3 method joins the curated live list: `scripts/e2e-emulator.sh`'s LIVE `TEST_TARGET` gains it after `#interactiveTurn_toolPrompt_rendersToolStepInThread`, `android-test-gate.py` raises `LIVE_MINIMUM` by one, and `test_live_floor_matches_the_curated_list` expects 39 and asserts the method is listed (MUST FIX: the live gate never selected it). The sampler counts a sample as dark only when the stop control is present both before and after its reading checks, so `turn_state{idle}` between reads is not an empty band; its "other reading" now also covers Reset session, the connection arm and waiting for answers (SHOULD FIX). The stall-colour assertion reads the composed theme's `colorScheme.error` through `ScriptedThreadHarness.colorScheme` instead of the static light scheme.

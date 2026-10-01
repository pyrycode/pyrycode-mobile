# #1321 — Disable permission and question answers while the host is not connected

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `connectionState`, `onModalOption`, `onModalCancel`, `sendAnswer`, `sendCancel`, `onQuestionEvent`, `sendQuestion`, `onAlwaysAllowChanged` — the send paths that get the tap-time gate; `connectionState` is `WhileSubscribed` with an optimistic `Connected` seed, so it cannot be the gate.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConnectionStateSource.kt` → `ConnectionStateSource.observe` — only a cold flow; no synchronous current value.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the thread destination's anonymous `ConnectionStateSource` — production `observe()` is `coordinator.connectionStatus.map { … }` (handshake-gated since #1318), `flowOf(Offline)` without a bundle.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` → `permissionRequestItems`, `PermissionRequestCard`, `ModalOptionButton`, `AlwaysAllowOffer` — the inline permission controls.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `ModalCancelButton` — already has an `enabled` parameter (its existing disabled state).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt` → `QuestionBatchActions`, `QuestionBlock` — the question Cancel/Continue and the choice rows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionModalState.kt` → `locked`, `canContinue`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — composes both inline prompts and already receives `connectionState`.
- `app/src/test/.../ThreadViewModelPermissionTest.kt`, `ThreadViewModelQuestionTest.kt` — Unconfined main; the question file has one `StandardTestDispatcher` case that calls `runCurrent()` after construction.
- `app/src/sharedTest/.../ThreadScreenModalTest.kt`, `ThreadInlineQuestionTest.kt` — screen-test setup the new tests mirror.

No in-flight `feature/*` branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=639-2242 (inline permission request) and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910 (Offline thread).

No new visuals: the ticket keeps the #1305/#1306 drawings and uses their existing disabled states — Material's disabled button colours for the option buttons and Continue/Cancel, `ModalCancelButton(enabled = false)` for the permission Cancel. The Figma MCP was not authenticated in this run; nothing here depends on a value read from it.

## Context

The inline permission and question prompts send through a closed connection while the host is down; the user may believe they answered. Desktop's `usePromptResponseAvailability` / `canRespondToPromptNow` disable every sending control while the prompt's host is not connected and re-check at tap time. "Not connected" is every `ConnectionState` except `Connected` (including `Connecting` and `Reconnecting`).

## Design

### ViewModel: a tap-time connection read

- New private `hostConnection: StateFlow<ConnectionState?>` = `connectionStateSource.observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)`. Eager, so it is current with no UI collector; seeded `null` (unknown), never an optimistic `Connected`.
- New private `fun hostConnectedNow(): Boolean = hostConnection.value == ConnectionState.Connected`.
- The public `connectionState` flow is unchanged (the banner keeps its optimistic seed).

Gates, each a pure early return with no state change:

- `onModalOption`: after the existing scoping and stale-id guards, `if (!hostConnectedNow()) return` — before the default send, the armed second-tap send, or arming. An existing arm is kept, so after reconnect the armed second tap still sends.
- `onModalCancel`: same position, before clearing the arm and the grant draft.
- `onQuestionEvent`: `Continue` and `Cancel` return before `sendQuestion` (which locks via `Sending`). Option toggles and other text are not gated.
- `onAlwaysAllowChanged`: not gated (local choice).

The #1305 generation/lock handling and the #1306 scoping, arming reset and stale-id guards are untouched. A gated tap logs `event=prompt_send_blocked kind=<permission_option|permission_cancel|question_answer|question_refuse> reason=not_connected` via `RelayLog.d` (static codes only).

### UI: disable sending controls

- `permissionRequestItems(…, connected: Boolean, …)`: `ModalCancelButton(enabled = connected)`; `ModalOptionButton(enabled = connected)` passed to each M3 button's `enabled`, so a disabled tap neither sends nor arms. `AlwaysAllowOffer` stays enabled.
- `QuestionBatchActions(state, connected, onEvent)`: Cancel `enabled = !state.locked && connected`, Continue `enabled = state.canContinue && connected`. `QuestionBlock` keeps `enabled = !pending.locked` — picks and other text stay editable.
- `ThreadScreen` computes `val connected = connectionState == ConnectionState.Connected` and passes it to both.

## State + concurrency model

`hostConnection` is one more `Eagerly` collection in `viewModelScope`, cancelled in `onCleared` like `currentModal` and `draft`. Under `Dispatchers.Main.immediate` it updates in the same frame as a main-thread emission. No new jobs on the send paths.

## Error handling

A gated tap is a silent no-op: the control is already drawn disabled, and the existing connection banner explains why. No send-error notice fires. The existing `RelayErrorException` / `IllegalStateException` handling for a send that still loses the race stays.

## Testing strategy

Unit (`ThreadViewModelPermissionTest`, `ThreadViewModelQuestionTest`), each switching a `FakeConnectionStateSource` to `Offline` immediately before the call, never collecting `connectionState`:

- Permission default option while Offline → nothing sent; after `Connected` the same tap sends.
- Non-default first tap while Offline → not armed; after `Connected` it arms.
- Armed second tap while Offline → nothing sent, arm kept; after `Connected` the second tap sends.
- Cancel while Offline → nothing sent, grant draft and arm kept; after `Connected` cancel sends.
- Question Continue while Offline → not locked (`Idle`), nothing sent, picks kept; after `Connected` Continue sends the kept answers.
- Question refuse while Offline → not locked, nothing sent; after `Connected` refuse sends.
- A source that has not emitted yet (unknown) gates like Offline.

Screen (`app/src/sharedTest`, Robolectric):

- `ThreadScreenModalTest`: inline permission card with `connectionState` held in state — Offline: options and Cancel not enabled, tapping an option forwards nothing, the session-grant checkbox still toggles; switch to Connected: all enabled and a tap forwards.
- `ThreadInlineQuestionTest`: Offline: Continue and Cancel not enabled, an option row still dispatches `OptionToggled`, the picked selection still shows; Connected: Continue and Cancel enabled.

Not an operator-facing new flow (it removes an action), so no rung-3 scenario.

## Open questions

- Whether any existing VM test drives a permission/question send under a `StandardTestDispatcher` main without `runCurrent()` after construction (it would now see `null`). Resolve by running the affected classes.

## Documentation handoff

Pending for the documentation stage: fold the "prompt answers require a connected host; tap-time gate reads an eager, null-seeded copy of the connection source" rule into `docs/knowledge/features/modal-answer-flow.md` (§ "The fail-safe-deny belt" or a new subsection beside "Stale taps") and `docs/knowledge/features/question-batch-modal.md` (§ "Batch ownership" and § "Rendering"). The ticket names no specific doc section.

## Revisions

**2026-10-01 (implementation).**
- The gate helper is `promptSendAllowed(kind)` rather than `hostConnectedNow()`: it does the same `hostConnection.value == Connected` read and also emits the planned `prompt_send_blocked` log, so each call site is one condition.
- The permission race cases live in `ThreadViewModelTest`, beside the existing #451 `onModalOption` cases, not in `ThreadViewModelPermissionTest`, which covers the composer's permission *mode*. `vmWithModalSendPath` gained a `source` parameter.
- Open question resolved: the affected classes (`ThreadViewModel*`, the thread package's screen tests, `RelayConnectionFactoryTest`) all pass. The one `StandardTestDispatcher` question case already calls `runCurrent()` after construction, so its eager collector reports `Connected` before any send.

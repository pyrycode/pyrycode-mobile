# #872 — Show Reset session's phase in the thread status area

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.observeResetting`, `ResetStatus` (`Phase`, `Handoff`) — the #871 seam this ticket consumes; defaults to `flowOf(null)`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ResettingProjection.kt` → `ResettingProjection` — the falling-edge and `session_transition` clears already live here, so the VM and screen add no clearing logic.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `ThreadViewModel.isCompacting` — the `.stateIn` hoist the new `resetting` flow clones.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` (trailing defaulted status params), `ThreadStatusArea` (the one-slot `when` and its precedence KDoc).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → the `CONVERSATION_THREAD` destination's `collectAsStateWithLifecycle` block and `ThreadScreen(...)` call.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CompactingIndicator.kt` → `CompactingIndicator` — the M3 row shape (spinner + `bodySmall`/`onSurfaceVariant`, merged semantics) the new indicator reuses.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt` → `pushCompacting`, `compactingEnvelope`, `ScriptedThreadHarness.start` — where the new `pushResetting` helper and the `resetting` collection go.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedCompactingTest.kt` → the content-description assertion idiom (`awaitDisplayed` / `awaitGone`).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → `CompactingControllableRepo`, the `isCompacting_*` tests the `resetting_*` tests mirror.
- `docs/knowledge/features/resetting-state.md` — the data contract: two rising edges replace (not stack), `null` = not resetting, fields carried independently (so `WrappingUp`+`Written` and `Restarting`+`Pending` are representable and the render must stay total), and "the render ticket must not block interaction on it".
- `docs/knowledge/features/compacting-indicator.md`, `usage-limit-indicator.md` — ladder rationale; precedence is single-sourced in `ThreadStatusArea`, not the VM.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8 (status area sub-node `111:3525`)

The status area is one row: a small leading glyph and a `bodySmall` label (Figma shows "Thinking..." in `Schemes/Primary`), with an optional trailing action button. Per the ticket, the resetting arm reuses the shipped `CompactingIndicator` shape — a 16dp indeterminate `CircularProgressIndicator` plus a `bodySmall` / `onSurfaceVariant` label in a 16dp/8dp padded `Row` — so it sits in the slot exactly like its compaction and api-retry siblings.

## Context

After Reset session the thread pauses through the wrap-up turn and the respawn with nothing on screen but, at best, generic thinking. #871 decoded the `resetting` envelope into `observeResetting(conversationId): Flow<ResetStatus?>`; this ticket renders it as one arm of the composer's status slot. No ADR needed — it extends the established status-slot pattern.

## Design

- **`ThreadViewModel.resetting: StateFlow<ResetStatus?>`** — `repository.observeResetting(conversationId).stateIn(viewModelScope, WhileSubscribed(5_000), null)`, a clone of `isCompacting`. No extra operator: the projection already dedups, and a phase change is a distinct `ResetStatus` so it reaches the screen. Scoping to the open conversation and host comes from the VM's own `conversationId` and the host-bound repository it already holds, the same as every sibling.
- **`ResettingIndicator(status: ResetStatus?, modifier: Modifier = Modifier)`** — new file `ui/conversations/components/ResettingIndicator.kt`. Early-return on `null`. Same `Row` as `CompactingIndicator`; the label comes from `stringResource(resettingLabelRes(status))` and the merged `contentDescription` is that same label (one wording source, the `UsageLimitIndicator` precedent).
- **`internal fun resettingLabelRes(status: ResetStatus): Int`** (`@StringRes`) — total over the two enums:
  - `WrappingUp` (any handoff) → `thread_resetting_wrapping_up` "Claude is writing a handoff note for the next session"
  - `Restarting` + `Written` → `thread_resetting_restarting_written` "Restarting · handoff note saved"
  - `Restarting` + `Skipped` → `thread_resetting_restarting_skipped` "Restarting without a handoff note"
  - `Restarting` + `Pending` → `thread_resetting_restarting` "Restarting" (not a documented wire combination, but representable; claims no outcome)
- **`ThreadScreen`** gains `resetting: ResetStatus? = null` beside `isCompacting` in the trailing defaults; existing call sites keep compiling. It passes it to `ThreadStatusArea`.
- **`ThreadStatusArea`** gains `resetting: ResetStatus?` and the arm `resetting != null -> ResettingIndicator(...)` between usage limit and compaction. KDoc updated to the ladder api-retry → usage limit → resetting → compaction → turn outcome → thinking, with the ticket's rationale (below the two "something may be wrong" signals; above compaction/thinking because the wrap-up is itself a claude turn; above a lingering pre-reset turn outcome).
- **`MainActivity`** collects `vm.resetting` and passes it through.

## State + concurrency model

One new `StateFlow` on `viewModelScope`, `WhileSubscribed(5_000)`, cancelled with the VM. No new jobs, dispatchers or local UI state. The indicator is pure; it does not block input (the composer stays live, per resetting-state.md).

## Error handling

None new. Malformed or out-of-set frames are dropped upstream in `ResettingProjection`; a stuck reading clears on `session_transition` or reconnect. The arm renders only local string resources chosen by enum; no daemon text reaches Compose.

## Testing strategy

- **JVM** `ResettingIndicatorLabelTest` — `resettingLabelRes` for all six enum combinations (wrapping up ignores handoff; the three restarting outcomes).
- **JVM** `ThreadViewModelTest` — `resetting_initialValue_isNullWithPlainFake`; `resetting_reflectsPhaseChangeThenClear` (wrapping_up → restarting/written → null, each push drained); `resetting_observesOnlyOwnConversationId`. New `ResettingControllableRepo` double beside `CompactingControllableRepo`.
- **androidTest** `ScriptedThreadHarness.pushResetting(active, phase, handoff, conversationId = this.conversationId)` plus a private `resettingEnvelope`; `start()` passes `resetting = vm.resetting.collectAsState().value`.
- **androidTest** `ScriptedResettingTest` (rung 2):
  - three-frame sequence: wrapping_up/pending → restarting/written → falling edge; the slot shows each phase in turn, the previous label is gone (one slot, no second indicator), and it clears.
  - restarting/skipped shows the skipped text.
  - a `session_transition` clears the arm.
  - resetting replaces thinking and compaction; api-retry wins over resetting.
  - a `resetting` frame for another conversation id never shows here.
- Live behaviour is #679's (rung 3); no rung-3 scenario here per the AC.

## Open questions

- None blocking. `WrappingUp`+`Written`/`Skipped` renders the wrapping-up text (the phase decides the reading; the handoff outcome is only meaningful once restarting).

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/resetting-state.md` still says the consumer is "not yet filed"; it and the status-slot ladder sections (`compacting-indicator.md`, `usage-limit-indicator.md` § Placement) need the new arm. A new `resetting-indicator.md` overview is the documentation stage's call.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the single untrusted→trusted boundary stays #871's `ResettingProjection.decodeResetting`, which narrows `phase` / `handoff` to `ResetStatus`'s closed enums. This ticket only reads that enum pair: `resettingLabelRes` is an exhaustive `when` over the enums returning a local `@StringRes`, so no daemon or claude string, number or id reaches Compose, a URL, a filename or a log through the new arm.
- [Trust boundaries] No findings — cross-conversation scoping: `ThreadViewModel.resetting` observes only the VM's own `conversationId` through the host-bound repository it already holds, like `isCompacting`. Proven by `resetting_observesOnlyOwnConversationId` and the scripted other-conversation case.
- [Threat model — hostile daemon] Accepted, documented — a daemon that sends a rising edge and never a falling one keeps the arm showing, and because resetting outranks compaction, turn outcome and thinking, it would mask those three. It cannot mask api-retry or usage-limit (both rank above), the stall banner (a separate affordance), or the interrupt control and composer (the arm blocks no input). A `session_transition` or reconnect clears it. This is the same posture #871 and `compacting` already accept; no client-side timeout owed by the wire contract.
- [Tokens / File / IPC / Crypto / Network] Not applicable by design — no token, key, storage, intent, deep link, WebView, crypto primitive or network call is added; the change is one `StateFlow` hoist, one stateless composable and four string resources.
- [Logs] No findings — the plan adds no logging; nothing on the render path logs the status or the conversation id.
- [Concurrency] No findings — one `stateIn` on `viewModelScope` with `WhileSubscribed(5_000)`, cancelled with the VM; no mutable shared state, no new job.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

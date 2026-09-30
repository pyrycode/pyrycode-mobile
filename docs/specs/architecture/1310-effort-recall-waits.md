# #1310 — Effort recall waits for an addressable session and offered level

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/EffortRecall.kt` → `EffortRecall.decide`, `cancel`, `awaitWrite` — the once-per-opening decision and cancellation boundary.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadUiState.kt` → `ThreadRunConfig.writable`, `effortChoices`, `pending` — session eligibility and model-specific offered levels.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `state`, `onEffortSelected`, `startEffortRecall`, `sendSessionSettings` — every reading is offered; taps cancel recall; acknowledgement and rejection use the existing write path.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelEffortRecallTest.kt` → `ScriptedRepo`, `MemoryStore`, `reading` — existing recall, saved-choice, tap, rejection and first-message coverage.
- `docs/knowledge/features/thread-composer-footer-effort-recall.md` § Remembered effort recall — preserve live-session isolation, success-only remembering and content-free logging.
- `docs/knowledge/features/thread-composer-footer-testing.md` § Testing and `docs/knowledge/features/development-verification.md` § Gradle and source checks — focused unit proof and existing integration coverage.
- `docs/e2e-interactive-stream.md` § Model and effort settings round trip — existing rung-3 `interactiveTurn_rememberedEffort_recalledAfterRestartIntoFreshChatAndChannel` covers recall through the real daemon.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=600-1694

The ticket specifies behaviour only, with no visual change. This change touches only recall eligibility; no Compose layout, styling, resources or interaction geometry changes are planned.

## Change

`EffortRecall.decide` keeps recall undecided while the reading has no addressable session or its selected model does not offer the remembered level. Later offers can then start one write, provided saved effort is still empty. No remembered value and a saved effort remain terminal; `cancel` remains terminal even during either new waiting condition. Set `decided` before invoking `start`, so synchronous state emissions and failed/rejected writes cannot start a second recall. Preserve the existing loading, menu, pending-write and replaced-session guards and static outcome logs. Update the collaborator's KDoc to describe waiting rather than deciding at the first usable reading.

There are no new types, subscriptions, jobs or error paths. All entry points remain on Main and the existing write remains owned by `viewModelScope`; cancellation and `awaitWrite` are unchanged. No dependency or wire change is needed.

Sizing: one deliverable, one production file, approximately 20 production lines plus 80 test/plan lines; no exported declarations or consumer updates, two acceptance criteria and no new reject branches. This remains XS. The refreshed remote feature branches have no overlap with the planned files.

## Testing strategy

- Extend the no-session unit case: no initial write, a later live session and matching reading start exactly one write, subsequent empty-effort readings never repeat it.
- Extend the unoffered-level unit case: a selected model lacking the remembered level writes nothing; a later model offering it starts one write, and later readings never repeat it.
- Prove a saved effort or effort tap received while waiting ends recall even if a later empty-effort reading becomes eligible.
- Run the complete `ThreadViewModelEffortRecallTest` class, including rejection, saved-choice, tap, pending-write and message-order regressions. Observe the new eligibility assertions fail before the implementation, then pass afterwards.
- Run focused unit tests, Android lint, debug assembly, formatting and the forced formatting check. No screen or device test edits are needed. Existing rung-3 recall proof is retained; full regression and live execution remain dispatcher-owned and are not claimed as local passes.

## Documentation handoff

The ticket names no documentation requirement. Pending for the documentation stage: update `docs/knowledge/features/thread-composer-footer-effort-recall.md` § Remembered effort recall to describe `no_session` and `unpublished` as waiting conditions rather than terminal skips, and update the corresponding testing summary in `docs/knowledge/features/thread-composer-footer-testing.md` § Testing.

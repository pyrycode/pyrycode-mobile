# #1411 — compute the context percentage from token totals, falling back to session settings

## Files read

- `ui/conversations/thread/ThreadViewModel.kt` — `runConfigFlow`, the combine that sets `contextPercent` from `ContextUsage.percentage`; top-level `runConfig` beside which the new pure function sits.
- `ui/conversations/thread/ThreadUiState.kt` — `ThreadRunConfig.contextPercent` KDoc states the #946 "verbatim, never derived" rule.
- `data/repository/ConversationRepository.kt` — `observeContextUsage` and `ContextUsage` KDocs state the same rule.
- `data/network/ContextUsagePayloads.kt` — `toReading` KDoc ("never recomputed from the token counts").
- `test/.../thread/ThreadViewModelContextUsageTest.kt` — the #946 tests that pin the old rule; rewritten to the new one.
- Desktop `contextTokenSource.ts` / `contextUsage.ts` — the reference: a present reading always wins (even with `maxTokens == 0`), absent falls back to settings; `round(used / window * 100)` clamped to 0–100, `null` for a non-positive or non-finite window.

## Design source

Figma node 110-3497 (`Cxt: 84%`). The footer and Status sheet look does not change; only where the number comes from. No composable is touched.

## Change

Add `internal fun contextPercent(usage: ContextUsage?, settings: SessionSettings?): Int?` at the bottom of `ThreadViewModel.kt`. It picks the token pair (`usage.totalTokens`/`maxTokens` when a reading is present, whatever it holds; else `settings.usedTokens`/`windowTokens`; else none) and returns `null` for no pair or a window `<= 0`, otherwise `used * 100 / window` in `Double`, rounded half-up (desktop's `Math.round`) and clamped to 0–100. Claude's `percentage` field is no longer read for display.

`runConfigFlow` carries the `SessionSettings` alongside the config through its two chained combines (as a pair), so the final combine with `observeContextUsage` sets `contextPercent = contextPercent(usage, settings)`: one place, which feeds both the footer (`ThreadComposerFooter`) and the Status sheet (`ThreadScreen` passes `runConfig.contextPercent`). `sessionSettings` is not subscribed twice, because its `onEach` has side effects on pending state.

The decoder still drops a frame with a negative `percentage` (unchanged wire handling); only KDocs change in `ContextUsagePayloads.kt`, `ConversationRepository.kt` and `ThreadUiState.kt`, to say the shown percentage is computed from the reported totals with the settings pair as fallback (#1411, reversing #946).

## Testing strategy

- New `ContextPercentTest` (unit, `app/src/test/.../thread/`): reading wins over settings; reading with `maxTokens = 0` gives `null` even with usable settings; settings fallback; neither gives `null`; settings window `0` gives `null`; rounding edges (0.5% → 1, 0.49% → 0, 84.5% → 85); clamp (over-full → 100, negative used → 0).
- `ThreadViewModelContextUsageTest` rewritten: a reading shows the computed value (not its `percentage`); settings figures fill in with no reading; a reading replaces the settings figure and clearing it falls back; another conversation's reading does not appear. These assert the single `runConfig.contextPercent` both surfaces read.
- Existing footer/Status sheet screen tests set `contextPercent` directly and are unaffected.

# #1412 — Footer context reading warns at 50 and 70 percent

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt` — `ContextSegment`, which draws the `Cxt:` reading in `colorScheme.primary` at every value.
- `app/src/main/java/de/pyryco/mobile/ui/theme/WarningColors.kt` — the `ColorScheme.warning` extension.
- `app/src/main/res/values/strings.xml` — `thread_footer_context`, `cd_context_usage`.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterTest.kt` — the #946 context-segment tests.

## Design source

Figma `110:3497` (`Cxt: 84%`). As on desktop #1062, the thresholds and colours are the operator's ruling rather than a design state, so the node shows only the normal reading. The text style (`bodySmall`) does not change.

## Change

Add `internal enum class ContextUsageStep { Normal, Warning, High }` and `internal fun contextUsageStep(percent: Int): ContextUsageStep`, mirroring desktop's `contextUsageStep`: below 50 is `Normal`, 50 to 69 is `Warning`, 70 and above is `High`. `ContextSegment` colours a reading by its step: `colorScheme.primary`, `colorScheme.warning`, `colorScheme.error`. At `High` the text becomes "Cxt high: N%" (new `thread_footer_context_high`) and the content description "Context usage high, N%" (new `cd_context_usage_high`). The unavailable state, the test tag and the layout are unchanged.

## Testing strategy

- Unit test `ContextUsageStepTest` under `app/src/test/`: 0 and 49 are `Normal`, 50 and 69 are `Warning`, 70 and 100 are `High`.
- Screen test in `ThreadComposerFooterTest`: renders `ThreadComposerFooter` inside the theme at 49, 50, 69 and 70, reads each text's colour from its `TextLayoutResult` and compares it with the theme's `primary`, `warning` and `error`; at 70 asserts "Cxt high: 70%" and the high content description, and at 69 the ordinary "Cxt: 69%".

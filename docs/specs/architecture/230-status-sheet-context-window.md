# 230 — StatusSheet: Context window section

Issue: [pyrycode/pyrycode-mobile#230](https://github.com/pyrycode/pyrycode-mobile/issues/230). Size: S.

## Context

Sibling slices #254 (scaffold + Model section) and #229 (Effort + YOLO sections) landed the StatusSheet shell, the four-section growable `StatusSheetContent` Column, the `Effort` + `Model` enums in `ThreadUiState`, and the inert-stub-on-companion pattern (`STUB_TOKEN_PERCENT = 73` populates `state.tokenPercent`).

This slice adds the fourth and final section to the sheet — **Context window** — a read-only visualisation of token utilisation. There is no user input, no event surface, no new mutator. The work is entirely additive:

1. Two `Int` stub fields on `ThreadUiState` (`tokensUsed`, `tokensTotal`).
2. Two companion-object constants (`STUB_TOKENS_USED`, `STUB_TOKENS_TOTAL`) populated in the existing main `combine` lambda alongside `STUB_TOKEN_PERCENT`.
3. A new private `ContextWindowSection` composable in `StatusSheet.kt`, plus a private `formatTokens` integer-K helper and a private `progressColor` threshold helper.
4. Three additive parameters on `StatusSheet` / `StatusSheetContent` (`tokenPercent: Int = 0`, `tokensUsed: Int = 0`, `tokensTotal: Int = 0`) — **with `= 0` defaults** so existing test/preview call sites compile untouched (see § Edit fan-out below).

No ViewModel logic, no flow added, no Phase 4 swap-point earlier than necessary. The two new stub constants share `// Phase 4 swap point: replace with backend AgentStatus flow.` with the existing `STUB_TOKEN_PERCENT` — same swap, same comment.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100

The "Context window" region sits at the bottom of node `20:100`, immediately under the YOLO row. From top to bottom: a `label-large` `onSurfaceVariant` "Context window" header (same paddings as the existing Model/Effort/YOLO headers); a `body-large` `onSurface` label `"73% used (146K of 200K tokens)"`; an 8dp-high pill-shaped progress bar with `surface-container-highest` track and a `warning`-coloured fill at 73%; a `body-small` `onSurfaceVariant` caption `"When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll)."`. Container is `Column(verticalArrangement = Arrangement.spacedBy(8.dp))` with `padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)`.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt:1-353` — full file (after #229). The `SectionHeader` paddings to reuse (line 130-140), the existing private composable shape (`EffortChipRow`, `YoloRow`), the `StatusSheetContent` Column body to extend (line 71-99), the public `StatusSheet` signature to extend (line 41-69), and the five `@Preview` composables (lines 240-353) — these last must keep compiling with the defaulted new params.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:53-67,112-144,239-243` — `ThreadUiState` data class to extend with two `Int` fields; the `state` combine lambda at lines 119-135 that populates `tokenPercent = STUB_TOKEN_PERCENT` (mirror with the two new fields); the companion object holding `STUB_TOKEN_PERCENT = 73` (add two sibling constants).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt:81-88` — the `tokenPercentColor` helper used by the status row. **Do not share** with the new progress-bar helper (different semantic — text emphasis vs fill chroma); see § Design / `progressColor` below for the rationale.
- `app/src/main/java/de/pyryco/mobile/ui/theme/WarningColors.kt:13-24` — the `ColorScheme.warning` composition-local extension (from #119). Already in use by `ThreadStatusRow.kt:85`; the new progress-bar helper uses it the same way.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:45,87-95,216-225,310-490` — confirms the only production call site of public `StatusSheet` (line 234ish, count from `StatusSheet(`); confirms the four `@Preview` composables that build `ThreadUiState(... tokenPercent = 73 ...)` directly and need two new constructor args added.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt:1-274` — 10 existing `StatusSheetContent(...)` call sites. These do **not** need updates — the new params default to `0`, and these tests don't exercise the Context window section.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:195-219` — the two existing initial-value / post-subscription assertions that already check `tokenPercent`. Add two assertion lines each (`tokensUsed`, `tokensTotal`).
- `docs/specs/architecture/229-status-sheet-effort-yolo.md` — sibling spec for the previous slice; mirror conventions on naming, preview shape, and test scaffold style.

## Design

### `ThreadUiState` — `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`

Add two `Int` fields after `tokenPercent`, defaulting to `0`. Maintain field order: the three "context window" related fields stay adjacent.

```kotlin
val tokenPercent: Int = 0,
val tokensUsed: Int = 0,
val tokensTotal: Int = 0,
```

Pure additive change; no rename, no type swap. No new imports needed (`Int` is in scope).

### `ThreadViewModel` companion — same file

Replace the single-constant companion with three constants. Single shared swap-point comment (the three are all replaced together when the Phase 4 backend `AgentStatus` flow lands).

```kotlin
companion object {
    // Phase 4 swap point: replace with backend AgentStatus flow.
    private const val STUB_TOKEN_PERCENT = 73
    private const val STUB_TOKENS_USED = 146_000
    private const val STUB_TOKENS_TOTAL = 200_000
}
```

The values are not arbitrary: `73% × 200_000 = 146_000` matches the Figma reference (`"73% used (146K of 200K tokens)"`), and matching keeps the "this is the stub" reader signal sharp — anyone seeing `73 / 146_000 / 200_000` in the running app immediately recognises the placeholder.

### `ThreadViewModel.state` populator — same file, lines 119-135

Two-line additive change inside the existing `combine { ... } ThreadUiState(...)`. Insert immediately after the existing `tokenPercent = STUB_TOKEN_PERCENT,` line:

```kotlin
tokenPercent = STUB_TOKEN_PERCENT,
tokensUsed = STUB_TOKENS_USED,
tokensTotal = STUB_TOKENS_TOTAL,
```

No combine signature changes. No new flow source. No new dependency injected. The five-arg `combine` typed overload remains valid (we are not adding a sixth flow — only reading three constants).

### `StatusSheet` / `StatusSheetContent` — `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt`

Add **three new parameters with `= 0` defaults** to both the public `StatusSheet` and the internal `StatusSheetContent`, appended after `onYoloToggled`:

- `tokenPercent: Int = 0`
- `tokensUsed: Int = 0`
- `tokensTotal: Int = 0`

The defaults are load-bearing — they let the 10 existing `StatusSheetTest` call sites and the 4 existing in-file `@Preview`s (lines 240-353) compile untouched. The single production caller (`ThreadScreen.kt`) wires the three real values. See § Edit fan-out for the rationale (and why this is *not* a "default-param escape hatch" rationalisation).

Public `StatusSheet` passes the three params through to `StatusSheetContent` (named-arg forwarding, no logic).

Inside `StatusSheetContent`, after the existing `SectionHeader(text = "YOLO mode")` + `YoloRow(...)` block and before the closing `Spacer(modifier = Modifier.height(24.dp))`, add:

```kotlin
SectionHeader(text = "Context window")
ContextWindowSection(
    tokenPercent = tokenPercent,
    tokensUsed = tokensUsed,
    tokensTotal = tokensTotal,
)
```

The closing 24dp bottom spacer stays.

#### `ContextWindowSection` (new private composable)

Signature: `private fun ContextWindowSection(tokenPercent: Int, tokensUsed: Int, tokensTotal: Int)`.

Behaviour, top to bottom:

- A `Column` with `Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)` and `verticalArrangement = Arrangement.spacedBy(8.dp)`.
- Label `Text("$tokenPercent% used (${formatTokens(tokensUsed)} of ${formatTokens(tokensTotal)} tokens)", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)`.
- `LinearProgressIndicator` with: `progress = { (tokenPercent.coerceIn(0, 100) / 100f) }`, `modifier = Modifier.fillMaxWidth().height(8.dp)`, `color = progressColor(tokenPercent)`, `trackColor = MaterialTheme.colorScheme.surfaceContainerHighest`. Use the M3 stop-indicator-suppressing params if the BOM version exposes them (`gapSize = 0.dp, drawStopIndicator = {}`) — Figma `20:153/154` shows no stop dot or gap. If the BOM-pinned M3 version on this branch does not expose those params, accept the default stop indicator for this slice and file a follow-up; do not bump the BOM here.
- Caption `Text("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`. Copy is **verbatim from Figma `20:155`** including the lowercase "claude's".

The caption string stays inline (matches `StatusSheet`'s existing convention of inlining UI copy; no `strings.xml` extraction this slice).

#### `formatTokens` (new private helper)

Signature: `private fun formatTokens(n: Int): String`.

Behaviour: integer-divide by 1000, append `"K"`. `formatTokens(146_000) == "146K"`, `formatTokens(200_000) == "200K"`, `formatTokens(0) == "0K"`, `formatTokens(999) == "0K"` (intentional truncation — sub-1K precision is not meaningful for context-window display, and rounding to nearest would only matter for hypothetical mid-range values the producer will never emit).

Not a top-level helper, not a shared util, no new module. Per ticket Technical Notes: "single formatter helper site... not a shared util module for it."

#### `progressColor` (new private helper)

Signature: `@Composable private fun progressColor(percent: Int): Color`.

Behaviour:

```kotlin
val clamped = percent.coerceIn(0, 100)
return when {
    clamped < 50 -> MaterialTheme.colorScheme.primary
    clamped < 95 -> MaterialTheme.colorScheme.warning
    else -> MaterialTheme.colorScheme.error
}
```

**Below-50% choice resolved:** `colorScheme.primary` (the M3 `LinearProgressIndicator` default). Rationale per ticket Technical Notes: a single binary choice between `primary` and the M3 default — these are the same thing, so we take `primary` and avoid introducing a third tone. The 50% and 95% thresholds are the same boundary values as `ThreadStatusRow.tokenPercentColor` — but the colours differ (`onSurfaceVariant` vs `primary` for <50%), so the two helpers express different visual intents and stay separate.

**Why not share with `ThreadStatusRow.tokenPercentColor`:** that helper colours **text** (`onSurfaceVariant` is a low-emphasis text colour); this helper colours **fill** (`primary` is a fill-chroma colour). Sharing would force one of the two consumers to use the wrong slot. Two functions, ~8 LOC each — duplicated boundaries are cheap; a wrong slot is not.

### `ThreadScreen` — `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Two edits:

1. **`StatusSheet(...)` invocation** (around line 234, after the `onYoloToggled = ...` arg): add three lines wiring the new params from state:
   ```kotlin
   tokenPercent = state.tokenPercent,
   tokensUsed = state.tokensUsed,
   tokensTotal = state.tokensTotal,
   ```
   The `state.tokenPercent` read is already used by `ThreadStatusRow` (line 94) — same source, same `state`, no new collection.

2. **Four `@Preview` composables** (lines ~310, ~338, ~463, ~484 — every `ThreadUiState(...)` construction that already passes `tokenPercent = 73,`): add two adjacent lines:
   ```kotlin
   tokenPercent = 73,
   tokensUsed = 146_000,
   tokensTotal = 200_000,
   ```
   This keeps the four preview screens visually consistent with the production stub data. They render the bar at 73% (warning tier), matching the in-app stub.

No imports change. No parameter additions to `ThreadScreen`'s signature (it does not surface tokens-used/total upstream — they are read directly off `state`).

### `MainActivity` / Koin

No changes. The new fields ride through the existing `ThreadViewModel` injection unchanged.

## State + concurrency model

- **No new state, no new flows.** The three values are added to `ThreadUiState` (a `data class`) and populated from companion-object constants inside the existing `state` combine lambda. The existing five-arg typed `combine` overload is preserved untouched.
- **Hot/cold:** no change. `state: StateFlow<ThreadUiState>` continues to use `stateIn(WhileSubscribed(5_000))`. The two new fields are emitted on every state update (same as `tokenPercent` today) and the values are constant, so there is no recomposition cost beyond the initial composition.
- **Dispatcher:** no change. No I/O, no `viewModelScope.launch`. The two new `Int` fields are read in the combine lambda on whichever dispatcher the upstream `combine` runs on.
- **Recomposition correctness:** `LinearProgressIndicator` receives a `progress: () -> Float` lambda (deferred read). The lambda closes over `tokenPercent`, which is stable (`Int` is `@Stable`). `progressColor` reads `MaterialTheme.colorScheme` and is `@Composable` — it recomposes when the theme changes, which is correct. `formatTokens` is a pure non-`@Composable` function and produces a stable `String`.
- **Configuration changes:** Phase 0 stub values are static. On rotation, the recreated ViewModel re-derives the same three constants. No persistence concern.

## Error handling

No new failure modes. The three stub values cannot fail to materialise. `tokenPercent.coerceIn(0, 100)` guards the progress lambda against out-of-range stub data (and against future producer bugs in Phase 4). `formatTokens` integer-divides; no zero-divide path. The caption text is a static string literal.

If a future Phase 4 producer feeds `tokensTotal = 0`, the label renders `"NN% used (XK of 0K tokens)"` — semantically odd but non-crashing. That's a Phase 4 concern (the producer must guarantee `tokensTotal > 0`), not a Phase 0 stub concern.

## Testing strategy

Compose tests for the new section in `StatusSheetTest`; two-line assertion additions in `ThreadViewModelTest` for the populator. `./gradlew test` covers the ViewModel test additions; `./gradlew connectedAndroidTest` covers the StatusSheet tests. Both must pass.

### `StatusSheetTest` additions — `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`

The 10 existing tests stay untouched (new params default to `0`; tests don't exercise the new section). Add three new tests in the existing `setContent { PyrycodeMobileTheme { StatusSheetContent(...) } }` shape. For each new test, pass an explicit non-zero `tokenPercent` / `tokensUsed` / `tokensTotal` triple; other params keep their existing test values.

- **`renders_context_window_section_with_header_label_and_caption`** — render with `tokenPercent = 73, tokensUsed = 146_000, tokensTotal = 200_000`. Assert `hasText("Context window")` is displayed. Assert `hasText("73% used (146K of 200K tokens)")` is displayed (one assertion covers both the formatter and the label-assembly contract). Assert `hasText("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).")` is displayed.
- **`label_format_uses_integer_K_division`** — render with `tokenPercent = 5, tokensUsed = 12_345, tokensTotal = 200_000`. Assert `hasText("5% used (12K of 200K tokens)")` is displayed. This pins the integer-divide-truncation behaviour of `formatTokens` indirectly (private function; verified via the visible label).
- **`label_format_handles_zero_values_gracefully`** — render with defaults (`tokenPercent = 0, tokensUsed = 0, tokensTotal = 0`). Assert `hasText("0% used (0K of 0K tokens)")` is displayed. This is the test surface for the initial-state shape and prevents a regression into a crash on zero-total or a divide-by-zero shape change in `formatTokens`.

Three new tests. ~60 LOC of test code.

**Not tested:** progress-bar fill colour at the three threshold tiers. Compose colour assertions are awkward (`SemanticsNodeInteraction` does not expose draw colours), and the AC explicitly says `@Preview` is the verification surface for threshold transitions. The four new `@Preview`s below cover this.

### `ThreadViewModelTest` additions — `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`

Two existing tests to extend (lines 195-219), no new test methods:

- `state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults` (line 195) — after the existing `assertEquals(0, vm.state.value.tokenPercent)` line, add:
  ```kotlin
  assertEquals(0, vm.state.value.tokensUsed)
  assertEquals(0, vm.state.value.tokensTotal)
  ```
- `state_postSubscription_emitsDefaultModelEffortAndTokenPercent` (line 207) — after the existing `assertEquals(73, vm.state.value.tokenPercent)` line, add:
  ```kotlin
  assertEquals(146_000, vm.state.value.tokensUsed)
  assertEquals(200_000, vm.state.value.tokensTotal)
  ```

The literal values match the companion constants (`STUB_TOKENS_USED`, `STUB_TOKENS_TOTAL`); the constants stay `private` and are not exposed for test reference. The pattern mirrors the existing `assertEquals(73, ...)` line that references `STUB_TOKEN_PERCENT` by literal.

Four new assertion lines. ~6 LOC of test code.

### New `@Preview`s in `StatusSheet.kt`

Per AC: "`@Preview` shows the section at multiple token-% values (e.g. 20%, 60%, 88%, 97%) so the threshold transitions are visually verifiable."

Add four new `@Preview` composables at the bottom of `StatusSheet.kt`. Each follows the existing `StatusSheetOpusPreview` shape (line 240) — `PyrycodeMobileTheme(darkTheme = false) { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) { StatusSheetContent(...) } } }`, `widthDp = 412`. All four pass `selectedModel = Model.OPUS_4_7, selectedEffort = Effort.HIGH, yoloEnabled = false` for visual stability — only the three context-window params vary. For each, set `tokensUsed = (tokenPercent / 100f * 200_000).toInt()`, `tokensTotal = 200_000`.

- **`StatusSheetContextWindow20Preview`** — `tokenPercent = 20, tokensUsed = 40_000, tokensTotal = 200_000`. Bar fills 20%, `primary` colour (below 50%). Label `"20% used (40K of 200K tokens)"`.
- **`StatusSheetContextWindow60Preview`** — `tokenPercent = 60, tokensUsed = 120_000, tokensTotal = 200_000`. Bar fills 60%, `warning` colour (50-95% tier). Label `"60% used (120K of 200K tokens)"`.
- **`StatusSheetContextWindow88Preview`** — `tokenPercent = 88, tokensUsed = 176_000, tokensTotal = 200_000`. Bar fills 88%, `warning` colour. Label `"88% used (176K of 200K tokens)"`. (Mid-warning verifies a different fill width than 60%.)
- **`StatusSheetContextWindow97Preview`** — `tokenPercent = 97, tokensUsed = 194_000, tokensTotal = 200_000`. Bar fills 97%, `error` colour (≥95%). Label `"97% used (194K of 200K tokens)"`.

Four new preview composables. ~80 LOC. The existing five previews (lines 240-353) stay untouched; they render the section at 0% with primary colour, which is a valid empty/initial state preview.

## Edit fan-out

Counted call sites for the `StatusSheet` / `StatusSheetContent` signature change:

- Public `StatusSheet` callers: **1** (`ThreadScreen.kt:~234`).
- Internal `StatusSheetContent` callers: **15** = 1 from public `StatusSheet` (named-arg forwarding) + 5 in-file `@Preview`s (lines 240-353) + 10 in `StatusSheetTest.kt`.

Raw total: **16 call sites**. Raw count is above the 10-call-site red line.

**Resolved by adding `= 0` defaults to all three new params.** This is not a "default-param escape" rationalisation — the defaults are semantically correct: `0` means "no context window data yet", which is exactly what the empty/initial state shows. The 10 existing `StatusSheetTest` tests don't exercise the Context window section and shouldn't need to be edited for an additive section, and the four pre-existing `@Preview`s render the section at its empty state which is a useful baseline. After defaults: **5 deliberate edits** (1 `ThreadScreen` production call, 4 `ThreadScreen` previews). Comfortably under 10.

Two production-source files touched (`StatusSheet.kt`, `ThreadViewModel.kt`, `ThreadScreen.kt` — wait, that's three production files). Re-checking the production-file count for the scope self-check:

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` — modified
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — modified
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — modified

Three production `*.kt` files. Test files (`StatusSheetTest.kt`, `ThreadViewModelTest.kt`) and the spec doc are excluded from the count. **3 < 5** — the `>= 5 production files` split threshold is not tripped. Size remains S.

Total projected LOC (production + tests + previews + spec): ~340 LOC. Well below the ~600 ceiling. No reject branches (no state machine; pure additive display surface) — the ≥10-reject-branches red line is not in play.

## Open questions

- **`LinearProgressIndicator` stop-indicator suppression.** Newer M3 versions draw a small "stop dot" at the end of the track by default. Figma shows no stop dot. The `gapSize` / `drawStopIndicator` parameters may or may not be on the M3 version pinned by the current `compose-bom`. If they are, use `gapSize = 0.dp, drawStopIndicator = {}`. If they are not, accept the default stop indicator for this slice and leave a one-line `// TODO(#NNN): suppress stop indicator once compose-bom is bumped` next to the `LinearProgressIndicator` call. **Do not bump the BOM here** — that's a separate, cross-cutting concern.
- **Numeric formatting for `tokensTotal = 0`.** With Phase 4 producers, `tokensTotal = 0` would render `"NN% used (XK of 0K tokens)"` — semantically odd but non-crashing. Out of scope for this stub-only slice. The label-format test (`label_format_handles_zero_values_gracefully`) pins the non-crashing shape; a Phase 4 ticket can decide whether to fall back to `"—"` or assume the producer guarantees `tokensTotal > 0`.
- **Sharing the threshold helper.** The new `progressColor` and the existing `ThreadStatusRow.tokenPercentColor` use the same `< 50 / < 95 / ≥ 95` boundaries but different colour slots (fill vs text). Spec keeps them separate. If a future ticket adds a third consumer of the same thresholds (e.g. a badge), that ticket is the right place to extract a shared `enum class TokenPressure { Low, High, Critical }` — premature here.

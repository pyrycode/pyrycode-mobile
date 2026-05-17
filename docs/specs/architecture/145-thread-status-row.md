# Spec: ThreadStatusRow component (Figma 16:58) — #145

## Context

`ThreadScreen` currently exposes a single bottom surface — `ThreadInputBar` (#188) — inside the `Scaffold` `bottomBar` slot. The user has no at-a-glance signal of which model / effort is active or how much of the context window has been consumed; that information was previously discoverable only via the Status Sheet that #146 will host.

This ticket adds an always-visible, single-row status surface above the input bar: `Opus 4.7 · high · 73% used ▴`. Tapping anywhere on the row will eventually open the Status Sheet; in this ticket we expose the tap callback (`onExpandClick`) but do not wire the sheet itself — that's #146's scope.

Values are stub-sourced in Phase 2; Phase 4 swaps `ThreadViewModel`'s populations for real backend state via a one-line change. The `ThreadUiState` shape is the stable contract.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58

A single horizontal text run set in `Roboto Mono Regular 12px / 16 line-height` at 85% opacity. Three span colors: the model name (`Opus 4.7`) renders in `onSurface`, the middle separator-and-effort segment (`· high ·`) in `onSurfaceVariant`, the trailing `73% used` in the threshold-driven color (`onSurfaceVariant` / `warning` / `error`). The Figma node is text-only; the trailing `▴` expand glyph from the ticket body should ship as a small `Icon` (`Icons.Filled.KeyboardArrowUp`) tinted `onSurfaceVariant` for accessibility (contentDescription) rather than as a literal Unicode glyph.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:35-134` — current `Scaffold` shape; `bottomBar` slot currently holds only `ThreadInputBar`; preview seeds at lines 190-228 + 334-372 are the templates to clone for new status-row previews.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:20-64` — `ThreadUiState` data class and the `combine(...).stateIn(WhileSubscribed)` pipeline; the new stub fields are populated inside the `combine` block, with defaults on the data class so `initialValue` doesn't have to enumerate them.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt:65-169` — the existing bottom surface this row stacks above; mirror its `Column` + `HorizontalDivider` pattern. Note `imePadding()` is applied at the bottom-bar Column level — the new wrapper Column in `ThreadScreen`'s `bottomBar` slot should host both children without breaking IME inset.
- `app/src/main/java/de/pyryco/mobile/ui/theme/WarningColors.kt:1-25` — `ColorScheme.warning` extension property (`@Composable` + `@ReadOnlyComposable`); call it like any other M3 slot inside a composable. Confirmed wired by #119; no theme changes needed.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-214` — the `ThreadScreen` route binding; the new `onExpandClick` parameter is wired here as `onExpandClick = {}` (the sheet lands in #146).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:49-58, 376-394` — `makeVm` helper and the initial-state assertion pattern (`assertEquals(ThreadUiState(...), vm.state.value)`). Defaults-with-stub-values on the data class keep that one-line assertion green.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ToolCallRow.kt:150-170` (or any sibling using `FontFamily.Monospace`) — project convention for monospace text without a custom font asset.

## Design

### New composable — `ThreadStatusRow`

New file: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusRow.kt`.

Public signature:

```kotlin
@Composable
fun ThreadStatusRow(
    model: String,
    effort: String,
    tokenPercent: Int,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Behavior contract:

- Renders a single `Row` (`Modifier.fillMaxWidth().clickable(onClick = onExpandClick).padding(horizontal = 16.dp, vertical = 4.dp)`, vertical alignment center, alpha 0.85f to match the Figma `opacity-85`).
- Composes the text portion as a single `Text` with an `AnnotatedString`: three spans — `model` in `onSurface`, ` · $effort · ` in `onSurfaceVariant`, `$tokenPercent% used` in the threshold-driven color (helper below). All spans use `FontFamily.Monospace`, `fontSize = 12.sp`, `lineHeight = 16.sp`.
- Trailing `Icon(imageVector = Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.cd_thread_status_expand), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))`, with a 4.dp `Spacer` between the text and the icon.
- The whole row's `clickable` provides the tappable surface; the icon does **not** need its own `IconButton`.
- New string resource `cd_thread_status_expand` ("Expand status details") in `app/src/main/res/values/strings.xml`.

Token-percent color helper (private, file-local):

```kotlin
@Composable
private fun tokenPercentColor(tokenPercent: Int): Color
```

- `tokenPercent < 50` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `tokenPercent in 50..94` → `MaterialTheme.colorScheme.warning`
- `tokenPercent >= 95` → `MaterialTheme.colorScheme.error`

Clamp negative values to 0 and values >100 to 100 before applying the threshold (defensive — Phase-4 backend swap could conceivably overshoot). Note the AC's "95%+" boundary uses `>= 95`.

### `ThreadUiState` extension

Add three fields to the existing data class at `ThreadViewModel.kt:20`, each with a default matching the Phase-2 stub:

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val items: List<ThreadItem> = emptyList(),
    val model: String = "Opus 4.7",
    val effort: String = "high",
    val tokenPercent: Int = 0,
)
```

Defaults serve two purposes: (1) the `initialValue = ThreadUiState(conversationId, displayName)` literal in the VM continues to compile unchanged; (2) `ThreadViewModelTest.kt:49-58` (which asserts the initial state via a two-argument constructor literal) remains green.

### `ThreadViewModel` stub population

Inside the existing `combine(...) { ... ThreadUiState(...) }` block at `ThreadViewModel.kt:47`, explicitly set the three new fields from companion constants:

```kotlin
companion object {
    private const val STUB_MODEL = "Opus 4.7"
    private const val STUB_EFFORT = "high"
    private const val STUB_TOKEN_PERCENT = 73
}
```

In the combine block emission:

```kotlin
ThreadUiState(
    // ...existing fields...
    model = STUB_MODEL,
    effort = STUB_EFFORT,
    tokenPercent = STUB_TOKEN_PERCENT,
)
```

Phase-4 swap point: replace these three constant references with reads from a backend `Flow<AgentStatus>` mixed into `combine`. Keep the field shape (`String`, `String`, `Int`) stable — no premature `enum class Effort` or `value class TokenPercent` wrappers.

The stub `tokenPercent = 73` lands inside the 50-95% warning band so the developer (and reviewer) immediately see the threshold-driven color rendering live, not just in previews. The previews then sweep the threshold boundaries.

### `ThreadScreen` wiring

In `ThreadScreen.kt:60-62`, replace the single-child `bottomBar` with a `Column` hosting both children:

```kotlin
bottomBar = {
    Column(modifier = Modifier.fillMaxWidth()) {
        ThreadStatusRow(
            model = state.model,
            effort = state.effort,
            tokenPercent = state.tokenPercent,
            onExpandClick = onExpandClick,
        )
        ThreadInputBar(onSend = onSendMessage)
    }
},
```

Add `onExpandClick: () -> Unit = {}` as a new parameter on `ThreadScreen` (matching the existing optional-callback style, defaulted to `{}` so previews don't have to supply it).

The `imePadding()` modifier already lives inside `ThreadInputBar` and only affects that child, so the status row will not be shoved up by the soft keyboard. That's the desired behavior — only the input bar follows the IME; the status row stays visually anchored to the input bar from above.

### `MainActivity` route wiring

At `MainActivity.kt:204-213`, add one parameter to the `ThreadScreen(...)` call site:

```kotlin
onExpandClick = {},
```

This is a placeholder until #146 hosts the Status Sheet. Comment with `// TODO(#146): open Status Sheet` per the project's convention if such a convention exists; otherwise leave bare.

### Previews

Four `ThreadStatusRow` previews (`@Preview(showBackground = true, widthDp = 412)`), each in both light and dark via `PyrycodeMobileTheme(darkTheme = false|true)`, at token-% values `20`, `60`, `88`, `97`. This sweep validates all three color thresholds (`< 50`, `50..94`, `>= 95`) and the within-band visual at `88`. Eight previews total is acceptable for a component this small; alternatively a single parameterized `@PreviewParameter` provider keeps the file lean — developer's discretion. Update the existing four `ThreadScreen` previews so each ThreadUiState literal sets a non-zero `tokenPercent` (any value in the warning band, e.g. `73`) so the row is visible in the screen-level previews too.

## State + concurrency model

No new flows, no new dispatchers. `ThreadUiState` gains three plain fields populated synchronously inside the existing `combine` block on `viewModelScope`. The `WhileSubscribed(5_000)` policy is preserved. `ThreadStatusRow` is pure presentation — no side effects, no `LaunchedEffect`, no `remember{}` beyond what Compose itself produces for the `Text` style construction.

The row recomposes whenever `model`, `effort`, or `tokenPercent` changes on `ThreadUiState`. All three are stable types (`String`, `String`, `Int`), so Compose skips recomposition when the values are unchanged across emissions.

## Error handling

No new failure modes. The stub values are hard-coded constants; the row degrades gracefully if `tokenPercent` is out-of-range (clamp to 0..100 in `tokenPercentColor`). Empty `model` / `effort` strings produce a visually awkward but non-crashing row (`" ·  · 73% used"`); we explicitly do NOT add validation here — Phase 4's real backend integration will own that contract.

## Testing strategy

- **`ThreadViewModelTest.kt`** — add **two** new unit-test cases (no `runTest` needed; the initial value is synchronous):
  - `state_initialValue_includesStubModelEffortAndTokenPercent` — assert `vm.state.value.model == "Opus 4.7"`, `effort == "high"`, `tokenPercent == 0` (or `73` — see clarifying note below). This nails down the data-class defaults.
  - `state_postSubscription_emitsStubModelEffortAndTokenPercent` — `runTest` + `launch { vm.state.collect {} }` + `advanceUntilIdle()`, then assert the combine block emitted `model == "Opus 4.7"`, `effort == "high"`, `tokenPercent == 73`. This nails down the VM's explicit population (the Phase-4 swap point).

  Reconcile the existing `state_initialValue_isConversationIdPlaceholderBeforeSubscription` test at `ThreadViewModelTest.kt:49-58`: the data-class default for `tokenPercent` is `0`, so its existing two-argument `ThreadUiState(conversationId, displayName)` literal continues to match. The initial value and post-subscription value differ on `tokenPercent` (0 vs 73) — that's intentional and the two new tests cover both states.

- **No new Compose UI tests** required. `ThreadStatusRow` is rendered exclusively through `ThreadScreen`'s `bottomBar`, and the project currently has no `ThreadScreen` compose tests (`app/src/androidTest/` has no thread directory). The previews are the visual gate; eight preview frames across the threshold sweep are sufficient for this round. If a future ticket adds `ThreadScreenComposeTest`, the row's `clickable` surface + content description will be the obvious assertion points.

- **Test framework note:** unit tests run via `./gradlew test`; previews compile via `./gradlew assembleDebug`. No instrumented (`connectedAndroidTest`) coverage is added or required for this ticket.

## Open questions

- Whether the `▴` should ship as a vector icon (this spec's choice) or a literal `▴` Unicode glyph appended to the `AnnotatedString`. The vector approach gives a clean `contentDescription` for accessibility; the glyph approach matches the Figma export verbatim. The developer may switch to the glyph if it visually reads closer to the design at runtime — note the change in the PR if so.
- Whether the row's `clickable` should use a `Modifier.semantics { role = Role.Button }` to label the entire row as a button for TalkBack. Recommended; not strictly required by the AC. Add it if the lint / accessibility profile of nearby surfaces (`WorkspaceChip`, etc.) already does so.

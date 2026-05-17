# StatusSheet

Stateless Material 3 `ModalBottomSheet` (shell + Model section [#254](../codebase/254.md); Effort + YOLO sections [#229](../codebase/229.md); Context window section [#230](../codebase/230.md)) that hosts the Status Sheet — the surface a user opens by tapping the [`ThreadStatusRow`](thread-status-row.md) to inspect or change per-conversation run configuration. Renders Figma node `20:100`: a `"Run configuration"` title row with a trailing close icon, over four sections in order — **Model** (`selectableGroup`-wrapped radio rows for Opus 4.7 / Sonnet 4.6 / Haiku 4.5, each pairing the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) title with a short Figma-derived description), **Effort** (single `Row` of five `FilterChip`s — `low` / `medium` / `high` / `xhigh` / `max` from the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension), **YOLO mode** (full-width `toggleable` row with a two-line label + an M3 `Switch`), and **Context window** (read-only `bodyLarge` label + 8dp `LinearProgressIndicator` with threshold-driven fill colour + `bodySmall` caption).

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `StatusSheet.kt`. Third **sheet** in that package after [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) and [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)); follows their shell + `*Content` split verbatim.

## Shape

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
    yoloEnabled: Boolean,
    onYoloToggled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    tokenPercent: Int = 0,
    tokensUsed: Int = 0,
    tokensTotal: Int = 0,
)
```

- **Public** (no `internal`) — same posture as the public shell in [`WorkspacePickerSheet`](workspace-picker-sheet.md) post-#220. The sheet has one production consumer ([`ThreadScreen`](thread-screen.md)) but the visibility decision tracks the sibling sheets, not consumer count.
- **`sheetState` defaulted but exposed** — the host can drive an animated close before invoking `onDismiss` if needed; defaulting keeps the host wiring one-line. `skipPartiallyExpanded = true` because the body is a short list, not a half-sheet.
- **No nullable callbacks** — every section in this sheet (four post-[#230](../codebase/230.md)) always renders, so all callbacks are always wired.
- **`selectedModel: Model` and `selectedEffort: Effort` are typed enums** — [#253](../codebase/253.md) lifted `selectedModel`, [#229](../codebase/229.md) followed the same shape for `selectedEffort`. The radio/chip selection compares against the enum rather than a label string, so a future relabel (e.g. `"Opus 4.7"` → `"Claude Opus 4.7"`) doesn't break selection identity.
- **`yoloEnabled: Boolean` is a primitive flag** — no nullable, no wrapper. The architectural single-writer invariant ([#229](../codebase/229.md)) is enforced at the VM layer (`private val yoloEnabled: MutableStateFlow<Boolean>` with one mutator, `ThreadViewModel.onYoloToggled`); the sheet's parameter is just the projection of that field.
- **`tokenPercent` / `tokensUsed` / `tokensTotal` are `Int = 0`-defaulted** ([#230](../codebase/230.md)) — three additive read-only display fields appended after the sheet's `sheetState` defaulted tail. The `= 0` defaults are semantically correct: `0` means "no context window data yet" (the empty/initial state). The defaults are load-bearing — they let the 10 pre-existing `StatusSheetTest` call sites and the 5 pre-existing `@Preview`s compile untouched, holding edit fan-out at 5 deliberate edits across `ThreadScreen` (1 production call + 4 previews). The fields are not callbacks — they're projections of `ThreadUiState.tokenPercent / tokensUsed / tokensTotal` which are still companion-constant-populated at the VM (`STUB_TOKEN_PERCENT = 73`, `STUB_TOKENS_USED = 146_000`, `STUB_TOKENS_TOTAL = 200_000`).

A peer `internal` composable carries the body:

```kotlin
@Composable
internal fun StatusSheetContent(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
    yoloEnabled: Boolean,
    onYoloToggled: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    tokenPercent: Int = 0,
    tokensUsed: Int = 0,
    tokensTotal: Int = 0,
)
```

`StatusSheet` is the `ModalBottomSheet` shell that delegates into it. The split exists so previews and Compose UI tests can render the content directly — the modal scrim + animation machinery don't render in the IDE preview pane and aren't wired into `createComposeRule()`-style tests. Same architectural shape M3 samples use for sheet previews, and the same shape `WorkspacePickerSheet` and `ChannelInfoSheet` follow.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = "Run configuration", onClose = onDismiss)`** — `titleLarge` in `onSurface` filling the row, trailing `IconButton(Icons.Filled.Close)` with `contentDescription = "Close"` and `tint = onSurfaceVariant`. Padding `start = 16, end = 4, top = 4, bottom = 12` per Figma `20:104`.
2. **`SectionHeader(text = "Model")`** — `labelLarge` in `onSurfaceVariant`, padding `start = 24, end = 16, top = 12, bottom = 4` per Figma `20:113`. Same shape as [`WorkspacePickerSheet`](workspace-picker-sheet.md)'s `"Recent"` / `"Other"` headers.
3. **`Column(modifier = Modifier.selectableGroup())`** wrapping `Model.entries.forEach { model -> ModelRow(model, selected = model == selectedModel, onClick = { onModelSelected(model) }) }` — three rows in source order (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`). The `selectableGroup()` modifier is the M3-recommended container for a radio set; TalkBack announces "1 of 3 selected" semantics at the group boundary rather than per row.
4. **`SectionHeader(text = "Effort")`** ([#229](../codebase/229.md)) — same padding + style as the Model header per Figma `20:130`.
5. **`EffortChipRow(selectedEffort, onEffortSelected)`** ([#229](../codebase/229.md)) — single `Row` of five `FilterChip`s; see [§ `EffortChipRow`](#effortchiprow) below.
6. **`SectionHeader(text = "YOLO mode")`** ([#229](../codebase/229.md)) — same padding + style as above per Figma `20:142`.
7. **`YoloRow(yoloEnabled, onYoloToggled)`** ([#229](../codebase/229.md)) — full-width `toggleable` row with two-line label + M3 `Switch`; see [§ `YoloRow`](#yolorow) below.
8. **`SectionHeader(text = "Context window")`** ([#230](../codebase/230.md)) — same padding + style as above per Figma `20:151`.
9. **`ContextWindowSection(tokenPercent, tokensUsed, tokensTotal)`** ([#230](../codebase/230.md)) — read-only label + 8dp `LinearProgressIndicator` + caption; see [§ `ContextWindowSection`](#contextwindowsection) below.
10. **`Spacer(height = 24.dp)`** — bottom inset, matching the trailing spacer on the sibling sheets.

`ModalBottomSheet`'s default `BottomSheetDefaults.DragHandle` paints the M3 drag pill at the top; the composable doesn't override it.

### `ModelRow`

```kotlin
@Composable
private fun ModelRow(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
)
```

`Row(Modifier.fillMaxWidth().selectable(selected, onClick, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.Top)` with three children:

1. `RadioButton(selected = selected, onClick = null)` — default M3 colors. **`onClick = null` is required**, not optional — the row's `selectable` modifier owns the click semantics; passing the handler to both fires the callback twice. The lint-clean shape is `onClick = null` on the inner `RadioButton`.
2. `Spacer(width = 12.dp)`.
3. `Column` with two `Text`s:
   - Title `Text(text = model.label(), style = bodyLarge, color = onSurface)` — uses the existing [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension at `data/preferences/Model.kt` ([#253](../codebase/253.md)). Do not duplicate the strings.
   - Description `Text(text = model.description(), style = bodySmall, color = onSurfaceVariant)` — uses a private file-local `Model.description(): String` extension co-located in `StatusSheet.kt` (mapping table below).

`Alignment.Top` (not `CenterVertically`) is load-bearing: the description text wraps to multiple lines on narrow widths; centring the radio against the wrapped block looks broken. Top-aligning the radio to the title baseline keeps the visual anchor stable across description heights.

### `Modifier.selectable(role = Role.RadioButton)` on the row, not on the radio

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.Top,
) {
    RadioButton(selected = selected, onClick = null)
    // ...
}
```

This is the canonical M3 shape for a radio row whose tappable region is the whole row, not just the radio control. Two reasons:

1. **Touch target.** The radio circle is ~20dp; the row (title + description + padding) is ~56dp+. Without `selectable` on the row, taps outside the radio do nothing.
2. **Semantics.** `role = Role.RadioButton` on `selectable` flows TalkBack semantics through the parent — the row is announced as a radio button, and the inner `RadioButton(onClick = null)` doesn't double-announce.

### Model descriptions

Private file-local `Model.description(): String` extension, mapping per Figma nodes `20:117/122/127`:

| `Model` | Description |
| --- | --- |
| `OPUS_4_7` | `"best for complex work"` |
| `SONNET_4_6` | `"faster, cheaper"` |
| `HAIKU_4_5` | `"fastest"` |

The extension is **private**, not promoted to `data/preferences/Model.kt` alongside `label()`. Reason: these are Figma-derived presentation strings specific to this sheet's "user picks a model" framing. A future Settings model-picker may want different copy (e.g. "Default for new conversations" framing) or no descriptions at all — re-promote only when a second consumer materialises with the same framing. The label/title is shared via `Model.label()` ([#253](../codebase/253.md)); the descriptions are deliberately not.

### `EffortChipRow`

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EffortChipRow(
    selectedEffort: Effort,
    onEffortSelected: (Effort) -> Unit,
)
```

`Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp))` iterating `Effort.entries` (`LOW, MEDIUM, HIGH, XHIGH, MAX`) — five `FilterChip(selected = effort == selectedEffort, onClick = { onEffortSelected(effort) }, label = { Text(effort.label()) })`. No leading icon, no trailing icon.

- **`FilterChip` defaults match Figma `20:131–140` exactly — no `FilterChipDefaults.filterChipColors(...)` override.** Selected: `secondaryContainer` background + `onSecondaryContainer` label, no border. Unselected: 1dp `outline` border + transparent background + `onSurfaceVariant` label. The default `FilterChipDefaults.filterChipBorder(...)` paints the unselected outline; the selected state replaces border with background fill automatically.
- **`Effort.entries.forEach`, not five hand-written calls.** Same precedent as `ModelRow`'s `Model.entries.forEach` — source order at `data/preferences/Effort.kt` is the single source of truth; enum reorder / insertion propagates without UI edits.
- **Labels resolve via the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension** at `ui/settings/EffortPickerDialog.kt:77` — widened from `internal` to top-level public in [#229](../codebase/229.md) when this slice became the second consumer (alongside the [#233](../codebase/233.md) Settings dialog). Returns lowercase `"low"` / `"medium"` / `"high"` / `"xhigh"` / `"max"`.
- **A11y.** `FilterChip` owns `Role.Button` with selection state; `selectableGroup()` lets TalkBack announce "in group of 5".

### `YoloRow`

```kotlin
@Composable
private fun YoloRow(
    enabled: Boolean,
    onToggled: (Boolean) -> Unit,
)
```

`Row(Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = onToggled).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically)` with three children:

1. `Column(modifier = Modifier.weight(1f))` with two `Text`s:
   - Title `Text("Auto-accept tool calls", style = bodyLarge, color = onSurface)`.
   - Supporting `Text("Claude runs commands without asking for confirmation. Use carefully.", style = bodySmall, color = onSurfaceVariant)`.
2. `Spacer(modifier = Modifier.width(16.dp))`.
3. `Switch(checked = enabled, onCheckedChange = null)` — `null` because the surrounding `toggleable` owns the click.

Same canonical M3 shape family as `ModelRow`'s `Modifier.selectable + RadioButton(onClick = null)` — the parent owns click + a11y semantics; the inner control's handler must be `null` or the callback fires twice. The 8.dp vertical padding (vs. the 4.dp on `ModelRow`) is deliberate — the two-line label needs the extra breathing room.

### `ContextWindowSection`

```kotlin
@Composable
private fun ContextWindowSection(
    tokenPercent: Int,
    tokensUsed: Int,
    tokensTotal: Int,
)
```

`Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` with three children:

1. Label `Text("$tokenPercent% used (${formatTokens(tokensUsed)} of ${formatTokens(tokensTotal)} tokens)", style = bodyLarge, color = onSurface)` — Figma `20:152`.
2. `LinearProgressIndicator(progress = { tokenPercent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = progressColor(tokenPercent), trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})` — Figma `20:153/154`. The deferred `progress = { lambda }` overload is Compose-recommended (lazy draw-time read; no relayout when only the fill width changes). The `coerceIn(0, 100)` clamp is defensive over the Phase-4 backend swap. `gapSize = 0.dp, drawStopIndicator = {}` suppresses the M3 stop-dot indicator to match Figma; both params ship on the BOM-pinned M3 version (`compose-bom = 2026.02.01`).
3. Caption `Text("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).", style = bodySmall, color = onSurfaceVariant)` — Figma `20:155`. Copy is **verbatim from Figma including the lowercase `claude's`**.

Read-only — no event surface, no callback. The section is a pure projection of `ThreadUiState.tokenPercent / tokensUsed / tokensTotal`. No auto-close (it's a display, not a picker) — taps inside the section do nothing.

#### `formatTokens` (file-private helper)

```kotlin
private fun formatTokens(n: Int): String = "${n / 1000}K"
```

Integer-divide-by-1000 truncation. `formatTokens(146_000) == "146K"`, `formatTokens(999) == "0K"` (intentional — sub-1K precision is noise for context-window display). Kept file-private alongside `progressColor`; not promoted to a shared util module. Per the [#230](../codebase/230.md) ticket Technical Notes: "single formatter helper site… not a shared util module for it."

#### `progressColor` (file-private helper)

```kotlin
@Composable
private fun progressColor(percent: Int): Color {
    val clamped = percent.coerceIn(0, 100)
    return when {
        clamped < 50 -> MaterialTheme.colorScheme.primary
        clamped < 95 -> MaterialTheme.colorScheme.warning
        else -> MaterialTheme.colorScheme.error
    }
}
```

Same `< 50 / < 95 / ≥ 95` boundary values as [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper), **deliberately not shared**. That helper colours **text** (`onSurfaceVariant` → `warning` → `error`); this one colours **fill** (`primary` → `warning` → `error`). Sharing would force one consumer onto the wrong slot — `primary` is fill chroma, `onSurfaceVariant` is low-emphasis text. The below-50% choice between `primary` and the M3 `LinearProgressIndicator` default resolved to `primary` since those are the same colour (per [#230](../codebase/230.md) ticket Technical Notes: "a single binary choice… take `primary` and avoid introducing a third tone"). If a future ticket adds a third consumer of the same thresholds + colour mapping, extract a `TokenPressure { Low, High, Critical }` enum then — premature here.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/surface-container-highest` | `colorScheme.surfaceContainerHighest` | Context window progress-bar track |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row titles, YOLO row title, Context window label |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section header, row descriptions, close icon, unselected `FilterChip` label, YOLO supporting text, Context window caption |
| `Schemes/primary` | `colorScheme.primary` | Context window progress-bar fill (< 50%) |
| `Schemes/warning` (slot from [#119](../codebase/119.md)) | `colorScheme.warning` | Context window progress-bar fill (50–94%) |
| `Schemes/error` | `colorScheme.error` | Context window progress-bar fill (≥ 95%) |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | Selected `FilterChip` background |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | Selected `FilterChip` label |
| `Schemes/outline` | `colorScheme.outline` | Unselected `FilterChip` 1dp border |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Run configuration"` |
| `Static/Label Large` | `typography.labelLarge` | `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` section headers |
| `Static/Body Large` | `typography.bodyLarge` | Model row titles, YOLO row title, Context window label |
| `Static/Body Small` | `typography.bodySmall` | Model row descriptions, YOLO row supporting text, Context window caption |

## Recomposition / stability

- All four callback params (`onModelSelected`, `onEffortSelected`, `onYoloToggled`, `onDismiss`) are `(T) -> Unit` / `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `selectedModel: Model`, `selectedEffort: Effort`, `yoloEnabled: Boolean`, `tokenPercent: Int`, `tokensUsed: Int`, `tokensTotal: Int` are all primitive (enum, boolean, int) — Compose-stable by definition; the row / chip / switch / progress composables skip recomposition when their inputs are unchanged.
- `LinearProgressIndicator` receives `progress = { lambda }` — the deferred-read form — so the closure over `tokenPercent` is re-invoked on draw without re-laying-out the bar. `progressColor` is `@Composable` (reads `MaterialTheme.colorScheme.*`) so it recomposes correctly on theme change; `formatTokens` is non-`@Composable` and returns a stable `String`.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` + `FilterChip` + `Switch` + `LinearProgressIndicator` (with `gapSize` + `drawStopIndicator` params) all ship in `androidx.compose.material3` already in the BOM (`composeBom = 2026.02.01`); `Modifier.selectable` / `selectableGroup` / `toggleable` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit across [#254](../codebase/254.md), [#229](../codebase/229.md), or [#230](../codebase/230.md).
- **No new string resources.** Literals inline (`"Run configuration"`, `"Model"`, `"best for complex work"`, `"faster, cheaper"`, `"fastest"`, `"Effort"`, `"YOLO mode"`, `"Auto-accept tool calls"`, `"Claude runs commands without asking for confirmation. Use carefully."`, `"Context window"`, `"When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll)."`, `"Close"`); first-localisation pass migrates everything together. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md).

## Hosting in `ThreadScreen`

The sheet is mounted inside [`ThreadScreen`](thread-screen.md) at the screen-root level, as a `Scaffold` sibling (not inside the `Scaffold` content slot — `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order). The screen owns a `rememberSaveable`-hoisted visibility flag and forwards the model selection to the VM:

```kotlin
@Composable
fun ThreadScreen(
    // ...existing params...
    onModelSelected: (Model) -> Unit = {},
    // ...existing params...
) {
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        // ...
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                ThreadStatusRow(
                    // ...
                    onExpandClick = { sheetVisible = true },
                )
                ThreadInputBar(onSend = onSendMessage)
            }
        },
    ) { /* content */ }
    WorkspacePicker(/* ... */)              // sibling host from #137
    if (sheetVisible) {
        StatusSheet(
            selectedModel = state.selectedModel,
            onModelSelected = { model ->
                onModelSelected(model)
                sheetVisible = false
            },
            selectedEffort = state.selectedEffort,
            onEffortSelected = { effort ->
                onEffortSelected(effort)
                sheetVisible = false
            },
            yoloEnabled = state.yoloEnabled,
            onYoloToggled = onYoloToggled,
            onDismiss = { sheetVisible = false },
            tokenPercent = state.tokenPercent,
            tokensUsed = state.tokensUsed,
            tokensTotal = state.tokensTotal,
        )
    }
}
```

Four things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Asymmetric auto-close: single-pick sections close the sheet, toggle/display sections don't** ([#229](../codebase/229.md) resolution to the #254 open question, [#230](../codebase/230.md) extension). `onModelSelected` and `onEffortSelected` both wrap in `{ value -> upstream(value); sheetVisible = false }` — a radio/chip pick is a complete action, M3 modal-bottom-sheet convention. `onYoloToggled` passes straight through with no auto-close — a Switch toggle is a state-change the user may want to immediately reverse. The Context window section has no callback at all (read-only display); taps inside it do nothing. The final rule from [#230](../codebase/230.md): discrete-value picker → auto-close, slider/toggle/display → keep open.
- **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects.
- **`ModalBottomSheet`'s hide animation runs on a coroutine the framework owns** — no `sheetState.hide()` call needed before flipping `sheetVisible = false`.

The `ThreadScreen`-side `onModelSelected: (Model) -> Unit = {}`, `onEffortSelected: (Effort) -> Unit = {}`, `onYoloToggled: (Boolean) -> Unit = {}` are all defaulted so previews and other call sites keep compiling unchanged; `MainActivity` binds the three parameters at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    onEffortSelected = vm::onEffortSelected,
    onYoloToggled = vm::onYoloToggled,
    // ...
)
```

All three are method references into [`ThreadViewModel`](thread-screen.md#viewmodel) — synchronous `MutableStateFlow.value` writes that update the in-memory per-conversation state. `onModelSelected` ([#253](../codebase/253.md)) and `onEffortSelected` ([#229](../codebase/229.md)) update an override layer over the matching `AppPreferences.default*` flow; `onYoloToggled` ([#229](../codebase/229.md)) writes the `yoloEnabled` flag directly with **no preference layer at all** — the architectural single-writer invariant guarantees this method is the only writer of the field. None of the three mutate `AppPreferences`.

## Preview

Nine `@Preview` composables in `StatusSheet.kt`, all light-mode + `widthDp = 412` + `showBackground = true`. Each follows the canonical sheet-preview wrap that simulates the `ModalBottomSheet`'s default container colour + drag-handle gap:

```kotlin
PyrycodeMobileTheme(darkTheme = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
            StatusSheetContent(
                selectedModel = <model>,
                onModelSelected = {},
                selectedEffort = <effort>,
                onEffortSelected = {},
                yoloEnabled = <bool>,
                onYoloToggled = {},
                onDismiss = {},
                tokenPercent = <int>,
                tokensUsed = <int>,
                tokensTotal = <int>,
            )
        }
    }
}
```

- **Model sweep** ([#254](../codebase/254.md)): `StatusSheetOpusPreview` (`Model.OPUS_4_7`), `StatusSheetSonnetPreview` (`Model.SONNET_4_6`), `StatusSheetHaikuPreview` (`Model.HAIKU_4_5`) — all with `selectedEffort = Effort.HIGH, yoloEnabled = false`. Context window params default to `0` (empty/initial state).
- **Effort/YOLO sweep** ([#229](../codebase/229.md)): `StatusSheetEffortLowYoloOffPreview` (`Effort.LOW`, `yoloEnabled = false`) and `StatusSheetEffortMaxYoloOnPreview` (`Effort.MAX`, `yoloEnabled = true`) — both fix `Model.OPUS_4_7`, exercising the chip selection contrast and the switch on/off rendering. Satisfies the [#229](../codebase/229.md) AC line "both Effort variations (e.g. low and max) and YOLO on/off".
- **Context window threshold sweep** ([#230](../codebase/230.md)): four previews fixing `Model.OPUS_4_7` + `Effort.HIGH` + `yoloEnabled = false`, varying only the three context-window params — `StatusSheetContextWindow20Preview` (`tokenPercent = 20, tokensUsed = 40_000, tokensTotal = 200_000` → `primary` band), `StatusSheetContextWindow60Preview` (60 / 120_000 / 200_000 → `warning` band), `StatusSheetContextWindow88Preview` (88 / 176_000 / 200_000 → `warning` band, wider fill), `StatusSheetContextWindow97Preview` (97 / 194_000 / 200_000 → `error` band). Satisfies the [#230](../codebase/230.md) AC line "`@Preview` shows the section at multiple token-% values (e.g. 20%, 60%, 88%, 97%) so the threshold transitions are visually verifiable". Compose colour assertions on draw layers are awkward, so these previews are the verification surface for the threshold transitions — not a Compose test.

All nine target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep is deliberately not included; the theme behaviour is identical across themes (no role-specific palettes — the threshold colours all resolve through `MaterialTheme.colorScheme` which adapts to the active theme). If a future ticket wants a dark sweep, mirror the [`WorkspacePickerSheet`](workspace-picker-sheet.md) `Preview + DarkPreview` pair.

## Tests

Fourteen Compose UI tests in `androidTest/.../StatusSheetTest.kt` (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

Model section ([#254](../codebase/254.md)):

- **`renders_model_section_with_all_three_rows_and_descriptions`** — `selectedModel = Model.OPUS_4_7`. Asserts "Model" header + each of `"Opus 4.7"` / `"best for complex work"` / `"Sonnet 4.6"` / `"faster, cheaper"` / `"Haiku 4.5"` / `"fastest"` are displayed via `hasText` matchers.
- **`tapping_sonnet_row_invokes_onModelSelected_with_sonnet`** — `selectedModel = Model.OPUS_4_7`, `onModelSelected = picks::add` capture. `onNode(hasText("Sonnet 4.6")).performClick()`. Asserts `picks == listOf(Model.SONNET_4_6)` (exact list equality — pins the "exactly one invocation" contract; a regression that fires the click twice would surface as `[SONNET_4_6, SONNET_4_6]`).
- **`tapping_haiku_row_invokes_onModelSelected_with_haiku`** — same shape, asserts `picks == listOf(Model.HAIKU_4_5)`.
- **`tapping_close_icon_invokes_onDismiss`** — `var invoked = 0`, `onNode(hasContentDescription("Close")).performClick()`, asserts `invoked == 1`. Pins the close-icon wiring + the exactly-one-invocation guarantee.
- **`selected_row_reports_selected_semantics`** — `selectedModel = Model.SONNET_4_6`. Matcher is `onNode(isSelectable() and hasAnyDescendant(hasText("Sonnet 4.6"))).assertIsSelected()` — `hasAnyDescendant` is required because the `selectable` modifier lives on the parent `Row`, not on the `Text` node; matching on the descendant text disambiguates among three selectable rows.

Effort + YOLO sections ([#229](../codebase/229.md)):

- **`renders_effort_section_with_all_five_chips`** — asserts "Effort" header + each of `"low"` / `"medium"` / `"high"` / `"xhigh"` / `"max"` is displayed.
- **`tapping_low_chip_invokes_onEffortSelected_with_low`** — `selectedEffort = Effort.HIGH`, capture into `mutableListOf<Effort>()`, `onNode(hasText("low")).performClick()`, asserts `[Effort.LOW]`. One representative chip is enough; the LOW/MEDIUM/HIGH/XHIGH/MAX mapping is exhaustively covered by the `Effort.entries.forEach` iteration in the production code.
- **`selected_effort_chip_reports_selected_semantics`** — `selectedEffort = Effort.MAX`. Positive assertion: `onNode(isSelectable() and hasAnyDescendant(hasText("max"))).assertIsSelected()`. Negative sister: `onNode(isSelectable() and hasAnyDescendant(hasText("low"))).assertIsNotSelected()` — pinning both halves of the contract guards against an over-broad `selected = true` regression on all chips that the positive-only test would miss.
- **`renders_yolo_section_with_title_and_supporting_text`** — asserts "YOLO mode" header + `"Auto-accept tool calls"` + `"Claude runs commands without asking for confirmation. Use carefully."` are all displayed.
- **`tapping_yolo_row_when_off_invokes_onYoloToggled_with_true`** — `yoloEnabled = false`, capture, `onNode(hasText("Auto-accept tool calls")).performClick()` (the `toggleable` modifier owns the whole row, so any in-row text node's click fires the callback), asserts `[true]`.
- **`tapping_yolo_row_when_on_invokes_onYoloToggled_with_false`** — `yoloEnabled = true`, click the row, asserts `[false]`. Pins the bidirectional toggle contract.

Context window section ([#230](../codebase/230.md)):

- **`renders_context_window_section_with_header_label_and_caption`** — `tokenPercent = 73, tokensUsed = 146_000, tokensTotal = 200_000` (the Figma reference triple). Asserts `"Context window"` header + `"73% used (146K of 200K tokens)"` formatted label + the full caption are all displayed. One assertion covers both `formatTokens` correctness and the label-assembly contract.
- **`label_format_uses_integer_K_division`** — `tokenPercent = 5, tokensUsed = 12_345, tokensTotal = 200_000`. Asserts `"5% used (12K of 200K tokens)"`. Pins `formatTokens`'s integer-divide-truncation behaviour indirectly through the visible label (the helper is `private`).
- **`label_format_handles_zero_values_gracefully`** — calls `StatusSheetContent(...)` with no explicit context-window params (exercises the `= 0` default path). Asserts `"0% used (0K of 0K tokens)"`. Pins the empty/initial-state shape and prevents a future divide-by-zero regression on `formatTokens`.

Progress-bar fill colour is **not** asserted at any of the three threshold tiers — `SemanticsNodeInteraction` doesn't expose draw colours; the four [`StatusSheetContextWindow*Preview`](#preview) composables are the verification surface for threshold transitions.

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by [`ThreadViewModelTest`](thread-screen.md#testing) — `selectedModel` via [#253](../codebase/253.md), `selectedEffort` + `yoloEnabled` via [#229](../codebase/229.md), `tokensUsed` + `tokensTotal` via [#230](../codebase/230.md) (four new assertion lines on the two pre-existing initial/post-subscription tests).

## Edge cases / limitations

- **Asymmetric auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. Tapping the YOLO row (or its switch) fires `onYoloToggled(!previous)` and **leaves the sheet open**. The Context window section has no callback at all — it's read-only. The asymmetry is deliberate: a single-pick is a complete action (M3 modal-bottom-sheet convention), but a Switch is a state-change the user may want to immediately reverse, and a display panel has no action surface to begin with. Resolution to the [#254](../codebase/254.md) open question, extended in [#230](../codebase/230.md) — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen).
- **No section abstraction.** The sheet body renders all four sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. The [#229](../codebase/229.md) spec flagged [#230](../codebase/230.md) as the re-evaluation point; [#230](../codebase/230.md)'s call was "still not yet — four sections is the threshold but the body divergence is high (radio rows / chip row / toggle row / label+progress+caption block); wait for a fifth occurrence or a real shape divergence". The header + container shape is repeated; the body shape is not. Pre-introducing the abstraction would have multiplied the surface for three tickets of value.
- **`progressColor` and [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) share boundaries but not colour slots.** Both use `< 50 / < 95 / ≥ 95`; `progressColor` (this file, fill chroma) returns `primary` / `warning` / `error`; `tokenPercentColor` (the status row, text emphasis) returns `onSurfaceVariant` / `warning` / `error`. **Deliberately not shared** per [#230](../codebase/230.md) — sharing would force one consumer onto the wrong slot. If a future ticket adds a third consumer with the same boundaries AND the same colour mapping, that ticket extracts a `TokenPressure` enum; until then, duplicated `when` blocks of ~8 LOC each are cheap.
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` headers, the three Model descriptions, the YOLO two-line label, the Context window caption, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. Product-vendor strings like model descriptions may rewrite at that point — extracting them now would mix layers without lock-in value.
- **`Model.description()` is private to this file.** A future Settings model-picker (still pending per [`app-preferences`](app-preferences.md) gap) cannot import it. That is intentional — the descriptions are framed for the per-conversation override flow, not a global default. If Settings wants descriptions, it declares its own (potentially with different copy).
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` / `Switch.onCheckedChange` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) or `toggleable.onValueChange` (YOLO) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` and `Switch(checked, onCheckedChange = null)` with the parent `Row` owning the click. Pinned by the `tapping_*_invokes_*` tests' exact-list assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row.
- **YOLO ignores `AppPreferences.defaultYolo`.** The dormant `defaultYolo` flow on `AppPreferences` and the non-functional Settings YOLO row at `SettingsScreen.kt:80, 169` are both dead code; this sheet's `yoloEnabled` parameter is sourced from `ThreadViewModel`'s `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial, no preference read. The architectural single-writer invariant (`onYoloToggled` is the only writer of the field) is enforced by `private` visibility on the ViewModel's flow plus a code-review `git grep` check; the AC test `yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault` verifies the dormant preference does not leak. See [#229](../codebase/229.md) § Patterns established for the rationale.
- **Context window values are still stubs.** `tokenPercent` + `tokensUsed` + `tokensTotal` are all populated from `STUB_*` companion constants (`73`, `146_000`, `200_000`) on `ThreadViewModel` under a single `// Phase 4 swap point: replace with backend AgentStatus flow.` comment. The three values are coupled (`73% × 200_000 = 146_000`) and Phase 4 replaces them together when the backend `AgentStatus` flow lands. If a future Phase 4 producer feeds `tokensTotal = 0`, the label renders `"NN% used (XK of 0K tokens)"` — semantically odd but non-crashing (the `label_format_handles_zero_values_gracefully` test pins this shape).

## Related

- Ticket notes: [`../codebase/254.md`](../codebase/254.md) (shell + Model section), [`../codebase/229.md`](../codebase/229.md) (Effort + YOLO sections), [`../codebase/230.md`](../codebase/230.md) (Context window section)
- Specs: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`, `docs/specs/architecture/229-statussheet-effort-yolo.md`, `docs/specs/architecture/230-status-sheet-context-window.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228) / [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected, onEffortSelected = vm::onEffortSelected, onYoloToggled = vm::onYoloToggled` at `MainActivity`; reads `tokenPercent / tokensUsed / tokensTotal` straight off `state` for the Context window section.
  - [Thread status row](thread-status-row.md) — the entry point. The row's `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder) now fires `{ sheetVisible = true }` internally inside `ThreadScreen`. Shares the `< 50 / < 95 / ≥ 95` boundary values with `progressColor` but uses text-emphasis colours (`onSurfaceVariant` / `warning` / `error`) rather than fill chroma — see § Edge cases / limitations for the deliberate non-sharing.
  - [App preferences](app-preferences.md) — the `Model` + `Effort` enums + both [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) and (since [#229](../codebase/229.md)) public `Effort.label()` extensions, plus the `AppPreferences.defaultModel` / `defaultEffort` flows that back the VM's `selectedModelFlow` / `selectedEffortFlow`. The dormant `AppPreferences.defaultYolo` flow is deliberately not consumed.
  - [Warning color slot](warning-color.md) — `MaterialTheme.colorScheme.warning` ([#119](../codebase/119.md)) consumed at the 50-95% band of `progressColor` (Context window fill) and of `tokenPercentColor` (status row text).
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - **Phase 4 backend `AgentStatus` flow** — replaces all three Context window stub constants (`STUB_TOKEN_PERCENT`, `STUB_TOKENS_USED`, `STUB_TOKENS_TOTAL`) together. If the producer ever feeds `tokensTotal = 0`, the label renders `"NN% used (XK of 0K tokens)"` (semantically odd but non-crashing; pinned by the `label_format_handles_zero_values_gracefully` test). The Phase 4 ticket can decide whether to fall back to `"—"` or assume the producer guarantees `tokensTotal > 0` — out of scope here.
  - **`Section` abstraction across the four sections** — still deferred per [#230](../codebase/230.md). Four `SectionHeader + <body>` pairs now live in the `Column`; the body shape diverges (radio rows / chip row / toggle row / label+progress+caption block), so a `SheetSection(header: String, body: @Composable () -> Unit)` thin wrapper might be the right abstraction. The architect's call for [#230](../codebase/230.md) was "still not yet — wait for a fifth occurrence or a real shape divergence".
  - Future Settings model-picker (`AppPreferences.setDefaultModel(...)` write-site, still pending per [`app-preferences`](app-preferences.md) gap) — re-uses [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) but **does not** re-use the private `Model.description()` extension here; it declares its own (different framing).
  - Settings YOLO cleanup — the dormant `mutableStateOf(false)` row at `SettingsScreen.kt:80, 169` and the dormant `AppPreferences.defaultYolo` flow can be removed in a separate ticket. Any future "default YOLO for new conversations" feature must respect the single-writer invariant on `ThreadViewModel.yoloEnabled` — likely via a new-conversation materialiser that reads the preference once at creation, not at every conversation open.
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this doc covers the shell + the Model section region at `20:113/117/122/127`, the Effort region at `20:130/131–140`, the YOLO region at `20:142+`, and the Context window region at `20:151-155`).

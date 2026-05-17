# StatusSheet

Stateless Material 3 `ModalBottomSheet` (shell + Model section [#254](../codebase/254.md); Effort + YOLO sections [#229](../codebase/229.md)) that hosts the Status Sheet — the surface a user opens by tapping the [`ThreadStatusRow`](thread-status-row.md) to inspect or change per-conversation run configuration. Renders Figma node `20:100`: a `"Run configuration"` title row with a trailing close icon, over three sections in order — **Model** (`selectableGroup`-wrapped radio rows for Opus 4.7 / Sonnet 4.6 / Haiku 4.5, each pairing the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) title with a short Figma-derived description), **Effort** (single `Row` of five `FilterChip`s — `low` / `medium` / `high` / `xhigh` / `max` from the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension), and **YOLO mode** (full-width `toggleable` row with a two-line label + an M3 `Switch`). The third (Context window) section is sibling slice [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230).

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
)
```

- **Public** (no `internal`) — same posture as the public shell in [`WorkspacePickerSheet`](workspace-picker-sheet.md) post-#220. The sheet has one production consumer ([`ThreadScreen`](thread-screen.md)) but the visibility decision tracks the sibling sheets, not consumer count.
- **`sheetState` defaulted but exposed** — the host can drive an animated close before invoking `onDismiss` if needed; defaulting keeps the host wiring one-line. `skipPartiallyExpanded = true` because the body is a short list, not a half-sheet.
- **No nullable callbacks** — every section in this sheet (currently three, expanding to four with [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230)) always renders, so all callbacks are always wired.
- **`selectedModel: Model` and `selectedEffort: Effort` are typed enums** — [#253](../codebase/253.md) lifted `selectedModel`, [#229](../codebase/229.md) followed the same shape for `selectedEffort`. The radio/chip selection compares against the enum rather than a label string, so a future relabel (e.g. `"Opus 4.7"` → `"Claude Opus 4.7"`) doesn't break selection identity.
- **`yoloEnabled: Boolean` is a primitive flag** — no nullable, no wrapper. The architectural single-writer invariant ([#229](../codebase/229.md)) is enforced at the VM layer (`private val yoloEnabled: MutableStateFlow<Boolean>` with one mutator, `ThreadViewModel.onYoloToggled`); the sheet's parameter is just the projection of that field.

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
8. **`Spacer(height = 24.dp)`** — bottom inset, matching the trailing spacer on the sibling sheets.

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

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row titles, YOLO row title |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section header, row descriptions, close icon, unselected `FilterChip` label, YOLO supporting text |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | Selected `FilterChip` background |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | Selected `FilterChip` label |
| `Schemes/outline` | `colorScheme.outline` | Unselected `FilterChip` 1dp border |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Run configuration"` |
| `Static/Label Large` | `typography.labelLarge` | `"Model"` / `"Effort"` / `"YOLO mode"` section headers |
| `Static/Body Large` | `typography.bodyLarge` | Model row titles, YOLO row title |
| `Static/Body Small` | `typography.bodySmall` | Model row descriptions, YOLO row supporting text |

## Recomposition / stability

- All seven callback params (`onModelSelected`, `onEffortSelected`, `onYoloToggled`, `onDismiss`) are `(T) -> Unit` / `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `selectedModel: Model`, `selectedEffort: Effort`, `yoloEnabled: Boolean` are all primitive (enum + boolean) — Compose-stable by definition; the row / chip / switch composables skip recomposition when their selection state is unchanged.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` + `FilterChip` + `Switch` all ship in `androidx.compose.material3` already in the BOM; `Modifier.selectable` / `selectableGroup` / `toggleable` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit across [#254](../codebase/254.md) or [#229](../codebase/229.md).
- **No new string resources.** Literals inline (`"Run configuration"`, `"Model"`, `"best for complex work"`, `"faster, cheaper"`, `"fastest"`, `"Effort"`, `"YOLO mode"`, `"Auto-accept tool calls"`, `"Claude runs commands without asking for confirmation. Use carefully."`, `"Close"`); first-localisation pass migrates everything together. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md).

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
        )
    }
}
```

Four things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Asymmetric auto-close: single-pick sections close the sheet, toggle sections don't** ([#229](../codebase/229.md) resolution to the #254 open question). `onModelSelected` and `onEffortSelected` both wrap in `{ value -> upstream(value); sheetVisible = false }` — a radio/chip pick is a complete action, M3 modal-bottom-sheet convention. `onYoloToggled` passes straight through with no auto-close — a Switch toggle is a state-change the user may want to immediately reverse, so closing the sheet would force a re-open just to undo. Apply the same rule to [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230)'s Context window section: discrete-value picker → auto-close, slider/toggle → keep open.
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

Five `@Preview` composables in `StatusSheet.kt`, all light-mode + `widthDp = 412` + `showBackground = true`. Each follows the canonical sheet-preview wrap that simulates the `ModalBottomSheet`'s default container colour + drag-handle gap:

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
            )
        }
    }
}
```

- **Model sweep** ([#254](../codebase/254.md)): `StatusSheetOpusPreview` (`Model.OPUS_4_7`), `StatusSheetSonnetPreview` (`Model.SONNET_4_6`), `StatusSheetHaikuPreview` (`Model.HAIKU_4_5`) — all with `selectedEffort = Effort.HIGH, yoloEnabled = false`.
- **Effort/YOLO sweep** ([#229](../codebase/229.md)): `StatusSheetEffortLowYoloOffPreview` (`Effort.LOW`, `yoloEnabled = false`) and `StatusSheetEffortMaxYoloOnPreview` (`Effort.MAX`, `yoloEnabled = true`) — both fix `Model.OPUS_4_7`, exercising the chip selection contrast and the switch on/off rendering. Satisfies the [#229](../codebase/229.md) AC line "both Effort variations (e.g. low and max) and YOLO on/off".

All five target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep is deliberately not included; the theme behaviour is identical across themes (no threshold-driven colors, no role-specific palettes). If a future ticket wants a dark sweep, mirror the [`WorkspacePickerSheet`](workspace-picker-sheet.md) `Preview + DarkPreview` pair.

## Tests

Eleven Compose UI tests in `androidTest/.../StatusSheetTest.kt` (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

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

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by [`ThreadViewModelTest`](thread-screen.md#testing) — `selectedModel` via [#253](../codebase/253.md), `selectedEffort` + `yoloEnabled` via [#229](../codebase/229.md) (seven new VM tests + two updated initial-value tests).

## Edge cases / limitations

- **Asymmetric auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. Tapping the YOLO row (or its switch) fires `onYoloToggled(!previous)` and **leaves the sheet open**. The asymmetry is deliberate: a single-pick is a complete action (M3 modal-bottom-sheet convention), but a Switch is a state-change the user may want to immediately reverse. Resolution to the [#254](../codebase/254.md) open question — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen).
- **No section abstraction.** The sheet body renders all three sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. This is **deliberate** per the [#254](../codebase/254.md) architecture spec — the [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) Context window slice is the natural re-evaluation point (four sections cross the third-occurrence threshold). Pre-introducing the abstraction would have multiplied the surface for two tickets of value.
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"YOLO mode"` headers, the three Model descriptions, the YOLO two-line label, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. Product-vendor strings like model descriptions may rewrite at that point — extracting them now would mix layers without lock-in value.
- **`Model.description()` is private to this file.** A future Settings model-picker (still pending per [`app-preferences`](app-preferences.md) gap) cannot import it. That is intentional — the descriptions are framed for the per-conversation override flow, not a global default. If Settings wants descriptions, it declares its own (potentially with different copy).
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` / `Switch.onCheckedChange` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) or `toggleable.onValueChange` (YOLO) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` and `Switch(checked, onCheckedChange = null)` with the parent `Row` owning the click. Pinned by the `tapping_*_invokes_*` tests' exact-list assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row.
- **YOLO ignores `AppPreferences.defaultYolo`.** The dormant `defaultYolo` flow on `AppPreferences` and the non-functional Settings YOLO row at `SettingsScreen.kt:80, 169` are both dead code; this sheet's `yoloEnabled` parameter is sourced from `ThreadViewModel`'s `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial, no preference read. The architectural single-writer invariant (`onYoloToggled` is the only writer of the field) is enforced by `private` visibility on the ViewModel's flow plus a code-review `git grep` check; the AC test `yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault` verifies the dormant preference does not leak. See [#229](../codebase/229.md) § Patterns established for the rationale.

## Related

- Ticket notes: [`../codebase/254.md`](../codebase/254.md) (shell + Model section), [`../codebase/229.md`](../codebase/229.md) (Effort + YOLO sections)
- Specs: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`, `docs/specs/architecture/229-statussheet-effort-yolo.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228) / [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected, onEffortSelected = vm::onEffortSelected, onYoloToggled = vm::onYoloToggled` at `MainActivity`.
  - [Thread status row](thread-status-row.md) — the entry point. The row's `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder) now fires `{ sheetVisible = true }` internally inside `ThreadScreen`.
  - [App preferences](app-preferences.md) — the `Model` + `Effort` enums + both [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) and (since [#229](../codebase/229.md)) public `Effort.label()` extensions, plus the `AppPreferences.defaultModel` / `defaultEffort` flows that back the VM's `selectedModelFlow` / `selectedEffortFlow`. The dormant `AppPreferences.defaultYolo` flow is deliberately not consumed.
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) — Context window section. Fourth section; if a repeated `Section` shape emerges across Model + Effort + YOLO + Context window, it can be factored here. Apply the same auto-close-vs-keep-open rule based on whether the section is a discrete pick or a state-change control.
  - Future Settings model-picker (`AppPreferences.setDefaultModel(...)` write-site, still pending per [`app-preferences`](app-preferences.md) gap) — re-uses [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) but **does not** re-use the private `Model.description()` extension here; it declares its own (different framing).
  - Settings YOLO cleanup — the dormant `mutableStateOf(false)` row at `SettingsScreen.kt:80, 169` and the dormant `AppPreferences.defaultYolo` flow can be removed in a separate ticket. Any future "default YOLO for new conversations" feature must respect the single-writer invariant on `ThreadViewModel.yoloEnabled` — likely via a new-conversation materialiser that reads the preference once at creation, not at every conversation open.
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this doc covers the shell + the Model section region at `20:113/117/122/127`, the Effort region at `20:130/131–140`, and the YOLO region at `20:142+`).

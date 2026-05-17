# StatusSheet

Stateless Material 3 `ModalBottomSheet` ([#254](../codebase/254.md)) that hosts the Status Sheet — the surface a user opens by tapping the [`ThreadStatusRow`](thread-status-row.md) to inspect or change per-conversation run configuration. Renders Figma node `20:100`: a `"Run configuration"` title row with a trailing close icon over a single **Model** section — `selectableGroup`-wrapped radio rows for Opus 4.7 / Sonnet 4.6 / Haiku 4.5, each pairing the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) title with a short Figma-derived description. Sibling slices append Effort + YOLO ([#229](https://github.com/pyrycode/pyrycode-mobile/issues/229)) and Context window ([#230](https://github.com/pyrycode/pyrycode-mobile/issues/230)) into the same `StatusSheetContent` `Column` — this slice ships only the shell + the Model section.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `StatusSheet.kt`. Third **sheet** in that package after [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) and [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)); follows their shell + `*Content` split verbatim.

## Shape

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
)
```

- **Public** (no `internal`) — same posture as the public shell in [`WorkspacePickerSheet`](workspace-picker-sheet.md) post-#220. The sheet has one production consumer ([`ThreadScreen`](thread-screen.md)) but the visibility decision tracks the sibling sheets, not consumer count.
- **`sheetState` defaulted but exposed** — the host can drive an animated close before invoking `onDismiss` if needed; defaulting keeps the host wiring one-line. `skipPartiallyExpanded = true` because the body is a short list, not a half-sheet.
- **No nullable callbacks** — every section in this sheet (currently one, expanding to four with #229/#230) always renders, so all callbacks are always wired.
- **`selectedModel: Model` is the typed enum from [#253](../codebase/253.md)** — the radio selection compares against the enum rather than a label string, so a future relabel (e.g. `"Opus 4.7"` → `"Claude Opus 4.7"`) doesn't break selection identity.

A peer `internal` composable carries the body:

```kotlin
@Composable
internal fun StatusSheetContent(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    onDismiss: () -> Unit,
)
```

`StatusSheet` is the `ModalBottomSheet` shell that delegates into it. The split exists so previews and Compose UI tests can render the content directly — the modal scrim + animation machinery don't render in the IDE preview pane and aren't wired into `createComposeRule()`-style tests. Same architectural shape M3 samples use for sheet previews, and the same shape `WorkspacePickerSheet` and `ChannelInfoSheet` follow.

## What it does

Single `Column(fillMaxWidth)` inside the `ModalBottomSheet`:

1. **`TitleRow(title = "Run configuration", onClose = onDismiss)`** — `titleLarge` in `onSurface` filling the row, trailing `IconButton(Icons.Filled.Close)` with `contentDescription = "Close"` and `tint = onSurfaceVariant`. Padding `start = 16, end = 4, top = 4, bottom = 12` per Figma `20:104`.
2. **`SectionHeader(text = "Model")`** — `labelLarge` in `onSurfaceVariant`, padding `start = 24, end = 16, top = 12, bottom = 4` per Figma `20:113`. Same shape as [`WorkspacePickerSheet`](workspace-picker-sheet.md)'s `"Recent"` / `"Other"` headers.
3. **`Column(modifier = Modifier.selectableGroup())`** wrapping `Model.entries.forEach { model -> ModelRow(model, selected = model == selectedModel, onClick = { onModelSelected(model) }) }` — three rows in source order (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`). The `selectableGroup()` modifier is the M3-recommended container for a radio set; TalkBack announces "1 of 3 selected" semantics at the group boundary rather than per row.
4. **`Spacer(height = 24.dp)`** — bottom inset, matching the trailing spacer on the sibling sheets.

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

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row titles |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section header, row descriptions, close icon |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Run configuration"` |
| `Static/Label Large` | `typography.labelLarge` | `"Model"` header |
| `Static/Body Large` | `typography.bodyLarge` | Model row titles |
| `Static/Body Small` | `typography.bodySmall` | Model row descriptions |

## Recomposition / stability

- All three callback params are `(Model) -> Unit` / `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `selectedModel: Model` is a primitive enum — Compose-stable by definition; the row composable skips recomposition when `selected` is unchanged.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` all ship in `androidx.compose.material3` already in the BOM; `Modifier.selectable` + `Modifier.selectableGroup` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit.
- **No new string resources.** Literals inline (`"Run configuration"`, `"Model"`, `"best for complex work"`, `"faster, cheaper"`, `"fastest"`, `"Close"`); first-localisation pass migrates everything together. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md).

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
            onDismiss = { sheetVisible = false },
        )
    }
}
```

Three things to notice:

- **`var sheetVisible by rememberSaveable { mutableStateOf(false) }`** lives on the screen, not on `ThreadViewModel`. UI-local presentation state with no business meaning belongs at the screen layer; pushing it into the VM would dirty the VM contract with screen-presentation concerns. `rememberSaveable` (over bare `remember`) means the sheet survives configuration changes (rotation) at zero VM-surface cost — same idiom as [`WorkspacePicker`](workspace-picker.md).
- **Auto-close on selection.** `onModelSelected = { model -> onModelSelected(model); sheetVisible = false }` flips visibility off immediately after forwarding. For a single-section sheet this matches the M3 modal-bottom-sheet convention (action-completing sheet, user picked, work is done). `ModalBottomSheet`'s hide animation runs on a coroutine the framework owns — no `sheetState.hide()` call needed before the flag flip. The auto-close decision will be revisited when [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) and [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) make the sheet a multi-setting surface.
- **Gated `if (sheetVisible) { StatusSheet(...) }`, not `AnimatedVisibility`.** `ModalBottomSheet` runs its own enter/exit animation; wrapping in `AnimatedVisibility` would double-animate the sheet. The `if` creates the composable on first show and destroys it on dismiss — what `ModalBottomSheet` expects.

The `ThreadScreen`-side `onModelSelected: (Model) -> Unit = {}` is defaulted so previews and other call sites keep compiling unchanged; `MainActivity` binds the parameter at the `CONVERSATION_THREAD` destination:

```kotlin
ThreadScreen(
    // ...
    onModelSelected = vm::onModelSelected,
    // ...
)
```

`vm::onModelSelected` is a method reference into [`ThreadViewModel.onModelSelected(model: Model)`](thread-screen.md#viewmodel) ([#253](../codebase/253.md)) — a synchronous `MutableStateFlow.value` write that updates the in-memory per-conversation override; it does **not** mutate `AppPreferences.defaultModel`.

## Preview

Three `@Preview` composables in `StatusSheet.kt`, one per `Model` value, all light-mode (matching the AC count of "three", not six). Each follows the canonical sheet-preview wrap that simulates the `ModalBottomSheet`'s default container colour + drag-handle gap:

```kotlin
PyrycodeMobileTheme(darkTheme = false) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) {
            StatusSheetContent(selectedModel = <model>, onModelSelected = {}, onDismiss = {})
        }
    }
}
```

All three previews carry `showBackground = true, widthDp = 412`. Names: `StatusSheetOpusPreview`, `StatusSheetSonnetPreview`, `StatusSheetHaikuPreview`. They target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme set is deliberately not included; the three light variants cover the AC's required three previews per selection, and the theme behaviour is identical across themes (no threshold-driven colors, no role-specific palettes). If a future ticket wants a dark sweep, mirror the [`WorkspacePickerSheet`](workspace-picker-sheet.md) `Preview + DarkPreview` pair.

## Tests

Five Compose UI tests in `androidTest/.../StatusSheetTest.kt` (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

- **`renders_model_section_with_all_three_rows_and_descriptions`** — `selectedModel = Model.OPUS_4_7`. Asserts "Model" header + each of `"Opus 4.7"` / `"best for complex work"` / `"Sonnet 4.6"` / `"faster, cheaper"` / `"Haiku 4.5"` / `"fastest"` are displayed via `hasText` matchers.
- **`tapping_sonnet_row_invokes_onModelSelected_with_sonnet`** — `selectedModel = Model.OPUS_4_7`, `onModelSelected = picks::add` capture. `onNode(hasText("Sonnet 4.6")).performClick()`. Asserts `picks == listOf(Model.SONNET_4_6)` (exact list equality — pins the "exactly one invocation" contract; a regression that fires the click twice would surface as `[SONNET_4_6, SONNET_4_6]`).
- **`tapping_haiku_row_invokes_onModelSelected_with_haiku`** — same shape, asserts `picks == listOf(Model.HAIKU_4_5)`.
- **`tapping_close_icon_invokes_onDismiss`** — `var invoked = 0`, `onNode(hasContentDescription("Close")).performClick()`, asserts `invoked == 1`. Pins the close-icon wiring + the exactly-one-invocation guarantee.
- **`selected_row_reports_selected_semantics`** — `selectedModel = Model.SONNET_4_6`. Matcher is `onNode(isSelectable() and hasAnyDescendant(hasText("Sonnet 4.6"))).assertIsSelected()` — `hasAnyDescendant` is required because the `selectable` modifier lives on the parent `Row`, not on the `Text` node; matching on the descendant text disambiguates among three selectable rows.

No unit tests under `app/src/test/`. The sheet is pure presentation; there's no `ThreadViewModel` change to cover, and the `selectedModel` plumbing is already pinned by [`ThreadViewModelTest`](thread-screen.md#testing) ([#253](../codebase/253.md)).

## Edge cases / limitations

- **Auto-close on every selection.** Tapping a radio row fires the callback and immediately closes the sheet — even if the user picks the model that was already selected. The "tap the current selection" gesture has no special handling (no toast, no haptic). This matches M3 modal-bottom-sheet convention for action-completing sheets; revisit when #229/#230 land if user testing shows the auto-close is too aggressive for the multi-section sheet.
- **No section abstraction.** The sheet body renders the Model section directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. This is **deliberate** per the architecture spec — sibling slices (#229 Effort + YOLO, #230 Context window) will append their sections; if a shared shape genuinely emerges by the third one, it can be factored then. Pre-introducing the abstraction would have multiplied the surface for one ticket of value.
- **No `strings.xml` extraction.** `"Run configuration"`, `"Model"`, and the three description strings are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. Product-vendor strings like model descriptions may rewrite at that point — extracting them now would mix layers without lock-in value.
- **`Model.description()` is private to this file.** A future Settings model-picker (still pending per [`app-preferences`](app-preferences.md) gap) cannot import it. That is intentional — the descriptions are framed for the per-conversation override flow, not a global default. If Settings wants descriptions, it declares its own (potentially with different copy).
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` handler** — passing one in addition to the row's `selectable.onClick` causes the callback to fire twice. The pattern is `RadioButton(selected = selected, onClick = null)` with the parent `Row` owning the click via `selectable`. Pinned by the `tapping_*_row_invokes_onModelSelected` tests' exact-list assertions.

## Related

- Ticket notes: [`../codebase/254.md`](../codebase/254.md)
- Spec: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected` at `MainActivity`.
  - [Thread status row](thread-status-row.md) — the entry point. The row's `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder) now fires `{ sheetVisible = true }` internally inside `ThreadScreen`.
  - [App preferences](app-preferences.md) — the `Model` enum + [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension (shared with the row), plus the `AppPreferences.defaultModel` flow that backs the VM's `selectedModelFlow`.
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) — Effort + YOLO sections append into `StatusSheetContent`. Will add `selectedEffort: Effort, onEffortSelected: (Effort) -> Unit, yoloEnabled: Boolean, onYoloToggled: (Boolean) -> Unit` to the content signature; will likely revisit the auto-close decision.
  - [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) — Context window section. Third section; if a repeated `Section` shape emerges, it can be factored here.
  - Future Settings model-picker (`AppPreferences.setDefaultModel(...)` write-site, still pending per [`app-preferences`](app-preferences.md) gap) — re-uses [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) but **does not** re-use the private `Model.description()` extension here; it declares its own (different framing).
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this slice ships the shell + the Model section region — header + three rows at `20:113/117/122/127`).

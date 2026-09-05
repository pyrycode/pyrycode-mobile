# StatusSheet

Stateless Material 3 `ModalBottomSheet` (shell + Model section [#254](../codebase/254.md); Effort + YOLO sections [#229](../codebase/229.md); Context window section [#230](../codebase/230.md), rendered as an honest "unavailable" state since [#601](../codebase/601.md)) that hosts the Status Sheet — the surface a user opens by tapping the [`ThreadStatusRow`](thread-status-row.md) to inspect or change per-conversation run configuration. Renders Figma node `20:100`: a `"Run configuration"` title row with a trailing close icon, over four sections in order — **Model** (`selectableGroup`-wrapped radio rows for Opus 4.7 / Sonnet 4.6 / Haiku 4.5, each pairing the [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) title with a short Figma-derived description), **Effort** (single `Row` of five `FilterChip`s — `low` / `medium` / `high` / `xhigh` / `max` from the public [`Effort.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) extension), **YOLO mode** (full-width `toggleable` row with a two-line label + an M3 `Switch`), and **Context window** (since [#601](../codebase/601.md): a `bodyLarge` `"Context usage unavailable"` label + `bodySmall` caption, no progress bar — the daemon does not serve mobile a real figure yet; see [§ `ContextWindowSection`](#contextwindowsection)).

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
- **No nullable callbacks** — every section in this sheet (four post-[#230](../codebase/230.md)) always renders, so all callbacks are always wired.
- **`selectedModel: Model` and `selectedEffort: Effort` are typed enums** — [#253](../codebase/253.md) lifted `selectedModel`, [#229](../codebase/229.md) followed the same shape for `selectedEffort`. The radio/chip selection compares against the enum rather than a label string, so a future relabel (e.g. `"Opus 4.7"` → `"Claude Opus 4.7"`) doesn't break selection identity.
- **`yoloEnabled: Boolean` is a primitive flag** — no nullable, no wrapper. The architectural single-writer invariant ([#229](../codebase/229.md)) is enforced at the VM layer (`private val yoloEnabled: MutableStateFlow<Boolean>` with one mutator, `ThreadViewModel.onYoloToggled`); the sheet's parameter is just the projection of that field.
- **No `tokenPercent` / `tokensUsed` / `tokensTotal` parameters.** [#230](../codebase/230.md) added them as `Int = 0`-defaulted display fields; [#601](../codebase/601.md) removed all three along with the render they fed, because the daemon has never served mobile a real figure and a hardcoded `73%` could never warn an operator about an actually-filling context window. Through #601 `ThreadUiState` still carried `tokenPercent` / `tokensUsed` / `tokensTotal` (populated from `STUB_TOKEN_PERCENT = 73` / `STUB_TOKENS_USED = 146_000` / `STUB_TOKENS_TOTAL = 200_000` at the VM) — the sheet simply stopped reading them, which is what let #601 ship without a `ThreadViewModel` edit. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, once both #601 and [#602](../codebase/602.md) (the [status row](thread-status-row.md)) had stopped reading them. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) re-adds parameters here when there is a real figure to carry; see [§ Edge cases / limitations](#edge-cases--limitations).

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
8. **`SectionHeader(text = "Context window")`** ([#230](../codebase/230.md)) — same padding + style as above per Figma `20:151`.
9. **`ContextWindowSection()`** ([#230](../codebase/230.md); parameterless since [#601](../codebase/601.md)) — read-only "unavailable" label + caption, no progress bar; see [§ `ContextWindowSection`](#contextwindowsection) below.
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
private fun ContextWindowSection()
```

Since [#601](../codebase/601.md), parameterless — it takes no token figures and reads only `MaterialTheme`. `Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp))` with two children:

1. Label `Text("Context usage unavailable", style = bodyLarge, color = onSurface)` — literal, not `stringResource` (every other piece of copy in this file is an inline literal). Replaces the Figma `20:152` figure; there is no daemon-served number to show.
2. Caption `Text("When full, oldest messages get dropped from claude's view (delimiter still shows; old messages stay in your scroll).", style = bodySmall, color = onSurfaceVariant)` — Figma `20:155`, **byte-identical to the pre-#601 caption**, including the lowercase `claude's`.

Read-only — no event surface, no callback, same as before #601. No auto-close (it's a display, not a picker) — taps inside the section do nothing.

**Wording is desktop-sourced, not invented.** pyrycode#1214 captured the desktop DOM on the same `stream-json` runner mobile ships against — `"...Context windowContext usage unavailableWhen full, oldest messages get dropped..."` — which pins both the exact text and the fact that desktop keeps the caption alongside it.

**Deliberate, spec'd Figma divergence.** Figma node `20:100`/`20:151` has no "unavailable" variant — it's the node the pre-#601 stub literally came from (`73% used (146K of 200K tokens)` over a filled bar). This section intentionally does not match that node; what survives is the header, the caption, and the layout/padding/typography. Flagged as a Figma-side gap worth filling, not blocking. See `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md` § Design source.

#### Deleted in #601: `formatTokens` and `progressColor`

Both file-private helpers — `formatTokens(n: Int) = "${n / 1000}K"` and a threshold `progressColor(percent: Int): Color` (`< 50 → primary`, `< 95 → warning`, `else → error`, the same boundaries as [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) but for fill chroma rather than text emphasis) — existed solely to render the stub figure and its `LinearProgressIndicator`. #601 deleted both along with their only call sites (verified by grep: zero references anywhere in `app/src`), plus the `LinearProgressIndicator` import, `androidx.compose.ui.graphics.Color`, and `de.pyryco.mobile.ui.theme.warning`. `ThreadStatusRow.tokenPercentColor` is untouched and still consumes the `warning` slot — the two helpers were always deliberately unshared (see [§ Edge cases / limitations](#edge-cases--limitations)), so deleting this file's copy didn't orphan the theme slot.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-low` | `colorScheme.surfaceContainerLow` (sheet bg) | `ModalBottomSheet` default |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, row titles, YOLO row title, Context window label |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Section header, row descriptions, close icon, unselected `FilterChip` label, YOLO supporting text, Context window caption |
| `Schemes/secondary-container` | `colorScheme.secondaryContainer` | Selected `FilterChip` background |
| `Schemes/on-secondary-container` | `colorScheme.onSecondaryContainer` | Selected `FilterChip` label |
| `Schemes/outline` | `colorScheme.outline` | Unselected `FilterChip` 1dp border |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Title Large` | `typography.titleLarge` | `"Run configuration"` |
| `Static/Label Large` | `typography.labelLarge` | `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` section headers |
| `Static/Body Large` | `typography.bodyLarge` | Model row titles, YOLO row title, Context window label |
| `Static/Body Small` | `typography.bodySmall` | Model row descriptions, YOLO row supporting text, Context window caption |

[#601](../codebase/601.md) removed four rows this file no longer draws: `surface-container-highest` (progress-bar track), `primary` / `warning` / `error` (progress-bar fill, threshold-driven). `warning` was still consumed elsewhere through #601 — `ThreadStatusRow`'s `tokenPercentColor` threshold helper — but [#602](../codebase/602.md) has since deleted that helper too, so `warning` no longer has a consumer in this feature area; see [warning-color.md](warning-color.md) for its other uses.

## Recomposition / stability

- All four callback params (`onModelSelected`, `onEffortSelected`, `onYoloToggled`, `onDismiss`) are `(T) -> Unit` / `() -> Unit` lambdas; the caller is responsible for `remember`-stabilising hot ones. Same posture as the rest of `ui/conversations/components/`.
- No internal mutable state, no `LaunchedEffect`, no `DisposableEffect`, no `rememberSaveable`. The only `remember` is the defaulted `rememberModalBottomSheetState(...)` parameter, which the host can override.
- `selectedModel: Model`, `selectedEffort: Effort`, `yoloEnabled: Boolean` are all primitive (enum, boolean) — Compose-stable by definition; the row / chip / switch composables skip recomposition when their inputs are unchanged.
- **Since [#601](../codebase/601.md): `ContextWindowSection()` is parameterless and reads only `MaterialTheme`**, so it recomposes on theme change alone — where previously (via `tokenPercent` / `tokensUsed` / `tokensTotal`) it also recomposed on any context-figure change. Those figures were constants, so the practical delta before #601 was zero, but the direction is now correct. No lambda captures, no unstable types, no `remember` needed. `LinearProgressIndicator`, its deferred `progress = { lambda }` read, and the `progressColor` / `formatTokens` helpers that fed it are gone along with the params.

## Configuration

- **No new dependencies.** `ModalBottomSheet` + `rememberModalBottomSheetState` + `SheetState` + `RadioButton` + `FilterChip` + `Switch` all ship in `androidx.compose.material3` already in the BOM (`composeBom = 2026.02.01`); `Modifier.selectable` / `selectableGroup` / `toggleable` are in `androidx.compose.foundation.selection`; `Icons.Filled.Close` is in `material-icons-extended`. No `gradle/libs.versions.toml` edit across [#254](../codebase/254.md), [#229](../codebase/229.md), [#230](../codebase/230.md), or [#601](../codebase/601.md). [#601](../codebase/601.md) removed the `LinearProgressIndicator` import (with its `gapSize` / `drawStopIndicator` params) — no dependency change, just an unused import gone.
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
        )
    }
}
```

(`tokenPercent` / `tokensUsed` / `tokensTotal` args removed at this call site in [#601](../codebase/601.md); `onDismiss` is now the trailing argument. [#603](../codebase/603.md) has since deleted the fields from `ThreadUiState` entirely, so there is no longer anything to omit here — the call site's shape is now permanent, not a transitional state.)

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

All three are method references into [`ThreadViewModel`](thread-screen.md#viewmodel). Through [#229](../codebase/229.md)/[#253](../codebase/253.md) they were synchronous `MutableStateFlow.value` writes only — an operator's tap moved the control locally and nothing else happened. **[#544](../codebase/544.md) wired all three to the daemon**: each still writes its override flow first (optimistic move — `onModelSelected`/`onEffortSelected` update the override layer over the matching `AppPreferences.default*` flow, `onYoloToggled` writes the `yoloEnabled` flag directly with no preference layer, preserving the architectural single-writer invariant), but now also sends only the changed field to `Conversation.currentSessionId` via `ConversationRepository.setSessionSettings` ([#543](../codebase/543.md)) and reverts the write on a daemon error or a not-connected `IllegalStateException`, surfacing the standard transient snackbar (`sessionSettingsErrors`, wired at [`ThreadScreen`](thread-screen.md) and `MainActivity`). The daemon's ack does not echo settings, so on success the optimistic value simply stays — "confirmed" means "sent and acked", not "read back". None of the three mutate `AppPreferences`. See [`../codebase/544.md`](../codebase/544.md) for the wiring detail (send helper, catch-triad, revert semantics).

## Preview

Five `@Preview` composables in `StatusSheet.kt` (nine before [#601](../codebase/601.md) deleted the four context-window-threshold previews below), all light-mode + `widthDp = 412` + `showBackground = true`. Each follows the canonical sheet-preview wrap that simulates the `ModalBottomSheet`'s default container colour + drag-handle gap:

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
- **Context window threshold sweep — deleted in [#601](../codebase/601.md).** [#230](../codebase/230.md) had added four previews (`StatusSheetContextWindow20/60/88/97Preview`) varying the three now-removed context-window params to show the `primary` / `warning` / `error` fill bands. With the params gone, each would have been byte-identical to `StatusSheetOpusPreview` — #601 deleted the blocks rather than stripping their args, to avoid four duplicate previews. The section they exercised no longer has a threshold to sweep.

All five target `StatusSheetContent` (not the modal) so the IDE preview pane renders — the modal scrim + animation machinery don't paint in the preview tooling. A dark-theme sweep is deliberately not included; the theme behaviour is identical across themes. If a future ticket wants a dark sweep, mirror the [`WorkspacePickerSheet`](workspace-picker-sheet.md) `Preview + DarkPreview` pair.

## Tests

Twelve Compose UI tests in `androidTest/.../StatusSheetTest.kt` (fourteen before [#601](../codebase/601.md) rewrote one and deleted two — see below) (`createComposeRule()` + `AndroidJUnit4` + `PyrycodeMobileTheme` wrapper — matches [`WorkspacePickerSheetTest`](workspace-picker-sheet.md#tests) shape). All target `StatusSheetContent` (not the `ModalBottomSheet`) because the modal machinery requires an attached `Activity` host:

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

Context window section ([#230](../codebase/230.md); rewritten in [#601](../codebase/601.md)):

- **`renders_context_window_section_as_unavailable_with_header_and_caption`** — renamed from `renders_context_window_section_with_header_label_and_caption` in #601, which also dropped the 3 token args from the `StatusSheetContent(...)` call. Asserts `"Context window"` header + `"Context usage unavailable"` + the full caption are all displayed, **and** that no progress indicator exists anywhere in the tree: `onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)`. `keyIsDefined` (not a value-based `expectValue`) is the right matcher because the point is that no `ProgressBarRangeInfo` node exists at all. Safe to assert over the whole tree — no other node in `StatusSheetContent` (`RadioButton`, `FilterChip`, `Switch`) sets that key.
- **`label_format_uses_integer_K_division` — deleted in #601.** Asserted only `formatTokens` output (`"5% used (12K of 200K tokens)"`); with the formatter gone there was nothing left to exercise.
- **`label_format_handles_zero_values_gracefully` — deleted in #601, compiler-invisible.** Passed **no** token args at all, relying on the `= 0` parameter defaults to assert `"0% used (0K of 0K tokens)"` — so removing the params left it compiling clean and failing only at run time on device. `compileDebugAndroidTestKotlin` going green is not proof this test file is finished when defaulted params are the thing being removed; it had to be found and deleted by name.

No unit tests under `app/src/test/` for the sheet itself. The sheet is pure presentation; the underlying VM plumbing is pinned by [`ThreadViewModelTest`](thread-screen-testing.md#testing) — `selectedModel` via [#253](../codebase/253.md), `selectedEffort` + `yoloEnabled` via [#229](../codebase/229.md). The `tokenPercent` / `tokensUsed` / `tokensTotal` `ThreadViewModelTest` assertions added in [#230](../codebase/230.md) were untouched by #601 (`ThreadViewModel` was not edited); [#603](../codebase/603.md) has since dropped those trailing assertions and renamed the two affected test methods — see [thread-status-row.md § Testing](thread-status-row.md#testing) for the current names.

## Edge cases / limitations

- **Asymmetric auto-close on selection.** Tapping a radio row (Model) or chip (Effort) fires the callback and immediately closes the sheet — even if the user picks the value that was already selected. Tapping the YOLO row (or its switch) fires `onYoloToggled(!previous)` and **leaves the sheet open**. The Context window section has no callback at all — it's read-only. The asymmetry is deliberate: a single-pick is a complete action (M3 modal-bottom-sheet convention), but a Switch is a state-change the user may want to immediately reverse, and a display panel has no action surface to begin with. Resolution to the [#254](../codebase/254.md) open question, extended in [#230](../codebase/230.md) — see [§ Hosting in `ThreadScreen`](#hosting-in-threadscreen).
- **No section abstraction.** The sheet body renders all four sections directly inside the `StatusSheetContent` `Column`; there is no `Section` interface or `SectionList` helper. The [#229](../codebase/229.md) spec flagged [#230](../codebase/230.md) as the re-evaluation point; [#230](../codebase/230.md)'s call was "still not yet — four sections is the threshold but the body divergence is high (radio rows / chip row / toggle row / label+progress+caption block); wait for a fifth occurrence or a real shape divergence". The header + container shape is repeated; the body shape is not. Pre-introducing the abstraction would have multiplied the surface for three tickets of value.
- **`progressColor` no longer exists in this file — deleted whole in [#601](../codebase/601.md).** It shared its `< 50 / < 95 / ≥ 95` boundaries with [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) (still live, unaffected by #601) but never its colour slots — `progressColor` returned fill chroma (`primary` / `warning` / `error`), `tokenPercentColor` returns text emphasis (`onSurfaceVariant` / `warning` / `error`). That non-sharing decision is now moot for this file (nothing here needs a threshold colour), but `tokenPercentColor` on the always-visible row still carries the same `73%` stub — see [#602](https://github.com/pyrycode/pyrycode-mobile/issues/602), the sibling ticket that removes the row's fabricated segment.
- **No `strings.xml` extraction.** All inline strings (`"Run configuration"`, `"Model"` / `"Effort"` / `"YOLO mode"` / `"Context window"` headers, the three Model descriptions, the YOLO two-line label, the Context window caption, `"Close"`) are Kotlin literals. Same posture as [`WorkspacePickerSheet`](workspace-picker-sheet.md) and [`ChannelInfoSheet`](channel-info-sheet.md); first-localisation pass migrates everything together. Product-vendor strings like model descriptions may rewrite at that point — extracting them now would mix layers without lock-in value.
- **`Model.description()` is private to this file.** A future Settings model-picker (still pending per [`app-preferences`](app-preferences.md) gap) cannot import it. That is intentional — the descriptions are framed for the per-conversation override flow, not a global default. If Settings wants descriptions, it declares its own (potentially with different copy).
- **`ModalBottomSheet` scrim and back-press both route to `onDismiss`** — provided by the M3 component; the host doesn't need to wire either separately. Drag-down-to-dismiss is supported by the M3 `SheetState` default behaviour.
- **No `RadioButton.onClick` / `Switch.onCheckedChange` handler on the inner control.** Passing one in addition to the parent row's `selectable.onClick` (Model) or `toggleable.onValueChange` (YOLO) causes the callback to fire twice. The canonical M3 pattern is `RadioButton(selected, onClick = null)` and `Switch(checked, onCheckedChange = null)` with the parent `Row` owning the click. Pinned by the `tapping_*_invokes_*` tests' exact-list assertions ([§ Tests](#tests)). `FilterChip` does **not** follow this pattern — the chip itself owns the click and `selectableGroup()` wraps the row.
- **YOLO ignores `AppPreferences.defaultYolo`.** The dormant `defaultYolo` flow on `AppPreferences` and the non-functional Settings YOLO row at `SettingsScreen.kt:80, 169` are both dead code; this sheet's `yoloEnabled` parameter is sourced from `ThreadViewModel`'s `private val yoloEnabled = MutableStateFlow(false)` — hardcoded `false` initial, no preference read. The architectural single-writer invariant (`onYoloToggled` is the only writer of the field) is enforced by `private` visibility on the ViewModel's flow plus a code-review `git grep` check; the AC test `yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault` verifies the dormant preference does not leak. See [#229](../codebase/229.md) § Patterns established for the rationale.
- **Context window figure is unavailable by design, not by stub.** Through [#601](../codebase/601.md) `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` still populated from `STUB_*` companion constants (`73`, `146_000`, `200_000`) on `ThreadViewModel` — untouched, since #601 was client-only — while this sheet no longer read any of them, so no stub figure reached the user here. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, so the fields don't exist at all any more. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) reintroduces parameters and a render once the daemon serves a real figure; the ticket's Technical Notes explicitly reject modeling a sealed `Unavailable | Known(...)` type now, since there is exactly one possible value today.

## Related

- Ticket notes: [`../codebase/254.md`](../codebase/254.md) (shell + Model section), [`../codebase/229.md`](../codebase/229.md) (Effort + YOLO sections), [`../codebase/230.md`](../codebase/230.md) (Context window section, stub), [`../codebase/601.md`](../codebase/601.md) (Context window section, "unavailable" render)
- Specs: `docs/specs/architecture/254-statussheet-scaffold-model-section.md`, `docs/specs/architecture/229-statussheet-effort-yolo.md`, `docs/specs/architecture/230-status-sheet-context-window.md`, `docs/specs/architecture/601-status-sheet-context-usage-unavailable.md`
- Parent: split from [#228](https://github.com/pyrycode/pyrycode-mobile/issues/228) / [#146](https://github.com/pyrycode/pyrycode-mobile/issues/146).
- Upstream:
  - [Thread screen](thread-screen.md) — the host. Owns the `rememberSaveable`-hoisted `sheetVisible` flag, mounts `StatusSheet` as a `Scaffold` sibling alongside the existing [`WorkspacePicker`](workspace-picker.md), and wires `onModelSelected = vm::onModelSelected, onEffortSelected = vm::onEffortSelected, onYoloToggled = vm::onYoloToggled` at `MainActivity`. Since [#601](../codebase/601.md) it no longer reads `tokenPercent` / `tokensUsed` / `tokensTotal` off `state` at this call site — `onDismiss` is the trailing argument now. Since [#544](../codebase/544.md) it also collects `sessionSettingsErrors` into the shared snackbar host — see § Hosting in `ThreadScreen` above.
  - [`../codebase/544.md`](../codebase/544.md) — sends the three controls' changes to the daemon (`ConversationRepository.setSessionSettings`, [#543](../codebase/543.md)) and reverts + snackbars on failure; the sheet's own composable is untouched.
  - [Thread status row](thread-status-row.md) — the entry point. The row's `onExpandClick` (shipped in [#145](../codebase/145.md) as a hoisted placeholder) now fires `{ sheetVisible = true }` internally inside `ThreadScreen`. Rendered the `73%` stub via `tokenPercentColor` through #601, which deliberately left this row untouched; [#602](../codebase/602.md) is the sibling ticket that removed its fabricated segment and the helper.
  - [App preferences](app-preferences.md) — the `Model` + `Effort` enums + both [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) and (since [#229](../codebase/229.md)) public `Effort.label()` extensions, plus the `AppPreferences.defaultModel` / `defaultEffort` flows that back the VM's `selectedModelFlow` / `selectedEffortFlow`. The dormant `AppPreferences.defaultYolo` flow is deliberately not consumed.
  - [Warning color slot](warning-color.md) — `MaterialTheme.colorScheme.warning` ([#119](../codebase/119.md)) was consumed at the 50-95% band of `progressColor` (Context window fill) until [#601](../codebase/601.md) deleted it, and at the same band of `tokenPercentColor` (status row text) until [#602](../codebase/602.md) deleted that helper too.
  - [`WorkspacePickerSheet`](workspace-picker-sheet.md) ([#212](../codebase/212.md)) + [`ChannelInfoSheet`](channel-info-sheet.md) ([#217](../codebase/217.md)) — sibling sheets following the same shell + `*Content` split. Three identical `TitleRow` / `SectionHeader` privates now live in the package; extraction is deferred until the first divergent-shape ticket lands.
- Sibling / downstream:
  - **[#591](https://github.com/pyrycode/pyrycode-mobile/issues/591)** (blocked on daemon-side pyrycode PR #1215) — serves a real context figure and re-adds parameters to `StatusSheet` / `StatusSheetContent` / `ContextWindowSection` to render it in place of the "unavailable" text this ticket ships. [#603](../codebase/603.md) has since deleted `ThreadUiState.tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants outright, so #591 designs the shape it needs against real wire data rather than restoring these — see [`../codebase/603.md`](../codebase/603.md).
  - **`Section` abstraction across the four sections** — still deferred per [#230](../codebase/230.md). Four `SectionHeader + <body>` pairs now live in the `Column`; the body shape diverges (radio rows / chip row / toggle row / label+progress+caption block), so a `SheetSection(header: String, body: @Composable () -> Unit)` thin wrapper might be the right abstraction. The architect's call for [#230](../codebase/230.md) was "still not yet — wait for a fifth occurrence or a real shape divergence".
  - Future Settings model-picker (`AppPreferences.setDefaultModel(...)` write-site, still pending per [`app-preferences`](app-preferences.md) gap) — re-uses [`Model.label()`](app-preferences.md#design-decision-defer-label-extensions-on-data-layer-enums) but **does not** re-use the private `Model.description()` extension here; it declares its own (different framing).
  - Settings YOLO cleanup — the dormant `mutableStateOf(false)` row at `SettingsScreen.kt:80, 169` and the dormant `AppPreferences.defaultYolo` flow can be removed in a separate ticket. Any future "default YOLO for new conversations" feature must respect the single-writer invariant on `ThreadViewModel.yoloEnabled` — likely via a new-conversation materialiser that reads the preference once at creation, not at every conversation open.
- Figma: [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (full Run-configuration sheet; this doc covers the shell + the Model section region at `20:113/117/122/127`, the Effort region at `20:130/131–140`, the YOLO region at `20:142+`, and the Context window region at `20:151-155`).

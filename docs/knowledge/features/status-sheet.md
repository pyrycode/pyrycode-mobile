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
- **No `tokenPercent` / `tokensUsed` / `tokensTotal` parameters.** [#230](../codebase/230.md) added them as `Int = 0`-defaulted display fields; [#601](../codebase/601.md) removed all three along with the render they fed, because the daemon has never served mobile a real figure and a hardcoded `73%` could never warn an operator about an actually-filling context window. Through #601 `ThreadUiState` still carried `tokenPercent` / `tokensUsed` / `tokensTotal` (populated from `STUB_TOKEN_PERCENT = 73` / `STUB_TOKENS_USED = 146_000` / `STUB_TOKENS_TOTAL = 200_000` at the VM) — the sheet simply stopped reading them, which is what let #601 ship without a `ThreadViewModel` edit. [#603](../codebase/603.md) has since deleted the fields and the `STUB_*` constants outright, once both #601 and [#602](../codebase/602.md) (the [status row](thread-status-row.md)) had stopped reading them. [#591](https://github.com/pyrycode/pyrycode-mobile/issues/591) (blocked on daemon-side pyrycode PR #1215) re-adds parameters here when there is a real figure to carry; see [§ Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations).

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

Both file-private helpers — `formatTokens(n: Int) = "${n / 1000}K"` and a threshold `progressColor(percent: Int): Color` (`< 50 → primary`, `< 95 → warning`, `else → error`, the same boundaries as [`ThreadStatusRow.tokenPercentColor`](thread-status-row.md#tokenpercentcolor--threshold-helper) but for fill chroma rather than text emphasis) — existed solely to render the stub figure and its `LinearProgressIndicator`. #601 deleted both along with their only call sites (verified by grep: zero references anywhere in `app/src`), plus the `LinearProgressIndicator` import, `androidx.compose.ui.graphics.Color`, and `de.pyryco.mobile.ui.theme.warning`. `ThreadStatusRow.tokenPercentColor` is untouched and still consumes the `warning` slot — the two helpers were always deliberately unshared (see [§ Edge cases / limitations](status-sheet-hosting-tests-and-edge-cases.md#edge-cases--limitations)), so deleting this file's copy didn't orphan the theme slot.

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

Split into [StatusSheet — hosting, tests and edge cases](status-sheet-hosting-tests-and-edge-cases.md) on 2026-09-22 to keep this document under the 50000-byte cap the docs guard enforces. Every section — Hosting in `ThreadScreen`, Preview, Tests and Edge cases / limitations — moved there verbatim, headings and anchors intact.

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

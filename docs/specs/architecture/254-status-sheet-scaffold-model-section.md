# 254 — StatusSheet scaffold + Model section UI

Issue: [pyrycode/pyrycode-mobile#254](https://github.com/pyrycode/pyrycode-mobile/issues/254). Size: S.

## Context

`ThreadStatusRow` (#145) already exposes an `onExpandClick: () -> Unit` that's wired in `MainActivity` as a no-op with a `// TODO(#146): open Status Sheet` marker. The prerequisite slice #253 (merged) added `ThreadUiState.selectedModel: Model` plus `ThreadViewModel.onModelSelected(Model)`.

This slice ships the first visible piece of the Status Sheet: a Material 3 `ModalBottomSheet` with a single **Model** section (radio rows for Opus 4.7 / Sonnet 4.6 / Haiku 4.5). Sibling slices append Effort + YOLO (#229) and Context window (#230); the layout must stay extensible for them without introducing a section abstraction now.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20:100

Node `20:100` is the full "Run configuration" sheet; this slice implements only the shell (drag handle + title row with "Run configuration" + close X) and the **Model** section region (`label-large` "Model" header + three rows of `body-large` title + `body-small` description with a leading radio control). Tokens are M3 `Schemes/*`: surface-container-low background (provided by `ModalBottomSheet`), on-surface for the title and row titles, on-surface-variant for the section header / row descriptions / close icon.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:36-72` — current `ThreadScreen` parameter list and the `ThreadStatusRow` wiring inside `bottomBar`. `onExpandClick` flows through; previews don't pass it (all rely on the default).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:200-386` — four `@Preview` composables. None of them pass `onExpandClick`; all rely on the default. Confirms removal of the parameter is safe.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-216` — the `CONVERSATION_THREAD` route. Holds the `onExpandClick = {}` + `// TODO(#146)` line to be removed and the call site where `onModelSelected = vm::onModelSelected` lands.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:35-92,129-131` — `ThreadUiState.selectedModel: Model` (initial value `Model.OPUS_4_7`) and `fun onModelSelected(model: Model)` (writes to `modelOverride: MutableStateFlow<Model?>`). Prerequisite from #253.
- `app/src/main/java/de/pyryco/mobile/data/preferences/Model.kt:1-11` — the `Model` enum (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`) plus `Model.label(): String` extension. Use the existing labels verbatim ("Opus 4.7" / "Sonnet 4.6" / "Haiku 4.5").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt` — convention: `Sheet` (public, wraps `ModalBottomSheet` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)`) delegates to internal `SheetContent` (public-internal `Column` body), with `TitleRow` + `SectionHeader` private helpers. **Follow this split exactly** — preview composables render `SheetContent` inside a `Surface` (not the real sheet) because Compose Previews don't reliably draw `ModalBottomSheet`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ChannelInfoSheet.kt:60-165` — second instance of the same convention; lift the `SectionHeader`'s padding values (`start = 24.dp, end = 16.dp, top = 12.dp, bottom = 4.dp`) and label-large + on-surface-variant styling verbatim so the Status Sheet visually matches its siblings.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheetTest.kt:1-60` — test pattern: `createComposeRule()`, `setContent { PyrycodeMobileTheme { StatusSheetContent(...) } }`, drive interactions via `hasText` / `hasContentDescription` matchers + `performClick`. Mirror this shape.

## Design

### New file: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt`

Two-composable split mirroring `WorkspacePickerSheet`:

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

Wraps `ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = sheetState)`. Body delegates to:

```kotlin
@Composable
internal fun StatusSheetContent(
    selectedModel: Model,
    onModelSelected: (Model) -> Unit,
    onDismiss: () -> Unit,
)
```

`StatusSheetContent` is a single `Column(modifier = Modifier.fillMaxWidth())` containing, in order:

1. `TitleRow(title = "Run configuration", onClose = onDismiss)` — copy the `TitleRow` shape from `WorkspacePickerSheet.kt:82-104` exactly (padding `start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp`, `titleLarge` text on `onSurface`, `IconButton` with `Icons.Filled.Close` tinted `onSurfaceVariant`, `contentDescription = "Close"`).
2. `SectionHeader(text = "Model")` — same shape as `WorkspacePickerSheet.kt:107-117`.
3. Three `ModelRow` instances, one per `Model` enum value in source order (`OPUS_4_7`, `SONNET_4_6`, `HAIKU_4_5`), each receiving `selected = (model == selectedModel)` and `onClick = { onModelSelected(model) }`.
4. `Spacer(modifier = Modifier.height(24.dp))` bottom inset, matching sibling sheets.

### `ModelRow` (private composable, same file)

Signature: `private fun ModelRow(model: Model, selected: Boolean, onClick: () -> Unit)`. Renders a horizontal `Row` with:

- `Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(horizontal = 16.dp, vertical = 4.dp)` — `Modifier.selectable` (from `androidx.compose.foundation.selection`) provides the click + a11y `RadioButton` role for the whole row. Do not also attach `onClick` to the `RadioButton` itself — let the row own the click so the touch target spans the row.
- `RadioButton(selected = selected, onClick = null)` (null because `selectable` handles it), default M3 colors. Vertical alignment `Alignment.Top` so it sits next to the title baseline.
- `Spacer(modifier = Modifier.width(12.dp))`.
- A `Column` with the two-line label/description:
  - Title `Text(text = model.label(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)` — use the existing `Model.label()` extension; do not duplicate the strings.
  - Description `Text(text = model.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`.

Add a private extension `private fun Model.description(): String` co-located in `StatusSheet.kt`, mapping:

| Model | Description (from Figma node `20:117/122/127`) |
| --- | --- |
| `OPUS_4_7` | `"best for complex work"` |
| `SONNET_4_6` | `"faster, cheaper"` |
| `HAIKU_4_5` | `"fastest"` |

Keep the extension `private` in this file — it's a Figma-derived presentation string, not domain data.

### Modified: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Three edits:

1. **Parameter list:** add `onModelSelected: (Model) -> Unit = {}`. **Remove** `onExpandClick: () -> Unit = {}` (it has no remaining external consumer after this slice — MainActivity stops passing it and the four previews never passed it). Add `import de.pyryco.mobile.data.preferences.Model` and `import de.pyryco.mobile.ui.conversations.components.StatusSheet`.

2. **Sheet visibility state** inside the `ThreadScreen` body, hoisted at the top before `Scaffold`:
   ```kotlin
   var sheetVisible by rememberSaveable { mutableStateOf(false) }
   ```
   Use `rememberSaveable` (not bare `remember`) so the sheet state survives configuration changes — matches the `rememberSaveable` use in `WorkspacePicker.kt:45`.

3. **Wire the status row + embed the sheet.** In `bottomBar`, change `onExpandClick = onExpandClick` to `onExpandClick = { sheetVisible = true }`. After the `WorkspacePicker(...)` block at line 139-143, add:
   ```kotlin
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
   ```
   Closing on selection matches the M3 modal-bottom-sheet convention for action-completing sheets (the user picked a model; the work is done). If we discover users want to toggle multiple settings without re-opening, we revisit when #229/#230 land — but for a single-section sheet, auto-close on select is correct.

The four existing `@Preview` composables (lines 200-386) keep compiling unchanged — they never passed `onExpandClick`, and the new `onModelSelected` has a default. No preview updates required for this slice; the three new previews live in `StatusSheet.kt`.

### Modified: `app/src/main/java/de/pyryco/mobile/MainActivity.kt`

At the `CONVERSATION_THREAD` route (lines 204-215):

- Remove the line `// TODO(#146): open Status Sheet` and the `onExpandClick = {},` line beneath it.
- Add `onModelSelected = vm::onModelSelected,` in the same call site (alongside the existing `onWorkspacePicked = vm::onWorkspacePicked` etc.).

### State + concurrency model

No new flows. `ThreadViewModel.modelOverride: MutableStateFlow<Model?>` is the existing single source of truth; `onModelSelected(model)` writes to it; `selectedModelFlow` already combines it with the app preference into `ThreadUiState.selectedModel`. The sheet is a stateless renderer of that state plus a callback into the existing `vm::onModelSelected`.

Sheet visibility is **UI-local state** that does not belong in `ThreadViewModel` — it has no business meaning the VM needs to react to and would dirty the VM contract with screen-presentation concerns. `rememberSaveable` survives rotation; that's enough.

`ModalBottomSheet` runs its hide animation on a Compose coroutine that the framework already manages — we do not need to call `sheetState.hide()` manually before flipping `sheetVisible` to `false`. The auto-dismiss-on-select pattern (write `sheetVisible = false`) lets `ModalBottomSheet` animate out via its own swipe-to-dismiss mechanics.

### Error handling

No new failure modes. `ThreadViewModel.onModelSelected` is a pure `MutableStateFlow` write; it cannot throw. No I/O, no async work added by this slice.

## Testing strategy

New file `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt`, following the `WorkspacePickerSheetTest` shape (`createComposeRule()`, render `StatusSheetContent` directly — not the `ModalBottomSheet` wrapper):

- **Renders all three model rows with their titles and descriptions.** With `selectedModel = Model.OPUS_4_7`, assert "Opus 4.7" + "best for complex work" + "Sonnet 4.6" + "faster, cheaper" + "Haiku 4.5" + "fastest" are all displayed.
- **Tapping a model row invokes `onModelSelected` with that `Model`.** Render with `selectedModel = Model.OPUS_4_7`, capture into a `mutableListOf<Model>`, `onNode(hasText("Sonnet 4.6")).performClick()`, assert the list contains exactly `Model.SONNET_4_6`. Repeat for Haiku.
- **Tapping the close icon invokes `onDismiss`.** Use `hasContentDescription("Close")`, `performClick`, assert the dismiss-callback was invoked once.
- **Selection state reflects `selectedModel`.** With `selectedModel = Model.SONNET_4_6`, assert the Sonnet row's radio is selected (`onNode(hasText("Sonnet 4.6") and hasParent(isSelectable())).assertIsSelected()` or equivalent — see Compose test docs for the exact matcher; mirroring how existing tests express `RadioButton` selection).

No unit tests under `app/src/test/`. The sheet is pure presentation; there's no `ThreadViewModel` change to cover, and the `selectedModel` plumbing already has #253's coverage.

`./gradlew test` should pass unchanged. `./gradlew connectedAndroidTest` requires the new `StatusSheetTest` to pass (plus the existing suite).

### Previews

Three `@Preview` composables in `StatusSheet.kt`, one per `Model` value, all light-mode (matching the AC count of "three", not six). Each follows the `WorkspacePickerSheetPreview` shape: wrap `StatusSheetContent(...)` in `PyrycodeMobileTheme { Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(modifier = Modifier.padding(PaddingValues(top = 12.dp))) { ... } } }`. Use `widthDp = 412`. Name them `StatusSheetOpusPreview`, `StatusSheetSonnetPreview`, `StatusSheetHaikuPreview`.

## Open questions

- **Auto-close on selection vs persistent selection.** Spec auto-closes on select (single-section sheet → action completes → dismiss). If product disagrees once Effort/YOLO/Context window siblings land (#229/#230 will make the sheet a multi-setting surface), revisit then. Not a blocker now.
- **Title row "Run configuration" string.** Pulled verbatim from Figma node `20:104`. No `strings.xml` extraction in this slice — `WorkspacePickerSheet` and `ChannelInfoSheet` both inline their titles ("Choose workspace", channel name), so this matches the local convention. Externalise to `strings.xml` only if the project introduces a translation effort.

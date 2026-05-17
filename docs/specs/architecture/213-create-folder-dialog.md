# 213 — `CreateFolderDialog` stateless composable (Figma 19:44)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/ThemePickerDialog.kt:1-75` — the canonical in-repo `AlertDialog` reference. Mirror its shape: `@Composable internal fun ...`, `AlertDialog(onDismissRequest, title, text, confirmButton, dismissButton)`, `TextButton`s for both actions. Do NOT mirror the `R.string.*` extraction — per ticket Technical Notes, labels stay inline for this composable.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheet.kt:1-227` — sibling composable just landed via #212. Match the file-layout convention exactly: top-level `internal` composable, `private` helpers in the same file, two `@Preview`s (Light + Dark) at the bottom using `PyrycodeMobileTheme` and `Configuration.UI_MODE_NIGHT_YES`, sample-data `val` at the bottom scoped `private`. The picker calls this dialog's host indirectly (via `onCreateNew`) in #207; the two composables are siblings in the same package.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` (skim) — `PyrycodeMobileTheme` entry point. Wrap both previews in it.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt` — confirms `AppTypography = Typography()` (M3 defaults). All typography resolves through `MaterialTheme.typography.*` — no custom text styles.
- `gradle/libs.versions.toml` (search `androidx-compose-material3`) — confirms `androidx.compose.material3.material3` is present. `AlertDialog`, `OutlinedTextField`, and `TextButton` ship in that artifact. No new dependency.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-44

Dark Material 3 `AlertDialog` (`Schemes/surface-container-high` background, 28dp radius, 24dp padding) with three vertically-stacked sections separated by 16dp gaps: a `headlineSmall` title (Figma label is **"Create workspace"**, not "Create folder" — see Deviation 1), an outlined input field showing the labelMedium prompt **"What should this workspace be called?"** as the floating label, and a trailing-aligned action row of two text buttons — `Cancel` then `Create`, both in `colorScheme.primary` per Figma `19:51/19:53`.

## Context

`CreateFolderDialog` is the second UI surface in the Workspace Picker flow, sitting alongside `WorkspacePickerSheet` (#212). It is invoked by the host (#207) when the user taps the picker's "Create new folder" row. Its single responsibility is to render the M3 `AlertDialog` chrome, manage the input field's local `TextFieldValue` (this is the dialog's own UI state — not hoisted, not in a ViewModel), and emit two callbacks: `onCreate(trimmed)` on submit, `onDismiss()` on cancel / outside-tap / back-press.

State hoisting, sheet/dialog visibility coordination, repository binding (`createWorkspaceFolder(name)` from #210), and the picker-→-dialog handoff all live in #207. This ticket is pure UI: stateless contract, with the input's `TextFieldValue` deliberately scoped to the dialog itself because no other consumer (host, picker, ViewModel) needs to read it before submit.

The contract is intentionally narrow. No `ViewModel`, no repository, no validation beyond "trimmed name is non-blank," no error display (the host handles repo-level failures after `onCreate` fires).

## Design

### Composable signature

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt`

```kotlin
@Composable
internal fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Notes on the signature:

- **Visibility `internal`.** Same as `ThemePickerDialog` and `WorkspacePickerSheet`. The host (#207) lives in a sibling package; consumed only within the module.
- **`modifier: Modifier = Modifier`.** Standard Compose hygiene; `AlertDialog` accepts a `Modifier` for the surface.
- **No `initialName` parameter.** The dialog opens with an empty input. The "pre-select the full text on first composition" UX in ticket Technical Notes is implemented via `TextFieldValue(text = "", selection = TextRange.Zero)` + `FocusRequester` — there is no initial-name use case in #207, so adding a parameter for it would be speculation.
- **No nullable callbacks.** Both `onCreate` and `onDismiss` are required.
- **`onCreate` receives the trimmed name.** The dialog trims before invoking; the host never sees leading/trailing whitespace.

### Internal state

Two pieces of local UI state inside the composable:

```kotlin
val focusRequester = remember { FocusRequester() }
var fieldValue by remember {
    mutableStateOf(TextFieldValue(text = "", selection = TextRange.Zero))
}
```

- `fieldValue: TextFieldValue` — the input's text + selection. `TextFieldValue` (not `String`) because the spec requires programmatic selection control on first composition.
- `focusRequester` — used to pull keyboard focus to the input when the dialog opens.

A `LaunchedEffect(Unit) { focusRequester.requestFocus() }` block requests focus once per dialog instance. Since the dialog opens with an empty string, selection is moot for the visible state — but matching the ticket Technical Notes ("pre-select the full text on first composition") means: when (and only when) the user does enter text and somehow the dialog gets recomposed with a non-empty initial value in a future variant, the selection-set-on-first-composition pattern is already in place. Today, with the empty-init contract, this resolves to `TextRange.Zero` — focus + visible cursor at position 0, no selection. The developer should keep the `TextFieldValue` representation (not collapse to `String`) so that a future `initialName` parameter (added in a follow-up if needed) can ship `TextRange(0, initialName.length)` without re-shaping the state.

The `derivedStateOf` for the trimmed name + enabled flag:

```kotlin
val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
val isCreateEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }
```

These avoid recomputing the trim / blank-check on every recomposition that doesn't change the field value.

### Layout structure

The composable body is a single `AlertDialog`:

- `onDismissRequest = onDismiss` — handles outside-tap and back-press.
- `title = { Text("Create workspace", style = MaterialTheme.typography.headlineSmall) }` — explicit style override because M3's default `AlertDialog` title style is `headlineSmall` already; pass it explicitly so the visual contract is documented in the source. Wraps to multiple lines if width-constrained (M3 default behavior).
- `text = { ... OutlinedTextField ... }` — see § Input field below.
- `confirmButton = { TextButton(onClick = { onCreate(trimmedName) }, enabled = isCreateEnabled) { Text("Create") } }`.
- `dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }`.

M3's `AlertDialog` slot ordering renders `dismissButton` to the left of `confirmButton` (Cancel then Create), matching Figma `19:49`. No `properties` override needed (defaults — `dismissOnBackPress = true`, `dismissOnClickOutside = true` — match AC #2).

### Input field

`OutlinedTextField` inside the `text` slot:

```kotlin
OutlinedTextField(
    value = fieldValue,
    onValueChange = { fieldValue = it },
    modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focusRequester),
    label = { Text("What should this workspace be called?") },
    singleLine = true,
    keyboardOptions = KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        imeAction = ImeAction.Done,
    ),
    keyboardActions = KeyboardActions(
        onDone = { if (isCreateEnabled) onCreate(trimmedName) },
    ),
)
```

Notes:

- **`label`, not `placeholder`.** Figma `19:46` shows the "What should this workspace be called?" string at labelMedium size positioned at the top of the outlined frame — that is the M3 `OutlinedTextField` floating-label position. The body-large region below is the input area itself, currently empty. Use the `label` slot.
- **`singleLine = true`.** Folder names don't wrap; Enter submits via `onDone`.
- **`ImeAction.Done` + `onDone` invokes `onCreate` only when enabled.** This is the natural keyboard-submit binding for a one-field form, and AC #2's "`Create` (when enabled) invokes `onCreate(trimmed)`" applies equally to keyboard-driven submit. If `onDone` fires while the field is blank, it is a no-op (no callback, no dismiss).
- **`KeyboardCapitalization.None`.** Folder paths are lowercase by convention; no auto-cap on the first letter.
- **No `isError` / error helper text.** The dialog has no validation beyond non-blank. Repo-level failures (collision, IO) are the host's concern after `onCreate` fires.
- **No `supportingText` (helper-text slot).** Figma does not show one.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex values.

| Figma slot                       | Compose slot                                   | Used by                       |
|----------------------------------|-----------------------------------------------|-------------------------------|
| `Schemes/surface-container-high` | `colorScheme.surfaceContainerHigh` (dialog bg) | `AlertDialog` default         |
| `Schemes/on-surface`             | `colorScheme.onSurface`                       | Title, input text             |
| `Schemes/on-surface-variant`     | `colorScheme.onSurfaceVariant`                | Floating label                |
| `Schemes/outline`                | `colorScheme.outline`                         | `OutlinedTextField` border    |
| `Schemes/primary`                | `colorScheme.primary`                         | Cancel + Create button labels |

Typography:

| Figma style          | Compose style                  | Used by                                |
|----------------------|-------------------------------|----------------------------------------|
| `Static/Headline Small` | `typography.headlineSmall` | "Create workspace" title               |
| `Static/Label Medium`   | `typography.labelMedium`   | Floating label (M3 default for `OutlinedTextField.label`) |
| `Static/Body Large`     | `typography.bodyLarge`     | Input value text (M3 default)          |
| `Static/Label Large`    | `typography.labelLarge`    | "Cancel" / "Create" button labels (M3 default for `TextButton`) |

The M3 `AlertDialog` and `OutlinedTextField` defaults already wire all four typography slots correctly via `MaterialTheme.typography`. The only style the spec asks the developer to set explicitly is `headlineSmall` on the title `Text` (everything else is the M3 default).

### `@Preview`s

Two previews following the `WorkspacePickerSheet` convention — Light + Dark — both `widthDp = 412`. Preview the dialog body directly (not a `Box` with `AlertDialog` floating inside it — `AlertDialog`'s scrim + window-machinery do render in the IDE preview for `AlertDialog`, unlike `ModalBottomSheet`; preview the composable as-called).

Per AC #3, the previews must show **enabled Create (non-empty name)** and **disabled Create (empty name)**. Two ways to satisfy this:

**Recommended:** Two distinct preview functions, each calling `CreateFolderDialog` directly and demonstrating the relevant state. To show the enabled-Create state at preview time, the dialog's internal field must contain a value at first composition — which the empty-init contract above doesn't support. Solution: introduce a `private` testing-seam composable that takes the initial `TextFieldValue` as a param, and have the public `CreateFolderDialog` delegate to it with an empty initial value. The previews call the seam directly with the desired initial values.

```kotlin
@Composable
internal fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CreateFolderDialogInternal(
        initialValue = TextFieldValue(text = "", selection = TextRange.Zero),
        onCreate = onCreate,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}

@Composable
private fun CreateFolderDialogInternal(
    initialValue: TextFieldValue,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) { /* the full body — AlertDialog, fieldValue seeded from initialValue, etc. */ }
```

Previews:

- `CreateFolderDialogEmptyPreview` (Light) — invokes `CreateFolderDialogInternal` with `initialValue = TextFieldValue("")`. Create is disabled.
- `CreateFolderDialogFilledPreview` (Light) — invokes `CreateFolderDialogInternal` with `initialValue = TextFieldValue("my-new-workspace")`. Create is enabled.
- `CreateFolderDialogDarkPreview` (Dark, `uiMode = Configuration.UI_MODE_NIGHT_YES`) — same as Filled but dark.

Three previews total: empty-light, filled-light, filled-dark. AC #3 explicitly mentions "two preview functions" as a minimum (or one parameterized via `PreviewParameter`) — three is fine and demonstrates both states + dark mode. If the developer prefers the `@PreviewParameter` route to collapse to one preview function, that is also acceptable; the AC accepts either shape.

The `CreateFolderDialogInternal` testing seam is `private` to the file — it is not part of the public contract. Its only purpose is to make the previews demonstrate both AC states without exposing an `initialValue` parameter on the public `CreateFolderDialog`.

## State + concurrency model

- **`viewModelScope`:** none. No ViewModel.
- **`StateFlow`:** none. The dialog's `fieldValue` is `mutableStateOf<TextFieldValue>` — Compose-native, hot state, scoped to the composition.
- **`LaunchedEffect(Unit) { focusRequester.requestFocus() }`** runs once per dialog instance to pull keyboard focus to the input when the dialog opens. No dispatcher choice (Compose's `LaunchedEffect` runs on the main thread by default; focus work is main-thread-only).
- **No `DisposableEffect` for cleanup.** The dialog tears down when the host stops composing it (i.e., when the host sets its "showDialog" flag to false). Compose handles state disposal.

## Error handling

None at this layer. The dialog has one validation rule (trimmed name non-blank), enforced by the Create button's `enabled` state. There is no IO, no network, no parsing.

Repo-level failures (`createWorkspaceFolder(name)` from #210 — e.g., name collision, filesystem error) surface in the host (#207) AFTER `onCreate` fires. The host either re-renders the dialog with an error banner (a future enhancement) or shows a snackbar; either way, that is not this ticket's concern.

## Testing strategy

- **Unit tests:** none required by AC. `./gradlew test` covers no logic here — the trim / blank-check is a single expression and the AlertDialog wiring is Compose's responsibility.
- **Compose tests:** not required by this ticket's AC. When the host (#207) lands, the integrated path will be exercised there. If a developer wants defensive compose-tests for this composable in isolation, the natural shapes are:
  - `create_button_disabled_when_input_blank`
  - `create_button_enabled_when_input_has_non_blank_text`
  - `tapping_create_invokes_onCreate_with_trimmed_text`
  - `tapping_cancel_invokes_onDismiss_without_data`
  - `outside_tap_invokes_onDismiss` (via `Espresso.pressBack()` or equivalent)
  - `pressing_keyboard_done_invokes_onCreate_when_enabled`
  - `pressing_keyboard_done_is_no_op_when_blank`
  Each test wraps `CreateFolderDialog` in `setContent { PyrycodeMobileTheme { ... } }`, uses `onNodeWithText` for the label / button lookups, and asserts callback invocation via a `var invoked: String? = null` capture pattern (matches existing test conventions in this repo — no MockK, no Turbine).
- **Preview verification:** the developer renders all three previews in the IDE preview pane and verifies visually against Figma `19:44` before opening the PR. The Filled preview validates AC #3's "enabled Create"; the Empty preview validates AC #3's "disabled Create."

## Deviations from ticket AC

**Deviation 1 — visible title is "Create workspace," not "Create folder."** The ticket file/symbol name is `CreateFolderDialog` (consistent with the picker's "Create new folder" action row in #212). The Figma node `19:45` shows the visible title as **"Create workspace"**, not "Create folder." Figma is the locked source of truth, so the visible string is `"Create workspace"`. The file/symbol stays `CreateFolderDialog` per AC #1 — it is not visible to the user, and renaming it would mean re-wiring the host (#207) which is not in scope here. The product naming inconsistency (file `CreateFolderDialog` vs. label "Create workspace" vs. picker row "Create new folder under pyry-workspace…") is a product-level decision for the PO; for this ticket, file name follows AC, visible labels follow Figma.

**Deviation 2 — `OutlinedTextField` uses `label`, not `placeholder`.** Figma `19:46/19:47/19:48` shows the "What should this workspace be called?" string sitting above the input's value-text region inside the outlined frame. That is the M3 `OutlinedTextField` floating-label position — not a placeholder (which sits inside the value-text region and disappears on first keystroke). Use the `label` slot. The visual contract is identical to what Figma shows when the field is empty (label sits at the top of the outline), and matches M3's standard rendering when the field has a value (label floats to the outline border).

**Deviation 3 — keyboard `ImeAction.Done` submit added.** AC #2 lists "tapping Create (when enabled) invokes `onCreate(trimmed)`," "Cancel invokes `onDismiss`," "outside-tap invokes `onDismiss`," and "back-press invokes `onDismiss`." It does NOT mention the keyboard's "Done" key. The spec adds `ImeAction.Done` + `onDone = { if (isCreateEnabled) onCreate(trimmedName) }` because:

1. A one-field form without keyboard submit is a UX regression compared to the rest of the M3 ecosystem (`OutlinedTextField` defaults `ImeAction.Default` to the system's "Next" or "Done" depending on context).
2. The behavior is identical to AC #2's "tapping Create (when enabled) invokes `onCreate(trimmed)`" — just a different input modality.
3. No-op-when-blank means there is no user-visible state change when the validation rule isn't met; this matches the visible-disabled state of the Create button.

If the PO disagrees with this addition, removing `keyboardOptions` and `keyboardActions` from the call site is a one-line revert.

## Open questions

None. The product-level "folder vs. workspace" naming inconsistency (Deviation 1) is the only ambiguity, and the resolution (file name follows AC, visible label follows Figma) is documented above. If the developer encounters anything else ambiguous during implementation, the answer is: render exactly what Figma `19:44` shows; do not invent visual elements.

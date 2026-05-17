# CreateFolderDialog

Stateless M3 `AlertDialog` (#213) that prompts the user to name a new workspace folder. Renders Figma `19:44`: a `"Create workspace"` headline, a single outlined text field with the floating label `"What should this workspace be called?"`, and `Cancel` / `Create` text buttons in the trailing action row. The input's `TextFieldValue` is **local to the dialog** (not hoisted) because no other consumer needs to read it before submit; the dialog emits the trimmed name via `onCreate` on submit and routes Cancel / outside-tap / back-press through `onDismiss`.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `CreateFolderDialog.kt`. Sibling to [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) — the picker's `onCreateNew` callback is what causes the host (#207, open) to mount this dialog.

## Shape

```kotlin
@Composable
internal fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`internal`** — consumed only within the module; same posture as `ThemePickerDialog` in `ui/settings/` and as [`WorkspacePickerSheet`](./workspace-picker-sheet.md).
- **No `initialName` parameter.** The dialog always opens with an empty input. The "pre-select the full text on first composition" UX from the ticket Technical Notes is realised internally via `TextFieldValue(text = "", selection = TextRange.Zero)` + `FocusRequester`; adding a public `initialName` would be speculation, since #207 has no use case for one.
- **Both callbacks are required.** No nullable callbacks.
- **`onCreate` receives the *trimmed* name.** The dialog trims `fieldValue.text` before invoking; the host never sees leading / trailing whitespace.

A file-private peer `CreateFolderDialogInternal(initialValue, onCreate, onDismiss, modifier)` carries the body. The public `CreateFolderDialog` delegates to it with an empty `TextFieldValue`; the previews call the seam directly with the desired initial values to satisfy AC #3's "enabled Create (non-empty name)" + "disabled Create (empty name)" requirement without exposing an `initialValue` parameter on the public contract. The seam is `private` (not `internal`) — not part of the public API; the Compose UI tests target the public `CreateFolderDialog` and type into the field instead.

## What it does

Single `AlertDialog` with four slots:

1. **`onDismissRequest = onDismiss`** — handles outside-tap and back-press (M3 defaults `dismissOnBackPress = true` and `dismissOnClickOutside = true` match the AC; no `properties` override needed).
2. **`title`** — `Text("Create workspace", style = typography.headlineSmall)`. The style is passed explicitly to document the visual contract in source even though M3's `AlertDialog` title slot defaults to `headlineSmall`.
3. **`text`** — a single `OutlinedTextField`:
   ```kotlin
   OutlinedTextField(
       value = fieldValue,
       onValueChange = { fieldValue = it },
       modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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
4. **`confirmButton`** — `TextButton(onClick = { onCreate(trimmedName) }, enabled = isCreateEnabled) { Text("Create") }`.
5. **`dismissButton`** — `TextButton(onClick = onDismiss) { Text("Cancel") }`.

M3's `AlertDialog` slot ordering renders `dismissButton` to the left of `confirmButton` (Cancel then Create), matching Figma `19:49`. No `properties` override is needed — the defaults match the AC contract verbatim.

### Internal state

```kotlin
val focusRequester = remember { FocusRequester() }
var fieldValue by remember { mutableStateOf(initialValue) }
val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
val isCreateEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }

LaunchedEffect(Unit) { focusRequester.requestFocus() }
```

- **`fieldValue: TextFieldValue` (not `String`)** — required so the seam can pre-select text via `TextRange(0, name.length)`; preserving this representation also makes a future public `initialName` parameter cheap (`TextRange(0, initialName.length)` on first composition).
- **`derivedStateOf` for trim / enabled** — avoids recomputing the trim + blank-check on recompositions that don't change the field value.
- **`LaunchedEffect(Unit)` for focus** — fires once per dialog instance; Compose's `LaunchedEffect` runs on the main thread by default, which is where focus work must happen.
- **No `DisposableEffect`.** The dialog tears down when the host stops composing it (sets its `showDialog` flag to `false`); Compose handles state disposal.

### Submit paths

Both the `Create` button tap and the keyboard `Done` action route through the same `onCreate(trimmedName)` invocation, gated on `isCreateEnabled`:

- **`Create` button** — `enabled = isCreateEnabled` produces the visible disabled state; tap is unreachable when blank.
- **`ImeAction.Done` / `onDone`** — guarded by `if (isCreateEnabled)`; firing while blank is a no-op (no callback, no dismiss). This is a deliberate addition beyond AC #2 (Deviation 3 in the spec) — a one-field form without a keyboard-submit binding is a UX regression vs. M3 conventions, and the behaviour is identical to the Create button tap.
- **Cancel / outside-tap / back-press** — all route to `onDismiss` with no data emission.

### Color & typography mapping

All references resolve through `MaterialTheme.colorScheme.*` and `MaterialTheme.typography.*` — no hex, no custom text styles. Figma `Schemes/*` slots map 1:1:

| Figma slot | Compose slot | Used by |
|---|---|---|
| `Schemes/surface-container-high` | `colorScheme.surfaceContainerHigh` | `AlertDialog` background (M3 default) |
| `Schemes/on-surface` | `colorScheme.onSurface` | Title, input value text |
| `Schemes/on-surface-variant` | `colorScheme.onSurfaceVariant` | Floating label |
| `Schemes/outline` | `colorScheme.outline` | `OutlinedTextField` border |
| `Schemes/primary` | `colorScheme.primary` | `Cancel` / `Create` button labels |

| Figma style | Compose style | Used by |
|---|---|---|
| `Static/Headline Small` | `typography.headlineSmall` | `"Create workspace"` title (set explicitly on the title `Text`) |
| `Static/Label Medium` | `typography.labelMedium` | Floating label (M3 default for `OutlinedTextField.label`) |
| `Static/Body Large` | `typography.bodyLarge` | Input value text (M3 default) |
| `Static/Label Large` | `typography.labelLarge` | `Cancel` / `Create` button labels (M3 default for `TextButton`) |

The dialog renders nothing visible beyond what these defaults already wire — only the title's `headlineSmall` is set explicitly in source. Everything else flows through M3's `MaterialTheme` resolution.

## Visible label vs. file / symbol name

The visible title is `"Create workspace"` (Figma `19:45` is the locked source of truth); the file and symbol are named `CreateFolderDialog` per AC #1. The product-level naming inconsistency — file `CreateFolderDialog` vs. visible title `"Create workspace"` vs. picker entry row `"Create new folder under pyry-workspace…"` — is recorded as Deviation 1 in the spec and is a PO-level decision to resolve. For this dialog, file follows AC, visible label follows Figma.

## Recomposition / stability

- All public params are stable: two function references and a `Modifier`. The caller is responsible for `remember`-stabilising hot callbacks (same posture as the rest of `ui/conversations/components/`).
- The dialog's local state (`fieldValue`, `focusRequester`, derived `trimmedName` / `isCreateEnabled`) is scoped to the composition; closing the dialog (host sets `showDialog = false`) tears it down and re-opening starts fresh with an empty input.
- `derivedStateOf` on the trim / enabled flags isolates the trim computation from recompositions that don't change the field value — irrelevant on this tiny surface, but the established pattern for any future `OutlinedTextField` validation.

## Configuration

- **No new dependencies.** `AlertDialog`, `OutlinedTextField`, `TextButton`, `FocusRequester`, `TextFieldValue`, `KeyboardOptions`, `KeyboardActions`, `ImeAction`, `KeyboardCapitalization`, `TextRange` all ship in the existing `androidx.compose.material3` / `androidx.compose.ui` / `androidx.compose.foundation` artifacts. No `gradle/libs.versions.toml` edit.
- **No new string resources.** All five labels (`"Create workspace"`, `"What should this workspace be called?"`, `"Cancel"`, `"Create"`, plus the field's `singleLine` / `imeAction = Done` configuration) live inline per the ticket Technical Notes. The first localisation pass migrates these alongside the rest of `ui/conversations/components/`.

## Preview

Three `@Preview`s, all `widthDp = 412` and `showBackground = true`, all targeting `CreateFolderDialogInternal` (the file-private seam) so the previews can demonstrate both AC states without exposing an `initialValue` parameter on the public composable:

- **`CreateFolderDialogEmptyPreview`** (Light) — `initialValue = TextFieldValue(text = "", selection = TextRange.Zero)`. Validates AC #3's "disabled Create" state.
- **`CreateFolderDialogFilledPreview`** (Light) — `initialValue = TextFieldValue(text = "my-new-workspace", selection = TextRange(0, "my-new-workspace".length))`. Validates AC #3's "enabled Create" state and also exercises the full-text-selection-on-first-composition path that the public composable doesn't currently invoke.
- **`CreateFolderDialogFilledDarkPreview`** (Dark, `uiMode = Configuration.UI_MODE_NIGHT_YES`) — same as Filled-Light but dark.

Unlike `ModalBottomSheet` previews ([`WorkspacePickerSheet`](./workspace-picker-sheet.md)), `AlertDialog`'s scrim and window machinery **do** render in the IDE preview pane — so the previews wrap the dialog directly in `PyrycodeMobileTheme(darkTheme = …) { CreateFolderDialogInternal(...) }` with no intermediate `Surface` wrapper. There is no `Box`-with-floating-dialog scaffolding.

## Tests

Six Compose UI tests in `app/src/androidTest/.../CreateFolderDialogTest.kt` (`createComposeRule()` + `AndroidJUnit4`, no MockK / Turbine — matches `ConversationAvatarTest.kt` and [`WorkspacePickerSheetTest.kt`](./workspace-picker-sheet.md)). All target the **public** `CreateFolderDialog` (the seam stays file-private per spec); typing happens via `performTextInput` on the `hasSetTextAction()` node:

- `title_and_label_and_buttons_render` — `"Create workspace"`, `"What should this workspace be called?"`, `"Cancel"`, `"Create"` all `assertIsDisplayed`.
- `create_button_disabled_when_input_blank` — `onNodeWithText("Create").assertIsNotEnabled()` on the default empty input.
- `create_button_enabled_after_non_blank_input` — type `"my-workspace"`, assert `Create.assertIsEnabled()`.
- `create_button_stays_disabled_when_input_is_whitespace_only` — type four spaces, assert `Create.assertIsNotEnabled()`. Locks down the **`trim()`-then-`isNotEmpty()`** contract (whitespace-only is treated as blank, not as a valid name).
- `tapping_create_invokes_onCreate_with_trimmed_text` — type `"  my-workspace  "`, tap Create, assert `created == "my-workspace"`. Locks down the trimmed-text round-trip end-to-end.
- `tapping_cancel_invokes_onDismiss_without_data` — type a name then tap Cancel, assert `dismissed == 1` AND `created` is `null` (no data emission on dismiss).

The Compose tests deviate **up** from the architect spec, which said `"not required by AC"` and described the test shape only as guidance for a defensive follow-up — shipped here because the surface is six callback / state paths and the `androidTest/` infrastructure is already in place ([`WorkspacePickerSheetTest.kt`](./workspace-picker-sheet.md) precedent). The outside-tap, back-press, and keyboard-`Done` paths are intentionally **not** covered: outside-tap and back-press are M3 component behaviour wired through `onDismissRequest`, and `KeyboardActions.onDone` requires a software-IME interaction that `createComposeRule()` doesn't model — those paths are implicitly covered by the visible-disabled-state assertions on Create.

## Edge cases / limitations

- **Whitespace-only input is treated as blank.** `trimmedName.isNotEmpty()` gates the Create button; four spaces, tabs, newlines all leave Create disabled and `onDone` a no-op. The dialog never emits a blank or whitespace-only name through `onCreate`.
- **No error / validation display.** The dialog has one validation rule (trimmed non-blank); there is no `isError`, no `supportingText`, no inline helper. Repo-level failures from `createWorkspaceFolder(name)` ([#210](../codebase/210.md)) — name collision, IO error — surface in the host (#207) *after* `onCreate` fires. The dialog has no opinion on what the host does next.
- **No automatic `KeyboardCapitalization`.** Folder paths are lowercase by convention; `KeyboardCapitalization.None` overrides the M3 default of capitalising the first letter.
- **No `selection` visible on the empty-init contract.** `TextRange.Zero` resolves to a cursor at position 0 with no selection, which is what the empty initial state should show. The pre-select-all behaviour kicks in only when the seam is invoked with a non-empty initial value (the previews exercise this path; the public composable does not).
- **`FocusRequester` requires the field to be in the composition tree before `requestFocus()` runs** — `LaunchedEffect(Unit)` defers until first composition completes, which is sufficient. No `LaunchedEffect(focusRequester)` indirection needed.
- **`AlertDialog` renders inside its own window** — outside-tap dispatches through the scrim, not through the dialog's own click handlers. The host doesn't need to wire either back-press or outside-tap separately.

## Related

- Ticket notes: [`../codebase/213.md`](../codebase/213.md)
- Spec: `docs/specs/architecture/213-createfolderdialog-stateless-composable.md`
- Parent: split from [#206](https://github.com/pyrycode/pyrycode-mobile/issues/206) (Workspace Picker bottom sheet — host + sheet + dialog bundle); itself split from #143.
- Sibling stateless composables in the same package: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212), [`ConversationRow`](./conversation-row.md), [`ConnectionBanner`](./connection-banner.md), [`ToolCallRow`](./tool-call-row.md), [`MessageBubble`](./message-bubble.md).
- Sibling stateless-dialog convention: `ThemePickerDialog` in `ui/settings/` — `internal` visibility, hoisted state, `onDismiss` callback shape. The closest in-repo `AlertDialog` reference at implementation time.
- Upstream: [`createWorkspaceFolder(name): String`](./conversation-repository.md) from [#210](../codebase/210.md) is what the host (#207) will invoke after this dialog fires `onCreate`.
- Downstream / open:
  - **Host (#207, open)** — owns dialog visibility coordination with the picker sheet, the picker → dialog handoff, and the repository binding. On `onCreate(name)` it calls `createWorkspaceFolder(name)` and feeds the returned path back through the picker's `onPick`.
  - Open: error display for repo-level failures (collision, IO). Natural extension is an `errorMessage: String? = null` parameter feeding an `isError` / `supportingText` slot on the `OutlinedTextField`; deferred until the host's error contract from [#210](../codebase/210.md) is exercised end-to-end.
  - Open: localise the five inline literals — deferred to the codebase's first `strings.xml` pass alongside [`WorkspacePickerSheet`](./workspace-picker-sheet.md)'s literals.
  - Open: resolve the file-name vs. visible-label vs. picker-row product-naming inconsistency (Deviation 1). PO-level decision.

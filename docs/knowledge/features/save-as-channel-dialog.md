# SaveAsChannelDialog

Stateless M3 `AlertDialog` ([#142](../codebase/142.md)) prompting the user to promote a discussion ([`Conversation`](data-model.md) with `isPromoted == false`) to a channel by collecting a name and a binary workspace choice. Renders Figma `19:24`: a `"Save as channel"` headline, a single `OutlinedTextField` pre-filled with the auto-suggested title `"New channel"` (full-range selected so typing immediately overwrites), two `RadioButton` rows (**Move to dedicated channel folder** — selected by default, with a mono-font caption `~/pyry-workspace/channels/<auto-slug>/`; and **Keep in scratch** — no caption), and `Cancel` / `Save` text buttons. `Save` is disabled when the trimmed input is blank; on tap it emits the trimmed name and the selected `WorkspaceChoice` via `onSubmit`. `Cancel` / outside-tap / back-press route through `onDismiss` with no emission.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `SaveAsChannelDialog.kt`. Sibling to [`RenameDialog`](rename-dialog.md) (the structural template — both lift from [`CreateFolderDialog`](create-folder-dialog.md)) and [`WorkspacePickerSheet`](workspace-picker-sheet.md). Hosted by [`ThreadScreen`](thread-screen.md); triggered exclusively today by the [`ThreadOverflowMenu`](thread-overflow-menu.md)'s discussion-only **Save as channel…** item ([#204](../codebase/204.md)).

## Shape

```kotlin
@Composable
fun SaveAsChannelDialog(
    initialName: String,
    onSubmit: (name: String, workspace: WorkspaceChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public`** (default visibility) — exported because the host (`ThreadScreen` in `ui/conversations/thread/`) is in a sibling package. Same posture as [`RenameDialog`](rename-dialog.md).
- **`initialName: String`** is required (no default) — the dialog has no meaningful "open empty" state. Phase 0 always passes the stub `"New channel"` from the VM's `AUTO_SUGGESTED_CHANNEL_NAME` constant; Phase 4 will pass a generated title derived from the first user message. The dialog is agnostic to the source.
- **`onSubmit: (name: String, workspace: WorkspaceChoice) -> Unit`** receives the *trimmed* name and the radio-selected `WorkspaceChoice` enum (not the resolved path string). The dialog is the trim authority. The host (`ThreadViewModel.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit)`) forwards `event.name` to `repository.promote` without re-trimming and translates `event.workspace` to a path via the file-scope `resolveWorkspace(name, choice): String?` helper.
- **`onDismiss`** covers Cancel, outside-tap (M3 `dismissOnClickOutside = true` default), and back-press (M3 `dismissOnBackPress = true` default) — same defaults `RenameDialog` and `CreateFolderDialog` rely on; no `DialogProperties` override.
- **`WorkspaceChoice`** is the cross-package enum (`enum class WorkspaceChoice { DEDICATED, SCRATCH }`) declared top-level in `ThreadViewModel.kt`. Imported by the dialog via `import de.pyryco.mobile.ui.conversations.thread.WorkspaceChoice`. Top-level placement (not nested inside `ThreadEvent` or `SaveAsChannelDialogState`) because two callers from different packages consume it; the one cross-package import is the price of putting the enum where it semantically belongs (a `ThreadEvent` payload type) — see [#142 lessons learned](../codebase/142.md#lessons-learned).

A file-private peer `SaveAsChannelDialogInternal(initialValue, initialWorkspace, onSubmit, onDismiss, modifier)` carries the body — the public composable always seeds `initialValue = TextFieldValue(text = initialName, selection = TextRange(0, initialName.length))` and `initialWorkspace = WorkspaceChoice.DEDICATED`. The seam exists so the three `@Preview`s can demonstrate both radio states (DEDICATED in light + dark, SCRATCH in light) without exposing the `TextFieldValue` / `initialWorkspace` plumbing on the public contract. Same shape as [`RenameDialog`](rename-dialog.md)'s public-plus-`*Internal`-seam — lifted wholesale.

## What it does

Single `AlertDialog` with four slots, structurally identical to [`RenameDialog`](rename-dialog.md) modulo the `text` slot's `Column` and the workspace-choice plumbing:

1. **`onDismissRequest = onDismiss`** — outside-tap + back-press routed through M3 defaults.
2. **`title`** — `Text(stringResource(R.string.save_as_channel_dialog_title), style = MaterialTheme.typography.headlineSmall)`. Style set explicitly to document the visual contract in source even though M3's `AlertDialog` title slot already defaults to `headlineSmall`.
3. **`text`** — a `Column(verticalArrangement = Arrangement.spacedBy(16.dp))` containing a `LaunchedEffect(Unit) { focusRequester.requestFocus() }`, the `OutlinedTextField` it targets, and a file-private `WorkspaceRadios` composable. The effect **must** sit inside this `Column`, not before the `AlertDialog(...)` call — see the internal-state note below on why (#589).
   ```kotlin
   Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
       LaunchedEffect(Unit) { focusRequester.requestFocus() }
       OutlinedTextField(
           value = fieldValue,
           onValueChange = { fieldValue = it },
           modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
           label = { Text(stringResource(R.string.save_as_channel_dialog_field_label)) },   // "Name"
           singleLine = true,
           keyboardOptions = KeyboardOptions(
               capitalization = KeyboardCapitalization.None,
               imeAction = ImeAction.Done,
           ),
           keyboardActions = KeyboardActions(
               onDone = { if (isSaveEnabled) onSubmit(trimmedName, selectedWorkspace) },
           ),
       )
       WorkspaceRadios(
           selected = selectedWorkspace,
           onSelectedChange = { selectedWorkspace = it },
       )
   }
   ```
4. **`confirmButton`** — `TextButton(onClick = { onSubmit(trimmedName, selectedWorkspace) }, enabled = isSaveEnabled) { Text(stringResource(R.string.save_as_channel_dialog_save)) }`.
5. **`dismissButton`** — `TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_as_channel_dialog_cancel)) }`.

M3's `AlertDialog` slot ordering renders `dismissButton` to the left of `confirmButton` (Cancel then Save), matching the Figma reference.

### `WorkspaceRadios` (file-private)

```kotlin
@Composable
private fun WorkspaceRadios(
    selected: WorkspaceChoice,
    onSelectedChange: (WorkspaceChoice) -> Unit,
    modifier: Modifier = Modifier,
)
```

`Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp))` containing two `Row`s:

- **DEDICATED row.** `Modifier.selectable(selected = selected == WorkspaceChoice.DEDICATED, onClick = { onSelectedChange(WorkspaceChoice.DEDICATED) }, role = Role.RadioButton).padding(horizontal = 4.dp, vertical = 8.dp)`. `RadioButton(selected = ..., onClick = null)` (row owns the click) + a `Column(verticalArrangement = Arrangement.spacedBy(2.dp))` for the label (`Text` with `bodyLarge` / `onSurface`) and the **mono-font path caption** (`Text("~/pyry-workspace/channels/<auto-slug>/", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)`).
- **SCRATCH row.** Same `Modifier.selectable(...)` shape with `WorkspaceChoice.SCRATCH`. `RadioButton(... onClick = null)` + a single `Text` (no caption — Figma `19:24` does not show one).

`Modifier.selectable(role = Role.RadioButton)` + `RadioButton(onClick = null)` is the M3 accessibility idiom for tappable radio rows: the row (not the radio glyph) is the click target, `selectableGroup()` on the parent `Column` enables single-select-radio-group screen-reader semantics. Without `selectableGroup()`, screen readers announce two independent radios; without `onClick = null` on the `RadioButton`, semantics produce two click targets and `assertIsSelected()` would not resolve unambiguously.

**The caption is a static literal, not a string resource.** `~/pyry-workspace/channels/<auto-slug>/` is fixed example text styled like the running app's filesystem display — it matches Figma `19:35`'s Roboto Mono rendering and is semantically not localizable in Phase 0. A future translator wanting to localize the `~/` prefix or path separator can convert to a resource trivially; the Phase-0 literal is cheaper than a resource that doesn't change. **The `<auto-slug>` substring is not interpolated** — it is the literal four characters plus angle brackets, a placeholder marker the user reads as "your slug will go here" rather than a live preview of `name.toChannelSlug()`. A future enhancement could substitute the live slug; the architect spec did not require it and the current literal matches Figma verbatim.

### Internal state

```kotlin
val focusRequester = remember { FocusRequester() }
var fieldValue by remember { mutableStateOf(initialValue) }
var selectedWorkspace by remember { mutableStateOf(initialWorkspace) }
val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
val isSaveEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }
```

`LaunchedEffect(Unit) { focusRequester.requestFocus() }` is declared **inside the `text` slot's `Column`**, immediately before the `OutlinedTextField` it targets — see the slot listing above.

- **`fieldValue: TextFieldValue`** (not `String`) — preserves the `selection = TextRange(0, initialName.length)` seed so the field opens with the full name selected. Typing immediately overwrites the seeded `"New channel"`: any keystroke replaces the selected range. Same shape as [`RenameDialog`](rename-dialog.md).
- **`selectedWorkspace: WorkspaceChoice`** seeded from `initialWorkspace` (always `WorkspaceChoice.DEDICATED` from the public composable; previews route through the seam to set SCRATCH). Mutated on row tap via `onSelectedChange` → `selectedWorkspace = it`.
- **Single-gate `isSaveEnabled`** — `trimmedName.isNotEmpty()` only. **No `trimmedName != initialName` clause** (the key behavioural difference from `RenameDialog`) — the dialog has no semantic notion of "unchanged"; submitting the unchanged seeded `"New channel"` is valid (the user intentionally chose to promote with the auto-suggested title). The slug helper handles the resulting `name = "New channel"` → `slug = "new-channel"` translation server-side.
- **`derivedStateOf` for trim / enabled** — same recomposition-isolation idiom as `RenameDialog`; computes only when `fieldValue` actually changes. **No `remember(initialName)` key** on `isSaveEnabled` — the rename dialog needed it to defensively recompute against a streaming-rename mutation of `initialName`; this dialog's `isSaveEnabled` doesn't reference `initialName` at all (single-gate), so the key would do nothing.
- **`LaunchedEffect(Unit)` for focus, placed inside `text`'s `Column`** — fires once per dialog instance after the field's own sub-composition commits. `AlertDialog`'s slots compose in the dialog window's **own** sub-composition, separate from the caller; an effect declared in the parent composition (before the `AlertDialog(...)` call) runs against a `FocusRequester` node that isn't attached yet in that sub-composition. `requestFocus()` returns cleanly either way — no `IllegalStateException` — so the defect was invisible until measured (#589; fixed after `interactiveTurn_saveAsChannel_promotesToChannelTier` was found to carry the same exposure, untested live). Same shape as `RenameDialog`, now that both are fixed.

### Submit paths

Both the `Save` button tap and the keyboard `Done` action route through the same `onSubmit(trimmedName, selectedWorkspace)` invocation, gated on `isSaveEnabled`:

- **`Save` button** — `enabled = isSaveEnabled` produces the visible disabled state; the tap is unreachable when invalid.
- **`ImeAction.Done` / `onDone`** — guarded by `if (isSaveEnabled)`; firing while invalid is a no-op (no callback, no dismiss). Same defensive pattern as `RenameDialog`.
- **`Cancel` / outside-tap / back-press** — all route to `onDismiss` with no data emission.

## Strings

Six new `R.string.save_as_channel_dialog_*` keys live in `app/src/main/res/values/strings.xml` immediately after the `rename_dialog_*` family:

- `save_as_channel_dialog_title = "Save as channel"` — dialog headline. No ellipsis (the ellipsis means "opens further UI"; the menu item has it because tapping opens this dialog, but the dialog title itself is the further UI).
- `save_as_channel_dialog_field_label = "Name"` — `OutlinedTextField` label.
- `save_as_channel_dialog_workspace_dedicated = "Move to dedicated channel folder"` — DEDICATED radio row label.
- `save_as_channel_dialog_workspace_scratch = "Keep in scratch"` — SCRATCH radio row label.
- `save_as_channel_dialog_save = "Save"` — `confirmButton` label.
- `save_as_channel_dialog_cancel = "Cancel"` — `dismissButton` label.

Six (not four as the AC body estimated; see the [#142 ticket notes](../codebase/142.md#lessons-learned) on the AC count being approximate). Namespace is `save_as_channel_dialog_*` — `save_as_channel_action` ("Save as channel…") at `strings.xml:11` is the **menu-item** string (reused by `ThreadOverflowMenu` since [#204](../codebase/204.md) and by `DiscussionListScreen`'s long-press menu since [#25](../codebase/25.md)) and stays untouched. The mono-font path caption under the DEDICATED radio (`~/pyry-workspace/channels/<auto-slug>/`) stays as a static literal in the composable — see § Caption posture above.

## Recomposition / stability

- All public params are stable: a `String`, two function references, and a `Modifier`. The caller is responsible for `remember`-stabilising hot callbacks — `ThreadScreen`'s `onSubmit = { name, workspace -> onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = name, workspace = workspace)) }` and `onDismiss = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) }` lambdas are re-allocated per recomposition, which is fine for a dialog that mounts ephemerally per `state.saveAsChannelDialog != null` window.
- Local state (`fieldValue`, `selectedWorkspace`, `focusRequester`, derived `trimmedName` / `isSaveEnabled`) is scoped to the composition; closing the dialog (host sets `saveAsChannelDialog = null`) tears it down and re-opening starts fresh with the seeded name and `WorkspaceChoice.DEDICATED`. **Not `rememberSaveable`** — pending edits should not survive rotation while the dialog is open. AC-acceptable; matches `RenameDialog` posture.

## Configuration

- **No new dependencies.** Same Compose Material 3 / UI / foundation surface area as `RenameDialog` plus the `foundation.selection.selectable` / `foundation.selection.selectableGroup` modifiers (already on the classpath via the existing `composeBom`).
- **No Koin changes.** The dialog is pure UI; the repository call lives on `ThreadViewModel`.
- **Six new string resources** (above). The dialog uses `stringResource(...)` for every visible label except the mono-font path caption — one inline literal, deliberately kept per the localization rationale.

## Preview

Three `@Preview`s in the file, all `widthDp = 412` and `showBackground = true`, all targeting the file-private `SaveAsChannelDialogInternal` seam (which exposes `initialWorkspace: WorkspaceChoice` so each preview can flip the radio's initial state without runtime input):

- **`SaveAsChannelDialogDefaultLightPreview`** — `initialValue = TextFieldValue("New channel", TextRange(0, "New channel".length))`, `initialWorkspace = WorkspaceChoice.DEDICATED`. Default state; DEDICATED radio selected; Save enabled.
- **`SaveAsChannelDialogScratchLightPreview`** — same `initialValue`, `initialWorkspace = WorkspaceChoice.SCRATCH`. Demonstrates the SCRATCH-selected variant (visible diff: DEDICATED radio glyph empty, SCRATCH glyph filled; the mono-font caption under DEDICATED still renders because the caption is row-scoped, not selection-gated). Validates AC #5(e) "preview shows the dialog with each radio state" via the second preview variant.
- **`SaveAsChannelDialogDefaultDarkPreview`** — same as `Default Light` but wrapped in `PyrycodeMobileTheme(darkTheme = true)` with `uiMode = Configuration.UI_MODE_NIGHT_YES`. DEDICATED, dark theme.

**No "edited" preview** (a la [`RenameDialog`](rename-dialog.md)'s `RenameDialogEditedLightPreview`) — there's no "Save disabled when unchanged" state to visualize; the single-gate `isSaveEnabled` only cares about trimmed-non-blank, and the seeded `"New channel"` is already non-blank so Save is enabled by default. Three previews cover the two radio states across light/dark — enough for visual sanity without overcrowding the preview pane.

Previews wrap `SaveAsChannelDialogInternal` directly inside `PyrycodeMobileTheme(darkTheme = …) { ... }` — same posture as `RenameDialog` (no intermediate `Surface` wrapper because `AlertDialog`'s window machinery renders inside the IDE preview pane).

## Tests

Two test files: 11 Compose UI tests for the dialog composable, four unit tests on the VM dispatcher.

### `SaveAsChannelDialogTest.kt` (Compose, `./gradlew connectedAndroidTest`)

`app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialogTest.kt`. `createComposeRule()` + `PyrycodeMobileTheme` wrapper, matching the [`RenameDialogTest`](rename-dialog.md#renamedialogtestkt-compose-gradlew-connectedandroidtest) precedent. 12 tests:

- `field_reports_focus_once_dialog_composes` (#589) — `hasSetTextAction() and isFocused()` (the exact predicate `InteractiveStreamE2ETest` waits on before typing into this dialog) resolves to exactly one node within 2 s. The uniqueness half carries extra weight here: the `text` slot also holds the `WorkspaceRadios` group, so this is the one of the three dialogs where a sibling node could plausibly match. See [`../codebase/589.md`](../codebase/589.md).
- `title_field_label_radios_and_buttons_render` — `"Save as channel"`, `"Name"`, `"Move to dedicated channel folder"`, `"Keep in scratch"`, `"Cancel"`, `"Save"` all `assertIsDisplayed`. The combined "renders the right widgets" sanity check.
- `field_prefills_with_initial_name` — `onNode(hasSetTextAction() and hasText("New channel")).assertIsDisplayed()`. Disambiguates the field node from the title `Text` and from the radio labels via `hasSetTextAction()`.
- `dedicated_radio_selected_by_default` — `onNodeWithText("Move to dedicated channel folder").assertIsSelected()`; `onNodeWithText("Keep in scratch").assertIsNotSelected()`. Locks AC #1 "selected by default."
- `save_enabled_with_seeded_name` — `onNodeWithText("Save").assertIsEnabled()`. Default-state Save-enabled check (the seeded `"New channel"` passes the single-gate non-blank check).
- `save_disabled_when_input_cleared` — `performTextReplacement("")` on the field, assert Save disabled. AC #2 "disabled on blank input."
- `save_disabled_on_whitespace_only_input` — `performTextReplacement("   ")`, assert Save disabled. Locks the `trim()`-then-`isNotEmpty()` half of the gate.
- `save_enabled_with_non_blank_trimmed_input` — `performTextReplacement("  alpha  ")`, assert Save enabled. The disabled→enabled transition.
- `selecting_scratch_radio_updates_selection` — click `onNodeWithText("Keep in scratch")` (the row, via the `Modifier.selectable` semantic on the row); assert SCRATCH `assertIsSelected()` AND DEDICATED `assertIsNotSelected()`. Locks the row-as-touch-target idiom AND the mutual-exclusion semantics of `selectableGroup()`.
- `tapping_save_with_dedicated_invokes_onSubmit_with_trimmed_name_and_dedicated_choice` — `performTextReplacement("  alpha  ")`, click "Save", assert `submitted == "alpha" to WorkspaceChoice.DEDICATED`. Locks the trimmed-text + workspace-choice round-trip end-to-end.
- `tapping_save_with_scratch_invokes_onSubmit_with_scratch_choice` — `performTextReplacement("alpha")`, click "Keep in scratch", click "Save"; assert `submitted == "alpha" to WorkspaceChoice.SCRATCH`. Verifies the SCRATCH path emits the right enum.
- `tapping_cancel_invokes_onDismiss_without_invoking_onSubmit` — click "Cancel"; assert `dismissed == 1` AND `assertNull(submitted)`. Same shape as `RenameDialogTest`.

Tests use **`performTextReplacement(...)` (not `performTextInput(...)`)** because the field opens with `"New channel"` already populated and `performTextInput` would *append* rather than replace — same gotcha [`RenameDialog`](rename-dialog.md)'s lessons learned documented at #141 and carried into this slice unchanged.

**Outside-tap and back-press are intentionally not covered** — same structural unreachability as `RenameDialogTest` and `CreateFolderDialogTest` (no `Activity`-owned scrim window, no hardware back dispatch in `createComposeRule()`). AC #2 mentions "outside-tap and back-press dismiss" but the wiring is provable by code inspection (`onDismissRequest = onDismiss`) and the Cancel-button test covers the `onDismiss` callback shape.

### `ThreadViewModelTest.kt` additions (unit, `./gradlew test`)

Four new `@Test` methods plus an extension to the private `RecordingRepo` test double. All re-use the established `TestScope.makeVm(handle, repository, ...)` receiver helper and the `launch { vm.state.collect {} }` + `advanceUntilIdle()` collector dance:

- `onOverflowEvent_saveAsChannel_setsDialogStateWithSeededName` — emit `ThreadEvent.SaveAsChannel`, assert `state.value.saveAsChannelDialog == SaveAsChannelDialogState(initialName = "New channel")` AND `repo.promoteCalls.isEmpty()`. AC #5(a) precondition + AC #5(b/c/d) negative.
- `onOverflowEvent_saveAsChannelSubmit_dedicated_callsPromoteWithSlugPath` — emit `SaveAsChannel` (to set the dialog state), then `SaveAsChannelSubmit(name = "Investment Strategy Review", workspace = WorkspaceChoice.DEDICATED)`. Assert `state.value.saveAsChannelDialog == null` AND `repo.promoteCalls == listOf(Triple("seed-channel-personal", "Investment Strategy Review", "pyry-workspace/channels/investment-strategy-review"))`. AC #5(b) — exactly-one-call with the slug-derived path AND flag clear.
- `onOverflowEvent_saveAsChannelSubmit_scratch_callsPromoteWithNullWorkspace` — emit `SaveAsChannel`, then `SaveAsChannelSubmit(name = "kitchenclaw refactor", workspace = WorkspaceChoice.SCRATCH)`. Assert `saveAsChannelDialog == null` AND `promoteCalls == listOf(Triple("seed-channel-personal", "kitchenclaw refactor", null))`. AC #5(c) — exactly-one-call with `null` workspace.
- `onOverflowEvent_saveAsChannelDismiss_clearsDialogWithoutPromote` — emit `SaveAsChannel`, assert dialog state set; then emit `SaveAsChannelDismiss`. Assert `saveAsChannelDialog == null` AND `promoteCalls.isEmpty()`. AC #5(d) — dismiss clears without side effect.

`RecordingRepo` gains a `val promoteCalls = mutableListOf<Triple<String, String, String?>>()` field and a real `promote(...)` body that records the triple and returns a minimal `Conversation(id, name, cwd = workspace ?: "", isPromoted = true, ...)` — the return value isn't read by the tests, they assert on `promoteCalls` directly. The previous `TODO("not used")` body on `promote` (pre-#142) was the placeholder for exactly this slice. The existing `onOverflowEvent_otherCases_doNotCallArchive` test iteration list **was not extended** to include `SaveAsChannel` — it was never in that loop (the [#204](../codebase/204.md) `SaveAsChannel` `Unit` arm didn't need that coverage; the case trivially satisfied `archiveCalls.isEmpty()`); see [`thread-overflow-menu.md`](thread-overflow-menu-wiring-tests-and-edge-cases.md#tests) on the not-extended rationale. With `SaveAsChannel` now mutating state, the test iteration list still doesn't reference it (the new positive coverage in the four save-as-channel tests is sufficient).

**Why `RecordingRepo`, not `FakeConversationRepository`?** Same rationale as [`RenameDialog`](rename-dialog.md) and [`ThreadOverflowMenu`](thread-overflow-menu.md)'s Archive tests — direct call-shape assertion (`promoteCalls == listOf(Triple(id, name, workspace))`) is one indirection less coupled than projecting `promote`'s effect through `observeConversations` and asserting on the resulting `Conversation.isPromoted` / `Conversation.cwd`. **The `Triple` over a dedicated `PromoteCall` data class** — the architect spec accepted either; the developer chose `Triple` to match the existing `renameCalls: MutableList<Pair<String, String>>` shape, keeping the test double's recording surface uniform.

## Edge cases / limitations

- **Whitespace-only input is treated as blank.** `trimmedName.isNotEmpty()` gates Save; four spaces, tabs, newlines all leave Save disabled and `onDone` a no-op. The dialog never emits a blank or whitespace-only name through `onSubmit`.
- **The submitted name passes verbatim to `repository.promote`** — `SaveAsChannelSubmit.name` is the trimmed name (the dialog is the trim authority), and `ThreadViewModel.onOverflowEvent` forwards `event.name` to `repository.promote(state.value.conversationId, event.name, resolveWorkspace(event.name, event.workspace))` without re-trimming. `FakeConversationRepository.promote` rejects blank names with `IllegalArgumentException` (per [#78](../codebase/78.md)); the dialog's non-blank gate makes the throw branch unreachable in production.
- **Slug edge case: pathological names that strip to empty.** A name like `"!!!"` passes `trimmedName.isNotEmpty()` (the trimmed text is `"!!!"`, not empty) but `String.toChannelSlug()` strips it to `""` after `replace(Regex("[^a-z0-9-]"), "")`. The `ifEmpty { "channel" }` fallback in the slug helper produces `"pyry-workspace/channels/channel"` — a defensive guard against the directory-with-trailing-slash failure mode. Phase 4's remote impl will compute the canonical slug server-side; the fallback is a Phase-0 stub.
- **"Bind to existing folder" is intentionally scoped out for MVP.** The Figma reference at `19:24` shows only the two radios. A third "bind to existing folder" option is a Phase 4 concern requiring a sub-flow (workspace picker), a `WorkspaceChoice` migration from `enum` to `sealed class` with per-variant data (the bind option carries a path), and a third row + the picker plumbing. The current two-radio shape is the AC-locked MVP.
- **No error / validation display.** The dialog has one validation rule (non-blank trimmed name) and no `isError`, no `supportingText`, no inline helper. The disabled Save button is the only signal. Repository failures from `promote(id, name, workspace)` (which can throw `IllegalArgumentException` on unknown id or blank name, `IllegalStateException` on already-promoted per [#78](../codebase/78.md)) propagate to the coroutine exception handler at the VM — the throws are structurally unreachable in production (the id we pass is `state.value.conversationId`, by construction valid; the name is non-blank by the dialog's gate; the conversation is non-promoted by construction because the menu item is gated on `!isPromoted` per [#204](../codebase/204.md)).
- **The `<auto-slug>` substring in the caption is a literal placeholder, not a live preview.** A future enhancement could substitute `name.toChannelSlug()` into the caption as the user types so the path preview updates live (e.g. typing `"Investment Strategy"` would render `~/pyry-workspace/channels/investment-strategy/`); the architect spec did not require it and the current static literal matches Figma `19:35` verbatim. Live-preview would require lifting the slug helper out of `ThreadViewModel.kt` to a shared location (since the dialog can't import VM-private helpers); not done in #142.
- **No automatic `KeyboardCapitalization`.** Channel names are free-form; `KeyboardCapitalization.None` overrides the M3 default of capitalising the first letter so the user's first keystroke replaces the pre-filled (and selected) `"New channel"` without inheriting a capital. Same rationale as [`RenameDialog`](rename-dialog.md).
- **Pending edits and radio selection are lost on rotation.** `fieldValue` and `selectedWorkspace` are `remember { ... }`, not `rememberSaveable`. Acceptable per AC and matches `RenameDialog` posture.
- **`AlertDialog` renders inside its own window** — outside-tap dispatches through the scrim, not through the dialog's own click handlers. The host doesn't need to wire either back-press or outside-tap separately.

## Host wiring (`ThreadScreen` + `ThreadViewModel`)

The dialog is rendered by [`ThreadScreen`](thread-screen.md) as a fourth `Scaffold` sibling alongside [`WorkspacePicker`](workspace-picker.md), [`RenameDialog`](rename-dialog.md), and [`StatusSheet`](status-sheet.md):

```kotlin
state.saveAsChannelDialog?.let { dialogState ->
    SaveAsChannelDialog(
        initialName = dialogState.initialName,
        onSubmit = { name, workspace ->
            onOverflowEvent(
                ThreadEvent.SaveAsChannelSubmit(name = name, workspace = workspace),
            )
        },
        onDismiss = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) },
    )
}
```

`?.let { ... }` over `if (state.saveAsChannelDialog != null)` — both compile, the `let` form gives the lambda a non-null `dialogState` receiver and avoids a `!!` bang at the `initialName` read. See [#142 lessons learned](../codebase/142.md#lessons-learned) on the smart-cast limitation that makes `let` the cleaner shape.

`state.saveAsChannelDialog` is wired from a private `pendingSaveAsChannelDialog: MutableStateFlow<SaveAsChannelDialogState?>` on `ThreadViewModel`, combined into `ThreadUiState` via the new private `TransientDialogs` pre-combiner that bundles both `pendingRenameDialog` and `pendingSaveAsChannelDialog` into a single source so the outer `combine(...)` stays at the native 5-arity ceiling — see [`thread-screen.md`](thread-screen.md) for the full `combine` shape. The flag is **VM-owned**, not screen-hoisted via `rememberSaveable` — same posture as `pendingRenameDialog` and `pendingWorkspacePicker` (and the opposite of `sheetVisible` / `overflowExpanded`, which are screen-hoisted). Dialog visibility carries semantic content (the user invoked `SaveAsChannel`, the dialog represents an in-progress promote operation against `Conversation` state) so the VM is the authority.

`ThreadEvent` (in `ThreadViewModel.kt`) grew two cases for this flow alongside the pre-existing `SaveAsChannel`:

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent
    data object RenameDismiss : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
    data object SaveAsChannel : ThreadEvent                       // menu-tap trigger (#204, unchanged)
    data class SaveAsChannelSubmit(                                // dialog Save tap (#142)
        val name: String,
        val workspace: WorkspaceChoice,
    ) : ThreadEvent
    data object SaveAsChannelDismiss : ThreadEvent                 // dialog Cancel / outside-tap (#142)
}

enum class WorkspaceChoice { DEDICATED, SCRATCH }                  // #142, top-level
```

`ThreadViewModel.onOverflowEvent` handles the three save-as-channel cases:

| Event | Effect |
|---|---|
| `ThreadEvent.SaveAsChannel` | `pendingSaveAsChannelDialog.value = SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)` (replaces the [#204](../codebase/204.md) `Unit` no-op) |
| `is ThreadEvent.SaveAsChannelSubmit` | `pendingSaveAsChannelDialog.value = null`, then `viewModelScope.launch { repository.promote(state.value.conversationId, event.name, resolveWorkspace(event.name, event.workspace)) }` — flag flips **synchronously before** the launch so the dialog dismisses immediately with no flicker, and a re-tap of `SaveAsChannel` during an in-flight promote has well-defined state |
| `ThreadEvent.SaveAsChannelDismiss` | `pendingSaveAsChannelDialog.value = null` |

`SaveAsChannelSubmit.name` carries the already-trimmed name (the dialog is the trim authority); the VM forwards it without re-trimming. `SaveAsChannelSubmit.workspace` is the `WorkspaceChoice` enum (not a path string); the VM resolves it via the file-scope `resolveWorkspace(name, choice): String?` helper which returns `"pyry-workspace/channels/${name.toChannelSlug()}"` for `DEDICATED` and `null` (preserve existing cwd) for `SCRATCH`. The conversation id is sourced from `state.value.conversationId` at dispatch time, not embedded on the event — same convention as `ThreadEvent.Archive` / `ThreadEvent.RenameSubmit` / the original `sendMessage` shape. `MainActivity` needs no changes: `onOverflowEvent = vm::onOverflowEvent` was already bound by [#252](../codebase/252.md), and method-reference syntax handles the two new variants automatically.

### Slug derivation (file-scope private)

```kotlin
private fun resolveWorkspace(name: String, choice: WorkspaceChoice): String? =
    when (choice) {
        WorkspaceChoice.DEDICATED -> "pyry-workspace/channels/${name.toChannelSlug()}"
        WorkspaceChoice.SCRATCH -> null
    }

private fun String.toChannelSlug(): String =
    lowercase()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^a-z0-9-]"), "")
        .trim('-')
        .ifEmpty { "channel" }
```

Order matters: `lowercase()` first → spaces to dashes → strip non-alphanumeric-or-dash → trim leading/trailing dashes → `ifEmpty { "channel" }` fallback (defensive against a name like `"!!!"` slipping through the non-blank check). Both helpers are file-scope `private` in `ThreadViewModel.kt`, alongside the pre-existing `Conversation.displayName()` / `Conversation.workspaceLabel()` extensions. Not tested in isolation — the VM tests assert observable behaviour through `repository.promote` argument equality (`"Investment Strategy Review" → "pyry-workspace/channels/investment-strategy-review"` and `"kitchenclaw refactor" → "pyry-workspace/channels/kitchenclaw-refactor"`).

Phase 4's remote impl will compute the canonical slug server-side; this is a deliberately minimal Phase-0 stub. If a sibling Phase-0 ticket needs to derive slugs for a different surface (unlikely Phase 0; possibly Phase 4 settings UI), promote to a shared `data/model/Slug.kt` then. Don't pre-shareify.

### Auto-suggested-title stub (file-scope private)

```kotlin
private const val AUTO_SUGGESTED_CHANNEL_NAME = "New channel"
```

The auto-suggested-title generator is a Phase 4 concern (synthesize from the first user message in the conversation); the Phase-0 stub is a constant. Swap point: replace the constant with a function `fun ThreadViewModel.suggestChannelTitle(): String` reading off `state.value.items` and synthesizing from the first `MessageItem` with `role == User`. The `ThreadUiState` and dialog shapes do not change for this swap.

## Related

- Ticket notes: [`../codebase/142.md`](../codebase/142.md); auto-focus contract fix: [`../codebase/589.md`](../codebase/589.md)
- Spec: `docs/specs/architecture/142-save-as-channel-dialog.md`
- Parent: second per-item follow-up of the four `[NewSession, ChangeWorkspace, ChannelInfo, SaveAsChannel]` cases from the [`ThreadOverflowMenu`](thread-overflow-menu.md) family — after [#141](../codebase/141.md) wired `Rename`, [#142](../codebase/142.md) wires `SaveAsChannel`.
- Sibling stateless dialogs in the same package: [`RenameDialog`](rename-dialog.md) (#141 — the direct structural template), [`CreateFolderDialog`](create-folder-dialog.md) (#213 — the original template that #141 lifted and #142 lifts again transitively).
- Upstream:
  - [`ThreadOverflowMenu`](thread-overflow-menu.md) — the **Save as channel…** discussion-only item (since [#204](../codebase/204.md)) is the sole entry point for this dialog today. The menu's `onEvent` sink reaches `ThreadViewModel.onOverflowEvent`, whose `SaveAsChannel` arm sets `pendingSaveAsChannelDialog.value = SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)`.
  - [`ThreadScreen`](thread-screen.md) — the host. Renders the dialog as a fourth `Scaffold` sibling gated on `state.saveAsChannelDialog?.let { … }`.
  - [`ConversationRepository.promote(conversationId, name, workspace): Conversation`](conversation-repository.md) — the repository method invoked from the `SaveAsChannelSubmit` handler. Pre-existing since the data layer's earlier slices; this ticket is its second production caller (#78 was the first, from the discussion-list long-press menu). The repo contract: `workspace = null` preserves the existing `cwd`; a non-null string replaces `cwd`. The slug-path string this ticket sends in for the DEDICATED branch becomes the new `cwd`. No data-layer changes.
- Downstream / open:
  - Phase 4 real auto-suggested-title generator — replaces `AUTO_SUGGESTED_CHANNEL_NAME` with a synthesizer reading from `state.value.items`. Dialog and state shapes unchanged.
  - Phase 4 "Bind to existing folder" third workspace choice — extends `WorkspaceChoice` (with a `sealed class` migration since the bind option carries a path payload), adds a third radio row + a workspace-picker sub-flow, and routes through the same `resolveWorkspace` helper.
  - Phase 4 live `<auto-slug>` caption preview — substitutes `name.toChannelSlug()` into the caption as the user types. Requires lifting the slug helper to a shared location accessible from the `components/` package.
  - Phase 4 error display for repo-level failures — out of scope at #142 (the throws are structurally unreachable in Phase 0). Phase 4 network/server failures will need a snackbar / `isError` slot on the field; the dialog's public signature is stable for that addition (`errorMessage: String? = null` parameter feeding `isError` + `supportingText`).
- Figma: [`19:24`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-24).

# RenameDialog

Stateless M3 `AlertDialog` (#141) prompting the user to rename a [`Conversation`](data-model.md) (channel or discussion — the dialog is type-agnostic; the repository call is `rename(conversationId, name)` either way). Renders Figma `19:14`: a `"Rename"` headline, a single `OutlinedTextField` pre-filled with the current name (full-range selected so typing immediately overwrites), and `Cancel` / `Save` text buttons. `Save` is disabled when the trimmed input is blank or equals the initial name; on tap it emits the trimmed name via `onSubmit`. `Cancel` / outside-tap / back-press route through `onDismiss` with no emission.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `RenameDialog.kt`. Sibling to [`CreateFolderDialog`](create-folder-dialog.md) (the original structural template), [`SaveAsChannelDialog`](save-as-channel-dialog.md) (sibling pre-filled-input dialog from [#142](../codebase/142.md) that lifts the same public-plus-`*Internal`-seam shape), and [`WorkspacePickerSheet`](workspace-picker-sheet.md). Hosted by [`ThreadScreen`](thread-screen.md); triggered today by the [`ThreadOverflowMenu`](thread-overflow-menu.md) `Rename` item, reusable by a future TopAppBar conversation-name-tap entry point (the title is already `clickable` since [#139](../codebase/139.md); the tap-to-rename wiring is not yet ticketed).

## Shape

```kotlin
@Composable
fun RenameDialog(
    initialName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public`** (default visibility) — exported because the host (`ThreadScreen` in `ui/conversations/thread/`) is in a sibling package. Different from [`CreateFolderDialog`](create-folder-dialog.md)'s `internal` posture because that dialog's host (#207, still open) lives inside the same package.
- **`initialName: String`** is required (no default) — the dialog has no meaningful "open empty" state since the disable-when-unchanged check needs an anchor. Pre-empty rename is structurally unreachable (every `Conversation` either has a `name` or falls back to the `"Untitled channel" / "Untitled discussion"` sentinel rendered as `state.displayName`); passing the displayed name keeps the disabled-on-unchanged check in lockstep with what the user sees in the title bar.
- **`onSubmit` receives the *trimmed* name** — the dialog is the trim authority. The host (`ThreadViewModel.onOverflowEvent(ThreadEvent.RenameSubmit)`) forwards `event.name` to `repository.rename` without re-trimming.
- **`onDismiss`** covers Cancel, outside-tap (M3 `dismissOnClickOutside = true` default), and back-press (M3 `dismissOnBackPress = true` default) — same defaults `CreateFolderDialog` relies on; no `DialogProperties` override.

A file-private peer `RenameDialogInternal(initialName, initialValue, onSubmit, onDismiss, modifier)` carries the body — the public composable always seeds `initialValue = TextFieldValue(text = initialName, selection = TextRange(0, initialName.length))`. The seam exists so the three `@Preview`s can demonstrate both the pre-filled state (Save disabled) and an "edited" state (Save enabled) without exposing the `TextFieldValue` plumbing on the public contract. Same shape as [`CreateFolderDialog`](create-folder-dialog.md)'s public-plus-`*Internal`-seam — lifted wholesale per the architect spec.

## What it does

Single `AlertDialog` with four slots, structurally identical to [`CreateFolderDialog`](create-folder-dialog.md) modulo the validation rule and the pre-selection seed:

1. **`onDismissRequest = onDismiss`** — outside-tap + back-press routed through M3 defaults.
2. **`title`** — `Text(stringResource(R.string.rename_dialog_title), style = MaterialTheme.typography.headlineSmall)`. Style set explicitly to document the visual contract in source even though M3's `AlertDialog` title slot already defaults to `headlineSmall`.
3. **`text`** — a `LaunchedEffect(Unit) { focusRequester.requestFocus() }` immediately followed by a single `OutlinedTextField`. The effect **must** sit inside this slot, not before the `AlertDialog(...)` call — see the internal-state note below on why (#589).
   ```kotlin
   LaunchedEffect(Unit) { focusRequester.requestFocus() }
   OutlinedTextField(
       value = fieldValue,
       onValueChange = { fieldValue = it },
       modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
       label = { Text(stringResource(R.string.rename_dialog_field_label)) },   // "Name"
       singleLine = true,
       keyboardOptions = KeyboardOptions(
           capitalization = KeyboardCapitalization.None,
           imeAction = ImeAction.Done,
       ),
       keyboardActions = KeyboardActions(
           onDone = { if (isSaveEnabled) onSubmit(trimmedName) },
       ),
   )
   ```
4. **`confirmButton`** — `TextButton(onClick = { onSubmit(trimmedName) }, enabled = isSaveEnabled) { Text(stringResource(R.string.rename_dialog_save)) }`.
5. **`dismissButton`** — `TextButton(onClick = onDismiss) { Text(stringResource(R.string.rename_dialog_cancel)) }`.

M3's `AlertDialog` slot ordering renders `dismissButton` to the left of `confirmButton` (Cancel then Save), matching the Figma reference.

### Internal state

```kotlin
val focusRequester = remember { FocusRequester() }
var fieldValue by remember { mutableStateOf(initialValue) }
val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }
val isSaveEnabled by remember(initialName) {
    derivedStateOf { trimmedName.isNotEmpty() && trimmedName != initialName }
}
```

`LaunchedEffect(Unit) { focusRequester.requestFocus() }` is declared **inside the `text` slot**, immediately before the `OutlinedTextField` it targets — see the slot listing above and § Auto-focus placement below.

- **`fieldValue: TextFieldValue`** (not `String`) — preserves the `selection = TextRange(0, initialName.length)` seed so the field opens with the full name selected. AC #2's "typing immediately overwrites" comes for free: any keystroke replaces the selected range.
- **Two-part `isSaveEnabled` gate.** `trimmedName.isNotEmpty()` blocks blank and whitespace-only inputs; `trimmedName != initialName` blocks the no-op rename. This is the key behavioural difference from [`CreateFolderDialog`](create-folder-dialog.md), which gates only on `trimmedName.isNotEmpty()` (its initial value is always empty).
- **`remember(initialName)` keying on `isSaveEnabled`** — defensive. `initialName` doesn't change mid-composition in current flow (the host gates the dialog with `if (state.showRenameDialog)`, which tears it down between renames), but keying makes it a non-issue if a future caller binds it to a value that mutates while the dialog is open.
- **`derivedStateOf` for trim / enabled** — same recomposition-isolation idiom as `CreateFolderDialog`; computes only when `fieldValue` actually changes.
- **`LaunchedEffect(Unit)` for focus, placed inside `text`** — fires once per dialog instance after the field's own sub-composition commits. `AlertDialog`'s slots compose in the dialog window's **own** sub-composition, separate from the caller; an effect declared in the parent composition (before the `AlertDialog(...)` call) runs against a `FocusRequester` node that isn't attached yet in that sub-composition. `requestFocus()` returns cleanly either way — no `IllegalStateException` — so the defect was invisible until measured (#589; fixed after four LIVE `InteractiveStreamE2ETest` scenarios stalled on it). No `LaunchedEffect(focusRequester)` indirection needed; `Unit` is correct once the effect is co-located with the field.

### Submit paths

Both the `Save` button tap and the keyboard `Done` action route through the same `onSubmit(trimmedName)` invocation, gated on `isSaveEnabled`:

- **`Save` button** — `enabled = isSaveEnabled` produces the visible disabled state; the tap is unreachable when invalid.
- **`ImeAction.Done` / `onDone`** — guarded by `if (isSaveEnabled)`; firing while invalid is a no-op (no callback, no dismiss). Same defensive pattern as `CreateFolderDialog` — Deviation 3 in [#213](../codebase/213.md)'s spec, lifted here without re-deriving.
- **`Cancel` / outside-tap / back-press** — all route to `onDismiss` with no data emission.

## Strings

Four new `R.string.rename_dialog_*` keys live in `app/src/main/res/values/strings.xml` immediately after the `thread_overflow_*` family:

- `rename_dialog_title = "Rename"`
- `rename_dialog_field_label = "Name"`
- `rename_dialog_save = "Save"`
- `rename_dialog_cancel = "Cancel"`

`rename_dialog_*` namespace (not `thread_overflow_*`) so a future TopAppBar tap-to-rename entry point reuses the same strings without their key suggesting a wrong origin. Different posture from [`CreateFolderDialog`](create-folder-dialog.md), which kept its labels as inline Kotlin literals — the architect spec called out string extraction explicitly here because the dialog has more than one entry point on the roadmap (overflow menu today, TopAppBar tap eventually) and the first-localisation pass would have to migrate them anyway.

## Recomposition / stability

- All public params are stable: a `String`, two function references, and a `Modifier`. The caller is responsible for `remember`-stabilising hot callbacks — `ThreadScreen`'s `onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) }` and `onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) }` lambdas are re-allocated per recomposition, which is fine for a dialog that mounts ephemerally per `state.showRenameDialog == true` window.
- Local state (`fieldValue`, `focusRequester`, derived `trimmedName` / `isSaveEnabled`) is scoped to the composition; closing the dialog (host sets `showRenameDialog = false`) tears it down and re-opening starts fresh with `initialName` and the full-range selection. **Not `rememberSaveable`** — pending edits should not survive rotation while the dialog is open. AC-acceptable; matches `CreateFolderDialog` posture.

## Configuration

- **No new dependencies.** Same Compose Material 3 / UI / foundation surface area as `CreateFolderDialog`.
- **No Koin changes.** The dialog is pure UI; the repository call lives on `ThreadViewModel`.
- **Four new string resources** (above). The dialog uses `stringResource(...)` for every visible label — no inline literals, deliberately diverging from `CreateFolderDialog`'s posture per the namespace rationale above.

## Preview

Three `@Preview`s in the file, all `widthDp = 412` and `showBackground = true`, all targeting the file-private `RenameDialogInternal` seam:

- **`RenameDialogPrefilledLightPreview`** — `initialName = "kitchenclaw refactor"`, `initialValue = TextFieldValue("kitchenclaw refactor", TextRange(0, "kitchenclaw refactor".length))`. Pre-filled with the full text selected; Save is disabled because `trimmedName == initialName`.
- **`RenameDialogPrefilledDarkPreview`** — same as Light but wrapped in `PyrycodeMobileTheme(darkTheme = true)` and `uiMode = Configuration.UI_MODE_NIGHT_YES`.
- **`RenameDialogEditedLightPreview`** — `initialName = "kitchenclaw refactor"`, `initialValue = TextFieldValue("kitchenclaw rewrite", TextRange("kitchenclaw rewrite".length))` (caret-at-end, no selection). Save is enabled because `trimmedName != initialName`. Validates AC #3's "Save enabled" state without depending on a runtime keystroke.

Previews wrap `RenameDialogInternal` directly inside `PyrycodeMobileTheme(darkTheme = …) { ... }` — same posture as `CreateFolderDialog` (no intermediate `Surface` wrapper because `AlertDialog`'s window machinery renders inside the IDE preview pane).

## Tests

Two test files: nine Compose UI tests for the dialog composable, three unit tests on the VM dispatcher.

### `RenameDialogTest.kt` (Compose, `./gradlew connectedAndroidTest`)

`app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogTest.kt`. `createComposeRule()` + `PyrycodeMobileTheme` wrapper, matching the [`CreateFolderDialogTest`](create-folder-dialog.md) precedent. Ten tests:

- `field_reports_focus_once_dialog_composes` (#589) — `hasSetTextAction() and isFocused()` (the exact predicate `InteractiveStreamE2ETest` waits on before typing into this dialog) resolves to exactly one node within 2 s. This is the auto-focus contract itself, not just render/state coverage; see [`../codebase/589.md`](../codebase/589.md).
- `title_and_field_label_and_buttons_render` — `"Rename"`, `"Name"`, `"Cancel"`, `"Save"` all `assertIsDisplayed`.
- `field_prefills_with_initial_name` — `onNode(hasSetTextAction() and hasText("old name")).assertIsDisplayed()`. Disambiguates the field node from the title `Text` (both could match `hasText("Rename")` if there were a conversation named `"Rename"`).
- `save_disabled_on_first_composition_when_matches_initial_name` — Save `assertIsNotEnabled` on the default render. AC #3 "disabled when trimmed input equals current name."
- `save_disabled_when_input_cleared` — `performTextReplacement("")`, assert Save disabled. AC #3 "disabled on blank input."
- `save_disabled_on_whitespace_only_input` — `performTextReplacement("   ")`, assert Save disabled. Locks the `trim()`-then-`isNotEmpty()` half of the gate.
- `save_disabled_when_trimmed_input_equals_initial_name` — `initialName = "kitchenclaw"`, `performTextReplacement("  kitchenclaw  ")`, assert Save disabled. Locks the `trimmedName != initialName` half *and* the trim-before-compare ordering (typing the name with surrounding whitespace should not enable Save).
- `save_enabled_when_trimmed_input_differs_from_initial_name` — `performTextReplacement("new name")`, assert Save enabled. The disabled→enabled transition.
- `tapping_save_invokes_onSubmit_with_trimmed_text` — `performTextReplacement("  refactored  ")`, click Save, assert `submitted == "refactored"`. Locks the trimmed-text round-trip end-to-end.
- `tapping_cancel_invokes_onDismiss_without_invoking_onSubmit` — type something, click Cancel, assert `dismissed == 1` AND `submitted == null` (the negative `assertNull` does the load-bearing assertion).

Tests use **`performTextReplacement(...)` (not `performTextInput(...)`)** because the field opens with `initialName` already populated and `performTextInput` appends rather than replaces — it doesn't honour the pre-set selection. `performTextReplacement` is the correct primitive for "user types over the pre-filled value." Same gotcha will hit any future `androidTest` of a pre-seeded `OutlinedTextField`.

The outside-tap, back-press, and keyboard-`Done` paths are intentionally not covered — same structural unreachability as `CreateFolderDialogTest` (no `Activity`-owned scrim window, no hardware back dispatch, no software IME in `createComposeRule()`).

### `ThreadViewModelTest.kt` additions (unit, `./gradlew test`)

Three new `@Test` methods plus an extension to the private `RecordingRepo` test double. All re-use the established `TestScope.makeVm(handle, repository, ...)` receiver helper:

- `onOverflowEvent_rename_setsShowRenameDialogFlag` — emit `ThreadEvent.Rename`, assert `state.value.showRenameDialog == true` AND `repo.renameCalls.isEmpty()`. AC #5(b) precondition + AC #5(c) negative.
- `onOverflowEvent_renameSubmit_callsRepositoryAndClearsFlag` — emit `Rename` (to set the flag), then `RenameSubmit("new name")`. Assert `showRenameDialog == false` AND `renameCalls == listOf("seed-channel-personal" to "new name")`. AC #5(b) — exactly-one-call + flag clear.
- `onOverflowEvent_renameDismiss_clearsFlagWithoutRepositoryCall` — emit `Rename`, then `RenameDismiss`. Assert `showRenameDialog == false` AND `renameCalls.isEmpty()`. AC #5(c) — dismiss clears without side effect.

`RecordingRepo` gains a `val renameCalls = mutableListOf<Pair<String, String>>()` field and a real `rename(...)` body that records the call and returns a minimal `Conversation` — the return value isn't read by the tests, they assert on `renameCalls`. The previous `TODO("not used")` body on `rename` (`ThreadViewModelTest.kt:564` pre-#141) was the placeholder for exactly this slice. The existing `onOverflowEvent_otherCases_doNotCallArchive` test loop dropped `ThreadEvent.Rename` from its iteration list — `Rename` is no longer a no-op case.

**Why `RecordingRepo`, not `FakeConversationRepository`?** Same rationale as [`ThreadOverflowMenu`](thread-overflow-menu.md)'s Archive tests — direct call-shape assertion (`renameCalls == listOf(id to name)`) is one indirection less coupled than projecting the rename's effect through `observeConversations` and asserting on the resulting `Conversation.name`. The `flowOf(emptyList())` / `flowOf(null)` non-emitting stubs on the `observe*` methods are non-optional (they keep the VM's `combine` block from deadlocking when the test subscribes via `launch { vm.state.collect {} }`).

## Edge cases / limitations

- **Whitespace-only input is treated as blank.** `trimmedName.isNotEmpty()` gates Save; four spaces, tabs, newlines all leave Save disabled and `onDone` a no-op. The dialog never emits a blank or whitespace-only name through `onSubmit`.
- **A surrounding-whitespace match counts as unchanged.** Typing `"  current name  "` when `initialName == "current name"` leaves Save disabled because `trimmedName == initialName`. Trim-before-compare is what makes this match the user's intuition ("nothing changed visually after I trim mentally → Save stays grey").
- **No error / validation display.** The dialog has two validation rules (non-blank, changed) and no `isError`, no `supportingText`, no inline helper. The disabled Save button is the only signal. Repository failures from `rename(id, name)` (which can throw `IllegalArgumentException` on unknown id per [`FakeConversationRepository.rename`](conversation-repository.md)) propagate to the coroutine exception handler at the VM — structurally unreachable in production because the id we pass is `state.value.conversationId`, by construction valid (the screen wouldn't be open otherwise). Same posture as `ThreadOverflowMenu`'s Archive path.
- **The `initialName` parameter is the source of truth for the unchanged check.** If the host passes `state.displayName` (which today encodes the `"Untitled channel"` / `"Untitled discussion"` fallback for blank names), the user must type something other than that fallback for Save to enable — which is the intuitive behaviour (the title bar shows `"Untitled channel"`, and "saving Untitled channel" would be a no-op rename).
- **No automatic `KeyboardCapitalization`.** Conversation names are free-form; `KeyboardCapitalization.None` overrides the M3 default of capitalising the first letter so the user's first keystroke replaces the pre-filled (and selected) name without inheriting a capital. Different rationale from `CreateFolderDialog`'s same setting (folder paths are lowercase by convention) — here the reason is "do not surprise the user mid-rename."
- **Pending edits are lost on rotation.** `fieldValue` is `remember { ... }`, not `rememberSaveable`. Acceptable per AC and matches `CreateFolderDialog`; the host VM is the authority on whether the dialog re-opens (via `pendingRenameDialog.value = true`), not on whether mid-edit text survives.
- **`AlertDialog` renders inside its own window** — outside-tap dispatches through the scrim, not through the dialog's own click handlers. The host doesn't need to wire either back-press or outside-tap separately.

## Host wiring (`ThreadScreen` + `ThreadViewModel`)

The dialog is rendered by [`ThreadScreen`](thread-screen.md) as a `Scaffold` sibling alongside [`WorkspacePicker`](workspace-picker.md) and [`StatusSheet`](status-sheet.md):

```kotlin
if (state.showRenameDialog) {
    RenameDialog(
        initialName = state.displayName,
        onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) },
        onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) },
    )
}
```

`state.showRenameDialog` is wired from a private `pendingRenameDialog: MutableStateFlow<Boolean>` on `ThreadViewModel`, combined into `ThreadUiState` as the fifth source in the existing `combine(...)` block (between `pendingWorkspacePicker` and `selectedModelFlow`). The flag is **VM-owned**, not screen-hoisted via `rememberSaveable` — same posture as `pendingWorkspacePicker` (and the opposite of `sheetVisible` / `overflowExpanded`, which are screen-hoisted). The discriminator: dialog visibility carries semantic content (the user invoked `Rename`, the dialog represents an in-progress operation against `Conversation` state) so the VM is the authority; the overflow menu's expansion is pure presentation with no business meaning.

`ThreadEvent` (in `ThreadViewModel.kt`) grew two cases for this flow alongside the pre-existing `Rename`:

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent                       // menu-tap trigger (unchanged)
    data class RenameSubmit(val name: String) : ThreadEvent  // dialog Save tap
    data object RenameDismiss : ThreadEvent                  // dialog Cancel / outside-tap
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
}
```

`ThreadViewModel.onOverflowEvent` handles the three rename cases:

| Event | Effect |
|---|---|
| `ThreadEvent.Rename` | `pendingRenameDialog.value = true` (replaces the [#251](../codebase/251.md) no-op) |
| `ThreadEvent.RenameSubmit(name)` | `pendingRenameDialog.value = false`, then `viewModelScope.launch { repository.rename(state.value.conversationId, name) }` — flag flips **synchronously before** the launch so the dialog dismisses immediately with no flicker, and a re-tap of `Rename` during the in-flight rename has well-defined state |
| `ThreadEvent.RenameDismiss` | `pendingRenameDialog.value = false` |

`RenameSubmit.name` carries the already-trimmed name (the dialog is the trim authority); the VM forwards it without re-trimming. The conversation id is sourced from `state.value.conversationId` at dispatch time, not embedded on the event — same convention as `ThreadEvent.Archive` and the original `sendMessage` shape. `MainActivity` needs no changes: `onOverflowEvent = vm::onOverflowEvent` was already bound by [#252](../codebase/252.md), and method-reference syntax handles the two new variants automatically.

## Related

- Ticket notes: [`../codebase/141.md`](../codebase/141.md); auto-focus contract fix: [`../codebase/589.md`](../codebase/589.md)
- Spec: `docs/specs/architecture/141-rename-dialog-wiring.md`
- Parent: this is the per-item follow-up for `ThreadEvent.Rename` from the [`ThreadOverflowMenu`](thread-overflow-menu.md) family — first downstream slice after [#252](../codebase/252.md) mounted the menu in production.
- Sibling stateless dialogs in the same package: [`CreateFolderDialog`](create-folder-dialog.md) (#213) — the original structural template; [`SaveAsChannelDialog`](save-as-channel-dialog.md) ([#142](../codebase/142.md)) — sibling pre-filled-input dialog using the same public-plus-`*Internal`-seam shape but with a single-gate `isSaveEnabled` (no unchanged-name check), an enum-payload `onSubmit` signature, and a `Column` with embedded radio rows in the `text` slot.
- Upstream:
  - [`ThreadOverflowMenu`](thread-overflow-menu.md) — the `Rename` item that today is the sole entry point for this dialog. The menu's `onEvent` sink reaches `ThreadViewModel.onOverflowEvent`, whose `Rename` arm flips `pendingRenameDialog`.
  - [`ThreadScreen`](thread-screen.md) — the host. Renders the dialog as a `Scaffold` sibling gated on `state.showRenameDialog`.
  - [`ConversationRepository.rename(conversationId, name): Conversation`](conversation-repository.md) — the repository method invoked from the `RenameSubmit` handler. Pre-existing since the data layer's earlier slices; this ticket is its first production caller. The repo contract does not trim or validate `name`; the dialog is the only validation surface.
- Downstream / open:
  - TopAppBar conversation-name tap-to-rename — not yet ticketed. The screen's title `Text` is already `clickable` (since [#139](../codebase/139.md), wired as `onTitleClick` on `ThreadScreen` defaulted to `{}`); a future ticket binds `onTitleClick = { onOverflowEvent(ThreadEvent.Rename) }` (or equivalent) and the same dialog renders. No code changes to the dialog itself.
  - Error display for repo-level failures — out of scope at #141 (Phase 0; `FakeConversationRepository.rename` cannot fail except for unknown id, which is structurally unreachable). Phase 4 network/server failures will need a snackbar / `isError` slot on the field; the dialog's public signature is stable for that addition (most likely an `errorMessage: String? = null` parameter feeding `isError` + `supportingText`).
- Figma: [`19:14`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-14).

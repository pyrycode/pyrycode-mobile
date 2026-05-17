# 141 — Rename dialog wiring (Figma 19:14)

## Context

Ticket #252 mounted `ThreadOverflowMenu` inside `ThreadTopAppBar` and wired its `Rename` item through `ThreadViewModel.onOverflowEvent(ThreadEvent.Rename)`, where the handler is currently a `Unit` no-op. This ticket replaces the no-op with the real flow: a Material 3 `AlertDialog` (`RenameDialog`) pre-filled with the current conversation name, exposing `Save` (disabled when the input is blank or unchanged) and `Cancel`. On Save, `ConversationRepository.rename(conversationId, newName.trim())` is called and the dialog closes; on Cancel / outside-tap, the dialog closes with no repository call.

The repository method `rename(conversationId, name): Conversation` already exists on the `ConversationRepository` interface (`ConversationRepository.kt:63-66`) and is implemented by `FakeConversationRepository` (`FakeConversationRepository.kt:168-179`). The work here is UI + ViewModel wiring only — no data-layer changes.

The dialog is reusable: introduced under `ui/conversations/components/` so a future "tap conversation name in TopAppBar to rename" entry point can mount the same composable.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-14

M3 `AlertDialog` on `schemes/surface-container-high`, 28dp rounded corners, 24dp padding. Content: a `headlineSmall` title "Rename", a full-width `OutlinedTextField` (4dp corner, `schemes/outline` border, `labelMedium` floating "Name" label, `bodyLarge` text in `schemes/on-surface`) pre-filled with the current conversation name, then right-aligned `Cancel` and `Save` `TextButton`s using `schemes/primary` for the label. No dividers, no icon, no supporting text below the field. This matches the default M3 `AlertDialog` shape — the `ThreadOverflowMenu`-companion to the `Archive` confirmation pattern (no separate confirmation dialog exists; Archive is fire-and-forget — Rename is the first dialog in this surface).

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:23-46` — `ThreadEvent` sealed interface and `ThreadUiState`. You'll add two `ThreadEvent` variants and one `ThreadUiState` field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:48-152` — `ThreadViewModel`. Mirror the existing `pendingWorkspacePicker: MutableStateFlow<Boolean>` pattern (line 57) for the new `pendingRenameDialog` flag, and extend the `combine(...)` block (line 64-92) and `onOverflowEvent` `when` (line 133-145).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialog.kt:1-152` — **structural template for `RenameDialog`.** Same `AlertDialog` + `OutlinedTextField` + `FocusRequester` + `TextFieldValue` + `derivedStateOf`-driven `enabled` flag shape; differences are (i) initial value seeded from a non-empty name + full-range selection, (ii) `enabled` also requires "trimmed != initial". Lift the structure; don't re-derive the pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:51-92` — composable signature and `Scaffold` slots. The dialog renders as a sibling of `WorkspacePicker` (`ThreadScreen.kt:203-207`) and `StatusSheet` (`ThreadScreen.kt:208-217`) — outside `Scaffold`, guarded by an `if (state.showRenameDialog)` block.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-216` — `composable(Routes.CONVERSATION_THREAD)` block. Binding is `onOverflowEvent = vm::onOverflowEvent`; no changes required here because the new `ThreadEvent` variants funnel through the same callback (method reference handles them automatically).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:497-530, 541-583` — existing `onOverflowEvent_archive_*` and `onOverflowEvent_otherCases_*` tests + the `RecordingRepo` test fake. Extend `RecordingRepo` with `renameCalls: MutableList<Pair<String, String>>` and add three new tests mirroring the existing style.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialogTest.kt:1-115` — instrumented test template for the new `RenameDialogTest`. Same `@RunWith(AndroidJUnit4::class)` + `createComposeRule()` + `PyrycodeMobileTheme` wrapper; same `hasSetTextAction()`, `performTextInput`, `assertIsEnabled` / `assertIsNotEnabled` idioms.
- `app/src/main/res/values/strings.xml:44-48` — existing `thread_overflow_*` strings. Add four new strings for the dialog title and buttons (see § Design step 5).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:63-66` — `rename` signature. Read once to confirm it's `suspend fun rename(conversationId: String, name: String): Conversation`.

## Design

Four production files (one new, three modified) + one new instrumented test file + one modified unit test file + one strings.xml entry. No new packages, no new dependencies.

### 1. `ThreadEvent` — add two variants

Extend `sealed interface ThreadEvent` (`ThreadViewModel.kt:23-33`) with two new cases. Placement: immediately after the existing `Rename` case so the rename family stays contiguous.

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent
    data object RenameDismiss : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
}
```

`RenameSubmit.name` is the **trimmed** name as passed by the dialog; the ViewModel forwards it to the repository without re-trimming. The dialog is the trim authority — keeps the contract at the UI boundary, where blank-validation also lives.

The "overflow event" naming on the callback is a slight stretch now that the channel carries dialog events too, but renaming `onOverflowEvent` → `onEvent` would cascade across 5 files for no behavioural gain. Keep the existing name; the unified channel is the intended design per the ticket TN.

### 2. `ThreadUiState` — add `showRenameDialog`

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val showRenameDialog: Boolean = false,
    val items: List<ThreadItem> = emptyList(),
    val selectedModel: Model = Model.OPUS_4_7,
    val effort: String = "high",
    val tokenPercent: Int = 0,
)
```

Placement: next to `workspacePickerVisible` (line 41) so dialog-visibility flags cluster.

### 3. `ThreadViewModel` — wire visibility flag + handler

Add a parallel `MutableStateFlow<Boolean>` mirroring the existing `pendingWorkspacePicker` (line 57):

- New private field: `private val pendingRenameDialog = MutableStateFlow(false)`
- Add it as the fifth source in the `combine(...)` block (`ThreadViewModel.kt:64-92`); thread the value into `ThreadUiState.showRenameDialog`. `combine` overloads support up to 5 flows natively; this stays within that.
- Update the `WhileSubscribed` initial-value `ThreadUiState(...)` (line 87-91) — no change needed, the new field defaults to `false`.

Extend `onOverflowEvent` (`ThreadViewModel.kt:133-145`) to handle the three rename cases:

| Event | Effect |
|---|---|
| `ThreadEvent.Rename` | `pendingRenameDialog.value = true` (replaces the no-op) |
| `ThreadEvent.RenameSubmit(name)` | `pendingRenameDialog.value = false`, then `viewModelScope.launch { repository.rename(state.value.conversationId, name) }` |
| `ThreadEvent.RenameDismiss` | `pendingRenameDialog.value = false` |
| `ThreadEvent.NewSession`, `ChangeWorkspace`, `ChannelInfo` | remain `Unit` no-op (unchanged) |
| `ThreadEvent.Archive` | unchanged from #252 |

Order of operations for `RenameSubmit`: clear the flag **before** launching the rename coroutine so the dialog dismisses immediately (no UI flicker waiting for the suspend to complete) and so a re-tap of the overflow menu's `Rename` item during the in-flight rename has well-defined state. The `repository.rename` call does not need to be awaited by the handler — fire-and-forget is consistent with the existing `Archive` and `changeWorkspace` patterns in this VM.

Imports to add: `de.pyryco.mobile` already imports `MutableStateFlow` (line 16) — no new imports.

### 4. `RenameDialog.kt` — new composable

Path: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt`.

Lift the structure of `CreateFolderDialog.kt` wholesale (public composable → internal composable seeded with a `TextFieldValue` → `AlertDialog` body), with three substantive differences:

**Public signature:**

```kotlin
@Composable
fun RenameDialog(
    initialName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`onSubmit` receives the **trimmed** new name. The dialog handles all validation; `ThreadScreen` just relays.

**Internal composable contract** (mirrors `CreateFolderDialogInternal`):

- Seeds `var fieldValue by remember { mutableStateOf(initialValue) }` where `initialValue = TextFieldValue(text = initialName, selection = TextRange(0, initialName.length))`. Full-range selection satisfies AC #2 ("typing immediately overwrites").
- `LaunchedEffect(Unit) { focusRequester.requestFocus() }` — same as `CreateFolderDialog` line 55-57.
- `val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }`
- `val isSaveEnabled by remember(initialName) { derivedStateOf { trimmedName.isNotEmpty() && trimmedName != initialName } }` — **the key behavioural difference from `CreateFolderDialog`.** Save is disabled when the trimmed input is blank OR equals `initialName`. The `remember(initialName)` keying is defensive — `initialName` shouldn't change mid-composition under current flow, but keying makes it a non-issue if it ever does.
- `AlertDialog`:
  - `onDismissRequest = onDismiss` (outside-tap routes here)
  - `title = { Text(stringResource(R.string.rename_dialog_title), style = MaterialTheme.typography.headlineSmall) }`
  - `text = { OutlinedTextField(...) }` — `value = fieldValue`, `onValueChange = { fieldValue = it }`, `singleLine = true`, `label = { Text(stringResource(R.string.rename_dialog_field_label)) }`, `keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done)`, `keyboardActions = KeyboardActions(onDone = { if (isSaveEnabled) onSubmit(trimmedName) })`, `modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)`.
  - `confirmButton = { TextButton(onClick = { onSubmit(trimmedName) }, enabled = isSaveEnabled) { Text(stringResource(R.string.rename_dialog_save)) } }`
  - `dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.rename_dialog_cancel)) } }`

**Previews:** three `@Preview`s mirroring `CreateFolderDialog` — pre-filled (Light), pre-filled (Dark), and an "edited" variant where the field has a different value so Save is visually enabled. Use `initialName = "kitchenclaw refactor"` to match the Figma reference. Wrap in `PyrycodeMobileTheme(darkTheme = …)`.

### 5. `strings.xml` — four new strings

Add immediately after `thread_overflow_channel_info` (line 48):

```xml
<string name="rename_dialog_title">Rename</string>
<string name="rename_dialog_field_label">Name</string>
<string name="rename_dialog_save">Save</string>
<string name="rename_dialog_cancel">Cancel</string>
```

(`rename_dialog_*` namespace makes future tap-to-rename reuse semantically clean — these aren't `thread_overflow_*` anymore once they live on the dialog.)

### 6. `ThreadScreen.kt` — render dialog conditionally

Add a single block alongside the existing `WorkspacePicker` and `StatusSheet` blocks (after line 207, before line 208):

```kotlin
if (state.showRenameDialog) {
    RenameDialog(
        initialName = state.displayName,
        onSubmit = { onOverflowEvent(ThreadEvent.RenameSubmit(it)) },
        onDismiss = { onOverflowEvent(ThreadEvent.RenameDismiss) },
    )
}
```

No new parameter on `ThreadScreen`; `onOverflowEvent` already exists from #252. `state.displayName` already encodes the user-facing fallback ("Untitled channel" / "Untitled discussion" for blank names) — using it as `initialName` means the Save-disabled-on-unchanged check uses the same string the user sees in the title bar, which is the intuitive behaviour.

Imports to add: `de.pyryco.mobile.ui.conversations.components.RenameDialog`.

### 7. `MainActivity.kt` — no changes

`onOverflowEvent = vm::onOverflowEvent` (line 210) is a method reference; the new `ThreadEvent.RenameSubmit` and `ThreadEvent.RenameDismiss` variants flow through it automatically.

### 8. New instrumented test: `RenameDialogTest.kt`

Path: `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/RenameDialogTest.kt`.

Instrumented (not unit) because `AlertDialog` renders into a separate window — same constraint as `CreateFolderDialogTest`. Use `createComposeRule()` + `PyrycodeMobileTheme` wrapper, same conventions as `CreateFolderDialogTest`.

Test scenarios (the developer writes the bodies):

| Scenario | Setup | Action | Assertion |
|---|---|---|---|
| **Renders title, field label, both buttons** | `RenameDialog(initialName = "old name", onSubmit = {}, onDismiss = {})` | none | "Rename" title, "Name" field label, "Cancel" and "Save" buttons all displayed. |
| **Field pre-fills with `initialName`** | Same as above | none | Field node has `hasText("old name")`. |
| **Save disabled on first composition** (matches `initialName`) | Same | none | "Save" `assertIsNotEnabled()`. |
| **Save disabled when input cleared** | Same | Clear field via `performTextReplacement("")` | "Save" `assertIsNotEnabled()`. |
| **Save disabled on whitespace-only** | Same | `performTextReplacement("   ")` | "Save" `assertIsNotEnabled()`. |
| **Save disabled when trimmed input equals `initialName`** | `initialName = "kitchenclaw"` | `performTextReplacement("  kitchenclaw  ")` | "Save" `assertIsNotEnabled()`. |
| **Save enabled when trimmed input differs from `initialName`** | Same | `performTextReplacement("new name")` | "Save" `assertIsEnabled()`. |
| **Tap Save → invokes `onSubmit` with trimmed input** | `initialName = "old"`, capture submitted in `var submitted: String? = null` | `performTextReplacement("  refactored  ")`; click "Save" | `submitted == "refactored"`. |
| **Tap Cancel → invokes `onDismiss`, never `onSubmit`** | Capture both | Type something; click "Cancel" | `dismissed == 1`, `submitted == null`. |

Notes:
- Use `performTextReplacement(...)` (replaces full field content) rather than `performTextInput(...)` (appends). The dialog seeds the field with `initialName` and full-range selection, but `performTextInput` doesn't honour the selection — it appends. `performTextReplacement` is the correct primitive for "user types over the pre-filled value."
- Field-text assertions: use `composeTestRule.onNode(hasSetTextAction() and hasText("old name"))` or `composeTestRule.onAllNodes(hasText("old name"))[0].assertIsDisplayed()` — the dialog also renders the title; disambiguate by combining with `hasSetTextAction()`.
- For Save-disabled-on-blank: the dialog seeds with `initialName`, so the field is non-empty at first composition. The "blank" test path clears it explicitly via `performTextReplacement("")`.

### 9. Extend `ThreadViewModelTest.kt`

Add three new tests + extend `RecordingRepo` (`ThreadViewModelTest.kt:541-583`).

**`RecordingRepo` extensions:**

- Add `val renameCalls = mutableListOf<Pair<String, String>>()` field.
- Replace the existing `rename(...): Conversation = TODO("not used")` body (line 564-567) with:

  ```kotlin
  override suspend fun rename(conversationId: String, name: String): Conversation {
      renameCalls += conversationId to name
      // Return a minimal Conversation — tests don't read the return value;
      // they assert on renameCalls.
      return Conversation(id = conversationId, name = name, /* … other fields with safe defaults */)
  }
  ```

  Inspect the existing `Conversation` data class for required constructor parameters and seed with safe defaults consistent with the other test fixtures in this file. (If `RecordingRepo` doesn't currently construct `Conversation` instances, lift the construction pattern from `FakeConversationRepository.rename` at `FakeConversationRepository.kt:168-179` — but minimally, returning whatever the data class needs to satisfy the type.)

**New tests** (place after `onOverflowEvent_otherCases_doNotCallArchive` at line 530):

| Test | Setup | Action | Assertions |
|---|---|---|---|
| `onOverflowEvent_rename_setsShowRenameDialogFlag` | `RecordingRepo`, fresh VM, `state` collector launched, `advanceUntilIdle()` | `vm.onOverflowEvent(ThreadEvent.Rename)`; `advanceUntilIdle()` | `vm.state.value.showRenameDialog == true`; `repo.renameCalls.isEmpty()`. |
| `onOverflowEvent_renameSubmit_callsRepositoryAndClearsFlag` | Same; first emit `ThreadEvent.Rename` to set the flag true | `vm.onOverflowEvent(ThreadEvent.RenameSubmit("new name"))`; `advanceUntilIdle()` | `vm.state.value.showRenameDialog == false`; `repo.renameCalls == listOf("seed-channel-personal" to "new name")` (exactly once). |
| `onOverflowEvent_renameDismiss_clearsFlagWithoutRepositoryCall` | Same; emit `ThreadEvent.Rename` first | `vm.onOverflowEvent(ThreadEvent.RenameDismiss)`; `advanceUntilIdle()` | `vm.state.value.showRenameDialog == false`; `repo.renameCalls.isEmpty()`. |

Also update `onOverflowEvent_otherCases_doNotCallArchive` (`ThreadViewModelTest.kt:514-530`) to NOT include `ThreadEvent.Rename` in its loop — that case now mutates state, so it's no longer a "no-op other case." Move it out; the loop covers `NewSession`, `ChangeWorkspace`, `ChannelInfo` only. The test name still holds (it's about Archive non-invocation, not about no-op behaviour).

## State + concurrency model

- `showRenameDialog: Boolean` lives on `ThreadUiState` (driven by VM), backed by a private `MutableStateFlow<Boolean>` (`pendingRenameDialog`) combined into the state flow. Lifecycle: tied to `viewModelScope` like all VM state. The flag is **not** `rememberSaveable` — process death / config change tears down the screen and re-launches it; the dialog is intentionally transient. (`rememberSaveable` is the right choice for `overflowExpanded` in `ThreadScreen` because that's local UI state with no semantic content; for `showRenameDialog` the VM is the authority so the dialog re-opens via `ThreadEvent.Rename`, not via saved state.)
- The dialog's internal field text (`fieldValue: TextFieldValue`) lives in the composable via `remember { mutableStateOf(...) }` — local to the dialog instance, lost on dismissal. **Not `rememberSaveable`** — pending edits should not survive rotation while the dialog is open. Acceptable per AC; matches `CreateFolderDialog` pattern.
- One `viewModelScope.launch` per `RenameSubmit`, fire-and-forget, dispatched onto the VM's default dispatcher. The `FakeConversationRepository.rename` is non-blocking (just `state.update`); when the Phase 4 remote impl lands, it'll be a network call but still fire-and-forget here.
- Concurrent rename submissions: theoretically possible (user double-taps Save before the dialog closes). The dialog's `onSubmit` is the canonical race window. Material 3 `TextButton`'s click suppression during composition transitions is sufficient for the typical case; not adding deduplication. If we later see duplicate `rename` calls in telemetry, a `MutableStateFlow<RenameInFlight>` guard on the VM is the fix — out of scope here.

## Error handling

- `repository.rename` may throw `IllegalArgumentException` if the conversation id is unknown (per `FakeConversationRepository.rename` at line 169, via `unknown(conversationId)`). The VM's `viewModelScope.launch { repository.rename(...) }` does not catch — exceptions propagate to the coroutine exception handler. Same behaviour as the existing `Archive` path. No user-visible error surface; the conversation id we pass is the active screen's `state.value.conversationId` which is by construction valid (or the screen wouldn't be open). The throw is a programming-error signal, not a runtime UX surface. Out of scope to surface.
- Dialog input validation is dual-layered: Save is disabled when invalid (UI prevention), and the dialog also short-circuits in `KeyboardActions.onDone` (`if (isSaveEnabled) onSubmit(...)`). The repository contract documents `name` is **not** trimmed or validated by the repo (`ConversationRepository.kt:79-82`-style docs for the sibling `sendMessage` set the convention — caller validates). Dialog is the trim/validate authority.
- Network/offline (Phase 4 concern, not this ticket): out of scope. The `FakeConversationRepository` is in-memory and cannot fail in this way.

## Testing strategy

- **Unit tests** (`./gradlew test`):
  - `ThreadViewModelTest.kt` — three new tests above + update to `onOverflowEvent_otherCases_doNotCallArchive`. Covers AC #5(b) and #5(c).
- **Instrumented tests** (`./gradlew connectedAndroidTest`):
  - `RenameDialogTest.kt` — new file, nine scenarios above. Covers AC #5(a) and the rest of the dialog surface contract.
- **Lint** (`./gradlew lint`): expected clean; one new string-extracted file, no hardcoded UI strings.
- **No changes** required to existing `ThreadOverflowMenuTest`, `ThreadScreenOverflowTest` (from #252), `CreateFolderDialogTest`, or any preview snapshot infrastructure.

## Open questions

None. The four AC points map directly to the eight design steps above. The `ThreadEvent` extension shape (RenameSubmit carrying the trimmed name + RenameDismiss data object) follows the ticket TN verbatim.

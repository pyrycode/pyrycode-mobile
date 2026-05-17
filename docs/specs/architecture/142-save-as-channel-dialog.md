# #142 — Save as Channel dialog (Figma 19:24)

## Context

#204 added the **Save as channel…** item to the discussion-side overflow menu, wired through `ThreadEvent.SaveAsChannel` and parked as a no-op in `ThreadViewModel.onOverflowEvent`. This ticket replaces that no-op with the full promotion flow: an M3 `AlertDialog` (`SaveAsChannelDialog`) collecting a channel name and a binary workspace choice, then calling `conversationRepository.promote(conversationId, name, workspace)` on submit.

The repository contract is already in place — `ConversationRepository.promote(conversationId: String, name: String, workspace: String? = null): Conversation` (`ConversationRepository.kt:34`), with `FakeConversationRepository.promote` (`FakeConversationRepository.kt:120`) treating `workspace = null` as "preserve the discussion's current `cwd`" (i.e. keep in scratch) and a non-null string as "move to this folder". The dialog is the second one mounted from the thread overflow menu (after #141's `RenameDialog`); the pattern is the same shape — `MutableStateFlow<SubState?>` for visibility, dialog owns its transient input state, VM owns the repository call.

Two things are intentionally simple in this slice:

- The **auto-suggested title generator** is a constant stub (`"New channel"`); the real Phase 4 generator (derive from first user message) is out of scope.
- **"Bind to existing folder"** as a third workspace choice is scoped out (see ticket body, Design — Conversations Model § Save as channel dialog). Only the two AC radios render.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=19-24

M3 `AlertDialog` on `schemes/surface-container-high`, 28dp rounded corners, 24dp padding. Content: a `headlineSmall` title "Save as channel", a full-width `OutlinedTextField` (4dp corner, `schemes/outline` border, `labelMedium` floating "Name" label, `bodyLarge` text in `schemes/on-surface`) pre-filled with the auto-suggested title, then two radio rows — the first ("Move to dedicated channel folder", selected by default) shows a secondary `labelSmall`/mono caption `~/pyry-workspace/channels/<auto-slug>/`; the second ("Keep in scratch") has no caption. Right-aligned `Cancel` and `Save` `TextButton`s using `schemes/primary` for the label. No dividers, no icon.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:23-41` — current `sealed interface ThreadEvent`. Add two new cases (`SaveAsChannelSubmit(name, workspace)` and `SaveAsChannelDismiss`) and a top-level `enum class WorkspaceChoice { DEDICATED, SCRATCH }`. `SaveAsChannel` stays — it's the open-dialog trigger from #204.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:43-55` — `ThreadUiState`. Add `saveAsChannelDialog: SaveAsChannelDialogState? = null` (nullable sub-state, mirroring #78's `PendingPromotion` shape). Add the data class itself near `ThreadUiState`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:66-105` — the existing `combine(...)` block (currently 5 sources). You'll add a sixth source (`pendingSaveAsChannelDialog`). Kotlin's typed `combine` overloads stop at 5; bundle the two transient-dialog flags into a nested upstream combine to stay typed. See § Design step 3.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:146-166` — `onOverflowEvent`. Replace the `ThreadEvent.SaveAsChannel` no-op (currently grouped with `NewSession, ChangeWorkspace, ChannelInfo`) with an "open dialog" branch; add submit + dismiss branches mirroring #141's `RenameSubmit` / `RenameDismiss` shape (clear flag → fire-and-forget repo call).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RenameDialog.kt:1-170` — **structural template for `SaveAsChannelDialog`.** Same `AlertDialog` + `OutlinedTextField` + `FocusRequester` + `TextFieldValue` + `derivedStateOf`-driven `enabled` flag shape. Differences are detailed in § Design step 4; lift the structure verbatim where it overlaps.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:205-216` — existing overlay block where `RenameDialog` is conditionally rendered. Add a sibling `if (state.saveAsChannelDialog != null) { SaveAsChannelDialog(...) }` block alongside it.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:497-588` — existing `onOverflowEvent_archive_*` / `onOverflowEvent_rename_*` test conventions (`Dispatchers.setMain(UnconfinedTestDispatcher())`, `makeVm`, `RecordingRepo`, `launch { vm.state.collect {} }` + `advanceUntilIdle()`). Mirror these for the five new tests.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:599-637` — `RecordingRepo`. Extend `promote` from `TODO("not used")` to a recording implementation (capture `(conversationId, name, workspace)` triples). Use the same minimal `Conversation` return shape as `rename` already does (`ThreadViewModelTest.kt:623-637`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/CreateFolderDialogTest.kt:1-115` — instrumented test template. Same `@RunWith(AndroidJUnit4::class)` + `createComposeRule()` + `PyrycodeMobileTheme` wrapper; same `hasSetTextAction()`, `performTextReplacement`, `assertIsEnabled` / `assertIsNotEnabled` idioms. Radio assertions: use `assertIsSelected()` / `assertIsNotSelected()` (semantic state for `Modifier.selectable(role = Role.RadioButton)`).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:34` — confirm `promote` signature: `suspend fun promote(conversationId: String, name: String, workspace: String? = null): Conversation`. Already correct; no interface change needed.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:120-140` — confirm `workspace = null` semantics: "preserve the conversation's existing `cwd`". A non-null string replaces `cwd`. The slug-path string this ticket sends in for the "dedicated" branch becomes the new `cwd`. No data-layer changes.
- `app/src/main/res/values/strings.xml:11,20-23,50-53` — existing dialog strings. `save_as_channel_action` (line 11, "Save as channel…") is the **menu-item** string (used by #204). Add four new dialog strings (see § Design step 6). The `save_as_channel_*` naming convention from `rename_dialog_*` (line 50-53) is the model.
- `CLAUDE.md` — MVI conventions; stateless composables, single `StateFlow<UiState>` per VM; `data/` portability for Compose Multiplatform walk-back.

## Design

Three production source files (one new, two modified) + four new strings + one unit-test file extended + one new instrumented test file. No new packages, no dependency changes, no data-layer changes.

### 1. `ThreadEvent` — two new cases + workspace-choice enum

In `ThreadViewModel.kt`, extend the existing sealed interface (placement: immediately after the existing `SaveAsChannel` case so the family stays contiguous):

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent
    data object RenameDismiss : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
    data object SaveAsChannel : ThreadEvent
    data class SaveAsChannelSubmit(           // NEW
        val name: String,
        val workspace: WorkspaceChoice,
    ) : ThreadEvent
    data object SaveAsChannelDismiss : ThreadEvent  // NEW
}

enum class WorkspaceChoice { DEDICATED, SCRATCH }   // NEW
```

`SaveAsChannelSubmit.name` is the **trimmed** name from the dialog — the dialog is the trim authority (same convention #141 established with `RenameSubmit`). The `workspace` field is the enum, not the resolved path string; the VM converts to a path. Keeping the enum at the event boundary lets the dialog stay path-agnostic and makes the test assertions read cleanly (`WorkspaceChoice.DEDICATED` vs an opaque `"pyry-workspace/channels/foo"`).

`WorkspaceChoice` is top-level in the same file. It's small, has no logic, and is consumed by exactly two places (the dialog and the VM); a separate file would be overhead.

### 2. `ThreadUiState` — nullable sub-state for the dialog

Add a nullable sub-state field next to `showRenameDialog` (which stays):

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
    val showRenameDialog: Boolean = false,
    val saveAsChannelDialog: SaveAsChannelDialogState? = null,   // NEW
    val items: List<ThreadItem> = emptyList(),
    val selectedModel: Model = Model.OPUS_4_7,
    val effort: String = "high",
    val tokenPercent: Int = 0,
)

data class SaveAsChannelDialogState(
    val initialName: String,
)
```

Why nullable sub-state instead of a `Boolean` + sibling field (the #141 shape)?

The dialog has **one** initial-name-derived input (the seeded pre-fill), and that value is set at open time, not at render time. Bundling `initialName` into the same nullable carrier as the visibility flag means "visible iff non-null", colocates the seed value with the visibility, and matches the #78 `PendingPromotion` pattern explicitly called out in the ticket TN as the established shape. #141 used a `Boolean` because its initial name comes from `state.displayName` (always available, no separate seeding step needed) — different problem, different shape.

If Phase 4's real auto-suggested-title generator is later added, it'll plug in by changing the value passed to `SaveAsChannelDialogState(initialName = ...)` in the VM handler; the `ThreadUiState` shape stays.

### 3. `ThreadViewModel` — sixth combine source, handler updates

Add a private `MutableStateFlow` mirroring the existing dialog flags (`ThreadViewModel.kt:66-68`):

```kotlin
private val pendingSaveAsChannelDialog = MutableStateFlow<SaveAsChannelDialogState?>(null)
```

**Combine extension.** The existing `combine(...)` block uses the 5-arg typed overload (`ThreadViewModel.kt:75-96`). Adding a sixth source would push past the typed overloads; the vararg form (`combine(vararg flows: Flow<*>) { values: Array<*> -> R }`) loses per-position typing. Instead, **bundle the two transient-dialog flags into a nested upstream combine**:

```kotlin
private data class TransientDialogs(
    val renameVisible: Boolean,
    val saveAsChannel: SaveAsChannelDialogState?,
)

private val transientDialogs: Flow<TransientDialogs> =
    combine(pendingRenameDialog, pendingSaveAsChannelDialog) { rename, save ->
        TransientDialogs(renameVisible = rename, saveAsChannel = save)
    }
```

Then the outer combine stays at 5 sources, replacing the current `pendingRenameDialog` arm with `transientDialogs`:

```kotlin
val state: StateFlow<ThreadUiState> =
    combine(
        repository.observeConversations(ConversationFilter.All),
        repository.observeMessages(conversationId),
        pendingWorkspacePicker,
        transientDialogs,
        selectedModelFlow,
    ) { conversations, items, pickerVisible, dialogs, selectedModel ->
        val conv = conversations.firstOrNull { it.id == conversationId }
        ThreadUiState(
            conversationId = conversationId,
            displayName = conv?.displayName() ?: conversationId,
            isPromoted = conv?.isPromoted ?: false,
            hasMessages = items.any { it is ThreadItem.MessageItem },
            workspaceLabel = conv?.workspaceLabel() ?: "scratch",
            workspacePickerVisible = pickerVisible,
            showRenameDialog = dialogs.renameVisible,
            saveAsChannelDialog = dialogs.saveAsChannel,
            items = items,
            selectedModel = selectedModel,
            effort = STUB_EFFORT,
            tokenPercent = STUB_TOKEN_PERCENT,
        )
    }.stateIn(/* unchanged */)
```

`TransientDialogs` is private inside `ThreadViewModel` (or top-level `internal` in the same file — developer's call). The `initialValue` of `stateIn` does not change (`ThreadUiState` defaults handle both new fields).

**Handler updates** to `onOverflowEvent` (`ThreadViewModel.kt:146-166`):

| Event | Effect |
|---|---|
| `ThreadEvent.SaveAsChannel` | `pendingSaveAsChannelDialog.value = SaveAsChannelDialogState(initialName = AUTO_SUGGESTED_CHANNEL_NAME)` — replaces the current no-op |
| `is ThreadEvent.SaveAsChannelSubmit` | `pendingSaveAsChannelDialog.value = null` (clear first), then `viewModelScope.launch { repository.promote(state.value.conversationId, event.name, resolveWorkspace(event.name, event.workspace)) }` |
| `ThreadEvent.SaveAsChannelDismiss` | `pendingSaveAsChannelDialog.value = null` |
| `ThreadEvent.NewSession`, `ChangeWorkspace`, `ChannelInfo` | remain `Unit` no-op (existing) |
| Other existing arms (Archive, Rename family) | unchanged |

**Remove `SaveAsChannel` from the existing no-op group** at `ThreadViewModel.kt:160-164` — it now opens the dialog. The remaining no-op group becomes `NewSession, ChangeWorkspace, ChannelInfo` only.

Order of operations on submit (clear-flag-before-launch) matches #141's `RenameSubmit` for the same reasons: deterministic dismiss before the suspend point; well-defined state on a double-tap of the menu item during in-flight promote.

**Slug helper** (file-scope `private`, alongside the existing `displayName()` / `workspaceLabel()` extensions at lines 175-184):

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

Order matters: lowercase → spaces to dashes → strip non-alphanumeric-or-dash → trim leading/trailing dashes → fallback to `"channel"` if empty after stripping (defensive against a name like `"!!!"` slipping through the "non-blank" check). Phase 4's remote impl will compute the canonical slug server-side; this is a deliberately minimal Phase 0 stub. **No tests for `toChannelSlug` in isolation** — its only consumer is `resolveWorkspace`, and the VM tests assert observable behaviour through `repository.promote` arguments.

**Constant** (file-scope `private const val`, near `STUB_EFFORT`):

```kotlin
private const val AUTO_SUGGESTED_CHANNEL_NAME = "New channel"
```

Phase 4 swap point: replace with a generator that reads `state.value.items` and synthesizes a title from the first user message. Keep the constant local to the VM file — it's a transient stub, not a project-wide concern.

Imports to add in `ThreadViewModel.kt`: none beyond what's already there. `combine`, `MutableStateFlow`, `Flow` are all imported.

### 4. `SaveAsChannelDialog.kt` — new composable

Path: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt`.

Lift the structure of `RenameDialog.kt` wholesale (public composable → internal composable seeded with `TextFieldValue` → `AlertDialog` body). Differences from `RenameDialog`:

1. **Submit signature carries the workspace choice** in addition to the trimmed name.
2. **`isSaveEnabled`** depends only on trimmed-name-nonblank — no "trimmed != initialName" check (unlike rename, submitting with the unchanged seeded "New channel" is valid).
3. **Radio rows** live in the `text = { … }` slot below the `OutlinedTextField`.

**Public signature:**

```kotlin
@Composable
fun SaveAsChannelDialog(
    initialName: String,
    onSubmit: (name: String, workspace: WorkspaceChoice) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

The composable receives the `WorkspaceChoice` enum import from the `thread` package (`de.pyryco.mobile.ui.conversations.thread.WorkspaceChoice`). The dialog is in `ui/conversations/components/`; the cross-package import is a single line and is the cleanest placement — the enum is conceptually a `ThreadEvent` payload type.

**Internal composable contract** (mirrors `RenameDialogInternal`, lines 51-114):

- Seeds `var fieldValue by remember { mutableStateOf(initialValue) }` where `initialValue = TextFieldValue(text = initialName, selection = TextRange(0, initialName.length))`. Full-range selection satisfies the AC's "typing immediately overwrites" requirement (mirrors #141).
- Seeds `var selectedWorkspace by remember { mutableStateOf(WorkspaceChoice.DEDICATED) }` — AC: "selected by default".
- `LaunchedEffect(Unit) { focusRequester.requestFocus() }`.
- `val trimmedName by remember { derivedStateOf { fieldValue.text.trim() } }`.
- `val isSaveEnabled by remember { derivedStateOf { trimmedName.isNotEmpty() } }`.
- `AlertDialog`:
  - `onDismissRequest = onDismiss` (outside-tap and back-press both route here).
  - `title = { Text(stringResource(R.string.save_as_channel_dialog_title), style = MaterialTheme.typography.headlineSmall) }`.
  - `text = { Column { OutlinedTextField(...); Spacer(8.dp); WorkspaceRadios(...) } }`.
  - `confirmButton = { TextButton(onClick = { onSubmit(trimmedName, selectedWorkspace) }, enabled = isSaveEnabled) { Text(stringResource(R.string.save_as_channel_dialog_save)) } }`.
  - `dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.save_as_channel_dialog_cancel)) } }`.

**`OutlinedTextField`** — identical configuration to `RenameDialog`: `singleLine = true`, `KeyboardOptions(capitalization = None, imeAction = Done)`, `KeyboardActions(onDone = { if (isSaveEnabled) onSubmit(trimmedName, selectedWorkspace) })`, `label = { Text(stringResource(R.string.save_as_channel_dialog_field_label)) }`, `.fillMaxWidth().focusRequester(focusRequester)`.

**Radio rows** — internal `WorkspaceRadios` composable (private to the file):

- Two `Row(Modifier.selectable(selected = ..., onClick = { selectedWorkspace = ... }, role = Role.RadioButton).fillMaxWidth())` rows.
- Each row: `RadioButton(selected = ..., onClick = null)` (the row handles selection via `Modifier.selectable` per the M3 accessibility convention) + a `Column` for the label and optional caption.
- **DEDICATED row** label: `R.string.save_as_channel_dialog_workspace_dedicated` ("Move to dedicated channel folder"). Caption below: literal string `"~/pyry-workspace/channels/<auto-slug>/"` styled `MaterialTheme.typography.labelSmall` in `MaterialTheme.colorScheme.onSurfaceVariant`. The caption is a **static literal**, not a string resource — it's fixed mono-font example text (matches Figma `19:35` showing `~/pyry-workspace/channels/<auto-slug>/` in Roboto Mono). Keep it as a literal in the composable; do not resource-extract.
- **SCRATCH row** label: `R.string.save_as_channel_dialog_workspace_scratch` ("Keep in scratch"). No caption.
- The radio rows form a logical `Modifier.selectableGroup()` ancestor `Column` for a11y per M3 conventions.

Don't pre-write the radio-row composable body in the spec — the developer follows the M3 `Modifier.selectable` + `RadioButton(onClick = null)` accessibility pattern that's standard for this widget. The two label strings and the static caption above are the only project-specific decisions.

**Imports to add:** `androidx.compose.foundation.selection.selectable`, `androidx.compose.foundation.selection.selectableGroup`, `androidx.compose.material3.RadioButton`, `androidx.compose.ui.semantics.Role`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.layout.Column`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.padding`, `androidx.compose.foundation.layout.fillMaxWidth`, plus the existing `RenameDialog` import set, plus `de.pyryco.mobile.ui.conversations.thread.WorkspaceChoice`.

**Previews** — three `@Preview`s mirroring `RenameDialog` (AC #5(e) requires each radio state):

1. `SaveAsChannelDialog — Default (Light)` — seeded with "New channel", DEDICATED radio selected.
2. `SaveAsChannelDialog — Scratch selected (Light)` — seeded with "New channel", SCRATCH radio selected (a preview helper variant of the internal composable that accepts the initial radio choice, mirroring `RenameDialog`'s preview pattern of routing through `RenameDialogInternal` to seed state).
3. `SaveAsChannelDialog — Default (Dark)` — DEDICATED, dark theme.

The "Edited" preview (a la #141) isn't necessary — there's no "Save disabled when unchanged" state to visualize. Two states matter: default DEDICATED, and SCRATCH. Light + dark per existing convention; combine into three previews total to keep the preview pane scannable.

The `Internal` variant should expose `initialWorkspace: WorkspaceChoice = WorkspaceChoice.DEDICATED` as a parameter (defaulting to DEDICATED so the public composable's behaviour is unchanged) — this is the lever previews use to render the SCRATCH-selected state.

### 5. `ThreadScreen.kt` — render dialog conditionally

Add a sibling block alongside the existing `RenameDialog` overlay (`ThreadScreen.kt:210-216`):

```kotlin
state.saveAsChannelDialog?.let { dialogState ->
    SaveAsChannelDialog(
        initialName = dialogState.initialName,
        onSubmit = { name, workspace ->
            onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = name, workspace = workspace))
        },
        onDismiss = { onOverflowEvent(ThreadEvent.SaveAsChannelDismiss) },
    )
}
```

No new `ThreadScreen` parameter — `onOverflowEvent` already carries the new events through the existing callback. Stateless composable contract preserved; no `MutableState` hoisted into the screen.

Imports to add: `de.pyryco.mobile.ui.conversations.components.SaveAsChannelDialog`. The `WorkspaceChoice` reference is implicit through `ThreadEvent.SaveAsChannelSubmit`'s constructor — no separate import needed in `ThreadScreen.kt`.

### 6. `strings.xml` — four new strings

Add immediately after the `rename_dialog_*` group (line 53):

```xml
<string name="save_as_channel_dialog_title">Save as channel</string>
<string name="save_as_channel_dialog_field_label">Name</string>
<string name="save_as_channel_dialog_workspace_dedicated">Move to dedicated channel folder</string>
<string name="save_as_channel_dialog_workspace_scratch">Keep in scratch</string>
<string name="save_as_channel_dialog_save">Save</string>
<string name="save_as_channel_dialog_cancel">Cancel</string>
```

Six strings (the AC mentions four; the AC was approximate — title, field label, two radio labels, two buttons = six). Naming convention follows `rename_dialog_*` (line 50-53). The caption under the dedicated radio (`~/pyry-workspace/channels/<auto-slug>/`) stays as a literal in the composable — see § 4.

**Reuse `save_as_channel_action`** (existing at line 11, "Save as channel…") for the menu item only (already used by #204). Do not introduce a `thread_overflow_save_as_channel`. The dialog title omits the ellipsis (the ellipsis convention means "opens further UI", which the menu item does and the dialog title is the further UI).

### 7. `MainActivity.kt` — no changes

`onOverflowEvent = vm::onOverflowEvent` (line 210) is a method reference; the two new `ThreadEvent.SaveAsChannelSubmit` and `ThreadEvent.SaveAsChannelDismiss` variants flow through it automatically. The ticket body's "two new event arms forwarded in MainActivity" was an estimation artifact — the actual MainActivity wiring requires no edit, same as #141.

## State + concurrency model

- **`pendingSaveAsChannelDialog: MutableStateFlow<SaveAsChannelDialogState?>`** — private, VM-owned, lifecycle-bound to `viewModelScope`. Single writer (`onOverflowEvent` handler); UI reads via the combined `state` flow.
- **`TransientDialogs` (private)** — pure projection over the two transient-dialog flows. Bundles `pendingRenameDialog` (Boolean) + `pendingSaveAsChannelDialog` (nullable sub-state). Lives only to keep the outer combine inside Kotlin's typed 5-arg overload.
- **Dialog internal state** (`fieldValue: TextFieldValue`, `selectedWorkspace: WorkspaceChoice`) — composable-local via `remember { mutableStateOf(...) }`. Not `rememberSaveable` — pending edits do not survive configuration changes while the dialog is open. Same posture as `RenameDialog` and `CreateFolderDialog`.
- **`viewModelScope.launch { repository.promote(...) }`** — fire-and-forget. Dialog dismisses immediately on submit (clear flag *before* launching). The next `observeConversations` emission flips the conversation's `isPromoted` to true; the thread screen's `isPromoted`-dependent UI (e.g. the conditional WorkspaceChip) updates reactively.
- **Dispatcher** — inherited from `viewModelScope` (Main.immediate). `FakeConversationRepository.promote` is in-memory `state.update { … }`; no IO dispatcher switch.
- **Cancellation** — none. The promote call is short and best-effort; if the user backs out of the screen between submit and emission, the launch completes in background. The user explicitly confirmed; promotion proceeds.
- **Two dialogs at once** — sealed by construction: `pendingRenameDialog = true` and `pendingSaveAsChannelDialog != null` are independent flags, but the only entry points are overflow menu items, the menu dismisses on tap (#204 convention), and re-opening the menu requires the previous dialog to be dismissed. Not enforced in the type system; not worth enforcing for the cost.

## Error handling

- **`repository.promote` throws** (e.g. `IllegalStateException("unknown conversation $id")` from the fake at `FakeConversationRepository.kt:128`) — `viewModelScope.launch { repository.promote(...) }` does not catch; the exception propagates to the uncaught-exception handler. Same posture as #141's `rename` and #78's `promote`. The conversation id passed is `state.value.conversationId` which is by construction valid (or the screen wouldn't be open). The throw is a programming-error signal, not a UX surface. **Out of scope to catch.**
- **Submit validation** — dual-layered: Save button is disabled when `trimmedName.isEmpty()` (UI prevention), and `KeyboardActions.onDone` short-circuits with the same check. The repository contract does not trim or re-validate (`promote` accepts `name: String` verbatim). Dialog is the trim/validate authority.
- **Slug edge cases** — a name that produces an empty slug after stripping (e.g. `"!!!"`) falls back to `"channel"` via the `ifEmpty { … }` clause in `toChannelSlug`. Phase 4's remote impl will compute the canonical slug; the fallback is a defensive no-op for Phase 0.
- **Network/offline** — Phase 4 concern. The `FakeConversationRepository` is in-memory.

No new error UI. No snackbar. No toast.

## Testing strategy

### Unit tests (`./gradlew test`) — `ThreadViewModelTest.kt`

**Extend `RecordingRepo`** (`ThreadViewModelTest.kt:599-637`):

- Add `val promoteCalls = mutableListOf<Triple<String, String, String?>>()` (or a small `PromoteCall` data class — developer's choice).
- Replace the existing `promote(...): Conversation = TODO("not used")` body (line 611-615) with a recording implementation: append `(conversationId, name, workspace)` to `promoteCalls`, return a minimal `Conversation` using the same shape `rename` (lines 623-637) already uses (`cwd = workspace ?: ""`, `isPromoted = true`, safe defaults elsewhere).

**New tests** (place after `onOverflowEvent_renameDismiss_clearsFlagWithoutRepositoryCall` at line 588, before the `// --- helpers ---` divider):

| Test | Setup | Action | Assertions |
|---|---|---|---|
| `onOverflowEvent_saveAsChannel_setsDialogStateWithSeededName` | `RecordingRepo`, fresh VM, `state` collector launched, `advanceUntilIdle()` | `vm.onOverflowEvent(ThreadEvent.SaveAsChannel)`; `advanceUntilIdle()` | `vm.state.value.saveAsChannelDialog == SaveAsChannelDialogState(initialName = "New channel")`; `repo.promoteCalls.isEmpty()`. |
| `onOverflowEvent_saveAsChannelSubmit_dedicated_callsPromoteWithSlugPath` | Same; pre-open dialog via `ThreadEvent.SaveAsChannel` | `vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Investment Strategy Review", workspace = WorkspaceChoice.DEDICATED))`; `advanceUntilIdle()` | `vm.state.value.saveAsChannelDialog == null`; `repo.promoteCalls == listOf(Triple("seed-channel-personal", "Investment Strategy Review", "pyry-workspace/channels/investment-strategy-review"))`. |
| `onOverflowEvent_saveAsChannelSubmit_scratch_callsPromoteWithNullWorkspace` | Same; pre-open dialog | `vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "kitchenclaw refactor", workspace = WorkspaceChoice.SCRATCH))`; `advanceUntilIdle()` | `vm.state.value.saveAsChannelDialog == null`; `repo.promoteCalls == listOf(Triple("seed-channel-personal", "kitchenclaw refactor", null))`. |
| `onOverflowEvent_saveAsChannelDismiss_clearsDialogWithoutPromote` | Same; pre-open dialog | `vm.onOverflowEvent(ThreadEvent.SaveAsChannelDismiss)`; `advanceUntilIdle()` | `vm.state.value.saveAsChannelDialog == null`; `repo.promoteCalls.isEmpty()`. |

The "exactly once" qualifier in AC #5(b) and 5(c) is satisfied by the list-equality assertion (a duplicate call would change the list to length 2 and fail equality).

**No new test for the empty-slug fallback** (`"!!!"` → `"channel"`). The slug helper is private and not directly callable; the AC doesn't mention pathological inputs; adding a test would require either reflection or exposing the helper. Acceptable gap; mention in PR description if it bothers the reviewer.

**No update needed to `onOverflowEvent_otherCases_doNotCallArchive`** (line 514-529). It iterates `NewSession`, `ChangeWorkspace`, `ChannelInfo` — `SaveAsChannel` was never in that loop (the test was added after #204 grouped `SaveAsChannel` with the no-ops in production code, but the test loop intentionally only covers the three remaining-no-ops). Verify by reading the test before editing.

### Instrumented tests (`./gradlew connectedAndroidTest`) — new `SaveAsChannelDialogTest.kt`

Path: `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialogTest.kt`.

Instrumented because `AlertDialog` renders into a separate window (same constraint as `RenameDialogTest` and `CreateFolderDialogTest`). Use `createComposeRule()` + `PyrycodeMobileTheme` wrapper. Mirror `RenameDialogTest`'s `string()` helper for resource lookup.

| Scenario | Setup | Action | Assertion |
|---|---|---|---|
| Renders title, field, both radios, both buttons | `SaveAsChannelDialog(initialName = "New channel", onSubmit = { _, _ -> }, onDismiss = {})` | none | Title "Save as channel" + field label "Name" + "Move to dedicated channel folder" + "Keep in scratch" + "Cancel" + "Save" all displayed. |
| Field pre-fills with `initialName`, fully selected | Same | none | Field node has `hasText("New channel")` (full-range selection is hard to assert directly; covered by manual smoke). |
| DEDICATED radio selected by default | Same | none | `onNodeWithText("Move to dedicated channel folder")` ancestor with `Role.RadioButton` is `assertIsSelected()`; SCRATCH variant `assertIsNotSelected()`. |
| Save enabled with seeded name | Same | none | "Save" `assertIsEnabled()`. |
| Save disabled when input cleared | Same | `performTextReplacement("")` on the field | "Save" `assertIsNotEnabled()`. |
| Save disabled on whitespace-only input | Same | `performTextReplacement("   ")` | "Save" `assertIsNotEnabled()`. |
| Save enabled with non-blank trimmed input | Same | `performTextReplacement("  alpha  ")` | "Save" `assertIsEnabled()`. |
| Selecting SCRATCH radio updates semantic state | Same | Click `onNodeWithText("Keep in scratch")` (or its row) | SCRATCH `assertIsSelected()`; DEDICATED `assertIsNotSelected()`. |
| Tap Save with DEDICATED → onSubmit(trimmed, DEDICATED) | `var submitted: Pair<String, WorkspaceChoice>? = null` | `performTextReplacement("  alpha  ")`; click "Save" | `submitted == "alpha" to WorkspaceChoice.DEDICATED`. |
| Tap Save with SCRATCH → onSubmit(trimmed, SCRATCH) | Same | `performTextReplacement("alpha")`; click "Keep in scratch"; click "Save" | `submitted == "alpha" to WorkspaceChoice.SCRATCH`. |
| Tap Cancel → onDismiss, never onSubmit | `var submitted: ... = null; var dismissed = 0` | Click "Cancel" | `dismissed == 1`; `submitted == null`. |

Notes:

- Use `performTextReplacement(...)` (not `performTextInput`) — the dialog seeds the field with `initialName` and full-range selection. `performTextInput` doesn't honour the selection.
- Field-text assertions: disambiguate from the title using `composeTestRule.onNode(hasSetTextAction() and hasText("New channel"))`.
- Radio assertions: `Modifier.selectable(... role = Role.RadioButton)` produces `SemanticsProperties.SelectableGroup` + `SemanticsProperties.Role = RadioButton` + `SemanticsProperties.Selected`. `assertIsSelected()` is the standard primitive.
- Outside-tap / back-press: `AlertDialog`'s `onDismissRequest = onDismiss` handles both. Asserting these in instrumented tests is awkward (`Espresso.pressBack()` against a Compose-rule-only setup needs an Activity). **Drop these test scenarios** — the wiring is provable by code inspection (`onDismissRequest = onDismiss`) and the Cancel-button test covers the `onDismiss` callback shape. AC #2 mentions "outside-tap and back-press dismiss" but the existing `RenameDialogTest` follows the same posture (doesn't test outside-tap explicitly).
- No Mockito / MockK — the dialog has no collaborators beyond the two lambdas, which test code captures into `var`s.

### Out of scope for tests

- No `ThreadScreen` integration test for the new overlay block — one assignment + a `?.let { }` block. The unit tests cover state shape; the dialog tests cover dialog behaviour. Combined coverage is sufficient.
- No `RecordingRepo.promote` test for the slug helper in isolation — the VM tests assert observable behaviour via the workspace string argument.
- No new preview snapshot test — the project doesn't have a snapshot infrastructure yet; the three `@Preview`s exist for developer/reviewer visual sanity only.

### Manual smoke

After landing, run debug on device/emulator:

1. Open a discussion (`isPromoted = false`) in the thread screen.
2. Tap the overflow menu → "Save as channel…".
3. Dialog appears with "New channel" pre-filled (fully selected), DEDICATED radio selected.
4. Type a new name (the seeded text gets replaced because of full-range selection).
5. Tap Save → dialog dismisses → returning to the channel list shows the conversation has moved tiers (Channels now, not Discussions).
6. Repeat with SCRATCH radio selected; verify the conversation's workspace label stays as "scratch" (cwd unchanged).
7. Repeat with Cancel; verify no promotion occurs.

Catches navigation-wiring regressions the unit tests miss.

## Open questions

- **Outer combine arity (nested-combine vs vararg).** § 3 recommends the nested-combine approach to keep the outer combine inside Kotlin's typed 5-arg overload. The vararg `combine(vararg flows, transform)` overload is an acceptable alternative (one fewer indirection in exchange for losing per-position typing). Either is reasonable; the spec's recommendation is the typed nested form because it makes the `TransientDialogs` semantics ("the two transient overlays the thread screen owns") explicit in code.
- **`WorkspaceChoice` enum placement.** Currently top-level in `ThreadViewModel.kt` (this spec's recommendation). It could equally live in `SaveAsChannelDialog.kt` (UI-side) or `ConversationRepository.kt` (data-side). The argument for VM-side: it's a `ThreadEvent` payload type, and `ThreadEvent` lives there. The argument against: cross-package import from `components/` to `thread/`. Net: keep it in `ThreadViewModel.kt`; the import is one line and the semantic location is correct. Flag in PR if the reviewer prefers a different placement.
- **Slug-helper visibility.** Currently file-scope `private`. If a sibling Phase 0 ticket wants to derive slugs for a different surface (unlikely Phase 0; possibly Phase 4 settings UI), promote to a shared `data/model/Slug.kt` then. Don't pre-shareify.
- **Caption under DEDICATED radio.** Figma renders `~/pyry-workspace/channels/<auto-slug>/` in Roboto Mono. Per § 4, this is a literal string in the composable (not a resource), and uses `MaterialTheme.typography.labelSmall` with `FontFamily.Monospace`. If a translator later wants to localize the "~/" prefix or the path separator semantics, the literal converts to a resource trivially. Acceptable as a literal for Phase 0.

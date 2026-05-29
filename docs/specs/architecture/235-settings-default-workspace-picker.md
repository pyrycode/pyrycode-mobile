# Spec #235 — Default workspace row opens Workspace Picker + persists selection

Wire the inert "Default workspace" `SettingsRow` to open the existing `WorkspacePicker` host
(shipped #143/#220), persist the chosen cwd via `AppPreferences.setDefaultWorkspace(...)`, and
render the current persisted default in the row's supporting text.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt:18-91` — the VM to extend.
  **Copy the `defaultModel` / `onSelectDefaultModel` shape verbatim** for the new `defaultWorkspace`
  StateFlow + persist callback. `STOP_TIMEOUT_MILLIS` companion constant already present.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:50-67` — `SettingsScreen` signature
  (add 5 params here); `:189-194` — the Default workspace row to wire; `:276-291` — the `SettingsRow`
  helper (supporting + onClick already supported, no change needed); `:327-332` — the `ThemeMode.label()`
  extension, the **pattern to mirror for the new workspace-label helper**; `:336-378` — the two `@Preview`
  bodies that must gain the new params.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt:18-67` — the reusable
  host. **This is the reuse seam.** `WorkspacePicker(visible, onPicked, onDismiss)` self-injects the
  `ConversationRepository`, observes `recentWorkspaces()`, and owns the Create-Folder dialog (calling
  `onPicked(path)` for *both* recent picks and newly-created folders). Do NOT reimplement any of it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:214-218` — the canonical
  render of the host from a screen: `WorkspacePicker(visible = …, onPicked = …, onDismiss = …)`. Copy this shape.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:91, 187-200` — the
  visibility-flag pattern: a `private val pendingWorkspacePicker = MutableStateFlow(false)`, an opener
  (`onWorkspaceChipTapped`), a pick handler (`onWorkspacePicked` → flip false + persist), a dismiss
  handler (`onWorkspacePickerDismissed` → flip false). `:294-299` — `workspaceLabel()`, the display
  convention to replicate (see Design → Display label).
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt:66-71` — `defaultWorkspace: Flow<String>`
  (falls back to `DEFAULT_SCRATCH_CWD`) and `suspend fun setDefaultWorkspace(cwd: String)`. Already shipped (#231).
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt:21` — `const val DEFAULT_SCRATCH_CWD = "~/.pyrycode/scratch"`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:219-242` — the Settings route. Add two
  `collectAsStateWithLifecycle()` reads + 5 new args to the `SettingsScreen(...)` call.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt:54-119, 204-237, 439-484` —
  test harness: real `AppPreferences` over a `TemporaryFolder` DataStore (no mocks), `stubRepo()` helper.
  Mirror `onSelectDefaultModel_persists*` and `defaultModel_initialState_*` for the new cases.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The "Default workspace" row (node `17:56`–`17:60`) is the standard M3 `ListItem`: headline "Default workspace"
(`body-large` / `Schemes/on-surface`), supporting text "scratch" (`body-small` / `Schemes/on-surface-variant`),
trailing `KeyboardArrowRight` chevron (20dp). Visually **unchanged** from the current
`SettingsScreen.kt:189-194` — this ticket only makes the supporting text dynamic and wires `onClick`. The
Workspace Picker sheet it opens (node `20:2`) is reused unchanged from #143; no new Figma surface.

## Context

`SettingsScreen.kt:189-194` currently renders a dead "Default workspace" row: hardcoded `supporting = "scratch"`,
empty `onClick = {}`. `AppPreferences.defaultWorkspace`/`setDefaultWorkspace` (#231) and the `WorkspacePicker`
host (#143/#220) already exist; this ticket connects them through `SettingsViewModel`. The persisted value is
consumed by new-conversation creation in #236 (closed) and the FAB short-press default (#240) — this ticket
owns only the Settings-side UI: open the picker, persist the pick, reflect the current default.

#208 (thread overflow "Change workspace…" → same picker) is a parallel OPEN sibling applying this shape to a
different consumer. Branch-overlap check at architect time: no shared files (#208 is thread-side only). Design
from this spec, not #208's branch.

## Design

Three production files, no new exported types (the `WorkspacePicker` host is reused as-is).

### 1. `SettingsViewModel` — contract additions

Mirror the existing per-field StateFlow + callback style. New surface:

```kotlin
val defaultWorkspace: StateFlow<String>          // appPreferences.defaultWorkspace.stateIn(…, initialValue = DEFAULT_SCRATCH_CWD)
val workspacePickerVisible: StateFlow<Boolean>   // backed by a private MutableStateFlow(false), exposed via asStateFlow()

fun onDefaultWorkspaceTapped()                   // pendingWorkspacePicker.value = true
fun onSelectDefaultWorkspace(path: String)       // flip visible→false; viewModelScope.launch { appPreferences.setDefaultWorkspace(path) }
fun onWorkspacePickerDismissed()                 // pendingWorkspacePicker.value = false  (no persist)
```

- `defaultWorkspace` uses the same `stateIn(scope = viewModelScope, started = WhileSubscribed(STOP_TIMEOUT_MILLIS),
  initialValue = DEFAULT_SCRATCH_CWD)` recipe as `defaultModel` et al. Initial value is the sentinel so the row
  shows "scratch" before the cold flow's first emission, satisfying "initial state mirrors `AppPreferences.defaultWorkspace`".
- `onSelectDefaultWorkspace` is the single handler for **both** AC2 (recent pick) and AC3 (create-new) — the
  `WorkspacePicker` host funnels both into its `onPicked` callback. Flipping the visibility flag to `false`
  is what dismisses the sheet (the host's `if (!visible) return` early-out drops it from composition), exactly
  as `ThreadViewModel.onWorkspacePicked` does at `:191-196`.
- Import `de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD` and `kotlinx.coroutines.flow.MutableStateFlow` /
  `asStateFlow`. Constructor signature is unchanged (already `(AppPreferences, ConversationRepository)`); no Koin/DI edit.

### 2. `SettingsScreen` — wiring

Add to the composable signature (placed with the other `default*` params):

```kotlin
defaultWorkspace: String,
workspacePickerVisible: Boolean,
onDefaultWorkspaceTapped: () -> Unit,
onSelectDefaultWorkspace: (String) -> Unit,
onWorkspacePickerDismissed: () -> Unit,
```

- Wire the row at `:189-194`: `supporting = workspaceLabel(defaultWorkspace)`, `onClick = onDefaultWorkspaceTapped`.
  Leave headline + trailing chevron as-is.
- Render the host alongside the existing conditional dialogs in the `Scaffold` content lambda (it self-gates on `visible`):
  `WorkspacePicker(visible = workspacePickerVisible, onPicked = onSelectDefaultWorkspace, onDismiss = onWorkspacePickerDismissed)`.
- Add a private label helper mirroring the `ThemeMode.label()` style at `:327`:

```kotlin
private fun workspaceLabel(cwd: String): String =
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) "scratch"
    else cwd.substringAfterLast('/').ifEmpty { cwd }
```

- Imports: `de.pyryco.mobile.ui.conversations.components.WorkspacePicker`, `de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD`.
- Update **both** `@Preview` bodies (`:336`, `:358`): add `defaultWorkspace = DEFAULT_SCRATCH_CWD`,
  `workspacePickerVisible = false`, and `{}` for the three new lambdas.

#### Display label — architect's call

The sentinel → `"scratch"` (matches the placeholder copy and `SettingsRow.kt` Theme/Model/Effort summary style).
For real paths, show the **trailing folder name** (e.g. `~/Workspace/Projects/pyrycode-mobile` → `pyrycode-mobile`),
**not** the full path. Rationale: the Settings supporting line is a one-line *summary*, semantically identical to
the thread workspace chip (`ThreadViewModel.workspaceLabel()` at `:294`), which already uses trailing-folder-name.
The picker's recent rows show full monospace paths because they're a *selection* list needing disambiguation —
a different role. Keeping the summary convention also avoids overflow/wrap of a long path in a `body-small` slot.
The helper is intentionally **duplicated locally** rather than extracted from `ThreadViewModel` (its copy is
`private`; extracting it would touch `ThreadViewModel.kt`, which #208 is concurrently editing — out of scope).

### 3. `MainActivity` — Settings route (`:219-242`)

Add two reads and five args:

```kotlin
val defaultWorkspace by vm.defaultWorkspace.collectAsStateWithLifecycle()
val workspacePickerVisible by vm.workspacePickerVisible.collectAsStateWithLifecycle()
// … SettingsScreen(… , defaultWorkspace = defaultWorkspace, workspacePickerVisible = workspacePickerVisible,
//     onDefaultWorkspaceTapped = vm::onDefaultWorkspaceTapped, onSelectDefaultWorkspace = vm::onSelectDefaultWorkspace,
//     onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed)
```

`ConversationRepository` is a Koin `single` available app-wide, so the host's internal `koinInject` resolves in
the Settings route with no DI change.

## State + concurrency model

- **`defaultWorkspace`** — cold `AppPreferences.defaultWorkspace` (DataStore-backed) → `stateIn` on `viewModelScope`,
  `WhileSubscribed(5_000)`, sentinel initial value. Single source of truth; the row reads it derived through `workspaceLabel`.
- **`workspacePickerVisible`** — hot in-memory `MutableStateFlow(false)` exposed via `asStateFlow()`. Pure
  transient UI state, no dispatcher/scope concerns; resets to `false` on pick or dismiss. Lives in the VM (not
  the composable) per single-source-of-state and to satisfy the AC's "VM exposes a visibility flag".
- **Persistence write** — `viewModelScope.launch { appPreferences.setDefaultWorkspace(path) }`, fire-and-forget,
  identical to `onSelectDefaultModel`. DataStore serializes its own IO; no manual dispatcher switch.
- **Cancellation/exit** — `WhileSubscribed(5_000)` stops the cold flow 5s after the screen leaves; the visibility
  flag is plain memory, nothing to clean up. Recents observation lives inside the host and follows its lifecycle.

## Error handling

No new failure surface. DataStore writes are fire-and-forget exactly like the sibling preference setters
(`onSelectDefaultModel`/`onToggleDefaultYolo`); no observed write failures justify wrapping them (evidence-based —
matches the established pattern, don't add speculative try/catch). `recentWorkspaces()` and the create-folder flow
(`createWorkspaceFolder`) are internal to the unchanged `WorkspacePicker` host (#143); the fake returns a list and
the remote impl is deferred to Phase 4. Dismiss-without-pick simply flips the flag and never calls the setter, so
the persisted default is untouched (AC4).

## Testing strategy

Unit only (`./gradlew test`) — extend `SettingsViewModelTest` using the existing harness (real `AppPreferences`
over a `TemporaryFolder` DataStore; `stubRepo()` for the repo arg). "Does NOT call the setter" is asserted by
reading the persisted value back and checking it is unchanged (the harness uses a real store, not a verify-mock —
match that style). Scenarios:

- `defaultWorkspace_initialState_emitsScratchSentinel_whenNoStoredValue` — fresh prefs → `vm.defaultWorkspace.value == DEFAULT_SCRATCH_CWD`.
- `defaultWorkspace_initialState_mirrorsPersistedValue` — `prefs.setDefaultWorkspace("~/Workspace/Projects/foo")` first → VM value mirrors it.
- `onSelectDefaultWorkspace_persistsPath` — call with a path → `prefs.defaultWorkspace.first()` equals it; `workspacePickerVisible.value == false`.
- `defaultWorkspace_flowReEmits_afterOnSelectDefaultWorkspace` — collector running, two successive selects → VM value tracks each.
- `onDefaultWorkspaceTapped_setsPickerVisible` — `workspacePickerVisible.value` flips `true`.
- `onWorkspacePickerDismissed_hidesPicker_andLeavesDefaultUnchanged` — open, persist a value, dismiss → visible `false` AND `prefs.defaultWorkspace.first()` unchanged (AC4); also assert dismiss from the default state never writes a non-sentinel value.

No new Compose/instrumented tests required — the row + host render is a thin pass-through of existing,
already-tested composables. (If the developer adds a `ComposeTestRule` smoke test that tapping the row makes the
sheet appear, that's welcome but not load-bearing.)

## Open questions

- **Label consolidation (deferred).** Three `workspaceLabel`-style helpers will now exist (this one,
  `ThreadViewModel:294`, and the chip logic). Consolidating into a shared `Conversation`/workspace util is a
  reasonable future cleanup but is out of scope here — it would touch `ThreadViewModel.kt` (concurrently edited by
  #208) and violates "touch only what's necessary". Leave duplicated.
- **Full-path vs folder-name** is settled above (folder name); flagged only so code-review knows the divergence
  from the picker's full-path recents is intentional, not an oversight.

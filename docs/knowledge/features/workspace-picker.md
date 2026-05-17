# WorkspacePicker

Stateful host composable (#220) that owns the workspace-picker flow: it composes [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) and [`CreateFolderDialog`](./create-folder-dialog.md) (#213), reads [`recentWorkspaces()`](./conversation-repository.md), calls [`createWorkspaceFolder(name)`](./conversation-repository.md) on submit, and reports the picked path back to the caller via a single `onPicked` callback. Each consumer screen contributes one `Boolean` (`visible`) to its `UiState` and one callback (`onPicked(path)`) — every other piece of wiring (repository binding, sheet ↔ dialog sequencing, single-invocation guarantee) lives inside the host.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `WorkspacePicker.kt`. Sibling to [`WorkspacePickerSheet`](./workspace-picker-sheet.md) and [`CreateFolderDialog`](./create-folder-dialog.md) — the host that the other two were designed to compose into.

## Shape

```kotlin
@Composable
fun WorkspacePicker(
    visible: Boolean,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public` (no `internal`).** Unlike the two children, the host is the package's public contract — consumer screens in sibling packages (`ui/conversations/list/`, `ui/conversations/thread/`) call it directly. Kotlin's default top-level visibility is `public`; do not add `internal`.
- **`visible: Boolean` is a hoisted prop.** Each consumer owns its own visibility flag (typically a `combine` arm on the screen's `UiState`, mirroring [`DiscussionListViewModel`](./discussion-list-viewmodel.md)'s `pendingPromotion` pattern). When `visible == false` the host renders nothing (early return before any `koinInject` / `remember` / `LaunchedEffect`).
- **`onPicked: (String) -> Unit`.** Single callback for both "user tapped a recent row" and "user created a new folder and we got back a path." The consumer cannot distinguish — by design: the three near-term consumers (Channel List FAB long-press, [`ThreadScreen`](./thread-screen.md) empty-state chip from #137, thread overflow "Change workspace…" from #208) take the same next action regardless of which path produced the result.
- **`onDismiss: () -> Unit`.** Fires only when the user dismisses the **sheet** (close icon, scrim tap, drag-down, back-press while the dialog is not open). Cancelling the create-folder dialog does NOT fire `onDismiss` — the sheet stays visible, the dialog just closes.
- **`modifier: Modifier = Modifier`.** Forwarded to the underlying `WorkspacePickerSheet`; lets consumers attach semantics or test tags. Has no effect when invisible.

## What it does

The public `WorkspacePicker` is a one-line gate: `if (!visible) return`, otherwise it obtains the repository via `koinInject<ConversationRepository>()` and delegates to a package-`internal` seam `WorkspacePickerInternal(repository, onPicked, onDismiss, modifier)`. The seam holds all behaviour:

```kotlin
val recents by repository
    .recentWorkspaces()
    .collectAsStateWithLifecycle(initialValue = emptyList())
var showCreateDialog by rememberSaveable { mutableStateOf(false) }
val scope = rememberCoroutineScope()

WorkspacePickerSheet(
    recent = recents,
    onPick = onPicked,
    onCreateNew = { showCreateDialog = true },
    onDismiss = onDismiss,
    modifier = modifier,
)
if (showCreateDialog) {
    CreateFolderDialog(
        onCreate = { name ->
            showCreateDialog = false
            scope.launch {
                val path = repository.createWorkspaceFolder(name)
                onPicked(path)
            }
        },
        onDismiss = { showCreateDialog = false },
    )
}
```

Behaviour:

- **Tapping a recent row** → `onPick = onPicked` passes straight through. The sheet emits `onPick(path)` exactly when the user taps a row; no wrapping, no transformation.
- **Tapping "Create new folder under pyry-workspace…"** → `onCreateNew = { showCreateDialog = true }` flips the dialog flag. The `AlertDialog` composes into its own window above the `ModalBottomSheet`, so the two surfaces don't conflict visually.
- **Submitting the dialog** → `showCreateDialog = false` flips synchronously *before* launching the coroutine, so the dialog leaves composition and a second `onCreate` from the same dialog instance is structurally impossible. The launched job awaits `repository.createWorkspaceFolder(name)`, then calls `onPicked(path)` with the returned path.
- **Cancelling the dialog (Cancel button, outside-tap, back-press)** → `onDismiss = { showCreateDialog = false }` flips only the dialog flag. The sheet stays visible. The host's outer `onDismiss` is NOT called.
- **Dismissing the sheet itself (close icon, scrim tap, drag-down, back-press while the dialog is not open)** → `onDismiss = onDismiss` invokes the consumer's outer callback. The consumer typically flips its `visible` flag to false; the host re-renders with `visible == false`, returns nothing, and the sheet's exit animation runs as `ModalBottomSheet` leaves composition.
- **`visible == false`** → `if (!visible) return` in the public composable means no Koin lookup, no flow subscription, no coroutine scope. Mounting the host as a permanent sibling of a screen body costs nothing when invisible.

### Internal testing seam

`WorkspacePickerInternal(repository, onPicked, onDismiss, modifier)` is `internal` (not `private`) so the `androidTest/` source set can reach it. The seam exists for one reason: the project has no `KoinTestRule` plumbing (no androidTest in this repo bootstraps Koin), so the tests would otherwise need to set up `startKoin` / `stopKoin` per test. The seam takes the repository as a parameter, the test passes [`FakeConversationRepository()`](./conversation-repository.md) directly, and the public Koin path stays untouched. Same shape as [`CreateFolderDialog`](./create-folder-dialog.md)'s `CreateFolderDialogInternal` seam (#213) — but **here the seam is `internal`, not `private`**, because the test reaches in. The early-return `if (!visible) return` lives in the public composable, NOT in the seam; the seam is always-visible because the visibility flag is trivially-correct conditional rendering and doesn't need its own test.

**Rule:** consumers MUST call the public `WorkspacePicker`. The seam is for tests only; runtime use would bypass the Koin lookup that production code depends on.

### Single-invocation guarantee for `createWorkspaceFolder`

AC #2 demands the write fires exactly once per submit. Two failure modes are ruled out by construction:

1. **Rapid double-tap on the Create button.** Eliminated because `showCreateDialog = false` flips synchronously *before* the coroutine launches. The dialog unmounts; subsequent taps cannot reach the now-unmounted button.
2. **Recomposition firing `onCreate` twice for the same click.** Compose's click handling is debounced internally to one invocation per click — the framework gives this for free.

What the host does NOT guarantee: idempotence of `createWorkspaceFolder` itself. Dismissing the dialog and re-opening it with the same name produces two writes — that's two distinct submits, both correct per the fake's semantics (`bumpWorkspace` dedups the recents list, so position 0 is updated rather than duplicated). Deduping across consecutive opens is a consumer concern, not a host concern.

### Cancellation behaviour on consumer-driven dismiss mid-write

If the consumer flips `visible = false` while a `createWorkspaceFolder` coroutine is in flight (e.g. user dismisses the sheet via scrim tap before a future Phase 4 network call returns), `rememberCoroutineScope` cancels the in-flight job when the host leaves composition. `onPicked` is NOT called. In Phase 0 this is unreachable (the [`FakeConversationRepository`](./conversation-repository.md) `createWorkspaceFolder` is non-suspending in practice — `MutableStateFlow.update` then return); in Phase 4, this is the documented behaviour. If a future Phase 4 ticket needs at-least-once semantics for the write, the scope choice will change (e.g. `applicationScope`).

## State + concurrency

- **`viewModelScope` jobs:** none. The host is not a ViewModel.
- **`StateFlow`s exposed by the host:** none. The host exposes nothing; it consumes `repository.recentWorkspaces()` and renders.
- **Flow collection:** `collectAsStateWithLifecycle(initialValue = emptyList())` — the project's established pattern (see `MainActivity.kt`). Empty initial value means the sheet renders without a Recent header on first composition until the first emission arrives. With [`FakeConversationRepository`](./conversation-repository.md)'s `MutableStateFlow`-backed `recents`, the first emission is synchronous; the empty-initial path is reached only when the seeded recents list is empty.
- **Coroutine scope:** `rememberCoroutineScope()` for the on-submit launch. Tied to the host's composition; cancels when the host leaves composition (i.e. when `visible` flips false).
- **`rememberSaveable` for `showCreateDialog`** survives configuration changes (rotation) within a single host instance. When `visible` flips false → true again, the host is re-mounted so the saveable state is fresh (dialog starts closed). Desired.
- **Dispatcher:** Main (Compose default). `repository.createWorkspaceFolder` is `suspend` but Phase 0's implementation is non-blocking; `Dispatchers.IO` is not requested. Phase 4 will revisit.

## Error handling

- **`recentWorkspaces()` failure modes.** None in Phase 0 (the fake's `MutableStateFlow` cannot fail). If Phase 4's flow ever emits a terminal error, `collectAsStateWithLifecycle` swallows it silently — the sheet renders the last good list. Acceptable for Phase 0; Phase 4 will wire a `catch { }` operator if surfacing errors is needed.
- **`createWorkspaceFolder` failure modes.**
  - `IllegalArgumentException` from blank-name validation: structurally unreachable. The dialog's Create button is disabled while the trimmed name is blank (see [`CreateFolderDialog`](./create-folder-dialog.md) § Internal state); the dialog never invokes `onCreate("")`. The host does not catch — if this ever fires it indicates a contract violation upstream and the app should crash loudly.
  - Phase 4 failures (network, server-side collision): out of scope here. The host has no `try { … } catch { … }` wrap today. When Phase 4 lands, the natural surface is a snackbar / error-state slot; until then, the throwing default on `ConversationRepository` and the fake's non-throwing implementation mean uncaught throws cannot occur.
- **Coroutine cancellation:** silent — see § Cancellation behaviour on consumer-driven dismiss mid-write above. No callback is invoked.

## Configuration

- **No new dependencies.** `koinInject`, `collectAsStateWithLifecycle`, `rememberCoroutineScope`, `rememberSaveable`, `launch` all ship in the existing `org.koin:koin-androidx-compose` / `androidx.lifecycle:lifecycle-runtime-compose` / `androidx.compose.runtime` / `kotlinx.coroutines` artifacts. No `gradle/libs.versions.toml` edit, no `app/build.gradle.kts` edit.
- **No new DI registrations.** `single { FakeConversationRepository() } bind ConversationRepository::class` is already registered in [`AppModule`](./dependency-injection.md) by [#209](../codebase/209.md) / [#210](../codebase/210.md); the host's `koinInject<ConversationRepository>()` lookup resolves to the same singleton that ViewModels consume via constructor injection.
- **No previews.** The host's behaviour is sequencing, not pixels — the two children carry their own previews ([`WorkspacePickerSheet`](./workspace-picker-sheet.md): two; [`CreateFolderDialog`](./create-folder-dialog.md): three). Adding a host preview would require a fake-repo wrap and would duplicate what the children's previews already show.

## Usage

Consumers add one `Boolean` to their `UiState` and one callback to forward the picked path. The established pattern is a `combine` arm on the ViewModel, mirroring [`DiscussionListViewModel`](./discussion-list-viewmodel.md)'s `pendingPromotion`:

```kotlin
data class UiState(
    /* … other state … */
    val pendingWorkspacePicker: Boolean = false,
)

// In the screen composable:
WorkspacePicker(
    visible = state.pendingWorkspacePicker,
    onPicked = { path -> viewModel.onEvent(Event.WorkspacePicked(path)) },
    onDismiss = { viewModel.onEvent(Event.WorkspacePickerDismissed) },
)
```

The host is typically mounted as a sibling of the screen body inside the same `Scaffold` content slot. Mounting it permanently is cheap — when `visible = false` it does no work.

## Tests

Three Compose UI tests in `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerTest.kt` (`createComposeRule()` + `AndroidJUnit4`, matching [`CreateFolderDialogTest.kt`](../codebase/213.md) and [`WorkspacePickerSheetTest.kt`](../codebase/212.md) — no MockK, no Turbine, no shared fixtures; `assertEquals` for callback verification, `hasText` / `hasSetTextAction` lookups for selectors, real `FakeConversationRepository` not a hand-rolled mock). All three target the `WorkspacePickerInternal` seam directly because the seam takes the repository as a parameter and the androidTest source set cannot easily bootstrap Koin:

- **`dialog_submit_calls_createWorkspaceFolder_exactly_once_and_forwards_returned_path_to_onPicked`** — tap "Create new folder under pyry-workspace…", type `"my-workspace"`, tap Create. Asserts `picked == listOf("pyry-workspace/my-workspace")` (exactly one invocation, exact path) **and** `repo.recentWorkspaces().first().first() == "pyry-workspace/my-workspace"` (the bump landed at position 0; re-asserts the repo contract from the host's vantage and validates end-to-end wiring).
- **`cancelling_create_dialog_keeps_sheet_visible_and_does_not_invoke_host_onDismiss`** — tap "Create new folder under pyry-workspace…", then tap Cancel. Asserts `dismissed == 0`, the sheet's `"Choose workspace"` title still displays, and the dialog's `"Create workspace"` title `assertDoesNotExist()`. This is the load-bearing test for the "two flag-flips that share the surface verb 'dismiss' route differently" contract.
- **`tapping_a_recent_row_invokes_onPicked_with_that_rows_path`** — tap a known seeded recent path (`"~/Workspace/pyrycode-mobile"` from the fake's default seed) and assert `picked == listOf("~/Workspace/pyrycode-mobile")`. The wiring is `onPick = onPicked` (one-line pass-through); the test locks the contract per AC #5c.

Tests intentionally NOT included:

- **Empty-recents rendering** — covered at the sheet layer by `WorkspacePickerSheetTest.recent_header_is_omitted_when_recent_is_empty` ([#212](../codebase/212.md)). The host adds no logic over what the sheet already exercises.
- **Sheet dismiss invokes host `onDismiss`** — `onDismiss = onDismiss` is a one-line pass-through; reading the source documents it, and the underlying `IconButton(... contentDescription = "Close")` wiring is already covered by `WorkspacePickerSheetTest.close_icon_invokes_onDismiss_on_tap`.
- **`visible == false` renders nothing** — the seam doesn't have a `visible` parameter to exercise; the early-return guard lives only in the public `WorkspacePicker`, and a trivially-correct `if (!visible) return` doesn't need a test.

## Edge cases / limitations

- **`onPicked` does not distinguish "recent tap" from "newly created."** Consumers cannot tell which path produced the result. By design — both flows yield a workspace path the screen should bind, and the next action is identical.
- **Idempotence across repeated submits is not guaranteed.** The host guarantees single-invocation *per submit*; opening the dialog twice and submitting the same name twice produces two writes (both correct per `bumpWorkspace`'s dedup semantics on the recents list).
- **Cancellation mid-write is silent.** No `onPicked`, no `onDismiss`, no error callback — the job just stops. In Phase 0 this is unreachable (synchronous fake); in Phase 4, document the contract before relying on at-least-once semantics.
- **`name` reaches `createWorkspaceFolder` already trimmed.** `CreateFolderDialog` trims internally before invoking `onCreate` (see [#213](../codebase/213.md) § Internal state); the host does not re-trim.
- **Scratch sentinel filtering happens upstream.** [`recentWorkspaces()`](./conversation-repository.md) already filters `DEFAULT_SCRATCH_CWD` and the empty cwd at the bump-write layer ([#209](../codebase/209.md)'s `bumpWorkspace`); the host passes the list through unchanged.
- **No animated-close coordination.** The host does not call `sheetState.hide()` before invoking `onDismiss`; the sheet's exit animation runs naturally as `ModalBottomSheet` leaves composition when `visible` flips false. If a future ticket needs the animated-close ordering (`sheetState.hide()` then `onDismiss`), the sheet already exposes `sheetState` (see [`WorkspacePickerSheet`](./workspace-picker-sheet.md)) — the host would need a `LaunchedEffect` to drive the hide.

## Related

- Ticket notes: [`../codebase/220.md`](../codebase/220.md)
- Spec: `docs/specs/architecture/220-workspace-picker-host-composable.md`
- Parent: split from [#207](https://github.com/pyrycode/pyrycode-mobile/issues/207) (Workspace Picker host); itself split from [#143](https://github.com/pyrycode/pyrycode-mobile/issues/143).
- Children: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212), [`CreateFolderDialog`](./create-folder-dialog.md) (#213).
- Upstream data surfaces: [`recentWorkspaces()`](./conversation-repository.md) from [#209](../codebase/209.md), [`createWorkspaceFolder(name)`](./conversation-repository.md) from [#210](../codebase/210.md).
- Sibling stateful host (different shape — not a composable host, but the same hoisted-`visible: Boolean` UiState convention): [`DiscussionListViewModel`](./discussion-list-viewmodel.md)'s `pendingPromotion`, [`SaveAsChannelDialog`](./channel-list-screen.md) visibility from #142.
- Downstream / open:
  - **Channel List FAB long-press (next ticket after #220)** — edits `ChannelListScreen` + `ChannelListViewModel`, contributes a `pendingWorkspacePicker: Boolean` to the screen's `UiState`, routes `onPicked(path)` to a new-discussion seed action.
  - **Empty-thread workspace chip ([#137](https://github.com/pyrycode/pyrycode-mobile/issues/137))** — the chip's tap opens this host.
  - **Thread overflow "Change workspace…" ([#208](https://github.com/pyrycode/pyrycode-mobile/issues/208))** — the menu item's tap opens this host.
  - **Phase 4 error UI** — snackbar / error-state slot for `createWorkspaceFolder` network or server-side failures, landed when the real backend ships.
  - **Animated-close coordination** — a `LaunchedEffect` driving `sheetState.hide()` before `onDismiss` if a future ticket demands the exit-animation completion before the consumer's state flip.
  - **Literal-strings localisation** — out of scope; deferred to the first `strings.xml` pass alongside the children's literals.

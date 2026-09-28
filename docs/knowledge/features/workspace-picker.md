# WorkspacePicker

Stateful host composable (#220) that owns the workspace-picker flow: it composes [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212) and [`CreateFolderDialog`](./create-folder-dialog.md) (#213), reads [`recentWorkspaces()`](./conversation-repository.md), calls [`createWorkspaceFolder(name)`](./conversation-repository.md) on submit, and reports the picked path back to the caller via a single `onPicked` callback. Each consumer screen contributes one `Boolean` (`visible`) to its `UiState` and one callback (`onPicked(path)`) — the route supplies repository ownership, while sheet ↔ dialog sequencing and the single-invocation guarantee live inside the host. First consumer (#221): [`ChannelListScreen`](./channel-list-screen.md)'s FAB long-press → picker → `repository.createDiscussion(workspace = path)` → navigate — retired along with the FAB in #738; the host row's own long-press carried this sheet forward until #904 moved it onto [`AddWorkspaceModal`](mobile-modal-callers.md#callers) instead. See [§ Consumers](#consumers) for who still draws this host.

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
- **`onPicked: (String) -> Unit`.** Single callback for both "user tapped a recent row" and "user created a new folder and we got back a path." The consumer cannot distinguish — by design: every consumer takes the same next action regardless of which path produced the result (Channel List FAB long-press — shipped #221 — calls `createDiscussion(workspace = path)`; the Settings "Default workspace" row — shipped [#235](../codebase/235.md), host-scoped by [#714](https://github.com/pyrycode/pyrycode-mobile/issues/714) — calls `setDefaultWorkspace(ownerServerId, path)`; [`ThreadScreen`](./thread-screen.md) empty-state chip from #137 and thread overflow "Change workspace…" from #208 likewise).
- **`onDismiss: () -> Unit`.** Fires only when the user dismisses the **sheet** (close icon, scrim tap, drag-down, back-press while the dialog is not open). Cancelling the create-folder dialog does NOT fire `onDismiss` — the sheet stays visible, the dialog just closes.
- **`modifier: Modifier = Modifier`.** Forwarded to the underlying `WorkspacePickerSheet`; lets consumers attach semantics or test tags. Has no effect when invisible.

## What it does

The public `WorkspacePicker` is a one-line gate: `if (!visible) return`, otherwise it obtains `LocalWorkspacePickerRepository.current` or, when absent, the compatibility `koinInject<ConversationRepository>()` binding and delegates to a package-`internal` seam `WorkspacePickerInternal(repository, onPicked, onDismiss, modifier)`. The seam holds all behaviour:

```kotlin
val recents by repository
    .recentWorkspaces()
    .collectAsStateWithLifecycle(initialValue = emptyList())
var showCreateDialog by rememberSaveable { mutableStateOf(false) }
var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
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
            errorMessage = null
            scope.launch {
                try {
                    onPicked(repository.createWorkspaceFolder(name))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    errorMessage = CREATE_FOLDER_ERROR_MESSAGE
                }
            }
        },
        onDismiss = { showCreateDialog = false },
    )
}
errorMessage?.let { message ->
    AlertDialog(
        onDismissRequest = { errorMessage = null },
        title = { Text("Couldn't create folder") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
    )
}
```

Behaviour:

- **Tapping a recent row** → `onPick = onPicked` passes straight through. The sheet emits `onPick(path)` exactly when the user taps a row; no wrapping, no transformation.
- **Tapping "Create new folder under pyry-workspace…"** → `onCreateNew = { showCreateDialog = true }` flips the dialog flag. The `AlertDialog` composes into its own window above the `ModalBottomSheet`, so the two surfaces don't conflict visually.
- **Submitting the dialog** → `showCreateDialog = false` flips synchronously *before* launching the coroutine, so the dialog leaves composition and a second `onCreate` from the same dialog instance is structurally impossible. The launched job awaits `repository.createWorkspaceFolder(name)` inside a `try`/`catch` ([#564](../codebase/564.md)); on success it calls `onPicked(path)` with the returned path, on any non-cancellation failure it sets `errorMessage` instead (see § Error handling).
- **Cancelling the dialog (Cancel button, outside-tap, back-press)** → `onDismiss = { showCreateDialog = false }` flips only the dialog flag. The sheet stays visible. The host's outer `onDismiss` is NOT called.
- **Dismissing the sheet itself (close icon, scrim tap, drag-down, back-press while the dialog is not open)** → `onDismiss = onDismiss` invokes the consumer's outer callback. The consumer typically flips its `visible` flag to false; the host re-renders with `visible == false`, returns nothing, and the sheet's exit animation runs as `ModalBottomSheet` leaves composition.
- **`visible == false`** → `if (!visible) return` in the public composable means no Koin lookup, no flow subscription, no coroutine scope. Mounting the host as a permanent sibling of a screen body costs nothing when invisible.

### Internal testing seam

`WorkspacePickerInternal(repository, onPicked, onDismiss, modifier)` takes an
explicit repository for component tests. It is always visible; the public
composable owns the early return and repository resolution. Runtime consumers use
the public `WorkspacePicker`, so the route's ownership provider is respected.
Direct seam tests verify behavior but cannot prove production repository ownership.

### Repository ownership

`MainActivity` wraps host-owned thread destinations and (since
[#714](https://github.com/pyrycode/pyrycode-mobile/issues/714)) the Settings destination in
`HostWorkspaceRepository`, which provides `LocalWorkspacePickerRepository`. Thread pickers use the
route host; Settings' picker uses its own picker's captured owner,
`SettingsViewModel.workspacePickerServerId`, independent of subsequent compatibility selection
changes. The factory returns a reconnecting facade for that owner, or the existing fake singleton
in demo mode. See [DI ownership](dependency-injection-host-conversation-source.md#destination-ownership).

**The channel list stopped wrapping this picker in #904.** The former host row's long-press used to bind
`HostWorkspaceRepository(hostState.workspacePickerServerId, destinations)` around the whole screen
so this component's own repository lookup would agree with the picker target; #904 replaced that
control's destination with [`AddWorkspaceModal`](mobile-modal-callers.md#callers), which reads and writes
through `ChannelListViewModel`'s own `hostSource.repositoryFor(serverId)` call at the press instead
— and #1190 later removed that long-press entry. `Routes.CHANNEL_LIST` no longer wraps `ChannelListScreen` in `HostWorkspaceRepository`,
and nothing on that screen reads `LocalWorkspacePickerRepository` any more. See
[§ Consumers](#related) below and [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring).

Recents and `createWorkspaceFolder` must use the same owner as the caller's final
workspace change or discussion creation. Binding only the ViewModel leaves this
component's independent Koin lookup free to read/create folders on another host.
During disconnect, recents empty and creation keeps the existing generic error
UI; reconnect resumes the same host. **Every production picker host binds its
owner as of #714** — Settings was the last one still falling through to the
compatibility binding. The nullable local's `?: koinInject<ConversationRepository>()`
fallback stays only for previews and for component tests (`WorkspacePickerTest`)
that compose the picker with no provider; it is no longer reachable from any
production screen.

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

- **`recentWorkspaces()` failure modes — resolved live in [#565](../codebase/565.md).** The remote implementation's cold `flow { }` wraps its request/decode in `.catch { emit(emptyList()) }`, so every failure (not-connected, server error, malformed reply) degrades to an empty emission at the data layer — `collectAsStateWithLifecycle` never sees a terminal error to begin with, and the host needs no `catch { }` operator or error state of its own. The Recent section simply renders empty on any wire/connection failure, same as an empty registry. See [Remote conversation repository § `recentWorkspaces()`](remote-conversation-repository-workspace-and-push.md#recentworkspaces--the-fourth-read-verb-leanest-of-the-family-no-fold-565).
- **`createWorkspaceFolder` failure modes — the Tier-1 crash fix ([#564](../codebase/564.md)).** Before #564, tapping "Create new folder" against the real relay crashed the app: the launch block had no `try`/`catch`, so any throw from `repository.createWorkspaceFolder` — not-connected `IllegalStateException`, server `RelayErrorException`, or a decode exception — propagated uncaught into the coroutine. Because this host is shared by all three consumers (thread workspace flow, settings default-workspace row, FAB long-press), the crash reproduced identically from all three (Tier 1, 2026-07-03 backend-gaps audit). The fix wraps the launch:
  - `catch (CancellationException) { throw it }` **first** — a dismissed sheet cancelling the coroutine must propagate normally, not be mis-read as a failure. JVM `CancellationException extends IllegalStateException`, so an ISE-only or broad-`Exception`-only catch placed first would swallow it (see [[catch-illegalstate-swallows-cancellation]]).
  - `catch (Exception) { errorMessage = CREATE_FOLDER_ERROR_MESSAGE }` — every other throw sets a **fixed generic literal** (never the server's `RelayErrorException.message`, never the attempted name/path — the message must not leak untrusted or server-authored text). Rendered as an `AlertDialog` ("Couldn't create folder" / OK) layered above the still-visible `ModalBottomSheet` (`onPicked` was never called, so the sheet doesn't close).
  - `IllegalArgumentException` from blank-name validation is still structurally unreachable from the shipped dialog (Create stays disabled while the trimmed name is blank — see [`CreateFolderDialog`](./create-folder-dialog.md) § Internal state) but is now also caught by the broad `catch (Exception)` rather than crashing, belt-and-suspenders.
  - Deliberately **not** [`GuardedRepoLaunch`](./guarded-repo-launch.md) (#490) — that guard silently swallows, which fails "user-visible message." This is a bespoke catch precisely because the failure must be seen, not hidden.
- **Coroutine cancellation:** the in-flight job is cancelled if the host leaves composition mid-write (see § Cancellation behaviour on consumer-driven dismiss mid-write above); the `CancellationException` catch above rethrows it, so no callback and no `errorMessage` fire — cancellation stays silent by design, distinct from a real failure.

## Configuration

- **No new dependencies.** `koinInject`, `collectAsStateWithLifecycle`, `rememberCoroutineScope`, `rememberSaveable`, `launch` all ship in the existing `org.koin:koin-androidx-compose` / `androidx.lifecycle:lifecycle-runtime-compose` / `androidx.compose.runtime` / `kotlinx.coroutines` artifacts. [#564](../codebase/564.md) added `AlertDialog`/`Text`/`TextButton` (already-used `androidx.compose.material3` types) and `kotlinx.coroutines.CancellationException` — no new Gradle artifact either. No `gradle/libs.versions.toml` edit, no `app/build.gradle.kts` edit.
- **Repository resolution:** `LocalWorkspacePickerRepository` carries the captured route/picker owner. Without a provider, the public host uses the build-selected compatibility `ConversationRepository` binding. The internal component-test seam takes its repository explicitly.
- **No previews.** The host's behaviour is sequencing, not pixels — the two children carry their own previews ([`WorkspacePickerSheet`](./workspace-picker-sheet.md): two; [`CreateFolderDialog`](./create-folder-dialog.md): three). Adding a host preview would require a fake-repo wrap and would duplicate what the children's previews already show.

## Usage

Consumer screens keep the `visible`, `onPicked` and `onDismiss` contract:

```kotlin
WorkspacePicker(
    visible = pickerVisible,
    onPicked = { path -> onEvent(SomeEvent.WorkspacePicked(path)) },
    onDismiss = { onEvent(SomeEvent.WorkspacePickerDismissed) },
)
```

**Retired (#904).** The channel list used to be this shape's own example: it adapted
`LongPressFab` (later the host row's own long-press control) to
`openHostWorkspacePicker(capturedServerId)`, projected the captured owner's presence into
flat-screen visibility, and sent pick/dismiss to `pickHostWorkspace` / `dismissHostWorkspacePicker`,
also supplying that owner through the composition local. #904 replaced that whole path with
[`AddWorkspaceModal`](mobile-modal-callers.md#callers), bound to `ChannelListViewModel`'s own
`AddWorkspaceState` — see [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring) for the
state machine that replaced it. `ChannelListScreen` is no longer a consumer of this component;
Settings' own `SettingsViewModel.workspacePickerServerId` triple (below) is the current example of
the host-scoped shape this component expects from a caller.

Two important conventions established by the first consumer (#221) that future consumers should follow:

- **Three event variants** — open (a gesture), confirm-with-payload (a picked path), and cancel (a
  dismissal). The "dismissed without picking" path is distinct from "picked a path" and must NOT
  trigger the side effect. Same three-variant shape as #78's `PromoteChannelRequested` /
  `PromoteConfirmed` / `PromoteCancelled`.
- **Clear the visibility flag *synchronously before* the suspend** in the confirm arm — so the sheet's exit animation starts immediately instead of waiting for the repository call to complete. Same discipline as #78's `confirmPromotion`.

The host is typically mounted as a sibling of the screen's `Scaffold` (not inside the Scaffold's content lambda) so the `ModalBottomSheet`'s window-level scrim doesn't conflate with the body layout. Mounting it permanently is cheap — when `visible = false` it does no work (the public composable's `if (!visible) return` runs *before* the Koin lookup, `remember`, or coroutine scope).

## Tests

`WorkspacePickerTest` uses the explicit-repository internal seam for component
behavior with a real fake repository or a throwing delegate. Production ownership
is covered separately by `LiteralScreenNavigationTest`'s
`threadWorkspacePickerKeepsOwnerAcrossSelectionChanges`,
`flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges`, and (since
[#714](https://github.com/pyrycode/pyrycode-mobile/issues/714))
`SettingsNavigationTest`'s
`settingsWorkspacePickerReadsCreatesAndStoresOnlyForItsOwnHost`. Those tests open
the real picker through the production graph, use distinct A/B recents, and assert
that B receives no picker reads/writes after selection changes — the Settings case
flips compatibility selection to B *while its sheet is open* and asserts B's peer
saw none of the picker verbs at all. The thread case also covers disconnected
creation failure and reconnect. A direct seam test alone would pass with the
wrong public injection — `SettingsNavigationTest` also carries a negative control
that substitutes an unbound `HostWorkspaceRepository(null, …)` to confirm the
Settings device test fails against that pre-#714 state.

- **`dialog_submit_calls_createWorkspaceFolder_exactly_once_and_forwards_returned_path_to_onPicked`** — tap "Create new folder under pyry-workspace…", type `"my-workspace"`, tap Create. Asserts `picked == listOf("pyry-workspace/my-workspace")` (exactly one invocation, exact path) **and** `repo.recentWorkspaces().first().first() == "pyry-workspace/my-workspace"` (the bump landed at position 0; re-asserts the repo contract from the host's vantage and validates end-to-end wiring).
- **`cancelling_create_dialog_keeps_sheet_visible_and_does_not_invoke_host_onDismiss`** — tap "Create new folder under pyry-workspace…", then tap Cancel. Asserts `dismissed == 0`, the sheet's `"Choose workspace"` title still displays, and the dialog's `"Create workspace"` title `assertDoesNotExist()`. This is the load-bearing test for the "two flag-flips that share the surface verb 'dismiss' route differently" contract.
- **`tapping_a_recent_row_invokes_onPicked_with_that_rows_path`** — tap a known seeded recent path (`"~/Workspace/pyrycode-mobile"` from the fake's default seed) and assert `picked == listOf("~/Workspace/pyrycode-mobile")`. The wiring is `onPick = onPicked` (one-line pass-through); the test locks the contract per AC #5c.
- **`dialog_submit_whenCreateWorkspaceFolderThrows_showsErrorMessage_andDoesNotInvokeOnPicked`** ([#564](../codebase/564.md)) — injects a repository whose `createWorkspaceFolder` throws `IllegalStateException`, built via Kotlin interface delegation (`object : ConversationRepository by FakeConversationRepository() { override fun createWorkspaceFolder(...) = throw ... }`) rather than subclassing, since `FakeConversationRepository` is `final`. Drives row → dialog → Create, `waitForIdle()`, then asserts the generic error message node **is displayed**, `onPicked` was **never** invoked, and tapping OK dismisses it. This is the crash-regression test — before #564 this exact sequence threw uncaught.

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

- Ticket notes: [`../codebase/220.md`](../codebase/220.md), [`../codebase/221.md`](../codebase/221.md) (first consumer — Channel List FAB long-press wires the host into [`ChannelListScreen`](./channel-list-screen.md) + [`ChannelListViewModel`](./channel-list-viewmodel.md)), [`../codebase/564.md`](../codebase/564.md) (wires `createWorkspaceFolder` live and fixes the Tier-1 crash — see § Error handling)
- Spec: `docs/specs/architecture/220-workspace-picker-host-composable.md`
- Parent: split from [#207](https://github.com/pyrycode/pyrycode-mobile/issues/207) (Workspace Picker host); itself split from [#143](https://github.com/pyrycode/pyrycode-mobile/issues/143).
- Children: [`WorkspacePickerSheet`](./workspace-picker-sheet.md) (#212), [`CreateFolderDialog`](./create-folder-dialog.md) (#213).
- Upstream data surfaces: [`recentWorkspaces()`](./conversation-repository.md) from [#209](../codebase/209.md), [`createWorkspaceFolder(name)`](./conversation-repository.md) from [#210](../codebase/210.md).
- Sibling stateful host (different shape — not a composable host, but the same hoisted-`visible: Boolean` UiState convention): [`DiscussionListViewModel`](./discussion-list-viewmodel.md)'s `pendingPromotion`, [`SaveAsChannelDialog`](./channel-list-screen.md) visibility from #142.

### Consumers

- **Channel List FAB long-press (#221 — shipped, retired #904)** — [`ChannelListScreen`](./channel-list-screen.md) + [`ChannelListViewModel`](./channel-list-viewmodel.md). The first consumer: long-press emitted `LongPressFab`, set `workspacePickerVisible = true`; `onPicked(path)` ran `repository.createDiscussion(workspace = path)` then navigated to the new thread. The button itself retired in #738; the host row's own long-press carried the wiring forward (renamed to `openHostWorkspacePicker`) until #904 replaced the whole path with [`AddWorkspaceModal`](mobile-modal-callers.md#callers) — see [ChannelListViewModel § Wiring](channel-list-viewmodel.md#wiring). The channel list is no longer a consumer of this component.
- **Settings "Default workspace" row ([#235](../codebase/235.md) — shipped, host-scoped by [#714](https://github.com/pyrycode/pyrycode-mobile/issues/714))** — [`SettingsScreen`](./settings-screen.md) + [`SettingsViewModel`](./settings-viewmodel.md). The **first consumer outside the `ui/conversations` package**, and (until #714) the first to hoist the `visible` flag to a `ViewModel` `StateFlow<Boolean>` (`workspacePickerVisible`) rather than deriving it from a screen `UiState`. #714 replaced that flag with `workspacePickerServerId: StateFlow<String?>` — the host the open picker reads and writes for, or `null` while closed — following the retired flat channel screen's `hostState.workspacePickerServerId` shape (#904 replaced that shape on the channel list with `AddWorkspaceState`; this row's own field is unaffected) and letting the Settings route key `HostWorkspaceRepository` off the same nullable it derives the sheet's visibility from, so an open sheet can no longer be bound to the compatibility repository. Tapping the row calls `onDefaultWorkspaceTapped()`, which sets the pending owner to the destination's captured `ownerServerId` (a blank owner opens nothing); `onPicked(path)` → `onSelectDefaultWorkspace(path)` reads and clears the pending owner, then fire-and-forget persists via `appPreferences.setDefaultWorkspace(ownerServerId, path)`; `onDismiss` → `onWorkspacePickerDismissed()` clears the pending owner without persisting (AC4). Same open/pick/dismiss triple shape as `ThreadViewModel`'s picker host. The row's supporting text reflects the persisted default via a local `workspaceLabel(cwd)` helper (sentinel → "scratch", real path → trailing folder name).
- **Empty-thread workspace chip ([#137](https://github.com/pyrycode/pyrycode-mobile/issues/137))** — the chip's tap opens this host.
- **Thread overflow "Change workspace…" ([#208](https://github.com/pyrycode/pyrycode-mobile/issues/208))** — the menu item's tap opens this host.

### Downstream / open

- **Animated-close coordination** — a `LaunchedEffect` driving `sheetState.hide()` before `onDismiss` if a future ticket demands the exit-animation completion before the consumer's state flip.
- **Literal-strings localisation** — out of scope; deferred to the first `strings.xml` pass alongside the children's literals. `CREATE_FOLDER_ERROR_MESSAGE` and the `AlertDialog`'s title/OK strings ([#564](../codebase/564.md)) are inline literals in `WorkspacePicker.kt`, same posture as the rest of the file.
- **Recent-workspaces population — shipped ([#565](../codebase/565.md)).** `recentWorkspaces()` is now live (see [Remote conversation repository § `recentWorkspaces()`](remote-conversation-repository-workspace-and-push.md#recentworkspaces--the-fourth-read-verb-leanest-of-the-family-no-fold-565)); a folder created via [#564](../codebase/564.md) appears in Recent on the picker's next open (re-fetch per collection, no live push — #888 is one-shot daemon-side).
- **Failure-state pixel fidelity** — the generic `AlertDialog` in [#564](../codebase/564.md) has no Figma design (Figma `19:44` covers only the input dialog); testable-today behaviour is in scope, pixel fidelity is deferred.

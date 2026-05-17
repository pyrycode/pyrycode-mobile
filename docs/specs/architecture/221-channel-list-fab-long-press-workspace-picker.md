# 221 — Channel List FAB long-press → `WorkspacePicker`

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:52-62` — current `ChannelListEvent` sealed interface; add two new variants here (`LongPressFab`, `WorkspacePicked(path)`, `WorkspacePickerDismissed`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:99-110` — current `floatingActionButton` slot; wrap the existing `FloatingActionButton` in a `Box` with `Modifier.combinedClickable(onClick, onLongClick)` (see § FAB long-press wiring for the precise shape and why the inner FAB's `onClick` must be neutralised).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:111-161` — current Scaffold body; the `WorkspacePicker` host call goes here at the bottom of the lambda (after the `when (state)` block), reading the visibility flag off whichever non-`Loading`/`Error` state is active.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:24-49` — current `ChannelListUiState` sealed interface + `ChannelListNavigation`. Add the new `workspacePickerVisible: Boolean = false` field to `Loaded` and `Empty` with default `false` so the existing 26 `Loaded(...)`/`Empty(...)` call sites (previews, tests, the VM's own combine) keep working unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:54-112` — current `state` assembly: a 3-way `combine(channelsFlow, discussionsFlow, lastMessagesFlow)`. Replace with the 4-way variant adding a private `pendingWorkspacePicker: MutableStateFlow<Boolean>` arm — same shape as `pendingPromotion` in `DiscussionListViewModel` (see `docs/specs/architecture/78-promote-discussion-confirmation-dialog.md`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:117-129` — current `onEvent` `when` block. Add three arms (`LongPressFab`, `WorkspacePicked`, `WorkspacePickerDismissed`); the existing `CreateDiscussionTapped` arm is the model for the new `WorkspacePicked(path)` arm.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt` (full, 67 lines) — the host being consumed. Note the contract: `WorkspacePicker(visible, onPicked, onDismiss, modifier)`; on `visible == false` it returns nothing, on `visible == true` it composes the picker sheet + create-folder dialog. `onPicked(path)` fires for BOTH "tapped a recent row" AND "created a new folder and got back a path"; the consumer can't tell them apart (intentional per #220's design).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:137-163` — current `composable(Routes.CHANNEL_LIST)` block. The `onEvent` `when` gets new arms forwarding `LongPressFab`, `WorkspacePicked`, and `WorkspacePickerDismissed` to `vm.onEvent`. The existing `ChannelListNavigation.ToThread → navController.navigate(...)` collector (lines 140-147) is untouched — the new flow reuses it.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:32` — `suspend fun createDiscussion(workspace: String? = null): Conversation`. We call this with the picked path: `repository.createDiscussion(workspace = path)`. No interface change.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:91-118` — fake `createDiscussion` sets `cwd = workspace ?: ""`. Tests assert on `cwd == "/some/path"` for the picked-workspace test.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:300-361` — the two prior create-flow tests (`createDiscussionTapped_createsOneUnpromotedConversation`, `createDiscussionTapped_emitsToThreadNavigationWithCreatedId`). Mirror their shape exactly for the new `WorkspacePicked` test — `FakeConversationRepository()` (not the `stubRepo` helper, which returns `TODO("not used")` for `createDiscussion`), `async { vm.navigationEvents.first() }`, `advanceUntilIdle()`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:30-45` — project precedent for `combinedClickable`. The pattern here applies the modifier on the outer `ListItem`; we use the same shape on the outer `Box` wrapping the FAB.
- `docs/specs/architecture/22-channel-list-fab-new-discussion.md` — the parent ticket. § 4 (FAB design), § 5 (MainActivity wiring), and § "State + concurrency model" are the load-bearing references; we extend the same Channel/`navigationEvents` plumbing.
- `docs/specs/architecture/78-promote-discussion-confirmation-dialog.md` — the `combine(upstream, MutableStateFlow<Visibility>)` arm pattern this ticket re-uses, including the "clear visibility BEFORE the suspend point" discipline (§ "ViewModel — combine upstream with a private `pendingPromotion` flow", confirmPromotion bullet).
- `app/src/main/res/values/strings.xml` — add the long-press accessibility hint string here (see § Strings).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

The Channel List FAB's visual treatment is unchanged from #22 — the same M3 `FloatingActionButton` with `Icons.Default.Add` lives bottom-right at 16dp margins, primary-container fill, the standard 56dp surface (Figma node `15:106`). Long-press is a gesture, not a separate visual surface; the picker that opens on long-press is the `WorkspacePicker` host's bottom sheet (Figma `20:2`, already implemented by #212/#220), which this ticket consumes unchanged. No pixel work in this ticket.

## Context

#22 shipped the FAB with tap → create-scratch-discussion → navigate. #22 explicitly de-scoped FAB long-press → workspace picker as "Phase 2." #220 then landed the reusable `WorkspacePicker` host composable (Koin-injected `ConversationRepository`, recents flow, create-folder sequencing, single-call guarantees on `createWorkspaceFolder`). This ticket is the first concrete consumer of that host.

The new behaviour: long-press the FAB → bottom sheet opens with recent workspaces + a "Create new folder" affordance → user picks a recent row OR creates a folder → the new discussion is created with `workspace = pickedPath` (instead of the scratch default) → navigate to its thread. Tap-to-create-scratch behaviour from #22 is preserved verbatim.

The picker is a transient, modal sheet — visibility belongs in `ChannelListUiState`, not in a `MutableState` hoisted to the screen (per ticket Technical Notes). The combine-arm pattern from `DiscussionListViewModel.pendingPromotion` (#78) is the established shape.

## Design

### 1. `ChannelListEvent` — three new variants

In `ChannelListScreen.kt:52-62`, extend the sealed interface:

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
    data object CreateDiscussionTapped : ChannelListEvent
    data object RecentDiscussionsTapped : ChannelListEvent
    data object LongPressFab : ChannelListEvent                          // NEW
    data class WorkspacePicked(val workspace: String) : ChannelListEvent // NEW
    data object WorkspacePickerDismissed : ChannelListEvent              // NEW
}
```

Three variants (not two) because the "user dismissed the sheet without picking" path is distinct from "user picked a path" and must NOT trigger discussion creation. Mirrors #78's split into `PromoteConfirmed` / `PromoteCancelled` — same shape, same rationale.

### 2. `ChannelListUiState` — `workspacePickerVisible` on `Loaded` and `Empty`

Add a single `Boolean` field to the two states that can show the picker. Default `false` to preserve all existing positional/named call sites:

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Empty(
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,           // NEW
    ) : ChannelListUiState
    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussions: List<Conversation>,
        val recentDiscussionsCount: Int,
        val recentDiscussionLastMessages: Map<String, Message> = emptyMap(),
        val workspacePickerVisible: Boolean = false,           // NEW
    ) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}
```

Why not on `Error`/`Loading`: the FAB renders only in `Loaded`/`Empty` (see `ChannelListScreen.kt:100`), so the picker can only be reached from those states. If a flow error fires while the picker is open, the upstream's `.catch` collapses state to `Error` and the picker disappears (the same edge-case discipline as #78's "upstream emits empty while dialog open" — the `Loaded → Error` transition removes the field entirely; on the next legitimate `Loaded` emission, `workspacePickerVisible` projects from `pendingWorkspacePicker.value` which the VM keeps coherent — see § 3).

`workspacePickerVisible` is intentionally lifted out of a nullable wrapper (no `pendingPick: PendingWorkspacePick?` analog of `PendingPromotion`). Unlike the promotion dialog, the picker carries no per-target payload — there's no "which discussion are we promoting" to remember. A bare `Boolean` is the minimum sufficient surface.

### 3. `ChannelListViewModel` — private `pendingWorkspacePicker` + 4-way combine

Add the private flow and extend the existing 3-way `combine` to 4-way:

```kotlin
private val pendingWorkspacePicker = MutableStateFlow(false)
```

The `state` flow assembly's `combine(channelsFlow, discussionsFlow, lastMessagesFlow)` becomes `combine(channelsFlow, discussionsFlow, lastMessagesFlow, pendingWorkspacePicker)`. The transform lambda gains a fourth parameter `pickerVisible` and passes it through to `workspacePickerVisible` on both `Loaded` and `Empty`. The 4-arity `combine` overload exists (kotlinx.coroutines ships `combine` overloads up to 5 args).

`Error` branch: the existing `.catch { e -> emit(Error(...)) }` is unchanged — `Error` carries no `workspacePickerVisible` field. If a downstream error occurs while the picker is open, the next `Error` emission supersedes everything; the picker visibility is lost from the state stream. The VM's internal `pendingWorkspacePicker.value` retains its prior `true`, but it is unobservable — there is no consumer state that exposes it. When upstream recovers (`Loaded` emits again), the picker reappears. This is acceptable Phase 0 behaviour: Phase 4 may revisit if `Error` becomes a routine transient state.

### 4. `onEvent` — three new arms

Add three arms to the existing `when (event)` block; the existing `CreateDiscussionTapped` arm and the `Unit` arm covering `RowTapped`/`SettingsTapped`/`RecentDiscussionsTapped` are unchanged.

- **`ChannelListEvent.LongPressFab`** → `pendingWorkspacePicker.value = true`. One line.
- **`is ChannelListEvent.WorkspacePicked`** → set `pendingWorkspacePicker.value = false` *first*, then `viewModelScope.launch { val c = repository.createDiscussion(workspace = event.workspace); navigationChannel.send(ChannelListNavigation.ToThread(c.id)) }`. Same shape as the existing `CreateDiscussionTapped` arm but with the picked workspace passed as the `workspace` argument and with a synchronous flag-clear before the suspend.
- **`ChannelListEvent.WorkspacePickerDismissed`** → `pendingWorkspacePicker.value = false`. One line.

Three design decisions to call out:

- **`WorkspacePicked` clears visibility BEFORE launching the suspend.** Same discipline as #78's `confirmPromotion`. Two reasons: (a) the sheet's exit animation starts immediately as the state's `workspacePickerVisible` flips to `false`, instead of waiting for `createDiscussion` to complete; (b) a hypothetical second `onPicked` invocation from a not-yet-dismissed sheet cannot launch a second `createDiscussion` because the host's sheet has already been told to dismiss (host's own internal flag cycles `visible: true → false`).
- **`createDiscussion(workspace = event.workspace)` reuses #22's plumbing.** Same `navigationChannel.send(ToThread(conversation.id))` afterwards. No new navigation type; no new channel. The returned `Conversation.id` is the navigation target.
- **`pendingWorkspacePicker.value = false` is the dismiss action — not the picker's responsibility.** The host's `onDismiss` callback fires when the user dismisses the sheet (close icon, scrim tap, drag-down, back-press). The screen forwards this to `WorkspacePickerDismissed`; the VM clears its own flag. The flag flip causes the state to re-emit with `workspacePickerVisible = false`, which causes the host to re-compose with `visible = false`, which returns nothing, which lets the M3 `ModalBottomSheet` run its exit animation as it leaves composition. This is the documented `WorkspacePicker` contract (#220 § "AC #2g").

### 5. `ChannelListScreen` — FAB long-press wiring

The current FAB:

```kotlin
FloatingActionButton(onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) }) {
    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_new_discussion))
}
```

Replace with an outer `Box` that owns the gesture, and a `FloatingActionButton` inside whose own `onClick` is neutralised:

```kotlin
val longPressLabel = stringResource(R.string.cd_long_press_fab_pick_workspace)
Box(
    modifier = Modifier.combinedClickable(
        onClick = { onEvent(ChannelListEvent.CreateDiscussionTapped) },
        onLongClick = { onEvent(ChannelListEvent.LongPressFab) },
        onClickLabel = stringResource(R.string.cd_new_discussion),
        onLongClickLabel = longPressLabel,
        role = Role.Button,
    ),
) {
    FloatingActionButton(onClick = {}) {                            // gesture handled by Box
        Icon(Icons.Default.Add, contentDescription = null)          // label moved to outer Box
    }
}
```

Why the outer-`Box` shape (not `combinedClickable` directly on the FAB):

- **M3's `FloatingActionButton` already attaches a `Surface(onClick = ...)` internally.** That Surface installs its own `clickable` modifier on a leaf composable. Wiring a second `combinedClickable` onto the FAB's outer `modifier` parameter sets up two pointer-input regions competing for the same gestures — the outer modifier captures first, but the inner Surface's `clickable` is still attached and can announce a duplicate accessibility role. The outer `Box` shape isolates the gesture surface entirely: the inner FAB's `onClick = {}` is a no-op that never fires (the outer Box consumes both tap and long-press before propagation reaches the inner Surface).
- **The inner FAB still renders its visual treatment** — same colors, same elevation, same 56dp surface, same ripple on press. The outer `Box` does not request its own `indication`; press feedback comes from the inner Surface's own indication chain via `LocalIndication.current`, which still plays because the press is *also* observed at the FAB's hit region.
- **Accessibility:** the outer `Box` owns the semantic role (`Role.Button`), the tap label (`onClickLabel`), and the long-press label (`onLongClickLabel`). The inner `Icon`'s `contentDescription = null` prevents TalkBack from announcing the icon a second time. TalkBack reads the FAB as "New discussion, button, double-tap to activate, long-press to pick workspace" — the long-press hint comes from `onLongClickLabel`.
- **Codebase precedent:** `ConversationRow.kt:40-45` applies `combinedClickable` on the outer container modifier rather than on an inner M3 component, for the same reason. We mirror that pattern.

The FAB's surrounding `if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty)` guard from #22 (line 100) is unchanged — long-press is still gated on the FAB being visible.

### 6. `ChannelListScreen` — host the `WorkspacePicker`

After the `Scaffold { ... }` block closes (i.e. at the end of the `ChannelListScreen` composable body, NOT inside the Scaffold's content lambda), add:

```kotlin
val pickerVisible = when (state) {
    is ChannelListUiState.Loaded -> state.workspacePickerVisible
    is ChannelListUiState.Empty -> state.workspacePickerVisible
    ChannelListUiState.Loading, is ChannelListUiState.Error -> false
}
WorkspacePicker(
    visible = pickerVisible,
    onPicked = { path -> onEvent(ChannelListEvent.WorkspacePicked(path)) },
    onDismiss = { onEvent(ChannelListEvent.WorkspacePickerDismissed) },
)
```

Three notes:

- **`WorkspacePicker` is composed alongside the Scaffold, not inside it.** The host wraps `ModalBottomSheet`, which manages its own `Popup`/`Window` and renders above the entire activity. Composing it inside the Scaffold's content slot works but conflates the bottom-sheet's window-level scrim with the body's layout, which is awkward when the screen's body is a `LazyColumn`. Placing the host as a sibling to the Scaffold (under the same outer composable) keeps the responsibilities clear and matches the same shape as #78's dialog rendering (also outside the Scaffold body — see #78 § "Composable — render the dialog as an overlay").
- **The `when (state)` block exhausts the sealed `ChannelListUiState`.** Loading and Error are explicitly mapped to `false` — the host renders nothing in those states (it would also return early on `visible = false`, but mapping explicitly removes ambiguity for the reader).
- **`onPicked(path)`** is forwarded verbatim. The host treats "recent row tap" and "create-folder-and-got-back-path" identically per #220's design (consumer cannot distinguish). The VM then dispatches to `WorkspacePicked(path)` which always creates a discussion at `path` regardless of origin. Correct: if a user creates `pyry-workspace/new-thing` and immediately gets a new discussion in that folder, that's the desired UX.

### 7. `MainActivity` — forward three new events

In the `composable(Routes.CHANNEL_LIST)` block at `MainActivity.kt:148-162`, extend the `onEvent` `when` to forward the three new variants to the VM:

```kotlin
when (event) {
    is ChannelListEvent.RowTapped ->
        navController.navigate("conversation_thread/${event.conversationId}")
    ChannelListEvent.SettingsTapped ->
        navController.navigate(Routes.SETTINGS)
    ChannelListEvent.RecentDiscussionsTapped ->
        navController.navigate(Routes.DISCUSSION_LIST)
    ChannelListEvent.CreateDiscussionTapped,
    ChannelListEvent.LongPressFab,
    is ChannelListEvent.WorkspacePicked,
    ChannelListEvent.WorkspacePickerDismissed ->
        vm.onEvent(event)
}
```

The existing `navigationEvents` collector at lines 140-147 is unchanged — `WorkspacePicked → createDiscussion → ToThread` emits onto the same channel as #22's tap path, so the same `navController.navigate(...)` arm handles both.

### 8. Strings

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="cd_long_press_fab_pick_workspace">Pick a workspace for the new discussion</string>
```

The `cd_` prefix matches the existing accessibility-string convention (`cd_open_settings`, `cd_new_discussion`, etc.). One new string; the `cd_new_discussion` tap label is reused.

### 9. Previews

The existing four `ChannelListScreen*Preview` composables (lines 310-414) construct `Loaded(...)` and `Empty(...)` with named-arg syntax. The new `workspacePickerVisible: Boolean = false` field defaults preserve all four previews — no edits required. An optional fifth preview with `workspacePickerVisible = true` is NOT requested; the picker visual is already covered by `WorkspacePickerSheet`'s own previews from #212.

## State + concurrency model

- **`pendingWorkspacePicker: MutableStateFlow<Boolean>`** — private to the VM, single writer (the VM's `onEvent` handler), lifecycle-bound to `viewModelScope`. The UI reads only via the combined `state` flow's projection.
- **4-way `combine`** — adds one upstream to the existing 3-way `combine`. The fourth flow (`pendingWorkspacePicker`) has an initial value (`false`) so combine doesn't suspend on it; the projection emits as soon as the three data flows have produced their first values. Loading semantics from #22 are preserved.
- **`viewModelScope.launch { repository.createDiscussion(workspace = path) }`** — fire-and-forget. Same shape as #22's `CreateDiscussionTapped`. The `Channel<ChannelListNavigation>(Channel.BUFFERED)` retains buffering semantics (#22 § "State + concurrency model"); the buffered slot tolerates the one-tap-per-pick burst even if the screen is mid-recomposition.
- **`pendingWorkspacePicker.value = false` BEFORE the suspend** — see § 4. This is the single-tap-creates-one-discussion invariant. A double-tap on the picker's recent row would normally fire `onPicked(path)` twice, but the host's own internal flag cycles `visible: true → false` synchronously when the consumer flips its state, so the M3 sheet's secondary click is consumed by the dismiss animation, not the row. (This is the host's own contract — see #220 § "Single-invocation guarantee".)
- **Dispatcher** — Main. `repository.createDiscussion` is `suspend` but Phase 0's fake is non-blocking; no dispatcher hop.
- **Cancellation** — if the user navigates away mid-`createDiscussion`, `viewModelScope` is cancelled when the ViewModel is cleared. The launch aborts; no partial state. Same as #22.
- **`rememberSaveable` on the visibility flag?** No. The flag lives in the VM, not in Compose state. `viewModelScope` survives configuration changes (rotation) by default, and `MutableStateFlow` value retention is automatic. A user who long-presses, rotates the device, and lands on the same screen finds the picker still open. Correct.

## Error handling

- **`repository.createDiscussion(workspace = path)` failure** — out of scope per #22's same decision. The fake never throws; Phase 4's real implementation gains error-state surfacing in a future ticket. Do not wrap in `try/catch` here.
- **Picker dismissal mid-`createDiscussion`** — unreachable in Phase 0. The VM clears `pendingWorkspacePicker` *before* the suspend; once the suspend returns, navigation fires. The user cannot dismiss "during" — there's no async user input between flag-clear and launch-completion.
- **Stale `WorkspacePicked(path)` after a downstream error** — extreme edge: upstream errors while the user holds the sheet open, then the user picks. The picker's host doesn't know about the error; it fires `onPicked(path)`. The VM's `WorkspacePicked` arm runs unconditionally — it tries to create the discussion. The fake succeeds; the new discussion appears in the next `Loaded` emission; the state recovers. Phase 4: the real implementation's `createDiscussion` may itself fail and propagate; that's the same Phase 4 ticket as the previous bullet.

## Testing strategy

Unit tests in `ChannelListViewModelTest.kt`, mirroring the existing `createDiscussionTapped_*` tests (see § "Files to read first" pointer to lines 300-361 for shape). Use `FakeConversationRepository()` directly — the file's `stubRepo` helper returns `TODO("not used")` for `createDiscussion`, so it cannot back the new tests.

Three new test cases (AC #5):

- **`longPressFab_setsWorkspacePickerVisibleToTrue`** (AC #5a)
  - Setup: `stubRepo` with `MutableSharedFlow` channels + discussions; emit `Loaded`-shaping data; collect `vm.state` to hold `WhileSubscribed` hot.
  - Acts: `vm.onEvent(ChannelListEvent.LongPressFab)`, `advanceUntilIdle()`.
  - Asserts: the resulting `state.value` is `Loaded` (or `Empty` depending on channels emission) AND its `workspacePickerVisible == true`.
  - Why the stub-shaped test (not `FakeConversationRepository`): we want deterministic control over the `Loaded`/`Empty` branch and `LongPressFab` itself does no repository work. The stub keeps the test focused.

- **`workspacePicked_createsDiscussionWithPickedWorkspace_emitsNavigation_andClearsVisibility`** (AC #5b)
  - Setup: `FakeConversationRepository()` (production fake; we need `createDiscussion` to actually run); snapshot `repository.observeConversations(ConversationFilter.Discussions).first()` as `before`; `val vm = ChannelListViewModel(repository)`.
  - Acts: pre-arrange `vm.onEvent(ChannelListEvent.LongPressFab)` to set visibility to true so the clear-visibility assertion is meaningful; `val deferredEvent = async { vm.navigationEvents.first() }`; `vm.onEvent(ChannelListEvent.WorkspacePicked("pyry-workspace/my-folder"))`; `advanceUntilIdle()`.
  - Asserts:
    1. `after.size == before.size + 1` (one new discussion).
    2. The new discussion (`after - before` by id, `.single()`) has `cwd == "pyry-workspace/my-folder"` (the fake sets `cwd = workspace ?: ""` — see `FakeConversationRepository.kt:108`).
    3. `deferredEvent.await()` is `ChannelListNavigation.ToThread(newId)` where `newId` matches the new discussion's id.
    4. The current `vm.state.value.workspacePickerVisible == false` (cast to `Loaded` or `Empty` per the fake's channel seeds; the fake seeds at least one channel by default so `Loaded` is expected).
  - Note: collect `vm.state` (e.g. `val collector = launch { vm.state.collect { } }`) before triggering `LongPressFab` so the upstream `combine` is hot and the flag-flip reaches the projection.

- **`createDiscussionTapped_stillCreatesScratchDiscussionAndNavigates`** (AC #5c, regression of #22)
  - This test already exists at `ChannelListViewModelTest.kt:300-361` as two tests (`createDiscussionTapped_createsOneUnpromotedConversation` and `createDiscussionTapped_emitsToThreadNavigationWithCreatedId`). They cover the AC's regression requirement verbatim — do not duplicate them. Verify they continue to pass after the `Loaded`/`Empty` field addition (they should: named-arg construction with default `workspacePickerVisible = false` is structurally equivalent to the prior call sites).

A `workspacePickerDismissed_clearsVisibility` test is NOT required by AC but is the trivially-correct dual of `longPressFab_setsWorkspacePickerVisibleToTrue`. The developer may add it OR skip it; the architect's call is to skip — the VM's `WorkspacePickerDismissed` arm is a single line (`pendingWorkspacePicker.value = false`) whose behaviour is end-to-end exercised by the `WorkspacePicked` test (which also clears visibility) and by the existing #220 host tests that confirm `onDismiss` is invoked on sheet dismissal. Adding a third test for a one-liner has low marginal value.

### Compose UI tests — none

`ChannelListScreenTest.kt` exists in `app/src/androidTest/` but #22 chose not to add a UI test for the FAB. Mirroring that decision: this ticket adds no new androidTest. The `WorkspacePicker` host's androidTest from #220 already verifies the sheet renders and `onPicked`/`onDismiss` fire correctly; this ticket's screen-level wiring is a hoisted `visible` prop + an `onPicked`/`onDismiss` pass-through — trivially-correct conditional rendering that doesn't merit a separate UI test.

### `./gradlew` commands

- `./gradlew test` — runs the new + existing VM unit tests. Required to pass.
- `./gradlew connectedAndroidTest` — unaffected; no new instrumented tests.
- `./gradlew lint` — should remain clean.

## Open questions

None. All five AC items map to concrete decisions above.

Two design decisions worth flagging for code-review attention:

1. **Outer-`Box` FAB wrapper for `combinedClickable`** (§ 5). The "right" shape in Compose for "long-press a Material 3 component" is non-obvious; the alternative (applying `combinedClickable` directly to the FAB's `modifier`) has accessibility-duplication risk. Reviewer is invited to challenge if a Material 3 1.x release has since introduced a native `onLongClick` parameter on `FloatingActionButton` — current Compose BOM (per `gradle/libs.versions.toml`) does not expose one.
2. **No `rememberSaveable` on the visibility flag** (§ "State + concurrency model"). VM-owned state survives configuration change by default; introducing `rememberSaveable` would be redundant and contradicts the "single source of state per ViewModel" principle in `CLAUDE.md`. Flagging only to forestall the "shouldn't we persist it" review comment.

## Out of scope

- **Wiring the picker into other consumers.** `#137`'s empty-thread chip and `#208`'s thread overflow item are separate tickets that consume the same `WorkspacePicker` host — each adds its own `pendingWorkspacePicker` flag to its own screen's state.
- **A "this discussion was created in X workspace" hint on the new thread.** The created `Conversation`'s `cwd` is set; surfacing it visually on the thread screen is a separate UX concern.
- **Workspace validation.** The host (#220) trusts the dialog (#213) to validate non-blank folder names; the VM here trusts whatever `path` the host emits. No double-validation.
- **Debounce on rapid long-press.** No observed failure; Compose long-press is already debounced at the gesture-detector level.
- **Phase 4 error-state surfacing.** As with #22, out of scope until the real repository ships.

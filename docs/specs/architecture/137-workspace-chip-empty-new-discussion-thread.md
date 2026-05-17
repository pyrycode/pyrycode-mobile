# 137 — Workspace chip on empty new-discussion thread

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:17-72` — current `ThreadUiState` shape and the `combine`-less `state` flow that this ticket widens. Note the `displayName()` private extension at 70-72 — the existing display-name derivation pattern; the new `workspaceLabel` derivation lives next to it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:1-99` — the screen body with `ConnectionBanner` above an empty `LazyColumn(reverseLayout = true)`. The new chip slots between them inside the `Column`. Note the screen takes loose callbacks (no sealed `ThreadEvent`); add new callbacks alongside `onTitleClick` / `onOverflowClick`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:34-152` — the canonical wiring pattern: `pendingWorkspacePicker = MutableStateFlow(false)` combined into `state`, `WorkspacePickerVisible` field on the data class, three event handlers (open / picked / dismissed). Mirror this shape on `ThreadViewModel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:174-184` — the canonical render-call: `pickerVisible` derivation, `WorkspacePicker(visible, onPicked, onDismiss)` at screen-root as a sibling to `Scaffold`. The host's `ModalBottomSheet` lives in its own window, so source-order placement doesn't affect Z-order.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspacePicker.kt:1-67` — the host's public signature; what the chip's click target eventually invokes. Read once to confirm `onPicked(path: String)` and `onDismiss()` semantics; the consumer wires them as in #221.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:21-100` — the three repository surfaces this ticket reads: `observeConversations(All)` (for `isPromoted` + `cwd`), `observeMessages(id)` (for "has any `MessageItem`?"), and `changeWorkspace(id, workspace)` (write on pick). Note `changeWorkspace` mints a new `Session` and returns it — the ViewModel discards the return value.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — full file is ~22 lines. Note `isPromoted: Boolean`, `cwd: String`, and `DEFAULT_SCRATCH_CWD = "~/.pyrycode/scratch"` sentinel.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:91-118,187-197,274-277` — three regions: `createDiscussion` (line 108: `cwd = workspace ?: ""` — fresh discussions get **empty-string** `cwd`, not the scratch sentinel); `changeWorkspace` and `mintNewSession` (the write path the picker triggers); and `bumpWorkspace`'s no-bound-workspace filter at 274-277 (`cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD`). The chip's workspace-label derivation MUST match this same filter — both `""` and `DEFAULT_SCRATCH_CWD` render as `"scratch"`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:130-152` — `ThreadItem` sealed interface. The chip's "has-messages" gate counts only `ThreadItem.MessageItem`, not `ThreadItem.SessionBoundary`. A workspace-change boundary triggered by the chip itself appears in the stream first; the chip stays visible until a real `MessageItem` lands.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:33-271` — full file. New unit-test cases extend this class; mirror the `makeVm(handle, repository, source = …)` helper at line 213 and the `fixedRepo(...)` helper at line 231 for tests that need a stub repository. JUnit 4, `runTest`, `UnconfinedTestDispatcher`, no MockK.
- `docs/specs/architecture/220-workspace-picker-host-composable.md` — host's public contract, single-invocation guarantee, and the rationale for `onPicked` not distinguishing "recent tap" vs "newly-created folder." Consumer wiring is the same either way.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `16:8` is the **populated** Conversation Thread Screen — the canvas frame this ticket lives inside, but the rendered Figma shows messages and a session-change delimiter rather than an empty-state variant with the chip. There is no separate empty-state node drawn in Figma; the chip's styling is anchored by the ticket's "M3 `AssistChip` with leading folder icon" instruction rather than a pixel-exact reference. Visual placement: at the top of the thread body (between `ConnectionBanner` and the message list), padded inside the same `px = 16.dp` gutter the populated thread uses (per node `16:21` — `Message list` frame), with the chip start-aligned. No new design tokens introduced.

## Context

When a user creates a fresh discussion, the thread opens with zero messages. There is currently nothing on the empty thread that tells the user *which* workspace the next message will run in, and no inline path to change it before sending. The Channel List FAB long-press (`#221`) sets the workspace at creation time, and the Settings default-workspace wiring (`#240`) covers the short-press path — but once the user lands on the thread, neither affordance is reachable without backing out.

This ticket adds an `AssistChip` at the top of the thread body that:
1. Surfaces the discussion's current workspace as a basename label (`scratch` for unbound, otherwise the last path segment).
2. Routes tap → the existing `WorkspacePicker` host (`#220`) — same wiring pattern `#208` will use for the overflow "Change workspace…" entry point.
3. Disappears once any user/assistant message lands (per ticket Context: "design doc recommends disappear over read-only downgrade").

Channels are out of scope: a channel's workspace is fixed at promotion time, so the chip never renders for promoted conversations regardless of message presence.

This ticket lands ahead of `#208` (overflow → picker wiring) and `#138` (empty-state copy/visual). `#208`'s spec (open issue body, not yet written) commits to introducing `workspacePickerVisible` on `ThreadUiState` and the `WorkspacePicker` render call in `ThreadScreen`; this ticket introduces both, and `#208` will reuse them rather than re-add. `#138`'s empty-state copy renders **below** the chip in the same `Column`; the chip owns the topmost slot, `#138` owns whatever comes after.

## Design

### `ThreadUiState` widening

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
    val isPromoted: Boolean = false,
    val hasMessages: Boolean = false,
    val workspaceLabel: String = "scratch",
    val workspacePickerVisible: Boolean = false,
)
```

Defaults rationale:

- `isPromoted = false` — conservative. Until the conversation resolves, treat as discussion. Combined with `hasMessages = false` and `workspacePickerVisible = false`, the *initial* state (before any flow emission) renders the chip in its visible state with label `"scratch"`. This is the correct UX for the loading window of a brand-new discussion. For the wrong-default cases (channels resolving slowly), the first emission immediately corrects to `isPromoted = true`, which hides the chip.
- `hasMessages = false` — same conservative default. Initial render shows the chip; first emission corrects.
- `workspaceLabel = "scratch"` — string-typed (already derived). The ViewModel resolves the basename at the flow boundary. Composables never see the raw `cwd`.
- `workspacePickerVisible = false` — picker is closed until the user taps the chip. Establishes the field that `#208` will reuse.

### Workspace-label derivation

Same `cwd` → label rule as `bumpWorkspace`'s no-bound-workspace filter (line 274 of the fake):

```kotlin
private fun Conversation.workspaceLabel(): String =
    if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) {
        "scratch"
    } else {
        cwd.substringAfterLast('/').ifEmpty { cwd }
    }
```

Behavior summary (the asserting tests are listed in § Testing strategy):

- `cwd = ""` → `"scratch"` (matches the fresh-discussion empty-string state from `createDiscussion(null)`).
- `cwd = "~/.pyrycode/scratch"` → `"scratch"` (matches `DEFAULT_SCRATCH_CWD`).
- `cwd = "pyry-workspace/my-app"` → `"my-app"`.
- `cwd = "~/Workspace/Projects/X"` → `"X"`.
- `cwd = "X"` (no slash) → `"X"` (the `.ifEmpty { cwd }` guards against `substringAfterLast` returning empty on no-separator inputs).
- `cwd = "foo/"` (trailing slash) → `cwd` (the `.ifEmpty { cwd }` guard kicks in; acceptable degenerate-case behavior — fresh-discussion paths never have trailing slashes per `createWorkspaceFolder`'s `"pyry-workspace/$name"` shape).

Lives as a `private` extension at the bottom of `ThreadViewModel.kt`, beside the existing `displayName()` extension.

### `ThreadViewModel.state` flow widening

The current flow maps `observeConversations(All)`. Widen to a `combine` of three sources:

```kotlin
val state: StateFlow<ThreadUiState> =
    combine(
        repository.observeConversations(ConversationFilter.All),
        repository.observeMessages(conversationId),
        pendingWorkspacePicker,
    ) { conversations, items, pickerVisible ->
        val conv = conversations.firstOrNull { it.id == conversationId }
        ThreadUiState(
            conversationId = conversationId,
            displayName = conv?.displayName() ?: conversationId,
            isPromoted = conv?.isPromoted ?: false,
            hasMessages = items.any { it is ThreadItem.MessageItem },
            workspaceLabel = conv?.workspaceLabel() ?: "scratch",
            workspacePickerVisible = pickerVisible,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThreadUiState(
            conversationId = conversationId,
            displayName = conversationId,
        ),
    )
```

`pendingWorkspacePicker` is a private field:

```kotlin
private val pendingWorkspacePicker = MutableStateFlow(false)
```

Notes:

- The `WhileSubscribed(5_000)` keep-alive stays. The 5-second teardown survives configuration changes (per the existing convention).
- The `initialValue` keeps `conversationId` as the `displayName` fallback to preserve the contract asserted by `ThreadViewModelTest.state_initialValue_isConversationIdPlaceholderBeforeSubscription`. All other fields fall back to the data-class defaults defined above.
- `combine` recomposes any time any upstream emits; `observeMessages` re-emits on `sendMessage` (the `hasMessages` flip happens here) and on `changeWorkspace` (a new `SessionBoundary` arrives; `hasMessages` stays false because boundaries don't count).
- Conversation-missing edge case: `conv` is `null` → `isPromoted = false` (treated as discussion), `workspaceLabel = "scratch"` (safe default). Matches the existing `displayName` fallback to `conversationId` at line 37.

### Event handlers on `ThreadViewModel`

Three new methods, mirroring `ChannelListViewModel.onEvent` arms:

```kotlin
fun onWorkspaceChipTapped() { … }       // sets pendingWorkspacePicker.value = true
fun onWorkspacePicked(path: String) { … }  // clears flag, calls repository.changeWorkspace(id, path) on viewModelScope
fun onWorkspacePickerDismissed() { … }   // clears pendingWorkspacePicker.value = false
```

Signature and behavior contracts (test asserts the invariants, body is straightforward):

- `onWorkspaceChipTapped()` — synchronous flag flip. No coroutine, no repository call. Idempotent (calling twice while picker is open is a no-op since the flag is already true).
- `onWorkspacePicked(path: String)` — flag flip first (`pendingWorkspacePicker.value = false`), then `viewModelScope.launch { repository.changeWorkspace(conversationId, path) }`. The picker host's `onPicked` guarantees exactly-once invocation per submit (see #220 spec § Single-invocation guarantee); we do not re-debounce. The `changeWorkspace` return value (`Session`) is discarded — the conversation's `cwd` update propagates via the `observeConversations` re-emission.
- `onWorkspacePickerDismissed()` — synchronous flag clear. No repository call. Matches `ChannelListEvent.WorkspacePickerDismissed`'s handler.

No new `ThreadEvent` sealed type. The existing `ThreadScreen` uses loose callbacks (`onBack`, `onSendMessage`, `onTitleClick`, `onOverflowClick`); the three new callbacks slot in alongside.

### `ThreadScreen` wiring

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Signature gains three callbacks (placement: after `onOverflowClick`):

```kotlin
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    connectionState: ConnectionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
    onWorkspaceChipTapped: () -> Unit = {},
    onWorkspacePicked: (String) -> Unit = {},
    onWorkspacePickerDismissed: () -> Unit = {},
)
```

All three have default `{}` so existing previews and callers don't break.

Inside the Scaffold body, between `ConnectionBanner` and the `LazyColumn`, insert a conditional chip render:

```kotlin
ConnectionBanner(state = connectionState, onRetry = onRetry)
if (!state.isPromoted && !state.hasMessages) {
    WorkspaceChip(
        workspaceLabel = state.workspaceLabel,
        onClick = onWorkspaceChipTapped,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
LazyColumn(…)
```

After the `Scaffold` closing brace, sibling to the Scaffold (mirror of `ChannelListScreen.kt:180-184`):

```kotlin
WorkspacePicker(
    visible = state.workspacePickerVisible,
    onPicked = onWorkspacePicked,
    onDismiss = onWorkspacePickerDismissed,
)
```

Notes:

- The chip's visibility condition `!state.isPromoted && !state.hasMessages` lives at the call site (one `if`), not on `ThreadUiState`. Two reasons: (a) the predicate is `&&` of two raw fields, trivially correct to inline; (b) `isPromoted` and `hasMessages` are useful raw signals for `#208` and `#138` to read later without re-deriving from a hidden boolean.
- Padding (`horizontal = 16.dp, vertical = 8.dp`) matches the message-list frame's gutter from Figma `16:21`. The chip occupies its own padded row above the (empty) message list.
- The picker render uses `state.workspacePickerVisible` from `ThreadUiState` — no separate local `when` block needed because `ThreadUiState` is a single concrete data class (no `Loaded`/`Empty` variants like `ChannelListUiState`).

### `WorkspaceChip` composable (new file)

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt`

Public composable signature:

```kotlin
@Composable
fun WorkspaceChip(
    workspaceLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Body: a single M3 `AssistChip` with leading folder icon, label text `Workspace: <workspaceLabel> (change)`.

- `leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) }` — `Outlined` matches M3's recommendation for non-selected leading icons; `contentDescription = null` because the label text already conveys the affordance.
- `label = { Text("Workspace: $workspaceLabel (change)") }` — single `Text`, no styled spans. The whole chip is the click target; the `(change)` parenthetical is hint copy.
- `onClick = onClick` — direct pass-through.
- `modifier = modifier` — forwarded. The caller (`ThreadScreen`) is responsible for the surrounding padding.
- No new colors, no theme additions. M3 defaults for `AssistChip` carry the correct surface/outline tokens.

Two `@Preview` composables (light + dark) showing the chip in its visible state — required by AC #5. Use `PyrycodeMobileTheme(darkTheme = …)` wrap, matching the convention from `SessionBoundaryDelimiter.kt` and the other components in this package. Sample labels: `"scratch"` (light) and `"my-app"` (dark) to exercise both the sentinel and the basename paths.

### `MainActivity` call-site update

File: `app/src/main/java/de/pyryco/mobile/MainActivity.kt:204-210`

Pass the three new callbacks into the `ThreadScreen` invocation:

```kotlin
ThreadScreen(
    state = state,
    onBack = { navController.popBackStack() },
    onSendMessage = vm::sendMessage,
    connectionState = connectionState,
    onRetry = vm::retry,
    onWorkspaceChipTapped = vm::onWorkspaceChipTapped,
    onWorkspacePicked = vm::onWorkspacePicked,
    onWorkspacePickerDismissed = vm::onWorkspacePickerDismissed,
)
```

No DI changes — `ThreadViewModel`'s constructor surface is unchanged.

## State + concurrency model

- **`viewModelScope` jobs.** One new launch site: `onWorkspacePicked` → `viewModelScope.launch { repository.changeWorkspace(…) }`. Tied to the ViewModel; cancels on screen pop (standard).
- **`StateFlow` shape.** Same single `state: StateFlow<ThreadUiState>` exposed; widened to combine three upstreams instead of one. `connectionState: StateFlow<ConnectionState>` is untouched.
- **Hot vs cold.** `pendingWorkspacePicker` is hot (`MutableStateFlow`) so a picker-open event survives recomposition. `observeConversations` and `observeMessages` remain cold; the `combine`/`stateIn` shapes them into the hot `state` output.
- **Dispatcher.** Main (Compose default). `changeWorkspace` is `suspend` but Phase 0's fake is non-blocking; no explicit dispatcher switch.
- **Shutdown / cancellation.** Screen pop → `viewModelScope` cancels → any in-flight `changeWorkspace` is cancelled silently. Picker state is dropped (`MutableStateFlow` is GC'd with the ViewModel). Matches the existing pattern.

## Error handling

- **`changeWorkspace` failure modes.** Phase 0's fake doesn't throw for valid `conversationId`; the only thrown path (`unknown(conversationId)`) is unreachable here because the conversation must exist for the user to be on its thread. No `try`/`catch`. Phase 4's network-backed implementation will revisit (out of scope; flag matches #220's stance).
- **Picker dismiss mid-write.** If the user dismisses the picker (clears the flag) while a `changeWorkspace` coroutine is in-flight, the launch continues on `viewModelScope` — it's not tied to the picker's visibility. The conversation's `cwd` still updates; the chip label re-renders if the chip is still visible. Documented; no special handling needed.
- **Repository `observeMessages` failure.** Phase 0 fake cannot fail. Phase 4 may emit errors; today the `combine` would propagate the throw and the StateFlow would terminate. Out of scope for this ticket; if Phase 4 wires error UI, a `catch { }` operator goes on the upstream flow before the `combine`.
- **Empty `conversationId`.** The existing fallback at line 28 (`savedStateHandle.get<String>("conversationId").orEmpty()`) means `conversationId` could be `""`; `observeMessages("")` returns `emptyList()` (fake's behavior) and `observeConversations` won't match. Result: `hasMessages = false`, `isPromoted = false`, `workspaceLabel = "scratch"` — the chip renders. Calling `changeWorkspace("", path)` would throw in the fake (unknown id). This degenerate path is structurally unreachable in production (the route requires a non-empty `conversationId` nav arg) and the existing test `state_collapsesAbsentConversationIdToEmptyString` doesn't exercise the picker. No defensive code needed.

## Testing strategy

### `ThreadViewModelTest` (unit) — six new cases

Extends the existing test class in `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`. Mirror the conventions already in the file: `runTest`, `UnconfinedTestDispatcher`, `makeVm` helper, `FakeConversationRepository` (no mocks), `collector = launch { vm.state.collect {} }` to subscribe `stateIn`'s `WhileSubscribed` flow before asserting.

1. **`state_workspaceLabel_isScratch_whenCwdIsEmptyString`** — load a fresh discussion (`repo.createDiscussion(workspace = null)`), assert `vm.state.value.workspaceLabel == "scratch"`. Exercises the `cwd.isEmpty()` branch — the actual default state of a fresh discussion per `FakeConversationRepository.createDiscussion`.
2. **`state_workspaceLabel_isScratch_whenCwdIsDefaultScratchSentinel`** — `fixedRepo` with one discussion at `cwd = DEFAULT_SCRATCH_CWD`, assert label `"scratch"`. Exercises the sentinel branch.
3. **`state_workspaceLabel_isBasename_forArbitraryCwd`** — `fixedRepo` with `cwd = "pyry-workspace/my-app"`, assert label `"my-app"`. Exercises the `substringAfterLast` happy path.
4. **`state_chipFields_reflectChannelAndMessagePresence`** — single test that walks: seeded channel (`"seed-channel-personal"`) — assert `isPromoted = true`, `hasMessages` is whatever the seeds carry (read once and lock); fresh discussion — assert `isPromoted = false`, `hasMessages = false` before `sendMessage`, then call `vm.sendMessage("hi")`, `advanceUntilIdle`, assert `hasMessages = true`. Locks the two raw fields that the chip's call-site `if (!isPromoted && !hasMessages)` consumes.
5. **`onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag`** — fresh discussion via `repo.createDiscussion(null)`, capture its `id`. Call `vm.onWorkspaceChipTapped()`, assert `workspacePickerVisible == true`. Call `vm.onWorkspacePicked("pyry-workspace/my-app")`, `advanceUntilIdle`, assert `workspacePickerVisible == false` AND the conversation's `cwd` is now `"pyry-workspace/my-app"` (re-fetch via `repo.observeConversations(All).first()`). Locks the side-effect plus the flag clear.
6. **`onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace`** — fresh discussion, `onWorkspaceChipTapped`, `onWorkspacePickerDismissed`, assert `workspacePickerVisible == false` and the conversation's `cwd` is unchanged. Locks the no-side-effect path.

`recentWorkspaces` / `createWorkspaceFolder` are not exercised here — those are the picker host's contract, already tested under `WorkspacePickerTest` (`#220`).

### Existing unit tests

The seven existing `ThreadViewModelTest` cases should pass unmodified. They assert `displayName`, `conversationId`, `sendMessage`, `connectionState`, and `retry` — all unchanged. The widened `ThreadUiState` adds defaulted fields; existing test assertions like `assertEquals(ThreadUiState(conversationId = "...", displayName = "..."), vm.state.value)` continue to match because the new fields default to `false` / `false` / `"scratch"` / `false`, which is exactly what the initial-value branch (before flow emission) produces.

One nuance — `state_initialValue_isConversationIdPlaceholderBeforeSubscription` (line 46) compares full `ThreadUiState` equality without subscribing. The widened initial value must still equal `ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal")`. The defaults make this work, but **verify the equality holds after widening** — if the test breaks, the `initialValue` block in the new `state` flow is the only place to fix (it must continue to construct the `ThreadUiState` with only `conversationId` and `displayName` set, letting the other four fields default).

### Compose / androidTest

**Not required by AC.** AC #5 demands a preview, not an androidTest. Skip; rely on the previews + the unit tests above. The thread package has no existing androidTest infrastructure for `ThreadScreen` (verified: `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/` does not exist), and adding one for this ticket would be a 100+ LOC sidewinder that the AC doesn't request. Developer may add one at their discretion, but it's explicitly out of scope of the size budget.

### Test commands

- `./gradlew test` — runs the six new + seven existing `ThreadViewModelTest` cases.
- `./gradlew connectedAndroidTest` — unaffected by this ticket.
- `./gradlew lint` — should be clean; one new file under `components/`, standard Kotlin/Compose patterns.

## Open questions

1. **Should `changeWorkspace` on an empty thread skip the `SessionBoundary`?** As implemented, picking a workspace before any message lands creates a `SessionBoundary` in the stream; once the user sends a message, the thread renders `[boundary, message1]`. The boundary shows "Workspace changed to X" — informative, but arguably redundant in this UX path. Out of scope to fix here (would require either a new repository method or a behavior change on `changeWorkspace`); flagging for future product/design review. The chip's visibility logic already handles this correctly — boundaries do not count toward `hasMessages`.

2. **Chip-to-message-list spacing in the empty state.** The current `ThreadScreen` has the `LazyColumn` immediately after the chip with no spacer; the visual is just the chip floating in otherwise-empty space (since the list is empty when the chip is visible). Once `#138` lands the empty-state copy, that copy provides the visual anchor below the chip. No spacer in this ticket.

3. **Accessibility semantics.** The chip's whole surface is the click target; `Icon(contentDescription = null)` defers the announcement to the label text, which already reads "Workspace: scratch (change)" — a screen reader will announce the chip's `Role.Button` plus the full label. No additional `semantics { }` block needed. If a future a11y audit requests a separate change-affordance announcement, revisit then.

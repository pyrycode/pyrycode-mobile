# 251 — ThreadOverflowMenu composable + sealed ThreadEvent + VM archive wiring

## Context

`ThreadTopAppBar`'s `MoreVert` icon currently fires a no-op `onOverflowClick`. This slice lands the three pieces needed for the menu to be implementable end-to-end without mounting it in the top bar:

1. A stateless `ThreadOverflowMenu` composable that renders the five common items (New session, Rename, Change workspace…, Archive, Channel info).
2. A `sealed interface ThreadEvent` scoped to the overflow only — one case per item.
3. A `ThreadViewModel.onOverflowEvent(event: ThreadEvent)` dispatcher; `Archive` calls `repository.archive(conversationId)`, the other four cases are empty handler bodies for downstream tickets (#252 mounts the menu, then per-item follow-ups fill in rename/new-session/change-workspace/channel-info).

`ThreadOverflowMenu` is exported and stateless but has **no production caller** in this slice — `ThreadTopAppBar.onOverflowClick` remains a no-op (mounting + `expanded` state are #252's scope). This matches the project's pattern for stubbed-but-unwired entry points (`onTitleClick`, the current `onOverflowClick`) and lets the contract land with full unit + Compose-test coverage before the surface change in #252.

`ThreadViewModel` today exposes six plain handler methods (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`). CLAUDE.md prescribes "sealed `UiState` + `Event` types" but the merged VM is plain-method shaped. This ticket introduces a sealed `ThreadEvent` **scoped to the overflow only** — leaving the six existing plain methods intact. Existing methods are not migrated to the sealed surface in this slice (option ii from the convention question on #203; option iii — migrate all six alongside the overflow work — was rejected as oversized).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The overflow `IconButton` trigger lives at node `16:16` inside the Conversation Thread Screen (`16:8`); Figma has no dedicated frame for the open-menu state. Render as a stock Material 3 `DropdownMenu` anchored to the existing `MoreVert` `IconButton`, with one `DropdownMenuItem` per row in the documented order. No bespoke icons, dividers, or styling — this is a vanilla M3 menu and the design intentionally inherits M3 defaults (surface container, body-large item text, standard insets) so the Figma source omits it.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:23-119` — current `ThreadUiState` (read `conversationId` at line 24), `ThreadViewModel` constructor (post-#253: `SavedStateHandle, ConversationRepository, ConnectionStateSource, AppPreferences`), `viewModelScope.launch { repository.sendMessage(state.value.conversationId, text) }` shape at lines 91-96 — the canonical pattern `onOverflowEvent(Archive)` mirrors verbatim for the archive call.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:22-57` — current `onOverflowClick: () -> Unit` no-op stub. **Do not modify** in this slice; #252 owns the wiring.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:40` — `suspend fun archive(conversationId: String)`; throws `IllegalArgumentException` on unknown ids per repo conventions (see neighboring `delete` doc at lines 44-61), but with the VM passing the current `state.value.conversationId` the unknown-id branch is unreachable.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:196-216` — the only existing `DropdownMenu` + `DropdownMenuItem` site in the codebase. **Critical reference:** note the `onClick = { menuExpanded = false; onSaveAsChannel() }` ordering — dismiss before invoking the handler. `ThreadOverflowMenu` adopts the same ordering.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:48-67, 499-557` — `runTest`/`Dispatchers.setMain` scaffold, `newDataStore()` helper, `makeVm(handle, repository, source = FakeConnectionStateSource(), prefs = AppPreferences(newDataStore()))` helper, and the `fixedRepo`/`RecordingConnectionStateSource` test-double shapes the two new VM tests follow.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt:442-560` — `RecordingRepo` pattern (per-method call counters; archive/unarchive recorded). Mirror this shape for the new VM test's recording double rather than introducing a new test framework. `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt:448-557` is an alternative reference if its pattern fits more cleanly.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/WorkspacePickerSheetTest.kt:1-100` — the project's canonical Compose-test setup (`createComposeRule`, `PyrycodeMobileTheme` wrapper, `hasText`/`hasContentDescription`/`performClick`). `ThreadOverflowMenuTest` follows the same shape verbatim.
- `app/src/main/res/values/strings.xml:1-44` — string-resource conventions (snake_case keys, no per-feature prefixes, separate `cd_*` keys for content descriptions). Add the five item labels here.
- `CLAUDE.md` — MVI conventions: stateless composables, sealed types, single `StateFlow<UiState>` per ViewModel, test-first.

## Design

### `sealed interface ThreadEvent`

Co-locate with `ThreadViewModel` in `ThreadViewModel.kt` (top-level, above the `ThreadUiState` data class). Same file is the existing precedent for sealed types in this project (`DiscussionListEvent` is declared in `DiscussionListScreen.kt`; `ChannelListEvent` in `ChannelListScreen.kt`) — placement next to the consumer-VM is the closer match because this sealed surface is dispatched on by the ViewModel, not the screen, in this slice.

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
}
```

All five cases are parameterless `data object`s — no per-event payload is needed (the conversation id is sourced from the VM's `state.value` at dispatch time). Use `data object` (not plain `object`) for the auto-generated `toString`/`equals` — keeps assertion failure messages readable in tests.

`ThreadEvent` is `public` (default) and `internal`-by-convention via being scoped to the `thread/` package; downstream tickets (#252 mounting, per-item follow-ups) will consume it from within the same package.

### `ThreadOverflowMenu` composable

Path: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt`.

Signature (single-event-sink shape — see rationale below):

```kotlin
@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

Body — Material 3 `DropdownMenu` containing five `DropdownMenuItem`s in this exact order:

| Order | Label resource | `ThreadEvent` |
| ----- | --------------- | -------------- |
| 1     | `R.string.thread_overflow_new_session`     | `ThreadEvent.NewSession`     |
| 2     | `R.string.thread_overflow_rename`          | `ThreadEvent.Rename`         |
| 3     | `R.string.thread_overflow_change_workspace` | `ThreadEvent.ChangeWorkspace` |
| 4     | `R.string.thread_overflow_archive`         | `ThreadEvent.Archive`        |
| 5     | `R.string.thread_overflow_channel_info`    | `ThreadEvent.ChannelInfo`    |

Each item's `onClick` calls `onDismiss()` **before** `onEvent(...)` — same ordering as `DiscussionListScreen.kt:209-212`. The dismiss-before-handler ordering is load-bearing for two reasons:

1. The Compose-test assertion in AC (d) verifies dismissal precedes event dispatch.
2. Downstream tickets open dialogs / sheets / nav transitions from these events; the menu must be closed before the new surface mounts to avoid Compose layout layering issues.

`DropdownMenu` already dismisses itself on outside-tap (via `onDismissRequest = onDismiss`); explicit item taps are the only path that needs to call dismiss themselves.

No preview composables required for this slice — no production caller renders the menu yet, and the menu has no per-state visual variants worth previewing in isolation. The host preview lands with #252.

#### Why a single `onEvent` sink (not per-item callbacks)

The ticket leaves the parameter shape at architect's discretion but requires items to dispatch through `ThreadEvent`. Single-sink chosen because:

- It is the direct CLAUDE.md MVI convention. `ChannelListScreen`/`DiscussionListScreen` already use `onEvent: (XxxEvent) -> Unit` for their screen-level event surfaces.
- The host (#252) binds `onEvent = vm::onOverflowEvent` directly — no per-callback → sealed-event mapping layer.
- The Compose test is one event-recording lambda assertion instead of five — slightly less boilerplate for the same coverage.
- `ChannelInfoSheet`'s per-callback shape is a precedent for **sheets** (heterogeneous distinct affordances spread across sections); a menu is a homogeneous list dispatching the same shape of event, so the sealed-sink shape fits the surface better.

### `ThreadViewModel.onOverflowEvent`

Add a single dispatcher method, sized to ~10 LOC:

```kotlin
fun onOverflowEvent(event: ThreadEvent) {
    when (event) {
        ThreadEvent.Archive -> viewModelScope.launch {
            repository.archive(state.value.conversationId)
        }
        ThreadEvent.NewSession,
        ThreadEvent.Rename,
        ThreadEvent.ChangeWorkspace,
        ThreadEvent.ChannelInfo -> Unit // filled in by downstream tickets
    }
}
```

Notes:

- The `Archive` branch is the only side effect this slice ships. The other four branches are intentionally empty — exhaustive `when` over the sealed surface plus `Unit` at the call site documents "downstream tickets fill these in" without adding TODOs or stubs that would tempt expanding scope.
- `viewModelScope.launch` is the same shape as the existing `sendMessage` handler (lines 93-96). No new dispatcher; no `Job` retention. `repository.archive(id)` is a `suspend` one-shot — its post-condition is that the conversation is no longer in `observeConversations(All)` for non-archived filters, and the existing `state` flow re-emits naturally.
- The conversation id is read from `state.value.conversationId` (not the constructor-captured `private val conversationId`) to match the existing `sendMessage` pattern. Both reads resolve to the same value because `state.value.conversationId` is initialized to and never overwritten away from the constructor value, but using `state.value` is consistent with the rest of the file.
- No optimistic UI update; no error handling beyond the default propagation. `archive` throws `IllegalArgumentException` for unknown ids per repo contract, but the id we pass came from our own state — the throw branch is unreachable in production.

### Strings

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="thread_overflow_new_session">New session</string>
<string name="thread_overflow_rename">Rename</string>
<string name="thread_overflow_change_workspace">Change workspace…</string>
<string name="thread_overflow_archive">Archive</string>
<string name="thread_overflow_channel_info">Channel info</string>
```

The `Change workspace…` label uses the U+2026 horizontal-ellipsis character (not three dots) per Material 3 guidance for "this action opens further UI". Matches `save_as_channel_action` ("Save as channel…") at strings.xml:11.

## State + concurrency model

- **Hot/cold:** no new flow shape. `onOverflowEvent` is a synchronous dispatch that launches into `viewModelScope` only for `Archive`. Existing `state: StateFlow<ThreadUiState>` re-emits naturally when `repository.archive(id)` mutates `observeConversations`.
- **Dispatcher:** inherits `viewModelScope` (Main by default for the launcher; repository's internal IO is its own concern — `FakeConversationRepository` is in-memory, `Phase 4` remote impl will dispatch IO on its own).
- **Cancellation:** the launched job is short-lived (one `repository.archive` call). Screen exit destroys `viewModelScope`, cancelling any in-flight archive — acceptable: the user-visible state is "archive may or may not have happened" until the next read, but `FakeConversationRepository` is synchronous-effective in tests and Phase 4 work owns retry semantics for the remote case.
- **`ThreadOverflowMenu`** holds no state itself. `expanded`/`onDismiss` are hoisted to the future host (#252).

## Error handling

- `repository.archive(id)` failure paths:
  - `IllegalArgumentException` for unknown id: unreachable (id sourced from VM's own state, populated by `observeConversations`).
  - IO failure in Phase 4: out of scope. This slice does not add a snackbar / banner / toast — adding error UI now would couple to surface choices that #252+ will make. The post-#253 codebase has no global error-surfacing convention in `ThreadViewModel` yet; this ticket preserves the current posture (unhandled exceptions surface as `viewModelScope` crashes, caught at the coroutine boundary by Android's default handler).
- No `try`/`catch` in `onOverflowEvent`. No optimistic UI rollback. No "are you sure?" confirmation — archive is reversible (`unarchive` exists on the repository contract) and the eventual surface will be in the Channel Info sheet (#217) which already handles the confirmation flow design.

## Testing strategy

### `ThreadViewModelTest` — unit (`./gradlew test`)

Re-use the existing `makeVm` helper at line 499 (post-#253 baseline: takes `handle`, `repository`, `source`, `prefs`). The two new tests construct a `RecordingRepo` test double rather than using `FakeConversationRepository` so the assertion against `archive` is a direct counter read, not a state-projection inference.

Add a private `RecordingRepo` inside `ThreadViewModelTest` (mirror `DiscussionListViewModelTest.kt:442-560`'s shape) exposing at minimum:

- `archiveCalls: List<String>` — appended to in `override suspend fun archive(conversationId)`. List rather than counter so the test can assert both call count *and* id.
- All other `ConversationRepository` methods: `TODO("not used")` or empty stubs as in `fixedRepo`. `observeConversations`, `observeMessages`, `observeLastMessage` must return non-throwing default flows (the VM subscribes to them via `combine` at construction — they must produce or the test hangs). Use `flowOf(emptyList())` / `flowOf(null)` defaults.

New tests (bulleted scenarios — developer writes them in the existing JUnit 4 idiom):

- **`onOverflowEvent_archive_callsRepositoryArchiveOnceWithCurrentConversationId`** — construct VM with `RecordingRepo` and `SavedStateHandle("conversationId" to "seed-channel-personal")`. Subscribe to `vm.state` to drive the `WhileSubscribed` flow. `advanceUntilIdle`. Call `vm.onOverflowEvent(ThreadEvent.Archive)`. `advanceUntilIdle`. Assert `repo.archiveCalls == listOf("seed-channel-personal")`. Cancel collector.
- **`onOverflowEvent_otherCases_doNotCallArchive`** — construct as above. Call `vm.onOverflowEvent(ThreadEvent.NewSession)`, `vm.onOverflowEvent(ThreadEvent.Rename)`, `vm.onOverflowEvent(ThreadEvent.ChangeWorkspace)`, `vm.onOverflowEvent(ThreadEvent.ChannelInfo)`. `advanceUntilIdle`. Assert `repo.archiveCalls.isEmpty()`. (Optionally also assert no calls to any other recording method — but `archiveCalls.isEmpty()` is sufficient for this AC.)

### `ThreadOverflowMenuTest` — instrumented Compose (`./gradlew connectedAndroidTest`)

New file at `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt`. Mirror `WorkspacePickerSheetTest`'s scaffold: `createComposeRule`, wrap content in `PyrycodeMobileTheme`, use `hasText(...)`/`performClick(...)`/`assertIsDisplayed()` matchers.

Setup pattern for each test: `composeTestRule.setContent { PyrycodeMobileTheme { ThreadOverflowMenu(expanded = true, onDismiss = ..., onEvent = ...) } }`. The `expanded = true` literal forces the menu to render immediately; no anchor or hosting state machine needed for the contract tests.

Tests (bulleted scenarios):

- **`menu_items_render_in_documented_order_when_expanded`** — render with `expanded = true`. Assert all five labels are displayed by `hasText` (lookup against `composeTestRule.activity.getString(R.string.thread_overflow_*)` to avoid hardcoding English). Verify order by reading the items via `onAllNodes(hasAnyAncestor(...))` or `onAllNodes(isMenuItem)`-equivalent matcher and asserting the in-order text sequence matches `[New session, Rename, Change workspace…, Archive, Channel info]`. If a matcher-friendly order assertion is awkward (Compose-test ordering matchers vary by library version), fall back to: assert each of the five labels exists, and add a single sentinel test that taps each item one at a time and asserts the event dispatched matches the documented order — that also verifies positional integrity.
- **`tapping_each_item_dismisses_then_dispatches_event`** — for each of the five items, in a single test or one-per-item:
  - Use a list-backed event recorder: `val events = mutableListOf<ThreadEvent>()`; `onEvent = events::add`.
  - Use a counter-backed dismiss recorder: `var dismissed = 0`; `onDismiss = { dismissed++ }`.
  - Perform `composeTestRule.onNode(hasText("New session")).performClick()`.
  - Assert `dismissed == 1` **and** `events == listOf(ThreadEvent.NewSession)`. (The dismiss-before-event ordering is enforced by the composable's own `onClick` body — Compose-test can't observe call ordering across two separate recorders directly, but the test can assert that `dismissed >= 1` *and* `events.size == 1` *and* `events[0] == ThreadEvent.NewSession`, with the actual ordering invariant captured by code-review of the `onClick` body. If stricter ordering coverage is desired, the developer may use a single combined recorder — `val log = mutableListOf<String>(); onDismiss = { log.add("dismiss") }; onEvent = { log.add("event:$it") }` — and assert `log == listOf("dismiss", "event:NewSession")`. This is the **preferred** shape because it captures the AC explicitly.)
  - Repeat for the other four items.

The combined-recorder approach is the preferred form because AC (d) explicitly calls out "dismisses the dropdown *before* firing its `ThreadEvent`". The single-list assertion `log == listOf("dismiss", "event:NewSession")` is the directly-testable expression of that AC.

### Out of scope for tests

- No test for `ThreadEvent` exhaustiveness — the Kotlin compiler enforces this in the `when` inside `onOverflowEvent` (no `else` branch; missing case is a compile error).
- No test for the four no-op branches *interacting* with anything other than the repository — they have no observable state effect to assert against.
- No test for `ThreadTopAppBar` — not modified in this slice.
- No test for #252's host-state plumbing (mount point, `expanded` state, `onEvent = vm::onOverflowEvent` binding).

## Open questions

- **`ThreadEvent` placement.** Spec recommends co-locating with `ThreadViewModel.kt`. The screen-level precedent (`DiscussionListEvent` in `DiscussionListScreen.kt`) is the alternative — but this event surface is dispatched on by the VM, not the screen, in this slice. If #252's wiring ends up routing through the screen layer in a way that makes screen-file placement more natural, the developer may relocate; document the choice in the PR description. Either location keeps the same package.
- **`data object` vs `object`.** Spec recommends `data object`. The only behavioral difference is `toString`/`equals`/`hashCode`. If a future change adds payload fields to any case (`Rename(newName: String)`, etc.) the case migrates to a `data class` — `data object` is the lower-friction predecessor for that migration.
- **`Change workspace…` ellipsis character.** Spec uses U+2026. If the project's i18n linter has any opinion about literal-vs-character ellipsis in `strings.xml`, the developer should follow the lint and adjust this spec's recommendation; no other call site cares.

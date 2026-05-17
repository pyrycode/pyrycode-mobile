# ThreadOverflowMenu

Stateless Material 3 `DropdownMenu` (#251) listing the five common thread-overflow actions in this exact order: **New session**, **Rename**, **Change workspace…**, **Archive**, **Channel info**. Sole consumer of the [`ThreadEvent`](#threadevent) sealed surface introduced in the same slice. Mounted in production since [#252](../codebase/252.md) inside `ThreadTopAppBar`'s `actions` slot (wrapped in a `Box` alongside the existing `MoreVert` `IconButton` so the M3 `DropdownMenu` anchors directly below the icon); the screen-owned `expanded` flag lives on `ThreadScreen` as `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, and `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` so each menu-item tap reaches `ThreadViewModel.onOverflowEvent(...)`.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/`). File: `ThreadOverflowMenu.kt`. Sibling to [`ThreadScreen`](thread-screen.md), `ThreadTopAppBar.kt`, [`ThreadInputBar`](thread-input-bar.md), and `ThreadViewModel.kt` (where `ThreadEvent` and `onOverflowEvent` live).

## Shape

```kotlin
@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public`** (default visibility) — exported so the eventual #252 host (and the existing `androidTest` Compose tests) can render it.
- **Stateless.** No `remember`, no `mutableStateOf`. `expanded` / `onDismiss` are hoisted to the future host; today's `ThreadOverflowMenuTest` passes `expanded = true` literal to render immediately without a host state machine.
- **Single `onEvent: (ThreadEvent) -> Unit` sink — not per-item callbacks.** Direct CLAUDE.md MVI convention. The host (#252) binds `onEvent = vm::onOverflowEvent` with no per-callback → sealed-event mapping layer. The contrast against [`ChannelInfoSheet`](channel-info-sheet.md)'s six named callbacks is by design — sheets are heterogeneous, menus are homogeneous; see [the per-ticket Patterns established note](../codebase/251.md#patterns-established).
- **`modifier` defaulted but exposed** so the host can pass placement / sizing modifiers if needed; today no caller uses it.

## ThreadEvent

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
}
```

Co-located with `ThreadViewModel` at the top of `ThreadViewModel.kt` (above the `ThreadUiState` data class), not in its own file — the dispatcher (`onOverflowEvent`) is the only consumer in this slice. Placement follows the consumer; the screen-level precedent `DiscussionListEvent` / `ChannelListEvent` declare next to the screen file but that did not fit because in this slice the screen does not yet consume the sealed surface.

- **`data object` (not plain `object`) on every case.** Sole behavioural difference is auto-generated `toString`/`equals`/`hashCode`. The `toString` (`"NewSession"`, etc.) makes the Compose-test combined-log assertion `log == listOf("dismiss", "event:NewSession")` readable without a custom matcher.
- **Parameterless.** No per-event payload — the conversation id is sourced from the VM's `state.value.conversationId` at dispatch time inside `onOverflowEvent`, not embedded in the event. If a future ticket adds a payload field to any case (e.g. `Rename(newName: String)`), that case migrates to `data class`.
- **Scope is overflow-only.** Existing plain handler methods on `ThreadViewModel` (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`) are **not** migrated to `ThreadEvent` in this slice. Option (ii) from the convention question on [#203](https://github.com/pyrycode/pyrycode-mobile/issues/203); option (iii) — migrate all six alongside the overflow work — was rejected as oversized.

## What it does

Renders one M3 `DropdownMenu` containing five `DropdownMenuItem`s. Each item's `onClick` calls `onDismiss()` **before** `onEvent(ThreadEvent.X)`:

| Order | `R.string.thread_overflow_*` | Label              | `ThreadEvent`           |
| ----- | ---------------------------- | ------------------ | ----------------------- |
| 1     | `_new_session`               | New session        | `NewSession`            |
| 2     | `_rename`                    | Rename             | `Rename`                |
| 3     | `_change_workspace`          | Change workspace…  | `ChangeWorkspace`       |
| 4     | `_archive`                   | Archive            | `Archive`               |
| 5     | `_channel_info`              | Channel info       | `ChannelInfo`           |

Dismiss-before-handler ordering is load-bearing for two reasons:

1. The Compose-test combined-log assertion (`log == listOf("dismiss", "event:NewSession")`) pins it explicitly — see [Tests](#tests).
2. Downstream host wiring opens dialogs / sheets / nav transitions from these events; the menu must be closed before the new surface mounts to avoid Compose layout layering issues. Inverting the order ("emit the event, let the host close the menu") would push close-coordination into every event handler.

`DropdownMenu`'s built-in `onDismissRequest = onDismiss` covers the outside-tap / back-press / scrim-tap paths; explicit item taps are the only path that calls dismiss themselves.

Same dismiss-before-handler pattern as `DiscussionListScreen.kt:209-212` (`onClick = { menuExpanded = false; onSaveAsChannel() }` from [#25](../codebase/25.md)) — that's the only other production `DropdownMenu` call site in the codebase.

## ViewModel dispatcher

`ThreadViewModel` exposes a single 10-line `when` dispatcher:

```kotlin
fun onOverflowEvent(event: ThreadEvent) {
    when (event) {
        ThreadEvent.Archive ->
            viewModelScope.launch {
                repository.archive(state.value.conversationId)
            }
        ThreadEvent.NewSession,
        ThreadEvent.Rename,
        ThreadEvent.ChangeWorkspace,
        ThreadEvent.ChannelInfo,
        -> Unit
    }
}
```

- **`Archive` is the only side effect this slice ships.** The other four branches are intentionally an exhaustive `Unit` arm — exhaustive `when` over the sealed surface guarantees that a missing case is a compile error, so no TODO comments / no stub bodies are needed. Downstream tickets fill them in (rename dialog → `Rename`, [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) → `ChangeWorkspace`, Channel Info sheet host → `ChannelInfo`, eventual `startNewSession` wiring → `NewSession`).
- **`viewModelScope.launch { repository.archive(...) }`** is the same shape as the pre-existing `sendMessage` launcher at `ThreadViewModel.kt:103-108`. No new dispatcher, no `Job` retention.
- **Conversation id is read from `state.value.conversationId`, not the constructor-captured `private val conversationId`.** Both resolve to the same value (the data-class default mirrors the constructor field; no mutation path overwrites it), but `state.value.conversationId` matches the existing `sendMessage` read pattern for one-grep convention.
- **No optimistic UI update; no error handling.** `archive(id)` is documented to throw `IllegalArgumentException` for unknown ids per the [`ConversationRepository`](conversation-repository.md) contract, but the id we pass came from our own state — the throw branch is unreachable in production. The existing `state` flow re-emits naturally when `repository.archive` mutates the underlying `observeConversations` set; no manual `_state.value = ...` write needed.

## Configuration / wiring

- **No new dependencies, no Koin changes.** `DropdownMenu` / `DropdownMenuItem` ship in the existing `composeBom = 2026.02.01`. No new icon imports (the menu has no leading icons). The `ThreadViewModel` Koin binding at `AppModule.kt:36` is unchanged — `onOverflowEvent` adds no constructor parameter.
- **Five new `strings.xml` keys.** `thread_overflow_new_session`, `_rename`, `_change_workspace`, `_archive`, `_channel_info`. The `Change workspace…` label uses U+2026 horizontal-ellipsis (not three dots) per M3 guidance for "this action opens further UI" — same convention as `save_as_channel_action` ("Save as channel…") at `strings.xml:11`. All five labels render via `stringResource(...)` inside the composable.
- **Production wiring lives in [#252](../codebase/252.md).** `ThreadTopAppBar` mounts the menu inside its `actions` slot as a sibling to the `MoreVert` `IconButton`, both wrapped in a `Box` so the M3 `DropdownMenu` anchors below the icon (`Box`-as-anchor, not the implicit `actions` `Row`, because the row's bounds would mis-position a single-icon overflow). `ThreadTopAppBar` grew three parameters (`overflowExpanded: Boolean`, `onOverflowDismiss: () -> Unit`, `onOverflowEvent: (ThreadEvent) -> Unit`); `ThreadScreen` hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }` (mirroring the existing `sheetVisible` line) and passes `onOverflowClick = { overflowExpanded = true }` / `onOverflowDismiss = { overflowExpanded = false }` / `onOverflowEvent = onOverflowEvent` down. The screen's own `onOverflowClick: () -> Unit = {}` parameter was renamed in place to `onOverflowEvent: (ThreadEvent) -> Unit = {}` — same defaulted shape so the four `@Preview` composables keep compiling. `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` at the `composable(Routes.CONVERSATION_THREAD)` block.

## Preview

None. The menu has no per-state visual variants worth previewing in isolation (it's `expanded = true` ⇒ five rows, or `false` ⇒ nothing). Post-[#252](../codebase/252.md) the host-rendered open-menu state can be exercised through the existing `ThreadScreen` previews by tapping the icon at runtime; no `@Preview` was added in either slice because the four `ThreadScreen` previews already cover the surrounding chrome and the menu itself is system-popup-windowed (Compose `@Preview` doesn't draw popup windows).

## Tests

Two test files: a Compose `androidTest` for the menu composable, two `test` (unit) cases for the VM dispatcher.

### `ThreadOverflowMenuTest.kt` (Compose, `./gradlew connectedAndroidTest`)

Lives at `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt`. Mirrors [`WorkspacePickerSheetTest`](workspace-picker-sheet.md)'s scaffold: `createComposeRule` + `PyrycodeMobileTheme` wrapper + i18n-safe label lookup via `InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)`.

- **`menu_items_render_in_documented_order_when_expanded`** — renders with `expanded = true`; asserts all five `R.string.thread_overflow_*` labels are displayed via `composeTestRule.onNodeWithText(...).assertIsDisplayed()`. Order is implicitly verified by the five per-item tests below — a label-mismatch on any item would fail its own tap test before this one.
- **`tapping_<item>_dismisses_then_dispatches_event`** (one per item, five total) — uses the **combined-log recorder pattern**: a single `val log = mutableListOf<String>()` with `onDismiss = { log.add("dismiss") }` and `onEvent = { log.add("event:$it") }`. After `performClick()`, asserts `log == listOf("dismiss", "event:NewSession")` (or the corresponding event). The combined-log assertion is the directly-testable expression of "dismisses the dropdown *before* firing its `ThreadEvent`" — two separate recorders (a counter + a list) couldn't pin the ordering inside one `onClick` callback.

### `ThreadViewModelTest.kt` additions (unit, `./gradlew test`)

Two new `@Test` methods (L497-530 in the file post-#251). Both re-use the post-[#253](../codebase/253.md) `TestScope.makeVm(handle, repository, source, prefs)` receiver helper and a new private `RecordingRepo : ConversationRepository` test double.

- **`onOverflowEvent_archive_callsRepositoryArchiveOnceWithCurrentConversationId`** — constructs VM with `RecordingRepo` and `SavedStateHandle("conversationId" to "seed-channel-personal")`; subscribes to drive the `WhileSubscribed` flow; calls `vm.onOverflowEvent(ThreadEvent.Archive)`; asserts `repo.archiveCalls == listOf("seed-channel-personal")`.
- **`onOverflowEvent_otherCases_doNotCallArchive`** — same setup; calls `onOverflowEvent` with each of the other four cases; asserts `repo.archiveCalls.isEmpty()`.

**Why `RecordingRepo`, not `FakeConversationRepository`?** Direct call-shape assertion (`repo.archiveCalls == listOf(id)`) is one indirection less coupled than projecting the archive's effect through `observeConversations` and asserting on the resulting set. See [the per-ticket Lessons learned note](../codebase/251.md#lessons-learned) for the full rationale and the `flowOf(emptyList())` / `flowOf(null)` non-emitting-stub gotcha.

No test for `ThreadEvent` exhaustiveness — Kotlin's `when` compiler-enforces it. No test for the four no-op branches' wider interactions (they have no observable state effect to assert against). No test for `ThreadTopAppBar` (not modified). No test for #252's host-state plumbing.

## Edge cases / limitations

- **Mounted in production since [#252](../codebase/252.md).** Tapping the `MoreVert` overflow icon now opens the menu; tapping an item closes the menu and dispatches the corresponding `ThreadEvent` through `ThreadViewModel.onOverflowEvent`. Only `Archive` has an observable effect today (archives the conversation via `repository.archive`); the other four cases are intentionally no-op `Unit` arms on the VM until each per-item follow-up wires its handler — UX-wise the menu still closes correctly thanks to dismiss-before-handler ordering.
- **Single sink — sealed dispatch is the only event shape this composable speaks.** A future menu item with a payload (e.g. a context-sensitive "Move to channel X" with an id) migrates that case to `data class`; the sink stays `(ThreadEvent) -> Unit`.
- **No leading icons on items.** Vanilla M3 `DropdownMenuItem` text-only rows — Figma `16:16` for the open-menu state doesn't include icons, the design intentionally inherits M3 defaults (surface container, body-large item text, standard insets). Adding leading icons later is a `leadingIcon = { Icon(...) }` per-item edit that doesn't touch the public signature.
- **No dividers, no section headers.** Five flat items; the M3 `DropdownMenuItem` divider helper exists but the design doesn't use it. If a follow-up needs to split into "common actions" + "destructive actions" sections, `HorizontalDivider()` between items is the conventional shape — but the menu currently has no destructive emphasis on `Archive` (archive is reversible via `unarchive`; the eventual destructive surface is `delete`, owned by [`ChannelInfoSheet`](channel-info-sheet.md)).
- **Empty `Unit` arm covers four cases.** Post-[#252](../codebase/252.md) the menu mounts in production and dispatches all five events, but the four non-`Archive` cases land on the VM's exhaustive `Unit` branch — no UI response until each per-item host follow-up wires its handler. The dismiss-before-handler ordering means the menu still closes correctly even when the event is a no-op — UX-wise the user sees the menu close and nothing further happens.

## Related

- Ticket notes: [`../codebase/251.md`](../codebase/251.md) (composable + sealed event + VM dispatcher) and [`../codebase/252.md`](../codebase/252.md) (host mount + screen-owned expanded state + `MainActivity` binding).
- Specs: `docs/specs/architecture/251-thread-overflow-menu-archive-wiring.md` and `docs/specs/architecture/252-thread-overflow-menu-mount-and-wire.md`
- Parent: split from [#203](https://github.com/pyrycode/pyrycode-mobile/issues/203) (the umbrella overflow-menu ticket; the original #140 placeholder rolled into #203's scope).
- Upstream / siblings:
  - [`ThreadScreen`](thread-screen.md) — the screen that hosts the future mount; the post-#251 `ThreadViewModel` section now documents `onOverflowEvent(ThreadEvent)` alongside the six pre-existing plain handlers.
  - [`ChannelInfoSheet`](channel-info-sheet.md) — the sibling per-callback-shape composable; the deliberate shape contrast with this menu (homogeneous events → single sink vs heterogeneous affordances → named callbacks) is documented above.
  - [`DiscussionListScreen`'s long-press menu](discussion-list-screen.md) — the only other production `DropdownMenu` call site; established the `onClick = { menuExpanded = false; onSaveAsChannel() }` dismiss-before-handler precedent this composable mirrors.
  - [`ConversationRepository`](conversation-repository.md) — the `suspend fun archive(conversationId)` contract `onOverflowEvent(Archive)` invokes.
- Downstream:
  - Per-item follow-ups, one for each currently-empty `when` branch:
    - Rename dialog (`Rename`) — eventually under the successor to [#141](https://github.com/pyrycode/pyrycode-mobile/issues/141).
    - [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) (`ChangeWorkspace`) — reuses the `workspacePickerVisible` flag and the two picker handlers introduced by [#137](../codebase/137.md).
    - Channel Info sheet host (`ChannelInfo`) — eventual successor to [#217](../codebase/217.md), the slice that shipped the stateless [`ChannelInfoSheet`](channel-info-sheet.md).
    - `NewSession` — not yet ticketed; will call `repository.startNewSession(state.value.conversationId, workspace = null)` inside the same `viewModelScope.launch` shape `Archive` uses today.
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the parent Conversation Thread screen; the `MoreVert` trigger lives at child node `16:16`). No dedicated frame for the open-menu state — render as a vanilla M3 `DropdownMenu` with one `DropdownMenuItem` per row.

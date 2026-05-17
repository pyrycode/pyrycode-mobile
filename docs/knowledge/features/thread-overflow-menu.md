# ThreadOverflowMenu

Stateless Material 3 `DropdownMenu` (#251) listing five common thread-overflow actions in this exact order — **New session**, **Rename**, **Change workspace…**, **Archive**, **Channel info** — wrapped since [#204](../codebase/204.md) by **two mutually-exclusive context-aware items**: discussions (`isPromoted == false`) get a leading **Save as channel…** (emits `ThreadEvent.SaveAsChannel`); channels (`isPromoted == true`) get a trailing **Install memory plugin** (opens `MEMORY_PLUGIN_DOCS_URL` via `LocalUriHandler.current.openUri(...)` — no `ThreadEvent`). Sole consumer of the [`ThreadEvent`](#threadevent) sealed surface introduced in [#251](../codebase/251.md). Mounted in production since [#252](../codebase/252.md) inside `ThreadTopAppBar`'s `actions` slot (wrapped in a `Box` alongside the existing `MoreVert` `IconButton` so the M3 `DropdownMenu` anchors directly below the icon); the screen-owned `expanded` flag lives on `ThreadScreen` as `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, and `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` so each menu-item tap reaches `ThreadViewModel.onOverflowEvent(...)`. `ThreadScreen` passes `isPromoted = state.isPromoted` down through `ThreadTopAppBar` so the menu picks the right context-specific item without a new screen parameter.

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/`). File: `ThreadOverflowMenu.kt`. Sibling to [`ThreadScreen`](thread-screen.md), `ThreadTopAppBar.kt`, [`ThreadInputBar`](thread-input-bar.md), and `ThreadViewModel.kt` (where `ThreadEvent` and `onOverflowEvent` live).

## Shape

```kotlin
@Composable
fun ThreadOverflowMenu(
    expanded: Boolean,
    isPromoted: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

- **`public`** (default visibility) — exported so the [#252](../codebase/252.md) host (and the `androidTest` Compose tests) can render it.
- **Stateless.** No `remember`, no `mutableStateOf`. `expanded` / `isPromoted` / `onDismiss` are hoisted to the host; today's `ThreadOverflowMenuTest` passes `expanded = true` literal to render immediately without a host state machine.
- **`isPromoted` placement: between `expanded` and `onDismiss`** — keeps the "what to render" parameters grouped before the "what to do" callbacks. No default value (matches `expanded` / `onDismiss` / `onEvent`; the host always knows). Added in [#204](../codebase/204.md). Drives which of the two mutually-exclusive context-specific items renders alongside the five common items.
- **Single `onEvent: (ThreadEvent) -> Unit` sink — not per-item callbacks.** Direct CLAUDE.md MVI convention. The host ([#252](../codebase/252.md)) binds `onEvent = vm::onOverflowEvent` with no per-callback → sealed-event mapping layer. The contrast against [`ChannelInfoSheet`](channel-info-sheet.md)'s six named callbacks is by design — sheets are heterogeneous, menus are homogeneous; see [the per-ticket Patterns established note](../codebase/251.md#patterns-established).
- **`modifier` defaulted but exposed** so the host can pass placement / sizing modifiers if needed; today no caller uses it.

## ThreadEvent

```kotlin
sealed interface ThreadEvent {
    data object NewSession : ThreadEvent
    data object Rename : ThreadEvent
    data class RenameSubmit(val name: String) : ThreadEvent       // added in #141
    data object RenameDismiss : ThreadEvent                       // added in #141
    data object ChangeWorkspace : ThreadEvent
    data object Archive : ThreadEvent
    data object ChannelInfo : ThreadEvent
    data object SaveAsChannel : ThreadEvent                       // added in #204
}
```

Co-located with `ThreadViewModel` at the top of `ThreadViewModel.kt` (above the `ThreadUiState` data class), not in its own file — the dispatcher (`onOverflowEvent`) is the only consumer in this slice. Placement follows the consumer; the screen-level precedent `DiscussionListEvent` / `ChannelListEvent` declare next to the screen file but that did not fit because in this slice the screen does not yet consume the sealed surface.

- **Mixed `data object` and `data class` since [#141](../codebase/141.md).** Six `data object` cases (menu-item taps, parameterless — `NewSession`, `Rename`, `RenameDismiss`, `ChangeWorkspace`, `Archive`, `ChannelInfo`, `SaveAsChannel`) and one `data class` case (`RenameSubmit(val name: String)`, the dialog-Save dispatch carrying the user-trimmed new name). The [#251](../codebase/251.md) reservation for "a future menu item with a payload migrates that case to `data class`" cashed in at #141 — the rename family is contiguous in the sealed surface (`Rename, RenameSubmit, RenameDismiss`), with the parameter-bearing case in the middle so the trim-authority contract stays at the UI boundary.
- **No `InstallMemoryPlugin` event** despite the channel-only **Install memory plugin** item ([#204](../codebase/204.md)). The item's only side effect is `LocalUriHandler.current.openUri(MEMORY_PLUGIN_DOCS_URL)`, which is a UI-layer concern with no VM state mutation; routing through `ThreadEvent` would add a hop with no purpose. The discriminator versus `SaveAsChannel` (which *does* exist as an event) is the eventual VM authority: the promote-dialog flow ([#142](https://github.com/pyrycode/pyrycode-mobile/issues/142)) needs the VM to own the dialog flag and the `repository.promote(id, name)` launcher; the docs-URL handler does not.
- **`data object` semantics: `toString` is the load-bearing auto-generation.** The Compose-test combined-log assertion `log == listOf("dismiss", "event:NewSession")` reads cleanly because `data object NewSession.toString() == "NewSession"`; plain `object` would produce `"NewSession@<hashcode>"`.
- **Conversation id is sourced from `state.value.conversationId` at dispatch time** inside `onOverflowEvent`, not embedded on the events — same one-grep convention as `sendMessage`. `RenameSubmit.name` is the *only* payload field carried by any case, and it exists because the trimmed name has no other lookup path from the VM (the dialog is the trim authority; the field text is local to the dialog composable). `SaveAsChannel`'s eventual `PromoteSubmit(name)` companion ([#142](https://github.com/pyrycode/pyrycode-mobile/issues/142)) will mirror this shape.
- **Scope is overflow-only.** Existing plain handler methods on `ThreadViewModel` (`sendMessage`, `retry`, `onWorkspaceChipTapped`, `onWorkspacePicked`, `onWorkspacePickerDismissed`, `onModelSelected`) are **not** migrated to `ThreadEvent` in this slice. Option (ii) from the convention question on [#203](https://github.com/pyrycode/pyrycode-mobile/issues/203); option (iii) — migrate all six alongside the overflow work — was rejected as oversized.

## What it does

Renders one M3 `DropdownMenu` containing the five common `DropdownMenuItem`s, wrapped by two mutually-exclusive context-aware items ([#204](../codebase/204.md)). Each item's `onClick` calls `onDismiss()` **before** `onEvent(ThreadEvent.X)` (or, for the install-memory-plugin item, before `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)`):

| Order             | When                | String resource                            | Label              | Side effect                                                |
| ----------------- | ------------------- | ------------------------------------------ | ------------------ | ---------------------------------------------------------- |
| 1 (discussion)    | `!isPromoted`       | `R.string.save_as_channel_action`          | Save as channel…   | `onEvent(ThreadEvent.SaveAsChannel)`                       |
| 2 (always)        | —                   | `R.string.thread_overflow_new_session`     | New session        | `onEvent(ThreadEvent.NewSession)`                          |
| 3 (always)        | —                   | `R.string.thread_overflow_rename`          | Rename             | `onEvent(ThreadEvent.Rename)`                              |
| 4 (always)        | —                   | `R.string.thread_overflow_change_workspace`| Change workspace…  | `onEvent(ThreadEvent.ChangeWorkspace)`                     |
| 5 (always)        | —                   | `R.string.thread_overflow_archive`         | Archive            | `onEvent(ThreadEvent.Archive)`                             |
| 6 (always)        | —                   | `R.string.thread_overflow_channel_info`    | Channel info       | `onEvent(ThreadEvent.ChannelInfo)`                         |
| 7 (channel)       | `isPromoted`        | `R.string.thread_overflow_install_memory_plugin` | Install memory plugin | `uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` (no event)    |

Final orders, after the conditional branches collapse:

- **Discussion (`isPromoted == false`):** Save as channel… → New session → Rename → Change workspace… → Archive → Channel info. Six items, no install-memory-plugin.
- **Channel (`isPromoted == true`):** New session → Rename → Change workspace… → Archive → Channel info → Install memory plugin. Six items, no save-as-channel.

The two context-aware items render as two `if` blocks inside the `DropdownMenu` body — `if (!isPromoted) { ... }` prepended; `if (isPromoted) { ... }` appended. A `when (isPromoted)` over the entire menu body was considered and rejected — it would either duplicate the five common items in both branches or collapse to the same `if` pair around two extra items, and the `if`-pair shape directly expresses the AC wording ([per-ticket rationale](../codebase/204.md#patterns-established)).

**`save_as_channel_action` is reused, not duplicated** ([#204](../codebase/204.md)). The same `strings.xml:11` key serves [`DiscussionListScreen`](discussion-list-screen.md)'s long-press promote menu ([#25](../codebase/25.md)) and this menu's first item — same English copy, same semantic action, one localization entry.

**`MEMORY_PLUGIN_DOCS_URL` is reused too.** The `internal const val` at `SessionBoundaryDelimiter.kt:37` (introduced in [#135](../codebase/135.md) for the empty-thread install affordance) is imported via `de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL`. The constant's home stays at the boundary delimiter; a parallel `private const val` in `ThreadOverflowMenu.kt` was considered and rejected — the Phase 3+ swap-to-real-install-endpoint becomes a one-grep edit by keeping a single source of truth.

Dismiss-before-handler ordering is load-bearing for two reasons:

1. The Compose-test combined-log assertion (`log == listOf("dismiss", "event:NewSession")`) pins it explicitly — see [Tests](#tests).
2. Downstream host wiring opens dialogs / sheets / nav transitions from these events; the menu must be closed before the new surface mounts to avoid Compose layout layering issues. Inverting the order ("emit the event, let the host close the menu") would push close-coordination into every event handler.

`DropdownMenu`'s built-in `onDismissRequest = onDismiss` covers the outside-tap / back-press / scrim-tap paths; explicit item taps are the only path that calls dismiss themselves.

Same dismiss-before-handler pattern as `DiscussionListScreen.kt:209-212` (`onClick = { menuExpanded = false; onSaveAsChannel() }` from [#25](../codebase/25.md)) — that's the only other production `DropdownMenu` call site in the codebase.

## ViewModel dispatcher

`ThreadViewModel.onOverflowEvent` is a `when` dispatcher over the sealed surface. Two side effects ship today (`Archive`, `RenameSubmit`); two visibility-flag flips support the rename dialog flow (`Rename`, `RenameDismiss`); four cases remain exhaustive `Unit` arms pending their per-item follow-ups.

```kotlin
fun onOverflowEvent(event: ThreadEvent) {
    when (event) {
        ThreadEvent.Archive ->
            viewModelScope.launch {
                repository.archive(state.value.conversationId)
            }
        ThreadEvent.Rename -> pendingRenameDialog.value = true             // added in #141
        is ThreadEvent.RenameSubmit -> {                                   // added in #141
            pendingRenameDialog.value = false
            viewModelScope.launch {
                repository.rename(state.value.conversationId, event.name)
            }
        }
        ThreadEvent.RenameDismiss -> pendingRenameDialog.value = false     // added in #141
        ThreadEvent.NewSession,
        ThreadEvent.ChangeWorkspace,
        ThreadEvent.ChannelInfo,
        ThreadEvent.SaveAsChannel,                                         // added in #204
        -> Unit
    }
}
```

- **`Archive` was the only side effect through [#252](../codebase/252.md); [#141](../codebase/141.md) added `RenameSubmit`.** Both follow the identical `viewModelScope.launch { repository.<verb>(state.value.conversationId, ...) }` shape — fire-and-forget, no `Job` retention, conversation id read from `state.value` for one-grep convention with `sendMessage`. The four remaining `Unit` arms (`NewSession`, `ChangeWorkspace`, `ChannelInfo`, `SaveAsChannel`) await their per-item follow-ups ([#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) → `ChangeWorkspace`, Channel Info sheet host → `ChannelInfo`, eventual `startNewSession` wiring → `NewSession`, [#142](https://github.com/pyrycode/pyrycode-mobile/issues/142) → `SaveAsChannel`).
- **`SaveAsChannel` was added to the sealed surface in [#204](../codebase/204.md) but is a no-op `Unit` arm in this slice.** The exhaustive `when` over the sealed surface means the case must exist at compile time, so the spec adds it now and [#142](https://github.com/pyrycode/pyrycode-mobile/issues/142) (the promotion-dialog wiring; parallel to [#141](../codebase/141.md)'s rename slice in shape) fills the body — it will mount a new dialog as a `Scaffold` sibling on `ThreadScreen`, add a `PromoteSubmit(name)` / `PromoteDismiss` companion pair to `ThreadEvent`, add a `pendingPromoteDialog: MutableStateFlow<Boolean>` to `ThreadViewModel`, surface `showPromoteDialog: Boolean` on `ThreadUiState`, and call `repository.promote(state.value.conversationId, event.name)` inside the same `viewModelScope.launch` shape `RenameSubmit` uses today.
- **The rename flow is split across three cases.** `Rename` (menu-tap trigger) flips `pendingRenameDialog.value = true`, which surfaces to `ThreadUiState.showRenameDialog` via the main `combine(...)` block. `RenameSubmit(name)` (dialog Save tap) flips the flag back to `false` **synchronously before** launching `repository.rename(...)` — the synchronous ordering matters: it dismisses the dialog immediately so the UI doesn't wait for the suspend to complete, and a re-tap of `Rename` during an in-flight rename has well-defined state. `RenameDismiss` (dialog Cancel / outside-tap / back-press) flips the flag to `false` only.
- **Conversation id is read from `state.value.conversationId`, not the constructor-captured `private val conversationId`.** Both resolve to the same value (the data-class default mirrors the constructor field; no mutation path overwrites it), but `state.value.conversationId` matches the existing `sendMessage` read pattern for one-grep convention. Applies to both `Archive` and `RenameSubmit`.
- **`pendingRenameDialog: MutableStateFlow<Boolean>` is a private VM field**, mirroring `pendingWorkspacePicker` from [#137](../codebase/137.md). It's the fourth source folded into the main `combine(...)` block on `ThreadViewModel.state` — making the block five-arity total (`observeConversations, observeMessages, pendingWorkspacePicker, pendingRenameDialog, selectedModelFlow`), which is exactly the native `kotlinx.coroutines.flow.combine` overload ceiling. **The VM-owned vs. screen-hoisted choice matters**: dialog visibility belongs on the VM (and on `ThreadUiState`) because the dialog represents in-progress operation state and has multiple potential trigger points (today's overflow item, tomorrow's TopAppBar tap-to-rename) — centralising on the VM keeps the dialog single-sourced. Contrast against `overflowExpanded` ([#252](../codebase/252.md)) and `sheetVisible` ([#254](../codebase/254.md)), which are screen-hoisted `var ... by rememberSaveable { mutableStateOf(false) }` because they're pure UI presentation with no business meaning the VM needs to react to.
- **No optimistic UI update; no error handling.** Both `archive(id)` and `rename(id, name)` are documented to throw `IllegalArgumentException` for unknown ids per the [`ConversationRepository`](conversation-repository.md) contract, but the id we pass came from our own state — the throw branch is unreachable in production. The existing `state` flow re-emits naturally when `repository.archive` / `repository.rename` mutate the underlying `observeConversations` set; no manual `_state.value = ...` write needed.

## Configuration / wiring

- **No new dependencies, no Koin changes.** `DropdownMenu` / `DropdownMenuItem` ship in the existing `composeBom = 2026.02.01`. No new icon imports (the menu has no leading icons). The `ThreadViewModel` Koin binding at `AppModule.kt:36` is unchanged — `onOverflowEvent` adds no constructor parameter.
- **Six `strings.xml` keys.** `thread_overflow_new_session`, `_rename`, `_change_workspace`, `_archive`, `_channel_info` (all [#251](../codebase/251.md)); `thread_overflow_install_memory_plugin` ([#204](../codebase/204.md)). The `Change workspace…` label uses U+2026 horizontal-ellipsis (not three dots) per M3 guidance for "this action opens further UI" — same convention as `save_as_channel_action` ("Save as channel…") at `strings.xml:11`. All labels render via `stringResource(...)` inside the composable. **The `save_as_channel_action` key is reused** for the discussion-only item rather than a parallel `thread_overflow_save_as_channel` — same English copy and semantic action as `DiscussionListScreen.kt:208`'s long-press promote menu ([#25](../codebase/25.md)).
- **`MEMORY_PLUGIN_DOCS_URL` is imported, not duplicated** ([#204](../codebase/204.md)). The `internal const val` at `SessionBoundaryDelimiter.kt:37` ([#135](../codebase/135.md)) is consumed via `import de.pyryco.mobile.ui.conversations.components.MEMORY_PLUGIN_DOCS_URL`. Phase 3+ swap to the real install endpoint is a one-grep edit by keeping a single source of truth.
- **Production wiring lives in [#252](../codebase/252.md) (mount) + [#204](../codebase/204.md) (context plumb).** `ThreadTopAppBar` mounts the menu inside its `actions` slot as a sibling to the `MoreVert` `IconButton`, both wrapped in a `Box` so the M3 `DropdownMenu` anchors below the icon (`Box`-as-anchor, not the implicit `actions` `Row`, because the row's bounds would mis-position a single-icon overflow). `ThreadTopAppBar` grew four parameters: `overflowExpanded: Boolean`, `onOverflowDismiss: () -> Unit`, `onOverflowEvent: (ThreadEvent) -> Unit` (all [#252](../codebase/252.md)), and `isPromoted: Boolean` ([#204](../codebase/204.md), placed between `onOverflowEvent` and `modifier`). `ThreadScreen` hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }` (mirroring the existing `sheetVisible` line) and passes `onOverflowClick = { overflowExpanded = true }` / `overflowExpanded = overflowExpanded` / `onOverflowDismiss = { overflowExpanded = false }` / `onOverflowEvent = onOverflowEvent` / `isPromoted = state.isPromoted` down. **No new screen parameter** for `isPromoted` — `ThreadUiState.isPromoted` already flows in via `state: ThreadUiState` (projected from `Conversation.isPromoted` in the VM's `combine` block; not touched by [#204](../codebase/204.md)). The screen's own `onOverflowClick: () -> Unit = {}` parameter was renamed in place to `onOverflowEvent: (ThreadEvent) -> Unit = {}` — same defaulted shape so the four `@Preview` composables keep compiling. `MainActivity` binds `onOverflowEvent = vm::onOverflowEvent` at the `composable(Routes.CONVERSATION_THREAD)` block.

## Preview

None. The menu has no per-state visual variants worth previewing in isolation (it's `expanded = true` ⇒ five rows, or `false` ⇒ nothing). Post-[#252](../codebase/252.md) the host-rendered open-menu state can be exercised through the existing `ThreadScreen` previews by tapping the icon at runtime; no `@Preview` was added in either slice because the four `ThreadScreen` previews already cover the surrounding chrome and the menu itself is system-popup-windowed (Compose `@Preview` doesn't draw popup windows).

## Tests

Two test files: a Compose `androidTest` for the menu composable, two `test` (unit) cases for the VM dispatcher.

### `ThreadOverflowMenuTest.kt` (Compose, `./gradlew connectedAndroidTest`)

Lives at `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt`. Mirrors [`WorkspacePickerSheetTest`](workspace-picker-sheet.md)'s scaffold: `createComposeRule` + `PyrycodeMobileTheme` wrapper + i18n-safe label lookup via `InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)`.

- **`channel_menu_items_render_in_documented_order_when_expanded`** ([#204](../codebase/204.md) split) — renders with `isPromoted = true`; asserts all five `R.string.thread_overflow_*` labels + the `_install_memory_plugin` label are displayed and the `save_as_channel_action` label `assertDoesNotExist()`.
- **`discussion_menu_items_render_in_documented_order_when_expanded`** ([#204](../codebase/204.md) split) — renders with `isPromoted = false`; asserts `save_as_channel_action` + the five common labels are displayed and `_install_memory_plugin` `assertDoesNotExist()`. Pre-#204 the file had one `menu_items_render_in_documented_order_when_expanded` test that asserted only the five common items; #204 split it across the two `isPromoted` variants. Order is implicitly verified by the per-item tap tests below — a label-mismatch on any item would fail its own tap test before this one.
- **`tapping_<item>_dismisses_then_dispatches_event`** (one per item, seven total — five for the common items, plus `_save_as_channel` and the no-event variant `_install_memory_plugin`) — uses the **combined-log recorder pattern**: a single `val log = mutableListOf<String>()` with `onDismiss = { log.add("dismiss") }` and `onEvent = { log.add("event:$it") }`. After `performClick()`, asserts `log == listOf("dismiss", "event:NewSession")` (or the corresponding event). The combined-log assertion is the directly-testable expression of "dismisses the dropdown *before* firing its `ThreadEvent`" — two separate recorders (a counter + a list) couldn't pin the ordering inside one `onClick` callback. The five common-item tests pass `isPromoted = true`; the save-as-channel test passes `isPromoted = false`.
- **`tapping_install_memory_plugin_dismisses_and_opens_docs_url`** ([#204](../codebase/204.md)) — uses the same combined-log shape for `onDismiss`, but injects a fake `UriHandler` via `CompositionLocalProvider(LocalUriHandler provides fakeHandler) { ThreadOverflowMenu(...) }` to capture the URL. Asserts `log == listOf("dismiss")` (proves both dismiss-fired *and* no-event-fired in one equality) and `fakeHandler.openedUris == listOf(MEMORY_PLUGIN_DOCS_URL)`. The fake is a six-line file-local `class RecordingUriHandler : UriHandler { val openedUris = mutableListOf<String>(); override fun openUri(uri: String) { openedUris += uri } }` — same direct-test-double idiom as `RecordingRepo` (see [Lessons learned in #204](../codebase/204.md#lessons-learned) for the `CompositionLocalProvider`-inside-the-theme placement rule).

### `ThreadViewModelTest.kt` additions (unit, `./gradlew test`)

Two new `@Test` methods (L497-530 in the file post-#251). Both re-use the post-[#253](../codebase/253.md) `TestScope.makeVm(handle, repository, source, prefs)` receiver helper and a new private `RecordingRepo : ConversationRepository` test double.

- **`onOverflowEvent_archive_callsRepositoryArchiveOnceWithCurrentConversationId`** — constructs VM with `RecordingRepo` and `SavedStateHandle("conversationId" to "seed-channel-personal")`; subscribes to drive the `WhileSubscribed` flow; calls `vm.onOverflowEvent(ThreadEvent.Archive)`; asserts `repo.archiveCalls == listOf("seed-channel-personal")`.
- **`onOverflowEvent_otherCases_doNotCallArchive`** — same setup; calls `onOverflowEvent` with each of the non-`Archive` cases; asserts `repo.archiveCalls.isEmpty()`. Iteration list shrank post-[#141](../codebase/141.md) (which made `Rename` mutate state). **Not extended** in [#204](../codebase/204.md) to include `SaveAsChannel` — `SaveAsChannel` is a `Unit` arm and trivially satisfies `archiveCalls.isEmpty()`; adding it would not catch a future bug `SaveAsChannel → repository.archive(...)` doesn't already catch.

**Why `RecordingRepo`, not `FakeConversationRepository`?** Direct call-shape assertion (`repo.archiveCalls == listOf(id)`) is one indirection less coupled than projecting the archive's effect through `observeConversations` and asserting on the resulting set. See [the per-ticket Lessons learned note](../codebase/251.md#lessons-learned) for the full rationale and the `flowOf(emptyList())` / `flowOf(null)` non-emitting-stub gotcha.

No test for `ThreadEvent` exhaustiveness — Kotlin's `when` compiler-enforces it. No test for the four no-op branches' wider interactions (they have no observable state effect to assert against). No test for `ThreadTopAppBar` (pure prop-passing of `isPromoted`). No test for #252's host-state plumbing or #204's `state.isPromoted → ThreadTopAppBar` one-line edit.

## Edge cases / limitations

- **Mounted in production since [#252](../codebase/252.md); `Rename` wired in [#141](../codebase/141.md); context-aware items added in [#204](../codebase/204.md).** Tapping the `MoreVert` overflow icon opens the menu; tapping an item closes the menu and dispatches the corresponding `ThreadEvent` through `ThreadViewModel.onOverflowEvent` (or, for **Install memory plugin**, fires `LocalUriHandler.openUri(MEMORY_PLUGIN_DOCS_URL)` directly). **Three cases have observable effects today**: `Archive` calls `repository.archive(id)`, `Rename` opens the [`RenameDialog`](rename-dialog.md) (whose Save tap routes through `RenameSubmit(name)` → `repository.rename(id, name)`), and **Install memory plugin** opens the docs URL. The other four `ThreadEvent` cases (`NewSession`, `ChangeWorkspace`, `ChannelInfo`, `SaveAsChannel`) are intentionally no-op `Unit` arms on the VM until each per-item follow-up wires its handler — UX-wise the menu still closes correctly thanks to dismiss-before-handler ordering.
- **No third state.** Discussions (`isPromoted == false`) never get the install-memory-plugin item; channels (`isPromoted == true`) never get the save-as-channel item. The two `if` blocks render at most one extra item per render; both branches are mutually exclusive because `Boolean` has no third value.
- **Single sink — sealed dispatch is the dominant event shape this composable speaks.** A future menu item with a payload (e.g. a context-sensitive "Move to channel X" with an id) migrates that case to `data class`; the sink stays `(ThreadEvent) -> Unit`. The install-memory-plugin item is the sole exception — its side effect is a UI-layer `LocalUriHandler.openUri(...)` call with no VM authority, so it doesn't route through `onEvent`.
- **No leading icons on items.** Vanilla M3 `DropdownMenuItem` text-only rows — Figma `16:16` for the open-menu state doesn't include icons, the design intentionally inherits M3 defaults (surface container, body-large item text, standard insets). Adding leading icons later is a `leadingIcon = { Icon(...) }` per-item edit that doesn't touch the public signature.
- **No dividers, no section headers.** Six-or-seven flat items (six per mode); the M3 `DropdownMenuItem` divider helper exists but the design doesn't use it. If a follow-up needs to split into "common actions" + "destructive actions" sections, `HorizontalDivider()` between items is the conventional shape — but the menu currently has no destructive emphasis on `Archive` (archive is reversible via `unarchive`; the eventual destructive surface is `delete`, owned by [`ChannelInfoSheet`](channel-info-sheet.md)).
- **Empty `Unit` arm covers four cases post-[#204](../codebase/204.md)** (back up from three after [#141](../codebase/141.md), since `SaveAsChannel` was added but not wired). `NewSession`, `ChangeWorkspace`, `ChannelInfo`, and `SaveAsChannel` land on the VM's exhaustive `Unit` branch — no UI response until each per-item host follow-up wires its handler. The dismiss-before-handler ordering means the menu still closes correctly even when the event is a no-op — UX-wise the user sees the menu close and nothing further happens (until [#142](https://github.com/pyrycode/pyrycode-mobile/issues/142) lands and a promotion dialog mounts).
- **`Install memory plugin` may throw `ActivityNotFoundException` on devices with no browser.** `LocalUriHandler.openUri(...)` delegates to a platform `Intent.ACTION_VIEW`; without a handler the platform throws. The implementation does not try/catch — same posture as [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)'s identical `openUri(MEMORY_PLUGIN_DOCS_URL)` call. If the no-browser failure mode is observed in practice, a single fix can cover both call sites; the discriminator (no observed failure yet) per the project's [evidence-based fix selection](../../../CLAUDE.md) principle means no defensive code today.

## Related

- Ticket notes: [`../codebase/251.md`](../codebase/251.md) (composable + sealed event + VM dispatcher), [`../codebase/252.md`](../codebase/252.md) (host mount + screen-owned expanded state + `MainActivity` binding), [`../codebase/141.md`](../codebase/141.md) (rename dialog wiring; first per-item follow-up), and [`../codebase/204.md`](../codebase/204.md) (context-aware items; `isPromoted` parameter + `SaveAsChannel` event + URL-handler-only install-memory-plugin item).
- Specs: `docs/specs/architecture/251-thread-overflow-menu-archive-wiring.md`, `docs/specs/architecture/252-thread-overflow-menu-mount-and-wire.md`, `docs/specs/architecture/141-rename-dialog-wiring.md`, `docs/specs/architecture/204-context-aware-overflow-menu-items.md`.
- Parent: split from [#203](https://github.com/pyrycode/pyrycode-mobile/issues/203) (the umbrella overflow-menu ticket; the original #140 placeholder rolled into #203's scope).
- Upstream / siblings:
  - [`ThreadScreen`](thread-screen.md) — the screen that hosts the menu mount; post-[#204](../codebase/204.md) passes `isPromoted = state.isPromoted` through `ThreadTopAppBar` alongside the existing overflow-menu plumbing.
  - [`ChannelInfoSheet`](channel-info-sheet.md) — the sibling per-callback-shape composable; the deliberate shape contrast with this menu (homogeneous events → single sink vs heterogeneous affordances → named callbacks) is documented above.
  - [`DiscussionListScreen`'s long-press menu](discussion-list-screen.md) — the only other production `DropdownMenu` call site; established the `onClick = { menuExpanded = false; onSaveAsChannel() }` dismiss-before-handler precedent this composable mirrors, and the original consumer of `R.string.save_as_channel_action` this menu also reuses.
  - [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) — owns the `internal const val MEMORY_PLUGIN_DOCS_URL` constant ([#135](../codebase/135.md)) and the `LocalUriHandler.current.openUri(MEMORY_PLUGIN_DOCS_URL)` shape this menu's install-memory-plugin item ([#204](../codebase/204.md)) mirrors. The constant is shared, not duplicated.
  - [`ConversationRepository`](conversation-repository.md) — the `suspend fun archive(conversationId)` contract `onOverflowEvent(Archive)` invokes. The eventual `suspend fun promote(conversationId, name)` is what [#142](https://github.com/pyrycode/pyrycode-mobile/issues/142) will invoke from the filled `SaveAsChannel` arm.
- Downstream:
  - Per-item follow-ups, one for each currently-empty `when` branch (was four pre-[#141](../codebase/141.md); three after the rename slice landed; back up to four after [#204](../codebase/204.md) added `SaveAsChannel`):
    - [`#141`](../codebase/141.md) ✅ — rename dialog wiring; sibling [`RenameDialog`](rename-dialog.md) composable + two new `ThreadEvent` cases (`RenameSubmit(name)`, `RenameDismiss`) + `pendingRenameDialog` flag.
    - [#142](https://github.com/pyrycode/pyrycode-mobile/issues/142) (`SaveAsChannel`) — promotion dialog wiring. Parallel to [#141](../codebase/141.md) in shape: new dialog, `PromoteSubmit(name)` / `PromoteDismiss` companion pair, `pendingPromoteDialog` flag, `repository.promote(id, name)` launcher. Will need to either widen `combine(...)` past arity five or pre-fold two existing inputs (see [#141](../codebase/141.md)'s Patterns established for the ceiling note).
    - [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) (`ChangeWorkspace`) — reuses the `workspacePickerVisible` flag and the two picker handlers introduced by [#137](../codebase/137.md).
    - Channel Info sheet host (`ChannelInfo`) — eventual successor to [#217](../codebase/217.md), the slice that shipped the stateless [`ChannelInfoSheet`](channel-info-sheet.md).
    - `NewSession` — not yet ticketed; will call `repository.startNewSession(state.value.conversationId, workspace = null)` inside the same `viewModelScope.launch` shape `Archive` and `RenameSubmit` use today.
  - Phase 3+ swap of `MEMORY_PLUGIN_DOCS_URL` from the docs link to the real plugin install endpoint. Two call sites (this menu + [`SessionBoundaryDelimiter`](session-boundary-delimiter.md)); one-grep edit because the constant is shared.
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the parent Conversation Thread screen; the `MoreVert` trigger lives at child node `16:16`). No dedicated frame for the open-menu state — render as a vanilla M3 `DropdownMenu` with one `DropdownMenuItem` per row.

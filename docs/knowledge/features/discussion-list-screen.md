# DiscussionListScreen

Drilldown destination for unpromoted conversations (discussions), mounted at the `discussions` route. Sibling of [`ChannelListScreen`](channel-list-screen.md) — same `Scaffold + when (state)` skeleton, same `ConversationRow` items in `Loaded`, but with a back arrow instead of a settings gear, no FAB, and rows visibly de-emphasized via `Modifier.alpha(0.65f)` to signal the secondary tier.

Package: `de.pyryco.mobile.ui.conversations.list`. File: `DiscussionListScreen.kt`.

## What it does

Stateless `(state: DiscussionListUiState, onEvent: (DiscussionListEvent) -> Unit, modifier: Modifier)` composable. Renders an M3 `Scaffold` with a `TopAppBar` carrying the "Recent discussions" title and a back-arrow `IconButton`, and dispatches the four [`DiscussionListUiState`](discussion-list-viewmodel.md) variants:

- `Loading` → centered `Text("Loading…")` placeholder.
- `Empty` → centered `stringResource(R.string.discussion_list_empty)` ("No discussions yet").
- `Error(message)` → centered `"Couldn't load discussions: $message"`.
- `Loaded(discussions)` → `LazyColumn` of `DiscussionRow`s keyed by `Conversation.id`. Each `DiscussionRow` is a private composable in the same file that wraps the shared [`ConversationRow`](conversation-row.md) with the two gesture surfaces from #25 (long-press → `DropdownMenu`, right-to-left swipe → `SwipeToDismissBox`), and applies the `Modifier.alpha(0.65f)` de-emphasis on the inner row.

Emits `DiscussionListEvent.RowTapped(id)` on row tap, `DiscussionListEvent.SaveAsChannelRequested(id)` from both long-press menu selection and swipe completion (#25), and `DiscussionListEvent.BackTapped` on the back-arrow tap. The destination block in `MainActivity` routes `RowTapped` directly (`navController.navigate(...)`), forwards `SaveAsChannelRequested` to `vm.onEvent(event)` (the VM stub swallows it), and translates `BackTapped` into `navController.popBackStack()`. The VM additionally emits `RowTapped` onto its one-shot `navigationEvents` channel so the unit test can observe it (see [VM doc](discussion-list-viewmodel.md#dual-nav-wiring-24)).

## Event surface

```kotlin
sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data class SaveAsChannelRequested(val conversationId: String) : DiscussionListEvent
    data object BackTapped : DiscussionListEvent
}
```

Defined at the top of `DiscussionListScreen.kt` (paralleling `ChannelListEvent` in `ChannelListScreen.kt`). No `SettingsTapped`, no `CreateDiscussionTapped` — the drilldown is read-only in Phase 0. `SaveAsChannelRequested` is the only write-shaped event; the VM handles it as a `Unit` no-op stub with a `TODO(phase 2)` marker — the gesture surface ships independently of the promotion dialog (Phase 2).

## "Save as channel…" affordances (#25)

Each discussion row carries two gesture surfaces — both invoke `onEvent(DiscussionListEvent.SaveAsChannelRequested(discussion.id))` and Phase 0 swallows it as a VM no-op:

- **Long-press** opens an M3 `DropdownMenu` with a single `DropdownMenuItem(text = "Save as channel…")`. Long-press detection is wired through the new `onLongClick` parameter on [`ConversationRow`](conversation-row.md) (which switches its modifier chain from `Modifier.clickable` to `Modifier.combinedClickable`). Selecting the menu item dismisses the menu and invokes the callback.
- **Swipe right-to-left** (`SwipeToDismissBoxValue.EndToStart`) reveals a trailing `secondaryContainer`-tinted background with right-aligned "Save as channel…" text. The swipe is **trigger-only**: `confirmValueChange` invokes the callback and returns `false`, so the row springs back to rest. The leading direction (`StartToEnd`) is disabled (`enableDismissFromStartToEnd = false`) — reserved for a hypothetical future "promote-without-naming" shortcut.

Implementation: a private `DiscussionRow(discussion, onTap, onSaveAsChannel, menuInitiallyExpanded = false)` composable inside `DiscussionListScreen.kt` wraps the row in `SwipeToDismissBox { Box { ConversationRow(...); DropdownMenu(...) } }`. The `Box` is needed as a separate node to anchor the `DropdownMenu` to the row's outer frame (placing the menu directly inside `SwipeToDismissBox.content` would anchor it to the dismiss surface and misposition during in-progress swipes). Menu state is `var menuExpanded by remember(discussion.id) { mutableStateOf(menuInitiallyExpanded) }` — keyed on `discussion.id` so list re-keying resets the menu. The `menuInitiallyExpanded` parameter exists only so the static `@Preview` (next section) can render with the dropdown visible.

Direction rationale: Android UI convention puts destructive/trailing actions on the right edge (Gmail archive, system notification dismiss); "Save as channel" is a trailing action on the row. Don't swap on aesthetic grounds.

The `EndToStart` enum reads backward — in LTR locales, the user's finger swipes from the right edge toward the left ("end" → "start"). See [`../codebase/25.md`](../codebase/25.md) for the lessons learned around `SwipeToDismissBox` (background-content is in-progress-only; `enableDismissFromStartToEnd = false` is required *in addition to* a rejecting `confirmValueChange`).

Long-press menu is reached only by the keyboard-and-pointer combination Material 3 provides on `combinedClickable`; no manual `semantics { onLongClick(...) }` block is needed. The optional `onLongClick` parameter on `ConversationRow` is **only** passed at this call site — `ChannelListScreen` omits it (channel-row long-press is undecided territory).

## Visual de-emphasis — alpha at the call site

```kotlin
ConversationRow(
    conversation = discussion,
    lastMessage = null,
    onClick = onTap,
    modifier = Modifier.alpha(0.65f),
    onLongClick = { menuExpanded = true },
)
```

**Mechanism: outer `Modifier.alpha(0.65f)` applied to each `ConversationRow` inside the private `DiscussionRow` wrapper.** `ConversationRow` itself was not modified for the alpha (the de-emphasis lives at the call site, per #24).

Rationale:

- **No edit fan-out into the shared row component.** A `deEmphasized: Boolean = false` parameter on `ConversationRow` would force every existing call site (`ChannelListScreen` + the two `@Preview`s in `ConversationRow.kt`) to pass `deEmphasized = false`. Alpha-from-outside is a one-line addition at the new call site only.
- **Survives Material 3 ripples correctly.** The ripple renders against the un-alpha'd `ListItem` content inside `ConversationRow`; an outer alpha dims the static content uniformly without flattening the touch feedback. A surface-variant tint would require wrapping each row in a `Surface(tonalElevation = …)` — more code, same outcome.
- **"Secondary tier", not "disabled".** 0.65 reads as de-emphasized; values below 0.5 read as disabled. Do not lower the value below 0.5.

Hit-testing is unaffected by `Modifier.alpha` — the row's `onClick` fires at full opacity. That's correct: discussions are interactive, just secondary. A truly-disabled visual would need `enabled = false` on the inner `ListItem` (propagates `LocalContentColor` through M3), not alpha.

The "workspace label is absent on discussion rows" guarantee is **not** enforced by this screen. It falls out of `ConversationRow` calling `condenseWorkspace(conversation.cwd)`, which returns `null` for `DefaultScratchCwd`; the `WorkspaceChip` is gated on a non-null result. Fake-seeded discussions use `DefaultScratchCwd`, so no chip renders. The `@Preview` composables are the regression tripwire — a future change to `ConversationRow` or `condenseWorkspace` that re-introduces a label would show up in `DiscussionListScreenLoadedPreview`.

## Previews

Three `@Preview` composables live in the file:

- **`DiscussionListScreenLoadedPreview`** — full `DiscussionListScreen(state = Loaded([d1, d2]), onEvent = {})` with two fixture discussions (one normal, one `isSleeping = true`), both at `DefaultScratchCwd`. Verifies the Loaded layout under `PyrycodeMobileTheme(darkTheme = false)`, the alpha dim, and the absence of workspace chips.
- **`DiscussionListScreenWithMenuOpenPreview`** (#25) — renders a single `DiscussionRow(..., menuInitiallyExpanded = true)` so the static IDE preview shows the long-press `DropdownMenu` open over the row. Satisfies AC #5 ("at least one preview demonstrates the affordances"). The swipe surface is not previewable statically; gesture verification falls to manual exercise of the build.
- **`DiscussionRowVsChannelRowPreview`** — a `Column { channelRow; discussionRow }` rendering the same shared `ConversationRow` at full opacity (channel: promoted, named, real cwd) above the alpha-0.65 instance (discussion: unpromoted, name=null, `DefaultScratchCwd`). This is the visual contract for the #24 de-emphasis AC — the contrast between the two rows is the acceptance test. Don't move this preview out of `DiscussionListScreen.kt`; it belongs with the file that owns the alpha call site.

No `Empty` / `Error` previews — those branches are placeholders awaiting designed visuals.

## Destination block (in `PyryNavHost`)

```kotlin
composable(Routes.DiscussionList) {
    val vm = koinViewModel<DiscussionListViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.navigationEvents.collect { event ->
            when (event) {
                is DiscussionListNavigation.ToThread ->
                    navController.navigate("conversation_thread/${event.conversationId}")
            }
        }
    }
    DiscussionListScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                is DiscussionListEvent.RowTapped ->
                    navController.navigate("conversation_thread/${event.conversationId}")
                is DiscussionListEvent.SaveAsChannelRequested ->
                    vm.onEvent(event)
                DiscussionListEvent.BackTapped ->
                    navController.popBackStack()
            }
        },
    )
}
```

`Routes.DiscussionList = "discussions"` (in the private `Routes` object alongside `ChannelList`, `ConversationThread`, etc.). The route is **not yet linked from anywhere in the live app graph** — #26 adds the channel-list "Recent discussions (N) →" pill that calls `navController.navigate(Routes.DiscussionList)`. Until then, the route is reachable only via the previews or a hand-edited trigger.

`RowTapped` is dispatched on both wires (destination-side `navigate` *and* VM-side `navigationChannel`); see the VM doc for the rationale. `BackTapped` routes only at the destination — the VM treats it as a no-op `Unit` arm. `SaveAsChannelRequested` (#25) routes only at the VM as a `Unit` stub — the composable-side branch (`vm.onEvent(event)`) exists for `when` exhaustiveness but the actual work is the VM `TODO(phase 2)` no-op. Phase 2 replaces the VM stub with a real handler; the destination wiring does not change.

## Configuration

- **Strings:** `discussion_list_title` ("Recent discussions"), `discussion_list_empty` ("No discussions yet"), `cd_back` ("Back"), `save_as_channel_action` ("Save as channel…", #25). The first two follow the `<screen>_<role>` convention for user-facing copy (#23); `cd_back` is the project-wide back-button content description and is intentionally screen-agnostic — future screens with an `Icons.AutoMirrored.Filled.ArrowBack` reuse it rather than introducing per-screen variants. `save_as_channel_action` carries an ellipsis per Material guidance for "opens further UI" (anticipates the Phase 2 promotion dialog).
- **Imports:** `androidx.compose.material.icons.automirrored.filled.ArrowBack` (auto-mirrored RTL-aware), `androidx.compose.ui.draw.alpha` (the de-emphasis modifier), `androidx.compose.material3.{SwipeToDismissBox, SwipeToDismissBoxValue, rememberSwipeToDismissBoxState, DropdownMenu, DropdownMenuItem}` (#25 gesture surfaces). `material-icons-core` was already on the classpath via #21 (which pulled it in for the settings gear). No new dependency was added for #25 — the M3 swipe-and-menu APIs are already in the Compose BOM (`composeBom = "2026.02.01"`).
- **`AppModule`:** new line `viewModel { DiscussionListViewModel(get()) }` below the existing `ChannelListViewModel` registration. No new singletons.

## Edge cases / limitations

- **No FAB.** Discussions are created from the channel list (#22). Creating a new discussion *from* this screen is not in scope for Phase 0.
- **"Save as channel…" is gesture-only and Phase 0-stubbed.** Long-press menu + right-to-left swipe land in #25; both feed `DiscussionListEvent.SaveAsChannelRequested`, which the VM swallows as `Unit`. The Phase 2 promotion dialog replaces the stub. No leading-edge swipe, no undo affordance, no toast — explicit non-goals.
- **No instrumented UI tests.** Visual regression is covered by the two `@Preview` composables. The VM-level unit tests cover state-derivation and navigation-emit behavior.
- **`Loading` / `Error` / `Empty` are placeholder visuals.** Centered `Text` with no illustration, no retry button, no copy variation. Designed visuals land with their own tickets.
- **Route is currently unreachable from the live graph.** Intentional — #26 owns the entry-point pill. Don't add a temporary launcher tile or debug button "to be useful in the meantime".

## Related

- Ticket notes: [`../codebase/24.md`](../codebase/24.md), [`../codebase/25.md`](../codebase/25.md)
- Specs: `docs/specs/architecture/24-discussion-list-drilldown-screen.md`, `docs/specs/architecture/25-save-as-channel-affordances.md`
- Sibling: [ChannelListScreen](channel-list-screen.md) — structural clone source; the `Scaffold + LazyColumn + when (state)` skeleton, the `koinViewModel<…>()` + `collectAsStateWithLifecycle()` destination shape, and the inline `onEvent` → `navigate` translation pattern all originated there
- Upstream: [DiscussionListViewModel](discussion-list-viewmodel.md), [ConversationRow](conversation-row.md) (shared between tiers; gained the optional `onLongClick` parameter in #25), [Navigation](navigation.md) (the `discussions` route landed in `PyryNavHost`), [Data model](data-model.md) (`DefaultScratchCwd` → no workspace chip is the foundation of the AC)
- Downstream: #26 (channel-list "Recent discussions" pill — adds `navController.navigate(Routes.DiscussionList)` at the call site), Phase 2 promotion dialog (replaces the `SaveAsChannelRequested` VM stub with a real handler — name/workspace picker + `repository.promote(...)`), Phase 4 (`RemoteConversationRepository` swaps in; this screen is unchanged), designed `Loading` / `Empty` / `Error` visuals

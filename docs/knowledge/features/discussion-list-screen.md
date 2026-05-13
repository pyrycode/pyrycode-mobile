# DiscussionListScreen

Drilldown destination for unpromoted conversations (discussions), mounted at the `discussions` route. Sibling of [`ChannelListScreen`](channel-list-screen.md) — same `Scaffold + when (state)` skeleton, same `ConversationRow` items in `Loaded`, but with a back arrow instead of a settings gear, no FAB, and rows visibly de-emphasized via `Modifier.alpha(0.65f)` to signal the secondary tier.

Package: `de.pyryco.mobile.ui.conversations.list`. File: `DiscussionListScreen.kt`.

## What it does

Stateless `(state: DiscussionListUiState, onEvent: (DiscussionListEvent) -> Unit, modifier: Modifier)` composable. Renders an M3 `Scaffold` with a `TopAppBar` carrying the "Recent discussions" title and a back-arrow `IconButton`, and dispatches the four [`DiscussionListUiState`](discussion-list-viewmodel.md) variants:

- `Loading` → centered `Text("Loading…")` placeholder.
- `Empty` → centered `stringResource(R.string.discussion_list_empty)` ("No discussions yet").
- `Error(message)` → centered `"Couldn't load discussions: $message"`.
- `Loaded(discussions)` → `LazyColumn` of `ConversationRow`s keyed by `Conversation.id`, each wrapped with `Modifier.alpha(0.65f)`.

Emits `DiscussionListEvent.RowTapped(id)` on row tap and `DiscussionListEvent.BackTapped` on the back-arrow tap. The destination block in `MainActivity` routes both directly (`navController.navigate(...)` and `navController.popBackStack()`); the VM additionally emits `RowTapped` onto its one-shot `navigationEvents` channel so the unit test can observe it (see [VM doc](discussion-list-viewmodel.md#dual-nav-wiring-24)).

## Event surface

```kotlin
sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data object BackTapped : DiscussionListEvent
}
```

Defined at the top of `DiscussionListScreen.kt` (paralleling `ChannelListEvent` in `ChannelListScreen.kt`). No `SettingsTapped`, no `CreateDiscussionTapped` — the drilldown is read-only in Phase 0.

## Visual de-emphasis — alpha at the call site

```kotlin
ConversationRow(
    conversation = discussion,
    lastMessage = null,
    onClick = { onEvent(DiscussionListEvent.RowTapped(discussion.id)) },
    modifier = Modifier.alpha(0.65f),
)
```

**Mechanism: outer `Modifier.alpha(0.65f)` applied to each `ConversationRow` in the `LazyColumn`'s `items` lambda.** `ConversationRow` itself was not modified.

Rationale:

- **No edit fan-out into the shared row component.** A `deEmphasized: Boolean = false` parameter on `ConversationRow` would force every existing call site (`ChannelListScreen` + the two `@Preview`s in `ConversationRow.kt`) to pass `deEmphasized = false`. Alpha-from-outside is a one-line addition at the new call site only.
- **Survives Material 3 ripples correctly.** The ripple renders against the un-alpha'd `ListItem` content inside `ConversationRow`; an outer alpha dims the static content uniformly without flattening the touch feedback. A surface-variant tint would require wrapping each row in a `Surface(tonalElevation = …)` — more code, same outcome.
- **"Secondary tier", not "disabled".** 0.65 reads as de-emphasized; values below 0.5 read as disabled. Do not lower the value below 0.5.

Hit-testing is unaffected by `Modifier.alpha` — the row's `onClick` fires at full opacity. That's correct: discussions are interactive, just secondary. A truly-disabled visual would need `enabled = false` on the inner `ListItem` (propagates `LocalContentColor` through M3), not alpha.

The "workspace label is absent on discussion rows" guarantee is **not** enforced by this screen. It falls out of `ConversationRow` calling `condenseWorkspace(conversation.cwd)`, which returns `null` for `DefaultScratchCwd`; the `WorkspaceChip` is gated on a non-null result. Fake-seeded discussions use `DefaultScratchCwd`, so no chip renders. The `@Preview` composables are the regression tripwire — a future change to `ConversationRow` or `condenseWorkspace` that re-introduces a label would show up in `DiscussionListScreenLoadedPreview`.

## Previews

Two `@Preview` composables live in the file:

- **`DiscussionListScreenLoadedPreview`** — full `DiscussionListScreen(state = Loaded([d1, d2]), onEvent = {})` with two fixture discussions (one normal, one `isSleeping = true`), both at `DefaultScratchCwd`. Verifies the Loaded layout under `PyrycodeMobileTheme(darkTheme = false)`, the alpha dim, and the absence of workspace chips.
- **`DiscussionRowVsChannelRowPreview`** — a `Column { channelRow; discussionRow }` rendering the same shared `ConversationRow` at full opacity (channel: promoted, named, real cwd) above the alpha-0.65 instance (discussion: unpromoted, name=null, `DefaultScratchCwd`). This is the visual contract for AC #5 — the contrast between the two rows is the de-emphasis acceptance test. Don't move this preview out of `DiscussionListScreen.kt`; it belongs with the file that owns the alpha call site.

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
                DiscussionListEvent.BackTapped ->
                    navController.popBackStack()
            }
        },
    )
}
```

`Routes.DiscussionList = "discussions"` (in the private `Routes` object alongside `ChannelList`, `ConversationThread`, etc.). The route is **not yet linked from anywhere in the live app graph** — #26 adds the channel-list "Recent discussions (N) →" pill that calls `navController.navigate(Routes.DiscussionList)`. Until then, the route is reachable only via the previews or a hand-edited trigger.

`RowTapped` is dispatched on both wires (destination-side `navigate` *and* VM-side `navigationChannel`); see the VM doc for the rationale. `BackTapped` routes only at the destination — the VM treats it as a no-op `Unit` arm.

## Configuration

- **Strings:** `discussion_list_title` ("Recent discussions"), `discussion_list_empty` ("No discussions yet"), `cd_back` ("Back"). The first two follow the `<screen>_<role>` convention for user-facing copy (#23); `cd_back` is the project-wide back-button content description and is intentionally screen-agnostic — future screens with an `Icons.AutoMirrored.Filled.ArrowBack` reuse it rather than introducing per-screen variants.
- **Imports:** `androidx.compose.material.icons.automirrored.filled.ArrowBack` (auto-mirrored RTL-aware), `androidx.compose.ui.draw.alpha` (the de-emphasis modifier). `material-icons-core` was already on the classpath via #21 (which pulled it in for the settings gear).
- **`AppModule`:** new line `viewModel { DiscussionListViewModel(get()) }` below the existing `ChannelListViewModel` registration. No new singletons.

## Edge cases / limitations

- **No FAB.** Discussions are created from the channel list (#22). Creating a new discussion *from* this screen is not in scope for Phase 0.
- **No "Save as channel…" action.** Long-press / swipe affordances (#1) and the promotion dialog (Phase 2) land separately.
- **No instrumented UI tests.** Visual regression is covered by the two `@Preview` composables. The VM-level unit tests cover state-derivation and navigation-emit behavior.
- **`Loading` / `Error` / `Empty` are placeholder visuals.** Centered `Text` with no illustration, no retry button, no copy variation. Designed visuals land with their own tickets.
- **Route is currently unreachable from the live graph.** Intentional — #26 owns the entry-point pill. Don't add a temporary launcher tile or debug button "to be useful in the meantime".

## Related

- Ticket notes: [`../codebase/24.md`](../codebase/24.md)
- Spec: `docs/specs/architecture/24-discussion-list-drilldown-screen.md`
- Sibling: [ChannelListScreen](channel-list-screen.md) — structural clone source; the `Scaffold + LazyColumn + when (state)` skeleton, the `koinViewModel<…>()` + `collectAsStateWithLifecycle()` destination shape, and the inline `onEvent` → `navigate` translation pattern all originated there
- Upstream: [DiscussionListViewModel](discussion-list-viewmodel.md), [ConversationRow](conversation-row.md) (shared between tiers; not modified), [Navigation](navigation.md) (the `discussions` route landed in `PyryNavHost`), [Data model](data-model.md) (`DefaultScratchCwd` → no workspace chip is the foundation of AC #7)
- Downstream: #26 (channel-list "Recent discussions" pill — adds `navController.navigate(Routes.DiscussionList)` at the call site), Phase 2 promotion dialog (adds long-press → promote affordance), Phase 4 (`RemoteConversationRepository` swaps in; this screen is unchanged), designed `Loading` / `Empty` / `Error` visuals

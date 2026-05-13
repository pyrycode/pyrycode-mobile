# Spec — 'Save as channel' affordances on discussion rows (#25)

## Context

`DiscussionListScreen` (landed in #24) currently supports only row tap → drill into the thread. This ticket adds two gesture entry points for the most-used long-term affordance on this screen: **long-press** (discoverable, accessible) and **swipe** (quick, kinetic). Both fire the same event variant carrying the conversation id. The actual promotion dialog and `repository.promote(...)` call are Phase 2 work; the ViewModel handles the new event as a no-op stub with a `TODO(phase 2)` marker so the gesture surface ships independently.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt` — full file. The new event variant lives on `DiscussionListEvent`, and the `Loaded` branch's `LazyColumn` items lambda is the only place the gesture wrappers attach. The existing `ConversationRow` call uses `Modifier.alpha(0.65f)`; that stays.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt` — full file. The new `when` branch lands in `onEvent` next to `RowTapped` and `BackTapped`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:36-94` — `ConversationRow` signature and the `modifier.clickable(onClick = onClick)` chain on the `ListItem`. The spec adds **one** new parameter here (`onLongClick: (() -> Unit)? = null`) and switches the modifier chain to `combinedClickable` when it is non-null. Rationale in §Design.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt` (if present) — clone the existing `stubRepo` / fixture / `runTest` pattern for the new test. If it does not exist, follow the shape used in `ChannelListViewModelTest.kt`.
- `app/src/main/res/values/strings.xml` — existing `cd_*` and `*_title` strings; the new string `save_as_channel_action` lands here.
- `gradle/libs.versions.toml` — confirm `composeBom = "2026.02.01"` is unchanged; the spec uses `androidx.compose.material3.SwipeToDismissBox`, `DropdownMenu`, and `DropdownMenuItem` already in that BOM. No new dependency.
- `docs/specs/architecture/24-discussion-list-drilldown.md` — the predecessor spec; the design constraints in this spec build on its decisions (no FAB, alpha-dimmed rows, single `DiscussionListEvent` sealed interface).
- `CLAUDE.md` (project root) — "Stateless composables", "Sealed types for `UiState` and `Event`", "Test-first".

## Design

### New `DiscussionListEvent` variant

Extend the sealed interface in `DiscussionListScreen.kt`:

```kotlin
sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data class SaveAsChannelRequested(val conversationId: String) : DiscussionListEvent
    data object BackTapped : DiscussionListEvent
}
```

Both gesture surfaces (long-press menu item and swipe completion) construct `SaveAsChannelRequested(discussion.id)` and pass it to the existing `onEvent` callback.

### `ConversationRow` — add one generic gesture parameter

`ConversationRow.kt` gains a single optional parameter:

```kotlin
fun ConversationRow(
    conversation: Conversation,
    lastMessage: Message?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,   // NEW — default null preserves all existing call sites
)
```

The `ListItem` modifier chain switches based on `onLongClick`:

- `onLongClick == null` → `modifier.clickable(onClick = onClick)` (unchanged behaviour for `ChannelListScreen` and the two existing `@Preview`s).
- `onLongClick != null` → `modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)`.

Use `androidx.compose.foundation.combinedClickable` (already a stable foundation API; no opt-in required).

**Why this and not a screen-level pointer-input wrapper.** The ticket's technical note says "keep gesture handlers at the screen level so the shared component stays free of *discussion-specific* behaviour." A generic `onLongClick: (() -> Unit)? = null` parameter is **not** discussion-specific — it is the standard Compose convention for gesture-bearing primitives (cf. `Modifier.combinedClickable`). The constraint the ticket is protecting against — "ConversationRow knows about promotion / channels / discussions" — is satisfied: `ConversationRow` is told only "here is something to call on long press"; the meaning is supplied by the caller.

The alternative — a `Box` with `combinedClickable` wrapping the row at the screen level — does not work because `ConversationRow` applies its own `.clickable(onClick = onClick)` to the inner `ListItem`, which consumes the pointer event before the wrapping `combinedClickable` can see it. The remaining workarounds (custom `pointerInput { awaitEachGesture { … } }` that observes-but-does-not-consume) are brittle and re-implement what `combinedClickable` already gives you. One generic optional parameter on the shared composable is the cheapest correct path.

All existing call sites use `onLongClick = null` by default, so no other file needs to change for this addition. The fan-out of this signature change is **zero**.

### Gesture surface in `DiscussionListScreen`

Replace the `Loaded` branch's `items { … ConversationRow(...) }` body with a per-row composable that wraps the row in (a) a `SwipeToDismissBox` for the swipe affordance and (b) a `Box` that hosts the long-press `DropdownMenu` anchor. Both gestures call back into `onEvent(DiscussionListEvent.SaveAsChannelRequested(discussion.id))`.

Sketch (skeleton only — concrete details below):

```kotlin
items(items = state.discussions, key = { it.id }) { discussion ->
    DiscussionRow(
        discussion = discussion,
        onTap = { onEvent(DiscussionListEvent.RowTapped(discussion.id)) },
        onSaveAsChannel = { onEvent(DiscussionListEvent.SaveAsChannelRequested(discussion.id)) },
    )
}
```

Extract a private composable `DiscussionRow(discussion, onTap, onSaveAsChannel)` inside `DiscussionListScreen.kt` to keep the items lambda readable. The composable's contract:

- Holds the long-press menu's local UI state: `var menuExpanded by remember(discussion.id) { mutableStateOf(false) }`. The key on `discussion.id` ensures the state resets if list identity changes.
- Renders a `SwipeToDismissBox` whose `state = rememberSwipeToDismissBoxState(confirmValueChange = ...)` — see §Swipe below.
- Inside the dismiss box, a `Box` is the anchor for the `DropdownMenu`. The `ConversationRow` is its single child, with the existing `Modifier.alpha(0.65f)` and `onLongClick = { menuExpanded = true }`.
- A `DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false })` renders one `DropdownMenuItem` with text `R.string.save_as_channel_action`. Selecting the item dismisses the menu and calls `onSaveAsChannel()`.

#### Long-press menu — concrete details

- Menu position is anchored to the `Box` containing the row. M3 `DropdownMenu` defaults to a sensible anchor offset; no custom `offset` needed for Phase 0.
- The menu's single item:
  - `text = { Text(stringResource(R.string.save_as_channel_action)) }`
  - `onClick = { menuExpanded = false; onSaveAsChannel() }`
  - No leading icon (Phase 2 may add one).
- Accessibility: `combinedClickable` on `ConversationRow` automatically reports the long-press action via the row's semantics; no manual `semantics { onLongClick(...) }` block needed.

#### Swipe — concrete details

- Use `androidx.compose.material3.SwipeToDismissBox` with `rememberSwipeToDismissBoxState`.
- **Direction: `EndToStart` (right-to-left swipe).** Rationale: Android UI convention puts destructive/trailing actions on the right (Gmail archive, system notification dismiss); "Save as channel" is a trailing action on the row. The opposite direction (`StartToEnd`) is conventionally a leading "complete/confirm" action and is reserved for a future "mark as channel without naming" shortcut if that ever ships.
- The state's `confirmValueChange` lambda receives the target `SwipeToDismissBoxValue`:
  - On `SwipeToDismissBoxValue.EndToStart`: call `onSaveAsChannel()`, then `return false`. Returning `false` prevents the swipe from settling into the dismissed state, so the row springs back to its resting position. This is required because promotion is a stub in Phase 0 — the row must remain visible.
  - On any other value: `return true` (no-op direction; default behaviour).
- `enableDismissFromStartToEnd = false`, `enableDismissFromEndToStart = true`.
- `backgroundContent`: a `Box` filling the dismiss box's width, with `MaterialTheme.colorScheme.secondaryContainer` background and a trailing-aligned `Text(stringResource(R.string.save_as_channel_action))` plus 16.dp horizontal padding. No icon for Phase 0 (the M3 BOM is opinionated about icon styling; defer).

The `SwipeToDismissBox`'s `content` lambda renders the `Box` + `ConversationRow` + `DropdownMenu` group described above.

### ViewModel — stub handler

`DiscussionListViewModel.onEvent` gains one new branch:

```kotlin
is DiscussionListEvent.SaveAsChannelRequested -> Unit
// TODO(phase 2): show promotion dialog and call repository.promote(event.conversationId, name, cwd)
```

`Unit` is the entire body; no logging, no toast, no navigation. This matches the ticket's "no-op event the ViewModel swallows". The TODO marker documents the Phase 2 follow-up; do not pre-build any plumbing for it.

### Strings (`app/src/main/res/values/strings.xml`)

Add one new string:

- `save_as_channel_action` → `"Save as channel…"` (note the ellipsis — Material guidance for "opens further UI").

### Preview

Add one new `@Preview` to `DiscussionListScreen.kt`:

- `DiscussionListScreenWithMenuOpenPreview` — same fixtures as `DiscussionListScreenLoadedPreview`, but the first row's `menuExpanded` state is hoisted up via a small helper / forced-true pattern so the dropdown is visible in the static preview. One concrete way: lift the `menuExpanded` state in `DiscussionRow` into a parameter with a default (`menuInitiallyExpanded: Boolean = false`), used only by previews. Acceptable per the AC ("interactive preview acceptable if static can't show the gesture") — but static-with-forced-state is cheaper to render in the IDE.
- The existing `DiscussionListScreenLoadedPreview` and `DiscussionRowVsChannelRowPreview` previews stay; both compile unchanged because `onLongClick` defaults to `null`.

The swipe affordance does not need its own preview — `SwipeToDismissBox` previews are not visually informative without interaction. The unit test covers behaviour.

## State + concurrency model

- No new ViewModel state. `state: StateFlow<DiscussionListUiState>` is unchanged.
- No new `viewModelScope.launch` — `SaveAsChannelRequested` is `Unit` in Phase 0.
- Composable-side state: `var menuExpanded by remember(discussion.id) { mutableStateOf(false) }` per row, plus the `SwipeToDismissBoxState` from `rememberSwipeToDismissBoxState`. Both are correctly scoped to the row composition; no leakage across list items.
- Recomposition: `DiscussionRow` is keyed by `discussion.id` (via `LazyColumn`'s `key = { it.id }`), so swiping or opening a menu on one row does not invalidate siblings.
- No dispatcher concerns; this is pure UI state.

## Error handling

No new failure modes. The gesture is fire-and-forget into the VM, which swallows it. Repository, navigation, and existing error states are untouched.

## Testing strategy

One new unit test added to `DiscussionListViewModelTest.kt`:

- **`saveAsChannelRequested_isNoOp_inPhase0`** — VM receives `DiscussionListEvent.SaveAsChannelRequested("disc-1")`. Assert that no value arrives on `vm.navigationEvents` within a short timeout. Pattern: wrap `vm.navigationEvents.first()` in `withTimeoutOrNull(50)` and assert `null`. The state remains whatever the upstream fake emits (the test should make no assertions about state changes triggered by this event — there are none).

That is the only new test. Rationale: the AC's "stub behaviour" is "does nothing observable"; the test verifies precisely that — nothing observable — and nothing more.

No Compose UI test (`androidTest`) is required by the AC. The `@Preview` with the dropdown forced open serves as the visual contract.

Build / unit-test verification (per AC #6): the developer runs `./gradlew assembleDebug` and `./gradlew test` once at the end. Both must pass.

## Constraints / non-goals

- **Do not implement promotion.** No dialog, no `repository.promote(...)` call, no channel-name picker, no workspace picker. The handler body is the literal token `Unit` followed by a `TODO(phase 2)` comment.
- **Do not add `onLongClick` to `ChannelListScreen`'s `ConversationRow` call.** Channels are persistent; long-press on a channel will eventually map to a different action (archive? rename? — undecided). Out of scope here.
- **Do not introduce a new `Conversations`-level helper for gesture wrapping.** The `DiscussionRow` private composable stays inside `DiscussionListScreen.kt`. If a second screen later needs the same wrapper, lift it then.
- **Do not add a leading-edge swipe action.** AC explicitly says one direction.
- **Do not add an undo affordance.** Out of scope per the ticket's "Out of Scope" section.
- **Single source of state** — the VM still holds `UiState`. The two new pieces of composable-side state (`menuExpanded`, `SwipeToDismissBoxState`) are pure UI state with no business meaning and are correctly hoisted at the row level, not the VM.
- **Do not modify `ConversationRow`'s callers in `ChannelListScreen` or its previews.** They keep the existing 3-argument call shape; the new parameter is opt-in via the default.

## Open questions

- **Should the swipe also dismiss the row visually for a moment before springing back?** The current design returns `false` from `confirmValueChange`, which I expect to snap the row back without a "swiped" intermediate state. If the snap-back looks jarring in practice, the Phase 2 work that adds the promotion dialog can switch to manually animating `state.reset()` after the dialog opens. Acceptable for Phase 0; do not pre-build.
- **Why `Unit` and not `Log.d(...)` for the stub?** The codebase has no logging convention yet and no `Log` imports anywhere under `app/src/main/java/de/pyryco/mobile/`. Introducing one here would be cargo-culted. `Unit` is honest about the no-op.

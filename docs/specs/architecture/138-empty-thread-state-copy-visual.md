# 138 — Empty-thread state copy + visual

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:34-123` — the Scaffold body. Empty state is inserted as a sibling branch to the `LazyColumn` (lines 83-115). The workspace chip (#137) at lines 70-79 already conditions on `!state.isPromoted && !state.hasMessages`; this ticket coexists with it inside the same `Column` and uses the same `hasMessages` signal for the message-presence half of its predicate.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:20-28` — `ThreadUiState` already exposes `hasMessages: Boolean` (added in #137). No widening needed.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:127-145` — the canonical "centered text fills the remaining space" pattern in this codebase: `Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text(stringResource(...)) }`. Mirror this shape.
- `app/src/main/res/values/strings.xml:6-8` — existing empty-state strings (`channel_list_empty`, `discussion_list_empty`) for naming convention. The new string follows the same `<area>_empty` pattern.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/WorkspaceChip.kt:36-67` — preview convention in this package: `@Preview` + `PyrycodeMobileTheme(darkTheme = …)` wrapping a `Surface`. Dark preview uses `uiMode = Configuration.UI_MODE_NIGHT_YES`. The new component's previews mirror this exactly.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` — another peer composable using `MaterialTheme.typography.bodyMedium` and `colorScheme.onSurfaceVariant`; confirms the M3 token spelling in this codebase.
- `docs/specs/architecture/137-workspace-chip-empty-new-discussion-thread.md` — Design source section context: Figma node `16:8` is the populated thread; there is no separate empty-state node drawn. AC copy and styling drive the visual.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Node `16:8` is the **populated** Conversation Thread Screen — the canvas frame this ticket lives inside, but the rendered Figma shows a thread with messages, a code-block, a session delimiter, etc. There is no separate empty-state node drawn in Figma. Styling is anchored by the ticket's literal AC ("`bodyMedium` / `onSurfaceVariant`, vertically centered in the available space") rather than a pixel-exact reference. Visual placement: the prompt fills the message-list area (where the `LazyColumn` would render), centered both horizontally and vertically; on a fresh discussion the `WorkspaceChip` (#137) sits above it inside the same outer `Column`, with the chip-row's existing `padding(horizontal = 16.dp, vertical = 8.dp)` separating it from the centered prompt below. No new design tokens introduced.

## Context

A brand-new discussion (or a freshly-created channel with no messages) currently opens with an empty `LazyColumn` rendered into a blank gray panel under the connection banner and (for unbound discussions) the workspace chip. There is nothing telling the user that the screen is intentional and that the next step is the composer at the bottom.

This ticket adds a single centered prompt that fills the message-list area whenever the thread has no messages. The chip-presence math is unchanged: the chip (#137) is already gated on `!isPromoted && !hasMessages`; the empty-state prompt is gated on `!hasMessages` only (it also renders for the rare empty-channel case, where the chip itself is hidden because `isPromoted == true`).

`#137` (workspace chip on empty new-discussion thread) and `#136` (above-delimiter opacity treatment) have already landed; this ticket sits cleanly on top with no further widening of `ThreadUiState` or `ThreadViewModel`.

## Design

### `EmptyThreadState` composable (new file)

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/EmptyThreadState.kt`

Public composable signature:

```kotlin
@Composable
fun EmptyThreadState(
    modifier: Modifier = Modifier,
)
```

Body: a single `Box` that centers a single `Text` reading the new string resource.

- `Box(modifier = modifier, contentAlignment = Alignment.Center)` — the caller (`ThreadScreen`) supplies the size-determining modifier (`fillMaxWidth().weight(1f)`); the composable itself takes no opinion on outer size.
- `Text(stringResource(R.string.thread_empty_state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)` — single text node, no rich content, no icon, no secondary line. Matches AC #2 literally and the `ChannelListScreen` empty-state pattern.
- No `Modifier.padding` inside the composable. Horizontal padding (to keep the text from kissing the screen edges on narrow widths) is supplied by the caller as part of the size modifier.

Two `@Preview` composables (light + dark) wrapping the component in `PyrycodeMobileTheme(darkTheme = …) { Surface { … } }`. Mirror the `WorkspaceChip.kt` preview shape exactly, including the dark preview's `uiMode = Configuration.UI_MODE_NIGHT_YES`. For both previews give the `EmptyThreadState` a finite size via `Modifier.fillMaxWidth().height(400.dp)` so the centering is visible in the IDE preview pane (in the real screen the size comes from `weight(1f)`, which has no equivalent inside a preview wrapper).

### `ThreadScreen` wiring

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`

Inside the `Column` body of the `Scaffold` (currently lines 63-116), replace the unconditional `LazyColumn` block (lines 80-115) with an `if (!state.hasMessages) … else …` branch:

```kotlin
if (!state.hasMessages) {
    EmptyThreadState(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 24.dp),
    )
} else {
    val reversedItems = state.items.asReversed()
    val cutoffChronologicalIndex =
        remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        reverseLayout = true,
    ) {
        // existing itemsIndexed body, unchanged
    }
}
```

Notes:

- **Predicate choice.** `!state.hasMessages` (excludes `SessionBoundary`-only edge case), not `state.items.isEmpty()`. Two reasons: (a) consistency with the chip's `!hasMessages` half — chip and empty-state appear/disappear together on a fresh discussion; (b) the rare `SessionBoundary`-only edge case from #137 (user taps chip → workspace change emits a boundary → no `MessageItem` yet) renders correctly as "still empty" instead of as a lonely boundary line floating in the void. This matches the user-facing intent of AC #4 ("Hidden as soon as the message list has at least one entry") — a boundary is metadata, not an entry the user authored.
- **The `cutoffChronologicalIndex` `remember` block** must live inside the `else` arm only — its key is `state.items`, which is non-empty there. Moving it into the empty arm would compute on `emptyList()` and waste a recomposition slot.
- **Padding.** `horizontal = 24.dp` on the empty-state box keeps the prompt centered with breathing room on the narrowest supported width (412.dp preview, 360.dp portrait minimum). The chip above uses `horizontal = 16.dp`; the additional 8.dp on the empty-state prompt is intentional — a centered single line wants more inset than a left-aligned chip.

No changes to the `ThreadScreen` parameter list, the Scaffold structure, or the `WorkspacePicker` render after the Scaffold.

### `strings.xml` addition

File: `app/src/main/res/values/strings.xml`

Add one string (alphabetical placement near `thread_input_placeholder` on line 38 is fine):

```xml
<string name="thread_empty_state">Send a message to get started</string>
```

Naming follows the existing `<area>_empty…` pattern (`channel_list_empty`, `discussion_list_empty`, `archived_empty_channels`). Use `thread_empty_state` rather than `thread_empty` because the literal AC copy is a full sentence-style prompt (not a label) and "state" disambiguates from a potential future "no results" empty for a thread search feature.

### Preview at the screen level (optional)

The component-level previews in `EmptyThreadState.kt` satisfy AC #5 ("Preview shows the empty state"). The developer MAY additionally add a screen-level `@Preview` to `ThreadScreen.kt` showing a fresh-discussion `ThreadUiState` (`isPromoted = false`, `items = emptyList()`, `workspaceLabel = "scratch"`) so the chip + empty-state composition can be eyeballed together — this is useful documentation but is not required by AC. If added, mirror the existing `ThreadScreenLightPreview` / `ThreadScreenDarkPreview` pair shape (lines 179-217) and name them `ThreadScreenEmptyLightPreview` / `ThreadScreenEmptyDarkPreview`.

## State + concurrency model

No new state, no new flows, no new `viewModelScope` jobs. The empty-state branch is a pure UI decision driven by the existing `hasMessages` field on `ThreadUiState`. `combine` upstream of `state` is unchanged. Recomposition trigger: `hasMessages` flips from `true` → `false` (on initial subscription) or `false` → `true` (when the first `MessageItem` arrives via `repository.observeMessages`); both are already covered by the existing `WhileSubscribed(5_000)` `stateIn` keep-alive.

## Error handling

No new failure modes. The composable has no I/O, no `LaunchedEffect`, no `derivedStateOf`. `stringResource` lookup is compile-time-verified; a missing string is a build error, not a runtime one.

## Testing strategy

### Unit tests

**Not required.** The change is a pure UI rendering decision driven by an existing state field; the `hasMessages` field's transitions are already covered by the existing `ThreadViewModelTest` cases added in #137 (notably `state_chipFields_reflectChannelAndMessagePresence` and the `state_items_reflectsObserveMessagesStream` case visible in codegraph). No new ViewModel surface to assert.

### Compose / androidTest

**Not required by AC.** AC #5 demands a preview, not an instrumented test. The thread package has no existing androidTest harness for `ThreadScreen` (`app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/` does not exist) and adding one for this ticket would be a sidewinder of ~100 LOC the AC doesn't request. Developer MAY add one at their discretion (would assert `onNodeWithText("Send a message to get started").assertIsDisplayed()` when state has no messages, and `assertDoesNotExist()` when at least one `MessageItem` is present) — explicitly out of scope of the size budget.

### Manual verification

The developer should run the IDE preview pane after implementation to confirm:

1. `EmptyThreadStateLightPreview` renders the prompt centered in the bordered surface, text in `bodyMedium` on `onSurfaceVariant` (a medium-grey on light backgrounds, light-grey on dark).
2. `EmptyThreadStateDarkPreview` likewise.
3. (Optional, if added) The screen-level empty preview renders: top app bar → connection banner → workspace chip → centered empty-state prompt → input bar, with the prompt filling the vertical space between chip and input bar.

### Test commands

- `./gradlew test` — should remain green; no new unit tests, existing tests unaffected.
- `./gradlew lint` — should be clean; one new file, one string addition, one small `ThreadScreen` edit.
- `./gradlew assembleDebug` — should build; preview-only files compile with the rest of `main`.

## Open questions

1. **Should the empty state show only on fresh discussions, not on freshly-promoted-or-newly-created channels?** As specified, the empty state renders whenever `!state.hasMessages` regardless of `isPromoted`. This is intentional (matches AC #1 literally — `messages.isEmpty()` with no channel/discussion qualification) and consistent with the codebase's other empty-states (`channel_list_empty` is shown for any zero-channel state, not gated on context). If product later wants channel-specific copy ("This channel has no messages yet — say hi"), the predicate gains an `isPromoted` branch and a second string. Out of scope here.

2. **Centered prompt vs. centered + icon vs. centered + secondary line.** AC specifies one short line and `bodyMedium`/`onSurfaceVariant` — no icon, no secondary. Followed literally. If a future a11y / UX audit wants a leading icon or a "(or use the mic)" secondary hint, the composable's signature is stable (single Text in a Box) and the change is local.

3. **The 24.dp horizontal padding on the empty-state box.** Not pixel-anchored to a Figma node (the empty-state variant isn't drawn). The choice mirrors typical M3 dialog horizontal padding for centered single-line content and gives the prompt visible breathing room on the narrowest supported width. If the developer's preview render looks off, this is the single number to tune; the rest of the layout is structurally locked by `weight(1f)` and `Alignment.Center`.

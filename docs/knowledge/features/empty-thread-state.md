# EmptyThreadState

Stateless composable (#138) that renders the centered prompt "Send a message to get started" into the message-list area of a [`ThreadScreen`](thread-screen.md) whenever the thread has no `ThreadItem.MessageItem` rows. Single text node, `bodyMedium` / `onSurfaceVariant`, no icon, no secondary line — by design.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/EmptyThreadState.kt`). Sibling of [`WorkspaceChip`](workspace-chip.md), [`SessionBoundaryDelimiter`](session-boundary-delimiter.md), [`MessageBubble`](message-bubble.md), [`ConnectionBanner`](connection-banner.md). Figma: no separate empty-state node — node [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) is the **populated** thread; styling is anchored by the AC text ("centered, `bodyMedium`, `onSurfaceVariant`"), not a pixel-exact reference.

## Shape

```kotlin
@Composable
fun EmptyThreadState(
    modifier: Modifier = Modifier,
)
```

- **`public` (no `internal`).** Consumed from the sibling `ui/conversations/thread/` package.
- **`modifier: Modifier = Modifier`.** The caller (`ThreadScreen`) supplies the size-determining modifier (`fillMaxWidth().weight(1f).padding(horizontal = 24.dp)`); the composable itself takes no opinion on outer size. Keeping `weight` and padding outside the body lets previews substitute a finite `height(...)` for the IDE pane (where `weight(1f)` has no parent `Column` to weight against).

## What it renders

A single `Box(modifier, contentAlignment = Alignment.Center)` wrapping a single `Text`:

```kotlin
Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Text(
        text = stringResource(R.string.thread_empty_state),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
```

- **`R.string.thread_empty_state = "Send a message to get started"`** — added in #138; one of the `<area>_empty…` family (`channel_list_empty`, `discussion_list_empty`, `archived_empty_channels`).
- **`MaterialTheme.typography.bodyMedium` + `colorScheme.onSurfaceVariant`** — matches AC literally and the M3 token spelling [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) uses for its explanatory line.
- **No icon, no secondary line.** The AC specifies one short line; followed literally. Composable signature is stable if a future a11y/UX audit wants a leading icon or hint.

## Visibility gate

The gate lives **inlined at the call site** in `ThreadScreen.kt`, not folded into a hidden boolean on `ThreadUiState`:

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
        modifier = Modifier.fillMaxWidth().weight(1f),
        reverseLayout = true,
    ) {
        // existing itemsIndexed body unchanged from #136/#246
    }
}
```

The predicate is **`!state.hasMessages`, not `state.items.isEmpty()`** — `hasMessages` counts only `ThreadItem.MessageItem` (added in #137 via `items.any { it is ThreadItem.MessageItem }`). A `ThreadItem.SessionBoundary`-only stream (e.g. the user taps [`WorkspaceChip`](workspace-chip.md) → `changeWorkspace` emits a `WorkspaceChange` boundary before any message lands) renders correctly as "still empty" with the prompt visible, instead of as a lonely delimiter floating in the void above the input bar. The same `!hasMessages` signal gates the [`WorkspaceChip`](workspace-chip.md) on the chip-presence half of its predicate, so chip and prompt appear and disappear together on a fresh discussion.

The `remember(state.items) { mostRecentSessionBoundaryIndex(state.items) }` block from [#136](../codebase/136.md) lives **inside the `else` arm only** — its key `state.items` is `emptyList()` in the empty arm, so computing the helper there is wasted work and would pre-allocate an unconsumed recomposition slot.

## How it fits in the thread body

The empty-state branch is one slot in the [`ThreadScreen`](thread-screen.md) body `Column` (introduced in [#201](../codebase/201.md)). Render order top-to-bottom:

1. `ConnectionBanner(state = connectionState, onRetry = onRetry)` (#201) — zero-height under steady-state `Connected`.
2. `WorkspaceChip` (#137) — gated on `!isPromoted && !hasMessages`. Visible only on fresh, unpromoted, message-less discussions.
3. **`EmptyThreadState` OR `LazyColumn`** (#138). The empty-state arm takes `weight(1f)` so it fills exactly the space the list would have occupied between the chip (or banner) and the `ThreadInputBar` in the `bottomBar` slot.
4. (`ThreadInputBar` lives in the `Scaffold.bottomBar` slot, not in this `Column`.)

On a fresh discussion the user sees: banner (hidden) → chip → centered prompt → composer. As soon as the first message lands, both chip and prompt disappear in the same `combine`-arm re-emission and the `LazyColumn` takes over the `weight(1f)` slot. Channels (`isPromoted = true`) with zero messages skip the chip but still show the prompt — same `!hasMessages` gate, independent of the `!isPromoted` half.

## Previews

Two `@Preview`s at the bottom of `EmptyThreadState.kt`:

- **`EmptyThreadStateLightPreview`** — `PyrycodeMobileTheme(darkTheme = false) { Surface { EmptyThreadState(modifier = Modifier.fillMaxWidth().height(400.dp).padding(horizontal = 24.dp)) } }`. `widthDp = 412`, `showBackground = true`.
- **`EmptyThreadStateDarkPreview`** — same shape with `darkTheme = true` and `uiMode = Configuration.UI_MODE_NIGHT_YES`.

Both pass a **finite `height(400.dp)`** rather than the real screen's `weight(1f)`, because `weight` has no parent `Column` to weight against inside a `@Preview` and would collapse to zero height. Both wrap in `Surface` so the prompt renders against the theme's surface color rather than the bare `showBackground = true` white/black — same posture as `WorkspaceChip`'s previews from [#137](../codebase/137.md). Required by AC #5.

## Edge cases / limitations

- **The 24.dp horizontal padding is not Figma-anchored.** Figma node `16:8` shows the populated thread; there is no empty-state variant drawn. The `24.dp` mirrors typical M3 dialog horizontal padding for centered single-line content and gives the prompt visible breathing room on the 360dp portrait minimum. The chip above uses `horizontal = 16.dp` because a left-aligned chip needs less inset than a centered line. Tune the empty-state padding alone if a future preview-render review wants different breathing room; the rest of the layout is structurally locked by `weight(1f)` and `Alignment.Center`.
- **No `AnimatedVisibility` on the gate.** The prompt pops in/out instantly when `hasMessages` flips. Consistent with the rest of the screen's compose-state transitions ([`WorkspaceChip`](workspace-chip.md) gate, banner gate); a `Crossfade` between `EmptyThreadState` and `LazyColumn` is a one-line wrap if designer signs off on a duration.
- **No icon and no secondary line — by design.** AC says one short line. Composable signature is stable (single `Text` in a `Box`) so an icon or hint can land additively if a11y/UX audit asks for one.
- **Channels with zero messages also render the prompt.** The gate is `!hasMessages` regardless of `isPromoted`. A freshly-created empty channel sees the same prompt a fresh discussion does. If product later wants channel-specific copy ("This channel has no messages yet — say hi"), the predicate gains an `isPromoted` branch and a second string. Out of scope at #138.
- **`Conversation.cwd`-driven `SessionBoundary`-only streams still show the prompt.** Tapping the chip → `changeWorkspace` emits a `WorkspaceChange` boundary into the stream; `hasMessages` stays `false` (boundaries don't count), so the prompt remains visible until the user sends a real message. Once the first message lands the prompt disappears and the boundary renders at the top of the `LazyColumn` with the message below it. Matches the user-story intent of AC #4.

## Related

- Ticket notes: [`../codebase/138.md`](../codebase/138.md) (this slice).
- Spec: `docs/specs/architecture/138-empty-thread-state-copy-visual.md`
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) — Conversation Thread Screen canvas frame. Populated variant only; no empty-state node drawn.
- Consumer: [Thread screen](thread-screen.md) — the prompt's only call site. Coexists with [`WorkspaceChip`](workspace-chip.md) in the same body `Column` on fresh discussions; both gate on `!hasMessages`.
- Upstream signal: `ThreadUiState.hasMessages` (added in [#137](../codebase/137.md) as `items.any { it is ThreadItem.MessageItem }` on the `combine` arm; computed off the `observeMessages` stream from the [conversation repository](conversation-repository.md)).
- Sibling list body: the [#136](../codebase/136.md) above-delimiter dim and [#246](../codebase/246.md) sealed-interface dispatch onto [`MessageBubble`](message-bubble.md) / [`SessionBoundaryDelimiter`](session-boundary-delimiter.md) live inside the `else` arm of the empty-state branch — they don't run while the prompt is showing.
- Downstream / open:
  - Open: channel-specific copy under `isPromoted = true` if product asks (today the gate is `!hasMessages` regardless).
  - Open: leading icon or secondary "(or use the mic)" hint after an a11y/UX audit.
  - Open: `Crossfade` animation between `EmptyThreadState` and `LazyColumn` when `hasMessages` flips.

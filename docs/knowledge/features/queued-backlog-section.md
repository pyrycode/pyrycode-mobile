# Queued backlog section — `QueuedBacklog`

The **UI half of the queued-message backlog** ([#461](../codebase/461.md), split from #429): a stateless
composable that, while the active conversation's agent is busy and the daemon is buffering the user's
turns, renders the ordered backlog of **messages still waiting to send** as a **de-emphasized foot-of-list
section** below the thread — so the user knows what is queued instead of wondering whether the turns they
fired during a long response were dropped.

The signal it renders is the **data half** — [`observeQueue(conversationId)`](queued-backlog.md)
([#460](../codebase/460.md)), surfaced onto [`ThreadViewModel`'s `ThreadUiState.queuedMessages`](thread-screen.md).
This component adds **no data access** and **no new data path**: it receives the already-decoded, validated
`List<QueuedMessage>` as hoisted state and renders it. It is the render-after-decode twin of
[`ThinkingIndicator`](thinking-indicator.md) (#407) and [`StallPromotionBanner`](stall-promotion-banner.md)
(#396) — same component shape, but hoisted onto `ThreadUiState` rather than a sibling `StateFlow` (see
[Wiring](#wiring)). Read-only: the per-row **drop** affordance is the next slice, #467 — its data-layer
send (`dropQueuedMessage` → `dequeue_message`) already shipped in [#466](../codebase/466.md).

Package: `de.pyryco.mobile.ui.conversations.components`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`). File: `QueuedBacklog.kt`.

## Shape

```kotlin
@Composable
fun QueuedBacklog(
    queued: List<QueuedMessage>,
    modifier: Modifier = Modifier,
)
```

The one load-bearing param (`queued`) carries no default. The composable is a **pure function of the
list** — no `ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no callback (the
drop affordance is #467; its `dropQueuedMessage` send shipped in #466). Statelessness is an AC (#4), not a
style choice.

## What it does

- **`if (queued.isEmpty()) return`** — emits nothing when the backlog is empty (zero composition, zero
  height), the [`ThinkingIndicator`](thinking-indicator.md) / [`StallPromotionBanner`](stall-promotion-banner.md)
  early-return idiom. The section disappears the instant the list empties (AC #2/#3) because it holds no
  local state.
- When non-empty, renders a `Column` carrying the a11y group + the test anchor, containing:
  - a `thread_queued_backlog_label` ("Queued") caption in `MaterialTheme.typography.labelSmall` /
    `onSurfaceVariant`;
  - **one `QueuedMessageRow` per entry, in `queued` order verbatim** — no sort, no dedup (FIFO == wire
    order, AC #1; the data layer already preserves wire order, see [Queued backlog](queued-backlog.md)).
- **Each row mirrors the sent user bubble, de-emphasized (the visual distinction AC #1 requires).** A
  private `QueuedMessageRow` lays out an **end-aligned** `Row` at `Modifier.alpha(QUEUED_ALPHA = 0.6f)`:
  - a leading **decorative** "waiting" glyph — `Icons.Outlined.Schedule`, tinted `onSurfaceVariant`,
    `contentDescription = null` (the row text + the section content-description carry the meaning;
    `material-icons-extended` is already a dependency, used by [`ToolCallRow`](tool-call-row.md));
  - a `Surface` bubble in the **same rounded user shape** (`bottomEnd = 6.dp` notch) and the same
    `primaryContainer` / `onPrimaryContainer` colour family as [`UserMessageBubble`](message-bubble.md),
    holding **plain** `Text(entry.text, bodyMedium)` — **never `MarkdownText`**, matching
    `UserMessageBubble`, since this is un-sent user *input*.
- **Accessibility** — the `Column` carries `Modifier.semantics(mergeDescendants = true) { contentDescription
  = … }` sourced from `cd_thread_queued_backlog` ("Queued messages waiting to send"). Merging descendants
  makes TalkBack announce the section once as a single node; it is also the Compose-test handle.

### Styling (design-owed)

Queued entries deliberately read as **not yet sent**: the `primaryContainer` user-bubble shape/colour at
`QUEUED_ALPHA = 0.6f` (in the spirit of [`ThreadScreen`](thread-screen.md)'s `ABOVE_DELIMITER_ALPHA = 0.55f`
de-emphasis), plus the leading waiting glyph and the "Queued" caption, distinguish them from the
full-opacity sent / streamed bubbles. The Figma `16-8` frame has **no backlog treatment drawn yet** (same
design-owed status as the sibling stream-UI tickets #386 / #388 / [#396](../codebase/396.md)); until it
lands the visual follows the app's existing message-row idiom, exactly as `ThinkingIndicator` /
`StallPromotionBanner` shipped their M3 defaults. When the frame arrives, re-tune alpha / glyph / caption /
spacing here — no contract change.

### Constants

File-private `val`s at the top of `QueuedBacklog.kt` — no raw `.dp` literal in the body, the same
named-constant posture as the sibling components. The bubble shape/sizing constants
(`QueuedBubbleShape`, `QueuedBubbleMaxWidth`, the bubble paddings) **mirror** `UserMessageBubble`'s — those
are `private` to `MessageBubble.kt`, so the handful needed are **redeclared locally** rather than widened
to `internal` (a size-S slice doesn't carry a cross-component refactor; rule-of-three triggers extraction).
`QUEUED_ALPHA = 0.6f` and the glyph size/gap are local too.

## Placement in the thread

[`ThreadScreen`](thread-screen.md) renders the section in the content `Column` **between** the message list
and the foot-most [`ThinkingIndicator`](thinking-indicator.md) — **outside** the scrolling `LazyColumn`:

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(…) else LazyColumn(reverseLayout = true, …) { … }  // weight(1f)
    QueuedBacklog(queued = state.queuedMessages, modifier = Modifier.fillMaxWidth())            // NEW (#461)
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())              // unchanged
}
```

Why this seam:

- The backlog **continues the user's side of the conversation** (a list of message text the user just
  fired), so it sits directly below the message list — content, not a top banner.
- It is a **separate wrap-content section, not a `LazyColumn` row**, so the list's keying / alpha-dimming /
  auto-scroll logic stays untouched (lowest blast radius) — the established foot-of-list-affordance pattern
  (`ThinkingIndicator`, `StallPromotionBanner`). The list's `weight(1f)` absorbs the section's height; real
  backlogs are short (the daemon's `msgqueue` cap is server-side).
- `ThinkingIndicator` keeps its shipped **foot-most** position (an at-work status); the backlog sits just
  above it (queued content). The two are independent and can both render at once (busy + queued is the
  common case).

## Wiring

The queue is threaded as a **`ThreadUiState` field** — **not** a sibling `StateFlow` like
[`isStalled`](stall-promotion-banner.md) / `isThinking`. This is the deliberate divergence from the two
render twins:

- **`ThreadViewModel`** surfaces it on the existing single `state: StateFlow<ThreadUiState>`. The `state`
  `combine` is already at the 5-arg typed ceiling, so `observeQueue` is folded with the existing
  `threadItems` flow into a file-private pre-combiner (the same trick `TransientDialogs` / `RunConfig` use):

  ```kotlin
  data class ThreadUiState(/* … */, val queuedMessages: List<QueuedMessage> = emptyList(), /* … */)

  private data class ThreadContent(val items: List<ThreadItem>, val queued: List<QueuedMessage>)

  private val threadContent: Flow<ThreadContent> =
      combine(threadItems, repository.observeQueue(conversationId)) { items, queued ->
          ThreadContent(items, queued)
      }
  // in the state combine: the threadItems arm becomes threadContent; items = content.items,
  // queuedMessages = content.queued, hasMessages from content.items
  ```

  **No new operator, no new `StateFlow`, no constructor / DI / interface change** — `observeQueue` is on the
  [`ConversationRepository`](conversation-repository.md) interface (#460), reached through the
  [`StableConversationRepository`](stable-conversation-repository.md) facade the VM already holds. Both
  combine inputs seed immediately (the `threadItems` `scan` seeds `emptyList()`; `observeQueue` seeds
  `emptyList()`) and each carries `distinctUntilChanged`, so the combine never stalls.
- **`ThreadScreen`** needs **no new parameter** — `queuedMessages` rides the already-collected `state`.
- **`MainActivity`** is **untouched** — the key contrast with `isStalled` / `isThinking`, each of which
  added a `ThreadScreen` param + a `collectAsStateWithLifecycle()` wiring line. Surfacing content onto the
  state avoids that call-site cascade entirely.

### Why a `ThreadUiState` field, not a sibling `StateFlow`

`isThinking` / `isStalled` / `currentModal` are sibling `StateFlow`s because each is a **transient
cross-cutting boolean/scalar** (a spinner, a degrade CTA, a single app-level overlay). The queue is
different: it is **thread content** — an ordered list of message text, the same category as
`ThreadUiState.items`. AC #4 asks for exactly this ("state in via `UiState`; the composable reads no
repository directly"). Decide by signal class, not by reflex: cross-cutting scalar → sibling `StateFlow`;
conversation content → `ThreadUiState`. (Code review endorsed the call.)

## Recomposition / stability

- `queued: List<QueuedMessage>` is a stable read (`QueuedMessage` is a `Long`/`String`/`Instant` data
  class) ⇒ `QueuedBacklog` is **restartable + skippable** and recomposes only when the list changes. No
  lambda capture (read-only — no callbacks this slice), no internal mutable state, no `remember`, no side
  effect, no coroutine — pure projection of the list to a rendered (or absent) section.
- A public composable with a raw `List<…>` param raises the `ComposeUnstableCollections` Compose-lint
  **Warning** (non-fatal; pre-existing convention here — `ChannelListScreen` / `WorkspacePickerSheet` /
  `ChannelInfoSheet` all carry it; the project ships no immutable-collections dependency). Not a defect.

## Preview

`QueuedBacklog.kt` ships two `@Preview`s, one per theme (`showBackground = true`, `widthDp = 412`; the dark
variant adds `uiMode = Configuration.UI_MODE_NIGHT_YES`), each wrapping `PyrycodeMobileTheme(darkTheme = …)
{ Surface { QueuedBacklog(queued = previewQueue()) } }` with a 3-entry fixture. [`ThreadScreen`](thread-screen.md)
also adds an in-file dark preview of the whole thread with a non-empty queue.

## Configuration

- **No new dependencies.** Existing Compose Material 3 + `material-icons-extended` (already present) only.
  No `gradle/libs.versions.toml` edits.
- **Two new string resources** in `res/values/strings.xml`: `thread_queued_backlog_label` ("Queued", the
  section caption) and `cd_thread_queued_backlog` ("Queued messages waiting to send", content description /
  test anchor). No server text is placed in a string resource — `entry.text` renders only through the bubble
  `Text`.

## Edge cases / limitations

- **Visual is design-owed.** The backlog treatment is **not yet drawn** in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8). Until it lands the visual
  follows the app's M3 message-row idiom (de-emphasized user bubble + waiting glyph + "Queued" caption);
  re-tune when the frame arrives — no contract change.
- **Tall backlogs not capped.** `QueuedBacklog` is a wrap-content `Column`; the list's `weight(1f)` absorbs
  its height and real backlogs are short (server-side `msgqueue` cap). If a future observation shows the
  queue crowding the composer, cap with `heightIn(max = …)` + internal scroll — **deferred** (no observed
  failure; Evidence-Based Fix Selection).
- **Stall × queue independence.** [`observeStall`](stall-state.md) (#395/#396) and `observeQueue` are
  **independent** flows; this slice renders the queue only. A combined "stalled with N waiting" presentation
  is a separate derivation, explicitly **out of scope** (flagged from #460's spec).
- **Empty thread + non-empty queue.** The rare case (a queue exists before any persisted message) shows
  [`EmptyThreadState`](empty-thread-state.md) with the backlog below it — harmless and unlikely (a queue
  implies prior sends), not specially handled.
- **No animation.** The show/hide is an instant early-return swap, matching the sibling components. A
  fade-in is a design-owed nicety deferred with the Figma frame.
- **Stale-on-resume (known, accepted).** Like `items` / `isThinking`, the upstream `queuedMessages`
  (`stateIn(WhileSubscribed(5_000))`) can momentarily read a stale value on re-foreground after a long
  background; a transient "right-now" posture deliberately not handled in the stateless composable. The data
  layer is connection-scoped, so a reconnect re-derives the backlog from the next live snapshot — nothing
  stale survives a reconnect (see [Queued backlog](queued-backlog.md)).

## Related

- Ticket notes: [`../codebase/461.md`](../codebase/461.md) (this component) ·
  [`../codebase/460.md`](../codebase/460.md) (the data/repository half it consumes).
- Spec: `docs/specs/architecture/461-queued-backlog-render.md`.
- Upstream signal: [Queued backlog](queued-backlog.md) — `observeQueue` / `QueuedMessage`, the inbound
  `queue_state` decode this section renders; the full-replace snapshot model. Resolves its forwarded
  UI-leakage flag (no new `FLAG_SECURE` surface — `entry.text` is the same content class sent bubbles
  already render).
- Host: [Thread screen](thread-screen.md) — surfaces `queuedMessages` on `ThreadUiState` and mounts the
  section between the list and `ThinkingIndicator`.
- Bubble mirrored: [Message bubble](message-bubble.md) (`UserMessageBubble` — the shape/colour family the
  queued row de-emphasizes).
- Render twins (same shape, opposite hoisting decision): [Thinking indicator](thinking-indicator.md) (#407
  — foot-of-list, sibling `StateFlow`), [Stall promotion banner](stall-promotion-banner.md) (#396 — top
  banner, sibling `StateFlow`). Both design-owed M3 defaults against the same un-drawn `16-8` frame.
- Parent: split from [#429](https://github.com/pyrycode/pyrycode-mobile/issues/429); epic pyrycode#597
  Phase 3. Drop loop: the `dequeue_message` send shipped in **[#466](../codebase/466.md)**
  ([`dropQueuedMessage`](queued-backlog.md)); the per-row drop **affordance** that calls it is **#467**
  (blockedBy #466).
- Server SSOT: pyrycode#705/#720 (`queue_state` wire type), #722 (producer), `docs/protocol-mobile.md`
  § Queue (v2), ADR 025.
</content>

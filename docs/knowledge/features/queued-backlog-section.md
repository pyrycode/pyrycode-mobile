# Queued backlog rendering — `QueuedMessageRow`, folded into the thread (#782)

The **UI half of the queued-message backlog** ([#461](../codebase/461.md) render + [#467](../codebase/467.md)
drop affordance, split from #429; folded into the thread by #782): while the active conversation's agent
is busy and the daemon is buffering the user's turns, the thread renders each message still waiting to
send as a de-emphasized row **at its own position in the list** — so the user knows what is queued
instead of wondering whether the turns they fired during a long response were dropped — and lets the user
**drop** any of them before they send.

Through #467 this lived in a dedicated foot-of-list section, `QueuedBacklog`, drawn below the message
list. **#782 deleted that section.** The daemon parks a message and pushes a `queue_state` snapshot for
it, but the phone had already drawn its own optimistic echo of that same send the moment
`RemoteConversationRepository.sendMessage` issued the request — before the daemon's ack, since
[#1355](https://github.com/pyrycode/pyrycode-mobile/issues/1355) moved the draw ahead of the await —
interactive mode streams no user-message event back, so the echo is the phone's only record of its own
send. The section drew the daemon's snapshot as a
*second*, near-identical row below the thread, one of the two claiming delivery it hadn't made. #782 joins
the two at render time instead: `foldQueuedRows` (`ui/conversations/thread/ThreadRow.kt`) correlates the
daemon's backlog against the thread's own rows on the key [#781](../codebase/781.md) carried through
(`QueuedMessage.messageId` ↔ the echo's `Message.id` — `sendMessage` stamps both from the one minted
`UUID`), and the list renders one row per message: the queue treatment **in place** when a send is still
parked, gone the moment delivery removes it from the next snapshot.

**"In place" means wherever `items` already puts it, which is no longer tap-time position for this device's
own echoes ([#1558](https://github.com/pyrycode/pyrycode-mobile/issues/1558)).** The fold cannot see which
ids this device minted — that ledger lives in `ThreadProjection`, not here — so it cannot be the layer that
decides a queued echo belongs at the foot of a running turn rather than where it was typed; doing the move
here would let a `queue_state` naming a foreign id drag *that* device's row out of place too. `ThreadProjection.observe`
does the move instead, upstream of this fold: a still-queued own echo reads last in `items`, below every row
of the turn it waits behind, before `foldQueuedRows` ever runs. The fold's own contract below is unchanged by
this — it still never reorders anything itself — but rule 1 no longer implies "at the position it was sent";
see [Queued backlog § Own echo
position](queued-backlog.md#own-echo-position-a-queued-message-draws-below-the-turn-it-waits-behind-1558) for
where that move happens and why.

The signal it renders is still the **data half** — [`observeQueue(conversationId)`](queued-backlog.md)
([#460](../codebase/460.md)), surfaced onto [`ThreadViewModel`'s `ThreadUiState.queuedMessages`](thread-screen-how-it-works-state.md).
This component still adds **no data access** and **no new data path** — the fold and the row together are
the render-after-decode consumer, the same relationship `ThinkingIndicator` (#407) has to its own signal
(and the stall promotion banner, #396, had to its own before [#883](../../specs/architecture/883-retire-literal-screen.md)
retired it), except the queue rides `ThreadUiState` rather than a sibling `StateFlow` (see [Wiring](#wiring)).

Package: `de.pyryco.mobile.ui.conversations.components` (`QueuedMessageRow.kt`, the promoted row) and
`de.pyryco.mobile.ui.conversations.thread` (`ThreadRow.kt`, the fold — both under
`app/src/main/java/de/pyryco/mobile/`).

## The render-time join (#782)

`foldQueuedRows(items: List<ThreadItem>, queued: List<QueuedMessage>): List<ThreadRow>` is a pure
function — no Compose, no repository, no clock — so it is provable by a JVM unit test
(`ThreadRowsTest.kt`) independent of the screen. `ThreadScreen` calls it once per render, cached with
`remember(state.items, state.queuedMessages)`, and walks the result instead of `state.items` directly.
`ThreadRow` is a sealed interface with two arms: `Delivered(item: ThreadItem)` for a row the daemon has
run (or a non-message row — a boundary, an unrecognized frame) and `Queued(queuedMessageId, text,
echoId)` for a message the last `queue_state` snapshot still reported waiting.

**The join stays render-time by design, not convenience.** `queue_state` is daemon state (server SSOT
pyrycode#720), not part of claude's turn stream, so it never folds into the thread's message reducer
(`ThreadFold` — see [thread-screen-how-it-works-state.md](thread-screen-how-it-works-state.md)); `items`
and `queuedMessages` stay two separate `ThreadUiState` fields and only the view reads both. That is also
what makes a replacing snapshot free: every row is re-derived on each call, so there is no reconciliation
state to orphan when a snapshot lands — including the empty one a reconnect clears the backlog to.

The correlation contract, shared verbatim with `pyrycode-desktop`'s `foldQueuedRows.ts` (its own #1214),
so the two clients agree on one join:

1. **Every thread item appears exactly once, at its own index, in order.** The fold never reorders, drops
   or duplicates an item — it only decides, per index, whether it renders `Delivered` or `Queued`.
2. **One-to-one, greedy, in snapshot order.** An index of echo positions is built once from `items`; each
   backlog entry claims at most one position and consumes it. Two equal texts under distinct ids
   therefore claim two distinct rows, and a snapshot repeating an id claims first-come and leaves the
   second unmatched rather than double-marking a row. The index holds a *list* of positions per key even
   though `HistoryPageReducer.withMessage`'s upsert makes `Message.id` unique today — the defence must
   not depend on an invariant enforced in another file.
3. **Only `Role.User` `ThreadItem.MessageItem`s are candidates.** The guard that stops a hostile
   `queue_state` from putting the operator's own queue treatment — and its drop control — onto
   daemon-authored content: an assistant bubble, a tool row, an unrecognized-output row.
4. **Only a non-empty `messageId` on both sides participates.** `""` correlates with nothing, matching
   `QueuedMessage.messageId`'s own contract, and text is never compared. This is the *first* guard in
   this path, not a second one — #781 put its own empty-id rule in `dropQueuedMessage`, which this
   consumer does not reach.
5. **An unmatched backlog item becomes its own `Queued` row, appended after every thread row**, in
   snapshot order, with `echoId = null`. A first-class state, not an error: `queue_state` reaches every
   paired device, so this phone sees ids it never minted (a send from another device, or a reconnect into
   a backlog it has no echo for).

O(items + queued): one pass to index the echoes, one to walk the snapshot.

**Recorded lesson — the crash a first draft nearly shipped.** The obvious key for an unmatched row is its
`queued_msg_id` — it reads like an identity. It isn't one: `queued_msg_id` is daemon-supplied and nothing
on this client checks it for uniqueness, so a snapshot repeating one value (a hostile daemon, or a
counter bug) would mint two identical `LazyColumn` keys and Compose throws, taking the whole thread down
for as long as that snapshot stood. The self-review security pass caught it before the plan was
committed; see [Wiring](#wiring) for the key each arm actually takes. The general shape: a remote counter
is not an identity until something validates it, and `QueuedMessage.messageId`'s own KDoc ("never key a
list on it") already said as much about the sibling field.

## Shape

```kotlin
@Composable
fun QueuedMessageRow(
    text: String,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`QueuedMessageRow` is the promoted, single render path for **both** kinds of queued row — matched and
unmatched — so they cannot drift apart. It carries no id: `onDrop` is a payload-free trigger, bound by
the caller (`ThreadScreen`'s `onDrop = { onDropQueued(row.queuedMessageId) }`), so the row itself never
holds a `Long` it could leak into a render, a key, or a log. The composable stays **stateless**: no
`ViewModel` reference, no flow collection, no `remember`, no `LaunchedEffect`, no internal mutable state.

## What it does

Each row mirrors the sent user bubble, de-emphasized:

- an end-aligned `Row`, inset on its leading edge by [`MessageRoleInset`](message-bubble.md) (100dp,
  since #644) and rendered at `Modifier.alpha(QUEUED_ALPHA = 0.6f)`;
- a leading **decorative** "waiting" glyph — `Icons.Outlined.Schedule`, tinted `onSurfaceVariant`,
  `contentDescription = null` (the row's own text plus its state description carry the meaning);
- a `Surface` bubble in the same uniform 6dp-cornered `BubbleShape` and `userBubbleContainer` /
  `onPrimaryContainer` colour family as [`UserMessageBubble`](message-bubble.md), holding **plain**
  `Text(text, bodyMedium)` — **never `MarkdownText`**, matching `UserMessageBubble`, since this is
  un-sent user *input* (an unmatched row's text is another paired device's input, relayed by the daemon,
  rendered through the exact same inert path);
- a **trailing drop affordance** — `IconButton(onClick = onDrop)` holding `Icon(Icons.Outlined.Close,
  tint = onSurfaceVariant)` with `contentDescription` from `cd_thread_queued_drop`. `Close` (×,
  *un-queue*) over `Delete` (trash, *delete*): a queued message is un-queued, not destroyed. The button
  sits **inside** the `.alpha(QUEUED_ALPHA)` scope but stays interactive while dimmed; `IconButton`
  supplies the ≥48dp tap target.
- **Accessibility (changed by #782).** The row carries `Modifier.semantics(mergeDescendants = true) {
  stateDescription = … }` sourced from `thread_queued_state_desc` ("Waiting to send") — a per-row
  `stateDescription`, not the old section's group `contentDescription`. `stateDescription` over
  `contentDescription`: the row now sits *among* delivered rows and must announce its own text plus the
  waiting state, where the old foot-of-list section could announce once for the whole group. It is also
  the Compose-test handle, the same marker idiom `ModalOptionButton` already uses. The trailing drop
  `IconButton` stays a clickable and so forms its own semantics node, unabsorbed by the row's merge —
  each drop affordance stays individually addressable (via `cd_thread_queued_drop`) in the unmerged
  tree.

### Styling (design-owed)

Unchanged by #782 — the visual only moved position, not shape. Queued entries deliberately read as **not
yet sent**: the `userBubbleContainer` fill and shared user-bubble shape at `QUEUED_ALPHA = 0.6f` (in the spirit of
[`ThreadScreen`](thread-screen.md)'s `ABOVE_DELIMITER_ALPHA = 0.55f` de-emphasis), plus the leading
waiting glyph, distinguish a queued row from the full-opacity sent / streamed bubbles around it. The
Figma `16-8` frame draws **no backlog treatment and no drop affordance** (unchanged since #461/#467); the
visual follows the app's existing message-row idiom until the frame gains one — no contract change when
it does, only a re-tune here.

The fill follows [MessageBubble's theme mapping](message-bubble.md#token-mapping-figma-roles-against-this-apps-two-schemes):
`#003355` under the app root's static dark palette; isolated light and
wallpaper-themed previews use the selected scheme's `primaryContainer`. The 0.6 opacity applies to the whole row,
including its fill, explicit `onPrimaryContainer` text and interactive drop control.

### Constants

The bubble geometry is *consumed* from [`MessageBubble.kt`](message-bubble.md), not copied: `BubbleShape`,
`BubbleHorizontalPadding`, `BubbleVerticalPadding` and `MessageRoleInset` are `internal` there specifically
so this file need not redeclare them. Only `WaitingGlyphSize`, `WaitingGlyphGap`, `QueuedRowVerticalPadding`
and `QUEUED_ALPHA = 0.6f` remain file-private `val`s local to `QueuedMessageRow.kt` (the glyph has no
shared equivalent elsewhere). `BacklogHorizontalPadding` / `BacklogRowSpacing` / the old section caption
are gone with the section itself — the row now sits on [`MessageContentGutter`](message-bubble.md)
directly, the same gutter every other row in the list uses.

**Recorded deviation, still true: the row *does* take `MessageRoleInset` (100dp).** The waiting glyph and
the trailing drop `IconButton` already consume roughly 72dp of the row; adding the inset on top narrows
the bubble below a sent one, but the alternative — no inset — was tried and rejected: without it a long
queued bubble grows to the full row width, wider than a sent bubble's maximum, which breaks the "same
bubble family" property the row exists to preserve. The narrower-but-in-family bubble is the accepted
cost.

## Position in the list

Pre-#782, [`ThreadScreen`](thread-screen.md) rendered `QueuedBacklog` as a separate wrap-content `Column`
**outside** the scrolling `LazyColumn`, between the list and the foot-most `ThinkingIndicator`. **#782
moves the row inside the `LazyColumn`, as one more `ThreadRow` arm:**

```kotlin
val rows = remember(state.items, state.queuedMessages) { foldQueuedRows(state.items, state.queuedMessages) }
// ...
LazyColumn(reverseLayout = true, ...) {
    itemsIndexed(items = rows.asReversed(), key = { i, row -> row.listKey(rows.size - 1 - i) }) { i, row ->
        val chronologicalIndex = rows.size - 1 - i
        // ... existing rowAlpha Box wrap ...
        when (row) {
            is ThreadRow.Delivered -> when (val item = row.item) { /* existing MessageBubble / SessionBoundaryDelimiter / UnrecognizedMessageRow dispatch */ }
            is ThreadRow.Queued -> QueuedMessageRow(text = row.text, onDrop = { onDropQueued(row.queuedMessageId) })
        }
    }
}
```

Why this is the right seam, and what it changes about the list the row now sits inside:

- **A queued row sits exactly where its send would render once delivered.** It is no longer a
  foot-of-list append — it continues the user's side of the conversation at the point it was fired,
  which is what makes "draw once, in place" true rather than "draw once, somewhere else."
- **Key derivation is `ThreadRow.listKey(chronologicalIndex)`**, living beside the fold in `ThreadRow.kt`
  rather than in the screen, so the two halves of its uniqueness argument sit next to each other. A
  matched `Queued` row takes **the same key its `Delivered` form carries** (`"msg:$echoId"`) — the
  property that leaves the row in place, unrecreated, when the next snapshot delivers it. An unmatched
  row keys on its **position** (`"queued-row:$chronologicalIndex"`), deliberately not on
  `queued_msg_id` — see the crash this avoids in [§ The render-time join](#the-render-time-join-782)
  above. The two arms use distinct string-literal namespaces (`"msg:"` / `"boundary:"` /
  `"unrecognized:"` / `"queued-row:"`), so none can collide with another.
- **The above-delimiter cutoff (`mostRecentSessionBoundaryIndex`) still reads `state.items`, unchanged.**
  `rows` shares a prefix with `items` index-for-index and only ever appends unmatched rows after them, so
  the two index spaces agree wherever the cutoff can land; only `chronologicalIndex` is re-derived from
  `rows.size`. See [the list section](thread-screen-how-it-works-list-and-status-row.md) for the
  cutoff itself.
- **The oldest-end history demand's `historyRowCount` now reads `rows.size`, not `state.items.size`** —
  same reasoning as the cutoff, folded into
  [that section](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777).
- **The `EmptyThreadState` gate widened to `!state.hasMessages && state.queuedMessages.isEmpty()`.** A
  backlog item this device minted no echo for is its own row with nothing else in the thread to anchor
  it, so the empty-state prompt must yield to it (AC #3 of #782) — when an item *is* matched its echo is
  already a `MessageItem`, so `hasMessages` alone already covers that case.
- **Because it is now a `LazyColumn` item, only visible queued rows compose** — a strict improvement over
  the old wrap-content section for a long backlog (see [Edge cases](#edge-cases--limitations)), though
  this was a side effect of the move, not its motivation.

## Wiring

The queue is still threaded as a **`ThreadUiState` field** — **not** a sibling `StateFlow` like
`isThinking` (or, before [#883](../../specs/architecture/883-retire-literal-screen.md) retired it,
[`isStalled`](stall-state.md)). #782 does not change this: the fold reads two
already-hoisted `ThreadUiState` fields and produces rows the screen renders; no new `ViewModel` state, no
new constructor param.

- **`ThreadViewModel`** surfaces `items` and `queuedMessages` on the existing single
  `state: StateFlow<ThreadUiState>`, pre-combined via the private `ThreadContent` carrier (see
  [ViewModel state § the render-time join](thread-screen-how-it-works-state.md)). Unchanged by #782.
- **`ThreadScreen`** needs no new parameter for the queue *content* — `state.queuedMessages` feeds
  `foldQueuedRows` alongside `state.items`, both already collected. The drop **action** stays the one
  defaulted param it has carried since #467: `onDropQueued: (Long) -> Unit = {}`, now bound per row as
  `onDrop = { onDropQueued(row.queuedMessageId) }` instead of handed to a section.
- **`MainActivity`** is untouched by #782 — the existing `onDropQueued = vm::onDropQueued` wiring line
  from #467 is unchanged.
- **`ThreadViewModel.onDropQueued(queuedMessageId: Long)`** is unchanged by #782: a `viewModelScope.launch`
  calling `repository.dropQueuedMessage(conversationId, queuedMessageId)`, mutating no `state.queuedMessages`
  (no optimistic removal), mirroring [`sendInterrupt`](interrupt-send-path.md)'s catch contract
  (rethrow `CancellationException` first, swallow `RelayErrorException` / `IllegalStateException`).

### Why a `ThreadUiState` field, not a sibling `StateFlow`

`isThinking` / `isStalled` / `currentModal` are sibling `StateFlow`s because each is a **transient
cross-cutting boolean/scalar**. The queue is different: it is **thread content** — an ordered list of
message text, the same category as `ThreadUiState.items`, which is exactly what makes the #782 fold a
view-layer join of two `ThreadUiState` fields rather than a third kind of state. Decide by signal class,
not by reflex: cross-cutting scalar → sibling `StateFlow`; conversation content → `ThreadUiState`.

## Recomposition / stability

- `foldQueuedRows` is `remember`-cached on `(state.items, state.queuedMessages)`, so it re-runs only when
  either input changes, not on every recomposition. It holds no state itself — pure input-to-output.
- Inside the `LazyColumn`, each `Queued` row's `onDrop = { onDropQueued(row.queuedMessageId) }` lambda
  allocates fresh per item per fold; unlike the pre-#782 wrap-content `Column`, this row now lives inside
  a recycling list, so its **key** (not lambda identity) is what Compose uses to preserve or discard
  composition state across a re-fold — see [Position in the list](#position-in-the-list) for why the key
  is safe to repeat across a matched row's `Queued` → `Delivered` transition.
- `QueuedMessageRow`'s own params (`text: String`, `onDrop: () -> Unit`) are both stable, so the
  composable itself is restartable + skippable.

## Preview

`QueuedMessageRow.kt` ships two `@Preview`s, one per theme (`showBackground = true`, `widthDp = 412`),
each wrapping a 3-row `Column` fixture. [`ThreadScreen`](thread-screen.md) carries its own dark preview
(`ThreadScreenQueuedRowsDarkPreview`, renamed from the pre-#782
`ThreadScreenQueuedBacklogDarkPreview`) showing **both** forms of the row at once: one `QueuedMessage`
whose `messageId` matches a `previewItems()` echo (renders in place, matched) and one whose `messageId`
matches nothing this device minted (renders after the thread rows, unmatched).

## Configuration

- **No new dependencies.** Existing Compose Material 3 + `material-icons-extended` only.
- **String resources, changed by #782:** `thread_queued_backlog_label` ("Queued") and
  `cd_thread_queued_backlog` ("Queued messages waiting to send") are **deleted** — their only reader was
  the deleted section. `thread_queued_state_desc` ("Waiting to send") is **new**, the per-row
  `stateDescription`. `cd_thread_queued_drop` ("Drop this queued message") is unchanged. No
  server-authored text is placed in a string resource — `entry.text` / the echo's own content render only
  through the row's plain `Text`.

## Edge cases / limitations

- **Visual is still design-owed.** Neither the queue treatment nor the drop affordance is drawn yet in
  [`16-8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8); the visual follows the
  app's M3 message-row idiom until the frame gains one.
- **Long backlogs now compose lazily, not a regression to watch.** Pre-#782 the section was a wrap-content
  `Column`, so every queued entry composed regardless of scroll position. Moving the row into the
  `LazyColumn` (§ Position in the list) means only visible queued rows compose now — a side effect of the
  move, in the same direction the pre-#782 doc flagged as a possible future fix, now already true.
- **Stall × queue independence, unchanged.** [`observeStall`](stall-state.md) and `observeQueue` are
  independent flows; the fold only reads the queue.
- **Empty thread + non-empty queue.** Handled explicitly since #782: the `EmptyThreadState` gate is
  `!state.hasMessages && state.queuedMessages.isEmpty()`, so an unmatched backlog item (no echo, no other
  thread row) renders instead of the empty-state prompt (§ Position in the list). Pre-#782 this rare case
  showed the empty-state prompt *above* the section; #782 closes that gap as part of the same slice.
- **No animation.** Unchanged — the queue treatment appears/disappears with the row's snapshot-driven
  state, not a transition. A fade is a design-owed nicety deferred with the Figma frame.
- **Stale-on-resume (known, accepted), unchanged.** Like `items`, the upstream `queuedMessages`
  (`stateIn(WhileSubscribed(5_000))`) can momentarily read a stale value on re-foreground after a long
  background. The data layer is connection-scoped, so a reconnect re-derives the backlog from the next
  live snapshot — nothing stale survives a reconnect.
- **A test that would have passed while broken (recorded from #782's build).** Locating "the lower row's
  drop affordance" by tree order (`onAllNodes(...).onLast()`, the old section test's idiom) is meaningless
  under `reverseLayout = true` — semantics order and visual order are not the same, and that assertion
  passes whether the routing is correct or not. `QueuedBacklogTest.kt`'s carried-over id-routing case now
  locates the lower row by `boundsInRoot.top` instead, and pins visual order as a separate assertion.

## Related

- Ticket notes: [`../codebase/461.md`](../codebase/461.md) (the original section, render) ·
  [`../codebase/460.md`](../codebase/460.md) (the data/repository half it consumes) ·
  [`../codebase/467.md`](../codebase/467.md) (the drop affordance, carried into this component).
  #782 postdates the frozen archive; its notes live in this document.
- Spec: `docs/specs/architecture/461-queued-backlog-render.md` (original) ·
  `docs/specs/architecture/782-fold-queued-backlog-into-thread.md` (the fold, the security review, and
  the sizing note recorded in its Revisions).
- Upstream signal: [Queued backlog](queued-backlog.md) — `observeQueue` / `QueuedMessage`, the inbound
  `queue_state` decode this row renders; the full-replace snapshot model; the separate `message_id`-keyed
  echo-removal-on-drop-ack mechanism (#781) that this render-time join does not touch or replace; since
  [#1558](https://github.com/pyrycode/pyrycode-mobile/issues/1558), the own-echo-position move that also
  happens upstream, in `ThreadProjection.observe`, before `items` ever reaches this fold.
- Host: [Thread screen](thread-screen.md) — surfaces `queuedMessages` on `ThreadUiState`; since #782 also
  hosts `foldQueuedRows` and the folded `LazyColumn` — see
  [the list section](thread-screen-how-it-works-list-and-status-row.md) and
  [ViewModel state](thread-screen-how-it-works-state.md).
- Bubble mirrored: [Message bubble](message-bubble.md) (`UserMessageBubble` — the shape/colour family the
  queued row de-emphasizes; the geometry constants (`BubbleShape`, paddings, `MessageRoleInset`) are
  consumed from there directly — see [Constants](#constants) above).
- Fold twin: `pyrycode-desktop`'s `foldQueuedRows.ts` (its #1214) — the same five-rule correlation
  contract, authored so the two clients agree on one join.
- Render twin (same signal shape, opposite hoisting decision): [Thinking indicator](thinking-indicator.md)
  (#407 — foot-of-list, sibling `StateFlow`). The stall promotion banner (#396 — top banner, sibling
  `StateFlow`) was another until [#883](../../specs/architecture/883-retire-literal-screen.md) retired it.
- Parent: split from [#429](https://github.com/pyrycode/pyrycode-mobile/issues/429); epic pyrycode#597
  Phase 3. Drop loop **complete** since [#466](../codebase/466.md)/[#467](../codebase/467.md); #782 fixed
  the double-draw without changing the drop loop itself.
- Error-contract twin (the drop handler mirrors it): [Interrupt send path](interrupt-send-path.md)
  ([#458](../codebase/458.md)) — fire-and-forget swallow-on-failure + cancellation-rethrow-first.
- Server SSOT: pyrycode#705/#720 (`queue_state` wire type), #722 (producer), `docs/protocol-mobile.md`
  § Queue (v2), ADR 025.

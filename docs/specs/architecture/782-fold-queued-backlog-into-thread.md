# #782 — Draw a queued message once by folding the backlog into the thread

## Files read

- `ui/conversations/components/QueuedBacklog.kt` → `QueuedBacklog`, `QueuedMessageRow` — the
  foot-of-list section this slice replaces; its private row composable is the treatment that survives.
- `ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `mostRecentSessionBoundaryIndex` — the
  list, its key function, the alpha math, the oldest-end demand predicate, the `EmptyThreadState` gate.
- `ui/conversations/thread/ThreadViewModel.kt` → `ThreadUiState` (`items`, `queuedMessages`,
  `hasMessages`), `onDropQueued` — the hoisted state both halves ride on. **Unchanged by this slice.**
- `data/repository/ConversationRepository.kt` → `QueuedMessage`, `ThreadItem` — the correlation key
  #781 landed, and its "equality only, never render, never key a list on it, never log it" contract.
- `data/repository/RemoteConversationRepository.kt` → `sendMessage`, `mintedMessageIds` — where the
  echo's `Message.id` **is** the minted `message_id`, which makes it the join predicate on this client.
- `data/repository/HistoryPageReducer.kt` → `withMessage`, `reduceHistoryPage`'s `TYPE_SEND_MESSAGE`
  arm — a stored `send_message` re-enters carrying the same client-minted id, and `withMessage` is
  upsert-by-id, so `Message.id` is unique within the projection. Both facts the fold leans on.
- `ui/conversations/components/MessageBubble.kt` → `BubbleShape`, `MessageContentGutter`,
  `MessageRoleInset` and the bubble paddings — the geometry the queued row already consumes.
- `app/src/androidTest/.../thread/QueuedBacklogTest.kt` — the wire-order / drop-affordance /
  id-routing coverage the ticket asks to carry over rather than delete.
- `app/src/test/.../thread/ThreadScreenCutoffTest.kt` — six assertions pinning
  `mostRecentSessionBoundaryIndex`'s `List<ThreadItem>` signature; why this plan leaves it alone.
- `docs/knowledge/features/queued-backlog-section.md` § Placement in the thread — the shipped visual,
  the `MessageRoleInset` deviation, and the seam this slice moves.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/foldQueuedRows.ts` → `foldQueuedRows` —
  the correlation contract the two clients agree on (rules 1–5), adopted here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=16-8

The conversation thread: an app bar, a message area of left-aligned assistant and right-aligned user
bubbles (6dp corners, 20/16 inner padding, a trailing meta row of timestamp-plus-copy), then the input
area's status band, field and model/effort footer. Read through `get_design_context` and the rendered
node: the frame draws **no backlog treatment, no queued row and no drop affordance** — unchanged since
#461/#467 recorded the same. So this slice invents no visual. The queued row keeps the treatment
`QueuedBacklog` already ships (de-emphasised user bubble at `QUEUED_ALPHA`, leading `Schedule` glyph,
trailing `Close` drop button); only its **position** changes, from a foot-of-list section to the
message's own place in the list. If the frame later gains a backlog treatment, retune there.

## Context

A message sent while claude is busy draws twice. `RemoteConversationRepository.sendMessage` appends a
user `Message` on the ack — the phone's only record of its own send, because interactive mode streams no
user-message event back — and the daemon, having parked it, pushes a `queue_state` snapshot that
`QueuedBacklog` draws again below the list. Two near-identical rows, one claiming delivery.

#781 carried the daemon's relayed `message_id` onto `QueuedMessage.messageId`. On this client the
optimistic echo's `Message.id` **is** that minted id (`sendMessage` stamps both from one `UUID`), so the
two sides already share a key and no new field is needed. This slice spends it.

The join is **render-time**. `queue_state` is daemon state, not part of claude's turn stream, so it must
not fold into the thread's message reducer: `ThreadUiState` keeps carrying `items` and `queuedMessages`
separately, the backlog stays replacement truth held as the wire list, and only the view reads both.
That is also what makes a replacing snapshot free — every row is re-derived from the current pair, so
there is no reconciliation state to orphan when a snapshot lands, including the empty one a reconnect
clears to.

No ADR is warranted: this is the mobile half of a contract `pyrycode-desktop` #1214 already recorded,
and the render-time line it draws is already stated in `queued-backlog.md`.

## Design

**New file `ui/conversations/thread/ThreadRows.kt`** — the fold, pure, no Compose, no repository, no
clock, so it is provable by a JVM unit test:

```kotlin
sealed interface ThreadRow {
    /** A row the daemon has run, or a non-message row. */
    data class Delivered(val item: ThreadItem) : ThreadRow

    /**
     * A message the daemon's last snapshot reported still waiting. [echoId] is the `Message.id` of the
     * echo it correlated to, or null for a backlog item this device minted no echo for; it is used for
     * the list key and equality only and is never rendered, logged or sent.
     */
    data class Queued(val queuedMessageId: Long, val text: String, val echoId: String?) : ThreadRow
}

internal fun foldQueuedRows(items: List<ThreadItem>, queued: List<QueuedMessage>): List<ThreadRow>

/** The row's `LazyColumn` key. Beside the fold rather than in the screen so it is unit-testable. */
internal fun ThreadRow.listKey(chronologicalIndex: Int): String
```

Contract, adopted from `foldQueuedRows.ts` so the two clients agree:

1. **Every thread item appears exactly once, at its own index, in order.** The fold never reorders,
   drops or duplicates an item; it only decides, per index, whether the item renders `Delivered` or
   `Queued`.
2. **One-to-one, greedy, in snapshot order.** An index of echo positions is built once from `items`;
   each backlog item claims at most one, and a claimed position is consumed. Two equal texts with
   distinct ids therefore claim two distinct rows, and a snapshot repeating an id claims first-come and
   leaves the second unmatched rather than double-marking a row. The index holds a *list* of positions
   per key even though `withMessage` makes `Message.id` unique today — the defence must not depend on an
   invariant enforced in another file.
3. **Only `Role.User` `ThreadItem.MessageItem`s are candidates.** This is the guard that stops a hostile
   `queue_state` putting the operator's own queued treatment, and its drop control, onto daemon-authored
   content: an assistant bubble, a tool row, an unrecognized-output row.
4. **Only a non-empty `messageId` on both sides participates.** `""` correlates with nothing, matching
   the field's contract, and text is never compared. This is the *first* guard in this path, not a
   second one — #781 put its empty-id rule in `dropQueuedMessage`, which this consumer does not reach.
5. **An unmatched backlog item becomes its own `Queued` row appended after every thread row**, in
   snapshot order, with `echoId = null`. A first-class state, not an error: `queue_state` reaches every
   paired device, so this phone sees ids it never minted.

O(items + queued): one pass to index, one to assign.

**`components/QueuedBacklog.kt` → `components/QueuedMessageRow.kt`.** The section composable
`QueuedBacklog` is deleted; its private `QueuedMessageRow` is promoted to the file's one public
composable and becomes the single render path for **both** kinds of queued row, so a matched and an
unmatched row cannot drift:

```kotlin
@Composable
fun QueuedMessageRow(text: String, onDrop: () -> Unit, modifier: Modifier = Modifier)
```

The visual is unchanged. Two edits inside it: the `onDrop` parameter loses its `Long` (the id is bound
by the caller, so the row never holds one — the row cannot leak back down a field it does not have),
and the section's `contentDescription` a11y group is replaced by a per-row
`semantics(mergeDescendants = true) { stateDescription = … }` carrying the new
`thread_queued_state_desc` ("Waiting to send"). `stateDescription` over `contentDescription` because the
row now sits among delivered rows and must announce *its own text* plus the waiting state, where the
section could announce once for the group; it is also the test-observable marker, the idiom
`ModalOptionButton` already uses. The drop `IconButton` stays a clickable and so keeps its own semantics
node inside the merge.

**`ThreadScreen`** builds the rows and renders them inside the existing `LazyColumn`:

- `val rows = remember(state.items, state.queuedMessages) { foldQueuedRows(state.items, state.queuedMessages) }`.
- The `QueuedBacklog(…)` call below the list is deleted, and with it the section between the list and
  the composer.
- `itemsIndexed` walks `rows.asReversed()`. The key function gains one arm: a `Queued` row keys on
  `"msg:${echoId}"` when matched — **the same key its `Delivered` form carries**, which is what makes
  delivery leave the row in place rather than recreate it — and on its **position**,
  `"queued-row:$chronologicalIndex"`, when not. The four namespaces are distinct string literals, so no
  arm can collide with another.

  The unmatched arm keys on position rather than on `queuedMessageId` deliberately: `queued_msg_id` is
  a daemon-supplied value this client never validates for uniqueness, and two snapshot entries
  repeating one would mint two identical keys and crash the thread's `LazyColumn`. Position is unique
  by construction. The matched arm is safe for the opposite reason — it keys on the **echo's**
  `Message.id` taken from `items`, which `withMessage`'s upsert keeps unique, and rule 2 lets at most
  one row claim a given echo. Neither arm keys on the snapshot's own `messageId`, which #781 documents
  as unique nowhere.
- The render fork is `Delivered` → today's `when (item)`; `Queued` → `QueuedMessageRow`. Both stay
  inside the existing alpha `Box`.
- The above-delimiter cutoff keeps calling `mostRecentSessionBoundaryIndex(state.items)` **unchanged**:
  `rows` shares a prefix with `items` index-for-index and only ever appends unmatched rows after them,
  so the two index spaces agree wherever the cutoff can land. Only `chronologicalIndex` is re-derived
  from `rows.size`. This is why that helper and its six-assertion unit test are untouched.
- The oldest-end demand predicate's `historyRowCount` moves from `state.items.size` to `rows.size`, so
  "the oldest visible row is the last index" stays true when unmatched rows sit at the newest end. No
  feedback loop is introduced: queued rows change on daemon snapshots, never on the indicator's own
  presence, which is the property that comment guards.
- The `EmptyThreadState` gate becomes `state.hasMessages || state.queuedMessages.isNotEmpty()`, so a
  backlog item with no echo is never hidden behind the empty state (AC #3). Every other case is
  unchanged: when a backlog item *is* matched its echo is a `MessageItem`, so `hasMessages` is already
  true. The `WorkspaceChip` gate is deliberately left as-is.

**`res/values/strings.xml`:** add `thread_queued_state_desc`; remove `thread_queued_backlog_label` and
`cd_thread_queued_backlog`, whose only readers were the deleted section. `cd_thread_queued_drop` stays.

`ThreadViewModel`, `ThreadUiState`, `MainActivity`, the repository and the wire are untouched.

## State + concurrency model

No new coroutine, scope, flow or dispatcher. The fold is a pure function called during composition and
cached by `remember` on its two inputs; it holds no state, so there is nothing to cancel and nothing
that can survive a `LifecycleConnectionDriver` background close. A replacing snapshot — including the
empty one a reconnect clears to — re-derives every row, which is the whole reconciliation story.

## Error handling

No new failure mode. The fold is total: a malformed or hostile snapshot produces rows, never an
exception — an unmatched item becomes its own row, an empty id matches nothing, a non-user candidate is
skipped. The drop path is unchanged (`ThreadViewModel.onDropQueued`, still fire-and-forget with the
`sendInterrupt` catch contract), and this slice still performs no optimistic removal: a dropped row
leaves on the next snapshot.

## Testing strategy

**Unit — `app/src/test/.../thread/ThreadRowsTest.kt`** (new), the fold against the five rules, RED
first:

- a parked send with a matched echo yields one row at the echo's index, carrying its `queuedMessageId`
  and `echoId`, and no tail row (AC #1);
- two items with equal text and distinct ids claim two distinct rows (AC #1);
- the same snapshot minus one entry leaves the delivered row at the same index with the same `echoId`
  and no queue handle (AC #2);
- an unmatched item, an `""`-`messageId` item, and an item whose id matches an *assistant* / tool /
  unrecognized row each append a tail `Queued` row and mark nothing (AC #3);
- a snapshot repeating one `messageId` marks one row and appends the second (rule 2);
- a snapshot repeating one `queued_msg_id` yields two rows, and the screen's key derivation gives them
  distinct keys (the duplicate-key crash the Security review's finding 1 names);
- folding snapshot B after snapshot A returns only B's rows (AC #4);
- items round-trip exactly once in order, with an empty and a non-empty backlog (AC #5).

**Compose — `app/src/androidTest/.../thread/QueuedBacklogTest.kt`** (rewritten in place; the ticket asks
that its wire-order, drop-affordance and id-routing assertions keep holding in the new shape): a queued
send renders one row, not two; rows keep wire order; delivery leaves the row where it was without a
drop affordance; the drop affordance routes that row's `queuedMessageId`; an unmatched item renders
below the thread rows; an empty backlog renders none. Existing suites cover the untouched paths.

No rung-3 or rung-4 scenario: this is a render-time join with no wire change and no new operator flow —
live cross-client queue-and-drop is #673's acceptance, which carries `needs-real-claude`.

## Open questions

1. Whether the "Queued" caption is worth keeping per row now that the section is gone. Current answer:
   no — the glyph, the de-emphasis and the state description carry it, and a caption per row is noise.
2. Whether a delivered row should animate out of the queued treatment. Deferred with the Figma frame,
   as #461 deferred the section's fade.

## Documentation handoff

Pending the documentation stage — not written by this ticket:

- `docs/knowledge/features/queued-backlog-section.md` — § Placement in the thread, § Shape, § Wiring,
  § Constants and § Edge cases: the section is gone, replaced by a per-row render folded into the list
  at the message's own position; record the correlation contract and its shared authorship with
  `pyrycode-desktop` #1214.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md` — the list section: rows
  are now `ThreadRow`s produced by `foldQueuedRows`, the key function's fourth namespace, and the
  `EmptyThreadState` gate.
- `docs/knowledge/features/thread-screen-how-it-works-state.md` — the content-state section:
  `ThreadUiState` still carries `items` and `queuedMessages` separately and the join stays in the view.

## Security review

**Verdict:** PASS (first pass returned FAIL on finding 1; the plan was revised inline before this
section was written, and the checklist re-walked from the top.)

**Findings:**

- **[Trust boundaries] MUST FIX — resolved in this plan.** The first draft keyed an unmatched queued
  row on `"queued:$queuedMessageId"`. `queued_msg_id` is daemon-supplied and nothing on this client
  checks it for uniqueness, so a snapshot repeating one — from a hostile daemon or a counter bug —
  mints two identical `LazyColumn` keys and Compose throws, taking the whole thread down for as long as
  the snapshot stands. A remote, unauthenticated-by-content crash of the screen. Fixed by keying the
  unmatched arm on the row's position, which is unique by construction; see § Design. The matched arm
  keys on the **echo's** `Message.id` read out of `items`, whose uniqueness `withMessage`'s upsert
  enforces locally and which rule 2 lets at most one row claim — so neither arm keys on the snapshot's
  `messageId`, honouring #781's "never key a list on it".
- **[Trust boundaries] No finding — the candidate guard is explicit and typed.** Rule 3 restricts
  correlation to `ThreadItem.MessageItem` with `Role.User`. This is the guard that stops a
  `queue_state` moving the operator's queued treatment, and its drop control, onto daemon-authored
  content — an assistant bubble, a tool row, an unrecognized-output row. It is a `when`/`is` check in
  `foldQueuedRows` and nowhere else, and it is asserted directly by three unit cases.
- **[Trust boundaries] Accepted, bounded — a relayed id can move the treatment onto an older user
  row.** `message_id`s pass through the daemon, and a history-folded `send_message` row carries the
  same client-minted id (`HistoryPageReducer`'s `TYPE_SEND_MESSAGE` arm), so a snapshot naming such an
  id marks that row. In the honest case this is *correct* — the backlog item and the history row are
  the same message. In the hostile case the daemon is fabricating queue state wholesale (it could as
  easily fabricate the text), and what it gains is a queued treatment on one of the operator's own
  rows: no daemon-authored content is ever marked, no capability is conferred, and the drop still
  addresses `conversation_id` + `queued_msg_id`, never the row. The strictly stronger guard — matching
  only ids this device minted — lives in `RemoteConversationRepository`'s `mintedMessageIds` ledger and
  is deliberately not widened into the UI contract: the ticket's design line is that the view reads
  the two existing projections and nothing more. Revisit if the ledger is ever surfaced for another
  reason.
- **[Trust boundaries / #4 Android surface] No finding — the render path is unchanged and inert.**
  `QueuedMessage.text` is other-client-authored text relayed by the daemon; it renders through plain
  `Text` exactly as the shipped section rendered it — never `MarkdownText`, never a `SelectionContainer`,
  never an attribute, URL, filename or cache key, and never into `rememberSaveable`. No new window, no
  `WebView`, no intent, no deep link, no pending intent; the row moves onto the thread's existing
  surface, so the screen-capture posture is unchanged.
- **[Error messages, logs, telemetry] No finding, with an implementation obligation.** Neither
  `messageId`, `echoId`, `queuedMessageId` nor any row text may reach a log; the fold logs nothing and
  no `Log`/`RelayLog` call is added. The one new user-visible string is the local
  `thread_queued_state_desc` — no interpolation, nothing daemon-derived. The verifier should check that
  no log call appeared in `ThreadRows.kt`.
- **[Network & I/O] Not applicable — no I/O is added.** No socket, no request, no new wire verb, no
  frame-size or timeout decision: `queue_state` decoding, its caps and `dropQueuedMessage` are all
  upstream of this slice and untouched. The drop send is the existing `ThreadViewModel.onDropQueued`.
- **[Concurrency] No finding.** No coroutine, scope, flow, dispatcher or mutex is introduced. The fold
  runs in composition, cached by `remember` on `items` and `queuedMessages`, so a recomposition that
  changes neither does no work; it holds no mutable state, so there is nothing for a background close
  or process death to leave partial. A replacing snapshot is absorbed by re-derivation.
- **[Tokens / File & storage / Cryptographic primitives] Not applicable.** This slice creates, stores,
  compares and transmits no secret, opens no file, and touches no key, nonce or handshake. The one
  equality comparison is on a non-secret correlation id, so constant-time comparison is not a
  requirement here.
- **[Threat model alignment] Hostile relay — no new exposure.** The relay stays content-blind and
  on-path; it can drop, delay or reorder `queue_state`, and every one of those outcomes lands as a
  snapshot the fold re-derives from whole. A dropped snapshot leaves a stale treatment until the next
  one, which is the pre-existing replacement-truth posture (`queued-backlog.md`), not a regression.
  **UI-side leakage** stays with the host surface, unchanged. **Token theft / malicious QR** are not
  reachable from this path.
- **[Threat model alignment] OUT OF SCOPE — unbounded queued text.** `QueuedMessage.text` carries no
  client-side length bound; a very long entry could jank the row. Pre-existing and unchanged (the
  shipped section rendered the same string), and the move *into* the `LazyColumn` strictly improves it,
  since only visible rows now compose. No observed failure, so no defence is added here
  (Evidence-Based Fix Selection). Belongs with a daemon-side or decode-side cap if it is ever observed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

## Revisions

**2026-09-22 — the fold's file is `ThreadRow.kt`, not `ThreadRows.kt`.** ktlint's `standard:filename`
rule failed the build: a file holding a single class plus extension functions for it must be named
after the class. No contract change — same package, same symbols, same visibility.

**2026-09-22 — sizing: the ticket landed over the 800-line boundary, recorded rather than split.**
Actual 1070 insertions / 312 deletions over 8 files, against a plan that estimated ~770. About 160 of
those insertions are `QueuedBacklog.kt`'s content carried into `QueuedMessageRow.kt` by the rename and
306 are this plan, so newly-authored work is nearer 600, but the raw count is the one the boundary
names and it is over. It was not split: every candidate slice — the fold alone, or the render alone —
produces a child whose only consumer is its sibling, which the floor rule forbids, and the floor beats
the ceiling. The comparable is the refiner's own analogue, `pyrycode-desktop` #1214 at 895/315 over 11
files. Recorded here so the next calibration reads the real number.
</content>

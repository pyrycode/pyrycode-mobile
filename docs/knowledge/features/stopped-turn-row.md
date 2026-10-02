# Stopped-turn row — `ThreadItem.StoppedTurn` / `StoppedTurnRow`

The thread's persistent answer to how a turn ended: a turn that failed or stopped early leaves a
"Stopped: …" row after its last row, so the reason survives after the next turn starts
([#1356](https://github.com/pyrycode/pyrycode-mobile/issues/1356)). Before this ticket the only trace of a
turn's ending was `ThreadViewModel.turnOutcome`, a status-area value the next turn's `Thinking`/`Responding`
clears — see [Turn-outcome indicator § How this differs](turn-outcome-indicator.md#how-this-differs-from-the-stopped-turn-row-1356).
Desktop keeps a `turnBoundary` row per `turn_end`, live and from history; this ticket copies desktop's rule
and its copy exactly, rather than reusing mobile's own `inertOutcomeToken` sanitizer.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/`).
File: `StoppedTurnRow.kt`. Rule + type: `data/repository/StoppedTurn.kt` (pure, no Android imports),
`data/repository/ConversationRepository.kt` (`ThreadItem.StoppedTurn`). Both lanes build the row in one
place, `HistoryPageReducer.kt`'s `withFinalizedTurn`, the function `ThreadProjection.finalizeAssistantTurn`
(live) and `withHistoryEntry`'s `TYPE_TURN_END` arm (history) both call. Cache:
`data/cache/FileConversationCache.kt` (`CachedStoppedTurn`). Sibling of [`BannerNoticeRow`](banner-notice-row.md)
and [`ModelRefusalRow`](model-refusal-row.md) — same bare-text treatment, same per-ticket landing shape, but a
turn id identity instead of a wire-minted `(type, ts)` one.

Wire SSOT: pyrycode `docs/protocol-mobile.md` § `turn_end` (sibling checkout). Desktop sibling:
pyrycode-desktop `ConversationScreen.tsx`'s `stoppedTurnText` / `stoppedReportText`, and the `turnEnd` arm of
`reduceTimelineContent` in `store/threadTimeline.ts`; the case table in `stoppedTurn.test.tsx` is the source
the unit tests below copy case for case.

## The thread-row type

```kotlin
sealed interface ThreadItem {
    data class StoppedTurn(
        val turnId: String,
        val reason: String,
        val category: String,
        val occurredAt: Instant,
    ) : ThreadItem
}
```

- **Identity: `turnId`.** A turn ends once, so its id is the row's identity on both lanes and at every
  instant it arrives — the opposite choice from `Banner`/`ModelRefusal`'s wire-minted `(type, ts)` key:
  there is no "two stopped rows for the same turn at different timestamps" case to disambiguate.
  `holdsStoppedTurn(turnId)` is the one shared predicate `alreadyHolds`, both thread writers, and
  `ThreadRow.listKey()` all read, so the merge, the live append and the `LazyColumn` key can never disagree.
  A replayed `turn_end` whose fields differ loses to the row already held, the fail-safe direction.
- **`reason` and `category` have already crossed `stoppedReportText`** by the time they reach the type —
  every control, format (including the bidi overrides and isolates), line-separator or paragraph-separator
  code point removed, and emptied outright if the value is over 256 UTF-8 bytes. `reason` empty reads as a
  bare error; `category` empty reads as none. They are still agent-authored strings and the render
  composable re-applies the same sanitizer (below), so a row restored from the cache is held to the rule a
  live one was built by, not trusted to have stayed sanitized on disk.
- **Invariant: unique among a thread's stopped rows**, a producer obligation like the sibling kinds —
  documented in KDoc and asserted in tests (`decodeThread` rejects a repeated `turnId`), not enforced at
  construction.

## The rule — `data/repository/StoppedTurn.kt`

`stoppedReportText(value: String): String` is desktop's `stoppedReportText`: empty when `value`'s UTF-8
length exceeds 256 bytes (a lone surrogate counts as the three bytes `TextEncoder` writes for its U+FFFD
replacement), else `value` with every code point of type `Cc`/`Cf`/`Zl`/`Zp` removed — removed, not
replaced, and never cut to a fixed length, unlike `TurnOutcomeIndicator`'s `inertOutcomeToken`. It iterates
code points, so a supplementary-plane format character (e.g. an invisible tag, U+E0000–E007F) is stripped
too — the gap `inertOutcomeToken` has (see [Turn-outcome indicator § Classification &
sanitization](turn-outcome-indicator.md#classification--sanitization)) does not exist here, because this
sanitizer was written to iterate code points from the start.

`LiveSessionEvent.TurnEnd.stoppedTurn(occurredAt: Instant): ThreadItem.StoppedTurn?` is desktop's
`stoppedTurnText` rule, read only off the agent's own result fields:

- `null` for `stopReason == "cancelled"` — the daemon's own interrupt classification wins outright.
- `null` when `isError` is false and the sanitized `outcome` is empty or `"success"` — a clean turn. The
  three daemon-only early-stop `stop_reason` values (`max_tokens`, `max_turn_requests`, `refusal`) that
  raise `TurnOutcomeIndicator`'s status-area arm add **no** row here on their own, since none of them sets
  `isError` or a non-`success` `outcome` by itself.
- Otherwise the reason is the sanitized `terminal_reason`, unless it is empty or `"completed"`; else
  `outcome == "error_max_turns"` maps to `"max_turns"` and `"error_max_budget_usd"` to `"budget_exhausted"`;
  else a non-empty sanitized `error_category` reads the reason as `"api_error"`; else the sanitized
  `outcome` itself, with `"success"` (an `is_error` turn with no other detail) left as `""`.
- `category` is always the sanitized `error_category`, carried independent of whether it decided the
  reason.

## `withFinalizedTurn` — the one place both lanes settle a turn

`HistoryPageReducer.kt`'s `withFinalizedTurn(event, occurredAt: Instant)` settles the turn's streaming rows
as before, then end-appends `event.stoppedTurn(occurredAt)` unless `holdsStoppedTurn(event.turnId)` already
holds. Both callers were already routed through this one function:

- **Live** — `ThreadProjection.finalizeAssistantTurn` passes `Clock.System.now()`, the same instant
  `applyAssistantDelta` stamps rows with.
- **History** — `withHistoryEntry`'s `TYPE_TURN_END` arm passes `entry.timestamp`, the page's own stored
  time.

`withSettledTurns` (#1419's late multi-turn settle) is unchanged and does not call `stoppedTurn`, so a late
settle adds no row — only the two call sites above do.

**Merge.** `alreadyHolds` gained `is ThreadItem.StoppedTurn -> holdsStoppedTurn(row.turnId)`; `joinIdentity`
gained `listOf("stopped", turnId)`. A history page's stopped row and the live row of the same turn join once
through the existing `mergeHistoryRows` skip-and-prepend path, the same as every other kind.

**A known merge-order gap, inherited, not new.** If the live thread missed a turn's live `turn_end` during a
connection (e.g. after a `ReplayGap`) but already holds the turn's other rows, a later history page's
`StoppedTurn` is the only new row that page contributes — skip-and-prepend puts it at index 0, above the
turn it describes, and the cache then saves that order. `Banner`, `CompactionBoundary` and `ModelRefusal`
have the same gap, and the plan accepted the existing merge semantics rather than special-casing this kind.
Verifier NIT on #1356, non-blocking; a candidate follow-up if seen on a device.

## The row composable

```kotlin
@Composable
fun StoppedTurnRow(item: ThreadItem.StoppedTurn, agent: ConversationAgent, modifier: Modifier = Modifier)
```

One bare `Text`, `bodyMedium` / `onSurfaceVariant`, the same `MessageContentGutter` / `MessageAreaRowSpacing`
modifier [`BannerNoticeRow`](banner-notice-row.md) uses — no bubble, border or icon
([Figma 620:1574](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576), "No details" state,
as placed in [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8)). The "Stopped" lead
is client-owned copy, so the meaning does not depend on colour.

`stoppedTurnLabel(item, agent): String` is a `@Composable` that re-applies `stoppedReportText` to both
`item.reason` and `item.category` before mapping reason to copy — so a row restored from the cache is held
to the same rule a live one was built by, not trusted to have stayed sanitized on disk. Copy
(`strings.xml`, every string client-owned):

| reason | text |
|---|---|
| `max_turns` | Stopped: turn limit reached |
| `budget_exhausted` | Stopped: budget exhausted |
| `prompt_too_long` | Stopped: context too long, compact or reset |
| `api_error` | Stopped: API error |
| `hook_stopped`, `stop_hook_prevented` | Stopped by a hook |
| `model_error` | Stopped: model error |
| other non-empty | Stopped: `<reason>` |
| `""` | Stopped: error |

A non-empty `category` appends `" (<agentName> reported: <category>)"` via `agentName()`
(`components/AgentName.kt`, the same closed-enum idiom `BannerNoticeRow` uses) — the client-owned attribution
names the conversation's own agent, never a fixed "Claude", matching [#1113](https://github.com/pyrycode/pyrycode-mobile/issues/1113)'s
rollout across the thread's other agent-naming rows. `ThreadScreen` renders the row with `state.agent`.

**The category clause is one plain string, unlike `BannerNoticeRow`'s separate styled attribution span.** A
category such as `"x) (Codex reported: y"` renders what looks like a second attribution clause, since
nothing stops the agent-authored category from containing the literal text `") (Codex reported: "`. The
impact is low — the category is bounded to 256 bytes and always follows the client-owned "Stopped" lead —
and desktop's `stoppedTurnText` has the same shape; the ticket asks for its copy exactly, so this keeps
parity rather than diverging from desktop. Verifier NIT on #1356, non-blocking; any change belongs on both
clients.

## `ThreadRow` / `ThreadScreen` wiring

All four exhaustive `when`s over `ThreadItem` gained a `StoppedTurn` arm:

- **`ThreadRow.listKey()`** — `"stopped:$turnId"`, a distinct namespace literal; unique because
  `holdsStoppedTurn` is, and the turn id is the key's whole tail so no separator inside it can make two ids
  spell one key.
- **`ThreadScreen`'s `LazyColumn` render** — `StoppedTurnRow(item = item, agent = state.agent)`, inside the
  same `rowAlpha`-driven `Box` as its neighbours.
- **`ThreadItem.timestamp()`** — `occurredAt`.
- **`RemoteConversationRepositoryTest.threadShape()`** — the test-fixture helper outside production code
  that also needs every `ThreadItem` arm to keep compiling, the same fourth site #608's and #873's Lessons
  learned named.

## Cache

`cacheableThreadRows` already keeps every kind but `ThreadItem.UnrecognizedMessage`, so no change was needed
there. `FileConversationCache.toRecord` maps the row to `CachedStoppedTurn(turnId, reason, category,
occurredAt)`; `CachedThreadRow.stopped: CachedStoppedTurn? = null` defaults to null like its siblings, so a
document written before this change still loads — pinned by the existing
`a thread document holding only messages and boundaries still reads` test, which has no `stopped` key and
still reads (the plan listed a separate "pre-change document still loads" test; the verifier confirmed that
existing test already covers it and nothing was missing). `decodeThread` rejects a document whose stopped
rows repeat a `turnId`, the same posture as the other kinds' identity fields.

## Testing

- `StoppedTurnTest` (JVM): the rule table from desktop's `stoppedTurn.test.tsx` — terminal-reason preference
  over the outcome, the two budget outcomes and a category each giving their mapped reason, suppression for
  `cancelled` / `isError = false` + empty-or-`success` outcome / the three daemon-only early-stop reasons
  alone, and `stoppedReportText` removing line breaks, NUL, DEL, ESC, bidi overrides and isolates, the
  zero-width joiner, U+2028/U+2029 and an astral format character (U+E0001) without cutting printable text,
  measuring the 256-byte bound in UTF-8 (including the multi-byte and lone-surrogate edges).
- `HistoryPageReducerTest`: a page with a failing `turn_end` yields the stopped row after the turn's last
  row, stamped with the entry's own `ts`; a cancelled or successful one yields none; a repeated `turn_end`
  in one page yields one row; the live `withFinalizedTurn` row and a history page's row of the same turn
  merge to one; `mergeCached_eachKindJoinsItsLiveTwinOnItsKeyAlone` gained the kind;
  `withFinalizedTurn_appendsTheStoppedRowOnce_andAnotherTurnsRowStill` pins the dedup and that a second
  turn still gets its own row.
- `ThreadProjectionTest.finalizeAssistantTurn_failedTurn_appendsOneStoppedRowThatOutlivesTheNextTurn`: the
  live path end to end — a failed `turn_end` appends one row after the turn's assistant message, a
  duplicate and a cancelled turn add nothing, and the row is still there after the next turn's
  `AssistantDelta`.
- `FileConversationCacheThreadTest`: round-trip of the row field-for-field through a fresh cache instance
  (what a process restart reads); a document with a repeated stopped `turnId` is rejected and the read
  reads empty.
- `StoppedTurnRowTest` (sharedTest, Robolectric): the full copy table rendered; a category credited to the
  conversation's own agent, both names; hostile reason/category text (a tag-like string, bidi overrides,
  line separators, and both fields over 256 bytes) cannot alter the fixed text; the row draws as plain,
  non-clickable `Text`.
- Not operator-facing as a new flow — the row is drawn from an existing `turn_end` frame with no action —
  so no rung-3/4 scenario, the same posture `turn-outcome-indicator.md` records for its own ladder.

## Security

Builder self-review **PASS** (full pass in the
[architecture plan](../../specs/architecture/1356-stopped-turn-row.md#security-review)); verifier review
**PASS** with three non-blocking NITs (the merge-order gap and the unstyled category clause above, and a
plan-wording note that the pre-change cache test already existed). `reason` and `category` are
agent-authored, bounded by the daemon's frame-size cap and the 256-byte report-text rule but not sanitized
on the wire; both cross `stoppedReportText` once when the row is built and again at render — a tampered
cache cannot bypass the inert rule because render re-sanitizes. The render path is a plain `Text`: no
markdown, no link detection, no `SelectionContainer`; the category is attributed through the client-owned
`agentName()`, never the daemon's token. `turnId` becomes a `LazyColumn` key; uniqueness is enforced by
`holdsStoppedTurn` in both writers and by `decodeThread` on restore, and a hostile duplicate loses the
second row rather than crashing the list. Nothing on this path is logged.

## Related

- Ticket: [#1356](https://github.com/pyrycode/pyrycode-mobile/issues/1356).
- Spec: [`docs/specs/architecture/1356-stopped-turn-row.md`](../../specs/architecture/1356-stopped-turn-row.md)
  (design + security review, verdict PASS).
- Wire SSOT: `pyrycode/docs/protocol-mobile.md` § `turn_end` (sibling checkout).
- Desktop sibling: pyrycode-desktop `ConversationScreen.tsx`'s `stoppedTurnText` / `stoppedReportText`, the
  `turnEnd` arm of `store/threadTimeline.ts`'s `reduceTimelineContent`, and `stoppedTurn.test.tsx`.
- Structural precedent: [`Banner notice row`](banner-notice-row.md), [`Model refusal row`](model-refusal-row.md)
  — same bare-text treatment and per-ticket landing shape; read both before adding a further `ThreadItem`
  variant, especially for the identity-key choice (`turnId` here vs. their wire-minted `(type, ts)`).
- Sibling status surface: [Turn-outcome indicator](turn-outcome-indicator.md) — the status-area arm this row
  does **not** replace; see its § How this differs for the split (changed separately in
  [#1357](https://github.com/pyrycode/pyrycode-mobile/issues/1357)).
- Consumers: [`Conversation repository`](conversation-repository.md) (`ThreadItem`, co-located types),
  [`Thread screen`](thread-screen.md) (`LazyColumn` key, render arm, `timestamp()`), [`Conversation
  cache`](conversation-cache.md) (`CachedStoppedTurn`, kept like its siblings).

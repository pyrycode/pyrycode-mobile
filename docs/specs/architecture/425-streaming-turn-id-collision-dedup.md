# Spec #425 — Streaming synthetic turn must never share a LazyColumn key with a persisted message

**Size:** S · **`security-sensitive`** (re-audit of the #337 fold — it sits on the network-input boundary, consumes the server-controlled `turnId`, and carries the per-conversation confidentiality + no-log invariants; see § Security review).
Surfaced by the #421 rung-3 live e2e. Targets the #337 fold in `ThreadViewModel.kt`. No new types, no data-layer change, no DI change — one render-level guard clause plus a corrected comment and one new test.

## Files to read first

| Path | What to extract |
|---|---|
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:531-547` | **The edit site.** `ThreadFold.render()` builds the synthetic streaming `Message(id = turn.turnId, …)` and appends it. The stale comment at lines 537-538 ("distinct namespace from the server message_id" / "never both present") encodes the two now-broken assumptions. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:439-529` | The fold internals you must NOT need to change: `StreamingTurn` (the `baselineAssistantIds` finalize oracle, line 448-449), `ThreadFold.reduce` (the `turnPersisted` Finished arm, lines 472-481 — the guard that *misses* the collision), `reduceLive`/`reduceDelta`. Read to confirm the fix lands in `render()`, not here. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:197-205` | The LazyColumn `key` lambda: `MessageItem -> "msg:${item.message.id}"`. This is the sink that throws `IllegalArgumentException: Key … already used` on a duplicate. Confirms the uniqueness invariant is **per `MessageItem.message.id`**. |
| `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` | The synthetic message shape: `Message(id, sessionId, role, content, timestamp, isStreaming, toolCall=null)`. `id` is the value that becomes the list key. |
| `app/src/main/java/de/pyryco/mobile/data/model/LiveSessionEvent.kt:46-53, 16-21` | `AssistantDelta(conversationId, turnId, seq, text)` — `turnId` is **server-controlled** and (per the bug) can equal a persisted `message_id`. The class KDoc's verbatim-untrusted-text + per-conversation contract (load-bearing for § Security). |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:358-447` | The two existing finalize tests (`turnEndThenFinishedMessage_settlesToFinishedWithoutDuplicate`, `finishedMessageBeforeTurnEnd_dropsStreamingWithoutDuplicate`) — the new collision test slots beside them and mirrors their shape exactly. |
| `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1409-1453, 1550-1556` | The test idiom: `MessagesControllableRepo` (exposes `messages: MutableStateFlow<List<ThreadItem>>` for `observeMessages`), `makeVm(activeHandle(), repo, liveSessionEvents = events)`, `assistantMessage(id, content)`, `messageIds(vm)`, `streamingContents(vm)`, `ACTIVE_CONV`. |
| `docs/specs/architecture/337-streaming-assistant-turns.md` (§ Open questions, § Security review) | The fold's own design + security review. Its Open-questions reconnect note got the *direction* wrong (assumed a backfilled *new* id would finalize early); the real failure is the opposite — the colliding id is *already in baseline*, so finalize never fires. This spec corrects that. |
| Memory `phase4-v2-wire-no-streaming.md` | The finished `message` payload (`{conversation_id, message_id, role, text}`) carries no `turn_id` — why correlation is structural, and why the daemon's `turnId` and a `message_id` can coincide. |

## Context

The thread folds the #313 finished-message projection (`observeMessages`) together with the #385 live
`assistant_delta` stream into one `items` list — the #337 fold in `ThreadViewModel`. While a turn is
in flight the fold renders a **synthetic** streaming `MessageItem` whose `id` is the turn's `turnId`
(`ThreadViewModel.kt:539`). The thread screen keys every `MessageItem` as `"msg:${message.id}"`
(`ThreadScreen.kt:201`).

The #337 render comment asserted two things that hold against the fake but break against the live
daemon: the synthetic `id` lives in *"a distinct namespace from the server message_id"*, and the
synthetic and the finished message are *"never both present."* Against pyrycode `main` the daemon's
`turnId` **equals** the persisted assistant `message_id`. So the synthetic and the finished message
produce the same `"msg:<id>"` key, and when both are in the rendered list Compose throws:

```
IllegalArgumentException: Key "msg:<id>" was already used.
```

The structural finalize guard (`ThreadFold.reduce`'s `Finished` arm, `turnPersisted`,
`ThreadViewModel.kt:472-481`) **misses the collision**: it drops the synthetic only when the finished
list gains an assistant id **not** in `stream.baselineAssistantIds`. In the live failure the colliding
id was already in the baseline set when the turn started (the persisted message was present at first
delta — the reconnect/replay ordering), so finalize never sees a "new" assistant id and never drops
the synthetic. The crash is server-triggerable.

A defensive render guard was proven locally during #421 and carried that run to green end-to-end. This
ticket lands it on `main`.

## Design

### Altitude: one render-level guard clause in `ThreadViewModel.kt`

The crash is a **list-key uniqueness** failure, and `ThreadFold.render()` is the single point where
the rendered `items` list (and therefore its keys) is assembled. Enforce the invariant there: the
synthetic streaming `MessageItem` is appended **only if** the finished list does not already carry a
`MessageItem` with the same `id` as the synthetic (`turn.turnId`).

This is the load-bearing fix and the only behavioural change. No new types; no change to `reduce`,
`reduceLive`, `reduceDelta`, or the `StreamingTurn`/`ThreadFold` shapes.

#### The guard (contract, not a full body)

In `ThreadFold.render()` (`ThreadViewModel.kt:532`), immediately after `val turn = stream ?: return finished`
and before constructing the synthetic, add a guard:

> If `finished` contains any `ThreadItem.MessageItem` whose `message.id == turn.turnId`, return
> `finished` unchanged (the persisted message for this turn is already in the list — render it alone;
> do not append the synthetic).

One predicate over the already-in-hand `finished` list (`finished.any { it is ThreadItem.MessageItem && it.message.id == turn.turnId }`).
The render stays a pure function of `(finished, stream)`; the post-guard list can never contain two
`MessageItem`s sharing an `id`, so the `"msg:<id>"` key is unique **regardless of how the fold state
arose** (collision present at turn start, arriving mid-stream, or replayed).

#### Why render-level, not a finalize/reduce change

- **Source-independent.** The guard is a pure projection over the rendered list; it does not need to
  know *why* the ids collide (it makes no assumption that `turnId` equals a `message_id`). It enforces
  "the synthetic never duplicates a finished key" at the point of consequence — the keys themselves.
- **The reduce arm can't catch this ordering.** In the live failure the `Finished` projection carrying
  the colliding message arrives *before* the first delta creates `stream`. The `turnPersisted` finalize
  in `reduce` is guarded by `stream != null`, so it never re-fires for that already-passed projection.
  Only a render-time check sees both the live `stream` and the standing `finished` together. A
  reduce-time fix would have to special-case every interleaving; render-time is total over them.
- **Belt-and-suspenders, different fabric.** The existing baseline-diff finalize (suspenders) still
  drops the stream in the *non-colliding* case (a genuinely new assistant id appears → `stream = null`
  → fewer lingering accumulators). The render guard (belt) deterministically guarantees key uniqueness
  in the colliding case. Both are deterministic code; neither relies on the other.

#### What happens to `stream` in the colliding case

In the collision ordering the baseline-diff finalize never fires, so `stream` lingers as
non-rendered state until the next turn's first delta supersedes it (`reduceDelta`'s `turnId != …`
arm, `ThreadViewModel.kt:512`). This is harmless: `render()` keeps suppressing the synthetic while the
persisted message is present, and `distinctUntilChanged` (`ThreadViewModel.kt:180`) collapses the
no-op re-emissions so there is no flicker. We deliberately do **not** add a reduce-time null-out for
the lingering stream — it would be dead in the load-bearing ordering and is not needed for any AC
(Evidence-Based Fix Selection: no observed failure from the lingering stream).

#### Stale comment correction

Replace the `ThreadViewModel.kt:537-538` comment. The two assumptions ("distinct namespace from the
server message_id" / "never both present: finalise drops this in the same emission") are false against
the live daemon and must be removed. The replacement states the actual invariant: the synthetic reuses
`turn.turnId` as its id, and the render guard above ensures it is appended only when no finished
message already carries that id, so the `"msg:<turnId>"` key never collides with a persisted
`"msg:<messageId>"` — the daemon may set `turnId == message_id`.

### Why not a `stream:` key prefix (the ticket's alternative root option)

Namespacing the synthetic key (`"stream:<turnId>"` vs `"msg:<messageId>"`) would stop the *crash* but
**not** the double-render: during the window where both the finished message and the synthetic are
present (the colliding-at-baseline case the finalize misses), the thread would show **two** assistant
bubbles with the same text — AC#2 forbids the double-render explicitly. A prefix would therefore *also*
require a finalize fix to drop the synthetic, i.e. two changes where the render guard is one. The
render guard alone satisfies AC#1 (no crash) **and** AC#2 (settles to exactly one) because it
suppresses the synthetic exactly when the finished message with that id is present. The prefix is
strictly more code for a worse result; rejected.

## State + concurrency model

No change. The guard is a branch inside the existing pure `render()` mapped over the existing
`scan(ThreadFold(...))` in the `threadItems` flow (`ThreadViewModel.kt:174-180`), itself inside the
`state` combine's `stateIn(viewModelScope, WhileSubscribed(5_000))`. No new flow, coroutine, scope,
dispatcher hop, or mutable state. The fold remains pure CPU (one `any { }` over the finished list per
emission). `distinctUntilChanged` continues to absorb no-op re-emissions.

## Error handling

No new failure mode and no new result type. The guard *removes* a failure mode (the dup-key
`IllegalArgumentException` thrown downstream in `LazyColumn`). All pre-existing degradations are
unchanged: wrong-conversation events dropped by the conversation-id guard, out-of-order `seq` ignored,
`turn_end` with no matching turn a no-op, cancelled turn keeps its settled partial. The reduction stays
total over the sealed `LiveSessionEvent` (no `else`). No exception is thrown or caught here.

## Testing strategy

Unit only (`./gradlew testDebugUnitTest --tests "de.pyryco.mobile.ui.conversations.thread.ThreadViewModelTest"`
— bare `test --tests` fails, see memory `gradle-single-test-class-task`). No `ComposeTestRule` — there
is no new composable; the behaviour under test is entirely the VM fold. Mirror the existing
`MessagesControllableRepo` + `makeVm(activeHandle(), repo, liveSessionEvents = events)` idiom. The
key-uniqueness assertion is the VM-level proxy for the LazyColumn crash: assert `messageIds(vm)` has
**no duplicates** (`== messageIds(vm).distinct()`) — a duplicate id is exactly a duplicate
`"msg:<id>"` key.

New test — **required by AC#4** (collision present at turn start):

- **`assistantDelta_turnIdEqualsPersistedMessageId_noDuplicateKeySettlesToSingle`** — seed
  `repo.messages.value = listOf(assistantMessage(id = "t1", content = "Done"))` so the persisted
  assistant id `"t1"` is in the baseline at turn start. Start the VM + collector. Emit
  `AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "Done")` (turnId == persisted id) →
  `advanceUntilIdle`. Assert: (a) `messageIds(vm) == listOf("t1")` — exactly one item, settled to the
  persisted message; (b) `messageIds(vm) == messageIds(vm).distinct()` — no duplicate list key
  (AC#1); (c) `streamingContents(vm).isEmpty()` — the synthetic was suppressed, no transient
  streaming bubble (AC#2). Optionally extend with a later `TurnEnd(ACTIVE_CONV, "t1", …)` and assert it
  stays single (no resurrected synthetic).

Secondary scenario (recommended, guards AC#3 against regression in the *other* collision ordering —
synthetic shown first, then the same-id finished message arrives):

- **`streamingThenFinishedMessageWithSameTurnId_settlesWithoutDuplicate`** — empty backfill; emit
  `AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "Don")`, `(seq = 1, text = "e")` →
  assert one growing streaming item `content == "Done"`, `isStreaming == true` (AC#3 streaming still
  works). Then `repo.messages.value = listOf(assistantMessage(id = "t1", content = "Done"))` (finished
  id equals the turnId) → assert `messageIds(vm) == listOf("t1")`, no duplicate, no streaming item.

The existing fold tests (`turnEndThenFinishedMessage_…`, `finishedMessageBeforeTurnEnd_…`,
`streamingTurn_appendsAtEndWithoutReorderingBackfill`, `cancelledTurn_…`, the seq/dedup and
conversation-id-scoping tests) all use **distinct** ids and must stay green unchanged — proof the
non-colliding path (AC#3) is preserved.

## Open questions

- **Lingering `stream` in the colliding ordering.** As noted in § Design, `stream` is not nulled when
  the render guard suppresses it; it is superseded by the next turn. If a future ticket surfaces
  per-turn diagnostics off the live `stream` count, that ticket may add a reduce-time null-out. Not
  needed now (no observed failure).
- **Whether the daemon should stop setting `turnId == message_id`** is a server question (out of mobile
  scope). The fix is robust either way — it makes no assumption about the relationship between `turnId`
  and `message_id`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Conversation Thread — a dark, scrollable column of right-aligned blue user bubbles and left-aligned
dark assistant bubbles (markdown bodies, a `read_file` tool row, a fenced `typescript` code block),
with a centered session-boundary delimiter ("Workspace changed to … / Claude doesn't remember … Install")
and a sticky status row + message input bar at the bottom. The streaming assistant message renders at
the bottom as an ordinary assistant bubble. **No visual change** — this is a rendering-correctness fix
to the existing (locked) thread design; visual fidelity is owned by the build tickets (#128/#129/#184),
not this one.

## Security review

**Verdict:** PASS

Run adversarially against the spec above, assuming it has holes. The ticket carries `security-sensitive`
because the fold sits on the **network-input boundary**: it consumes the server-controlled `turnId`,
and #337 (which built it) was security-reviewed for two invariants — per-conversation confinement and
no-log of verbatim untrusted delta text. This fix touches the fold's render step, so both invariants
are re-audited, plus the new crash-as-DoS angle the ticket raises.

**Findings:**

- **[Trust boundaries — per-conversation confinement]** No findings, and the guard does not weaken it.
  The new predicate runs entirely inside `render()` over `finished` (from
  `repository.observeMessages(conversationId)`, conversation-scoped by #313) and the live `stream`,
  which is only ever populated by `reduceLive` *after* its `event.conversationId == conversationId`
  guard (`ThreadViewModel.kt:489`, checked first). The guard compares ids drawn from those two
  already-confined sources only — it introduces no path by which a foreign conversation's message or
  text enters this thread's `items`. The existing `conversation-id scoping` test remains the oracle.
- **[Server-controlled id as a comparison key]** No finding — and the explicit reason the fix is safe.
  `turnId` is attacker-influenceable (a malicious/buggy daemon picks it). The guard's *only* use of it
  is an equality compare against finished `message.id` to decide whether to append the synthetic. The
  worst a hostile `turnId` can do is (a) collide with a finished id → synthetic suppressed (the
  finished message renders — the safe outcome), or (b) not collide → synthetic appended as before. No
  `turnId` value can produce a duplicate key (that is precisely what the guard prevents) and none can
  evict or alter a *persisted* message — the guard only ever *withholds the synthetic*, never mutates
  `finished`. Confidentiality and integrity of the persisted thread are untouched.
- **[Crash as server-triggerable DoS]** This is the vulnerability being **closed**, not opened. The
  pre-fix behaviour is a server-triggerable client crash (the daemon sets `turnId == message_id` and
  the thread throws). The guard makes a duplicate key structurally impossible at the assembly point,
  removing the crash for any id the server sends. Net security posture improves.
- **[Error messages, logs, telemetry]** No findings — and the #337 MUST holds unchanged: the guard
  adds **no logging** of delta text, accumulated content, `turnId`, or any payload field. The fold
  stays pure; there is no new error string. Code-review must reject any debug log added over `turnId`
  or delta content while touching this code.
- **[Tokens, secrets, credentials]** N/A — the VM sees decoded `LiveSessionEvent`s only, far downstream
  of all credential handling. `turnId` is a server-minted grouping key, not a secret.
- **[File / storage operations]** N/A — no path handling, file I/O, or persistence. The synthetic is
  in-memory and ephemeral.
- **[Inter-process / Android attack surface]** N/A — no Intent, deep link, provider, or WebView. The
  guard is pure Kotlin over in-memory lists; `data/` stays portable.
- **[Network & I/O — unbounded accumulation / DoS]** No new exposure. The guard adds one `any { }` scan
  over the finished list per emission (already iterated downstream). The `stream.text` accumulation
  bound is unchanged from #337 (one turn's coalesced output, cleared on supersession; the lingering
  stream in the colliding case holds at most one turn's text and is superseded next turn). No
  per-event allocation growth.
- **[Concurrency]** No findings. No new coroutine, scope, or shared mutable state — the guard is a
  branch inside the existing pure `render()` mapped over the existing `scan` inside the existing
  `stateIn`. No `check-then-mutate` on a `StateFlow`.
- **[Threat model alignment]** The mobile-relevant threats remain covered: foreign-conversation leakage
  (guard unchanged, checked first), untrusted-content rendering (no new sink — the synthetic still
  flows through the same `MessageBubble` path), log/telemetry leakage (no logging), and now the
  server-triggerable dup-key crash (closed by this fix). The fix introduces no new trust boundary.

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-19

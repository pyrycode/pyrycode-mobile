# #645 — merge history pages into the mobile timeline

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `HistoryPage`,
  `HistoryEntry`, `ThreadItem` — the page contract #623 landed and the row type the reduction produces.
  `HistoryEntry`'s KDoc names the `id`-vs-`eventId` trap this plan must not walk into.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` →
  `requestHistory` (the ask, whose KDoc already promises "folded into the timeline by #645"),
  `threadByConversation` (the projection to write), and the five live folds this ticket reuses:
  `appendMessages`, `applyToolUse`, `applyToolResult`, `applyAssistantDelta`, `finalizeAssistantTurn`,
  plus their shared `indexOfMessage` guard. Also `sendMessage` — the local echo AC #2 collapses against —
  and `onInbound`'s `CAPABILITY_INTERACTIVE` gating, which the reduction mirrors arm-for-arm.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `MessagePayloadDto.toMessage`
  (takes an `Envelope` today; a `HistoryEntry` has no envelope) and `SendMessagePayloadDto` — the shape a
  stored `send_message` entry's payload carries.
- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `ToolUsePayloadDto.toEvent`,
  `ToolResultPayloadDto.toEvent`, `AssistantDeltaPayloadDto.toEvent`, `TurnEndPayloadDto.toEvent`,
  `SessionTransitionPayloadDto.toBoundary`, `UnrecognizedMessagePayloadDto.toRow`. All six are already
  **payload-level** (no `Envelope`), and `toRow` already takes its two client-owned values as parameters —
  which is why this ticket mints no second decode surface.
- `app/src/main/java/de/pyryco/mobile/data/network/HistoryPayloads.kt` → `toHistoryPage` — confirms the
  wire's newest-first order is preserved verbatim, so reversing is the reducer's job.
- `docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md` → the thread store's
  arrival-order rule, and the precedent for this exact move: #336 lifted the five folds to `ThreadItem`
  "output-preserving (the existing suite is the guard)". Same guard applies here. It also records the
  known O(n²) `appendMessages` dedup as deferred-by-evidence; the reduction inherits that shape and the
  same deferral.
- `docs/knowledge/features/session-transition-fold.md`, `unrecognized-message-row.md` → the two row kinds
  the thread deliberately does **not** dedup today, and why (AC #3 has to solve both without changing the
  live posture).
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md` § *Conversation history
  (v2)* → *A history entry*, *Joining a page to the live stream*. The wire SSOT for the three join keys
  and for the untrusted-content posture. Not restated below; cited.

## Context

#623 landed `requestHistory` and the `history_page` decode, and deliberately stopped there: its KDoc says
a page "is returned to the caller and folded into the timeline by #645, so this repository touches no
projection". This ticket is that fold. Nothing in the app asks for a page yet — #646 owns the demand side
(open, scroll-back, reconnect) and #647 reuses the reducer for its offline cache; both are blocked on this.

No ADR is warranted: this adds no new architectural choice, it reuses the arrival-order thread store,
the existing decode arms and the existing capability gate.

## Design

### One fold surface, not two

The reduction runs **the same folds the live lane runs**. The five live folds are today
`MutableStateFlow.update { … }` methods whose bodies are already pure `List<ThreadItem>` transforms with a
map layer wrapped around them. This ticket lifts the transforms out into pure extensions in a new file and
leaves the five methods as thin wrappers over them — the #336 move, repeated, with the existing
`RemoteConversationRepositoryTest` suite as the output-preserving guard.

New file `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`:

- `internal fun List<ThreadItem>.indexOfMessage(id: String, role: Role): Int` — moved verbatim.
- `internal fun List<ThreadItem>.withMessage(message: Message): List<ThreadItem>` — `appendMessages`' per-row body.
- `internal fun List<ThreadItem>.withToolUse(event, timestamp): List<ThreadItem>` — `applyToolUse`'s body,
  with the row clock **hoisted to a parameter**. The live wrapper passes `Clock.System.now()`; the reducer
  passes the entry's stored `ts`. That is the only behavioural difference between the two lanes, and it is
  the right one: a replayed tool row must not be stamped with the moment it was replayed.
- `internal fun List<ThreadItem>.withToolResult(event): List<ThreadItem>`,
  `withAssistantDelta(event, timestamp)`, `withFinalizedTurn(event)` — likewise.
- `internal fun reduceHistoryPage(entries: List<HistoryEntry>, interactive: Boolean): List<ThreadItem>` —
  the page → rows reduction.
- `internal fun List<ThreadItem>.mergeHistoryRows(rows: List<ThreadItem>): List<ThreadItem>` — the merge.

`app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` gains the payload-level entry point the
ticket asks for: `fun MessagePayloadDto.toMessage(timestamp: Instant, sessionId: String): Message`, with
the existing `toMessage(envelope, sessionId)` delegating to it via `Instant.parse(envelope.ts)`. One
mapping, two callers — no second mapping surface, and the envelope form's contract is unchanged.

### The reduction

`reduceHistoryPage` reverses the wire's newest-first `entries` to oldest-first and folds them through the
extensions above, starting from an empty list. Dispatch is on `HistoryEntry.type`:

| `type` | Arm | Gated |
|---|---|---|
| `message` | `MessagePayloadDto` → `toMessage(entry.timestamp, sessionId = "")` → `withMessage` | no |
| `send_message` | `SendMessagePayloadDto` → `Message(id = messageId, role = User, content = text, timestamp = entry.timestamp)` → `withMessage` | no |
| `tool_use` / `tool_result` | `toEvent()` → `withToolUse(…, entry.timestamp)` / `withToolResult` | yes |
| `assistant_delta` / `turn_end` | `toEvent()` → `withAssistantDelta(…, entry.timestamp)` / `withFinalizedTurn` | yes |
| `session_transition` | `toBoundary()` → append when no structurally-equal boundary is present | yes |
| `unrecognized_message` | `toRow(id = "history-${entry.id}", occurredAt = entry.timestamp)` → append when no row with that id is present | yes |
| anything else | **dropped** | — |

Two properties fall out of that table rather than being bolted on:

- **AC #4 is structural.** `turn_state`, `stall`, `queue_state`, `api_retry`, `compacting`, `modal_shown`
  and `modal_dismissed` map to no `ThreadItem`, so they land in the `else` arm. The reducer's return type
  is `List<ThreadItem>` and it takes no reference to `stalledConversations`, `mutableLiveSessionEvents` or
  the modal stream — a stored state frame *cannot* reach a live-state holder, rather than being checked
  not to.
- **Unknown types and malformed payloads survive.** An unrecognised `type` is the `else` arm; a payload
  that fails its per-entry decode drops **that entry only**, inside the same
  `try/catch (IllegalArgumentException)` idiom every `onInbound` arm uses. The page is never failed and
  never dropped wholesale — deliberately unlike the `message_chunk` arm, whose whole-batch drop is a
  property of one envelope decoded as a unit; here each entry's `payload` is an independent second decode.

**The gate mirrors the live lane arm-for-arm.** `requestHistory` passes
`CAPABILITY_INTERACTIVE in negotiatedCapabilities()`; the six structured arms are gated exactly as their
live twins are, and `message` is ungated exactly as its live twin is. The daemon's `request_history`
handler carries no interactive gate (checked against `internal/relay/v2session_history_request.go`), so
without this the client's fail-closed posture would have a hole the live lane does not have.

### The merge

`mergeHistoryRows` is a **prepend, never a re-sort** — the thread is in arrival order by deliberate choice
(`applyToolUse`'s KDoc) and sorting the merged list would reorder live rows that are currently correct:

```
existing.mergeHistoryRows(reduced) == reduced.filterNot { existing.alreadyHolds(it) } + existing
```

`alreadyHolds` is the three-key join, expressed per row kind. **Each check uses the key `ThreadScreen`'s
`LazyColumn` keys that row kind on** — that is the deliberate choice, not structural equality, because a
row the merge lets through is a row the renderer must be able to key uniquely:

- `MessageItem` → any `MessageItem` with the same `message.id`, **id-only, role-agnostic**. That is both
  `appendMessages`' existing dedup rule (role-agnostic by design — see the thread-store overview) and the
  renderer's `"msg:${id}"` key. One key serves all three sources: a `message_id` (AC #2's collapse against
  the local echo, and the page-against-page join for stored `message` / `send_message` entries), a
  `tool_use_id`, and a `turn_id`. Two sends of identical text carry different ids, so they stay two rows.
  Note the asymmetry with the *reduction*, which folds through `indexOfMessage(id, role)` because the
  lifted live folds do; the merge's presence check is a different job and takes the renderer's key.
- `SessionBoundary` → any boundary with the same `(previousSessionId, newSessionId)` pair — again the
  renderer's key, and deliberately **stricter than structural equality**. Identical entries are `==` so
  AC #3 holds either way, but a page carrying two boundaries that share a session pair and differ only in
  `occurredAt` would pass a structural check and then give `ThreadScreen` two rows with one
  `LazyColumn` key, which throws. An honest daemon does not mint such a pair (a session id rotates once);
  a hostile one trivially can. Dropping the second boundary is the fail-safe direction — a missing
  delimiter, not a crashed thread. This closes the history path only; the live lane's
  `appendSessionBoundary` does not dedup at all and has the same exposure, which is pre-existing, outside
  this ticket's scope, and filed as its own bug (see § Security review).
- `UnrecognizedMessage` → equal `id`. `"history-${entry.id}"` is derived from the durable per-conversation
  log id, so it is stable across re-reduction and across pages; the live lane's `"unrecognized-N"`
  namespace cannot collide with it. The same presence check runs **inside the reduction** as well as in
  the merge, so a page whose entries repeat one log id yields one row rather than a duplicate-key crash.

The merge **skips** a duplicate rather than updating it. A page is older than the live lane, so the only
overlap is the narrow ask-vs-answer race the protocol names, in which the live lane still owns the newer
state and will complete the row itself. Updating in place would also break AC #1's "rows already in the
thread keep their existing relative order".

**Routing is by the conversation the client asked about, never by an entry's payload `conversation_id`.**
`reduceHistoryPage` returns a bare `List<ThreadItem>` carrying no routing information, so a page
structurally cannot write into another conversation's thread. See § Security review.

### The one consumer

`requestHistory` folds before returning — the read stays a read for its caller, and gains one write:

```
val page = MobileJson.decodeFromJsonElement<HistoryPagePayloadDto>(reply).toHistoryPage()
mergeHistoryPage(conversationId, page)   // threadByConversation.update { … }
return page
```

`mergeHistoryPage` is a private repository method doing one atomic `MutableStateFlow.update` on
`threadByConversation[conversationId]`, exactly as `sendMessage`'s confirmed insert already writes that
projection from a caller coroutine. **The merge is computed inside the `update {}` lambda, never outside
it**: the reduction is pure and may be hoisted, but reading the current thread, merging and assigning are
one check-then-act — doing it outside the lambda would silently lose a concurrent live append on every CAS
retry. `observeMessages` / `threadProjection` are untouched: the store already
holds `ThreadItem`s and `distinctUntilChanged` re-emits the enlarged thread on its own. `backfill_since`
on subscription is untouched — the history walk is complementary to it, and a `message_id` seen on both
paths is the same dedup that already absorbs a re-delivered backfill.

## State + concurrency model

No new coroutine, no new scope, no new flow. The reducer is a pure function. The single write is one
`MutableStateFlow.update {}` CAS on the existing `threadByConversation`, run on the caller's coroutine (the
one awaiting `requestHistory`) — so it retry-merges against a concurrent inbound-collector write rather
than clobbering it, which is why the read-modify-write must not be a `.value =` assignment. Cancelling the
awaiting caller before the reply lands means no fold ever runs; cancelling after it lands cannot interrupt
the CAS, which is not suspending. `negotiatedCapabilities()` is read once per page, at the fold.

## Error handling

- Per-entry decode failure → that entry is dropped; the rest of the page reduces. Never logged.
- Unrecognised `type` → dropped, no failure.
- `requestHistory`'s existing failure surface is unchanged: `IllegalStateException` (not connected),
  `IllegalArgumentException` (unknown conversation), `RelayErrorException` (`history.*`), and the decode
  exception for a malformed page — all thrown **before** the fold, so a failed ask mutates nothing.
- Nothing surfaces to the UI in this ticket; #646 owns how a failed walk is shown.

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`), no Compose test and no emulator scenario. Nothing in the
app calls `requestHistory` yet, so this ticket ships no operator-facing flow; AC #5 assigns live
verification to **#673** (*test: prove desktop-to-phone conversation continuity*), which is open.

New `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt` — pure, no relay:

- oldest-first: a newest-first page of three `message` entries reduces to ascending order.
- a `tool_use` + `tool_result` pair in one page folds to **one** completed row, not two.
- `assistant_delta` × 2 + `turn_end` in one page folds to one finalized, concatenated row.
- a `session_transition` entry becomes a `SessionBoundary` with the payload's `occurred_at`.
- an `unrecognized_message` entry becomes a row whose id is derived from the entry id.
- an unknown `type`, and a malformed payload on a known type, each drop one entry and leave the rest.
- every state/modal type (`turn_state`, `stall`, `queue_state`, `api_retry`, `compacting`, `modal_shown`,
  `modal_dismissed`) reduces to no row.
- with `interactive = false`, the six structured arms reduce to no row while `message` still reduces.
- merge: reduced rows land **ahead** of an existing thread whose relative order is unchanged (AC #1).
- merge: a stored `send_message` and a local echo with the same `message_id` collapse to one row; two
  ids with identical text stay two (AC #2).
- merge: re-merging the same page adds nothing — asserted for a message row, a boundary row and an
  unrecognized row (AC #3, the two kinds the thread does not dedup today included).
- merge: two boundaries sharing a `(previousSessionId, newSessionId)` pair but differing in `occurredAt`
  yield one row, so the renderer's `LazyColumn` key stays unique (§ Security review).
- reduction: a page whose `unrecognized_message` entries repeat one log `id` yields one row.

Added to `RemoteConversationRepositoryTest.kt` — the wiring, over the existing fake pump:

- `requestHistory` folds its page into `observeMessages(conversationId)` and still returns the page.
- the fold lands under the **asked-for** conversation even when an entry's payload names another (AC #4's
  sibling property, § Security review).
- a page whose entries are all state frames leaves `observeStall` / `observeApiRetry` / `observeCompacting`
  / `observeQueue` and the live-event stream untouched (AC #4).

The existing suite is the regression guard for the five lifted folds (AC #5): they must stay
output-preserving, so any live-fold test that reddens is a defect in the lift, not an expectation to edit.

## Sizing — one line of the boundary is exceeded, and the floor rule says build anyway

Re-counted against this written plan: production source files **3** (≤5 ✓), new exported types **0** (≤5
✓), consumer call sites **6** (≤10 ✓), acceptance criteria **5** (≤5 ✓), no state machine. Total written
work estimates at **~880 lines** against a ceiling of 800 — plan (321, of which ~65 is the mandatory
security review), reducer, the payload-level mapper, the repository wiring and two test files.

Every split I can draw produces a child whose only consumer is its sibling: reducer-then-merge (the merge
is the reducer's sole caller), lift-the-folds-then-reduce (a pure output-preserving refactor lands no
behaviour and reddens no gate), or split-by-wire-type (which cuts AC #3 in half, since that criterion is
specifically about the row kinds the thread does not dedup today). The floor rule governs: a one-consumer
slice is part of its sibling, and when the floor and the ceiling disagree the floor wins. So the overage
is stated here rather than routed back for a split. No parent, no grandparent — the depth gate does not
apply either way.

## Documentation handoff

None. The ticket carries no **Documentation handoff** section and no documentation-only acceptance
criterion. Lessons go in the PR body for the documentation phase to fold into
`docs/knowledge/features/remote-conversation-repository-reads-and-thread-store.md`.

## Open questions

1. Should the merge **update** an existing row from a page rather than skip it? Resolved in this plan:
   skip. Recorded here because it is the decision a reader will second-guess.
2. Does the live lane's `unrecognized_message` row join a page twin? It cannot — the live path stamps a
   process counter id and `Clock.System.now()`, neither payload-derived. This ticket makes the *history*
   side stable and does not change the live side. If a duplicated unrecognized row is ever observed across
   the ask-vs-answer race, the fix is to stamp the live row from its envelope `ts`; deferring per
   evidence-based fix selection, and named for #646 to watch.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries] No findings, after one design change.** The untrusted→trusted crossing is a single
  named site: `reduceHistoryPage`'s per-type arm, where `HistoryEntry.payload` (replayed, `claude`- or
  operator-authored) becomes a typed DTO and then a `ThreadItem`. The DTO never escapes the arm, matching
  `decodeUnrecognizedMessage`. No *new class* of content reaches a renderer: the protocol SSOT states an
  entry carries exactly the trust class of the live frame it mirrors, and every row kind this produces is
  one the live lane already produces into the same store and the same composables. The design change: the
  merge's presence checks were moved from structural equality to **the keys `ThreadScreen`'s `LazyColumn`
  uses**, because a row the merge admits is a row the renderer must key uniquely — see § The merge. Without
  it, a page carrying two `session_transition` entries sharing a `(previous, new)` session pair and
  differing only in `occurred_at` produced two rows with one key, which throws.
- **[Trust boundaries] SHOULD FIX — no new length bound, and none is needed; say so rather than adding
  one.** `raw` is capped daemon-side at 16 KiB, a page is bounded by the 65519-byte application-envelope
  cap (the daemon re-asks smaller rather than truncating), and `OkHttpRelayTransport`'s frame contract
  bounds it again. A fourth bound here would defend a failure that cannot reach this code — the standing
  judgement on `UnrecognizedMessagePayloadDto.raw`, unchanged.
- **[Tokens] Not applicable, with reason.** No token, key or credential is minted, stored, compared or
  read. The one opaque value on this path, the `cursor`, is handled entirely by `requestHistory`
  (unchanged here) and #623 already established it is not a capability: unsigned by design, carrying only
  the conversation id the caller supplied.
- **[File / storage] Not applicable, with reason.** No filesystem access and nothing persisted — the
  reduction is in-memory only. **No entry field ever becomes a path component, a filename or a cache key**,
  which is the concrete rule, not just an absence of file APIs. At-rest encryption for a cached page is
  #647's question, named there and not pre-empted here.
- **[IPC / Android surface] Not applicable.** No Activity, Service, Receiver, Provider, deep link,
  `PendingIntent`, WebView or FCM path is touched, and no `android:exported` changes.
- **[Cryptographic primitives] Not applicable.** No randomness and no primitive. This runs above the
  decrypted application layer; `NoiseIkSession` and the vendored `noise-java` are untouched.
- **[Network & I/O] No findings.** No new request, no change to `requestHistory`'s envelope, and no change
  to frame caps, timeouts, TLS or supervisor backoff. The fold runs on the awaiting caller's coroutine and
  the CAS does not suspend, so the single inbound collector is never on this path and cannot be stalled by
  a large page. Pre-existing and unchanged: `sendAndAwaitReply` has no per-request deadline; a daemon that
  never replies leaves the caller suspended until `failAllPending` runs at teardown (#488).
- **[Errors, logs, telemetry] No findings — and this is the load-bearing one.** **Nothing in the reducer,
  the merge or the new `requestHistory` write logs anything on any branch**: not `type`, not `payload`, not
  the entry `id`, not `conversationId`. `type`/`payload` are replayed content; a logged `conversation_id`
  is a cross-conversation correlation leak. Every drop branch is silent, matching every `onInbound` arm,
  and the per-entry `catch` returns the accumulator rather than rethrowing or wrapping, so no payload field
  can reach an exception message. Pre-existing from #623 and unchanged: a malformed *page* throws the
  kotlinx decode exception to the caller, whose message can name JSON fields.
- **[Concurrency] SHOULD FIX, written into the plan.** The merge is a read-modify-write on shared state and
  **must be computed inside the `update {}` lambda**; hoisting it out would lose a concurrent live append
  on a CAS retry. Stated in § The one consumer; the verifier should check it landed that way. No new
  coroutine, scope, mutex or hot flow, so the remaining sub-questions do not arise.
- **[Threat model] Hostile daemon — the applicable threat, closed structurally, not by check.**
  *Cross-routing:* `reduceHistoryPage` returns a bare `List<ThreadItem>` carrying no routing information
  and never reads an entry payload's `conversation_id`, so a page cannot write into a conversation the
  client did not ask about. *Reopening a prompt or restarting an indicator (AC #4):* the reducer's return
  type is `List<ThreadItem>` and it holds no reference to `stalledConversations`,
  `mutableLiveSessionEvents` or the modal stream, so a stored state frame has nowhere to land. *Row
  injection:* a page grants no capability the live lane does not already grant. *Duplicate-key crash:*
  closed for both dedup-less row kinds — boundaries by the session-pair check, unrecognized rows by the
  entry-derived id checked inside the reduction as well as the merge. *Malicious relay:* content-blind and
  on-path; it can drop, delay or reorder, each of which fails or delays one ask and leaks nothing, since
  the reduction produces no output outside the process.
- **[Threat model] OUT OF SCOPE — thread growth across a long walk.** Each page is capped, but a walk that
  merges many pages grows one in-memory `List<ThreadItem>` without bound, and `mergeHistoryRows` is
  O(rows × thread) — the same quadratic the thread-store overview already records as deferred-by-evidence
  for `appendMessages`. Not exploitable as a hang at the protocol's ~1365-entry page ceiling on a
  background coroutine. Walk depth and paging belong to **#646** (demand side) and **#647** (offline
  cache); named for both rather than pre-empted here.
- **[Threat model] OUT OF SCOPE — the live lane's boundary rows are still undeduped.** A hostile daemon can
  send two `session_transition` frames sharing a `(previous, new)` pair with differing `occurred_at`;
  `appendSessionBoundary` appends both and `ThreadScreen` then holds two rows with one `LazyColumn` key.
  Pre-existing, reachable today without this ticket, and fixing it means editing `appendSessionBoundary`
  or the renderer's key — production code outside this ticket's scope. Filed as **#775**
  (*bug: duplicate session-boundary rows crash the thread LazyColumn*), on the board in Inbox.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

## Revisions

### 2026-09-22 — implementation

The design landed as planned; three things are worth recording because they are not visible in the diff.

- **`RemoteConversationRepository`'s companion object widened from `private` to `internal`.** Not in the
  plan. The eight wire-type constants the reduction dispatches on live in that companion, and the
  alternative was re-spelling eight protocol strings in a second file. The constants are protocol
  vocabulary, not state, so widening them grants no new mutation. Recorded here rather than left as an
  unexplained diff hunk.
- **The four turn-scoped arms share one `decodeLiveEvent` + `when` over the sealed `LiveSessionEvent`,**
  rather than four per-type arms each casting its mapper's declared supertype. Same behaviour, no
  unchecked casts, and it mirrors the repository's own `decodeLiveSessionEvent` shape.
- **Open question 1 is resolved as planned — skip, do not update.** One consequence is worth stating
  because a reader will ask: a page holding a `tool_result` for a tool row the *thread* already holds as
  `Running` leaves that row `Running`, because the reduction folds against an empty accumulator and only
  then merges. That is correct for the one window it can occur in — the ask-versus-answer race, where the
  live lane still owns and will deliver that result — and updating in place would break AC #1's
  order guarantee. Open question 2 is unchanged and still belongs to #646.

**Sizing, measured rather than estimated.** § Sizing forecast ~880 lines of total written work against a
ceiling of 800. The actual is **~1347** — 1026 added lines of code and tests plus a 321-line plan. The
underestimate is entirely KDoc and test density: the reducer came in at 403 lines against a forecast of
~240 and its test at 410 against ~280, both matching this repository's house style of carrying the
rationale in KDoc rather than in a commit message. The split analysis in § Sizing is unchanged by the
larger number — every candidate slice still produces a one-consumer child, so the floor rule still
governs. What the miss does say is that a forecast drawn from a file-count sketch systematically
undercounts in a codebase with this documentation density; a future estimate for work in this area should
scale from #623's measured 914 lines rather than from a per-file guess.

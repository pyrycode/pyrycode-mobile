# #781 — Carry the queue item's `message_id` and drop its undelivered echo

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/InteractivePayloads.kt` → `QueuedMessageDto`,
  `QueueStatePayloadDto`, `QueueStatePayloadDto.toQueue` — the strict-decode boundary the new wire field
  joins, and the one documented nullable latitude (`queued`) it must not widen.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `QueuedMessage`,
  `ConversationRepository.dropQueuedMessage`, `ThreadItem.MessageItem` — the domain element the field lands
  on, the contract whose "no projection side effect" clause this ticket changes, and the thread row shape
  the echo removal filters.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `sendMessage`
  (mints the client `message_id` and appends the echo), `dropQueuedMessage` (the `dequeue_message` send),
  the `TYPE_QUEUE_STATE` arm and `decodeQueueState`, `queuedByConversation`, `threadByConversation`,
  `appendMessages`, `recordLastMessage` — every seam this slice touches lives in this one class.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` →
  `dropQueuedMessage` — the facade the thread ViewModel actually calls; confirms no signature change is
  needed there under the design below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `onDropQueued` — the
  caller; confirms it passes only the `Long` and holds no thread list of its own to mutate.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedBacklog.kt` → `QueuedBacklog`,
  `previewQueue` — the positional `QueuedMessage(...)` preview literals that decide where the new domain
  field goes in the constructor.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`'s
  `onDropQueued` parameter and its preview `QueuedMessage(...)` literals — the same constraint.
- `docs/knowledge/features/queued-backlog.md` — the #460/#466/#467 lessons: full-snapshot replace, the
  `queued_msg_id` `uint64`→`Long` trap (pyrycode#720), the deliberate no-optimistic-removal ruling on the
  *backlog row*, and the "nothing on this path logs the payload" discipline this slice inherits.
- `docs/knowledge/features/conversation-repository.md` — the interface-default cascade-escape convention
  that keeps test doubles free of new overrides.
- `../pyrycode/docs/protocol-mobile.md` § Queue (v2) — the wire SSOT for `message_id`: relayed verbatim,
  legally `""`, addresses nothing, and the multi-device rule (merge only against echoes you minted).
- `pyrycode-desktop` `src/renderer/src/screens/conversation/dropQueuedMessage.ts` — the sibling client's
  #1213 posture: the echo removal is gated on the drop actually going, the empty-id rule lives at the
  single producer of the removal, and a backlog **diff** is explicitly rejected as the trigger.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=16-8

The conversation thread: a back-arrow app bar over a scrolling column of alternating message bubbles
(`surfaceVariant`-toned inbound, `primary`-toned outbound, each with a trailing timestamp and copy glyph),
a status strip carrying the thinking indicator and a pairing-error chip, then the attachment strip and the
composer. The frame draws **no backlog treatment**, so this slice reproduces nothing new: it removes rows
and adds none, the queued row keeps the existing message-row idiom, and #782 is where the visual changes.

## Context

`RemoteConversationRepository.sendMessage` mints a client `message_id`, sends `send_message`, and on the ack
appends a user `Message` carrying that id to the thread projection — the phone's only record of its own
send, because interactive mode streams no user-message event back. When claude is busy the daemon parks the
message and pushes a `queue_state` snapshot that `QueuedBacklog` draws as a second row. Dropping the queued
entry today removes only the backlog row; the echo stays, reading as a message claude received when it
never did.

pyrycode#2092 put the client's own `message_id` on every `queue_state` item, verbatim. This slice carries it
through the decode to `QueuedMessage` and spends it on the drop path. No ADR is warranted — the correlation
rule it implements is already stated in `protocol-mobile.md` § Queue (v2), and this is the client half of a
contract decided upstream.

## Design

Three production files. **No UI and no ViewModel change**, which is the load-bearing decision below.

### 1. The wire field — `InteractivePayloads.kt`

```kotlin
internal data class QueuedMessageDto(
    @SerialName("queued_msg_id") val queuedMsgId: Long,
    @SerialName("message_id") val messageId: String,   // new, strict-required
    val text: String,
    val ts: String,
)
```

`toQueue()` copies it through verbatim — no `trim()`, no case fold, no re-encode (AC #1). Strict-required
matches every sibling field and the wire contract ("always present", non-omitempty); `""` is a legal
*value*, not an absent field, so an item carrying `""` decodes normally and the snapshot stands (AC #1).
The cost is stated in Edge cases below.

### 2. The domain field — `ConversationRepository.kt`

```kotlin
data class QueuedMessage(
    val id: Long,
    val text: String,
    val timestamp: Instant,
    val messageId: String = "",   // new, last, defaulted
)
```

Last position with a default `""`, so the positional `QueuedMessage(1L, "…", t0)` literals in
`QueuedBacklog`'s `previewQueue` and `ThreadScreen`'s previews stay untouched and no test double gains a
field. `""` is the domain value that means "correlates with nothing" — the same value the wire uses.

`dropQueuedMessage`'s KDoc loses its "no projection side effect" clause and gains the echo-removal
contract; the signature does not change.

### 3. The correlation — `RemoteConversationRepository.kt`

**The message id never crosses the UI.** `dropQueuedMessage(conversationId, queuedMessageId)` resolves the
item's `messageId` from the repository's **own** `queuedByConversation` snapshot, by the `queued_msg_id` the
caller already passes. That keeps `QueuedBacklog.onDrop`, `ThreadScreen.onDropQueued`, `MainActivity`'s
wiring, `ThreadViewModel.onDropQueued` and the `StableConversationRepository` facade byte-identical — a
four-file signature cascade avoided — and it puts the correlation in the one layer that owns both the queue
snapshot and the thread projection. `queued_msg_id` is the daemon-authoritative per-conversation key that
already addresses the row; `message_id` is a data-layer correlation detail with no business above it.

**A minted-id ledger enforces the multi-device rule.** A new connection-scoped
`MutableStateFlow<Map<String, Set<String>>>` (`conversationId -> the message ids this device minted and
echoed) is written by `sendMessage` after its ack, beside `appendMessages`. The drop removes a thread row
**only** when the resolved `messageId` is non-empty *and* a member of that conversation's ledger set. The
thread projection is not a valid correlation store on its own: it also holds rows folded from history
pages (#623/#778), which can carry ids minted by other devices, and `message_id` is client-chosen with
uniqueness enforced nowhere. Matching on the projection alone would let another device's colliding id
delete a row this phone did not send — exactly what § Queue (v2)'s multi-device rule forbids. The ledger is
that rule expressed in code.

Drop sequence, in order, and the order is the design:

1. Resolve `messageId` from `queuedByConversation[conversationId]` by `queuedMessageId` — **before** the
   send. A successful drop provokes a fresh `queue_state` that removes the item, so reading afterwards
   races the inbound collector and would usually find nothing.
2. Send `dequeue_message` and await the correlated reply, unchanged.
3. **Only on the ack**, if the resolved id is non-empty and in the ledger: filter the matching
   `ThreadItem.MessageItem` out of `threadByConversation[conversationId]` in one atomic `update`, and drop
   the id from the ledger (a consumed echo cannot be dropped twice). Every other row keeps its position
   (AC #2) — a `filterNot`, not a rebuild.

A throw from step 2 skips step 3 entirely, so a failed drop leaves the entry and the echo in place (AC #3).
An unresolved item, an empty `messageId`, or an id no ledger holds all leave step 3 a no-op: the send still
goes and no row is touched (AC #4). **Text is never compared** at any step.

Contract sketches:

```kotlin
// resolve the item's correlation key from this connection's own snapshot; "" when nothing matches
private fun queuedMessageIdFor(conversationId: String, queuedMessageId: Long): String
// filter one locally-minted echo out of a conversation's thread; no-op unless the ledger holds the id
private fun removeOwnEcho(conversationId: String, messageId: String)
```

### Edge cases and limitations

- **A daemon older than pyrycode#2092 sends no `message_id`.** Under strict-required its whole `queue_state`
  fails the structural decode and the backlog view goes empty rather than degrading to "no correlation".
  Accepted per the ticket's Technical Notes and because the daemon audit baseline (`43a52426`) already
  carries #2092; recorded here so the trade is on the record rather than discovered later. A wrong-*typed*
  `message_id` (a number, an object) drops the snapshot under either posture.
- **The channel-list preview is out of scope.** `sendMessage` also writes `lastMessages`, which drives the
  conversation-row preview; a dropped echo stays visible there. Reverting it needs a previous value the
  projection does not retain, and re-deriving one from a possibly-partial thread would regress the preview
  to an older or absent message. The AC names thread rows only. Carried to the PR's Lessons learned.
- **The backlog row itself keeps #467's non-optimistic ruling** — it leaves on the next `queue_state`, which
  the daemon publishes after a successful `dequeue_message`. Only the echo, which the daemon never authored,
  is removed locally. A backlog **diff** is explicitly *not* the trigger: a backlog also shrinks when it
  drains, and a diff-driven removal would delete the echo of every message that ran normally.
- **Ledger growth** is one id per successful send per connection, minus every consumed drop, and dies with
  the connection-scoped repository (#351) — the memory posture `threadByConversation` already accepts.

## State + concurrency model

One new field in the connection-scoped repository, alongside `queuedByConversation` and
`threadByConversation`. Two writers — `sendMessage` (adds after its ack) and `dropQueuedMessage` (removes
after its ack) — both through an atomic `MutableStateFlow.update`, the same posture `appendMessages`
already relies on for its two writers. No new coroutine, no new scope, no new dispatcher: both writes
happen on the caller's already-suspending path, and the inbound collector keeps sole ownership of
`queuedByConversation`. Nothing observes the ledger, so it publishes no flow and cannot leak across
screens. Cancellation between steps 2 and 3 leaves the ledger holding an id whose echo survived — the
same visible state as a failed drop, and inert.

## Error handling

Unchanged at every layer. `dropQueuedMessage` still throws `IllegalArgumentException` on
`conversation.not_found`, `RelayErrorException` on any other server error (a stale or already-drained id
surfaces generically), and `IllegalStateException` when not connected; `ThreadViewModel.onDropQueued` still
swallows all three inert with no user-visible surface, `CancellationException` arm first. The new step adds
no throw of its own: it is a pure fold over state already in hand. A malformed `queue_state` still drops
the one envelope and the inbound collector survives.

## Testing strategy

Unit-tier, in `RemoteConversationRepositoryTest` beside the existing #460/#466 blocks — the behaviour is
entirely repository-owned.

- `message_id` reaches `QueuedMessage.messageId` byte-for-byte, including a mixed-case, padded value (AC #1).
- A snapshot whose items carry `""` decodes and surfaces the backlog (AC #1).
- An item missing `message_id` drops the snapshot — pins the strict-required decision.
- Send → queue snapshot carrying the minted id → drop acked: the echo row is gone and every other row is
  present and in order (AC #2).
- A server `error`, and a not-connected send, each leave the entry and the echo in place (AC #3).
- An item whose `message_id` is `""` removes no row (AC #4).
- An item carrying an id this device never minted removes no row **even when a thread row carries that very
  id** and the texts are identical — the multi-device rule and the "text never matches" rule in one
  assertion (AC #4).
- A `queuedMessageId` absent from the current snapshot (the drained-between-render-and-tap race) still
  sends and removes no row.
- The existing `dropQueuedMessage_onAck_succeedsWithoutMutatingProjection` stays green on the new path (no
  snapshot ⇒ nothing correlates); its rationale comment is updated rather than left stale.

Fixture carry: ~10 one-line `queue_state` JSON sites gain `"message_id"` — the `queueStateEnvelope` helper
in `RemoteConversationRepositoryTest` (widened to carry the id per item), 6 inline payload strings there,
and 2 in `RelayConnectionFactoryTest`.

**No new Compose test, deliberately.** This slice changes no composable, no `@Preview` and no ViewModel
branch; a Compose test would drive a test double's removal behaviour and assert Compose re-rendered a
shorter list. The existing `QueuedBacklogTest` is the affordance's proof and must stay green — it runs as
the focused device check (§ B2) so the domain field's default is shown not to have disturbed the render.
No rung-3 scenario: this is a data-layer slice with no operator-facing flow of its own, and the live
cross-client drop is #673's acceptance.

## Documentation handoff

Pending the documentation stage — not written by this ticket:

- `docs/knowledge/features/queued-backlog.md` — fold the correlation key and the drop's echo removal into
  the sections that already describe the queue snapshot and the drop path (§ "The signal", § "Dropping a
  queued entry", § "Edge cases & limitations", § "Security").
- `docs/knowledge/features/conversation-repository.md` — the same, in the section describing the drop path.

## Open questions

1. **Does the echo removal belong to the repository projection or the thread fold?** Resolved in Design:
   the repository, because it is the only layer holding both the queue snapshot and the thread projection,
   and because the ViewModel maps an upstream flow it cannot filter without a local suppression set.
2. **Strict-required or defaulted `message_id` on the DTO?** Resolved to strict-required per the ticket's
   Technical Notes, with the pre-#2092-daemon consequence recorded under Edge cases.
3. **Should the drop also revert `lastMessages`?** Resolved to no — out of scope, rationale under Edge
   cases, carried to the PR.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No new boundary. `message_id` crosses untrusted→trusted at exactly one place,
  `decodeQueueState`'s `MobileJson.decodeFromJsonElement<QueueStatePayloadDto>`, which is already the sole
  decode seam for this arm; a wrong-typed value fails the structural decode and drops the one envelope.
  The value is unbounded in length on the wire, but it is held only inside the replaced-per-snapshot
  `queuedByConversation` entry — the same bounded-by-replacement posture `text` already has, and the
  transport's frame cap sits above both. Note that the **ledger never holds a wire-supplied id**: it holds
  only ids this device minted, so no wire input can grow it.
- **[Trust boundaries]** SHOULD FIX — the type system cannot tell `QueuedMessage.messageId` apart from a
  trusted string, and `QueuedMessage` is a **public** domain type whose next consumer is #782, the ticket
  that folds queued rows into the thread list. Two items may legally carry the same `message_id`, so a
  consumer that keys a `LazyColumn` on it crashes the thread on duplicate keys — the exact failure the
  `ThreadItem.UnrecognizedMessage.id` KDoc already warns about for a different field. Phase B must carry
  the constraint as a KDoc contract on the new field and on `QueuedMessageDto`: **compared for equality
  only; never rendered, never a list key, never logged.**
- **[Tokens, secrets, credentials]** No findings, and deliberately no constant-time compare. `message_id`
  guards no authority: it addresses nothing on the wire (`dequeue_message` still resolves
  `conversation_id` + `queued_msg_id`), authorizes nothing, and is not a nonce. A timing side channel on
  the comparison would leak ids to an observer that already has them — the phone sends every one of them
  to the daemon on `send_message`. `MessageDigest.isEqual` here would be cargo cult.
- **[Cryptographic primitives]** A property that was previously decorative is now load-bearing: the design
  assumes another device cannot **guess** an id this phone minted. `sendMessage`'s existing
  `UUID.randomUUID()` is a JVM CSPRNG v4 UUID (122 bits), which meets it; this slice must not weaken the
  mint to something derived, sequential or time-based. No new primitive, no hand-rolled anything, no
  change to the Noise path.
- **[Threat model — hostile daemon]** OUT OF SCOPE, accepted. A paired daemon knows every `message_id`
  this phone has sent, so it can forge a `queue_state` item carrying one; if the operator then drops that
  item, the phone deletes its own echo for a *different* message. Impact is bounded to one locally-drawn
  row on one device — no data is lost (the daemon holds the transcript) and nothing is disclosed. The
  actor required is the paired daemon, which ADR 025 already places inside the trust domain and which can
  fabricate arbitrary thread content by simpler means, so this is not a widening. The ledger still buys
  the case that matters: another **paired device**, which does not know this phone's minted ids, cannot
  make it delete a row.
- **[Network & I/O]** No findings. No new frame, socket, timeout or TLS decision; `message_id` is
  deliberately **not** added to the outbound `dequeue_message` payload. Against an on-path relay that can
  drop, delay or reorder: a lost ack means no removal (fails safe), a late ack means a late removal
  (inert), and a reordered/stale `queue_state` is safe to read because `queued_msg_id` is a monotonic
  per-conversation counter that is never recycled — item *N* in a stale snapshot is the same item *N*.
  That no-recycle property is what makes the read-before-send in step 1 race-safe rather than merely
  convenient.
- **[Error messages, logs, telemetry]** No findings, stated as an implementation obligation because the
  tempting place to break it is new: the no-correlation branch is exactly where a developer reaches for a
  debug log. The plan adds **no** log call. `message_id`, `text` and the payload are all MUST-NOT-log, and
  no new value reaches a user-visible error surface — the drop stays inert on failure.
- **[Concurrency]** No findings, but the shape deserves naming: step 1 reads shared state, step 2
  suspends, step 3 mutates. It is safe because the captured `messageId` is an immutable `String` taken
  before the suspension and step 3 is an atomic `update` whose lambda is a pure filter, so it cannot lose
  a concurrent write. A concurrent `sendMessage` cannot be hit by the filter (its echo carries a fresh
  UUID). Consuming the id from the ledger on use is what makes the legal **duplicate-`message_id`** case
  behave: dropping the first item removes the echo, dropping a second item carrying the same id finds the
  ledger already consumed and removes nothing, instead of deleting an unrelated row.
- **[Concurrency — bounded limitation, not a finding]** A later history page (#623/#778) that contains the
  dropped message would re-insert the row through `withMessage`. The daemon never ran the message, so its
  log should not contain it; if it does, the row returning is the daemon's own truth reasserting itself
  rather than a local lie.
- **[File / storage operations]** Not applicable: nothing on this path touches disk. The ledger and both
  projections are in-memory and connection-scoped (#351), so nothing is persisted, nothing joins
  auto-backup, and no path is ever built from untrusted input.
- **[Inter-process / Android attack surface]** Not applicable: no exported component, intent filter, deep
  link, `PendingIntent`, push path, content provider or WebView is added or touched. The phone's own UI
  layer is not modified at all by this slice.
- **[Threat model — UI leakage]** Not applicable: no new render. #461 settled the backlog's
  screen-capture posture (the same user-content class the thread already draws without `FLAG_SECURE`), and
  this slice only removes rows.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

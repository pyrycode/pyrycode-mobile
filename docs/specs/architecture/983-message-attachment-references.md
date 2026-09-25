# #983 — Attachment references on thread messages

## Files read

- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt` → `Message`, `ToolCall` — the model gains a defaulted field, the way `ToolCall` defaults its later additions.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt` → `MessageCommands.sendMessage` (three-argument form) — builds the confirmed-insert `Message` after the `ack`; the only place a sent row is created.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ConversationRepository.sendMessage` (three-argument form), `AttachmentOffer`, `observeAttachmentOffers` — the send contract and the offer type.
- `app/src/main/java/de/pyryco/mobile/data/repository/StableConversationRepository.kt` → its `sendMessage` override — pure forward to the live repository.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → its `sendMessage` override, the `TYPE_ATTACHMENT_OFFERED` arm of `onInbound`, the `attachmentOfferProjection` field.
- `app/src/main/java/de/pyryco/mobile/data/repository/AttachmentOfferProjection.kt` → `AttachmentOfferProjection.apply`, `decodeOffer` — first-offer-wins per connection; where an offer becomes known.
- `app/src/main/java/de/pyryco/mobile/data/repository/ThreadProjection.kt` → `ThreadProjection.appendMessages` — id-deduped append (`withMessage`), reused unchanged for offer rows.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `withHistoryEntry` (`TYPE_SEND_MESSAGE` arm), `mergeHistoryRows`, `alreadyHolds` — history reduction and the join used both by the history walk and by the cache merge.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` → `observeMessages` — `live.mergeHistoryRows(base)`: a live row wins over its cached twin, which is where a sent row's names would be lost after a reconnect (see Design § Reconciliation).
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `CachedMessage`, `ThreadItem.toRecord`, `CachedThreadRow.toDomain` — the persisted record; `ignoreUnknownKeys` + defaults keep old documents readable.
- `app/src/main/java/de/pyryco/mobile/data/network/AttachmentPayloads.kt` → `isAttachmentIdShape`, `attachmentDisplayName` — the id shape check and the name sanitiser, reused as they are.
- `app/src/main/java/de/pyryco/mobile/data/network/MessagePayload.kt` → `SendMessagePayloadDto.attachmentIds`, `MessageAttachmentIds.MAX` / `forSend`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `sendWithAttachments`, `upload` — holds each `PendingAttachment`'s `displayName` and `mimeType` at send time.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadFold.kt` → `ThreadFold.reduce` — drops the synthetic streaming row when a new assistant id appears in the finished list; checked against offer rows (see Design § Offer rows).
- `../pyrycode/docs/protocol-mobile.md` § Naming a message's attachments, § A history entry, § `attachment_offered` — wire SSOT; not restated.

In-flight overlap check: no open `feature/*` branch touches these files.

## Design source

N/A — data-layer ticket; #984 renders the references.

## Context

The thread model has no notion of which files belong to a message, so a sent attachment, a history replay of it and a file claude offered all vanish from the thread (offers survive only as long as the connection). This ticket adds references to `Message` and carries them through the live send, the history reduction, offer arrival and the disk cache. No ADR needed.

**Size overage, stated.** The plan modifies 9 production files, one over the 8-file line (the other five limits hold; ~700 lines of written work expected). A split was considered: {model + send path + cache} and {history + offer rows}. The second slice's only consumer is #984, a sibling in the #672 family, so by the floor rule it merges back; floor wins over ceiling, so this is built as one ticket. Two of the nine files (`StableConversationRepository`, `ConversationRepository`) change only because the send signature's element type changes.

## Design

### Model (`Message.kt`)

```kotlin
data class MessageAttachment(
    val attachmentId: String,
    val displayName: String? = null,
    val mimeType: String? = null,
) // toString names only attachmentId

data class Message(..., val attachments: List<MessageAttachment> = emptyList())
```

`null` means "not known" (a history reference); `""` is a known-but-empty name (an offer whose name cleaned to nothing). KDoc carries the untrusted-hint rule: display as inert text only, never a path, key, handler choice or log field. `attachmentId` is always a validated lowercase UUIDv4 when it comes from the wire; on the send path it is the id the daemon's `attachment_stored` returned.

### Send path

- `ConversationRepository.sendMessage(conversationId, text, attachments: List<MessageAttachment>)` replaces the `attachmentIds: List<String>` overload (same arity; JVM erasure forbids both). `StableConversationRepository` and `RemoteConversationRepository` forward.
- `MessageCommands.sendMessage` derives the wire ids from `attachments.map { it.attachmentId }` through `MessageAttachmentIds.forSend` exactly as today, and the confirmed-insert `Message` carries `attachments.distinctBy { it.attachmentId }` in caller order, each name and MIME passed through `attachmentDisplayName` (the local name comes from a document provider, which any installed app can author). The two-argument send delegates with `emptyList()` as today.
- `ThreadViewModel.sendWithAttachments` builds `MessageAttachment(id, entry.displayName, entry.mimeType)` per entry, in order, instead of a bare id list. Nothing else in the VM changes.

### History (`HistoryPageReducer.kt`)

The `TYPE_SEND_MESSAGE` arm sets `attachments = dto.attachmentIds.orEmpty().filter(::isAttachmentIdShape).distinct().take(MessageAttachmentIds.MAX).map { MessageAttachment(it) }`. A null/empty list yields `emptyList()`, so a text-only entry reduces exactly as today; an empty `text` with ids still produces the row (it already does).

### Reconciliation (`mergeHistoryRows`)

The join stays `message_id` and a duplicate is still skipped, not moved. New: when a kept `MessageItem` has a reference with a `null` name or MIME and the skipped twin has the same message id and a reference with the same attachment id carrying one, the kept row adopts it in place (position unchanged; returns `this` when nothing changes). Both merge directions need this rule to be symmetric:

- History walk (`existing.mergeHistoryRows(page)`): the local echo carries names, the page row none — no change.
- Cache merge (`live.mergeHistoryRows(cached)`) after a reconnect: the live row came from history without names, the cached echo has them — the drawn row takes the cached names, and the cache is rewritten with them.

### Offer rows

`AttachmentOfferProjection` takes the connection's `ThreadProjection` as a constructor parameter. When `apply` records a **first-seen** attachment id for a conversation, it also calls `threadProjection.appendMessages(listOf(conversationId to attachmentOfferRow(offer, Clock.System.now())))`. A repeat offer on the same connection adds nothing.

`attachmentOfferRow` (internal, same file) builds `Message(id = "attachment-offer-<attachmentId>", sessionId = "", role = Assistant, content = "", timestamp, isStreaming = false, attachments = listOf(MessageAttachment(attachmentId, displayName)))`. Stable identity: the id is derived from the validated attachment id, so a repeat on another connection, and the cached copy of the row, join on `message_id` through `withMessage` / `mergeHistoryRows` rather than duplicating. The prefix keeps the row's id out of the UUID `message_id` / `turn_id` / `tool_use_id` namespace. Arrival order: an end-append, like every other live row.

`ThreadFold` check: an offer row is a new assistant id in the finished list, so it would end a synthetic streaming turn. The projection's own streaming row (`withAssistantDelta`) already ends it after a turn's first delta, so the effect is limited to the moment between the fold seeing a delta and the projection emitting it, and the projection's row then takes over. Accepted, no change.

Until #984, an offer row and an attachment-only sent message render as an empty bubble.

### Cache (`FileConversationCache.kt`)

`CachedMessage` gains `attachments: List<CachedAttachment> = emptyList()`, `CachedAttachment(attachmentId: String, displayName: String? = null, mimeType: String? = null)`. Mapped both ways in `toRecord` / `toDomain`. The document `VERSION` stays 1: an old document has no key and defaults to empty; `explicitNulls = false` omits null hints. Offer rows are settled `MessageItem`s, so `cacheableThreadRows` keeps them with no change.

## State + concurrency model

No new jobs, flows or scopes. `AttachmentOfferProjection.apply` runs on the single inbound collector, as today; the first-seen flag is computed inside the `update` lambda (reassigned on every retry) and the thread append follows the update. `ThreadProjection.appendMessages` is already atomic.

## Error handling

No new failure paths. A non-conforming history id is dropped silently (the reducer never logs); the rest of the entry is kept. A malformed offer is still dropped before any row is built. A cache document with a malformed `attachments` value fails the thread decode as any malformed row does today (`invalid_data`, empty thread).

## Testing strategy

Unit tests only (`testDebugUnitTest`), all data-layer or ViewModel:

- `RemoteConversationRepositoryAttachmentTest`: a send with two references puts one user row in the thread carrying both, in order, with name and MIME; a name with a bidi override arrives cleaned; a repeated id yields one reference.
- `ThreadViewModelAttachmentTest`: the fake records `List<MessageAttachment>`; a send passes id, display name and MIME per pending entry in order.
- `HistoryPageReducerTest`: ids in wire order with null hints; empty text + ids → a row; a bad-shape id dropped, the rest kept; text-only entry unchanged (`attachments` empty); `mergeHistoryRows` fills missing hints from a skipped twin in both directions and leaves position and unrelated rows alone.
- `RemoteConversationRepositoryAttachmentOfferTest`: offers become assistant rows in arrival order with sanitised names; a repeat adds no row; another conversation's thread is untouched.
- `FileConversationCacheThreadTest`: sent, history (null hints), offer and attachment-only rows round-trip unchanged; a pre-change document (no `attachments` key) still loads.
- Existing `StableConversationRepositoryTest`, `ThreadViewModelEffortRecallTest`, `ThreadViewModelAppliedEffortTest` doubles update their override signature only.

No rung-3 scenario: this is a data-layer ticket with no operator-visible flow of its own; #984's rendering is where one belongs.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the thread/cache feature overviews may want the new `Message.attachments` field and the offer-row identity (`attachment-offer-<id>`).

## Open questions

- None blocking. If `attachmentDisplayName` on the MIME hint proves surprising in review, it can be `truncateUtf8` only.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — three entry points, each explicit: history ids pass `isAttachmentIdShape` in the `TYPE_SEND_MESSAGE` arm of `withHistoryEntry`; offer ids and names pass `decodeOffer` (`isAttachmentIdShape` + `attachmentDisplayName`) before `attachmentOfferRow` runs; sent names and MIME from the document provider pass `attachmentDisplayName` in `MessageCommands.sendMessage`. Downstream code holds `MessageAttachment` values whose KDoc states the inert-text rule.
- [Trust boundaries] SHOULD FIX (applied in plan) — a hostile daemon could replay a stored `send_message` with thousands of ids; the reducer bounds the list at `MessageAttachmentIds.MAX` and de-duplicates, so #984 cannot be handed an unbounded or key-colliding list.
- [Trust boundaries] No findings — `mergeHistoryRows` only fills a `null` hint from a twin with the same message id and attachment id; it never overwrites a known name, so a replayed history row cannot rename a file the operator sent.
- [Tokens] No findings — no credentials touched.
- [File / storage] No findings — no name, MIME or id reaches a path. The cache path is still the SHA-256 of the conversation id; references are JSON values inside the document under `noBackupFilesDir`, written through the existing temp-file + `ATOMIC_MOVE`. Cached names are the same class of data as cached message text already held there.
- [Inter-process] No findings — no new component, intent or provider. The content URI stays in UI state (#984), not in `data/` or the cache.
- [Crypto] No findings — none used.
- [Network & I/O] No findings — no new frame or verb; the send payload is unchanged (`attachment_ids` only).
- [Logs] No findings — no new log lines. `MessageAttachment.toString` and `CachedAttachment` (file-private) print no name or MIME; the existing send log still records only the id count.
- [Concurrency] No findings — offer rows are written from the single inbound collector through `MutableStateFlow.update`; no new scope.
- [Threat model] OUT OF SCOPE — rendering the hints safely (inert text, no handler chosen from an extension) is #984's.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-24

## Revisions

### 2026-09-24 — offer rows keep their place across a reconnect or restore (verifier rework 1)

**Finding.** Verifier MUST FIX on PR #987: an offer row is the first row only the cache holds, because the daemon never replays `attachment_offered`. `CachingConversationRepository` drew `live.mergeHistoryRows(base)`, which prepends every row the live side lacks. So after a restart or a dropped connection, cache `[m1, a1, offer, a2]` under a page `[m1, a1, a2]` drew `[offer, m1, a1, a2]`, and the cache write made that permanent. That broke AC 3's "in arrival order" on the reload path the ticket names.

**New contract.** The cache merge is its own function, `mergeCachedRows`, in `HistoryPageReducer.kt` beside `mergeHistoryRows`. It uses the same per-kind join (`alreadyHolds`) and the same hint fill (`withAttachmentHintsFrom`). Differs in one way: a cached row the live side does not hold goes directly after the live copy of the nearest row above it in the cache that the live side does hold. It goes in front of everything only when no such row exists. That covers the older rows a newest page does not reach, and a page that does not overlap the cache at all. Several cache-only rows after one anchor keep their cached order. `CachingConversationRepository.observeMessages` calls `mergeCachedRows`; nothing else in it changes.

**Unchanged.** The history walk still uses `mergeHistoryRows` and keeps skip-and-prepend, its answer to the ask-versus-answer race. For the rows the cache path already handled, the result is the same as before. Older rows and a non-overlapping page still go in front, and a merge with an empty live projection still returns the cached rows unchanged. A cached row that the live projection deliberately drops is still drawn from the base as before, now beside its anchor instead of at the top.

**Size.** This adds `CachingConversationRepository.kt` as a tenth production file (a one-call change plus its KDoc). The floor-over-ceiling reasoning in § Context still applies.

**Tests.** New `CachingConversationRepositoryTest` cases cover four things: a cold restore (cache `[m1, a1, offer, a2]`, page `[m1, a1, a2]`, then a new row, drawn and written in order), an empty-then-page reconnect, cache-only rows with no anchor still drawn in front, and the cache path giving a history-reduced sent row its names back.

**Security review, [Logs].** The verifier NIT was right: `CachedAttachment` was a plain data class whose generated `toString` included both hints. It now overrides `toString` to print only the attachment id, the same way `MessageAttachment` does. The [Logs] finding above is accurate as of this revision.

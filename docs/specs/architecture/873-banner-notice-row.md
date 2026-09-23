# #873: Claude's banner notices in the thread, live and on history reload

**Labels:** `enhancement`, `security-sensitive` · **Split from** #654 · **Live verification:** #679

## Files read

- `../pyrycode/docs/protocol-mobile.md` § `banner` and § *Joining a page to the live stream*: the frame contract, the level/text bound rules, the render obligations, and the `(type, ts)` join key. Cited, not restated.
- pyrycode `internal/protocol/interactive.go` → `BannerPayload`: all five fields are declared without `omitempty`, so every one is always present. That is why the DTO can require every field.
- pyrycode-desktop `ConversationScreen.tsx` → `bannerDisplayText` and the `banner` arm of the timeline render. These give the stripping set to mirror (OSC, DCS/SOS/PM/APC strings, CSI, other ESC sequences, C0/C1 controls except tab, LF and CR), the `Claude: ` attribution, and the truncation mark.
- `data/repository/ConversationRepository.kt` → `ThreadItem`, `ThreadItem.UnrecognizedMessage`, `ThreadItem.SessionBoundary`. The new variant goes here, and its identity KDoc follows `SessionBoundary`'s producer-obligation idiom.
- `data/network/InteractivePayloads.kt` → `UnrecognizedMessagePayloadDto` and its `toRow`. This is the strict DTO and pure mapper pattern to mirror.
- `data/network/MobileWireModels.kt` → `Envelope.ts`: a `String`. The live lane parses it with `Instant.parse`, as `HistoryPayloads` already does for a page entry's `ts`.
- `data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_UNRECOGNIZED_MESSAGE` arm, `decodeUnrecognizedMessage`, `appendUnrecognizedMessage`, `appendSessionBoundary`, and the companion's `TYPE_*` constants. This is the live arm pattern: the `interactive` gate, decode-or-drop, and routing by the payload's `conversation_id`.
- `data/repository/HistoryPageReducer.kt` → `withHistoryEntry`, `alreadyHolds`, `holdsBoundary`. These are the history arm and the join predicate shared with the live lane.
- `ui/conversations/thread/ThreadRow.kt` → `ThreadItem.listKey`, the list key. Its uniqueness must follow from the dedup predicate.
- `ui/conversations/thread/ThreadScreen.kt` → the `LazyColumn` render `when`, `ThreadItem.timestamp`, and `mostRecentSessionBoundaryIndex`, which stays unchanged.
- `data/cache/ConversationCache.kt` → `cacheableThreadRows`; `data/cache/FileConversationCache.kt` → `toRecord`. The banner row is never cached.
- `ui/conversations/components/UnrecognizedMessageRow.kt`, `SessionBoundaryDelimiter.kt` → the inert-text security KDoc, `MessageContentGutter`, and the `Session reset` body-small styling.
- `ui/theme/WarningColors.kt` → `ColorScheme.warning`, the existing warning token.
- Tests: `RemoteConversationRepositoryTest` (the `unrecognizedMessage_*` block and the `threadShape` exhaustive `when`), `HistoryPageReducerTest`, `FileConversationCacheThreadTest`, and androidTest `UnrecognizedMessageRowTest`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame has no dedicated notice component. The row borrows the Message area's `Session reset` treatment: a full-width body-small (`M3/body/small`) line inside the shared message gutter, with no bubble fill. A muted notice uses `Schemes/on-surface-variant`. A `warning` row uses the theme's existing `warning` token for the text and a leading `WarningAmber` icon, so the two read differently without relying on colour alone.

## Context

The daemon sends `banner` when claude prints something about the session, such as a hook's reason for blocking a prompt, a local command's output, or a loop notification. Mobile drops the frame on both lanes today, so a blocked prompt looks accepted and then ignored. This ticket lands the row the same way #608 and #609 landed `unrecognized_message`.

**Sizing note.** The plan touches 9 production `.kt` files, one over the 8-file boundary. The refiner flagged the overage. Five of those files take a one-to-three-line arm forced by the sealed `ThreadItem` `when`. The decoder and the renderer are each other's only consumer, so a split would produce a slice with no consumer outside the family. The floor rule wins, and the ticket is built as one.

## Design

### Domain (`ConversationRepository.kt`)

- `ThreadItem.Banner(level: BannerLevel, text: String, truncated: Boolean, occurredAt: Instant)`.
  - `occurredAt` is the envelope's or entry's `ts`, which the daemon mints once per event.
  - **Identity** is `occurredAt`. The protocol's key is `(type, ts)`, and `type` is implied by the variant.
  - **Invariant:** `occurredAt` is unique among a thread's banners. Both writers skip a banner the thread already holds (`holdsBanner`). This is documented in the KDoc and asserted in tests, not enforced at construction, following `SessionBoundary`.
  - `text` is carried **verbatim**, unsanitized. Stripping belongs to the render boundary, following the protocol's direction. Keeping the domain value verbatim means the join never depends on presentation.
- `enum class BannerLevel { Warning, Notice }`. The mapping is closed: `"warning"` becomes `Warning`, and every other value becomes `Notice`, including `info`, `notice`, `suggestion`, empty and unknown. The wire string never reaches the UI, so claude cannot inject a label.
- `stops_turn` is **decoded but not carried** into the row. Nothing on mobile reads it, and leaving it out of the domain type means nothing can accidentally use it to drive a turn.

### Wire (`InteractivePayloads.kt`)

- `@Serializable internal data class BannerPayloadDto(conversationId, level, text, truncated, stopsTurn)`. All five fields are strict-required and have no Kotlin defaults. This matches the Go struct, which declares no `omitempty`.
- `internal fun BannerPayloadDto.toRow(occurredAt: Instant): ThreadItem.Banner`. The mapper is **total**, because an unknown level is not a drop. It stays pure, and the caller supplies the instant.

### Live lane (`RemoteConversationRepository.kt`)

- Companion constant `TYPE_BANNER = "banner"`.
- The `onInbound` arm is gated by `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`. It calls `decodeBanner(envelope)?.let { (id, row) -> appendBanner(id, row) }`.
- `decodeBanner(envelope): Pair<String, ThreadItem.Banner>?` wraps the DTO decode and `Instant.parse(envelope.ts)` in one `try`/`catch (IllegalArgumentException)`. A malformed payload or `ts` drops that one envelope. Routing uses the payload's `conversation_id`.
- `appendBanner(conversationId, row)` does an atomic `threadByConversation.update` and end-appends the row **unless `holdsBanner(row)`**.
- The arm makes **exactly one write**. It sends no `liveSessionEvents` emission and changes no turn, stall, API-retry, compacting, thinking or usage-limit state, whatever `stops_turn` says. Nothing logs it.

### History lane (`HistoryPageReducer.kt`)

- A `TYPE_BANNER` arm in `withHistoryEntry` runs only when `interactive` is true. It decodes the DTO, calls `toRow(occurredAt = entry.timestamp)`, and skips the row if `holdsBanner`. The existing single `try` owns the malformed-entry drop.
- `alreadyHolds` gains `is ThreadItem.Banner -> holdsBanner(row)`.
- `internal fun List<ThreadItem>.holdsBanner(banner: ThreadItem.Banner): Boolean` matches on `occurredAt`. The history merge, the live lane's `appendBanner` and the list key read this one identity.

### Render (`ThreadRow.kt`, `ThreadScreen.kt`, new `components/BannerNoticeRow.kt`)

- `listKey`: `is ThreadItem.Banner -> "banner:$occurredAt"`. The key is unique because of `holdsBanner`.
- `ThreadScreen`: the render arm calls `BannerNoticeRow(item)`, and `timestamp()` returns `occurredAt`. `mostRecentSessionBoundaryIndex` is unchanged.
- `internal fun bannerDisplayText(text: String): String` is a pure function that mirrors desktop's `bannerDisplayText` stripping set, without its prefix or suffix. It removes:
  - OSC strings and DCS, SOS, PM and APC strings, whether terminated or unterminated
  - CSI sequences and other two-byte ESC sequences
  - C0 controls except `\t`, `\n` and `\r`
  - DEL and C1 controls
- `BannerNoticeRow(item, modifier)` is stateless and not clickable. It draws a single plain `Text` whose `AnnotatedString` has three parts:
  - the client-owned string `thread_banner_attribution` ("Claude: "), set in medium weight
  - `bannerDisplayText(item.text)`
  - when `truncated`, the client-owned `thread_banner_truncated` string, set in italics
  The row has no `SelectionContainer`, no markdown, no link detection and no second length cap. `Warning` adds a leading `WarningAmber` icon with the content description `cd_thread_banner_warning` and colours the icon and text with `colorScheme.warning`. `Notice` uses `onSurfaceVariant` and has no icon. Both use `bodySmall` and sit inside the `MessageContentGutter` with the same bottom spacing as their neighbours. Previews cover warning, notice and truncated rows, in light and dark.

### Cache (`ConversationCache.kt`, `FileConversationCache.kt`)

- `cacheableThreadRows` also filters out `ThreadItem.Banner`. `toRecord` gains an arm that throws `IllegalStateException("banner rows are never cached")`, following the unrecognized-row arm. `settledThreadRows` keeps banner rows, so a thread that has lost its connection still draws them.

## State + concurrency model

There is no new job, flow or scope. Both writes go through the existing `threadByConversation` `MutableStateFlow.update` compare-and-swap on the single inbound collector, and the history reduction stays pure. The dedup check runs inside the `update` lambda, so a concurrent merge cannot interleave a duplicate between the check and the write.

## Error handling

- A malformed payload, a missing or wrong-typed field, or a malformed `ts` drops that one frame or entry, silently. The collector and the rest of the page survive.
- An unknown or empty `level` is not an error; it maps to `Notice`.
- Without `interactive`, nothing is decoded on either lane.

## Testing strategy

JVM unit tests use `runTest`, `FakeSessionPump` and the existing helpers.

- `RemoteConversationRepositoryTest`, in a new `banner_*` block:
  - A banner folds one row with its level mapped, its text verbatim and its `truncated` value. `occurredAt` equals the parsed envelope `ts`.
  - `warning` maps to `Warning`. `info`, empty and an unknown level map to `Notice`.
  - The row interleaves with messages in arrival order and routes by `conversation_id`.
  - The same `ts` pushed twice produces one row. Different `ts` values produce two rows.
  - A `stops_turn: true` banner leaves an existing stall in place, emits no live event, and leaves API-retry and compacting state unchanged.
  - A malformed payload, missing `stops_turn` or a wrong-typed field is dropped, and a later frame still folds. A malformed `ts` is dropped the same way.
  - With the gate closed or set to an unrelated capability, nothing folds.
  - The `threadShape` helper gains a `Banner` arm.
- `HistoryPageReducerTest`:
  - A stored banner reduces to the same row, with `occurredAt` equal to the entry timestamp.
  - A non-interactive reduction yields nothing.
  - A malformed entry costs only that entry.
  - Merging a page whose banner the live thread already holds leaves one row.
  - A repeated `ts` within one page yields one row.
- `BannerDisplayTextTest` is a new JVM test covering CSI, OSC with BEL or ST terminators, unterminated OSC, DCS, C0 and C1 controls, and DEL. It also checks that tab and newline survive and that plain text passes through unchanged.
- `FileConversationCacheThreadTest`: `cacheableThreadRows` drops banner rows.
- Compose UI test `BannerNoticeRowTest` in androidTest checks:
  - The row shows `Claude: ` and the sanitized text, with escape bytes gone.
  - A truncated row shows the truncation mark and an untruncated one does not.
  - A warning row exposes the warning icon's content description, while a notice row does not.
  - The row has no click action.
- Rungs 3 and 4: live verification against real claude belongs to #679, as the ticket states. No scripted fakeclaude emitter for `banner` exists, so this ticket adds no rung-4 twin.

## Open questions

- Should the truncation mark be desktop's bare `…` or a word? **Resolved:** use a client-owned, italic ` (truncated)`. Claude can type `…` itself, but it cannot change a span's style, so the italic mark is harder to forge.

## Documentation handoff

- Pending for the documentation stage: fold the banner row, its `(type, ts)` identity and its never-cached rule into the owning feature overviews (`unrecognized-message-row.md`'s neighbour, `remote-conversation-repository-live-stream-and-modals.md`, `conversation-cache.md`), and consider a new `banner-notice-row.md` overview. The ticket names no other docs.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. The single decode boundary is `decodeBanner` on the live lane and the `TYPE_BANNER` arm of `withHistoryEntry` on the history lane. Both go through the strict `BannerPayloadDto`, and downstream code holds only `ThreadItem.Banner`. `level` is narrowed to the client-owned `BannerLevel`, so no daemon string reaches a label. `text` crosses verbatim and is made inert at the one render sink, `BannerNoticeRow`, through `bannerDisplayText`. It lands in a plain `Text` with no markdown, no link detection, no selection, no click, and never in a URL, filename, cache key or log. It is bounded by the daemon at 4 KiB and again by the frame cap. The ticket forbids a second cap.
- [Trust boundaries] SHOULD FIX. The realistic abuse the protocol names is text impersonating daemon chrome at `warning`. The mitigation is the client-owned `Claude: ` attribution span in a distinct weight, which claude cannot restyle, and the row's styling, which differs from the connection banner, status line and session delimiter. Phase B must keep the attribution a separate styled span rather than folding it into the text.
- [Trust boundaries] OUT OF SCOPE. Unicode bidi override and format characters (U+202A–U+202E, U+2066–U+2069) are not stripped. Desktop does not strip them either, and they can only reorder glyphs inside a row whose attribution span is client-owned. Follow up with a new ticket only if abuse is observed.
- [Tokens] No findings. The frame carries no credential, and the plan creates or stores none.
- [File / storage] No findings. The row is excluded from `cacheableThreadRows`, and `toRecord` throws if it ever gets a banner row, so claude-authored prose never reaches app-private disk. History replay restores the row.
- [Android surface] No findings. The plan adds no intent, deep link, WebView, provider or `PendingIntent`. The row is not clickable and not selectable, so it has no clipboard path.
- [Crypto] No findings. The plan uses no randomness and no primitive. The row id is the daemon's `ts`, which is compared only for equality, within one thread.
- [Network & I/O] No findings. The plan adds no outbound frame. Inbound size is bounded by the transport's existing frame cap.
- [Logs] No findings. Neither lane logs any payload field, `conversation_id` or `ts`, and the sanitizer does not log.
- [Concurrency] No findings. The dedup check and the append happen inside one `MutableStateFlow.update` lambda. No new coroutine is created.
- [Threat model: hostile daemon] No findings. A malformed frame drops only itself. A fabricated `stops_turn` actuates nothing, because it is not even carried into the domain type. A hostile daemon repeating a `ts` causes a dropped duplicate rather than a crashed `LazyColumn`, because the list key and the dedup read one identity. Two different banners sharing one `ts` lose the second, which is the fail-safe direction and matches `holdsBoundary`.
- [Threat model: UI leakage] OUT OF SCOPE. The text may echo a host path or the operator's prompt. The protocol notes that `tool_use` already carries both to the same grant, and screenshot hardening of the thread is not this ticket's concern.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

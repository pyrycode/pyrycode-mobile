# #874: A finished compaction marked in the thread with a divider, live and on history reload

**Labels:** `enhancement`, `security-sensitive` · **Split from** #654 · **Live verification:** #679

## Files read

- `../pyrycode/docs/protocol-mobile.md` § `compaction_boundary` and § *Joining a page to the live stream*: the frame contract (open `trigger` set, `int | null` counts, `null` ≠ `0`, no clamp or order, no `turn_id`, may arrive with no `compacting` edge) and the `(type, ts)` join key. Cited, not restated.
- pyrycode `internal/protocol/interactive.go` → `CompactionBoundaryPayload`: all four keys always present, counts are pointers marshalled as a literal `null`, a count the daemon cannot decode as an integer produces no frame at all.
- pyrycode-desktop `compactionBoundaryViewModel.ts` → `compactionBoundaryTitle`, `validCount`, `tokenCount`: the label rules and the `24k` formatter this row mirrors.
- `data/repository/ConversationRepository.kt` → `ThreadItem`, `ThreadItem.Banner`, `ThreadItem.SessionBoundary`: the new variant goes beside them with `Banner`'s identity KDoc.
- `data/network/InteractivePayloads.kt` → `BannerPayloadDto` and its `toRow`: the DTO and pure-mapper pattern to mirror.
- `data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_BANNER` arm, `decodeBanner`, `appendBanner`, the `TYPE_COMPACTING` arm (untouched — it keeps driving only `CompactingProjection`), and the companion `TYPE_*` constants.
- `data/repository/HistoryPageReducer.kt` → `withHistoryEntry`'s `TYPE_BANNER` arm, `alreadyHolds`, `holdsBanner`, `mergeHistoryRows`' join-key KDoc.
- `ui/conversations/thread/ThreadRow.kt` → `ThreadItem.listKey`.
- `ui/conversations/thread/ThreadScreen.kt` → the `LazyColumn` render `when`, `ThreadItem.timestamp`, `mostRecentSessionBoundaryIndex` (unchanged: the divider is not a session boundary).
- `data/cache/ConversationCache.kt` → `cacheableThreadRows`; `data/cache/FileConversationCache.kt` → `toRecord`.
- `ui/conversations/components/SessionBoundaryDelimiter.kt` → `SessionBoundaryDelimiterContent`'s rule / label / rule `Row`, `BoundaryRule`, `RuleLabelSpacing`, `RULE_ALPHA`, `boundaryLabel` (hardcoded-English pure label precedent, JVM-tested).
- `docs/knowledge/features/banner-notice-row.md` — the lesson that shapes this ticket: a variant with a wire-minted `ts` must dedup on it in both writers through one shared predicate that the list key also reads, or a history page racing the live lane crashes the `LazyColumn` on a duplicate key.
- `docs/knowledge/features/session-boundary-delimiter.md` — the rule/label/rule layout, its token mapping, and the unweighted-label wrapping rule.
- Tests: `RemoteConversationRepositoryTest` (`banner_*` block, `threadShape`, `collectCompacting`), `HistoryPageReducerTest` (`#873` block, `entry`, `bannerPayload`), `FileConversationCacheThreadTest`, androidTest `SessionBoundaryDelimiterTest`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Message area's `Session reset` layer (`119:3843`): a centred row of a hairline rule, a `M3/body/small` label in `Schemes/primary`, and a second hairline rule, 12dp apart, rules in `Schemes/inverse-primary` at 60% (mapped to `outlineVariant` at 60% since #643). The compaction divider is that same row with the compaction label in place of the session label, and — unlike the session delimiter — no explanation line or Install button beneath it.

## Context

The daemon sends `compaction_boundary` when claude finishes compacting. Mobile drops it on both lanes, so the user never learns why claude stops remembering earlier detail. This ticket lands the divider the way #873 landed `banner`: type, live decode, history decode and row in one ticket.

**Sizing note.** The plan touches 9 production `.kt` files, one over the 8-file line, as the refiner's estimate states. Four of them take a one-to-three-line arm forced by the sealed `ThreadItem` `when`s (`ThreadRow.kt`, `ThreadScreen.kt`, `ConversationCache.kt`, `FileConversationCache.kt`). The decoder and the renderer are each other's only consumer, so the floor rule wins and the ticket is built as one. The divider composable lives in `SessionBoundaryDelimiter.kt` beside the row it reuses rather than in a tenth file.

## Design

### Domain (`ConversationRepository.kt`)

- `ThreadItem.CompactionBoundary(preTokens: Long?, postTokens: Long?, manual: Boolean, occurredAt: Instant)`.
  - `preTokens` / `postTokens`: claude's count **only when valid** — a non-negative integer no larger than `2^53 - 1` (desktop's `validCount`: `Number.isSafeInteger && >= 0`). `null` when claude stated none, sent `null`, omitted the key, or stated an invalid value. Narrowed at decode, so the domain never holds an unvalidated count.
  - `manual`: `true` only for the exact trigger `"manual"`. The open `trigger` string never enters the domain, so no unrecognised claude token can reach a label.
  - **Identity** is `occurredAt`, the envelope's or stored entry's `ts` — `(type, ts)` with the type implied by the variant. **Invariant:** unique among a thread's compaction boundaries; both writers skip one the thread already holds (`holdsCompactionBoundary`). KDoc'd and tested, not enforced at construction (`Banner` / `SessionBoundary` posture).
  - KDoc states: not a session boundary; drives no turn and no status indicator; never cached.

### Wire (`InteractivePayloads.kt`)

- `@Serializable internal data class CompactionBoundaryPayloadDto(conversationId: String, trigger: String, preTokens: Long? = null, postTokens: Long? = null)`.
  - `conversation_id` and `trigger` are strict-required (the daemon always emits them; a missing one drops the frame).
  - The counts default to `null` so a missing key reads as "no count stated", per the ticket's "missing, `null` or invalid" rule, rather than dropping the divider. A count that is not a JSON integer representable as `Long` fails the decode and drops the frame — the daemon's own posture for an undecodable count is "no frame at all", so mobile agrees with it.
- `internal fun CompactionBoundaryPayloadDto.toRow(occurredAt: Instant): ThreadItem.CompactionBoundary` — total and pure; validates each count (`validTokenCount`), maps `trigger == "manual"`.

### Live lane (`RemoteConversationRepository.kt`)

- Companion `TYPE_COMPACTION_BOUNDARY = "compaction_boundary"`.
- `onInbound` arm gated by `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`: `decodeCompactionBoundary(envelope)?.let { (id, row) -> appendCompactionBoundary(id, row) }`.
- `decodeCompactionBoundary(envelope): Pair<String, ThreadItem.CompactionBoundary>?` — DTO decode and `Instant.parse(envelope.ts)` inside one `try`/`catch (IllegalArgumentException)`; routes by the payload's `conversation_id`.
- `appendCompactionBoundary(conversationId, row)` — one atomic `threadByConversation.update`, end-appends unless `holdsCompactionBoundary(row)`.
- Exactly one write. No `liveSessionEvents` emission; no turn, stall, API-retry, compacting, resetting, thinking or usage-limit state touched. Nothing logged.

### History lane (`HistoryPageReducer.kt`)

- `TYPE_COMPACTION_BOUNDARY` arm in `withHistoryEntry`, only when `interactive`: decode, `toRow(occurredAt = entry.timestamp)`, skip if `holdsCompactionBoundary`. The existing `try` owns the malformed-entry drop.
- `alreadyHolds` gains `is ThreadItem.CompactionBoundary -> holdsCompactionBoundary(row)`; the join-key KDoc list gains its line.
- `internal fun List<ThreadItem>.holdsCompactionBoundary(boundary: ThreadItem.CompactionBoundary): Boolean` — matches on `occurredAt`. One identity read by the history merge, the live append and the list key.

### Render (`ThreadRow.kt`, `ThreadScreen.kt`, `SessionBoundaryDelimiter.kt`)

- `listKey`: `"compaction:$occurredAt"`.
- `ThreadScreen`: render arm `CompactionBoundaryDivider(item = item)`; `timestamp()` returns `occurredAt`. `mostRecentSessionBoundaryIndex` unchanged, so above-line de-emphasis is untouched; the row inherits `rowAlpha` like its neighbours.
- `SessionBoundaryDelimiter.kt`:
  - Extract the existing rule / label / rule `Row` into a private `RuleLabelRow(label: String, modifier)`, used by both delimiters. The session delimiter's rendering is unchanged (its androidTest is the proof).
  - `@Composable fun CompactionBoundaryDivider(item: ThreadItem.CompactionBoundary, modifier: Modifier = Modifier)` — `RuleLabelRow(compactionBoundaryLabel(item))` inside the same `MessageContentGutter` / `MessageAreaRowSpacing` padding. Stateless, not clickable, no explanation line.
  - `internal fun compactionBoundaryLabel(item): String` — desktop's `compactionBoundaryTitle` without the failed branch: `"Conversation compacted"` + `", ${pre} → ${post} tokens"` only when both counts are non-null + `" by you"` when `manual`. Hardcoded English, as the sibling `boundaryLabel`.
  - `internal fun compactionTokenCount(value: Long): String` — desktop's `tokenCount`: below 1000 the plain number; otherwise tenths of a thousand rounded half-up, `.0` dropped, `k` suffix (`24000 → 24k`, `1250 → 1.3k`, `3456 → 3.5k`). Integer arithmetic, locale-independent.
  - Previews: plain, sized, sized + by-you, light and dark.

### Cache (`ConversationCache.kt`, `FileConversationCache.kt`)

- `cacheableThreadRows` also filters out `ThreadItem.CompactionBoundary`; `toRecord` throws `IllegalStateException("compaction rows are never cached")`. `settledThreadRows` keeps them.

## State + concurrency model

No new job, flow or scope. Both writes go through the existing `threadByConversation` `MutableStateFlow.update` on the single inbound collector; the history reduction stays pure. The dedup check runs inside the `update` lambda.

## Error handling

- Malformed payload, missing/wrong-typed `conversation_id` or `trigger`, a non-integer count, or a malformed `ts` → that one frame or entry is dropped silently; the collector and the rest of the page survive.
- Missing, `null`, negative or unsafe-large count → divider drawn without sizes. Unknown or empty `trigger` → divider without "by you".
- Without `interactive`, nothing is decoded on either lane.

## Testing strategy

JVM unit tests (`runTest`, `FakeSessionPump`, existing helpers):

- `RemoteConversationRepositoryTest`, new `compactionBoundary_*` block:
  - Folds one row with counts, `manual` and `occurredAt` = envelope `ts`.
  - `null` counts, a missing count key and a negative count each yield a `null` count; `auto`, empty and unknown triggers yield `manual = false`.
  - Interleaves with messages in arrival order; strict `conversation_id` routing.
  - Same `ts` twice → one row; distinct `ts` → two.
  - Leaves stall, `liveSessionEvents`, API-retry and compacting unchanged, including when it arrives while `compacting` is active (still active) and with no `compacting` edge before it.
  - Malformed payload (missing `trigger`, non-integer count, wrong-typed `conversation_id`) or malformed `ts` drops only that frame; a later good one folds.
  - Gate closed or unrelated → nothing folds.
  - `threadShape` gains the arm.
- `HistoryPageReducerTest`: stored boundary → same row stamped with the entry timestamp; non-interactive → nothing; malformed entry costs only itself; a page whose boundary the live thread holds merges to one row; a repeated `ts` in one page → one row.
- `CompactionBoundaryLabelTest` (JVM, new): the label for sizes + manual, sizes only, `manual` only, neither, one count `null`; `compactionTokenCount` below 1000, exact thousands, rounding half-up, and a large value.
- `FileConversationCacheThreadTest`: `cacheableThreadRows` drops compaction rows.
- androidTest `CompactionBoundaryDividerTest`: the label renders; the row has no click action. Existing `SessionBoundaryDelimiterTest` stays green unchanged as the proof the extraction preserved the session delimiter.
- Rungs 3/4: live verification belongs to #679 per the ticket; no scripted fakeclaude emitter for `compaction_boundary` is added here.

## Open questions

- Should the divider carry a "Claude" attribution, as the protocol asks for the frame generally? **Resolved:** no. The ticket settles the copy as desktop's client-owned label; only two validated integers and one boolean from claude reach it. Recorded in the security review.

## Documentation handoff

Pending for the documentation stage: fold the compaction divider, its `(type, ts)` identity, its count-validation rule and its never-cached rule into the owning overviews (`session-boundary-delimiter.md`, `remote-conversation-repository-live-stream-and-modals.md`, `conversation-cache.md`, `compacting-indicator.md`'s note that `compacting` still drives only the indicator). The ticket names no other docs.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. One decode boundary per lane — `decodeCompactionBoundary` live, the `TYPE_COMPACTION_BOUNDARY` arm of `withHistoryEntry` on replay — both through the strict `CompactionBoundaryPayloadDto` and the one mapper `toRow`. Downstream code holds only `ThreadItem.CompactionBoundary`, whose fields are two validated `Long?` and a `Boolean`. The open `trigger` string is narrowed to `manual` at the mapper and never reaches the UI, a log or storage, so the protocol's "strip control characters and escapes" obligation has no sink here: no claude-authored string is rendered at all.
- [Trust boundaries] OUT OF SCOPE. A fabricated boundary (`pre_tokens: 999999, post_tokens: 1`) draws a plausible compaction mark where none happened, and the protocol asks for the frame to read as claude's assertion. The ticket settles the copy as desktop's unattributed client label. The consequence is a misleading label, never an actuator: the arm moves no turn or status state and nothing acts on the counts. Revisit with a new ticket only if abuse is observed.
- [Trust boundaries] No findings on numeric abuse. Negative and beyond-`2^53 - 1` counts are narrowed to `null`; a non-integer or `Long`-overflowing count fails the decode and drops the one frame. `compactionTokenCount` uses integer arithmetic on a validated non-negative value, so it cannot overflow or produce a locale-dependent string. `post > pre` is rendered as stated — the protocol forbids ordering, and desktop does the same.
- [Tokens] No findings. The frame carries no credential; the plan creates or stores none.
- [File / storage] No findings. The row is excluded from `cacheableThreadRows` and `toRecord` throws on it; history replay restores it.
- [Android surface] No findings. No intent, deep link, WebView, provider or `PendingIntent`. The row is not clickable or selectable.
- [Crypto] No findings. No randomness, no primitive. The identity is the daemon's `ts`, compared for equality within one thread.
- [Network & I/O] No findings. No outbound frame; inbound size bounded by the transport's existing frame cap.
- [Logs] No findings. Neither lane logs any field, `conversation_id` or `ts`.
- [Concurrency] No findings. Dedup check and append inside one `MutableStateFlow.update` lambda; no new coroutine.
- [Threat model: hostile daemon] No findings. A malformed frame drops only itself. A repeated `ts` becomes a skipped duplicate rather than a crashed `LazyColumn`, because the list key and both writers read `holdsCompactionBoundary`. Two different boundaries sharing one `ts` lose the second — the fail-safe direction. A flood of distinct boundaries grows the thread exactly as a flood of messages would; not a new vector.
- [Threat model: UI leakage] No findings. The row shows only client copy and two numbers.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

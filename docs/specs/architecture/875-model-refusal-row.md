# #875 — Model refusal row, live and on history reload

## Files read

- `data/repository/ConversationRepository.kt` → `ThreadItem` (`Banner`, `CompactionBoundary`) — the sealed type gaining a `ModelRefusal` variant; `Banner`'s KDoc is the identity/invariant template.
- `data/network/InteractivePayloads.kt` → `BannerPayloadDto`, `CompactionBoundaryPayloadDto`, their `toRow` — the DTO + total-mapper shape the two new DTOs follow.
- `data/network/MobileWireCodec.kt` → `MobileJson` — `explicitNulls = false`, so a nullable property with no default decodes a missing key as `null`.
- `data/repository/ThreadProjection.kt` → `applyBanner`, `decodeBanner`, `appendBanner` — the live fold the new `applyModelRefusal` mirrors (post-#912, the projection owns thread writes).
- `data/repository/RemoteConversationRepository.kt` → `onInbound`'s `TYPE_BANNER` / `TYPE_COMPACTION_BOUNDARY` arms and their companion constants — where the live arm and the two type constants go.
- `data/repository/HistoryPageReducer.kt` → `withHistoryEntry`, `alreadyHolds`, `holdsBanner` — history arm, merge predicate, shared identity predicate.
- `data/cache/ConversationCache.kt` → `cacheableThreadRows`; `data/cache/FileConversationCache.kt` → `toRecord` — never-cached filter and the defensive throw arm.
- `ui/conversations/thread/ThreadRow.kt` → `ThreadItem.listKey`; `ui/conversations/thread/ThreadScreen.kt` → the `LazyColumn` `when`, `ThreadItem.timestamp`, `mostRecentSessionBoundaryIndex` (unchanged).
- `ui/conversations/components/UnrecognizedMessageRow.kt` → stateful/stateless split, collapsed pill, in-place expand — the row structure the ticket names.
- `ui/conversations/components/BannerNoticeRow.kt` → `bannerDisplayText`, the `"Claude: "` attribution span — reused, not copied.
- `docs/knowledge/features/banner-notice-row.md` § "The thread-row type" — lesson: a variant with a wire `(type, ts)` identity must dedup on both lanes or the `LazyColumn` crashes on a duplicate key the first time a page races the live lane.
- `../pyrycode/docs/protocol-mobile.md` § `model_refusal_fallback`, § `model_refusal_no_fallback`, § *Joining a page to the live stream* (wire SSOT, not restated); `../pyrycode/internal/protocol/interactive.go` → `ModelRefusalFallbackPayload`, `ModelRefusalNoFallbackPayload`.
- pyrycode-desktop `ConversationScreen.tsx` → `ModelRefusalRow` — title copy and "unknown model".

No in-flight feature branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The Conversation Thread frame draws message bubbles, the thinking row and the composer; it has no refusal component. The row reuses `UnrecognizedMessageRow`'s collapsed pill: a 12dp rounded `Surface` on `Schemes/Surface Container` with a 1dp `Schemes/Outline Variant` border, a leading 18dp outlined glyph and one `M3/body/small` line in `Schemes/On Surface Variant`, expanding in place to show the banner prose.

## Context

The daemon sends `model_refusal_fallback` / `model_refusal_no_fallback` when claude refused a turn on one model (and possibly retried on another). Mobile drops both on both lanes, so a changed model or a missing answer goes unexplained. This is the fourth `ThreadItem` variant landed in the #608/#873/#874 shape: type + live decode + history decode + row in one ticket, because the decoder and the renderer are each other's only consumer.

**Size overage, stated:** 10 production `.kt` files against the 8-file line. Five of them take a one-to-three-line `when` arm or filter clause forced by the new variant (`FileConversationCache`, `ConversationCache`, `ThreadRow`, `ThreadScreen`, `HistoryPageReducer`'s `alreadyHolds`); since #912 the live fold sits in `ThreadProjection` rather than the repository, which adds one more file than the refiner counted. Splitting would produce a decoder with no consumer or a row nothing feeds — the floor rule wins. Total written work is estimated ~900 lines, under 1600.

## Design

### Domain type — `ThreadItem.ModelRefusal`

```kotlin
data class ModelRefusal(
    val originalModel: String,
    val fallbackModel: String?,   // non-null iff the frame was model_refusal_fallback
    val banner: String,
    val bannerTruncated: Boolean,
    val occurredAt: Instant,
) : ThreadItem
```

- One variant for both frames: they render as one row kind with one of two titles. `fallbackModel != null` *is* the envelope type, so the domain carries it without a second flag.
- Every string is claude-authored, verbatim, unsanitized (stripping is the render boundary's, as `Banner.text`). KDoc: render inert, never persist, never log.
- `scope`, `refusal_category` and `dropped_fields` are decoded (shape-checked) and not carried — they drive nothing. A dropped field simply arrives empty; an empty model reads "unknown model", an empty banner makes the row non-expandable.
- `bannerTruncated` = `"banner" in truncated_fields`. Truncation of a model identifier is not marked (the AC names only the banner).
- **Identity:** `(kind, occurredAt)` where kind = `fallbackModel != null` — the protocol's `(type, ts)` join key. Invariant: unique among a thread's refusal rows; documented in KDoc, asserted in tests, enforced by both writers through one predicate.

### Wire DTOs (`InteractivePayloads.kt`)

- `ModelRefusalFallbackPayloadDto(conversationId, originalModel, fallbackModel, scope, refusalCategory, banner: String, truncatedFields: List<String>?, droppedFields: List<String>?)` — the strings strict-required with no default; the report arrays nullable (`null` on the wire is their normal value).
- `ModelRefusalNoFallbackPayloadDto` — same minus `fallbackModel` / `scope`.
- `internal fun <Dto>.toRow(occurredAt: Instant): ThreadItem.ModelRefusal` for each — total, no drop path.

### Live lane

- `RemoteConversationRepository` companion: `TYPE_MODEL_REFUSAL_FALLBACK = "model_refusal_fallback"`, `TYPE_MODEL_REFUSAL_NO_FALLBACK = "model_refusal_no_fallback"`.
- `onInbound`: one arm for both constants, gated on `CAPABILITY_INTERACTIVE in negotiatedCapabilities()`, calling `threadProjection.applyModelRefusal(envelope)`. Nothing else: no `liveSessionEvents` emission, no turn/stall/status/model-projection write.
- `ThreadProjection.applyModelRefusal(envelope)` → private `decodeModelRefusal(envelope): Pair<String, ThreadItem.ModelRefusal>?` selects the DTO by `envelope.type`, stamps `Instant.parse(envelope.ts)`, one `try`/`catch (IllegalArgumentException)` → null drops the one frame. Private `appendModelRefusal` end-appends inside one atomic `update` unless `holdsModelRefusal(row)`.

### History lane (`HistoryPageReducer.kt`)

- `withHistoryEntry`: one arm for both type constants, `interactive`-gated, decodes by `entry.type`, `toRow(occurredAt = entry.timestamp)`, skips via `holdsModelRefusal`. Malformed entry is caught by the existing single `try` and costs only that entry.
- `alreadyHolds`: `is ThreadItem.ModelRefusal -> holdsModelRefusal(row)`.
- `internal fun List<ThreadItem>.holdsModelRefusal(refusal: ThreadItem.ModelRefusal): Boolean` — same kind and same `occurredAt`. One identity, three readers: history merge, live append, list key.

### Cache

- `cacheableThreadRows` also filters `ThreadItem.ModelRefusal`; `toRecord` gains `is ThreadItem.ModelRefusal -> throw IllegalStateException("model refusal rows are never cached")`. `settledThreadRows` keeps the row (unchanged code).

### UI

- `ThreadRow.listKey`: `"refusal:fallback:$occurredAt"` / `"refusal:no-fallback:$occurredAt"`.
- `ThreadScreen`: render arm `ModelRefusalRow(item = item)`; `timestamp()` arm → `occurredAt`. `mostRecentSessionBoundaryIndex` untouched (not a session boundary).
- New `ui/conversations/components/ModelRefusalRow.kt`:
  - `@Composable fun ModelRefusalRow(item, modifier)` — owns `rememberSaveable` `expanded: Boolean` (only the toggle is saved), delegates to private stateless `ModelRefusalRowContent(item, expanded, onToggle, modifier)`.
  - Collapsed pill per Design source. Leading `Icons.Outlined.Info` glyph, `contentDescription = null`. The title is one `AnnotatedString` built from client-owned copy (`thread_refusal_refused_on` "Refused on ", `thread_refusal_continued_on` ", continued on ", `thread_refusal_refused_by` "Refused by ") with each model identifier as a separate `FontFamily.Monospace` + `onSurface` span; a missing model is the client-owned `thread_refusal_unknown_model` ("unknown model") in the client style.
  - The pill is `clickable` (with expand/collapse click labels) only when the sanitized banner is non-blank; otherwise it has no click action.
  - Expanded body: one `Text` whose `AnnotatedString` is the reused `thread_banner_attribution` ("Claude: ") span in `FontWeight.Medium`, then `bannerDisplayText(item.banner)`, then — when `bannerTruncated` — the reused `thread_banner_truncated` in italic. No markdown, no `SelectionContainer`, no link detection.
  - `internal fun refusalModelDisplay(model: String): String?` — `bannerDisplayText(model)` with tab/LF/CR also removed (an identifier has no business spanning lines), then `null` when blank → caller shows "unknown model".
  - Light + dark `@Preview`s over a fallback, a no-fallback with empty model, a truncated expanded banner, and an empty-banner row.

## State + concurrency model

No new scope or job. Both writes are single atomic `MutableStateFlow.update` folds inside `ThreadProjection` (live) and `mergeHistoryPage` (history), each running its dedup check in the same lambda as its write. UI state is the one saved `Boolean` per row, scoped by the row's `LazyColumn` key.

## Error handling

Decode-or-drop: a missing / wrong-typed field or a malformed `ts` drops the one live envelope (the lone collector survives) or the one history entry (the page survives). Without `interactive` negotiated nothing is decoded on either lane. No logging on any branch.

## Testing strategy

- `ModelRefusalDisplayTest` (JVM, new): `refusalModelDisplay` strips CSI/OSC/controls and tab/newline; plain identifiers survive; empty and escape-only/blank input → `null`.
- `RemoteConversationRepositoryTest` (`modelRefusal_*` block): fallback and no-fallback fold one row each with verbatim fields and envelope `ts`; `truncated_fields: ["banner"]` → `bannerTruncated`, `null`/other tokens → false; repeat of the same `(type, ts)` folds once; same `ts` on the two types folds two rows; routing by `conversation_id`; inert — `liveSessionEvents` silent, existing stall / model state untouched, existing rows unchanged; missing field, wrong-typed field, malformed `ts` drop only that frame; gate closed folds nothing. `threadShape()` gains the arm.
- `HistoryPageReducerTest`: both stored types reduce to the same row stamped with the entry timestamp; non-interactive yields nothing; a malformed entry costs only itself; a page whose refusal the live thread already holds merges to one row.
- `FileConversationCacheThreadTest`: `cacheableThreadRows` drops the row.
- `ModelRefusalRowTest` (androidTest, new): both titles; "unknown model" for an empty model; banner hidden until tapped, then shows "Claude: " + stripped text; truncated mark shown only when cut; empty banner → no click action.
- **No rung-3 / rung-4 scenario here.** The daemon has no scripted refusal emitter; live verification against a real claude is #679, per the ticket.

## Documentation handoff

Pending for the documentation stage (the ticket has no Documentation handoff section; carried as the natural follow-on): a feature overview for the model refusal row under `docs/knowledge/features/` (the `banner-notice-row.md` shape), plus the new `ThreadItem` variant noted in the thread-screen, conversation-cache and remote-repository overviews and `INDEX.md`.

## Open questions

- None blocking. Icon choice (`Info` vs another outlined glyph) is design-owed; resolved at implementation against the previews.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings — the untrusted payload becomes a typed value at exactly two decode points (`ThreadProjection.decodeModelRefusal`, `withHistoryEntry`'s arm), both through `MobileJson` and the two DTOs; downstream holds only `ThreadItem.ModelRefusal`. Daemon text is bounded daemon-side and reaches Compose only through `refusalModelDisplay` / `bannerDisplayText` into plain `Text`.
- [Trust boundaries — spoofing] Addressed in design — a model identifier crafted to read as client copy (e.g. `"a, continued on b"` in a no-fallback frame) is contained by rendering every identifier as its own monospace span between client-owned proportional spans, and by stripping newlines from identifiers so one cannot manufacture extra lines. The `"Claude: "` attribution stays a separate styled span (the #873 rule).
- [Trust boundaries — behaviour] No findings — `scope` and `refusal_category` are decoded for shape only and never reach the domain; `model_announced` stays the only writer of model state, and the arm writes nothing but the thread row, so a hostile frame cannot change the model chip, a turn or a status indicator.
- [Tokens] Not applicable — no token, key or credential is created, stored or read.
- [File / storage] No findings — the row is excluded by `cacheableThreadRows` and `toRecord` throws on it, so claude-authored prose never reaches app-private disk; only a `Boolean` reaches saved-instance state.
- [Android surface] Not applicable — no intent, deep link, provider, push or WebView; the row has no link detection and no click beyond the expand toggle.
- [Crypto] Not applicable — no primitive touched.
- [Network & I/O] No findings — no new frame path; the existing envelope cap applies. Lengths are daemon-bounded; the row adds no second cap (the `Banner` posture) and model identifiers wrap rather than lay out a single unbounded line.
- [Logs] No findings — no log call on any branch; the caught decode exception is discarded because its message can quote claude's text.
- [Concurrency] No findings — dedup check and write share one `update` lambda on both lanes; no scope or job added.
- [Threat model — hostile daemon] Addressed — malformed frames drop singly; a daemon repeating one `(type, ts)` for two different refusals loses the second (fail-safe: a missing row, not a crashed list). Unicode bidi/format characters are OUT OF SCOPE, matching `bannerDisplayText` and desktop (no observed abuse).

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

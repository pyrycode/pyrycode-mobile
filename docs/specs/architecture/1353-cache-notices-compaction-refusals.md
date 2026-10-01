# #1353 — Keep notices, compaction dividers and refusals in the saved thread

## Files read

- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` — `cacheableThreadRows` (the filter that drops the three kinds and trims with `takeLast`) and `MAX_CACHED_THREAD_ROWS`; the `readThread` KDoc that lists which identities never repeat.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` — `CachedThreadRow`, `ThreadItem.toRecord` (the three `IllegalStateException` arms), `CachedThreadRow.toDomain` (the exactly-one-kind rule), and `decodeThread`, which rejects a repeated list key.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — `ThreadItem.Banner`, `ThreadItem.CompactionBoundary`, `ThreadItem.ModelRefusal`: field shapes, and KDocs that say "must not persist" / "Never cached", which this ticket makes false.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` — `mergeCachedRows`, `alreadyHolds`, `holdsBanner`, `holdsCompactionBoundary`, `holdsModelRefusal`: the dedupe keys a cached row joins on. Unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` — `ThreadItem.listKey`: banner keys on `occurredAt`, compaction on `occurredAt`, refusal on `(fallbackModel != null, occurredAt)`. The decoder's duplicate rule must match these keys.
- `app/src/main/java/de/pyryco/mobile/data/repository/CachingConversationRepository.kt` — `observeMessages`: writes `cacheableThreadRows(drawn)` only when it differs from the last write. Unchanged.
- `docs/knowledge/features/conversation-cache.md` — lesson carried: every additive record field defaults so an older document still decodes (#983's `attachments`); the version stays 1.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

No visual change: the existing `BannerNoticeRow`, compaction divider and `ModelRefusalRow` draw in a restored thread exactly as they draw live. This is a data-layer change; there is nothing to compare against the node beyond the rows appearing.

## Context

The thread cache drops banners (#873), compaction dividers (#874) and model refusals (#875) because history replay used to restore them on every open. History now loads only when the user asks, so a reopened or offline chat loses those rows. Desktop keeps every settled row kind except the live-only attachment offer and caps a timeline at 100000 entries. This brings mobile in line, except that `UnrecognizedMessage` stays out: its raw JSON may not be persisted, and mobile's cache is plain files.

No decision record needed; the desktop parity rule is already the decision.

## Design

`CachedThreadRow` gains three optional fields, each defaulting to `null`, so a document holding only `message` and `boundary` rows still decodes. The version stays 1.

- `banner: CachedBanner?` — `CachedBanner(level: BannerLevel, text: String, truncated: Boolean, occurredAt: String)`.
- `compaction: CachedCompaction?` — `CachedCompaction(preTokens: Long? = null, postTokens: Long? = null, manual: Boolean, occurredAt: String)`.
- `refusal: CachedRefusal?` — `CachedRefusal(originalModel: String, fallbackModel: String? = null, banner: String, bannerTruncated: Boolean, occurredAt: String)`.

`occurredAt` is ISO text as in `CachedBoundary`, so `Instant` round-trips exactly; it is the dedupe key. Enums serialize by name.

`ThreadItem.toRecord` maps each kind to its record; only the `UnrecognizedMessage` arm still throws. `CachedThreadRow.toDomain` requires exactly one of the five fields and maps it back.

`decodeThread` additionally rejects a document with two banners on one `occurredAt`, two compaction rows on one `occurredAt`, or two refusals of one type on one `occurredAt` — the `LazyColumn` keys from `ThreadItem.listKey`, so a tampered document reads empty rather than crashing the thread, as for boundaries today.

`cacheableThreadRows` drops only `UnrecognizedMessage` and in-flight rows (via `settledThreadRows`) and keeps the newest `MAX_CACHED_THREAD_ROWS`, now `100_000`.

KDoc updates: the three `ThreadItem` variants say the thread cache stores them as held, rendered inert on restore; `cacheableThreadRows` and `readThread` describe the new rule.

Model and banner text are stored verbatim, as message content already is; the render path keeps its stripping.

## State and concurrency model

None changes. `CachingConversationRepository` already writes only when `cacheableThreadRows(drawn)` changes, so the three kinds add a write only when one arrives, and the higher limit adds no writes during streaming.

## Error handling

Unchanged failure model: a row with zero or several kinds, an unparseable `occurredAt`, or a repeated key throws `IllegalArgumentException` in decode, which `readThread` classifies `invalid_data` and reads as empty. An older document decodes through the defaults.

## Testing strategy

`FileConversationCacheThreadTest` (plain JVM):

- Round-trip: a thread holding a message, a banner (both levels, truncated true), a compaction row (null and non-null token counts, manual true/false), a refusal with and without a fallback model, and a boundary reads back equal and in order. Replaces the three "never stored" tests.
- Unrecognized, streaming and running rows are still not written (existing test, kept).
- A pre-#1353 document holding only a message and a boundary (literal JSON) still reads.
- Tampered duplicates: a repeated banner instant, compaction instant, and refusal type-plus-instant each read empty; a row setting two kinds reads empty.
- `cacheableThreadRows`: `MAX_CACHED_THREAD_ROWS == 100_000`; over the limit it keeps the newest rows (called directly, no 100k-row file write).

`CachingConversationRepositoryTest`:

- A `FileConversationCache`-backed restore offline draws the three kinds in their original positions.
- One test per kind: the cached row is re-delivered by the live page and is drawn once, in place, and the write equals the drawn thread (pins `alreadyHolds` through `mergeCachedRows`).

No device or rung-3 scenario: not operator-facing UI behaviour beyond existing rows drawing from a different source.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/conversation-cache.md` — the contract section and the thread-document shape (which row kinds are kept, the three new records), the new 100000 limit (and the "row-count cap" note under "What's deliberately not here"), and why `UnrecognizedMessage` stays out (its KDoc forbids persisting raw text; the cache is plain files). The cross-references in `banner-notice-row.md`, `session-boundary-delimiter.md` and `model-refusal-row.md` that say these rows are never cached.

## Open Questions

None.

## Revisions

- 2026-10-01: The KDocs of `BannerNoticeRow` and `ModelRefusalRow` also said the row "is never cached". Both are corrected to say the thread cache stores the row and a restored row renders through the same boundary. Comment-only; no behaviour or contract change.

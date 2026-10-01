# ADR 0007 — segment-derived keys, not a minted id, for per-turn assistant reply rows

**Status:** Accepted (2026-10-01, with [#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350)).

## Context

[#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350) makes mobile match desktop: a turn that
streams text, runs a tool, then streams more text draws as two assistant bubbles around the tool row,
instead of one bubble above it. `HistoryPageReducer.withAssistantDelta` now extends the thread's last row
only when it is already a segment of the same turn; otherwise it opens a new segment at the end.

A new segment needs an id, and that id is load-bearing in three places at once: it is the row's
`Message.id`, the dedupe key `appendMessages`/the merges use, and the key `ThreadScreen`'s `LazyColumn`
renders by — which throws on a duplicate. Three constraints shaped the choice:

- **Live and a replayed history page must derive the same key from the same delta**, with no shared state
  between the two lanes, because a page can start or end mid-segment and the merge has to recognise the
  overlap.
- **A row cached before this change** — one `message_id`-keyed row holding a whole turn's text — must keep
  reading as that turn's first segment, so the pre-existing [#425](../codebase/425.md) key-uniqueness guard
  in `ThreadFold.render` keeps matching it with no special case.
- **The daemon picks `turn_id` freely.** It could choose one that spells another turn's key, a `tool_use_id`,
  or a `message_id`; the key scheme cannot be trusted to avoid that by construction, and the code has to
  guard against it where keys are minted.

## Decision

**Derive the key from the segment's opening delta, never mint a fresh id.**

`segmentKey(turnId, openingSeq)` is the bare `turnId` when the opening delta's `seq` is `0` (every turn's
text starts there, so this is always the first segment) and `"<turnId>#<openingSeq>"` otherwise. Each
segment also records its deltas' `seq` and text length (`AssistantSegment`/`SegmentDelta`), so two copies of
one segment that a page boundary or a lane join cut in two can be told apart from two genuinely different
segments and rejoined (`joinSegments`/`withJoinedSegments`).

Uniqueness is enforced **where a key is minted**, not by the key format: `withAssistantDelta` drops a delta
that would open a segment under a key any row already carries, and `withToolUse`'s repeat check is
id-only across every row kind (not namespaced to `Role.Tool`), closing the reverse direction. Both guards
only ever suppress a row; neither completes, moves, or resets one.

A later rework ([PR #1420](https://github.com/pyrycode/pyrycode-mobile/pull/1420) review) added a second
dedupe dimension: `mergeHistoryRows`/`mergeCachedRows` now also dedupe by `(turnId, seq)`, with the
receiving thread owning a turn from its lowest held `seq` up (`segmentHeads`/`olderThan`), because a
client-placed local echo of a mid-turn user message can land in a different position relative to the
turn's deltas than the daemon's own log entry for the same message — breaking the assumption that an
id-and-adjacency join alone is enough. See
[Remote conversation repository § Assistant reply segments](../features/remote-conversation-repository-reads-and-thread-store-history-paging.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350)
for the full mechanism.

## Rationale

- **No coordination needed between lanes.** Because the key is a pure function of the opening delta
  (`turnId`, `seq`), live and history independently derive the same key for the same segment without a
  shared counter, a handshake, or a second decode pass.
- **Zero-cost backward compatibility.** The bare-id-at-`seq`-0 rule means a pre-#1350 cached row needs no
  migration and no sentinel field: it already satisfies the rule by construction, so it reads as the first
  segment and the existing #425 guard needs no segment-awareness to keep matching it (though
  `ThreadFold.render`'s guard was still widened to also match a *later* segment, which the bare-id check
  alone cannot see).
- **Guarding at the mint site generalises.** A format-only defense (e.g. reserving a key prefix) cannot
  stop a daemon from choosing a colliding `turn_id` on purpose; checking "is this key already held" at
  every place a key or an id is newly assigned closes that regardless of what the daemon sends.

## Alternatives considered

- **A client-minted synthetic id per segment** (e.g. a per-process counter or a UUID). Rejected: live and a
  replayed page would derive *different* ids for the same segment, since neither a counter nor a UUID is
  reproducible from the wire data alone — the merge could then never recognise that a page's segment and a
  live segment are the same thing, defeating the seam join entirely.
- **`(turnId, seq-of-first-delta)` as the key for every segment, including the first.** Considered and
  rejected for migration cost: it would make a pre-change cached row (no stored opening `seq`) require a
  compatibility branch in the #425 guard and in every merge, instead of the bare-id rule falling out for
  free at `seq 0`.
- **Keeping the original id-and-adjacency join with no `(turnId, seq)` dedupe.** This was the initial
  #1350 design and shipped in the first review round; the verifier reproduced duplicate reply text from a
  mid-turn user message's echo landing in a different lane position than its log entry. Reopened and fixed
  by the `(turnId, seq)` rework described above.

## Consequences

- **A corrupted or inconsistent cached segment record degrades to no join, never a crash.** `CachedSegment`
  is validated on read (lengths sum to the content length, `seqs` strictly increasing); a failing record
  loads with `segment = null`, which then reads as a pre-change whole-turn row and removes the turn's other
  segments from the drawn thread (noted as a NIT in the PR #1420 review — a cost beyond "loses its join",
  but only reachable through a corrupt or tampered cache file).
- **One known gap, not closed by this ticket:** `CachingConversationRepository.observeMessages` composes
  `mergeCachedRows` after `ThreadProjection.observe`'s `withOnlyLastRowStreaming()` normalisation, so that
  rule does not cover the cache-merged result — a mid-turn reconnect can show a non-last segment streaming
  above a queued echo. Flagged as a SHOULD FIX, not fixed in #1350. See
  [Streaming assistant turns § Finished rows are now per-segment](../features/streaming-assistant-turns.md#finished-rows-are-now-per-segment-not-one-bubble-per-turn-1350).
- **`turn_end` arriving before the rows it should settle is still unhandled** (pre-existing, not a #1350
  regression): `withFinalizedTurn` only settles rows already in the list. Tracked as
  [#1419](https://github.com/pyrycode/pyrycode-mobile/issues/1419); one `AssistantSegmentTest` case is
  `@Ignore`d on it.

## Related

- Ticket: [#1350](https://github.com/pyrycode/pyrycode-mobile/issues/1350)
- Spec: `docs/specs/architecture/1350-assistant-reply-segments.md` (§ Design, § Revisions for the
  `(turnId, seq)` rework)
- Feature docs: [Streaming assistant turns](../features/streaming-assistant-turns.md),
  [Live tool-call](../features/live-tool-call.md),
  [Remote conversation repository § Assistant reply segments](../features/remote-conversation-repository-reads-and-thread-store-history-paging.md#assistant-reply-segments-the-key-the-seam-join-and-the-turn-seq-dedupe-1350)
- Precedent this follows: [#775](../codebase/775.md)'s session-boundary identity fix — "when a list row has
  a client-visible identity, dedup upstream on *that* identity, or the two can silently disagree," recorded
  in the history-paging doc.
- Prior key-uniqueness guard it extends: [#425](../codebase/425.md) (`turnId == message_id` collision fix).
- Live-gate evidence: real-claude gate PASS, 41/41 executed tests, 2026-10-01 (issue #1350 comment).

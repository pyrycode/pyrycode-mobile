# #775 — One identity for a session boundary

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt` → `ThreadRow.listKey`, private `ThreadItem.listKey` — the boundary key `"boundary:<prev>-><new>"` reads neither `reason` nor `occurredAt`; the KDoc on `ThreadRow.listKey` carries the key-uniqueness argument.
- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt` → `appendSessionBoundary` (pure end-append, "no dedup"), `appendUnrecognizedMessage` (its KDoc contrasts itself with `appendSessionBoundary`'s "nothing to dedup on"), the `threadByConversation` KDoc ("boundaries pure-append … no dedup"), the `TYPE_SESSION_TRANSITION` arm of `onInbound` (three sibling writes; only the row append changes).
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt` → `holdsBoundary` (pair-only, private), `alreadyHolds`, `mergeHistoryRows` KDoc ("joins on its `(previousSessionId, newSessionId)` pair"), `withHistoryEntry`'s `TYPE_SESSION_TRANSITION` arm (also calls `holdsBoundary` within a page).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` → `ThreadItem.SessionBoundary` (`occurredAt: kotlinx.datetime.Instant`), `ThreadItem.UnrecognizedMessage`'s KDoc as the model for stating a uniqueness obligation.
- `app/src/test/java/de/pyryco/mobile/data/repository/HistoryPageReducerTest.kt` → `merge_boundaryDifferingOnlyInOccurredAt_addsNoSecondRow` pins the old pair-only rule and must flip.
- `app/src/test/java/de/pyryco/mobile/data/repository/RemoteConversationRepositoryTest.kt` → `sessionTransitionEnvelope` (takes `occurredAt`), `boundariesOf`, `threadShape`, the `sessionTransition_*` block.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadRowsTest.kt` → `boundary()` fixture and the listKey tests.
- Daemon, `../pyrycode/cmd/pyry/session_transition_v2.go` → the broadcast marshals `payloadJSON` once and hands the same bytes to `appendConversationHistory` and to every envelope; `toWirePayload` mirrors the evicted id into both id fields for `idle_evict`. So a stored boundary carries the live frame's exact `occurred_at` (Technical Note confirmed): the overlap dedup still collapses a live/history twin.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The thread screen: a column of M3 message bubbles under a top app bar, the composer and status chips at the foot, with session boundaries drawn by the existing `SessionBoundaryDelimiter`. Nothing visual changes here; a repeated eviction now draws its own delimiter at its arrival position instead of crashing the list.

## Context

`idle_evict` reuses the session id for both wire fields and a reactivated session keeps its id, so an honest daemon emits `A->A` once per eviction. Two such frames on one connection give the `LazyColumn` two rows with one key and the thread crashes. Deduping on the pair (what #645 did for history) would drop the second, legitimate delimiter. The fix gives a boundary one identity, `(previousSessionId, newSessionId, occurredAt)`, read by the key and both dedups.

File-overlap check: open PRs #836 (#822) and #834 (#823) touch `RemoteConversationRepository.kt` and `ConversationRepository.kt`, but their hunks sit well away from `appendSessionBoundary`, the `threadByConversation` KDoc and `ThreadItem.SessionBoundary`. Proceeding; a `git merge-tree` against both branches is run before the PR to prove no textual conflict.

## Design

- **Identity predicate, one definition.** `holdsBoundary` in `HistoryPageReducer.kt` becomes `internal` and compares all three fields: `previousSessionId`, `newSessionId`, `occurredAt`. `reason` and `workspaceCwd` stay out: two frames equal on the triple but differing in reason are still one boundary as far as the key is concerned, so the second is dropped (fail-safe: a missing delimiter, never a crash).
- **Live lane.** `appendSessionBoundary` reuses `holdsBoundary` inside its `MutableStateFlow.update`: when the conversation's thread already holds the boundary it returns the map unchanged (StateFlow conflation, no re-emit); otherwise it end-appends as today. The arm's other writes (`updateCurrentSessionId`, `bumpSettingsRevision`, `thinkingProgressProjection.clear`) are untouched.
- **History merge.** No code change beyond the predicate; `alreadyHolds` and `withHistoryEntry` both already route through `holdsBoundary`.
- **Render key.** The boundary arm of the private `ThreadItem.listKey` encodes exactly the three fields, length-prefixing the two daemon-supplied ids so no pair of distinct triples can concatenate to the same string (a session id containing `->` could otherwise collide): `"boundary:<prevLen>:<prev><newLen>:<new>@<occurredAt>"`. `occurredAt` is last, so it needs no prefix; `Instant.toString()` is injective.
- **KDoc** updated on: `ThreadItem.SessionBoundary` (uniqueness obligation, as `UnrecognizedMessage` states its own), `ThreadRow.listKey`, `appendSessionBoundary`, `appendUnrecognizedMessage`'s sibling rationale, `threadByConversation`, `mergeHistoryRows`'s join-key bullet, `holdsBoundary`.

## State + concurrency model

Unchanged. The dedup check runs inside the existing atomic `threadByConversation.update` CAS, so a concurrent writer retry-merges against the check rather than racing it.

## Error handling

No new failure mode. A repeated identical frame is dropped silently, nothing is logged (session ids are sensitive, as today).

## Testing strategy

Unit tests only (`./gradlew testDebugUnitTest`):

- `RemoteConversationRepositoryTest`: two live `A->A` `idle_evict` frames with different `occurred_at` → two boundary rows in arrival order (AC 1); the same frame repeated → one row, and a message between still lands in order (AC 2).
- `HistoryPageReducerTest`: flip `merge_boundaryDifferingOnlyInOccurredAt_addsNoSecondRow` to assert the row **is** admitted (AC 3); the existing `merge_aPageWhoseBoundaryIsAlreadyLive_addsNoSecondBoundary` and `merge_reMergingTheSamePage_addsNoRowOfAnyKind` keep the overlap proof.
- `ThreadRowsTest`: two boundaries sharing a pair but not `occurredAt` get distinct keys (AC 1's render half, AC 4); two triples whose ids would concatenate identically (`"a->b"`/`"c"` vs `"a"`/`"b->c"`) get distinct keys; a boundary's key is stable across equal instances.

No Compose UI test: the crash is a key collision, fully decided by `listKey`, which the unit test pins. Not an operator-facing new flow (a crash fix on an existing render path), so no rung-3 scenario.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: fold the boundary-identity rule into the thread-screen / conversation-repository overviews where #645's pair-only rule is described.

## Open questions

- None outstanding; the Technical Note's `occurred_at` question is resolved above from the daemon source.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No findings. Every boundary still enters through one decoder, `SessionTransitionPayloadDto.toBoundary`, for both the live arm (`decodeSessionTransition`) and history (`withHistoryEntry`); the only other producers are `FakeConversationRepository`'s seed and `ThreadScreen` previews. The ids and `occurredAt` are daemon-supplied and reach only the dedup predicate and the list key, never a log, URL, path or markup. The key is the one place daemon strings are concatenated, and the plan length-prefixes both ids so a hostile id containing `->`, `:` or `@` cannot forge a second triple's key; `Instant.toString()` is injective and sits last. Namespaces (`msg:`, `boundary:`, `unrecognized:`, `queued-row:`) stay disjoint by literal prefix.
- [Trust boundaries] No findings on the dedup direction. A hostile daemon repeating a triple with a different `reason` or `workspaceCwd` is collapsed to the first row, because `holdsBoundary` and the key read the same three fields. Fail-safe direction: a dropped delimiter, never two rows with one key.
- [Tokens / storage / IPC / crypto] Not applicable: the change adds no token, no file, no intent, no primitive; it edits an in-memory predicate and a string key.
- [Network & I/O] SHOULD FIX (accepted, not gated): the live append now scans the conversation's thread once per `session_transition` (O(n)), as the history path already does per page row. A daemon flooding transitions grows the thread without bound, but it could already do that with any row kind; the scan adds a linear factor, not a new vector. No cap added here.
- [Logs] No findings: nothing new is logged; the session-transition arm keeps its "drop silently" posture.
- [Concurrency] No findings: the hold check runs inside the existing `threadByConversation.update` CAS, so check-and-append is atomic against the other writers; returning the unchanged map on a repeat emits nothing (StateFlow conflation).
- [Threat model] OUT OF SCOPE: cross-reconnect replay of boundaries (a reconnect re-delivering an old transition) stays the deferred #402 concern; the triple identity happens to collapse an identical replay, but no reconnect-specific guarantee is claimed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-23

## Revisions

- **2026-09-23, during build.** `HistoryPageReducerTest.reduce_boundariesSharingASessionPair_yieldOneRow` also pinned #645's pair-only rule, within a single page (`withHistoryEntry` routes through `holdsBoundary` too). Flipped to `reduce_boundariesSharingASessionPairButNotAnInstant_yieldTwoRows`, with a sibling `reduce_boundariesSharingPairAndInstant_yieldOneRow` keeping the within-page dedup proven. No design change.

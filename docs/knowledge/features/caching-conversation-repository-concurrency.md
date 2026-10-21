# Caching conversation repository — state and concurrency

Split from [Caching conversation repository](caching-conversation-repository.md) for #2333.

## State and concurrency

**Authoritative restore and retirement (#2149).** Restore captures the store generation before
disk I/O. The file cache validates its envelope and saved record on its I/O dispatcher.
Conversion of raw saved items into the store snapshot, merging and ordering then run on
`processingDispatcher` under `historyWrites`, rechecking deletion and the captured generation
before application.
The store rejects mismatched epochs, reset-needed state and any runtime reset generation,
including reads begun after a reset that reused the epoch. A pristine store adopts saved
certification; merging keeps newer live revisions and absent live facts, and an existing live
checkpoint/coverage wins. Repair derives from merged applied progress and retained checkpoint,
plus current repair requests: stale partial disk progress cannot reopen repair after newer live
certification. Disk does not supply runtime generation or repair flags.

An accepted exact-owner, generation-checked restore can initialize the shared authoritative
baseline under `historyWrites` before any observer save succeeds. It records the validated
committed disk snapshot with capture zero, never the merged live view or a successful live capture.
Without this baseline, explicitly removing one fact from an unchanged partial view rewrites all
retained owned payloads. Restore unions same-incarnation fences and cannot restore certification
for retired identities; old-incarnation restores reject.

Authoritative candidates carry store generation and wrapper capture sequence. Inside the same
`historyWrites` lock used by legacy row/position writes and deletion, persistence rejects deleted
destinations, retired generations and captures older than the successful authoritative baseline.
Started atomic mutation and baseline publication finish together in `NonCancellable`; failure
advances no baseline. Older observers and final flushes cannot overwrite a newer committed record.

`removeAuthoritativeFact` takes the same lock, rechecks capture ownership/revision under the store
lock, then acknowledges only committed retirement or a proven committed superseding reset.
Cancellation before admission creates no acknowledged retirement; once admitted, persistence and
baseline publication finish together. A transient failure retains the uncommitted fence and its
recovery placement for the same capture to retry. A definitive `AuthoritativeRemovalRejected`
arrives only after rollback and carries the validated newer durable fact internally. The writer
withdraws just that matching uncommitted generation/incarnation/revision fence and restores the
fact, leaving the prior durable record/certificate intact. No failed mutation advances the baseline.

Rejection recovery preserves current and observed tie order as well as disk order. Admission
captures same-order peers, including identities hidden by other pending removals; otherwise
rejecting B then A from `[A,B,C]` can restore `[B,A,C]`. Recovery uses the nearest surviving
successor/predecessor without minting a placement move, and retains each captured order key so
different ties cannot acquire a relationship. After successful commit and baseline publication,
`commitRemovals` releases only matching recovery entries by generation/incarnation/retirement
revision. Durable fences remain. Failed work and newer removals admitted during held I/O retain
their evidence. Keeping every successful removal's copied peer list until reset would retain
quadratic history for tied or absent-order facts.

Accepted tie placement has its own store publication counter, independent of payload revision,
within the epoch/runtime generation. Captures retain this evidence through coalescing and initial
restore: saved peers can arrive behind an unchanged live identity. Under `historyWrites`, pending
placements are selected against the shared successful placement capture, including unchanged
peers in candidate order. Mutation and successful payload/placement bookkeeping finish together.
Filtering placement by payload revision loses these relationships even if single-item tests pass.
The [owned storage contract](conversation-cache-contract.md#owned-incremental-authoritative-mutations-2255)
describes atomic tie blocks, certification prerequisites and stale-page restart.

Reset retirement is accepted non-cancellably on the worker **before downstream delivery**.
Ordinary candidates retain acceptance after delivery. Cleanup joins started work, then refreshes
retirement after suspended delivery and again inside the write lock. After each successful final
mutation/bookkeeping it rechecks generation and same-generation removal fences and commits a
newer retirement before returning. Old snapshots are reconciled with current fences inside the lock.
Checking only before lock acquisition or mutation misses resets arriving during lock waits or
final I/O after collection has ended. Cleanup stops on mutation failure with static reporting;
it does not loop on failed storage or promise persistence through force-stop.

`resetAuthoritativeThread` returns `Result<Unit>` after durable retirement and commit, including
when a new runtime generation reuses the same epoch string. Supersession alone
cannot acknowledge reset: a rejected candidate succeeds only when the committed baseline proves
that generation or a later one reached disk. A superseded reset whose replacement is still held
or failed reports a content-free failure (`event=authoritative_cache_reset_failed`);
a failed mutation preserves the last successful saved record.
Confirmed deletion marks the destination tombstone before waiting for started work, clears both
families and baselines, and fences captured restores plus pending/in-flight/final writes. Refused
daemon deletion preserves saved facts; the same conversation id under another host is unaffected.

Real-file probes in `AuthoritativeThreadCacheTest` check exact fresh-instance items, epoch,
checkpoint and coverage. In particular, `remoteResetDuringFinalFlushIoPersistsRetirementBeforeCleanupCompletes`
holds final I/O and its retirement replacement, while
`restoreDecodingAndMergeWaitForProcessingDispatcherWhileMainProgresses` controls worker traversal
and caller progress. Count or successful-return assertions alone miss these boundaries.

**Legacy snapshot processing.**

Each cold snapshot flow owns its merge base, ordering, last drawn rows and a structured
`coroutineScope` with a writer child on `processingDispatcher`. Sequential worker hops finish
processing each captured snapshot before taking another; merge generations are never conflated
or cancelled in favour of newer input. Only pending disk work is conflated. The collection's
bounded timer signals readiness; it does no merge or cache-policy work. No application or
repository scope owns the writer.

Only rows are replaced in the emitted snapshot; suppression, unsigned order and read evidence
remain those of the captured generation. Restored rows create no sight claims, history requests
or read commands. The list-only observer compares rows on the worker and emits distinct lists
on the collector. Snapshot generations are allocated at upstream capture, before worker
processing can suspend; held drawn rows carry that generation into coverage saves. Coverage
capture precedes suspending `first()` cleanup, fencing newer accepted/delivered/completed work (#2254).

The wrapper's `historyWrites` mutex serializes observer writes, coverage-null position writes,
the complete coverage row/state operation and confirmed deletion. Lock order is wrapper,
migration-aware cache, storage mutex, then Room transaction (or file cache for replacement-only
implementations), with no callback into the wrapper. Both row writers publish their successful
`persistedThreads` baseline under this lock; it survives collection restarts. A writer also
remembers its completed candidate by identity, so cleanup cannot repeat that completed write
after another collector persists newer rows. Later candidates still compare against the shared
successful rows, not an obsolete collection-local baseline.

Ordinary successes also advance the shared capture fence (#2253). Each owned candidate carries
the capture of each actual row change and removal; only intents newer than that fence are eligible.
A later snapshot cannot renew unchanged stale text from an older collector after reconnect. On
success, baseline application preserves newer content the candidate did not change and unowned
rows in their neighbour slots. Failure advances neither baseline nor fence. Ownership indexes
initialize lazily after cached first draw on worker acceptance, only for capable caches; doing
that traversal for replacement caches doubled the existing 100000-row processing budget.

Pending row intents retain original captures. Coverage merges them without pre-trimming, with
newer intents taking precedence, and satisfies only contributing captures. Both row writers
conditionally retire satisfied intents, preserving concurrent replacements and failed retries.
Acceptance refuses satisfied work; evidence failure cannot restore retired payloads.

Lock acquisition and snapshot processing remain cancellable. Once either row writer starts a
mutation, the mutation and its successful baseline update finish together in `NonCancellable`
under `historyWrites`. Atomic replacement can commit before a cancellable dispatcher return
delivers success: joining a cancelled writer alone would otherwise leave disk and baseline
inconsistent and could skip a newer removal during flush. Failed writes update no baseline.
Confirmed deletion tombstones before waiting; host forgetting
waits for held cache work, removes it and refuses delayed/final saves through host retirement.
Neither path can be undone by cleanup or restart; colliding ids under another host remain exact.

Normal upstream completion, upstream failure and collector cancellation run non-cancellable
cleanup: cancel/join the timer and writer, then attempt the latest accepted pending candidate
once unless already completed, persisted, satisfied by coverage or deleted. Cleanup waits for
actual I/O, leaves no orphan writer and never loops on storage failure. Cancellation then
propagates. A snapshot interrupted before acceptance creates no flush work. This guarantees
orderly cleanup with successful storage, not persistence through force-stop or process death.

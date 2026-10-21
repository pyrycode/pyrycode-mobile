# #2437: local history state follows its reader generation

## Scope

Repair the independently confirmed Retry/reset defect in retained #2334 candidate
`b7724753efb9cc9848cdea2bbe5ebb3370bb71e8`. A failed local demand may retain its
boundary and Retry only while that saved reader still owns the current runtime generation.
An authoritative reset, including reused epoch text, invalidates that local state. Current
daemon Loading, successful response and exhaustion must then reach the existing ViewModel
presentation. Keep the parent feature and its historical evidence; this is its bounded
blocking repair, not a replacement implementation.

## Implementation plan

- In the repository, publish local load changes only for the current reader and eligible
  runtime generation. Serialize replacement, status publication and cleanup by conversation
  ownership so an obsolete completion or finally block cannot clear a replacement's state.
- Registration invalidates a previous reader's local status and cancels its local work.
  Runtime generation advancement retires the old cursor, cancels its hydration and clears
  Loading/Retry before delivering the reset snapshot. End-of-observation cleanup clears
  either state only if that observation still owns the reader slot.
- Keep ordinary failed-page retry, held rows and its seek boundary unchanged before reset.
  Use the existing daemon coordinator after reset, without adding requests or changing its
  certified continuation. No ViewModel change is expected.
- Restore the opening test's metadata bound after predecessor lookahead removal, and remove
  the unused `AuthoritativeDao.indexItems` query. No schema or wire change.

## Verification

First retain the failure of a production disk/repository/ViewModel regression: saved opening,
failed local demand, settled Retry, reset with reused epoch, successful daemon newest and older
responses, and exhaustion. Assert the current tail, exact facts, request counts and no page at
rest. Preserve the existing failed-boundary retry and held-read cancellation tests; cover
reader replacement so old cleanup cannot erase a new owner's state. Run affected paging,
opening, repository/ViewModel and storage checks through normal Linux admission, followed by
format/build/pre-verification and the existing six native saved-thread methods. Independent
complete configured acceptance remains required; prior green gates do not accept this fix.

## Security review

**Verdict:** PASS

- [Ownership] Every local status publication and invalidation must check the exact current
  reader; runtime generation advances independently of reused epoch text. Previous-reader
  completion and cleanup cannot publish into the replacement's slot.
- [Trust and certification] A reset cannot restore or certify stale saved facts. This changes
  local status lifetime only; daemon coverage, checkpoints and request ownership remain with
  the existing store/coordinator. No local cursor becomes a daemon cursor.
- [IO and cancellation] Cancellation retains coroutine cancellation semantics. Failed local
  reads before reset retain their retry boundary; reset and exit revoke that ownership.
  No additional disk traversal, background history walk or unsolicited network request.
- [Data and surface] No schema, credentials, cryptography, paths, exported Android surface,
  layout or rendering changes. Diagnostics remain static outcomes and numeric counts only.

Reviewer: manual maintainer self-review; independent review still required. Date: 2026-10-22.

## Documentation handoff

Record the final generation/reader-owned local status contract and exact accepted repair
candidate/results in `authoritative-reader-paging.md`. Preserve the parent contract links,
historical failures, configured counts and full native fixture/timing evidence. Closing this
child does not complete parent #2334's acceptance or documentation stage.

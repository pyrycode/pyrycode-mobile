# #2437: local history state follows its reader generation

## Scope

Repair the independently confirmed Retry/reset defect in retained #2334 candidate
`b7724753efb9cc9848cdea2bbe5ebb3370bb71e8`. A failed local demand may retain its
boundary and Retry only while that saved reader still owns the current runtime generation.
An authoritative reset, including reused epoch text, invalidates that local state. Successful daemon responses and exhaustion must then reach the existing ViewModel
presentation. Daemon query ownership is asserted through exact request counts; it does not
set the legacy/local history-tail Loading flag. Keep the parent feature and its historical evidence; this is its bounded
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

## Manual verification receipt

On Linux source `a0b55d71a`, both new regressions passed, as did all 178 affected JVM methods,
lint/build/instrumented compilation/format and all six existing native saved-thread methods
(`ui-x6u8ru_r`), with zero failures/errors/skips. The original real red and subsequent fixture
corrections remain retained. Exact source/APK/image identities, named methods, bounds, timings
and evidence locations are in the [paging companion](../../knowledge/features/authoritative-reader-paging.md#manual-repair-checks-before-independent-acceptance).
Main `4cf670c96` was subsequently merged normally through the parent branch, retaining all
catalog entries; its changes are unrelated test helpers/probes and documentation. Final
pre-verification and independent complete configured acceptance remain required on the final
candidate. The security verdict and bounded ownership design are unchanged by that integration.

## Application-boundary rework after independent review

Independent run `450817178f` rejected `d81339fd3`: a validated local page blocked
on the store monitor could apply after replacement cancelled its hydration. The probe
retained 200 initial facts and observed 400 without replacement demand. The complete
configured gates were green and remain historical evidence, not acceptance of this repair.

The remaining causal fix serializes reader registration and owned cleanup with the store
monitor already used for restore. Under that same monitor, restore must check captured
reader identity and coroutine cancellation before changing either store or persisted
baseline. This covers a synchronous monitor wait after the final cancellable suspension;
a cancellation check after mutation is insufficient. Keep validation and disk IO outside
the monitor. Preserve the existing history-writes-before-store lock order and do not add
suspending work under the store monitor. Apply the same reader guard to observation-owned
supplement restores and serialize the local cursor completion against replacement.

Retain the independent monitor-boundary regression in the production paging test class,
using the blocked restore symbol rather than a source line number. Assert no retired-page
facts, opening rows or baseline escape, and that replacement demand still applies its page.
Keep all earlier retry/reset/ownership/generation tests. The duplicate reopen timing event
will be named for its distinct completed assertions.

Security review for this delta: **PENDING** until the failing boundary regression passes.
The existing status-ownership review did not prove application ownership. No schema, wire,
network, payload-read bound, timing target or native fixture changes are intended.

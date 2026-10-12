# Send now pointer reveal

## Observed failure

The full diagnostic capture on 2026-10-12 reproduced the queue-removal timeout on
Mobile adb2b069f with daemon 9c9cd5a25. Before the single tap, the Send now control
occupied vertical pixels 1601 through 1727. The composer began at pixel 1540.
The tap center was therefore behind the composer. Compose still called the node
displayed. The app recorded neither receipt nor rejection of the Send now action.
The phone and second client remained connected. This capture localizes the missing
action before the daemon send path.

The baseline tap uses Compose performClick. The diagnostic uses its one physical
pointer equivalent and adds geometry plus payload-free action receipt logging.
It does not retry the action. The diagnostic-only production logging is not part
of this repair.

Private raw logs and hashes are retained in the Mobile agents evidence folder
mobile-recovery-diagnostics-20261012. The full diagnostic suite executed 65 tests: 64 passed, one failed, none skipped. This is a
newly localized occurrence of #2056, not proof that every historical timeout had
the same cause.

## Repair plan

Use the existing viewport reveal helper before one real Send now tap. It checks
the current header and composer bounds and scrolls the target between them. Keep
the original held turn, queue-removal check, marker delivery, completion count,
message ordering and absence of a queued duplicate.

A shared screen regression places the real queued control behind the real composer.
It requires the same test action to send the owned queued ID exactly once and never
invoke Cancel. Run it against the original action first, then the repaired action.
Verify the focused live and scripted send-now scenarios before the required full
live gate. No retries or larger deadlines.

## Controlled validation

The shared screen regression failed with the original performClick action: the
expected queued ID was never sent. It passes with the reveal helper. All 19 focused
queued routing, geometry and new pointer tests pass. Android-test compilation
passes. See the content-free [evidence record](../../../app/src/androidTest/assets/send-now-2056/evidence.txt).
The complete debug unit suite passed all 5503 tests with no failures or skips.
Lint, debug build, formatting, docs guard and pre-verification passed. Fresh device
checks and the required full live gate remain pending.

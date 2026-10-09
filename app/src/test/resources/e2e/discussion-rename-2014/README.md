# Delayed discussion rename (#2014)

The fresh constrained-scheduling failure identifies the fixture's `delay(200)` as the unfinished operation. In [diagnostic-background-exact.xml](diagnostic-background-exact.xml), the opening effect starts at virtual time 48, but the helper's 1,000 ms wall-clock deadline expires with virtual time only 160. The timer needs time 248; the dialog is absent, the composer is empty, and submissions are zero. This reproduces the same failed stage as the historical reports, but does not establish that their anonymous failures had the identical scheduling trigger.

The constrained command is `taskpolicy -b ./gradlew testDebugUnitTest --tests de.pyryco.mobile.e2e.DiscussionRenameTest.delayedDialogDoesNotReplaceComposer --rerun --console=plain --no-daemon`. It lowers only this execution's priority and uses a separate daemon so the test worker inherits that priority. No extra load, sleeps, retries or deadline changes are used to obtain a passing result. Ordinary unchanged baseline and diagnostic runs passed; their XML is retained too.

The repaired fixture holds Compose time until the helper observes the labelled dialog field is absent. It proves the composer is focused and empty, then explicitly drives the fixture timer. The helper still edits only the labelled field, submits once, and requires dismissal and an external renamed title. Existing callers leave the new fixture hook unset.

| Evidence | Executed | Passed | Failed | Errors | Skipped | Exit |
| --- | --- | --- | --- | --- | --- | --- |
| [Constrained before](diagnostic-background-exact.xml) | 1 | 0 | 1 | 0 | 0 | 1 |
| [Constrained after](after-background-exact.xml) | 1 | 1 | 0 | 0 | 0 | 0 |
| [Committed exact method](final-exact.xml) | 1 | 1 | 0 | 0 | 0 | 0 |
| [Committed full class](final-class.xml) | 5 | 5 | 0 | 0 | 0 | 0 |
| [Existing RenameDialog coverage](existing-rename-dialog.xml) | 14 | 14 | 0 | 0 | 0 | 0 |
| [Wrong-field negative control](composer-negative-control.xml) | 1 | 0 | 1 | 0 | 0 | 1 |

The negative control temporarily replaces the labelled in-dialog matcher with a focused editable-field matcher. It fails awaiting Save with composer length 12, no dialog, and zero submissions, proving the repaired regression still detects composer contamination. The control is absent from the committed implementation.

[after-exact.xml](after-exact.xml) is a failed first repair attempt, not acceptance: one manually advanced frame had not yet started the opening effect. The final fixture advances until the timer-armed predicate instead of guessing a frame count.

[execution.json](execution.json) records each retained run's command, process exit, tested commit, dirty paths, exact dirty-tree patch where present, XML hashes, method names and counts. Identical dirty trees share a patch. All runs use `--rerun` so the selected test task executes freshly. Final exact and class acceptance ran at `000c1410afe2d00e0e290eb2526f04076a431ca1` with no uncommitted changes. Historical XML remains linked from [PR #1950](https://github.com/pyrycode/pyrycode-mobile/pull/1950).

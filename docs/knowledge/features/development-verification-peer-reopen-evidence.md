# Development verification — peer-turn reopen evidence

Part of [Emulator and real evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence).

### Peer-turn reopen sweep resolution (#2318)

A focused pass after a sweep failure does not establish suite-order independence.
For `RecordedMobileSuiteTest#interactiveTurn_peerStartedTurn_continuesOnPhone`,
the separate post-repair main sweep supplies that ticket's complete-sweep evidence;
one passing sweep still makes no universal ordering guarantee.

The original 30000 ms reopened-user timeout matched #2317's failed baseline.
Cold assertion collections sent backfill and consumed the strict recording's
reopen request before the application reopened. An extra cold-read experiment
also failed; scrolling alone could not repair request admission. Offscreen
retained-data loss was not established. The merged repair observes the
application's existing subscription, requires a new active subscription on reopen
and preserves exact user uniqueness and the same completed reply's ownership,
identities and content. Count the user while displayed before revealing each
assistant row, then revalidate current evidence. See
[passive assertion contracts](../../recorded-tests.md#passive-thread-assertion-evidence-2317)
and [thread testing](thread-screen-testing.md#passive-reopen-evidence-2317).

**Counted Linux evidence, 2026-10-19.** Documentation read the retained focused
XML and the counted XML embedded in both sweep logs and this ticket's UI gate log.
The [accepted verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/2320#issuecomment-6276801534)
confirms the revision mapping and phone diagnostics. All rows have zero errors;
executed excludes skipped cases.

| Run | Revision | Executed | Passed | Failed | Skipped |
| --- | --- | ---: | ---: | ---: | ---: |
| Original main sweep | `d20e92cd4b5b58ef0ce6df1f87ca8b2c297b7699` | 1793 | 1792 | 1 | 2 |
| #2317 baseline (plan-only; original product source) | `1b368c9f8` | 1 | 0 | 1 | 0 |
| #2317 cold-read experiment | `2f36172fa47944defb6344127d47f784aad26705` | 1 | 0 | 1 | 0 |
| Post-repair focused method | `e729ec077d0c0b7bfdaa0caaeafb77d12f52eaf2` | 1 | 1 | 0 | 0 |
| Configured UI gate | `de7a5de794f2bbcfb6bb37a5005362978e7083bb` | 335 | 335 | 0 | 1 |
| Dispatcher post-repair main sweep | `dde1b41a6fde1dd0e545f9ad0e357b6256092fc8` | 1793 | 1793 | 0 | 2 |

The focused application/test source matches the merged repair in the table. Its command was
`python3 scripts/android-test-gate.py ui --tests de.pyryco.mobile.recording.RecordedMobileSuiteTest#interactiveTurn_peerStartedTurn_continuesOnPhone`.
The configured gate used
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`;
both main sweeps used
`UI_GATE_FULL=1 UI_DEVICE_ALL=1 python3 scripts/android-test-gate.py ui`.
Each repaired run passed the named method once with zero skips. Focused and
configured-gate phone diagnostics confirm initial completion, one retained reopened
user and reopened reply display through the current subscription. The UI gate
skipped only `RenameDialogCaptureTest.renameAtFigmaViewport`; both sweeps also
skipped `ComposerPasteTest.pastingAnImageUri_reachesTheAttachmentPath_andLeavesTheDraftEmpty`.

The reported sweep regression is resolved with navigation, strict replay inputs,
recording bytes, assertions and bounds preserved. Earlier failures remain failed.
Other operating systems are outside scope; no fresh Claude capture ran.

The [checked evidence summary](../../../app/src/androidTest/assets/peer-reopen-2318/evidence.txt)
records diagnostics and hashes. Private raw evidence was retained under
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2318/builder-20261019-post-repair/`:
`focused/dispatcher.xml`, `original-sweep/stdout.log` and
`post-repair-main-sweep/stdout.log`, with accompanying stderr and source records.
The accepted verifier bundle, run
`7d3dc88d0f44ecfd77e0f642bef9963b4e309506cee0f7da07e3ee22a9b767a8`,
retains counted UI XML in `logs/verifier-gate__2318_5.log` after gate-worktree
removal. Paths describe evidence retained at review time.

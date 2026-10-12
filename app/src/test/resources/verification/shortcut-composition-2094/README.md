# Shortcut composition scheduling evidence (#2094)

The responsible scheduling component is the instrumentation Compose rule's default
unconfined composition dispatcher. Worker completion can resume startup inline;
the test frame/apply interceptors can then compose navigation on that worker.
Supplying `StandardTestDispatcher` queues this work for the rule's Main-clock pump.
Production routing already makes its final navigation turn on Main and is unchanged.

Every run manifest records its command, exit status, revision, source XML digest,
counts and exact test-source dirty patch digest. Apply the adjacent zero-context `.patch` with `git apply --unidiff-zero` to
that revision to reconstruct the tested test source. The original context-diff digest is also retained; zero-context patches avoid
blank context-line whitespace and reconstruct identical tested source. Other dirty files are retained
evidence and plan notes; no other executable source changed. XML retains testcase
identities, outcomes and static stack frames, omitting system output and arbitrary
exception messages. Observation files retain only fixed events, thread
classification, clock values and timestamps. Raw report/log hashes identify the
original artifacts without publishing their unrelated application data.

| Run | Executed | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: |
| `baseline-full` configured device-only UI gate | 259 | 259 | 0 | 0 | 1 |
| `baseline-isolated` original shortcut method, same tree | 1 | 0 | 1 | 0 | 0 |
| `regression-red` minimal held startup producer, default rule | 1 | 0 | 1 | 0 | 0 |
| `regression-held-red` bounded Main-hold investigation | 1 | 0 | 1 | 0 | 0 |
| `regression-apply-red` Main-hold plus worker apply investigation | 1 | 0 | 1 | 0 | 0 |
| `regression-green` minimal producer, dispatching rule | 1 | 1 | 0 | 0 | 0 |
| `launcher-green` original shortcut method, dispatching rule | 1 | 1 | 0 | 0 | 0 |
| `class-green` final formatted affected device class | 2 | 2 | 0 | 0 | 0 |
| `routing` existing JVM IO-backed routing regressions | 15 | 15 | 0 | 0 | 0 |

The baseline full and isolated runs used
`3918ef87d14d6add0013c9f4d5538c0d22d87c5e` with no executable-source patch.
The full gate's recorded 260 cases include one ignored capture. Its original
shortcut method explicitly passed; the isolated method explicitly failed with
`Method addObserver must be called on the main thread`. That fresh stack connects
DataStore completion, the test apply/frame interceptors and `NavHost.setGraph`,
as does the historical stack at `0afc55b1057b3d9aa883076e4258be74a4c4cc04`.
The original retained verdict is
[PR #2081](https://github.com/pyrycode/pyrycode-mobile/pull/2081#issuecomment-6112403561).

Both baseline launcher startup sequences began on Main and logged ready on
workers, before teardown. The full-run ordering neighbours are retained in its
observations. Instrumentation shares its application graph across methods, but
this isolated reproduction establishes that prior-method state is not required.
Neither comparison attributes the historical failure to a particular preceding
case or proves its exact interleaving. Cleanup-time focus is not used as evidence.

The minimal regression holds a Main-started producer, proves the held UI, then
completes it on an owned worker. With the default rule it deterministically failed
its startup Main assertion: the continuation resumed on that worker at clock 16,
while the graph was later observed on Main at clock 32. Holding Main and explicitly
delivering snapshot apply notifications did not strengthen graph reproduction;
those variants are diagnostics, not the shipped fixture. The real launcher failure
separately proves the graph/lifecycle violation. With the supplied dispatcher the
same minimal fixture passes: worker completion is off Main, startup resumes on
Main at clock 16, and the real Navigation Compose graph composes on Main at clock
32. All graph observations are checked, and observation count is capped at 16.
The repaired original launcher method records both startup-ready events on Main.

The original OS publication, target, categories/MIME, label sanitization, artwork,
persisted task-replacing Intent, picker absence and unchanged draft assertions
remain intact. There is no retry, deadline increase, ignored method, global Main
replacement or disabled lifecycle enforcement.

The repaired full configured UI gate handoff is **complete**. The
[verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2110#issuecomment-6115101072)
records `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui`
on `ada13ce7e0091da85495876e5a6fbdb4f331b65b`, exit 0:
**260 executed, 260 passed, 0 failed, 0 errors, 1 skipped**. Both
`de.pyryco.mobile.ui.conversations.share.SharingShortcutsDeviceTest#dynamicTargetHasShareContractAndLauncherIntentOpensWithoutChangingDraft`
and `#workerCompletionKeepsStartupAndNavigationOnMainThread` executed and passed,
neither skipped. This was a fresh full configured run, separate from the baseline
and focused checks above.

The original fresh report in the verifier worktree was
`app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/TEST-pixel2Api33Atd_0-_app-.xml`,
timestamp `2026-10-11T23:20:51`. Complete sanitized XML is retained in
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2094_7.log` and
`/tmp/verifier-2110/dispatcher-ui.xml`, SHA-256
`b65932aa79675b5d751bf53ca6b33975df9406a4938eb6c87abcf92256feb28a`.
The dispatcher pass record binds the gates to that commit; the verdict records
its location and the companion scripted result (22 executed/passed, zero
failures/errors/skips). #2057 still requires its own fresh gates and real-Claude
acceptance after this repair lands.

# Development verification

Shared verification guidance for Kotlin, Jetpack Compose and the Android
emulator. Read the current source and the owning feature topic before applying a
historical lesson.

## Map

Split on 2026-10-02 to keep this document under the 50000-byte cap the docs guard
enforces. Each section named below moved verbatim, heading and anchors intact,
into its own document:

- [Development verification — change surface, Gradle gates and where a test goes](development-verification-gates.md) —
  `Establish the change surface`, `Gradle and source checks`, `Where a screen test goes`, `Device gate`
- [Development verification — Compose evidence](development-verification-compose-evidence.md) —
  `Compose evidence`, `Probe the evidence itself`; `Compose evidence` also covers the shared
  `ViewportRule` / `@Viewport` and the `design-1220/` capture harness (#1430)
- [Development verification — test scheduling and harnesses](development-verification-test-scheduling.md) —
  `Test scheduling and harnesses`
- [Development verification — JVM logging, emulator and real evidence](development-verification-emulator-evidence.md) —
  `JVM logging and formatting`, `Emulator and real evidence`, `Documentation evidence`, `Archive refresh regression`

The managed-device guidance below records the affected-host renderer validation;
the linked topics hold the broader verification guidance.

## Managed-device verification: HP renderer workaround

Emulator disappearance needs correlated instrumentation, UTP and host-process
evidence before it can be attributed to a test assertion. In [#2051](https://github.com/pyrycode/pyrycode-mobile/pull/2079),
the dark-only baseline on `build-hp-1` at
`969d9c3c8847b8c99af3a8573750f8429a72a1eb` started `fullSizeDarkFrames`, then lost
the emulator with SIGSEGV on the `auto-no-window` rendering path. Instrumentation
completed 41 of 259 expected methods; the 42nd XML record has an empty failure,
and UTP reports device-not-found. The captured instruction read an unmapped
address. This establishes emulator loss and host rendering-path ownership;
it does not establish a scanner assertion failure or the exact native defect.

The operator installed the SwANGLE workaround on 2026-10-10 in HP's emulator
launcher/container environment. Its read-only launcher mount enforces `-gpu swangle`.
The separate rollout boot identifies ANGLE with SwiftShader; that identity is
not a fresh focused-run renderer sample or evidence of a scripted-suite pass.
Mobile source, scanner assertions, capture retries and time bounds were unchanged.
A Mac pass alone cannot validate an affected-HP workaround.

[The retained HP report](../../../app/src/androidTest/assets/scanner-2051/hp-evidence.txt)
records UTC intervals, static instrumentation/device stages, commands, admission
and completion, original paths and launcher identity. Its adjacent `hp-hashes.txt`
distinguishes original and retained SHA-256 hashes; `hp-source-hashes.txt` records
scanner/capture/runner/build input equality. Original HP artifacts are under
`/home/juhana/pyrycode-runtime/work/Projects/.hp-emulator-diagnostics/`, in
`dark-only-20261010/`, `renderer-rollout-20261010/` and
`ticket-2051-focused-20261011/`. Count-derived XML preserves case identity and
outcome while removing payloads and stack bodies.

All HP rows below use the pinned revision above and `pixel2Api33Atd`. Recorded
means XML cases; executed excludes skipped cases. The baseline's incomplete
failure record still does not prove completed execution of that method.

| HP result | Recorded | Executed | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Default-renderer interruption | 42 | 42 | 41 | 1 | 0 | 0 |
| SwANGLE comparison, 2026-10-10 18:28–18:38 UTC | 259 | 258 | 257 | 1 | 0 | 1 |
| SwANGLE confirmation, 2026-10-10 18:42–18:54 UTC | 259 | 258 | 257 | 1 | 0 | 1 |
| Post-install class, 2026-10-10 20:21–20:24 UTC | 3 | 3 | 3 | 0 | 0 | 0 |
| Standalone enlarged method, 2026-10-11 09:31–09:32 UTC | 1 | 1 | 1 | 0 | 0 | 0 |

Both historical suites passed `fullSizeDarkFrames`, `compactDarkFrames` and
`compactEnlargedTextFrames`, but failed
`SavedThreadFirstDrawDeviceTest#savedThreads_firstNewestDrawWithinOneSecond_offlineAndHeldNewest_firstOpenAndReopen`
and skipped `RenameDialogCaptureTest#renameAtFigmaViewport`. Each exited 1:
these prove suite completion without emulator loss, not a passing gate.
[#2050 / PR #2066](https://github.com/pyrycode/pyrycode-mobile/pull/2066) is prior
saved-history repair context; any recurrence remains a separate gate failure.
Retained XML is `hp-suite-comparison-counted.xml` and
`hp-suite-confirmation-counted.xml` in the evidence directory.

The exact standalone `ScannerFrameTest#compactEnlargedTextFrames` result is in
`hp-focused.xml` and `hp-focused-counted.xml`; the class result is in
`hp-class.xml` and `hp-class-counted.xml`. Focused Gradle exited 0, while the
original collector exited 1 because it received `class#method` instead of its
class-only filter. Keep that error visible in `hp-focused-run.json` and
`hp-focused-validation.json`: exact-method raw XML and completed instrumentation
establish the pass, independently of the collector. Corrected collection did not
rerun the device test. Reuse the class result only with matching source/configuration
hashes and execution flags: one shard, disabled animations and forced execution,
under the same device coordination. The verifier confirmed those inputs still
match the reviewed branch. Earlier Mac 1/1, class 3/3 and gate-profile 3/3 comparisons
at `cc47cef876cc4429986e933fec5912e7de12fcf3` remain in `evidence.txt`, `focused.xml`,
`class.xml` and `gate-profile.xml`; they do not substitute for HP execution.

### Current dispatcher gate evidence

The [final verifier's counted XML review](https://github.com/pyrycode/pyrycode-mobile/pull/2079#issuecomment-6108239743)
and dispatcher gate report confirm passing configured gates on
`Juhanas-MacBook-Air.local`, `pixel2Api33Atd`, at
`ed9a5abc1630d50e2913f87a4b5ace6b0f5d6b8f` (pass recorded
2026-10-11T10:47:18.260Z). Both commands use
`ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py`:

| Mode | Exit | Recorded | Executed | Passed | Failed | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `ui` | 0 | 259 | 258 | 258 | 0 | 0 | 1 |
| `scripted-all` | 0 | 22 | 22 | 22 | 0 | 0 | 0 |

All three scanner methods executed/passed in the UI suite. Only the existing
rename capture skipped; saved-history did not recur. Artifact paths, relative to
`/Users/juhanailmoniemi/Workspace/Projects/.pyrycode-worktrees/pyrycode-mobile/verifier-2051/`,
are `build/dispatcher-tests/ui-_1kvtge0/dispatcher.xml` and
`build/dispatcher-tests/ui-_1kvtge0/0-TEST-pixel2Api33Atd_0-_app-.xml`
(XML timestamp `2026-10-11T10:44:08`), plus
`build/dispatcher-tests/scripted-all-z37ziru_/dispatcher.xml`.
These counts are attributed to the verifier and dispatcher: their original gate
worktree artifacts were unavailable for documentation-stage re-parsing.

The full scripted suite executed/passed both previously failing
`DeterministicInteractiveStreamE2ETest` methods,
`interactiveTurn_seededChannel_streamsMultiDeltaReplyIntoThread` and
`interactiveTurn_seededChannel_reopenOngoingReplyShowsArrivedPrefixImmediately`.
Their fresh individual reports are `stream-0-TEST-installed.xml` and
`reopen-stream-0-TEST-installed.xml` under that scripted artifact directory.
The earlier branch/main timeouts remain historical in the retained
`rework-evidence.txt`; upstream [#1985 / PR #2076](https://github.com/pyrycode/pyrycode-mobile/pull/2076)
changed the display fixtures to direct release. Subsequent focused results each
passed 1/1 with zero failures/errors/skips at reported harness revision
`87b188751a7148374b498058bfbcb56c9ab3e912`, before the final full gate.
[Post-merge evidence](../../../app/src/androidTest/assets/scanner-2051/rework-post-merge-evidence.txt)
and its adjacent hashes/XML retain those separate runs. This does not repair
[#2078](https://github.com/pyrycode/pyrycode-mobile/issues/2078)'s legacy queued-follow-up
projection defect. Neither current gate success nor HP completion proves an exact
native defect fix. No real-Claude scenario applies; stub-camera coverage retains
its [separate live-camera boundary](scanner-screen-edge-cases-and-testing.md#focused-verification).

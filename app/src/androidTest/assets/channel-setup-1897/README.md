# Channel setup investigation — #1897

The diagnosed test defect is a scope mismatch: setup archives only the harness host's channels,
but the old drive requires zero composed channel rows across every paired host. Another host's
channel can legitimately remain visible and prevent this wait from ever completing. Lazy-list
composition also means the global tag count is not a reliable membership check.

## Historical observation and limits

`historical-stack.txt` retains the original Compose timeout at the global-zero-channel wait in
`interactiveTurn_createEditArchiveChannel_readsPromptBack`, before tapping Channels plus. Mapping
the stack against feature `f3728e7752` and main `e4ecbb099d` confirms the operation: the preceding
`hostConversationIds` check has already accepted an empty target-host Channels list; the next wait
tests `TREE_CHANNEL_ROW_TEST_TAG` without a host qualifier.
`historical-drive.txt` retains that source excerpt from file blob
`4e9fcb63a58fbc6338acd4d1ee22c653adb13474` at the tested feature revision.

Original dispatcher artifacts were
`/Users/juhanailmoniemi/WorkSpace/Projects/pyrycode-mobile-agents/logs/2026-10-07T06-50-40-098Z_real-claude-gate_#1731.log`
and its `.stderr.log`; the rerun used the matching `real-claude-gate-rerun_#1731.log`.
The original stderr identifies daemon revision `6019328b378cad587f69b7bc94de37febbdf8556` and binary
`real-claude-gate-1731/build/e2e-bin/pyry`. Copied counted XML preserves both historical results:

| Artifact | Executed | Passed | Failed | Errors | Skipped | Named method |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `original-live.xml` | 64 | 51 | 13 | 0 | 0 | Failed |
| `failed-only-rerun.xml` | 13 | 12 | 1 | 0 | 0 | Passed |

The original phone semantics/logcat were not retained in these artifacts. Which host supplied the
historical remaining row, or whether a stale target-host UI row remained, cannot be established.
Another host is a demonstrated cause of this faulty drive under controlled state, and an inference
for the historical occurrence. The rerun was a failed-only selection, not a passing full live suite.

## Controlled reproduction

`EmptyHostChannelSetupTest.emptyTargetCreatesWhileAnotherSelectedHostKeepsItsChannel` renders the
real `ChannelListScreen`: target host is connected, loaded and empty; another host is selected and
holds a visible promoted channel. It asserts one global channel node and that other channel's
visibility before invoking the same drive used by the live scenario.

The extracted old drive preserves the original scroll/global-zero-wait/count-assert/tap sequence.
The test's one-second deadline shortens deterministic failure feedback; the live deadline is
unchanged. It fails at the global-zero wait, matching the historical failing operation. The repair
waits for the exact target's loaded empty snapshot, then scrolls to and clicks its own plus. The
event assertion requires exactly `TreeHostChannelAddTapped(target)` while the other host's channel
remains visible and unchanged.

Command for red and green:

```sh
./gradlew testDebugUnitTest --tests 'de.pyryco.mobile.e2e.EmptyHostChannelSetupTest' --console=plain
```

The green invocation also selected `de.pyryco.mobile.ui.conversations.list.ChannelListScreenTest`.
Tests ran over base mobile commit `fb9fdefd3` plus the test-only implementation included in this PR.
No daemon was used for this controlled Compose fixture.
`tested-source-sha256.txt` records the executed source before subsequent Spotless formatting.

| Artifact | Executed | Passed | Failed | Errors | Skipped | Exit |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `old-drive.xml` | 1 | 0 | 1 | 0 | 0 | 1 |
| `repaired-drive.xml` | 4 | 4 | 0 | 0 | 0 | 0 |
| `device-repaired-drive.xml` | 4 | 4 | 0 | 0 | 0 | 0 |

The three additional green guards reject missing, unloaded-empty and stale-nonempty target-host
snapshots. Each requires no creation event before the loaded empty update, then exactly one
target-host creation event afterward. Existing channel-list coverage passed 47/47 with no skips.

Focused device command (Android 13 `pixel2Api33Atd`, no daemon or Claude):

```sh
./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.e2e.EmptyHostChannelSetupTest' --console=plain
```

The fresh managed-device XML was copied from
`app/build/outputs/androidTest-results/managedDevice/debug/pixel2Api33Atd/TEST-pixel2Api33Atd-_app-.xml`.
Its CRLF line endings are normalized to LF; test content and counts are unchanged.

`git apply --unidiff-zero app/src/androidTest/assets/channel-setup-1897/old-drive.patch` reinstates
the old drive in an isolated checkout for reproduction; select just
`EmptyHostChannelSetupTest.emptyTargetCreatesWhileAnotherSelectedHostKeepsItsChannel` for the same
counted red. The repair is confined to test code. All downstream live functional assertions and
the scenario's guaranteed fixture restoration/deletion remain in place.

## Dispatcher full-live proof

The fresh dispatcher full suite on 2026-10-10 executed and passed
`de.pyryco.mobile.e2e.InteractiveStreamE2ETest#interactiveTurn_createEditArchiveChannel_readsPromptBack`:
65 executed/passed, 0 failed/errors/skipped. The
[evergreen verification record](../../../../../docs/e2e-interactive-stream.md#empty-host-channel-setup-1897)
identifies the tested mobile/daemon revisions and fresh dispatcher report/artifact paths.
This completes the live handoff; the historical rerun and controlled fixture remain separate evidence.

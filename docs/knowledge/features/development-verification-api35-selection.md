# Development verification — focused API35 capture selection

Part of [Development verification](development-verification.md). See also
[Compose capture evidence](development-verification-compose-evidence.md#compose-evidence)
and [test scheduling](development-verification-test-scheduling.md#test-scheduling-and-harnesses).

## Selection and loaded policy

The managed builder policy admits the exact `pixel8Api35DebugAndroidTest` task
through the existing bounded instrumentation validation. The comma-separated
selection for the list captures is:

```text
de.pyryco.mobile.design.ListDesignCaptureTest#listFramesAt412By892,de.pyryco.mobile.design.ListDesignCaptureTest#listFramesAt320By700LargeText
```

The Android launcher accepts this pair through one `--tests` value; its nested
Gradle task uses one `android.testInstrumentationRunnerArguments.class` project
property. Names must remain fully qualified and within one affected class.
Absent/empty selectors, wildcards, cross-class selections and unscoped companion
test tasks are rejected. All four property spellings participate in the same
validation and duplicate detection: `-Pname=value`, `-P name=value`,
`--project-prop name=value` and `--project-prop=name=value`. Even a duplicate
with the same value is rejected, preventing a later property from overriding
the reviewed selection. This adds no API35 Setup allowance or broader task family.
API33/connected compatibility, frozen caller-context checks, host/device admission
and cleanup remain in force. Use the managed launcher; task recognition alone
does not acquire admission or establish cleanup custody.

The Mobile agents consumer pin identifies the policy its launcher loads.
Controller readiness or a controller revision alone cannot establish that
identity. The [#2435 rollout receipt](https://github.com/pyrycode/pyrycode-mobile/issues/2435#issuecomment-6381919096)
records these reviewed identities:

| Component | Merged identity |
| --- | --- |
| [Dispatcher #337](https://github.com/pyrycode/agent-dispatcher/pull/337) | `91310c59a1c0834526ee0f2f356a80a763611566` |
| [Mobile agents pin #217](https://github.com/pyrycode/pyrycode-mobile-agents/pull/217) | `3105b6f49d4bb72859fdea020bea3ebc98810aeb` |
| `scripts/test-selection.py` SHA-256 | `98e62d3a0a76caefa4ef4bf72b5688dfefcc96a3ffd6d8faca95f183f68402d4` |

Both repaired files match independently reviewed dispatcher head
`cc66e454f75fafba0eb360f0a2a63347f0fc7a02`. The receipt reports all four
managers loaded the merged dispatcher and HP/Fractal/Mac launchers loaded the
matching consumer, dispatcher and policy hash. These are retained rollout
proofs, not newly sampled running-process state.

## Counted source evidence and pending captures

The [accepted verifier review for PR #2530](https://github.com/pyrycode/pyrycode-mobile/pull/2530#issuecomment-6382408473)
at Mobile candidate `8b5de023a6adc90bfc15f4538db29a3c8dbb9852` records a fresh
Linux run from the owning dispatcher checkout:
`PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p test_test_selection.py -v`.
It executed 13 tests: 13 passed, zero failures/errors/skips, exit 0. Its four
API35/property methods cover the exact pair, invalid/overridden selections,
frozen launcher context and all property spellings. This proves selection
behavior, not device execution.

Preserve the builder's initially reported invocation from the managed Mobile
worktree: 13 executed, 12 passed, one legacy-worker fixture error when its
synthetic root encountered the bound-caller guard. The builder then reported
13/13 passing from the owning checkout, with no policy change. Historical
command-result lines were truncated, so those totals and the working-directory
diagnosis remain attributed builder evidence; the verifier's fresh complete
13-test result establishes current Linux behavior. The owner's four context-bridge
passes and stubbed actual-launcher baseline-refusal/patched-acceptance probe also
remain attributed historical source evidence; original probe logs were unavailable.

The verifier's configured UI launcher skipped execution for this documentation
change. Its exit 0 is no device-test pass. The JVM gate executed/passed 5,547,
zero failures/skips; scripted-all executed 24, passed 19, failed five, skipped
zero. The dispatcher accepted those five as inherited failures against main
`6238e13b29f30749f530a86734d961e402ac15ea`, tracked on #2353, #2521 and #2469;
they remain failed results. None establishes API35 capture acceptance.

Support task `5493cf495c768cca7f425d3f9ddcbe284cc5b6a1664ad1cd4dd466805d4a2a23`
was withdrawn by the operator before any worker started, with zero artifacts.
Its terminal failed state represents withdrawal. Historical **0 executed / 0
passed / 0 failed / 0 skipped** remains non-passing pending evidence.

[#2418](https://github.com/pyrycode/pyrycode-mobile/issues/2418) /
[PR #2434](https://github.com/pyrycode/pyrycode-mobile/pull/2434) still owns both
fully qualified methods above on its pushed Mobile candidate. Each must execute
and pass on full API35 with real system bars, retaining fresh counted XML,
captures and viewport sidecars, glyph comparisons, source/APK/launcher identities
and cleanup evidence. Preserve existing holds and the causal dependency until
ordinary #2435 resolution. This source review neither accepts those captures nor
requests a complete Mobile profile. Other operating systems were outside this
Linux ticket's verification scope.

Accepted verifier evidence run
`6af91495a328762c7f0c88dcdfc925b90422872013a6deb28f6fa757e572880e`
retains the verdict and gate logs. Documentation read that bundle on 2026-10-24;
its selector command trace truncates the result, so the 13-test count above is
attributed to the accepted verifier verdict, not independently recounted here.
It supplies no fresh fleet or device observation.

# Focused API35 selection repair evidence review (#2435)

## Files read

- `pyrycode-mobile-agents/dispatcher/scripts/test-selection.py`: `gradle_selection`, `mobile_names`, `android_selection` and `check`; exact task admission, bounded selectors and frozen caller context.
- `pyrycode-mobile-agents/dispatcher/scripts/test_test_selection.py`: `SelectionTests`; both capture selectors, property spellings, invalid selections and context negatives.
- `pyrycode-mobile-agents/dispatcher` gitlink: merged consumer pin and source identity.
- `scripts/android-test-gate.py`: compatibility entry point delegates to the agents checkout.
- `docs/knowledge/INDEX.md` and `docs/knowledge/features/development-verification.md`: owning verification topics.
- `docs/knowledge/features/development-verification-compose-evidence.md`, real API35 capture evidence: admission and source probes cannot replace device execution or real-inset sidecars.
- `docs/knowledge/features/development-verification-test-scheduling.md`, test scheduling: retained focused evidence supplements later acceptance.

## Change

Review and retain the already shipped owner repair, as the current refined issue
requires. Dispatcher PR [#337](https://github.com/pyrycode/agent-dispatcher/pull/337)
merged as `91310c59a1c0834526ee0f2f356a80a763611566`; agents pin PR
[#217](https://github.com/pyrycode/pyrycode-mobile-agents/pull/217) merged as
`3105b6f49d4bb72859fdea020bea3ebc98810aeb`. The repair adds the exact
`pixel8Api35DebugAndroidTest` task to the existing instrumentation validation and
includes alternate property spellings in duplicate-selector rejection. No Mobile
implementation or test migration is needed. Only this review plan changes in
Mobile; no in-flight branch overlaps this new plan path.

The admitted estimate is zero additional production lines and zero Mobile
production files. This review remains one behaviour with its upstream regression,
well below every builder ceiling: no new exported types, consumer updates, state
machine branches or app acceptance criteria. The upstream repair was 109 added
and 3 deleted lines across two files; this plan and its evidence receipt are
expected to stay below 100 lines.

## Testing strategy

Run the merged `SelectionTests` on Linux with bytecode writes disabled. Verify
acceptance of the two fully qualified `ListDesignCaptureTest` methods together,
and rejection of absent/empty selections, wildcards, cross-class selections,
duplicate/overriding properties and unscoped companion tasks. Retain existing
API33/connected and frozen-context coverage. Compare local consumer/dispatcher
revisions and policy SHA-256 with the rollout receipt. Inspect merged diffs to
confirm admission and cleanup were not changed and no API35 setup allowance was
added. Run the role's format, lint, assembly and final pre-verification checks.

No new logic is authored here, so the upstream red/green regression evidence is
reused rather than reconstructing a production defect in a live checkout. The
owner's retained 13 selector passes, 4 context-bridge passes and stubbed launcher
baseline-refusal/patched-acceptance probe are historical source evidence. Fresh
Linux results will be recorded separately below and in the PR.

## Pending dependent acceptance

The rollout receipt in [the ticket comment](https://github.com/pyrycode/pyrycode-mobile/issues/2435#issuecomment-6381919096)
records loaded fleet identities and operator withdrawal of unstarted task
`5493cf495c768cca7f425d3f9ddcbe284cc5b6a1664ad1cd4dd466805d4a2a23`.
Its terminal failed state is withdrawal, with no worker or device execution.
The local source check corroborates the receipt, not fresh fleet process state.

#2418 / PR #2434 owns `de.pyryco.mobile.design.ListDesignCaptureTest#listFramesAt412By892`
and `de.pyryco.mobile.design.ListDesignCaptureTest#listFramesAt320By700LargeText` on
its pushed Mobile candidate using full API35 and real system bars. Both must
execute and pass with fresh counted XML, captures, viewport sidecars, glyph
comparisons, source/APK/launcher identities and cleanup evidence. Historical
0 executed / 0 passed / 0 failed / 0 skipped remains non-passing evidence.
Preserve that causal dependency until ordinary #2435 resolution and all existing
holds; this review does not accept captures or request a complete Mobile profile.
Other operating systems are outside this Linux ticket's verification scope.

# Confirm the landed live ping copy repair (#1895)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopy.kt`: `assertSideMessageCopy` scopes complete localized timestamps to the copied row and dismisses benign notices before the pointer tap.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopyTest.kt`: `SideMessageCopyTest` covers eight positive and negative helper regressions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` awaits the displayed reply and checks exact user and assistant source copying.
- `docs/knowledge/features/message-bubble-testing.md`: the shared helper's regression coverage and why screen-wide separator matching falsely rejects usage banners.
- `docs/knowledge/features/development-verification-gates.md`: focused shared-test execution and fresh XML counts.
- `docs/specs/architecture/1895-confirm-live-ping-copy.md`: this confirmation plan is the only intended branch change.

## Change

Confirm #1878's landed repair on current main rather than duplicate it. The initial
base is `4a523704100dba19454b5d5a8d2bfe1279e0ff45`, which contains merge
`28afd1ee`. Preserve the displayed ping reply, exact-source clipboard comparison
including its 100,000-character cap, and row-scoped timestamp checks before and
after copying. No production code, test assertions, ignores, signatures, state,
or visuals change unless fresh execution exposes a remaining scenario failure;
record any resulting design change under Revisions before implementing it.

This is one confirmation deliverable, estimated at about 40 plan lines, zero new
types, zero consumer updates, two acceptance criteria and zero new error branches.
Remote source overlaps do not block this plan-only change: no shared helper or
live-class edit is intended, and no in-flight branch supplies a required contract.

## Testing strategy

Run existing `SideMessageCopyTest` unchanged through `testDebugUnitTest` and inspect
fresh XML for eight executed/passed tests and zero failures, errors or skips.
Run lint and formatting, then merge current `origin/main`, push, and run the whole
unit/shared suite, `assembleDebug`, and `scripts/pre-verify.py --gradle` against the
PR body. No new test is needed for unchanged behavior already covered by #1878.

Dispatcher-owned live acceptance remains pending: run the full curated rung-3
gate (`all` in the PR's Live tests section), confirm the named ping method ran
and passed, and record executed/failed/skipped counts and the tested commit.
Keep `needs-real-claude`; deterministic regressions do not establish live success.

## Documentation handoff

Pending for the documentation stage: record fresh #1895 full-suite counts, tested
commit and named ping result in `docs/e2e-interactive-stream.md`, Verification
status, and `docs/knowledge/features/message-bubble-testing.md`, Testing.

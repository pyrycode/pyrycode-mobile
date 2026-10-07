# Confirm the landed live ping copy repair (#1895)

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopy.kt`: `assertSideMessageCopy` scopes complete localized timestamps to the copied row and dismisses benign notices before the pointer tap.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/SideMessageCopyTest.kt`: `SideMessageCopyTest` covers eight positive and negative helper regressions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_pingPrompt_streamsPingReplyIntoThread` awaits the displayed reply and checks exact user and assistant source copying.
- `docs/knowledge/features/message-bubble-testing.md`: the shared helper's regression coverage and why screen-wide separator matching falsely rejects usage banners.
- `docs/knowledge/features/development-verification-gates.md`: focused shared-test execution and fresh XML counts.
- `docs/specs/architecture/1895-confirm-live-ping-copy.md`: this confirmation plan is the only intended branch change.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=808-12242,
the shared Message Actions anchor from #1818, read with design context and screenshot
on 2026-10-07 for the accessibility rework. Copy is 11×12dp and Reply 13×12dp,
stacked with centres 25dp apart in a narrow column. Preserve the existing primary
tint, assets and bubble-relative centring. The invisible targets must each be
48×48dp; short message rows may grow to contain them rather than overlap neighbours.

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

## Revisions

2026-10-07 — Verifier finding 1 names the scripted background-Agent scenario's
final `child1827-after` scroll. The test closed the child run and then relied on
`Go to agent` to reopen it, although navigation preserves collapse state.
Upstream #1904's `13605325` repairs that scenario by selecting the owned child
run, asserting its closed state after navigation, and explicitly opening it
before the final ownership and unique-visibility assertions. Merge `0b688c85`
landed the repair on main; branch merge `d34b1174` already includes it. No
additional implementation, fixture, assertion weakening or skip is planned.

Compare fresh focused `background-agent` execution on this merged tree with
the reviewed main baseline `4a523704` in an isolated temporary worktree. Record
both counted reports in the PR, rerun `SideMessageCopyTest` unchanged, and
complete the final main-merged checks. The dispatcher must run fresh full
scripted and live gates before re-review and live acceptance respectively.
This revision adds only confirmation work within the original size boundary.

2026-10-07 — Re-review of `81e19e56`, findings 1 and 2, explicitly expands this
confirmation ticket to repair inherited #1818 accessibility geometry and duplicated
IME setup. `MessageActions` currently divides 73dp into two 36.5dp targets, which
violates the minimum height even though the widths pass. Use a 96dp pair with two
adjoining 48×48dp targets and retain the 25dp glyph-centre gap by placing glyphs
12.5dp either side of the shared edge. Remove the zero minimum-touch-target override.
`MessageContainer` reserves at least 96dp row height and vertically centres a shorter
bubble; the targets stay inside the row so adjacent short messages cannot steal taps.
Bubble width, timestamp toggling, source copy and reply callbacks retain their contracts.

Files read for this revision: `MessageBubble.kt` (`MessageContainer`, `MessageActions`),
`MessageBubbleTest.kt` (`assertSideGeometry`), `MessageReplyTargetsTest.kt` (`verify`),
`MessageReplyImeDeviceTest.kt` (`ime`), `TestImeRule.kt` (`select`, `apply`),
the #1818 plan and the message-bubble and verification feature overviews.
In-flight #1766 overlaps only streaming rendering and its reveal test, not these blocks.
The repair is approximately 300 written lines including revisions and tests, no new
exported type, three test-rule constructor consumers, no new failure branch and the
original two acceptance criteria. It remains one confirmation/repair deliverable.

First strengthen shared geometry tests to require both dimensions for Copy and Reply
on both roles at 320dp/412dp and while streaming; observe their height failure.
Retain midpoint/outer-edge pointer routing, exact copy and reply source, glyph sizes,
centres and timestamp isolation. Add a short-row containment/separation assertion.
Run existing bubble, palette, selection, reply staging, timestamp and thread coverage,
plus all eight unchanged `SideMessageCopyTest` regressions.

Reuse `TestImeRule` with a default-off `selectBeforeTest` option in its outer `apply`
try/finally. `MessageReplyImeDeviceTest` keeps rule order 0, selecting before the
Compose activity rule (order 1) and restoring after its cleanup, including setup
failure. Existing live/scripted consumers keep opt-in `select()` behaviour.
Run the affected device class (real keyboard/insets cannot be proven by Robolectric),
the scripted held-stream scenario and the named live ping method after the repair,
inspecting fresh counted XML. The dispatcher still owns full scripted and live gates.
No new coroutine, state, I/O boundary, dependency or product logging is introduced.

## Documentation handoff

Pending for the documentation stage: record fresh #1895 full-suite counts, tested
commit and named ping result in `docs/e2e-interactive-stream.md`, Verification
status, and `docs/knowledge/features/message-bubble-testing.md`, Testing.

Pending: revise `docs/knowledge/features/message-bubble.md`, side-action geometry,
and `docs/knowledge/features/message-bubble-testing.md`, Testing, for the 48×48dp
targets, short-row containment and minimum-dimension/pointer coverage.

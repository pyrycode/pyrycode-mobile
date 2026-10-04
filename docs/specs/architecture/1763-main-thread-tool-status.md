# Main-thread tool status (#1763)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `openToolCall`, `ThreadStatusArea` and `statusArm` select the status label and preserve the existing fallback ladder.
- `app/src/main/java/de/pyryco/mobile/data/model/Message.kt`: `ToolCall.parentToolUseId` is an empty string for main-thread calls.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/OpenToolCallTest.kt`: existing selector assertions and tool-row fixture.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/RunningToolIndicatorTest.kt`: existing status rendering, elapsed readings and idle coverage.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-how-it-works-list-and-status-row.md`: the status band retains its existing arm precedence and idle glyph.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md`: grouping depth depends on loaded parents, but status exclusion must depend directly on the parent id even if that parent is absent.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run under Robolectric and also require androidTest compilation.

## Design source

N/A: “No visual change; the status design is unchanged.” (ticket's Figma section).

## Change

Restrict `openToolCall` to running calls with an empty `parentToolUseId`, retaining the last qualifying call and its own elapsed reading. Running background-agent calls are skipped even when their parent row is not loaded. When none qualifies, return `null` so the existing status-arm logic supplies thinking, working or idle as appropriate. Update the helper's KDoc to state the main-thread contract. No new types, state, signatures, wire behavior or error branches are needed. Estimated written work is under 150 lines across the plan and three source/test files, with two acceptance criteria and no consumer updates.

In-flight #1642, #1747 and #1753 also edit `ThreadScreen`, respectively for queued sends, error notices and tool-row presentation; none changes `openToolCall`. This change stays local to that helper.

## Testing strategy

First add failing `OpenToolCallTest` regressions: a newer parented running call must not replace an earlier main-thread call or its elapsed reading; parented running calls alone, including an absent parent, must yield `null` even alongside completed main-thread calls. Extend `RunningToolIndicatorTest` to prove the main-thread label survives background activity and the existing working/idle fallback returns after the main call completes. Run these classes with `StatusArmTest`, then lint, assembleDebug, androidTest Kotlin compilation and forced Spotless verification. This changes a pure display selector over existing streamed rows, without adding an operator flow; existing real-Claude tool-status scenarios remain applicable and unchanged.

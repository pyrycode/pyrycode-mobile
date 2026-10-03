# #1578 — Drop the "doesn't remember" line and the fade above session boundaries

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SessionBoundaryDelimiter.kt` — `SessionBoundaryDelimiter`, `SessionBoundaryDelimiterContent` (the explanation `FlowRow` and boundary Install), `RuleLabelRow`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` — `ABOVE_DELIMITER_ALPHA`, the `rowAlpha` wrapper in the thread `itemsIndexed`, `mostRecentSessionBoundaryIndex`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/QueuedMessageRow.kt` — the `QUEUED_ALPHA` comment that cites `ABOVE_DELIMITER_ALPHA`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` — keeps `shouldOfferMemoryInstall` / `MEMORY_PLUGIN_DOCS_URL`; untouched.
- Tests matching a boundary by its explanation: `SessionBoundaryAssertions.kt` (`SESSION_BOUNDARY_EXPLANATION`, `awaitDisplayedSessionBoundary`), `SessionBoundaryVisibilityTest`, `ScriptedSessionBoundaryTest`, `ThreadScreenOverflowTest`, `ThreadAgentNameTest`, `SessionBoundaryDelimiterScreenTest`, `InteractiveStreamE2ETest` (`DELIMITER_EXPLANATION`; `awaitDisplayedSessionBoundary` in `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` and `interactiveTurn_createEditArchiveChannel_readsPromptBack`), `ThreadScreenCutoffTest`.
- `app/src/androidTest/assets/design-1220/README.md` — inventory rows and the `no separate frame` status.

In-flight overlaps (additive, unrelated hunks): #1628 (`ThreadScreen.kt` task pill), #1563 and #1581 (`InteractiveStreamE2ETest.kt`), #1619 (design-1220 `README.md`).

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=675-3682

The frame (and overflow frame `675:5883`) draws each `Session boundary` as the `Rule row` (hairline rule / `bodySmall` primary label / rule) plus an `Explanation` text node, and fades the rows above. By decision on this ticket (Juhana, 2026-10-03; #1580 closed as not needed) the app keeps the `Rule row` unchanged and drops the `Explanation` node and the fade; the frames stay as drawn. Recorded as a `no separate frame` row in `app/src/androidTest/assets/design-1220/README.md`.

## Change

`SessionBoundaryDelimiterContent` keeps its inset column and `RuleLabelRow` and loses the spacer, the explanation `FlowRow`, the memory-search copy and the Install `TextButton`; with nothing left to read them, `SessionBoundaryDelimiter` drops its `agent` and `memorySearch` parameters and the content its `uriHandler` (one production call site, in `ThreadScreen`). The column gains `Modifier.testTag(SESSION_BOUNDARY_TEST_TAG)` (`internal const val SESSION_BOUNDARY_TEST_TAG = "session-boundary"`), the new reason-independent matcher. `ThreadScreen` deletes `ABOVE_DELIMITER_ALPHA`, the `cutoffChronologicalIndex` remember and the `Modifier.alpha` wrapper, so every row draws at full opacity; `mostRecentSessionBoundaryIndex` has no other caller and goes with `ThreadScreenCutoffTest`. `QueuedMessageRow`'s comment stops citing the removed constant. `shouldOfferMemoryInstall`, `MEMORY_PLUGIN_DOCS_URL` and `agentDisplayName` stay: the overflow menu, channel info sheet and banner rows use them. `CompactionBoundaryDivider` is untouched.

## Testing strategy

- `SessionBoundaryAssertions.kt`: replace `SESSION_BOUNDARY_EXPLANATION` with `SESSION_BOUNDARY_EXPLANATION_FRAGMENT = "doesn't remember"` (absence probe only); `awaitDisplayedSessionBoundary` waits on `onNodeWithTag(SESSION_BOUNDARY_TEST_TAG)` and then asserts the fragment absent.
- `SessionBoundaryDelimiterScreenTest`: for Clear, WorkspaceChange and IdleEvict with an Absent report and a Codex agent, the tag is displayed and no "doesn't remember", "Search stored knowledge" or "Install" node exists; the Install-click and report-toggle tests go. Rule-colour and inset pixel tests stay.
- `ThreadRowOpacityTest` (new, shared): a thread of message / boundary / message renders both bubbles and the boundary with no ancestor `graphicsLayer` alpha — asserted by pixel: the older bubble text draws the same colour as the newer one.
- `ThreadAgentNameTest`, `ThreadScreenOverflowTest`, `SessionBoundaryVisibilityTest`, `ScriptedSessionBoundaryTest`: switch to the tag and assert the explanation absent; the overflow test asserts the boundary never shows Install while the overflow item still follows the report.
- `InteractiveStreamE2ETest`: `DELIMITER_EXPLANATION` becomes `onAllNodesWithTag(SESSION_BOUNDARY_TEST_TAG)` for the absence guards; the final reveal stays `awaitDisplayedSessionBoundary`. Live acceptance is the dispatcher's live gate (`## Live tests` lists both methods that call the helper).
- Focused: `testDebugUnitTest` on the touched classes, `compileDebugAndroidTestKotlin`, `lint`, `assembleDebug`, `spotlessCheck`.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/session-boundary-delimiter.md`: replace the "Rule / label / rule, explanation retained below (#644)" account and the Install-depends-on-report note with the rule-row-only boundary.
- `CLAUDE.md` and `AGENTS.md`, § Conversations model: drop the sentence beginning "Above-delimiter messages are visually de-emphasized", including its explanatory-line and boundary install-affordance claims.
- `docs/e2e-interactive-stream.md`: the #541 new-session scenario's matcher description (now the `session-boundary` test tag).
- Every other `docs/knowledge/features/` overview that describes the fade or the explanation line (search "de-emphasi" and "doesn't remember").

## Revisions

- 2026-10-03: `SessionBoundaryDelimiterContent` and its `uriHandler` seam are removed rather than kept as an inset column: with the explanation gone it would have wrapped a single child, so `SessionBoundaryDelimiter` now passes the inset padding and `SESSION_BOUNDARY_TEST_TAG` straight to `RuleLabelRow`. Same geometry (the inset pixel test is unchanged); the tag's node is the rule row with its padding. Drove it: the implementation, no finding. The opacity test is named `ThreadRowOpacityTest` and compares the dominant bubble pixel under each message text, drawn through `View.draw` (Robolectric's `captureToImage` timed out on the thread screen).

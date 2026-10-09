# Late background Agent tool ownership (#1951)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundAgentBlocks.kt`: `foldBackgroundAgentBlocks` claims loaded parent chains independently of turns and derives root status from task evidence.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `toolNestingDepths`, `foldToolRuns`, and #1940's `listKey` preserve first-child run identity and a separately visible root.
- `app/src/main/java/de/pyryco/mobile/data/repository/HistoryPageReducer.kt`: `withToolUse` preserves received parent attribution; #1941 correlates wire identities separately from renderer aliases.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt`: real repository → ViewModel → screen fixture and observable retained messages.
- `app/src/sharedTest/java/de/pyryco/mobile/e2e/AgentRunNavigationProof.kt`: existing pointer and child-key proof distinguishes collapse from lazy disposal.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `interactiveTurn_seededChannel_backgroundAgentMovesAndSettles` currently lacks main completion before late child arrival.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: `interactiveTurn_backgroundAgent_followsBottomUntilFinished` already observes received frames with a second client and holds the Agent open.
- `scripts/e2e-fixtures/background-agent-open.jsonl` and `background-agent-finish.jsonl`: existing two-drop fixture.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md`: ownership differs from child-run identity; the closed first-child key represents the run, not a visible child.
- `docs/e2e-interactive-stream.md`: rung-2 harness and existing rung-3/rung-4 Agent proofs.
- `/Users/juhanailmoniemi/Workspace/Projects/pyrycode/docs/protocol-mobile.md`: `tool_use`/`tool_result` child attribution after main completion (#2960), the wire source of truth.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=795-7158

Inspected Running frame `795:7178`: separate Agent header with running status, flush children indented 16 dp, background and primary-container tool outline, tertiary tool names and body-medium subjects. Preserve existing components, theme tokens and collapse controls; this ticket adds behavioral proof rather than retuning visual styling.

## Context

Daemon #2960 and Mobile #1940/#1941 are merged. The daemon previously lost attribution after main completion; Mobile already groups correctly attributed loaded children without a turn constraint. Add one regression-proof deliverable across the existing test ladder and change production only if it demonstrates a client defect. Forecast approximately 450 written lines, no new exported API, no required consumer migration, four acceptance criteria and no new reject branches.

In-flight #1682, #1689, #1690, #1691, #1693, #1695, #1725, #1766, #1879, #1888, #1900 and #1968 touch the e2e classes in other methods. Keep edits local to the two named Agent methods; no design dependency.

## Design

Reuse `ScriptedThreadHarness.pushEnvelope` for correctly attributed frames. Add a test-local collapse setting to the existing harness so the same received-frame scenario proves collapse off and on. Assert retained identities/status/order and actual background projection, then rendered Agent/child placement and owned run expansion. Include a child begun before main completion, two later children, unrelated foreground tools and another Agent family. No adjacency-based ownership inference.

Extend the deterministic open fixture with a main result before late child tools while retaining its existing child/prose and terminal release. Observe received `LiveSessionEvent`s before sending, establish the main `TurnEnd` then at least two attributed `ToolUse`s, and assert every received late child belongs to the same separately rendered Agent block when opened. Strengthen the real-Claude method with a child hold before its two late Bash calls; release that hold only after observing the launching main `turn_end`. Verify actual frame order and parent attribution with the existing peer, then visible owned placement using the existing navigation proof and list keys.

## State and concurrency model

Production state/scopes remain unchanged. Harness closes its repository/ViewModel scopes in teardown. Deterministic event collection uses a test-owned coroutine job cancelled in `finally`; the live method retains its existing peer cleanup and preference restoration. Child ownership is a projection of loaded parent chains, and task completion alone changes the Agent header to Done.

## State transitions and identity reuse

| Event | Proof |
| --- | --- |
| Main ends while a child is running; result arrives afterward | `ScriptedBackgroundAgentToolsTest.lateToolsRemainOwnedAcrossMainCompletion` |
| Two child calls arrive while idle or during a later main turn | same received-frame regression and both named e2e methods |
| Collapse off → closed → expanded → closed; task finishes | same regression and existing `verifyAgentRunNavigation` |
| Duplicate/replayed tools, another Agent and foreground tools | `ScriptedBackgroundAgentToolsTest.invariantReplayAndInterleavingPreserveExclusiveOwnership` |
| Parent backfill, reversed sibling arrivals and overlapping history | `BackgroundAgentBlocksTest.invariantLoadedParentChainsOwnEverySiblingPermutationOnce` and `BackgroundAgentProseTest.historyOverlapReplayAndReconnectKeepEachSegmentOnceAtTheFinishAnchor` |

## Error handling

No new failure modes. Tests fail on missing attribution, zero late children, changed sibling identity/order/status, escaped ownership or premature Agent completion. Do not repair daemon lifecycle behavior in Mobile.

## Testing strategy

Run the new received-frame shared screen regression and existing `ToolRunCollapseTest`, `BackgroundAgentBlocksTest`, `BackgroundAgentBlocksScreenTest`, `BackgroundAgentProseTest` and ownership probes. Confirm a negative attribution control fails for the ownership assertion, then restore correctly attributed input; passing unchanged production records that daemon #2960 resolves the client symptom. Run `scripted background-agent` and inspect the named method's fresh XML counts. E2e tests remain device-only because they require the real socket/host daemon and peer or scripted backend. Compile Android tests, lint, assemble, format, and run final `pre-verify.py --gradle` after merging main. Dispatcher owns the fresh full live gate, including the strengthened named live method.

## Open Questions

Does correctly attributed post-turn activity expose a Mobile defect? Resolve from the received-frame and scripted proofs before handoff.

## Revisions

2026-10-09: Correctly attributed received frames pass against unchanged Mobile production code. Removing `late-two`'s received parent makes the new ownership assertion fail (1 executed, 1 failed, 0 skipped); restoring it passes. Daemon #2960 supplies the missing attribution, so this ticket lands regression coverage and strengthened existing e2e proofs only. The loopback fixture adds an independent `/hold-tools`/`/release-tools` fence, released after the observed main end, to guarantee two late tool starts rather than relying on scheduling. A repository-visible replay barrier ensures replay assertions run after all duplicate frames were reduced. Actual written work remains below 1,600 lines with zero exported production API changes.

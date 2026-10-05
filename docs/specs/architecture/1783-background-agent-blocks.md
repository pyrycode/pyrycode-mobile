# Background agents at the newest end (#1783)

## Files read

- `docs/knowledge/features/thread-screen.md` and `thread-screen-subagent-tool-rows.md`: keyed reverse list, nesting, collapse identity and follow rules; tool expansion is saveable at the message key.
- `docs/knowledge/features/tool-call-row.md`, `ToolCallRow`, `MessageBubble`, `ConversationStatusDot`: existing inert tool treatment, gutters and theme-backed status dots.
- `BackgroundTask`, `BackgroundTaskProjection.applyStarted/applyUpdated/applyRoster`, `BackgroundTaskLifecycleFold`, `ThreadProjection.applyBackgroundTaskLifecycle`, `ThreadItem.BackgroundTaskLifecycle`: merged #1782 exposes roster joins and retained ordered finish positions independently of replacement panel state.
- `ThreadViewModel.backgroundTaskReading`, `ThreadUiState`: existing conversation-scoped lifecycle and roster inputs; no new ViewModel state required.
- `InteractivePayloads.toInputFields`, `HistoryPageReducer` ToolUse fold: daemon-stringified boolean launch inputs reach both live and historical ToolCall input fields.
- `ThreadRow.foldQueuedRows/foldToolRuns/listKey/toolNestingDepths`, `ThreadScreen`, `ThreadListFollow.FollowNewestEnd`: projection and scroll seams.
- `ToolRunFoldTest`, `ToolRunCollapseTest`, `ThreadScreenFollowTest`: existing projection, expansion and scroll coverage.
- `InteractiveStreamE2ETest.answerChat/runningToolPeer/allowPromptsUntil`, `scripts/e2e-emulator.sh`, `docs/e2e-interactive-stream.md` sections “What rung 3 is made of” and “Live mode”: isolated real-Claude harness and dispatcher evidence ownership.
- Sibling `pyrycode/docs/protocol-mobile.md`, background-task sections and Security model: wire SSOT; no wire changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=795-7158

Inspected context and screenshots for Running `795:7178` and marker variants `795:7177`. The marker has two lines, 6dp vertical padding, 2dp line gap, 8dp status-row gap and a 14dp description indent. Use bodySmall for status/description, labelMedium and primary for “Go to agent ↓”, onSurfaceVariant for status and onBackground for description. Reuse the existing 6dp circular status-dot treatment: primary for Busy, colorScheme.success for Finished. The existing tool header and nested outlines remain the block treatment.

## Context

The immediate launch result resolves the Agent tool while its background task continues. Moving repository rows would damage ordered history and pagination. A pure display projection can instead leave a launch marker and move the loaded Agent family, using the retained terminal entry to settle it. Cache-only rows lack parents/lifecycle and retain today's placement; this ticket does not extend storage. No decision record is needed.

In-flight overlaps: #1603 and #1747 touch separate ThreadScreen regions; #1674, #1682, #1689, #1690, #1691, #1693, #1695, #1728 and #1766 add separate live scenarios/selector entries. Changes here stay additive and local.

## Design

Add `foldBackgroundAgentBlocks(rows, items, roster)` after queued joining and before run folding. Match only local_agent tasks with nonempty tool-call joins to loaded Agent/Task tool rows. Retained launch/terminal evidence takes precedence, roster fills missing launch fields and establishes running state before history arrives. Never fabricate missing tools. Resolve loaded tool-parent chains with memoisation and a cycle guard; partition each family exactly once, preserving original row order.

Replace the Agent's original slot with `ThreadRow.AgentStartMarker`, keyed independently by the unique Agent message id. Mark projected delivered tool rows with a block identity, preserving msg keys. Running blocks append after all ordinary and queued rows, ordered by known launch evidence (roster order until known). Terminal blocks insert at the terminal evidence's original position relative to surviving rows, including when terminal evidence precedes launch backfill. Empty/replacing rosters cannot erase retained evidence or move a known finish. A finished roster with no terminal evidence falls back to the loaded launch slot until history supplies a position.

Override only the display copy of the Agent's ToolCall status: Running during the task, Done for any terminal status. Preserve input/output and descendant states. Block identity bounds both foldToolRuns and flush joins, so separate blocks and adjacent ordinary tools never merge. Existing run ids and expandedRuns stay keyed to the first tool. Individual tool expansion stays with the original msg key.

The marker is a stateless Compose component receiving description, finished and onGoToAgent. Its description is capped at 4096 characters and rendered as one inert ellipsized Text line. Clicking requests navigation by Agent id; ThreadScreen opens the run containing that id if collapsed, then a composition-owned effect scrolls the reversed list to the Agent header after the updated projection is available. It does not expand the tool body.

Pass the moved block's content as part of FollowNewestEnd's existing newestRow signature. This detects in-place growth even when the last row's identity does not change, preserving the existing older-reader and prompt rules.

## State and concurrency model

All placement is a remember-cached pure derivation of immutable ThreadUiState inputs on the Compose thread. No repository mutation, new flow, dispatcher, ViewModel job or persistent reconciliation state. expandedRuns and the existing keyed tool-body expansion remain saveable UI state. Navigation uses a LaunchedEffect owned by the screen and cancelled on exit. Existing lifecycle driver/socket cancellation remains untouched.

## Error handling

Unknown/empty joins, wrong tool/task types and missing parents preserve today's rendering. Duplicate task joins claim a loaded Agent once. Cyclic parent chains terminate without inventing rows. Missing navigation targets are inert until history supplies them; no network operation or new user error is introduced. No daemon text or id is logged by this projection.

## Testing strategy

Test first with JVM projection assertions: running/finished ordering, nested descendants and internal keys, queued rows, multiple launch orders, roster-before-start, terminal-before-launch backfill, pagination/reload, empty roster, unknown joins and cycles, isolated collapse boundaries and display-only status changes. Controlled Compose tests mount the real ThreadScreen for marker wording/truncation, navigation opening a run before/after finish, tool-body expansion after movement, nesting and follow/older-reader behavior. Run affected ToolRunCollapseTest, ToolRowNestingTest, ConsecutiveToolRowSpacingTest and ThreadScreenFollowTest coverage.

Land the required runnable live method on InteractiveStreamE2ETest and add it to the curated selector. A scenario-specific loopback HTTP fixture holds a real background local_agent's Bash tool after the launching turn; newer phone messages prove placement and marker navigation, a phone release command completes it, and a later phone message proves settling. The fixture has fixed endpoints, no request logging, a bounded hold and cleanup by the harness. Device-only because it exercises real Claude and host I/O. Deterministic projection/Compose fixtures cover multi-agent/history permutations; existing scripted tool coverage checks unchanged tool rendering. Compile androidTest, run focused JVM/screen checks, lint, assembleDebug and forced Spotless. The dispatcher owns the full live execution and fresh named XML evidence; no focused live run is required by the ticket.

## Open Questions

None. The cache-only limitation and dispatcher-owned live acceptance are explicit handoffs.

## Security review

**Verdict:** PASS

- Trust boundaries: SHOULD FIX: bound the new marker description at 4096 characters; Text only, never Markdown, URL, filename, action input or log. Joins resolve only to loaded tool rows; cyclic parent hints terminate.
- Tokens: no production credential generation, storage, rotation or exposure changes. The live harness keeps existing isolated authentication; the scenario never reads credentials.
- Files/storage: no cache or backup changes. The host fixture writes only its port into the harness-owned temporary directory and is removed by existing cleanup.
- Android attack surface: no component, intent, provider or WebView additions; the marker only scrolls in this screen.
- Cryptography: unchanged Noise_IK transport and Keystore ownership; projection consumes already-decoded domain data.
- Network/I/O: no new production I/O. The test-only fixture binds loopback, accepts fixed endpoints, holds at most 180 seconds and logs no request content.
- Errors/logs: no descriptions, summaries, ids or message contents enter new logs/errors. Projection has no classified I/O errors; lifecycle logging remains at existing boundaries.
- Concurrency: pure projection and cancellable Compose navigation; memoised bounded parent traversal. No shared mutable task lifetime state is added.
- Rework review: retained roster hints are connection-local, conversation-scoped and updated atomically with thread state, removed by `ThreadProjection.remove`. Only existing lifecycle entries receive missing fields; roster replay creates no position. Historical launch fields take priority. The marker retains its inert 4096-character bound, no new logging, storage, credentials or I/O is introduced, and run-expansion transfer is UI-local.
- Restoration review: pending run expansion saves only loaded tool ids, using the same conversation-scoped saveable UI state as expanded run ids. No message text, credentials, new storage format or I/O is added; spending intent still prevents reopening a deliberately collapsed run.
- Threat model: relay delay/reorder is addressed by retained lifecycle positions and tests; encryption/authentication and rooted-device token theft remain owned by existing transport/key storage. Hostile daemon text is bounded inert Text; existing screenshot/accessibility exposure is unchanged.

**Reviewer:** builder (self-review per builder/security-review.md)
**Date:** 2026-10-05

## Revisions

2026-10-05: Add the rung-4 `background-agent` raw-stream twin alongside the controlled multi-agent/history fixtures. It holds the first fragment until a second phone send releases the terminal fragment, proving lifecycle decoding, collapse navigation and finish placement through the daemon. The builder runs this focused scripted scenario; full live XML remains dispatcher-owned.

2026-10-05: A regression for a finished roster without terminal history showed the fallback block before its launch marker. Place it immediately after the marker until terminal history provides the authoritative position. Strengthened the follow test to grow an expanded tool result in place and assert both the newest-end offset and an older reader’s pixel anchor.

2026-10-05: The existing progress-panel live scenario documents that foreground subagents also emit local_agent lifecycle frames. A new failing regression proved task type and join alone would move them. Require the loaded Agent/Task input field `run_in_background` to equal `true`; false or missing inputs preserve current rendering. The new live scenario first makes two harmless printf tool calls because protocol task progress requires the tool count to advance by two, then enters its causal hold.

2026-10-05: A mixed-pagination regression exposed known launches sorting ahead of unknown older launches. Preserve unknown running agents' roster slots and sort known launch entries within the remaining slots; when all launch history is known, this becomes the authoritative launch order. Later backfill enriches the order without duplicating any block or changing terminal anchors.

2026-10-05: The live proof waits for the phone's Finished marker before sending its later message, so different peer/app socket delivery timing cannot turn that message into a pre-terminal optimistic echo.

2026-10-05: PR #1821's verifier reproduced loss of a roster-only join after finish and roster replacement. Retain first-known roster hints in `ThreadProjection.ProjectionState`, separate from the replacing panel, and enrich existing lifecycle entries in `observeSnapshot`. This adds connection-local reconciliation evidence, without creating a launch position or changing repository arrival/history order, terminal anchors, the cache or the ViewModel. Missing joins can be enriched by a later roster; launch history still wins. Tests drive the production projection through roster-before-terminal and terminal-before-roster, clearing/replacing rosters, history backfill/replay and conversation deletion.

2026-10-05: The same review found late movement closes a previously expanded containing tool run. Compare the previous tool-run membership when an Agent first gains a block identity, transfer the old run's expansion to its new block id, and persist it in `expandedRuns`. Ordinary updates and finish do not re-expand a run the reader deliberately closed. Compose transition tests cover expanded and collapsed starting runs, individual tool-body state, finish and marker navigation. Existing marker geometry and design tokens remain unchanged.

2026-10-05: The second review of PR #1821 found two transitions the first-gain rule missed: a lone Agent leaving an open run forms no run until its child arrives, and a backfilled Agent pulls in loaded descendants while only the Agent itself is newly moved. `carryRunExpansion` replaces that rule. When any loaded tool's block identity changes, every tool of a previously open run that now sits in a different run carries the expansion to its new run. A block tool left alone holds the intent in UI-local `pending` state until its block forms a run, then the intent is spent. With no block change and nothing pending, expansion is untouched, so a run the reader closed stays closed. JVM tests drive both transitions through `carryRunExpansion`, and Compose tests mount `ThreadScreen` for each.

2026-10-05: The third review of PR #1821 found that a lone moved Agent's pending expansion intent was lost on recreation before a child arrived. `pendingOpenTools` now uses conversation-scoped `rememberSaveable`, as does `expandedRuns`; the comparison snapshot remains transient because restored pending ids alone drive delayed run creation. A test-first `StateRestorationTester` regression recreates the screen between join and child arrival, then recreates a deliberately collapsed run to prove spent intent does not reopen it. A second screen regression switches conversations with matching tool ids and proves pending intent does not leak.

2026-10-05: The same review's failed replay-order history contains the initial user confirmation between reply deltas 1 and 2. The deterministic opener omitted Claude's replayed initial user, allowing the daemon's idle placement fallback to split an otherwise ordered reply into two legitimate segments. Add the `isReplay` user echo before thinking in `replay-order-open.jsonl`, matching the existing send-now fixture and real runner contract. A test-first harness regression pins this causal placement. Preserve the scenario's visible order/uniqueness assertions and additionally assert exact final reply text, delta identities `[0, 1, 2]`, and a single initial user preceding the reply. No production replay or segment-fold behavior changes; focused scripted evidence does not replace the dispatcher-owned combined gate.

## Documentation handoff

- Pending documentation stage: `docs/knowledge/features/thread-screen-subagent-tool-rows.md`, subagent nesting, newest-end follow and tool-run collapse sections: background-only qualification, marker navigation, running/terminal placement, roster/history backfill, retained joins and expansion on late splits, including conversation-scoped restoration before a block forms a run.
- Pending documentation stage: `docs/knowledge/features/thread-screen-previews-and-edge-cases.md`, limitations: cache-only rows without parents/lifecycle retain existing placement; the cache format is unchanged.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, coverage, “What rung 3 is made of” and “Live mode”: the named background-agent live scenario, bounded hold/release fixture and deterministic twin; record fresh full-live XML only after dispatcher execution.
- Pending documentation stage: `docs/e2e-interactive-stream.md`, Deterministic mode / replay-order: the opening fragment replays the initial user before thinking so idle fallback cannot insert its confirmation between reply deltas; exact final text and delta identities supplement the visible ordering/uniqueness proof.

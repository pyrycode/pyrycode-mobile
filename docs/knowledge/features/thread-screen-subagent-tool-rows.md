# Thread screen — subagent tool-row nesting

Split out of [Thread screen § how it works, the list, the chip, the empty state and the status
row](thread-screen-how-it-works-list-and-status-row.md) on 2026-10-01 to keep that document under the
50000-byte size cap the docs guard enforces. The section moved here verbatim and kept its heading, so its
anchor is unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge
cases and its links.

### Subagent tool-row nesting (#896)

`ThreadScreen` derives `toolNestingDepths(state.items)` from repository items and passes each
message's depth into `MessageBubble`. Nesting is independent of the display placement below:
a moved background Agent family keeps the same parent relationships and indentation.
`toolNestingDepths` is a pure derivation in `ThreadRow.kt`, beside the row folds.

**What it computes.** A tool row's [`Message.id`](data-model.md) is its own `tool_use_id`; its
[`ToolCall.parentToolUseId`](data-model.md) (#810) names the `Agent`/`Task` call whose subagent made it,
or `""` for the main thread. A row's depth is `0` when `parentToolUseId` is empty or names no *tool* row
loaded in the thread (an older page not yet fetched, an id belonging to some other kind of row, or —
because the disk cache doesn't persist the field — any cache-restored row), otherwise `1 +` its parent's
depth. The map holds only rows with depth `> 0`; a row absent from it renders at top level, which is what
`?: 0` above falls back to.

Depth zero does not establish that a call belongs to the main thread. The
[status selector](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311)
excludes every call with a nonempty `parentToolUseId` directly, even when missing loaded
parents leave its row at depth zero. Using the depth map for status selection would
let background activity replace the main tool's name and elapsed reading again.

**Matching is independent of list order.** Candidates are collected into a `parentOf: Map<String, String>`
first (one pass over `items`), then each row's depth is found by walking up its parent chain in that map,
memoising every id the walk passes through `depthOf` so no id is walked twice — O(tool rows) total however
deep the nesting goes, and correct whether a row's parent appears earlier or later in the list.

**Cycle rule.** `parentToolUseId` is a grouping *hint*, not a capability (`protocol-mobile.md` §
`tool_use`) — the daemon is never expected to send a loop, but the derivation still has to terminate if one
somehow arrives (a corrupt cache row parented to itself, for instance). The walk tracks the ids on its
current path in an `onPath` set and stops the moment it would revisit one; that row counts as top level,
and every row walked before it on the path counts up from there. Deterministic for a given list, and it
never loops, regardless of how the cycle is shaped (a two-row A→B→A pair and a one-row self-parent both
terminate the same way).

**Rejected alternative: a single forward pass.** A simpler shape — walk `items` once, looking up each row's
already-computed depth by its `parentToolUseId` as it goes — is *not* what's shipped, because it silently
leaves a row flat whenever its parent appears *later* in the list: the parent's depth isn't known yet at
the point the child is visited, forward-only. The two-map walk above (`parentOf` built first, `depthOf`
filled by a per-row backward walk) matches "a tool row loaded anywhere in the thread" as the AC requires,
at the same O(tool rows) cost, and the cycle guard above is the price of allowing that backward walk to
happen at all.

Tested independently of any composable in `ToolNestingDepthsTest` (`app/src/test/.../thread/`): main-thread
row absent from the map, matched child = 1, grandchild = 2, unmatched parent absent, empty-id row ignored,
a parent id that matches a *user* message (not a tool row) does not nest, a parent listed after its child
still nests, and both cycle shapes (a two-row loop, a self-parent) terminate per the rule above. The
Compose-level assertion — that the indent and the "Subagent step, level N" description actually reach the
rendered row — lives in `ToolRowNestingTest` (`app/src/sharedTest/.../thread/`), which mounts the real
`ThreadScreen`; see [`MessageBubble` § Subagent nesting
indent](message-bubble.md#subagent-nesting-indent-since-896) and [`ToolCallRow` § Subagent step
description](tool-call-row.md#subagent-step-description-since-896) for what each level actually renders.
The original nesting change needed no rung-3 scenario. Background placement now has its own
[live and deterministic proof](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

### Background-agent lifecycle placement (#1783)

`foldBackgroundAgentBlocks` runs after queued-row joining and before tool-run folding. It moves
only a `local_agent` joined by tool-call id to a loaded `Agent` or `Task` whose input field
`run_in_background` is exactly `"true"`. Foreground subagents also emit lifecycle frames;
task type and join alone would incorrectly move them. Other task types, false/missing background
inputs and unknown joins retain their existing rendering. No missing Agent or descendant is invented.
Loaded tool-parent chains are memoised with a cycle guard; each claimed family moves once, in its
original internal order, with its message keys and nesting intact. Repository arrival/history order
never changes.

The launch slot becomes a separately keyed `agent-start:<Agent message id>` marker: a Busy dot and
“Agent started, still working”, or a Success dot and “Agent finished” for any terminal status,
beside “Go to agent ↓”. The second line is an ellipsized launch description. The description is inert `Text`,
bounded to 4096 characters and never logged. Tapping the marker only scrolls: a screen-owned effect finds
the Agent header's row in the reversed list and scrolls to it. It never opens a collapsed run on the
reader's behalf — the root header always draws as itself regardless (#1827 follow-up), and its own
children's run opens only from its own tap, via `ToolRunRow`'s toggle — and works before and after finish.
Navigation preserves the reader's collapse state. A placement test must assert that state after
navigation and explicitly open the owned child run before inspecting its prose (#1904); reaching
the always-visible Agent root does not prove that its children are visible.

Running families sit below every ordinary and queued row. Multiple families keep unknown launches
in their roster slots and sort known launches within the remaining slots; once all start history is
loaded, launch order is authoritative. The display copy of the Agent header stays Running despite
its immediate “Async agent launched” result, and becomes Done for any task terminal status;
descendant statuses and repository messages are unchanged.

A terminal lifecycle entry supplies the settlement position relative to the original thread rows.
Later ordinary rows appear below that block, while remaining running blocks stay at the newest end.
Reload uses the terminal history position. Roster-before-start and terminal-before-launch pagination
can join loaded tools later without duplicating a family or shifting an already-known finish position.
Historical fields take precedence over roster hints.

Join evidence, finished knowledge and terminal position are separate facts. `ThreadProjection`
retains conversation-scoped, connection-local roster hints to fill absent fields in existing lifecycle
entries; hints never create an entry or position and are removed with the conversation.
`BackgroundTaskRoster.settledTasks` separately retains first-known finished records across empty or
unrelated panel replacements, even with no loaded start/terminal history. Existing settled records
learn absent joins from later roster records even when the panel now says running; only finished
records create settled knowledge. Without terminal history, the finished family stays immediately
after its launch marker until history supplies the authoritative position. Panel `tasks` and counts
remain replacement state. A retained join alone cannot preserve a finish, and a regression that inserts
an extra terminal frame can hide loss of roster-only finished knowledge.

`BackgroundAgentBlocksTest` and `BackgroundTaskProjectionTest` exercise the production projections,
including late joins through both replacement variants without an intervening terminal frame,
multiple agents, pagination/reload, cycles, unknown joins and unchanged repository order.
`BackgroundAgentBlocksScreenTest` covers marker navigation, placement and expansion transitions.
Its `settledNavigationThenOwnedPointerTapsOpenAndCloseALongChildRun` regression and the live/scripted
`verifyAgentRunNavigation` proof also cover the next close tap after scroll-only navigation (#1867).
Opening a long run can dispose its header after all child keys have entered the list. Waiting only
for an on-screen expansion label can therefore report a failed toggle that actually succeeded.
Wait for child-key membership first, then reveal the existing header once and check its expansion
action. Bring the owned paragraph into composition before positive visibility checks; after closing,
require every loaded owned child key absent through `IndexForKey`, since absence from semantics alone
can mean lazy disposal. The scripted proof reads child message ids from the repository snapshot,
not fixture paragraph text. See [counted navigation/open/close evidence](../../e2e-interactive-stream.md#verification-status).
See [cache-only limitations](thread-screen-previews-and-edge-cases.md#edge-cases--limitations)
and [the live ladder](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

### Attributed assistant prose in Agent blocks (#1827)

`foldBackgroundAgentBlocks` also claims assistant rows whose nonempty `parentToolUseId` reaches a
joined background Agent through the same memoised owner lookup it uses for tool rows. A claimed
segment moves into that block once, in loaded order, keeping its message id and content. Distinct
wire lanes stay distinct rows, so two agents' replies never concatenate. An empty parent, a parent
naming no loaded tool, a cycle, or a task with no matching Agent row keeps the ordinary top-level
assistant rendering. A retained finished block stays a match, so a reply does not leave its block
when the agent settles. `ThreadScreen` draws claimed prose with the unchanged `MessageBubble`,
indented by `MessageAreaRowSpacing` (16 dp) times the parent's tool depth plus one, and tags it
`background-agent-child:<Agent id>` for the live and scripted proofs. Parent ids are inert grouping
hints: they never trigger an action and are never logged.

The ViewModel's live synthetic row carries attribution too. `ThreadFold.reduceDelta` keeps the
lane's first nonempty parent on `StreamingTurn`, and `render` copies it onto the synthetic message,
so a streaming child reply never shows in the main thread before the repository row arrives.
A replayed or older delta still enriches unknown attribution before the text sequence guard drops
its text. The first implementation learned the parent only on in-order appends, so a lane whose
first delta had an empty parent and whose replay carried it stayed top-level and outside the
block's collapse control until a repository snapshot repaired it. Attribution enrichment and text
deduplication must stay independent here, as they already are in
`HistoryPageReducer.withAssistantDelta`. A later conflicting hint never replaces a known parent.

With collapse on, `foldToolRuns` keeps the Agent root separate and folds its contiguous child
tool/prose rows into a run when there are at least two child rows and at least one tool. A prose-only family remains attached to the root without a child-tool
run. The run's `tools` contain only tool messages, so prose does not change its count or status.
Ownership identifies the Agent family; the first loaded owned child tool identifies its run.
For direct children, select the first loaded `Role.Tool` message whose
`toolCall.parentToolUseId` equals the Agent id, then use its message id in `tool-run:<runId>`.
The root Agent id is not that run id. Match the clickable control beneath that tag rather than
an arbitrary "Using tools: N" label (#1904). `BackgroundAgentProseScreenTest`'s
`agentRunControlHasStableOwnershipWhenAnOrdinaryRunHasTheSameLabel` gives both independent runs
two tools so their labels really match, and derives the owned run from loaded child ownership.
The control hides and reveals its prose; collapse never drops it. With collapse off the prose
is visible as a nested child. Gaps inside a block are covered under
[the oldest-end history demand](thread-screen-oldest-end-history-demand.md#the-oldest-end-history-demand-777).

`BackgroundAgentProseTest` probes the production projection: two agents and a main lane; empty,
unknown, untracked and cyclic parents; running to finished; roster replacement; late parent joins;
replay; history overlap and reconnect. It asserts contents, ownership and unique row keys rather
than total row counts, which include markers and headers. `BackgroundAgentProseScreenTest` covers
collapse on and off, toggling, a prose-only block, the indent, two identically labelled runs, a long
block whose early paragraph is disposed at the newest end, and the history-gap cases. The live and
scripted proofs are in [the live ladder](../../e2e-interactive-stream.md#what-rung-3-is-made-of).

### Consecutive tool rows sit flush (#1577)

Beside `toolDepths`, the same `ThreadItem.MessageItem` dispatch arm passes `joinsNextToolRow =
row.joinsToolRow(rows.getOrNull(chronologicalIndex + 1))` into `MessageBubble`, which forwards it to
[`ToolCallRow`](tool-call-row.md#consecutive-tool-rows-sit-flush-1577) as `joinsNextToolRow`. The
`ThreadRow?.isToolRow()` helper only returns `true` for a `ThreadRow.Delivered` row wrapping a
`ThreadItem.MessageItem` whose `message.role == Role.Tool` and `message.toolCall != null` — a queued
row, a delimiter, a notice banner, and a delivered `Role.Tool` message with a null `toolCall` (which
cannot render a row at all, per [`ToolCallRow` § Routing from `MessageBubble`](tool-call-row.md#routing-from-messagebubble))
all read as `false`, so a tool row next to any of those keeps today's 12dp gap. The join also requires
equal `agentBlockId` values, so separate background blocks and ordinary tools keep separate outlines.
The check looks only at the immediate next display row; a row that draws nothing between two tool
rows (an empty banner,
for instance) still counts as a break and the gap returns even though nothing visible separates the
two — rare, flagged as a known inconsistency in the #1577 verifier review, not fixed.

No rung-3 scenario: this is a spacing-only layout change over rows that already stream, not a new
operator flow. See [`ToolCallRow` § Consecutive tool rows sit flush](tool-call-row.md#consecutive-tool-rows-sit-flush-1577)
for the row's own half of the join (the layout shrink and the shared outline), covered by
`ConsecutiveToolRowSpacingTest` (`app/src/sharedTest/.../thread/`).

The message region's `LazyColumn` fills its weighted `Box`, with the top overlay drawn after it. The list keeps `reverseLayout = true`, scrolling upward from the bottom; connection readings in the composer and an Offline Retry overlay do not reserve list height. See [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).

**Streaming auto-scroll (since [#185](../codebase/185.md)) and the newest-row pin ([#981](../codebase/981.md)) — retired by [#1314](https://github.com/pyrycode/pyrycode-mobile/issues/1314).** #185 kept only a *streaming* bubble's growing bottom edge anchored, by re-measuring item 0's size while `hasStreamingMessage` held; #981 added a second, identity-keyed effect beside it because a reply that arrived already finalized, a tool row, or any other new newest row never tripped the size-driven pin — under `reverseLayout = true` a new row at index 0 pushes the previous first row to index 1, which stays anchored, so the new row lands below the viewport, uncomposed, until something scrolls back to index 0. Both effects gated on one `var userScrolledAway by remember { mutableStateOf(false) }`, set by a `NestedScrollConnection` on any user drag delta and cleared only when a snapshot of the at-bottom position *changed* to true — so a reader already at the bottom on arrival never got it back — and neither effect ran after a send. #1314 replaced both, plus the #1305/#1306 prompt reveal below, with one following rule; see the next section for the current mechanism, and § *Inline question rows and the newest-end reveal* and § *Inline permission rows and the shared reveal* in [Thread screen — list and status row](thread-screen-how-it-works-list-and-status-row.md) for how the reveal now rides the same rule.

### The newest-end follow rule (#1314)

**One following state, derived from position, replaces the #185/#981 pins and the #1305/#1306 reveal.** `ThreadListFollow.kt`'s `FollowNewestEnd` composable and its pure `followStep` function port desktop's `useThreadScrollPin` (`src/renderer/src/screens/conversation/ConversationScreen.tsx`): a single `LaunchedEffect(listState)` collects one `snapshotFlow` per layout frame (`ListFrame`: the first visible row's key, index and scroll offset; a `content` signature of the newest row's key, the newest row's own content, the prompt identity, and the anchor row's size; and whether a scroll is in progress) and derives `following` and whether to re-pin from it, so following-recompute and re-pin can never race between two collectors reading a stale value. A second `LaunchedEffect` collects `ThreadViewModel.sentMessages` and both sets `following = true` and pins immediately — desktop's `followBottom`, fired after every *accepted* send rather than guessed from state.

**A scroll is any change to the anchor row's key or offset — not any change to its position.** `followStep` treats that as the rule: following is recomputed from whether the anchor is within `AtNewestEndTolerance` (4dp, desktop's `AT_BOTTOM_TOLERANCE_PX`) of index 0, offset 0. Under `reverseLayout`, inserting a row at index 0 moves the previous anchor's *index* but keeps its key and offset, so growth is never mistaken for a scroll; an older-history prepend and an overscroll that cannot move the list move neither key nor offset either, so following is untouched by both. A frame whose anchor key/offset are unchanged but whose index or content signature changed is growth, and pins only while already following (`following && (grew || anchorIndex != 0)`). Reading the recompute and the re-pin from the same collector, rather than two effects each watching the frame, was necessary: a slow drag within the tolerance seen by two separate collectors can read a stale `following = true` from one while the other is mid-update, and gets pulled back on every frame.

**A pin refused under a resting finger is retried by the next frame that is not a scroll, not by a dedicated retry loop.** `pinToNewest` wraps every `scrollToItem(0)` in `try { … } catch (e: CancellationException) { currentCoroutineContext().ensureActive() }`, same as #981's guard, costing one scroll per refusal and still propagating a genuine cancellation. The first version of this rule retried on the next *visible* content change, which missed the case where the refused pin was for the reply that had just arrived: that reply sits at index 0, below the viewport, so its own streamed deltas changed no row the rule was watching, and it streamed out of sight until some other row arrived. The fix folds the newest row's own content into the growth signature (`newestRow`, containing the newest projected row and the moved tool-block content) and adds `scrolling` (`isScrollInProgress`) to the frame, so the finger lifting is itself a frame that can retry the pin. While following, the list can only sit off index 0 because a pin was refused, so a successful pin always changes the anchor key — which reads as a scroll — and the retries end without a loop.

**While a question or permission card is mounted, row content and the anchor's size are masked from the growth signature (#1304).** `FollowNewestEnd`'s `promptPresent` parameter (`questionState != null || openRequest != null`) blanks `newestRow` and the anchor's size in `content` while true, so editing a field, the IME opening, or `BringIntoViewRequester` scrolling inside the card never reads as growth and never pulls a reader back down. A new prompt (`promptIdentity`, `shownQuestion?.generation to openRequest?.modalId`) still pins a reader who is following, the same as a new message row would — see [Thread screen — list and status row § Inline question rows and the newest-end reveal](thread-screen-how-it-works-list-and-status-row.md#inline-question-rows-and-the-newest-end-reveal-1305) and § *Inline permission rows and the shared reveal* there for the reveal's own history.

**A reader resting on the prompt follows again once the prompt leaves (#1449).** `ListFrame` carries `promptRows`, how many prompt rows lead the list in that frame's composition, and `followStep` checks it ahead of the scroll rule: when the previous frame's anchor was a prompt row (`previous.anchorIndex < previous.promptRows`) and the current frame has none, the step is `following = true, pin = true`. Without this, a reader resting on the permission or question card — not on its own newest-end position, but anywhere the card painted, down to its last row — stays unfollowed once the card leaves: the keyed anchor position cannot find the vanished row, so it keeps the old index, which now names an older message row, and the unmodified rule reads that as a scroll away from the end. The #1338 real-claude gate hit exactly this: `threadHeldToken=true` — the reply reached the phone's repository but the thread screen never composed a bubble for it. The ticket's leading suspect, a reader already at index 0 when the prompt resolves, does not reproduce: the vanished-row position stays at index 0, offset 0, and the unmodified rule already reads that as at-the-end. The live method's own `awaitPromptDialog` was what parked the reader on the card rather than at the true end — its `performScrollToNode` on the permission card, timed against the follow pin that brings new prompt rows into view, could land the list on the card instead of past it, which is why the flake was intermittent. [#1312](https://github.com/pyrycode/pyrycode-mobile/issues/1312) hit the same failure and attributed it to the always-present status band, and added a `performScrollToNode` on the reply token to the live method to paper over it; that attribution doesn't hold (the #1338 tree had no #1312), and the token scroll is removed — the method now asserts the reply is composed at the newest end with no scroll of its own. A reader anchored on a message row (scrolled into history) while a prompt is open is untouched when the prompt leaves, so the #1304 growth mask below still holds; and a prompt replaced directly by another (`promptRows` staying greater than zero) keeps today's behavior — the reader stays on the new prompt's rows rather than being pinned down, left open deliberately rather than resolved.

**The accepted-send signal fires from the one place a send is accepted, not from a second path.** `ThreadViewModel.sentMessages` (`Channel<Unit>(Channel.CONFLATED).receiveAsFlow()`, mirroring `attachmentRefusals`) is sent from inside `sendInLocalWindow` — [#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311)'s single point both `sendMessage` and `sendWithAttachments` pass through — only after `send()` returns. A thrown send (swallowed by `launchGuardedRepoCall`), a blank send, a not-connected skip, or a stopped upload never reaches it, so none of them moves the list. `MainActivity` passes `vm.sentMessages` to `ThreadScreen` beside `attachmentRefusals`.

**Recreation decides from the restored position, which replaces #981's `drop(1)`.** The first frame collected after `FollowNewestEnd` recomposes has no `previous` to compare against, so `followStep` sets `following` from where the restored `LazyListState` actually is rather than special-casing the first value. A position restored away from the newest end is therefore not following, the same outcome #981's `drop(1)` produced by skipping its own first emission — but as a direct consequence of deriving following from position, not a recreation-specific carve-out.

Covered by `ThreadListFollowTest` (`app/src/test/.../thread/`) for the pure rule — the first frame, growth while following and while not, an anchor-offset change within and beyond the tolerance, an unchanged (overscroll) frame, a refused pin followed by a growth pin, a pin landing at the end, a new prompt pinning only while following, a history page moving nothing, both the delta retry and the finger-lift retry on a reply refused at arrival, and (#1449) a prompt leaving from under the reader following and pinning versus a prompt leaving while the anchor is a message row changing nothing — and by `ThreadScreenFollowTest` (`app/src/sharedTest/.../thread/`, 30-row lists) at the screen level: a swipe at the newest end that cannot move the list, then a new row and a streamed delta, both in view; a tool result and a queued row staying in view while following and not moving the reader while not; a scroll refused mid-stream costing one scroll with later growth still followed; a reply whose pin is refused at arrival followed once the finger lifts; an accepted text send and an accepted attachment send after scrolling up, through a real `ThreadViewModel`, both bringing the newest row and the reply into view; a refused send leaving the list where it was; a `StateRestorationTester` recreation restored away from the end staying put while one restored at the end keeps following; and (#1449) `a_reader_on_a_prompt_resolved_elsewhere_follows_the_reply_that_follows` — a reader resting on the permission card with its Cancel row scrolled out of view, the prompt resolved elsewhere, a short complete reply composed at the newest end with no scroll by the test — beside `a_reader_in_history_while_a_prompt_is_open_stays_there_when_it_is_resolved`, which proves the history-reader case the rule leaves untouched. The existing `ThreadScreenNewestRowTest`, `ThreadInlineQuestionTest`, `ThreadScreenModalTest` and `ThreadScreenHistoryTest` suites — the #185/#777/#981/#1305/#1306 regression coverage — pass unchanged against the new rule.

A streamed delta in these screen tests is revealed by a clock-driven `produceState`, so an assertion taken right after `waitForIdle` can still see the previous text; the tests wait for the revealed text itself before checking list position.

For background blocks, the growth signature includes all moved delivered tool rows, even when
the newest row key stays unchanged or the run is collapsed. In-place output growth therefore pins
a reader who is following; it leaves an older reader's keyed pixel anchor alone.
`BackgroundAgentBlocksScreenTest.inPlaceBlockGrowthPinsFollowerAndKeepsHistoryAnchor`
covers expanded output growth and the older-reader anchor (#1783).

### Collapsing runs of consecutive tool rows (#1635)

With `AppPreferences.collapseToolUses` (#1634, default on) true, `ThreadScreen` runs
`foldToolRuns` after queued joining and background-agent placement: a maximal run of two or more
adjacent tool rows (sub-agent rows included — they are tool rows too) becomes one `ThreadRow.ToolRun(runId, tools, expanded)`
header, "Using tools: N", with a down chevron. Tapping it expands to the header (now an up chevron)
followed by the run's own tool rows, flush, keeping their sub-agent indent — the same flush join
[#1577](#consecutive-tool-rows-sit-flush-1577) gives adjacent tool rows elsewhere. A lone tool row
with no tool neighbour, and every non-tool row (assistant text, a user message, a queued row, a
delimiter, a banner), passes through untouched and ends a run. Inside a joined background-agent block
the exception is attributed prose: it stays in the block's run, and a lone Agent with prose forms one
([#1827](#attributed-assistant-prose-in-agent-blocks-1827)). `ThreadRow?.isToolRow()` moved from a
private helper in `ThreadScreen.kt` to `internal` in `ThreadRow.kt` so this fold and the #1577
neighbour check share one predicate. With the setting off, `foldToolRuns` is skipped and the thread
draws the projected rows without tool-run headers. Background-agent placement and block boundaries
still apply with collapse off.

Both run folding and flush joins stop when `agentBlockId` changes: a moved family cannot merge
with another family or adjacent ordinary tools. On a late join or parent backfill, `carryRunExpansion`
transfers expansion from previously open runs to every resulting run receiving their tools, including
already-loaded descendants. A moved tool left alone retains pending opening intent until a child
forms a run. Both `pendingOpenTools` and `expandedRuns` use conversation-scoped `rememberSaveable`,
so recreation before that child arrives preserves intent and matching ids in another conversation
cannot inherit it. Intent is spent when applied; ordinary updates and finish do not reopen a run the
reader deliberately closed. Individual body expansion stays keyed to the original message id.
JVM transition tests and screen restoration tests cover lone-Agent and parent-backfill splits,
recreation before run formation, spent intent and conversation isolation.

**The run's identity is its first tool row's message id**, so a run that gains new tool rows at its
end keeps the same `runId` and, with it, its expanded state — a run never collapses just because
another tool call joined it. `expandedRuns` is `rememberSaveable` (tightened from plain `remember` on
PR #1653's verifier review, [MUST FIX]), so an open run survives rotation and a back-stack return, the
same guarantee the individual tool rows inside it already have via their own `rememberSaveable`.

**Trailing status** reads the run's own tool calls, not a stored aggregate: the running spinner while
any tool in the run is `Running`, "K failed" in the error colour with the error icon when K tools are
`Failed` or `Denied`, otherwise the done check — reusing the tool row's `cd_tool_running`,
`cd_tool_failed` and `cd_tool_done` content descriptions, which is also why the existing scripted
`tool`/`tool-failed` scenarios and the live `interactiveTurn_toolPrompt_rendersToolStepInThread` keep
passing unchanged: a lone tool row is unaffected, and a collapsed run's status icons carry the same
descriptions those scenarios already match on. Running and failed are not mutually exclusive — a run
with both shows "K failed" beside the spinner rather than picking one signal to hide.

**Adding `ThreadRow.ToolRun` broke the exhaustive `when` in `ThreadRowsTest`** the first time this
fold was wired in: any test with an exhaustive `when` over the fold's output needs a branch for every
new arm, which is a compile-time signal, not a runtime one, so it surfaces immediately rather than as
a flaky failure. The fold's own unit coverage lives beside, but not inside, that file —
`ToolRunFoldTest` (`app/src/test/.../thread/`) covers run boundaries at assistant text, a session
boundary and a banner, a lone tool row, a tool message with a null `toolCall` (not a tool row), nested
sub-agent rows joining the run, expanded order, identity holding as new rows join, and `listKey`
uniqueness across a folded list (`"tool-run:<runId>"` is a fifth distinct namespace, and `runId` being
a message id keeps it unique the same way `withMessage`'s upsert does for `msg:` keys).

A screen-level `ToolRunCollapseTest` (`app/src/sharedTest/.../thread/`) covers the composable
end to end: collapse/expand both directions with the flush join and sub-agent indent preserved; a run
staying expanded as a new tool row joins it; the three status states plus a live count change; and
the setting toggled off, on and off again on an already-open thread without remounting it — `MainActivity`
collects `collapseToolUses` with `collectAsStateWithLifecycle`, so toggling the Settings switch updates
an open thread live. No new rung-3 scenario: the technical notes' reasoning above (status-icon content
descriptions kept, lone tool row unchanged) is why the existing live and scripted coverage stays valid
as-is.

See [`ToolCallRow`](tool-call-row.md#consecutive-tool-rows-sit-flush-1577) for the flush join the
expanded run reuses, and the `AppPreferences.collapseToolUses` setting itself (#1634).

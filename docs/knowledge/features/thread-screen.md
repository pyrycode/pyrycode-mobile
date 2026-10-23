# Thread screen

Outer shell for the `conversation_thread/{serverId}/{conversationId}` route. `ThreadScreen` owns the frame, message region and composer placement; its linked topics below cover state, rows, overlays, sheets and tests. The current [Figma thread frame](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) governs the static dark layout. See [shared typography](shared-typography.md) for text roles.


A task-tracking link alone does not make a running shell a background-only task.
The foreground status and elapsed reading remain eligible until the actual tool
result arrives. A tracked task that continues after that result stays in the task
roster without keeping the foreground tool label alive. Existing source, parent and
live-progress checks still apply.

## Map

Split on 2026-09-05 to keep this document under the 50000-byte cap the docs guard enforces. Each section named below moved verbatim, heading and anchors intact, into its own document:

- [Thread screen — shape](thread-screen-shape.md) — `Shape`
- [Thread screen — how it works, ViewModel state](thread-screen-how-it-works-state.md) — `SavedStateHandle.get<String>("conversationId").orEmpty() (lifted to a private val)`, `combine(observeConversations, observeMessages, pendingWorkspacePicker).stateIn(WhileSubscribed) — three upstreams since #137`, `observeConversations(All).map { firstOrNull } — option (c) for displayName derivation`, `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = ThreadUiState(id, id))`, `private fun Conversation.displayName() — re-declared, not extracted`, `Display-name fallback chain`, `Free rename re-emission`, `items and queuedMessages stay two ThreadUiState fields — the join with the backlog is render-time, not VM-time (#782)`
- [Thread screen — how it works, the list, the chip, the empty state and the status row](thread-screen-how-it-works-list-and-status-row.md) — `LazyColumn(reverseLayout = true) — established in #126, populated in #246, dimmed in #136, nested in a Column since #201, rows folded with the queued backlog since #782`, `Workspace-chip wiring (post-#137)`, `Empty-state branch (post-#138)`, `Status-row wiring (post-#145)`
- [Thread screen — how it works, the sheets](thread-screen-how-it-works-sheets.md) — `Status Sheet hosting (post-#254)`, `ChannelInfoSheet hosting (post-#226)`, `ChannelInfoSheet Archive/Delete + pop-back nav (post-#227)`
- [Thread screen — how it works, overlays, retry and the app bar](thread-screen-how-it-works-overlays-and-app-bar.md) — `Connection status placement`, `Thinking-indicator placement (post-#407)`, `Thread top overlay placement (post-#1002)`, `Interrupt-affordance placement (post-#459)`, `Stall-promotion-banner placement (post-#396, retired from the screen in #883)`, `Permission-modal overlay placement (post-#446)`, `fun retry() — non-suspend, VM owns the launch`, `connectionState: StateFlow<ConnectionState> — same lifetime as state`, `ThreadTopAppBar — Figma 16:8 chrome`, `Modifier ordering inside the body`
- [Thread screen — testing](thread-screen-testing.md) — `Testing`
- [Thread screen — previews and edge cases](thread-screen-previews-and-edge-cases.md) — `Previews`, `Edge cases / limitations`
- [Thread screen — wiring](thread-screen-wiring.md) — `Wiring`, `Koin binding`, `Destination block`, and the composer topic pointer; split for #2388.
- [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md) — split out 2026-09-24: `Composer draft ownership`, `Composer pending attachments`

The sections that stay here: `## What it does`, `## How it works`, `## Configuration`,
`## Related`, and a link to the extracted wiring topic.

## What it does

**Production thread ownership.** The screen uses daemon-built items from its saved
host and exact negotiated connection. Production requests both thread and interactive
support. Missing support suppresses saved content. Offline saved facts grant no live
support, prompts or progress. Retiring a connection clears its transient state at once;
frame-paced rendering cannot retain a retired control.

Each destination holds its own query lease. Closing one destination leaves another
destination's paging and catch-up active. Read confirmation requires current certified
coverage and sight of the exact rendered revisions. A retry retains its producing
connection and cannot move to a replacement. Saved file-offer notices reuse the existing
attachment controls after identifier validation and display-name cleaning.

Session cost comes from certified, settled root terminals for the session named by
the current connection's conversation summary. The latest positive finite estimate
replaces the previous value, including on a failed turn. Child turns, other sessions,
quoted numbers and retired sources cannot supply it. The legacy demo retains its
existing live-event reading.

Phone-uploaded originals retain their acknowledged file name and media type under
the exact host, conversation and attachment identity. Reopening the original uses
that metadata even when the daemon supplies only the attachment identifier.


**Supplied send reconciliation (#1986).** A send immediately adds one provisional
**Sending** row owned by the client. Empty acknowledgement is receipt only: it
neither delivers nor removes feedback. An admitted daemon `user_message` with the
same host, conversation, originating device public identity and client message ID
retires that feedback, including through live, catch-up or page recovery without
an acknowledgement. Peer ID collisions and equal text cannot settle it. Late
acknowledgements/errors cannot recreate the row. See [send ownership](remote-conversation-repository-send-create-promote-rename.md#sendmessageconversationid-text--the-first-mutation-346).

Daemon items own accepted lifecycle and placement. Shown unordered queued/lost items
(including peers') form the tail in acceptance-item-ID order; queued rows show
**Waiting**. Unresolved
local sends follow in local send order. Delivery moves the same scoped daemon item
key into history at daemon `order`, without a waiting copy or reordering other
ordered rows. A hidden drop disappears; explicit shown unordered `lost` retains
its text and **Lost** label without delivered styling or queue controls. Sending
has no controls; queued items with malformed handles retain text without actions.
Omission from a batch, disconnect, timeout and queue disappearance prove neither
delivery nor loss. [Guarded actions](queued-backlog.md)
wait for admitted facts before changing rows. Capability activation and combined
deterministic/real-daemon proof remain with #1988.

Dismissal reasons and attachment “File saved” use inert Default pills at the bottom
of the [top-overlay stack](thread-top-overlay.md#the-default-confirmation-pill-1851).
They share a conversation-keyed FIFO independent of errors: each occurrence, even
equal text, receives 4,000ms adjusted for accessibility text/icons without controls.
Leaving or switching conversation cancels active and queued confirmations. The
modal-id effect admits into a composition-owned scope so a later dismissal cannot
cancel an earlier one; unrelated recomposition does not replay it. Reopening retains
the host fold’s existing latest-dismissal behavior. Pills overlay messages without
shifting content; the thread has no confirmation snackbar host.

On the legacy route, queued rows expose an independent **Send now** action before drop only when the current
session's fresh capability report explicitly enables mid-turn input (#1642). Settings replacement,
session change and owning-host disconnect invalidate stale support. A tap uses the destination's
repository without confirmation or optimistic row movement; failure uses drop's existing inert
treatment. Backlog removal does not establish delivered position: the first modern delivered
push or stored history delivery does, and a late tap can open the next turn. Parked own echoes
read below the running turn in snapshot FIFO order, independent of temporary store reservations.
Delayed drain snapshots cannot move an established delivery into its answering reply (#1655). See [queue control and ordering](queued-backlog.md)
and [row geometry/accessibility](queued-backlog-section.md#styling).

Opening or reopening a thread shows the current text of a streaming row created
before opening immediately, even if it has never received `turn_end`. Appended
text reveals progressively, and a reply first arriving after opening starts with
no visible text. `ThreadScreen` remembers the phone-clock opening time per
conversation and passes it to [MessageBubble](message-bubble.md#streaming-variant--progressive-reveal--blinking-caret-since-184).
A fresh thread composition captures a new time, so arrived text does not replay.

The thread follows [Figma `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).
Its message list fills the screen area below the system status bar and above the IME,
scrolling behind the full-width translucent, progressively blurred header and composer.
The frame glow and scrim remain behind the thread; foreground controls stay sharp.
Rows own their 20dp gutters, without a second list inset.

The header's visible content row starts 24dp below the screen-area top, with a 28dp
row, 16dp rule gap and 1dp rule: 69dp total, retaining 48dp Back and overflow targets.
At the oldest end, measured header height plus 28dp places the oldest row below the
rule; short threads keep their top alignment. Top-overlay pills share that 28dp
clearance and the 20dp right gutter. The composer owns 16dp top/bottom and 20dp side
padding. Its measured height follows attachments and multiline drafts, excluding
IME padding. At the newest end an ordinary message surface rests 12dp above the
status band. Other row types keep their own internal spacing, owned by
[#1630](https://github.com/pyrycode/pyrycode-mobile/issues/1630); whichever ticket
integrates second must recheck the combined ordinary-row gap.

IME opening, dismissal and reopening resize the drawing viewport and lift the
composer. The remembered reverse list retains its follow-newest and history-reader
rules. See [list reservations](thread-screen-how-it-works-list-and-status-row.md),
[chrome and overlays](thread-screen-how-it-works-overlays-and-app-bar.md#threadtopappbar--figma-168-chrome)
and [hardware evidence](thread-screen-testing-coverage.md#testing).

Complete thread content publishes at most once per actual display frame during
snapshot/live bursts; unrelated composer and action state remains responsive.
Rows and exact read evidence travel together. See
[worker admission, pacing and cancellation](thread-screen-how-it-works-state.md)
and [frame probes](thread-screen-testing.md#frame-paced-content-1968).

Closed tool bodies and descendants behind omitted ancestors keep summaries until demanded. Retention, coverage and wire delivery stay independent; summaries never prove sight. See [deferred presentation probes](thread-screen-testing.md#deferred-authoritative-presentation-2375).

**Missing tool bodies on expansion (#2388).** Only visible, individually expanded
Tool use rows demand missing details, including an agent-launch body where the
existing tool presentation needs it. Closed rows, hidden descendants and opening a
grouped run alone issue no body reads. Complete content for the exact held identity
and revision renders immediately, including unchanged reopen and offline reuse.
Fetched bodies remain in the host snapshot's in-memory ID/revision map; existing
complete-fact disk persistence is unchanged. A process restart cannot restore that
fetched-body map. Hosts without expansion support retain complete delivery.

The expanded row uses its existing body/code surface for Loading, loaded contents,
a generic failure with explicit Retry, or offline missing-details text. An
explicitly empty input/output object becomes loaded only after validated data and
its separate matching success marker. Refusal, missing/partial data, send failure,
timeout and cancellation cannot become empty success. Failure is sticky across
recomposition; Retry applies only to the current still-demanded body.

The bound screen consumer follows the current presentation and producing connection
through the ViewModel and negotiated repository. Equal active/queued targets
coalesce. Collapse, scrolling out of visibility and destination/lifecycle retirement
withdraw demand. A current binding must withdraw even while frame pacing holds an
older presentation: the lane intersects stale targets with that consumer's already
admitted keys. Stale snapshots cannot add targets or authorize Retry. Checking
snapshot freshness before withdrawals would let a collapsed queued body start when
another body completes. Other consumers keep their own demand; abandoned reads
still drain before lane reuse.

Loading and body completion grant neither history coverage nor read sight. The
existing viewport, exact rendered revision and certified-checkpoint requirements
still apply after contents render. See [connection-owned body queries](remote-conversation-repository.md#missing-tool-body-queries-2388)
and [focused evidence for the parent compact handoff](../../e2e-interactive-stream.md#missing-tool-body-evidence-2388).

**Retained expansion capacity (#2390).** Bodies and child pages share inclusive
16,777,216-byte/1000-summary limits per host across conversations, open and closed.
Costs count serialized UTF-8 body objects, original raw summaries and cursors once
per entry. Loaded expanded demand and descendant ancestry stay protected across
consumers. Closed entries use cross-kind LRU; retained reopen refreshes use without
refetch, while recomposition does not. Atomic capacity rejection keeps prior
children/cursor and shows sticky inline Retry. Closing demand can free capacity
for explicit Retry; evicted reopen reads again. See [retention rules](remote-conversation-repository.md#retained-expansion-budget-2390).

**Direct agent children (#2389).** A visible, individually expanded agent demands
its first missing page of direct-child summaries through the same owned lane.
Nested agents demand only their own children when individually expanded. Opening
a group exposes loaded member summaries with bodies closed; recomposition and
hidden descendants initiate neither speculative child pages nor closed-body reads.
Body demand remains independent. Unchanged retained pages reopen without a read,
including offline, within the current in-memory snapshot.

Loading, loaded children, authoritative empty, explicit Retry and offline missing
data appear inside the expanded parent. Load more follows that parent's displayed
descendants, uses the existing inline history-control styling and requests one
certified continuation per tap; it is separate from thread-history paging. A short
page does not mean complete. Only validated separate success with `more: false`
certifies the enumeration, including marker-only empty success. Failures remain
sticky across recomposition. Cursor rejection offers Retry from the first page for
the current owner, retaining old rows until a validated replacement succeeds.

Child demand uses independent consumer targets and the current presentation,
connection and resumed destination binding. Collapse and retirement withdraw even
before the Loading presentation reaches a display frame; held presentations may
withdraw prior targets but cannot add demand or authorize Retry/Load more. Other
consumers retain their demand; abandoned requests drain before lane reuse.

Certified order-zero children stay under their parent, never in the root
queued/lost tail. A full fact without order cannot borrow placement from an older
summary. Summary `shown: false` participates in cursor accounting but renders no
row. Its inert headline stays independent of tool input and survives body loading.
Summary conversion supplies no read sight or history coverage. Final parent
revisions govern descendant reachability in either page arrival order; stale
parent pages cannot expose or demand descendants. See the [page admission rules](remote-conversation-repository.md#direct-child-pages-2389)
and [counted evidence](../../e2e-interactive-stream.md#direct-child-page-evidence-2389).

**Foreground read checkpoints (#1912, #2085).** `ThreadReadViewport` sends
`ThreadEvent.NewestContentPresented` only while the destination is resumed and
unobscured, after exact row reveal completes and its readable trailing edge is
inside the measured message viewport, below the header/top-overlay pills and
above the composer/IME. Permission/question prompts, Channel info, background-task
and status panels, editing/workspace dialogs, overflow/header menus and slash
suggestions block sight even though the destination remains resumed. Opening,
receipt, scrolled-away updates and unchanged recomposition do not establish sight.
A shown legacy Agent block must itself be presented; hidden task lifecycle is receipt.

For the supplied authoritative presentation, coverage comes only from the store's
certified `checkpoint`, never `completedVersion`, a batch maximum or item revisions.
Usable `lastShownVersion` and confirmed `readUpTo` are separate summary facts.
Unavailable shown metadata, partial catch-up, repair/reset, coverage below shown,
rows beyond coverage or no shown-row evidence reaching the watermark withhold a
checkpoint. Hidden/state-only changes grant no sight. Qualification is bounded by
the shown watermark and requires the newest shown row currently visible plus sight
of **every shown row revision above confirmed read**, including an older row appended
between confirmed read and the newest watermark. Seeing only the newest row can
otherwise mark an intermediate offscreen append read.

Both viewport selection and checkpoint qualification use
`AuthoritativeThreadPresentation.newestReadKey`: the last delivered row carrying
admitted `shownRowRevisions` evidence, even beneath Sending, Waiting or Lost tails.
Those tails supply no read revision and cannot certify reads by themselves. Viewed
history and a subsequent delivered reply can qualify while feedback remains;
exact sight, summary bounds, unread intermediate revisions and certified coverage
still apply. Selecting the final rendered row instead would let a retained Lost
row block reads indefinitely.

Deterministic regression evidence for #1986 (2026-10-14): the fresh focused XML
recorded **135 executed/passed, 0 failed/errors, 0 skipped**, including
`AuthoritativeSendReconciliationTest`, `AuthoritativeSendRepositoryTest`,
`AuthoritativeSendScreenTest`, `AuthoritativeQueueActionTest` and retained legacy
queue/Send now/geometry probes. All four read regressions passed:
`AuthoritativeReadSightTest.sendingWaitingAndLostTailsPreserveExactHistoryReadRequirements`
and `AuthoritativeReadViewportTest.sendingTailAllowsViewedHistoryAndReplyWhileFeedbackRemains`,
`waitingTailAllowsViewedHistoryAndReplyWhileFeedbackRemains`, and
`lostTailAllowsViewedHistoryAndReplyWhileFeedbackRemains`. XML was retained at
`/tmp/builder-1986/rework-focused-results/` at documentation time. The
[verifier's final evidence](https://github.com/pyrycode/pyrycode-mobile/pull/2165#issuecomment-6178853468)
records 5,615 unit/shared tests passed with 0 failures/errors/skips; the configured
UI gate executed/passed 261 with 0 failures and 1 unrelated skip
(`renameAtFigmaViewport`), and scripted-all executed/passed 22 with 0 failures/skips.
These are deterministic results, not #1988's pending real-daemon acceptance.

Authoritative candidates and the composition-local seen ledger use scoped list keys
and exact revisions. Equal banners, refusals, dividers and compactions cannot share
sight through displayed-value equality. The ledger resets when the presentation
instance changes; replacement/removal and host, conversation, epoch or generation
changes retire readiness. `ThreadViewModel` validates the carried key/revision proof,
active presentation and composition binding, exact owner and captured facade
connection identity before forwarding an increasing checkpoint. A reconnect cannot
reuse a mounted composition's sight. Host availability fences authoritative sight;
legacy/local fallback retains its existing behavior.

The supplied-input seam remains dormant until #1988 activates capability selection
and authoritative input collection; #1984 owns sync/cache integration. #1988 also
owns `InteractiveStreamE2ETest.interactiveTurn_attentionDot_followsARealTurn` and the
combined partial-reconnect/unseen-content daemon acceptance. This slice changes no
follow-to-latest presentation and claims no live proof. Qualified work uses the
existing host-owned retry path after screen closure or disconnect; only daemon
confirmation advances shared read. See
[daemon conversation read marks](remote-conversation-repository.md#daemon-conversation-read-marks).

On the legacy route since #1953, UI-local read bookkeeping holds only one `ThreadReadCandidate`: the
newest rendered delivered row's exact version and list key, with layout, readable
edge and reveal readiness. Invisible info banners do not become candidates; a queued
newest row blocks qualification. Version/key changes and removal followed by
reintroduction create fresh bookkeeping. Retired callbacks close over the retired
holder and cannot qualify its replacement. This bound excludes existing thread rows
and repository evidence; nonvisual-tail advancement and background-agent evidence
keep their existing rules.

Only that candidate receives read-position measurement. Message edges remain on the
inner readable column, inside bubble padding; tool edges remain on the tool surface,
excluding trailing row spacing. Whole lazy-item bounds cannot substitute for either.
The tall-row rule requires the readable trailing edge inside the viewport, without
requiring the whole row to fit. `ThreadReadViewport` reads candidate fields, measured
viewport bounds and visible list membership inside `snapshotFlow`; the list host
passes holders without reading their frame-updated values during composition. Moving
the edge alone would leave layout/reveal subscriptions and historical-map scans
behind: all three belong to the single candidate.

Legacy receipt coverage and a daemon-confirmable displayable target are distinct
(#2198). In scripted ping, the exact finished reply carried complete receipts through
12, but visible content ended at 9; successful completion 10 changed the row without
adding unread content, and metadata 11–12 could not raise the daemon's
`LatestDisplayableEntryID` clamp. Resumed, unobscured qualification and confirmation
of 9 had succeeded; waiting for 12 misdiagnosed a test target as a read-write failure.

The test-only `awaitScriptedReplyReadCheckpoint` first retains the exact completed
live reply and its complete `ThreadReadEvidence.checkpoint` proof. Only then may its
bounded `requestHistory` classify receipt ids using `contributesToUnreadWatermark`;
the target is the highest displayable receipt belonging to that retained reply.
History merges can populate receipts and finish rows, so fetching first could conceal
missing live delivery. A later projection cannot replace the retained proof. Unknown
receipts, holes/barriers, unfinished or wrong replies and metadata-only claims still
withhold a target. Receipt classification supplies no foreground sight, and local
pending writes supply no daemon confirmation. The later peer-read step confirms the
exact received durable post id rather than the maximum bookkeeping id.

`ScriptedReplyReadCheckpointTest` covers both classifier rejection and orchestration:
missing receipts/completion must prevent the query, and projection replacement during
classification must preserve the retained proof. Linux validation recorded 79 focused
passes, 2 ping passes and 22 scripted-all passes, all without failures or skips; UI
recorded 334 passes and one skip. See [final counts, revisions and raw evidence](../../e2e-interactive-stream.md#scripted-read-target-evidence-2198).

Required device probes live in `ThreadReadViewportTest` in `sharedTest`, selected
through `ThreadReadViewportDeviceTest` in `androidTest`. They cover lifecycle,
scrolled-away updates, dialogs, production-host scroll composition, replacement tool
versions and the tall-row edge rule. See [testing guidance and counted evidence](thread-screen-testing-foreground-read-tracking.md#foreground-read-tracking-1912-1953).

`ThreadScreen` uses a `Scaffold` with `ThreadTopAppBar`, a message-region `Box` containing either `EmptyThreadState` or the remembered reverse-layout `LazyColumn`, and a composer column in `bottomBar`. Connecting and Reconnecting appear in the composer status band; Offline Retry and pairing Re-pair appear in `ThreadTopOverlay`, pinned over the message region without reflowing the list. The list folds delivered and queued rows, draws every row at full opacity (session boundaries included, since #1578), and leaves each row's rendering to its own component. The footer, attachments and input keep their existing behavior and visual ownership. The header menu uses the shared Actions overlay below the live button (#1666), hosted over the Scaffold with IME-constrained scrolling and header-priority Back; see [overlay wiring](thread-screen-how-it-works-overlays-and-app-bar.md#header-actions-overlay-1666).

Back, title and overflow stay reachable through their callbacks. The overflow opens `ThreadOverflowMenu` beneath its control; screen-owned state hosts the rename, save-as-channel, status and other modal flows described in the linked topics. Workspace picker state remains in the screen contract for older flows, but the current thread frame and overflow expose no workspace chip, label, picker trigger or workspace-edit action.

## Supplied session readings and presentation (#2084)

Durable item content and actionable live state have separate sources. Current-session
activity, settings and prompts pass through repository session admission before the
existing ViewModel/status/modal presentations consume them. Summary bindings identify a
session without proving a running process. A replacement retires session-local readings
and callbacks while preserving durable items, supplied pending/queue state and account
rate readings. [Held prompt collection](relay-repository-coordinator-seams-and-passthroughs.md#modal-event-seam-445-and-the-hoisted-hostmodals-fold-492)
keeps an admitted request visible even if it arrived before the screen subscribed.

The `projectAuthoritativeThread` overload accepts `AuthoritativeLiveProgress` from
`observeAuthoritativeProgress`: tool elapsed-time and task progress maps plus producing
session identity. It joins only to active supplied items whose recorded session matches
that identity, independently of the legacy task roster. Reusing a tool/task id in a
later session cannot attach old progress to the new row. Family clears remove only the
matching transient map; duplicate clears after fresh state preserve it. Progress changes
no durable item, revision, checkpoint or read evidence. Three-argument presentation
callers retain their existing contract; #1988 owns collecting these inputs in production.

Answered permission/question `notice` / `prompt_answered` items remain inert historical
content across replay and a new connection. Only outstanding supplied requests can be
answered. Clear/replacement never sends an answer; captured request identities and
ViewModel guards keep retired taps from acting on replacement prompts. Recollection or
configuration change must not replay an action. `AuthoritativeSessionScreenTest` covers
session replacement and retired rendered callbacks, real answered-history records and
fresh connection/repository/ViewModel ownership, plus supplied task/tool progress without
a legacy roster. Remounting the same ViewModel alone cannot prove reconnect behavior.

\#1988 owns capability activation, delivery and the rung-3
`InteractiveStreamE2ETest.interactiveTurn_newSession_rendersSessionBoundaryDelimiter`
proof with current-session status freshness; this supplied-input slice claims no live
result. #1986 owns pending sends/queue reconciliation, #1947 legacy context reset.
Existing activity/prompt geometry and assets remain unchanged. See
[repository retirement](remote-conversation-repository-status-projections.md#supplied-session-ownership-and-family-retirement-2084)
and [wire ownership](mobile-protocol-v2-wire-layer.md#session-scoped-live-admission-2084).

## How it works

Split on 2026-09-05 into four documents, listed in § Map above: [ViewModel state](thread-screen-how-it-works-state.md), [the list, the chip, the empty state and the status row](thread-screen-how-it-works-list-and-status-row.md), [the sheets](thread-screen-how-it-works-sheets.md), and [overlays, retry and the app bar](thread-screen-how-it-works-overlays-and-app-bar.md). Every `###` subsection kept its heading and anchor.

The current session's `state.runConfig.memorySearch` feeds [Channel info](channel-info-sheet.md) through `toChannelInfoUiModel`, each [session boundary](session-boundary-delimiter.md), and [channel overflow](thread-overflow-menu.md) through `ThreadTopAppBar`. The same report drives all three surfaces after a replacement report or conversation change: Channel info shows installed, disabled and unknown states, while the boundary and channel menu offer Install only for confirmed absence. An omitted report is unknown, not absent. Memory search retrieves stored knowledge. It does not capture the conversation or give the agent access to all messages above a session boundary.

Connection readings from the [Connecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740) and [Reconnecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4657) dark frames share the composer band. [Offline](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910) uses an overlay Retry pill; rejected pairing shows Re-pair there instead.

The overlay's exact-host Retry has live coverage in
`InteractiveStreamE2ETest.interactiveTurn_offlineRetry_reconnectsSameHostAndReplies`
([#1286](https://github.com/pyrycode/pyrycode-mobile/issues/1286)). A harness-owned
daemon failure produces the actual Offline pill while the thread stays open;
restarting the same host and tapping Retry clears it and permits a new rendered
real-Claude reply without re-pairing. The 2026-09-30 full live XML includes this
passing, unskipped method: 44 executed, 0 failed, 0 skipped. See the
[coverage and evidence boundary](../../e2e-interactive-stream.md#offline-retry-proof)
and [dispatcher evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1286#issuecomment-5914205992).

The five transient readings in the composer's [status band](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643) follow the [input status component `533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), inspected with the [pill variants `347:6618`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6618) and thread `16:8` on 2026-09-29. Thinking has the supplied snowflake glyph; retry, compaction and Reset use a fixed-length rotating arc; outcomes use the shared error pill. Figma specifies no dedicated frame for the latter four readings or their combination with a task pill, so their component treatment is the reference, not a full-screen pixel match.

The running-task pill shares the reading's 24 dp band at normal text scale, or sits at its right end alone. Only this thread caller gives the shared primary-container `NoticePill` a 24 dp minimum height (no minimum width since [#1628](https://github.com/pyrycode/pyrycode-mobile/issues/1628)); its 6 dp corners, `bodySmall` label and 8/4 dp padding follow the inspected Figma task-pill node `568:3162`, whose 104 × 24 dp frame is the hug width of "2 tasks running", not a width floor. The band and pill can grow for larger text. A zero count removes the pill; a live count uses the client-owned singular or plural label. Tapping it opens the same [background-task panel](mobile-modal-callers.md) as the count-free top menu; that topic owns finish-summary visibility and cut-marker behavior. The [status-band layout](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643) covers its interaction with readings and nearby controls.

The [model-refusal row's switch-back offer](model-refusal-row.md#switch-back-1360) owns its failure
feedback inline. A refused or failed write leaves an enabled retry button and its retry line immediately;
only this caller suppresses the shared run-configuration failure notice, while ordinary model/effort edits
still report there (#1615). Retry clears the inline failure during pending. The visible outline is a
32 dp minimum independent of the 48 dp touch target; wrapped destinations and enlarged text grow it so
the retry line stays below all label lines. Button extensions invoke switch-back without toggling details.

Local failures no longer use a bottom snackbar (#1747). They queue as inert Error pills at the end of the
[top overlay](thread-top-overlay.md#the-transient-error-pill-1747), in the order they happened, each for the
accessibility-adjusted Short time, and leaving the screen cancels them. Only Saved and the dismissed-elsewhere
prompt still use the bottom snackbar.

## Wiring

See [thread destination and Koin wiring](thread-screen-wiring.md#wiring).

## Configuration

- **Dependencies:** no new entries. `ConversationRepository` was already on classpath; `kotlinx.coroutines.flow.stateIn` rides in via the existing `kotlinx-coroutines-core` (catalog: `libs.coroutines.core`). No `gradle/libs.versions.toml` edits.
- **Strings:** `R.string.cd_back` reused, `R.string.cd_more_actions` added in #139 (`<string name="cd_more_actions">More actions</string>`), `R.string.cd_thread_status_expand` added in #145 for the trailing icon on `ThreadStatusRow`, retired with that row by [#808](../codebase/808.md) and carried onto the [Status sheet opener](thread-composer-footer.md) it replaced it with. `R.string.thread_re_pair` ("Pairing error - Re-pair") added in [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843) for the status area's Re-pair button, moved into the [Top overlay's pairing pill](thread-top-overlay.md#the-pairing-pill) by [#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002); `R.string.thread_notice_dismiss` ("Dismiss notice") added by #1002 for the usage pill's dismiss X. `R.plurals.thread_task_count` ("%1$d task running" / "%1$d tasks running") added by [#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) for the status band's task-count pill — a client-owned plural, never daemon-authored text. Naming follows the project's `cd_*` content-description convention.
- **Route identity survives process recreation.** The back-stack entry restores both `serverId` and `conversationId` into `SavedStateHandle`; the factory rebinds the saved host and `stateIn` re-subscribes on collection. Thread data is fetched again, not persisted as route state.

## Related

- Ticket notes: [`../codebase/126.md`](../codebase/126.md) (skeleton), [`../codebase/139.md`](../codebase/139.md) (TopAppBar promotion), [`../codebase/188.md`](../codebase/188.md) (input bar + `sendMessage`), [`../codebase/201.md`](../codebase/201.md) (ConnectionBanner wiring), [`../codebase/137.md`](../codebase/137.md) (WorkspaceChip + WorkspacePicker hosting + `ThreadUiState` widening to six fields), [`../codebase/246.md`](../codebase/246.md) (LazyColumn body filled with typed `ThreadItem` render loop + seventh `items` field on `ThreadUiState`), [`../codebase/136.md`](../codebase/136.md) (above-delimiter opacity + `itemsIndexed` migration + `mostRecentSessionBoundaryIndex` helper + `previewItemsWithBoundaries`), [`../codebase/138.md`](../codebase/138.md) (empty-state prompt + `if (!hasMessages) … else …` branch around the `LazyColumn`), [`../codebase/145.md`](../codebase/145.md) (ThreadStatusRow stacked above ThreadInputBar in bottomBar; `model`/`effort`/`tokenPercent` on ThreadUiState; the hoisted `onExpandClick` placeholder that #254 later deleted), [`../codebase/251.md`](../codebase/251.md) (sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher; the [`ThreadOverflowMenu`](thread-overflow-menu.md) composable lands exported but not mounted), [`../codebase/252.md`](../codebase/252.md) (mounts `ThreadOverflowMenu` inside `ThreadTopAppBar`'s `actions` slot, hoists `var overflowExpanded by rememberSaveable { mutableStateOf(false) }`, renames `onOverflowClick` to `onOverflowEvent`, binds `vm::onOverflowEvent` at the `MainActivity` destination), [`../codebase/254.md`](../codebase/254.md) ([`StatusSheet`](status-sheet.md) shell + Model section + screen-hoisted `sheetVisible` + `onModelSelected` parameter swap), [`../codebase/141.md`](../codebase/141.md) (rename dialog wiring — `RenameDialog` composable hosted as a `Scaffold` sibling, `showRenameDialog: Boolean` field on `ThreadUiState`, `pendingRenameDialog: MutableStateFlow<Boolean>` as the fourth `combine` source, two new `ThreadEvent` cases `RenameSubmit(name)` + `RenameDismiss`, three new arms on `onOverflowEvent` that replace the [#252](../codebase/252.md) `Rename` no-op), [`../codebase/185.md`](../codebase/185.md) (streaming auto-scroll — `LazyListState` hoist + `NestedScrollConnection` user-input filter + two `LaunchedEffect`s on item 0's measured size and the bottom-anchor predicate), [`../codebase/226.md`](../codebase/226.md) ([`ChannelInfoSheet`](channel-info-sheet.md) host as a fifth `Scaffold` sibling — `channelInfoOpen` + three ingredient fields on `ThreadUiState`, `pendingChannelInfo` folded into `TransientDialogs`, `ChannelInfoDismiss` event, and the pure `toChannelInfoUiModel` mapper), [`../codebase/227.md`](../codebase/227.md) (the sheet's Archive / Delete wired — `Archive` close-and-pop, `Delete` / `DeleteConfirm` / `DeleteDismiss` + the `DeleteConfirmationDialog` sixth sibling + `deleteConfirmVisible` flag + the `ThreadNavigation` one-shot pop-back channel collected in `MainActivity`), [`../codebase/446.md`](../codebase/446.md) (the permission-modal overlay as the seventh `Scaffold` sibling — the `modalState` / `onModalOption` / `onModalCancel` params + the then-used snackbar host (removed in #1851) + the `PermissionModalOverlay` / `ModalOptionButton` / `dismissReasonText` private composables + the dialog-window `FLAG_SECURE`), [`../codebase/603.md`](../codebase/603.md) (deleted `tokenPercent` / `tokensUsed` / `tokensTotal` and the `STUB_*` constants #145 introduced, once [#601](../codebase/601.md) and [#602](../codebase/602.md) had both stopped reading them; stripped the matching arg from all five `ThreadScreen.kt` preview literals), [`../codebase/15.md`](../codebase/15.md) (the placeholder route this slice replaces), [#789](https://github.com/pyrycode/pyrycode-mobile/issues/789) (composer text ownership moved from `ThreadInputBar`'s `rememberSaveable` to the app-scoped [`ComposerDraftStore`](#composer-draft-ownership); no visual change), [#790](https://github.com/pyrycode/pyrycode-mobile/issues/790) (host-removal and conversation-deletion draft eviction — see [Composer draft ownership](#composer-draft-ownership); no visual change), [#798](https://github.com/pyrycode/pyrycode-mobile/issues/798) (the same two removals now also clear cached conversation content — see [Conversation cache § Removal on unpair](conversation-cache-removal.md#removal-on-unpair--forgetremovedhost) and [Caching conversation repository § delete](caching-conversation-repository.md); no visual change), [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981) (the newest-row pin — a second `LaunchedEffect` beside [`#185`](../codebase/185.md)'s streaming auto-scroll follows any new newest row into view, streaming or not, unless the reader has scrolled away; see [Thread screen — list and status row](thread-screen-how-it-works-list-and-status-row.md); no visual change)
- Specs: `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/126-thread-screen-skeleton.md`, `docs/specs/architecture/139-thread-topappbar-back-title-overflow.md`, `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/201-thread-screen-wire-connectionbanner.md`, `docs/specs/architecture/137-workspace-chip-empty-new-discussion-thread.md`, `docs/specs/architecture/246-wire-thread-items-into-lazycolumn.md`, `docs/specs/architecture/136-above-delimiter-opacity-treatment.md`, `docs/specs/architecture/138-empty-thread-state-copy-visual.md`, `docs/specs/architecture/145-thread-status-row.md`, `docs/specs/architecture/185-auto-scroll-thread-streaming.md`, `docs/specs/architecture/981-thread-pins-new-newest-row.md`, `docs/specs/architecture/252-thread-overflow-menu-mount-and-wire.md`, `docs/specs/architecture/227-channelinfosheet-archive-delete-actions.md`, `docs/specs/architecture/789-per-conversation-composer-drafts.md`, `docs/specs/architecture/790-drop-drafts-on-host-or-conversation-removal.md`, `docs/specs/architecture/843-thread-re-pair.md` (the composer status area's Re-pair action — see [overlays and app bar § trailing contextual-action slot](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643))
- Upstream: [Navigation](navigation.md) (the `conversation_thread/{serverId}/{conversationId}` route this destination consumes), [Conversation repository](conversation-repository.md) (the `observeConversations(All)` + `observeMessages` + `changeWorkspace` surfaces the VM consumes; `sendMessage` it forwards to since #188; `observeMessages` + `changeWorkspace` added since #137), [Connection state](connection-state.md) (the `ConnectionStateSource.observe()` / `retry()` contract the VM consumes since #201), [Dependency injection](dependency-injection.md) (the host-owned `ThreadDestinationFactory` binding), [Paired server store](paired-server-store.md#wiring--usage) (the `ObservablePairedServerStore.remove` hook that evicts a host's drafts on unpair, since #790)
- Child components: [Thread input bar](thread-input-bar.md) (the composer in `bottomBar`, landed in #188), [Slash-command type-ahead](slash-command-type-ahead.md) (composer suggestions anchored above the input bar, via [Options overlay](options-overlay.md); landed in [#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) — see [overlays and app bar § Slash-command type-ahead placement](thread-screen-how-it-works-overlays-and-app-bar.md#slash-command-type-ahead-placement-post-885)), [Thread composer footer](thread-composer-footer.md) (the model/effort buttons and Status-sheet opener below the input bar in `bottomBar`; landed as the `ThreadStatusRow` single status line in #145, tap-to-open the Status Sheet wired in #254, replaced by the two-button footer plus [Options overlay](options-overlay.md) in [#808](../codebase/808.md)), [Connection status indicator](connection-banner.md) (Connecting/Reconnecting in the composer; Offline Retry in the overlay), [WorkspaceChip](workspace-chip.md) (the empty-discussion chip between banner and list, wired in #137), [WorkspacePicker](workspace-picker.md) (rendered as Scaffold sibling, wired in #137), [StatusSheet](status-sheet.md) (rendered as Scaffold sibling gated on screen-hoisted `sheetVisible`, wired in #254 — Model section only; #229/#230 append sibling sections), [RenameDialog](rename-dialog.md) (rendered as Scaffold sibling gated on `state.showRenameDialog`, wired in [#141](../codebase/141.md); seeded with `state.displayName` as `initialName`, emits `ThreadEvent.RenameSubmit(name)` / `RenameDismiss` through `onOverflowEvent`), [ChannelInfoSheet](channel-info-sheet.md) (rendered as Scaffold sibling gated on `state.channelInfoOpen`, wired in [#226](../codebase/226.md); fed a `ChannelInfoUiModel` via the pure `state.toChannelInfoUiModel(now)` mapper, Rename / Change workspace emit-then-dismiss through `onOverflowEvent`; Archive / Delete wired in [#227](../codebase/227.md) — Archive closes-and-pops, Delete opens the `DeleteConfirmationDialog`), [DeleteConfirmationDialog](thread-screen-how-it-works-sheets.md#channelinfosheet-archivedelete--pop-back-nav-post-227) (private confirm dialog rendered as the sixth Scaffold sibling gated on `state.deleteConfirmVisible`, wired in [#227](../codebase/227.md)), [Permission-modal overlay](permission-modal-overlay.md) (the `PermissionModalOverlay` `BasicAlertDialog` rendered as the seventh Scaffold sibling when `modalState is ModalUiState.Open`, wired in [#446](../codebase/446.md); collects the hoisted app-level [`currentModal`](current-modal-state.md) from #445, dialog-window `FLAG_SECURE`, `Dismissed` → mapped-reason Default top-overlay pill; answering is #444), [EmptyThreadState](empty-thread-state.md) (the centered empty-thread prompt that occupies the `weight(1f)` slot when `!hasMessages`, wired in #138), [MessageBubble](message-bubble.md) (`MessageItem` row dispatch since #246), [SessionBoundaryDelimiter](session-boundary-delimiter.md) (`SessionBoundary` row dispatch since #246), [Model refusal row](model-refusal-row.md) (`ThreadItem.ModelRefusal` row dispatch, `listKey()` and `timestamp()` arms, wired in [#875](../codebase/875.md)), [Stopped-turn row](stopped-turn-row.md) (`ThreadItem.StoppedTurn` row dispatch, `listKey()` and `timestamp()` arms, wired in [#1356](https://github.com/pyrycode/pyrycode-mobile/issues/1356)), [ThreadOverflowMenu](thread-overflow-menu.md) (client-owned Actions overlay consuming `ThreadEvent`, hosted directly by the screen since #1666 — the screen owns `overflowExpanded` via `rememberSaveable`)
- Downstream remaining (each layers on top of the post-#246 render loop, not the chrome): [#141](../codebase/141.md) ✅ rename dialog wired (via `ThreadEvent.Rename` from the overflow menu — `onTitleClick` stays a `{}` no-op, a future ticket may bind it as a second entry point), [#229](https://github.com/pyrycode/pyrycode-mobile/issues/229) Effort + YOLO sections append into the existing `StatusSheetContent` `Column` (may revisit the [#254](../codebase/254.md) auto-close-on-select decision for the multi-toggle UX), [#230](https://github.com/pyrycode/pyrycode-mobile/issues/230) Context window section appends as the third section (if a `Section` shape genuinely emerges, factor then), [#208](https://github.com/pyrycode/pyrycode-mobile/issues/208) overflow "Change workspace…" (reuses `workspacePickerVisible` + the two picker handlers introduced by #137; fills the `ThreadEvent.ChangeWorkspace` branch of `onOverflowEvent`), and generic scroll-to-bottom on every new newest row — shipped by [#981](https://github.com/pyrycode/pyrycode-mobile/issues/981), distinct from [`#185`](../codebase/185.md)'s streaming-only auto-anchor: #185 re-pins only while `isStreaming = true` and only against item 0's measured-size growth, while #981 added a second, identity-keyed pin beside it that also follows a row that arrives already finalized (see [Thread screen — list and status row](thread-screen-how-it-works-list-and-status-row.md)). [#136](../codebase/136.md) above-delimiter opacity landed `0.55f` alpha over rows above the most-recent `SessionBoundary`; [#138](../codebase/138.md) landed the centered empty-thread prompt that replaces the `LazyColumn` whenever `!state.hasMessages`; [#145](../codebase/145.md) landed the `Model · effort · NN% used ▴` status row above the input bar; [`#251`](../codebase/251.md) landed the sealed `ThreadEvent` + `ThreadViewModel.onOverflowEvent` dispatcher + stateless [`ThreadOverflowMenu`](thread-overflow-menu.md) composable (exported but not mounted; `Archive` is the only branch wired today); [`#252`](../codebase/252.md) mounted the overflow menu inside `ThreadTopAppBar`'s `actions` slot (the screen owns `overflowExpanded` via `rememberSaveable`, the renamed `onOverflowEvent` parameter binds `vm::onOverflowEvent` at the destination); [`#254`](../codebase/254.md) landed the [`StatusSheet`](status-sheet.md) shell with the Model section (the row's `onExpandClick` is wired internally, the screen owns `sheetVisible` via `rememberSaveable`, and `MainActivity` binds `onModelSelected = vm::onModelSelected`).
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (the populated Conversation Thread Screen canvas; this slice ships the outer shell + the TopAppBar chrome + the composer in `bottomBar` + the connection status band and top overlay + the empty-discussion workspace chip between banner and list + the picker host at screen root + the populated message list with bubble/delimiter dispatch from #246 + the above-delimiter dim from #136 + the centered empty-thread prompt from #138 in the no-`MessageItem` branch + the status row above the input bar from #145 + the Status Sheet host at screen root from #254), subframe [`16:58`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-58) (the status row specifically), subframe [`20:100`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-100) (the Status Sheet; this slice ships the Model section region only)

## Fresh production recovery actions

The required daemon thread route raises Switch back and Compact recovery from fresh accepted additions. Restored history cannot arm either action. The current connection and session must still own each receipt. Refusal actions target a durable notice identity, so a late acknowledgement cannot clear a newer offer. See [the activation plan](../../specs/architecture/2245-production-daemon-thread.md).

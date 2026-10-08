# Thread top overlay — `ThreadTopOverlay`

The **notice surface** for the thread ([#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002)):
other conversations' attention, claude's usage-limit report, a failed MCP server ([#1345](https://github.com/pyrycode/pyrycode-mobile/issues/1345)),
the pairing-error notice, Offline Retry, conversation session errors, stopped-turn recovery and local confirmations, drawn as a right-aligned stack of
[`NoticePill`](notice-pill.md)s pinned over the top of the message area — replacing the two arms they used
to share with live turn status inside `ThreadStatusArea`.

Package: `de.pyryco.mobile.ui.conversations.thread`
(`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt`).

## Why this moved (#1002)

Since 2026-09-24 the Max plan's seven-day window has been past 75%, so claude attaches an
`allowed_warning` usage report to *every* turn until the window resets. `ThreadStatusArea`'s pre-#1002
ladder ranked that reading above resetting, compaction, the turn outcome and thinking/running-tool, so
while the reading was live none of those ever rendered — "Running Bash…", the wrap-up phase and
"Interrupted" were all invisible. Three `InteractiveStreamE2ETest` methods
(`interactiveTurn_permissionHeldTool_statusAreaNamesRunningTool`,
`interactiveTurn_newSession_rendersSessionBoundaryDelimiter`,
`interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`) failed on every branch as a result.
Moving notices out of the ladder into a pinned overlay — instead of, say, re-ranking the ladder — keeps
both signals visible at once: live turn status in the status row, notices as pills above the messages. See
[Resetting indicator § Placement](resetting-indicator.md#placement-in-the-thread) for the ladder that
remains.

## Shape

```kotlin
@Composable
internal fun ThreadTopOverlay(
    usageLimit: UsageLimitReading?,
    usageLimitDismissed: Boolean,
    onDismissUsageLimit: () -> Unit,
    showRePair: Boolean,
    onRePair: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: ConnectionState = ConnectionState.Connected,
    onRetryConnection: () -> Unit = {},
    mcpFailure: String? = null,
    onOpenMcpFailure: () -> Unit = {},
    sessionError: String? = null,
    agent: ConversationAgent = ConversationAgent.Claude,
    turnOutcome: TurnRecoveryNotice? = null,
    onCompact: (() -> Unit)? = null,
    transientError: String? = null,
    transientErrorOccurrence: Long = 0L,
    attentionPill: (@Composable () -> Unit)? = null,
    confirmation: String? = null,
    confirmationOccurrence: Long = 0L,
)
```

The usage lead no longer names the agent ([Usage-limit indicator](usage-limit-indicator.md), #1519).
Since #1678, `agent = state.agent` selects the session-error copy independently of usage copy.

Emits nothing when there is no pill to show (`usageLimit == null || usageLimitDismissed`, `mcpFailure == null`,
`!showRePair`, `connectionState != Offline`, `sessionError == null`, `turnOutcome == null`, `transientError == null`, `LocalNavigationErrorNotice.current?.currentMessage == null`, `confirmation == null`, and `attentionPill == null`) — the overlay is an overlap (`Box` alignment, not a layout slot), so an empty overlay costs
nothing and the message area draws exactly as if it were absent. Otherwise a `Column(horizontalAlignment =
End, verticalArrangement = spacedBy(12.dp))` — Figma `541:2446`'s 12dp pill gap — with, top to bottom:

### The attention pill (#1735)

The optional `attentionPill` slot comes first, above usage, MCP, connection and session-error
notices. `ThreadAttentionNotice` renders at most one pill without an X: gold Waiting or green
Finished. One other unmuted waiting conversation shows "<name> needs your answer"; two or more
show "<n> conversations need you". Waiting aggregates every host, excludes only the current
host/conversation pair, and follows current attention, mute state and names. An equal conversation
id on another host still counts. A new `TurnCompleted` alert from another unmuted pair shows "<name> finished" for five seconds.
Waiting clears Finished and discards completions received while it shows, without replay afterward.
See [attention navigation](navigation.md#what-it-does) for single and count targets.

Names use `notificationTitle`: remove controls, retain at most 80 Unicode code points without
splitting surrogate pairs, trim, and use the app name when empty or unavailable. Plain `Text` keeps
host-authored names inert; two-line ellipsis bounds the label within the thread's 20dp gutters.
Static-dark Waiting uses `#3D3215` / `#D8B85A`; Finished uses `#0F3313` / `#2FC038`.
Light/dynamic containers are tonal colors composed over the theme surface.

The visible shared pill keeps bodySmall text, 6dp corners, 8dp horizontal / 4dp vertical padding,
hug width and a 24dp minimum height. Its separate clickable wrapper adds 24dp invisible space
upward into the overlay's top clearance, yielding a minimum 48dp target. It reports only the
visible height to the stack: the 12dp gap below and the neighboring usage X target stay intact.
Longer labels grow both heights. The target ends at the visible pill's bottom.
The wrapper owns one labelled accessible button; the inner `NoticePill` uses
`mergeDescendants = false` so text and description merge into that parent.

### The usage pill

When `usageLimit != null && !usageLimitDismissed`: `NoticePill(text = usageLimitLabel(usage), isError =
!usageLimitIsWarning(usage), onDismiss = if (warning) onDismissUsageLimit else null)`. See [Usage-limit
indicator](usage-limit-indicator.md) for `usageLimitLabel` / `usageLimitIsWarning` and the client-owned
copy rule behind them.

### The failed-MCP-server pill ([#1345](https://github.com/pyrycode/pyrycode-mobile/issues/1345))

When `mcpFailure != null` and neither `showRePair` nor the offline pill shows: `NoticePill(text =
stringResource(R.string.thread_mcp_server_failed, boundMcpText(mcpFailure)), isError = true, onClick =
onOpenMcpFailure, maxLines = 2)`. Ports desktop #1494's `openMcpFailure` onto this overlay. `mcpFailure` is
`ThreadViewModel.mcpFailure`: the first server in the thread's current MCP status report that is exactly
`"failed"` and not yet acknowledged for this host and conversation, or `null` while the host isn't
`Connected`. See [Channel info sheet § MCP servers
section](channel-info-sheet.md#mcp-servers-section) for the status report this reads and for
`McpFailureAcknowledgements`, the holder that tracks what's been acknowledged.

Tapping the pill (`onOpenMcpFailure = vm::onMcpFailureTapped`) acknowledges every server the current report
shows as failed, then opens Channel info through the same `ThreadEvent.ChannelInfo` path as the overflow
item, which also asks for fresh status. A repeat report with the same failures stays quiet; a server failing
for the first time raises its own notice; a server no longer reported as failed stops showing. `maxLines = 2`
is a deliberate deviation from the Figma pill (normally one line): the server name is Claude-authored and can
run to 256 code points, bounded further by `boundMcpText`, and two lines keep it from covering the message
area — recorded as a security SHOULD FIX in the [#1345 plan](../../specs/architecture/1345-mcp-failure-notice.md#security-review).

Mutually exclusive with the pairing and offline pills below: a rejected pairing or a dropped connection is a
worse signal than a stale MCP report, and the MCP reading itself starts empty on a fresh connection anyway,
so there is nothing to show while either of those pills would.

### The pairing or Offline pill

When `showRePair`: `NoticePill(text = stringResource(R.string.thread_re_pair), isError = true, onClick =
onRePair)` — the local string resource "Pairing error - Re-pair", never daemon text (a code comment on this
line makes the point explicit, since every other pill in this file carries claude-authored text). Carries
forward [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843)'s contract unchanged: `showRePair`
comes from `ThreadViewModel.rePairAvailable`, `true` exactly when this thread's own host is in the
rejected-pairing state; `onRePair` is bound at `MainActivity` to
`navController.navigate(Routes.pairCode(target.serverId))`. This pill has no dismiss X — a rejected pairing
is never hideable, unlike a warning reading.

Re-pair alone overrides the vertical minimum touch size to `0.dp`, so its target follows
its measured visible surface height, including native text and font scaling. The usage
X retains a 36dp vertical minimum; both inherit the platform horizontal minimum
(48dp on the tested devices). The stack still measures a 12dp gap between visible
surfaces. Do not assume both pills render at 24dp or give both targets symmetric
36dp expansion: separate semantic bounds do not guarantee that an expanded edge
can actually receive input. See [Notice pill caller contracts](notice-pill.md#caller-contracts).

Usage sits above the lower action, matching Figma `533:1956`. When `connectionState` is Offline and `showRePair` is false, the lower action is the error-toned [Retry pill](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910). Its 48 dp clickable box extends below the visible pill, preserving the 12 dp gap and avoiding the usage dismiss target. Since [#1499](https://github.com/pyrycode/pyrycode-mobile/issues/1499), the visible pill no longer fills that box: it hugs "Offline · Retry" (94dp under Robolectric — the label plus 8dp padding on each side) at the box's top-right corner, which the box's `Alignment.TopEnd` places at the overlay's right edge, matching frame `627:4910`. The 144 x 48 dp box itself is unchanged from [#1283](https://github.com/pyrycode/pyrycode-mobile/issues/1283) — only the drawn pill's width changed, not the touch target. `showRePair` wins when pairing is rejected, since a network retry cannot repair that state.

### The session-error pill (#1678)

Every non-null `sessionError`, including an empty or unknown code, shows an inert
Error `NoticePill` after the existing persistent usage/MCP/pairing/offline notices.
It has no click, dismiss control, leading icon or timeout; long copy wraps. Recovery advice follows it before the transient error pill. Repository clearing alone removes this pill.

Exact code matches select client resources; neither raw codes nor daemon prose
reach visible text or accessibility semantics:

| Code | Claude copy | Codex copy (`_codex` resource variants) |
| --- | --- | --- |
| `session.blocked` | Claude did not pick up the last message. It was not delivered. | Codex did not pick up the last message. It was not delivered. |
| `session.child_crashing` | Claude keeps failing to start. Your message is waiting. | Codex keeps failing to start. Your message is waiting. |
| Any other code | Claude stopped responding. | Codex stopped responding. |

Blocked delivery has abandoned the backlog; child crashing retains the queued
message. Showing an error never resends it. See [repository clearing rules](remote-conversation-repository-state-errors-and-handoff.md#conversation-session-errors-1677)
and [destination observation](thread-screen-how-it-works-state.md#session-errors-and-local-send-settlement-1678).

### The stopped-turn recovery pill (#1603)

[TurnOutcomeIndicator](turn-outcome-indicator.md) follows session errors as one icon-free,
X-free Error pill. Context reads “Context too long - Compact”; its whole surface invokes
the published Compact command once. Unavailable Compact and agent-specific billing/sign-in
are inert. Status remains independent, with the idle snowflake when connected and idle.

The visible pill keeps the design's 24dp height and 12dp neighbor gaps, while Compact's
merged action target extends down to at least 48dp. A following transient notice is
measured from the visible pill, and consumes taps on its inert surface where it overlaps
that target. Reserving the target height in the stack would incorrectly make the visible
gap 36dp. Native geometry and physical pointer tests cover both preceding and following
neighbors, including Offline and Re-pair.

### The transient error pill (#1747)

The thread's local failures show below every persistent notice and the
session error and recovery pill, with the same 12 dp gap. These are new-session, archive, workspace and run-configuration
failures, attachment size and count refusals, attachment-send failures, refused pasted or keyboard-inserted
images, markdown-open failures, and the attachment no-app, open-failed and save-failed outcomes. Each shows
its existing client-owned sentence in an inert Error pill (`TransientErrorPill`): no X, no tap action, a 24 dp
minimum height and a polite live region. It overlays the list without moving it. Under an Offline pill,
spacing follows the visible pill, not Retry's 48 dp target, and the error draws above that target so tapping
it cannot run Retry.

`TransientErrorNoticeState` (`TransientErrorNotice.kt`), remembered per conversation, owns the local queue. Every
conversation-local failure joins that first-in, first-out queue in the order it happened, and each
pill stays for the full Material Short time of 4 s, adjusted by the accessibility manager as a snackbar's would
be. Expiry removes only that pill. Each occurrence has its own identity, so a repeated identical failure is
announced again. Leaving the screen or switching conversation cancels the shown and queued pills.
Confirmations use a separate queue below these errors (#1851).

Share capture failures and intake/selection count or size refusals reuse this inert Error treatment
through `NavigationErrorPill` (#1824). They render after conversation-local transient errors with a 12dp
gap, including inside the Offline following-notice region above Retry's expanded target. The reused
pill retains bodySmall, errorContainer/error colors, 6dp corners, 8dp horizontal/4dp vertical padding
and the overlay shadow; it has no X or action and announces politely.

Their queue is separate from the conversation-local queue: `ShareErrorNoticeHost` remembers it above
navigation, keyed by the intake ViewModel, and supplies it through nullable `LocalNavigationErrorNotice`.
A destination change neither cancels nor restarts its remaining lifetime. Each occurrence receives its
own full accessibility-adjusted 4-second Short lifetime and identity; disposing the host cancels its
collector and all active/queued children. The collector enqueues each notice immediately rather than
waiting for expiry before receiving the next failure. Standalone screens have a null provider and no
navigation notice. See [Incoming shares](navigation.md#incoming-shares-1728) for picker, Direct Share and
startup placement, and [share regression evidence](development-verification-emulator-evidence.md#share-failure-presentation-1824)
for the actual collector/navigation coverage. A conversation-local queue would clear selection
refusals at the very navigation boundary where the operator needs to read them.

### The Default confirmation pill (#1851)

Dismissal reasons from `dismissReasonText` and attachment “File saved” share one
screen-local confirmation FIFO. `TransientConfirmationPill` renders after every other
visible notice, including conversation and navigation errors, with 12dp visible-surface
gaps. Offline and stopped-turn following-notice layouts measure those gaps from the
painted pill rather than an expanded action target. The inert Default treatment uses
`primaryContainer` / `onPrimaryContainer`, bodySmall, 6dp corners, 8/4dp padding,
a 24dp single-line height and a polite live region. It reserves no message space.

`rememberTransientConfirmationNoticeState` reuses `TransientErrorNoticeState` in a
separate instance. Every admission, including identical text, has a new occurrence
and its full 4,000ms lifetime starting when it becomes visible. Accessibility timeout
calculation sets text/icons true and controls false. Errors keep independent queues
and can appear above an active confirmation immediately. The confirmation state and
scope are keyed by conversation; screen exit or conversation switch cancels active
and waiting occurrences, clearing the visible message.

Enqueue from the modal-id effect into the composition-owned scope. Launching the
queued job as a child of `LaunchedEffect(modalId)` would let a later modal ID cancel
an earlier confirmation. Keep the modal-id trigger to prevent unrelated recomposition
from replaying it; reopening still shows the latest eligible dismissal exposed by the
host fold. No persistence or additional reconnect/history replay is introduced.
See [thread routing](thread-screen.md#what-it-does), [reader confirmations](markdown-reader-screen.md#what-it-does)
and the [three notice-only comparisons](../../../app/src/androidTest/assets/confirmation-1851/README.md).

## Placement in `ThreadScreen`

The message area — either `EmptyThreadState` or the `LazyColumn` — is wrapped in a `Box(Modifier
.fillMaxWidth().weight(1f))`; the child takes `fillMaxSize()`, and `ThreadTopOverlay` is drawn after it,
`Modifier.align(Alignment.TopEnd).padding(start = ComposerGutter, top = TopOverlayTopGap, end =
ComposerGutter)`. [#1562](https://github.com/pyrycode/pyrycode-mobile/issues/1562) moved the message
region's top edge up to the app bar's rule so scrolled rows could draw through what used to be a dead
28dp band; `TopOverlayTopGap = MessageAreaTopInset` (28dp, the same constant `ThreadScreen.kt` uses as the
`LazyColumn`'s top `contentPadding` and `EmptyThreadState`'s top padding) keeps the overlay's visible
content at the y it held before that region moved, matching Figma `533:1956` / `685:4337`. Being an overlap
rather than a `Column` child is load-bearing: the overlay draws *over* the scrolling messages, so it never
reserves layout space and the list never reflows as pills appear or clear.

`ThreadScreen` passes its optional `attentionPill` slot through unchanged. Its default-null slot
keeps other callers compatible. Two defaulted usage parameters also feed the overlay:

```kotlin
dismissedUsageLimits: Set<UsageLimitDismissals.Key> = emptySet(),
onDismissUsageLimit: (UsageLimitReading) -> Unit = {},
```

The screen stays stateless — it computes `usageLimitDismissed = usageLimit?.dismissalKey() in
dismissedUsageLimits` and binds the X to the reading it is currently showing
(`onDismissUsageLimit = { usageLimit?.let(onDismissUsageLimit) } }`). Defaulting both keeps every prior
`ThreadScreen(` call site and preview compiling unchanged; only `MainActivity` and any test asserting
dismissal need the new arguments.

Offline Retry is suppressed while `showRePair` is true; see [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).

## Dismissal — `UsageLimitDismissals`

```kotlin
class UsageLimitDismissals {
    val dismissed: StateFlow<Set<Key>>
    fun dismiss(reading: UsageLimitReading)
    data class Key(val status: String, val limitType: String, val resetsAt: Long)
}
internal fun UsageLimitReading.dismissalKey(): UsageLimitDismissals.Key
```

Package: `de.pyryco.mobile.ui.conversations.thread` (`UsageLimitDismissals.kt`). App-process heap only, the
same idiom as [`ComposerDraftStore`](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
— a plain class, one `MutableStateFlow<Set<Key>>` mutated with `update { it + key }`, bound `single {
UsageLimitDismissals() }` in `AppModule.kt` beside the draft store.

**The key is the reading, never the conversation or the host.** A usage limit belongs to the *account*, so
dismissing a reading in one thread hides the same reading in every open thread — deliberate, not an
oversight. `Key` is `(status, limitType, resetsAt)`; a change to any of the three is a different key, so the
pill returns (the window reset, or a new limit type started reporting). A changed `utilization` alone keeps
the same key, so ticking utilization on an already-dismissed reading does not resurrect its pill.

**Never persisted, never logged.** No `DataStore`, no `SavedStateHandle`, no `rememberSaveable` — a process
restart shows every currently-live reading again. The key holds daemon-authored strings and the account's
quota posture, so no part of it may reach a log line, an exception message or a crash report (see [Usage-
limit indicator § Security](usage-limit-indicator.md#security)).

`MainActivity`'s thread destination injects the singleton via `koinInject()`, collects `dismissed` with
`collectAsStateWithLifecycle()`, and passes `dismissedUsageLimits = dismissed` /
`onDismissUsageLimit = dismissals::dismiss` into `ThreadScreen`.

## Acknowledgement — `McpFailureAcknowledgements`

```kotlin
class McpFailureAcknowledgements {
    fun observe(serverId: String, conversationId: String): Flow<Set<String>>
    fun acknowledge(serverId: String, conversationId: String, names: Collection<String>)
    fun clearHost(serverId: String)
}
```

Package: `de.pyryco.mobile.ui.conversations.thread` (`McpFailureAcknowledgements.kt`). Same app-process-heap
idiom as [`UsageLimitDismissals`](#dismissal--usagelimitdismissals) above, bound `single {
McpFailureAcknowledgements() }` in `AppModule.kt` beside it, and injected into `ThreadViewModel` the same way
(`permissionDraftStore` pattern: an optional constructor parameter, a private instance when the caller passes
none).

**Keyed by host, then conversation — not the reading alone.** Unlike the usage-limit key, which is the
account-level reading, an MCP server belongs to one host and one conversation, so the map is
`host → conversation → names`, the shape [`ComposerDraftStore`](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
already uses. Acknowledging a failure in one chat leaves every other chat's notice showing, including
another conversation on the *same* host. Acknowledgements are never pruned by a later report — a server that
recovers and then fails again stays quiet for the rest of the app run, matching desktop, which never prunes
either.

**Never persisted, never logged, names compared only for equality.** No `DataStore`, no saved-instance state
— a process restart (or a second `ThreadViewModel` on the same shared holder, i.e. leaving and reopening the
chat) forgets nothing *within* the run, because the holder itself is a `single`, but a real process restart
starts every holder empty again. The server name is Claude-authored: it is rendered as inert text through
`boundMcpText`, never logged, and never appears in an exception message — see [Channel info sheet § MCP
servers section § Plain text only](channel-info-sheet.md#mcp-servers-section) for the same rule applied to
the rest of the MCP surface.

**Cleared on unpair.** `ObservablePairedServerStore.forgetRemovedHost` calls `mcpAcknowledgements.clearHost(serverId)`
right after `drafts.clearHost(serverId)`, so removing a host's pairing drops every conversation's
acknowledgements for that host; other hosts' entries are untouched.

## State + concurrency model

`rememberThreadAttention` collects the cold `observeThreadAttention` flow only inside the
back-stack entry's `repeatOnLifecycle(RESUMED)`. A covered thread may stay composed, so composition
lifetime alone cannot own this surface. Leaving, covering or backgrounding cancels collectors and
the expiry job and clears presentation. Resuming reads current Waiting with no old Finished.
`HostConversationSource.attention` supplies waiting; its non-replayed `alerts` supplies only
`TurnCompleted`, independently of the system notifier's ledger. `Unread` and prompt alerts cannot
create Finished.

A newer eligible finish replaces the target and restarts its five-second delay. Snapshot renames
update the label without extending expiry; muting that target clears it. Callbacks read current
attention and snapshot values, with no suspension between eligibility, mutation and publication.
Waiting cancels the finish timer. Parent cancellation cancels every child collector and delay.

`conflate()` directly on the `channelFlow`, before `distinctUntilChanged()`, retains the latest
projection under delayed consumption. A default-buffered `trySend` can reject a new target or clear
while the local timer advances, leaving stale UI. Intermediate presentations are replaceable;
non-suspending publication must retain the latest one. Logs contain only static reasons and counts.

No coroutine is launched by either holder. `UsageLimitDismissals` and `McpFailureAcknowledgements` each hold
one `MutableStateFlow`, mutated only with `update` (compare-and-set) — the former on the main thread from a
click, the latter from `onMcpFailureTapped` and from `forgetRemovedHost`'s unpair path, so a concurrent
acknowledge and an unpair cannot lose each other's write. The usage reading's own flow, its 30 s re-read
ticker (see [Usage-limit indicator § Wiring](usage-limit-indicator.md#wiring--a-30-s-re-read-ticker-not-a-timer-from-resets_at))
and `rePairAvailable` are all untouched by #1002 — only where their values are *rendered* changed. #1345's
open-and-reconnect MCP status ask runs as a `viewModelScope` collector over `repositoryAvailable`, not inside
either holder; see [Channel info sheet § MCP servers section](channel-info-sheet.md#mcp-servers-section) and
[Development verification § Emulator and real evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence)
for why that ask shares a daemon worker with sending a message and can stall behind an unanswered one.

## Testing

`ThreadTopOverlayTest.theUsagePill_sitsAboveThePairingPill_whichStartsRePair`
uses physical center and facing-edge taps for both controls, checks exactly one
intended callback per tap and dismissal state, and measures the 12dp visible gap,
nonoverlapping touch bounds and retained horizontal widths. Usage's facing-edge
tap stays inside the Surface's visible clip; advertised dismiss expansion outside
that clip cannot be treated as tappable. After #1757's line-height repair, the
native red did not reproduce the original 3.5px overlap: it failed because the
advertised expanded upper Re-pair edge was untappable (1 executed, 1 failed,
0 skipped in [native-red.xml](../../../app/src/androidTest/assets/touch-1760/native-red.xml)).
Semantic clicks or nonoverlap assertions alone would miss that defect.

Retained 2026-10-05 evidence confirms the named method passed without an ignore:

- Managed Android 13: `./gradlew :app:pixel2Api33AtdDebugAndroidTest --rerun
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.ui.conversations.thread.ThreadTopOverlayTest'
  -Pandroid.testInstrumentationRunnerArguments.notPackage=de.pyryco.mobile.e2e --console=plain`.
  The [post-merge native XML](../../../app/src/androidTest/assets/touch-1760/merge-native-class-green.xml)
  records 14 executed/passed, 0 failed/errors/skipped, including the named method
  at `2026-10-05T18:04:20`. The earlier separate method run records 1 executed/passed,
  0 failed/errors/skipped in [native-method-green.xml](../../../app/src/androidTest/assets/touch-1760/native-method-green.xml).
- JVM: `./gradlew testDebugUnitTest --tests
  'de.pyryco.mobile.ui.conversations.thread.ThreadTopOverlayTest' --tests
  'de.pyryco.mobile.ui.conversations.thread.ThreadAttentionNoticeTest' --tests
  'de.pyryco.mobile.ui.conversations.components.NoticePillTest' assembleDebug --console=plain`.
  [Focused post-merge XML](../../../app/src/androidTest/assets/touch-1760/merge-jvm/)
  records 26 executed/passed (14 overlay, 10 attention, 2 component), 0 failed/errors/skipped.
  The final `./gradlew testDebugUnitTest assembleDebug --console=plain` run records
  4,309 executed/passed, 0 failed/errors/skipped in the
  [summary](../../../app/src/androidTest/assets/touch-1760/merge-final-jvm-summary.xml);
  its [overlay XML](../../../app/src/androidTest/assets/touch-1760/merge-final-jvm-overlay.xml)
  confirms the named method passed among 14 tests.

[Initial commands](../../../app/src/androidTest/assets/touch-1760/commands-and-results.txt)
and [post-merge commands/counts](../../../app/src/androidTest/assets/touch-1760/merge-commands-and-results.txt)
retain the exact selections and results. The default device-only UI gate does not
select this shared test; the targeted managed-device evidence establishes its native pass.
Removing main's temporary #1760 ignore deliberately restores this regression;
the neighboring Offline Retry regression remains runnable.


- **Attention (#1735):** `ThreadAttentionTest` uses a controlled clock for replacement, expiry,
  precedence, mute/name changes and cancellation/no replay. Delayed-consumer cases hold consumption
  beyond the old buffer capacity, deliver 100 finishes and waiting updates, and independently prove
  upstream delivery; ignored `tryEmit` results could otherwise hide the race.
  `ThreadAttentionNoticeTest` checks native-graphics colors, stacking, hostile names, both target
  boundaries and the usage X neighbor. Assert tag, text, description and click action together:
  text-only checks can pass while a nested merging node leaves the button unlabelled.
  Width assertions use measured thread bounds and both 20dp gutters, rather than Robolectric's
  default 320dp viewport. `ThreadAttentionNavigationTest` covers colliding ids, list taps, covered
  entries and background/resume. In instrumented coverage the composition-owned expiry delay uses
  the Compose rule's virtual clock. Advance it explicitly before checking expiry: `waitUntil`
  advances only one frame per poll, so slow full-suite polling can exhaust a wall-clock timeout
  while the five virtual seconds have not elapsed. The rung-3 scenario advances by 5,100ms and
  checks disappearance and no replay on reopening; its named full-suite pass is recorded in the
  [live ladder](../../e2e-interactive-stream.md#verification-status).
  An isolated answer daemon does not isolate the phone's overlay (#1905): an inherited prompt on
  another paired host correctly changes a single-target Waiting pill to an aggregate count. A peer
  modal alone cannot prove the expected phone label. Diagnose the first Waiting stage using bounded
  modal/attention/name-match booleans and semantics counts, without logging names, contents or trees.
  The live scenario temporarily keeps only the answer host paired, waits for host-source isolation,
  and restores exact records, names and order in non-cancellable cleanup before answer-host removal.
  `AttentionHostIsolationTest` drives the real attention fold with both hosts waiting, proving the
  count under the old setup and B alone under isolation; it also covers restoration and error
  preservation after failure or cancellation. This is distinct from the expiry-clock repair above.
  The [live ladder](../../e2e-interactive-stream.md#verification-status) retains the controlled
  red/green XML and fresh full-suite pass; product aggregation remains unchanged.
- **Robolectric** `ThreadTopOverlayTest` (`app/src/sharedTest/.../thread/`):
  - no reading and no re-pair → no pill nodes, no dismiss X;
  - an `allowed_warning` reading → a pill with the label and an X; tapping the X (against a test-held
    dismissal set wired to `onDismissUsageLimit`) hides it; giving the reading a new `resetsAt` shows it
    again;
  - a `rejected`-style and an unrecognised status → a pill shown, no X;
  - the usage pill's bounds sit above the pairing pill's when both show; tapping the pairing pill fires
    `onRePair` exactly once;
  - **the regression proof (AC #4):** with an `allowed_warning` reading live, "Running Bash…" still renders
    in the status row (a busy state with an open tool row), the wrapping-up reset label still renders, and
    "Turn interrupted" still renders — written first, and it fails against the pre-#1002 ladder.
  - **#1345:** a failed-server name renders "MCP server NAME failed" as a tappable Error pill below the
    usage pill; it is absent alongside either the Re-pair pill or the offline retry target.
- **Session errors (#1678):** `ThreadTopOverlayTest` checks persistent-notice ordering,
  inert semantics and the reused Error colors/right-aligned bodySmall text with native graphics.
  `ScriptedSessionErrorTest` proves the real repository-to-screen graph; see
  [thread testing](thread-screen-testing.md#session-error-graph-and-acknowledgement-races-1678).
- **Emulator (rung 4, #1457):** the scripted `mcp-failed` scenario
  (`DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_failedMcpServerPillOpensChannelInfo`,
  [Scenarios](../../e2e-interactive-stream.md#scenarios-454)) drives the pill through the real daemon and
  relay: fakeclaude's first `mcp_status` answer names `pyry_mcp_test` as `failed`, the pill whose text
  starts with the `thread_mcp_server_failed` prefix is tapped, and Channel info's MCP servers section is
  asserted shown. No live rung-3 twin is possible — a child spawned under the daemon's MCP document runs
  with `--strict-mcp-config` and loads only `pyry_approve` and `pyry_files`, so a real-Claude session never
  sees a failed server; pyrycode #2272 pins the real-Claude shape of a failed server on the daemon side
  instead.
- **JVM unit** `UsageLimitDismissalsTest` — see [Usage-limit indicator §
  Testing](usage-limit-indicator.md#testing). `McpFailureAcknowledgementsTest` covers the holder in isolation
  (selection order, exact-`"failed"` matching, per-host/per-conversation isolation, `clearHost`) and
  `ThreadViewModelMcpFailureTest` covers `ThreadViewModel.mcpFailure` / `onMcpFailureTapped` / the open-and-
  reconnect ask — see [Channel info sheet § Tests](channel-info-sheet.md#tests).
- **Kept unchanged**, `ThreadScreenRePairTest`: still finds the pairing notice by its `R.string.thread_re_pair`
  text and clicks it, still checks the connection banner is withheld — the test needed no change because it
  asserts by text, not by composable identity, so it holds whether the notice is a status-row button or a
  Top-overlay pill.
- **Original overlay placement, rung 3 (live):** #1002 added no scenario of its own. The acceptance proof is that the three
  `InteractiveStreamE2ETest` methods named in [Why this moved](#why-this-moved-1002) pass again under
  `python3 scripts/android-test-gate.py live` while the account's usage reading is `allowed_warning` — they
  needed no code change, since moving the reading off the status slot they assert against is the whole fix.
  The dismiss affordance has no rung-4 twin: a scripted `rate_limited` frame drives the same pill the
  Robolectric coverage above already exercises.

## Security

Builder self-review **PASS**, full text in the [architecture
doc](../../specs/architecture/1002-thread-top-overlay-pills.md#security-review). No new trust boundary: the
daemon-authored fields reach Compose only through `usageLimitLabel` (since
[#1519](https://github.com/pyrycode/pyrycode-mobile/issues/1519), `usageLimitText`'s exact-equality lookup —
see [Usage-limit indicator § Security](usage-limit-indicator.md#security)), and the one new branch on
`status` (`usageLimitIsWarning`, exact equality) only ever adds or withholds a dismiss X — every other
status, recognised or not, is an Error pill that cannot be hidden, so a hostile daemon gains nothing by
sending `"allowed_warning"` that it could not already do with the benign `"allowed"`. `UsageLimitDismissals`
is heap-only (see [Dismissal](#dismissal--usagelimitdismissals) above) — no disk, no backup-eligible state,
no log. No intent, deep link, provider or WebView is added; the overlay's actions hide a notice locally or navigate to existing screens. Attention taps
use typed host/conversation targets, never names, and cannot answer prompts or send commands. Dismissal tells the
daemon nothing.

**#1345's MCP pill** adds no new trust boundary either: the server name already reaches this screen through
Channel info's MCP servers section (#1344), rendered the same bounded way through `boundMcpText`. The only
new comparison is `status == "failed"`, a client constant, not daemon-chosen branching. `maxLines = 2`
bounds the pill's height against the full 256-code-point name. `McpFailureAcknowledgements` is heap-only like
`UsageLimitDismissals` (see [Acknowledgement](#acknowledgement--mcpfailureacknowledgements) above); logs
around it (`event=mcp_failure_acknowledged count=N`, `event=mcp_status_requested reason=reconnect`) carry
only a count or a static reason, never the server name, the status or the conversation id.

## Related

- Content: [Notice pill](notice-pill.md) — the shared pill composable the overlay notices render through.
- Usage reading: [Usage-limit indicator](usage-limit-indicator.md) — the label/warning helpers, the
  dismissal key extension, and the removed status-row arm's history.
- MCP failure: [#1345](https://github.com/pyrycode/pyrycode-mobile/issues/1345) and [Channel info sheet §
  MCP servers section](channel-info-sheet.md#mcp-servers-section) — the status report the pill reads, the
  open-and-reconnect ask, and the daemon FIFO-worker coupling recorded in [Development verification §
  Emulator and real evidence](development-verification-emulator-evidence.md#emulator-and-real-evidence).
- Spec: `docs/specs/architecture/1345-mcp-failure-notice.md`.
- Pairing notice: [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843) — the rejected-pairing
  signal (`ThreadViewModel.rePairAvailable`) and the Re-pair priority over Offline Retry, described in [connection status placement](thread-screen-how-it-works-overlays-and-app-bar.md#connection-status-placement).
- Host: [Thread screen](thread-screen.md) — the `Box` overlap this composable draws into, and the two new
  defaulted parameters.
- Status ladder left behind: [Resetting indicator § Placement](resetting-indicator.md#placement-in-the-thread)
  — the current `ThreadStatusArea` ladder, now turn-status only.
- Idiom mirrored: [Composer draft ownership](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
  (`ComposerDraftStore`) — the app-process-heap `Koin single` shape `UsageLimitDismissals` clones.
- Spec: `docs/specs/architecture/1002-thread-top-overlay-pills.md`.
- Figma: [`533:1956`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=533-1956)
  (message area with the Top overlay), [`347:6617`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG/Pyrycode-Client?node-id=347-6617)
  (the Pill component set).

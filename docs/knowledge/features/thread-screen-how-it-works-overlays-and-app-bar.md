# Thread screen — how it works, overlays, retry and the app bar

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### Connection status placement

`connectionState` remains a flat `ThreadScreen` parameter collected from `ThreadViewModel.connectionState`, separate from the conversation state. [Connecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-1740) and [Reconnecting](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4657) render through `ConnectionStatusIndicator` in the composer `ThreadStatusArea`, in muted `onSurfaceVariant` body-small text. They take precedence over turn readings while the link is unavailable; the task-count pill can still sit beside them. Connected and Offline emit no composer connection reading.

[Offline](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=627-4910) shows Retry in [`ThreadTopOverlay`](thread-top-overlay.md), using the existing `onRetry = vm::retry` route. A rejected pairing also derives Offline, but `showRePair` takes precedence: Re-pair navigates to pairing because network retry cannot repair rejected credentials. Neither action changes message-list height. The older `ConnectionBanner` and its structural placement above the list were removed.

### Thinking-indicator placement (post-#407, moved in #643, re-expressed as `statusArm` in #1311)

[#407](../codebase/407.md) originally mounted the stateless [`ThinkingIndicator`](thinking-indicator.md) as the final child of the content `Column`, at the foot of the list above the `bottomBar`. [#643](../codebase/643.md) moved it — along with the retry and compaction arms it shares a slot with — into the composer itself, as the **`Status area`** band of the Figma `16:8` `Input area` (`533:1957`). It is now the first child of the `bottomBar` `Column`, rendered by a private `ThreadStatusArea` composable (`ThreadScreen.kt`) that exists purely so the `bottomBar` lambda stays readable. [#1311](https://github.com/pyrycode/pyrycode-mobile/issues/1311) is the most recent change to this slot: it added `isStalled`, `isBusy` and `localSendPending` parameters, moved the arm-selection logic out of an inline `when` into the named `statusArm` function, and added the `waitingForAnswers` branch (#1305/#1306, below) beside the ladder dispatch rather than inside it:

```kotlin
bottomBar = {
    Column(Modifier.fillMaxWidth().background(surface).imePadding().padding(top = 12.dp, bottom = 16.dp)) {
        ThreadStatusArea(apiRetry = apiRetry, resetting = resetting, isCompacting = isCompacting, isStalled = isStalled, turnOutcome = turnOutcome, onCompact = onCompact, isThinking = isThinking, isBusy = isBusy, localSendPending = localSendPending, thinkingProgress = thinkingProgress, runningTool = if (isBusy) openTool else null, waitingForAnswers = …, connectionState = connectionState, taskCount = state.backgroundTaskCount, onTasksClick = { backgroundTasksOpen = true }, agent = state.agent)
        ThreadInputBar(onSend = onSendMessage, isBusy = isBusy, onInterrupt = onInterrupt, …)
        ThreadComposerFooter(runConfig = state.runConfig, onOpen = { openControl = it }, onStatusClick = { sheetVisible = true }, onAnchorChanged = { control, bounds -> footerAnchors[control] = bounds }, …)
    }
}

@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?, // #872
    isCompacting: Boolean,
    isStalled: Boolean, // #1311
    turnOutcome: TurnRecoveryNotice?, // #1357
    onCompact: (() -> Unit)?, // #1357
    isThinking: Boolean,
    isBusy: Boolean, // #1311
    localSendPending: Boolean, // #1311
    thinkingProgress: ThinkingProgress?, // #803
    runningTool: ToolCall?, // #897
    waitingForAnswers: Boolean, // #1305
    connectionState: ConnectionState,
    taskCount: Int, // #1043
    onTasksClick: () -> Unit, // #1043
    agent: ConversationAgent, // #1114
) {
    val reading: @Composable (Modifier) -> Unit = { modifier ->
        if (waitingForAnswers) {
            // the fixed "Waiting for answers" row (#1305) — see § Inline question rows below
        } else {
            StatusReading(
                arm = statusArm(connectionState, resetting != null, apiRetry != ApiRetryStatus.NotRetrying, isCompacting, isStalled, turnOutcome != null, isThinking, isBusy, localSendPending, runningTool != null),
                apiRetry, resetting, turnOutcome, onCompact, isThinking, thinkingProgress, runningTool, connectionState, agent, modifier,
            )
        }
    }
    if (taskCount <= 0) {
        reading(Modifier.fillMaxWidth().padding(horizontal = ComposerStatusGutter))
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = ComposerStatusGutter, end = ComposerGutter),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reading(Modifier.weight(1f))
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            NoticePill(text = pluralStringResource(R.plurals.thread_task_count, taskCount, taskCount), isError = false, onClick = onTasksClick, modifier = Modifier.sizeIn(minWidth = 104.dp, minHeight = 24.dp), shadowElevation = 0.dp)
        }
    }
}

/** Which one reading the band shows, decided once by [statusArm] (#1311); see [Thread screen § The arm
 * order](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311) for the full enum and
 * precedence table. [StatusReading] below only switches on the already-decided arm. */
@Composable
private fun StatusReading(
    arm: StatusArm,
    apiRetry: ApiRetryStatus,
    resetting: ResetStatus?,
    turnOutcome: TurnRecoveryNotice?,
    onCompact: (() -> Unit)?,
    isThinking: Boolean,
    thinkingProgress: ThinkingProgress?,
    runningTool: ToolCall?,
    connectionState: ConnectionState,
    agent: ConversationAgent, // #1114
    modifier: Modifier = Modifier,
) {
    when (arm) {
        StatusArm.None -> Unit
        StatusArm.Connection -> ConnectionStatusIndicator(state = connectionState, modifier = modifier)
        StatusArm.Resetting -> ResettingIndicator(status = resetting, modifier = modifier, agent = agent) // agent: #1112
        StatusArm.ApiRetry -> ApiRetryIndicator(status = apiRetry, modifier = modifier, agent = agent)
        StatusArm.Compacting -> CompactingIndicator(isCompacting = true, modifier = modifier, agent = agent)
        StatusArm.TurnOutcome -> TurnOutcomeIndicator(notice = turnOutcome, agent = agent, onCompact = onCompact, modifier = modifier)
        // One branch, so the glyph keeps its composition identity, and its pulse, across these readings (#1311).
        StatusArm.Stalled, StatusArm.Thinking, StatusArm.Working, StatusArm.RunningTool ->
            ThinkingIndicator(
                isThinking = arm == StatusArm.Thinking,
                modifier = modifier,
                progress = thinkingProgress.takeIf { isThinking },
                runningTool = runningTool.takeIf { arm == StatusArm.RunningTool },
                agent = agent,
                isWorking = arm == StatusArm.Working, // #1311
                isStalled = arm == StatusArm.Stalled, // #1311
            )
    }
}
```

`agent` reaches both functions from `ThreadScreen`'s own `state.agent` (see [Thinking indicator § The agent
name](thinking-indicator.md#the-agent-name-1114)); `resetting`'s branch is the only one of the arms that
picked up `agent` after #1114 shipped, closed by #1112 — see [Resetting indicator § The agent
name](resetting-indicator.md#the-agent-name-1112). Since [#1357](turn-outcome-indicator.md) the turn-outcome arm
shows only client-owned recovery copy — no daemon text crosses into it at all — and carries the `onCompact`
callback the context notice's Compact pill uses. See [Thread screen § The arm
order](thread-screen-how-it-works-list-and-status-row.md#the-arm-order-1311) for `statusArm`'s full
signature, the precedence table and the local-send window that feeds `localSendPending`.

**[#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) added the task-count pill at the band's
right end, and split the `when` out into `StatusReading` to make room for it.** Above zero,
`state.backgroundTaskCount` (§ [Background-tasks panel placement](#background-tasks-panel-placement-post-678)
below) renders as a [`NoticePill`](notice-pill.md) reading the client-owned plural `R.plurals.thread_task_count`
("1 task running" / "N tasks running") beside whichever `StatusReading` arm is live, right-aligned on the
20dp gutter; tapping it sets the same `backgroundTasksOpen` flag the Actions menu's row sets, opening the
same `BackgroundTaskPanel`. `StatusReading` itself is byte-identical to the pre-#1043 `when` above, just
parameterised on `modifier` instead of closing over the file-private `slot` — at `taskCount <= 0` it still
gets exactly that `slot`, so an idle thread with no running tasks renders identically to before this ticket.
When a reading is live, `StatusReading` takes `Modifier.weight(1f)` inside the `Row` and `Arrangement.End`
keeps the pill flush against the gutter; when no reading is live, `StatusReading` emits no node (§ *The band
collapses when nothing is live* below still holds), its weight goes with it, and `Arrangement.End` leaves the
pill alone at the right end. `NoticePill` gained a `shadowElevation: Dp = PillShadow` parameter for this
caller — Figma `568:3162` (the in-band pill) carries no drop shadow, unlike [`ThreadTopOverlay`](thread-top-overlay.md)'s
pills, which keep the default; see [Notice pill § Three call sites](notice-pill.md#three-call-sites-three-contracts).
A clickable `Surface` inside a 24dp band would otherwise lay out at M3's 48dp minimum interactive size and
double the band's height — `CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides
Dp.Unspecified)` around the pill keeps the layout at Figma's 24dp while Compose's hit-test still expands the
pill's *touch* bounds to 48dp, so the tap target is unaffected. This is the app's first use of that local.
`TaskCountPillTest` (`app/src/sharedTest/.../thread/`, `@GraphicsMode(NATIVE)` — see [Compose evidence](development-verification-compose-evidence.md#compose-evidence))
proves the band's collapse is exact rather than assumed: it measures the newest message row's bottom edge
(not the input field's top — the composer is a bottom-anchored `bottomBar`, so only the band's own height
moves that edge) at zero tasks, again once the pill raises it by exactly 32dp (the pill's 24dp plus the
column's 8dp gap), and again after it returns to zero, asserting the second zero-count measurement equals
the first.

When a reading and task pill coexist, their content sets the band's height: both occupy 24dp at normal
text scale, placing the pill at (288, 696)–(392, 720) in the 412 × 892 dark frame. Only this caller
sets a 104 × 24dp minimum pill size; larger labels and text scales can grow. The former 28dp combined-band
minimum raised the pill and composer by 4dp. A Robolectric label-width measurement was about 2dp wider
than the managed device's, so a tolerant screen assertion missed the narrower device pill; the local
minimum width closes that observed gap. The [labelled emulator comparison](../../../app/src/androidTest/assets/task-pill-1296/comparison-412x892.png)
shows the result against Figma. With only the pill, the row keeps its intrinsic height. Physical pointer
checks cover the pill, adjacent reading and composer targets; a device check covers the real keyboard and
Actions menu with a visible pill. The fixed 16dp arc for retry, compaction and Reset came from device capture: a
Material indeterminate arc could shrink to a barely visible stroke at one animation frame while a bounds
assertion still passed. Native-graphics tests sample visible pixels over multiple frames, and the compact
device capture checks the two-line outcome label beside the task pill. See [capture evidence](../../../app/src/androidTest/assets/thread-activity-1209/comparison.txt).

**[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) removed `usageLimit`, `showRePair` and
`onRePair` from this composable, and deleted the private `RePairButton` it used to hold.** From #804 to
\#1002 `ThreadStatusArea` took a `usageLimit: UsageLimitReading?` parameter and raised an arm for it between
api-retry and resetting; from #843 to #1002 it also took `showRePair` / `onRePair` and, while `showRePair`
held, wrapped the signal in a `Row` beside a private `RePairButton` (Figma `354:7093`, a 6dp-radius
`Surface` at 16dp/8dp padding) so the row split leading (signal) / trailing (button). Both moved out because
claude has attached an `allowed_warning` usage report to every turn since 2026-09-24, and the reading
outranked resetting, compaction, the turn outcome and thinking/running tool — none of them rendered while
it was live. `ThreadScreen` now draws both notices as pills in [`ThreadTopOverlay`](thread-top-overlay.md),
pinned over the top of the message area instead of sharing this slot; see that document for the pill
shapes, including the exact Figma deviation the old `RePairButton` recorded (the theme's
`errorContainer`/`onErrorContainer` pair rather than Figma's literal `on-error`/`error`, carried forward
unchanged by [`NoticePill`](notice-pill.md)) and for `showRePair`'s sourcing, which #1002 left untouched.
`ThreadStatusArea` is now turn status only, and always a single signal with no trailing slot.

The `bottomBar` column's third band was `ThreadStatusRow(model = …, effort = …, onExpandClick = { sheetVisible = true }, …)` through [#807](../codebase/807.md); [#808](../codebase/808.md) replaced it with [`ThreadComposerFooter`](thread-composer-footer.md), shown above, and wrapped the surrounding `Scaffold` in a `Box` so an [`OptionsOverlay`](options-overlay.md) can draw above it on selection — see [Thread composer footer](thread-composer-footer.md) for the full wiring. `ThreadStatusArea` itself (below) is unaffected by that change.

- **The pairing pill (#843, moved to the Top overlay by #1002).** The KDoc above `ThreadStatusArea` had
  reserved a trailing contextual-action slot since #643 ("stays empty until #675 fills it");
  [#843](https://github.com/pyrycode/pyrycode-mobile/issues/843) filled it with a **Re-pair** button while
  `showRePair` held, and #1002 replaced that button with the pairing [`NoticePill`](notice-pill.md) in
  [`ThreadTopOverlay`](thread-top-overlay.md#the-pairing-pill) — same trigger (`showRePair`), same target
  (`onRePair`), same colours, different surface. `showRePair` comes from `ThreadViewModel.rePairAvailable`,
  `true` exactly when this thread's own host is in the rejected-pairing state (see below); `onRePair` is
  bound at `MainActivity` to `navController.navigate(Routes.pairCode(target.serverId))`. The label is the
  local string resource `R.string.thread_re_pair` ("Pairing error - Re-pair"), never daemon-authored text.
- **Sourced by `serverId` through the registry, not the captured connection bundle.** `ThreadDestinationFactory.thread` (`di/AppModule.kt`) builds `showRePair`'s upstream from a new top-level `pairingRejected(connections: Flow<List<HostConversationConnection>>, serverId: String): Flow<Boolean>` that finds the matching entry in `RelayConnectionRegistry.hostConnections` and `flatMapLatest`s onto *its* `status` — not off the `HostConversationConnection` bundle the factory captured when the destination opened. This is load-bearing: a successful re-pair changes the saved record, and `RelayConnectionRegistry.reconcile` closes the old bundle and publishes a **new** entry with its own `status` flow for the same `serverId`. Reading through the registry means the `flatMapLatest` picks up the replacement and the pill clears; reading off the captured bundle would have kept observing the closed connection's now-frozen status and the action could never go away after a successful re-pair. `ThreadViewModel` exposes the result as a sibling `StateFlow<Boolean>` (`rePairAvailable`, `WhileSubscribed(5_000)`, defaulted to `flowOf(false)` so the demo destination is unaffected), matching the `connectionState` precedent rather than widening `ThreadUiState` — the `state` combine is already at its five-arity ceiling. Untouched by #1002.

- **Moved, not rewritten.** The three original arms, their flags and their precedence (api-retry first, then compaction, then thinking — see [API-retry indicator](api-retry-indicator.md#placement-in-the-thread)) are byte-identical to the pre-#643 `when`; only the mount point and the horizontal inset changed at #643. `ThinkingIndicator.kt`, `ApiRetryIndicator.kt` and `CompactingIndicator.kt` were not touched by that move.
- **[#803](thinking-indicator.md) adds a sixth flat sibling, `thinkingProgress: ThinkingProgress?`, and no new arm.** It decorates the thinking arm's own `else` branch, so it rides the precedence above rather than adding to it — retry and compaction still pre-empt a live reading for free. Visibility stays `isThinking`'s alone; see [Thinking indicator § What it does](thinking-indicator.md#what-it-does).
- **[#805](https://github.com/pyrycode/pyrycode-mobile/issues/805) adds a flat sibling, `turnOutcome: TurnOutcomeReport?`, and one new `when` arm** — inserted between compaction and thinking, the bottom of the ladder at the time. Compaction is mid-turn progress and a turn outcome is necessarily post-turn, so the two co-occurring has not been observed. The arm is raised by a `turnOutcomeReport(event)` classification held in `ThreadViewModel.turnOutcome`, and it clears itself the moment the next turn's `thinking`/`responding` phase arrives — never on `idle`, which may arrive on either side of the `turn_end` it accompanies. See [Turn-outcome indicator](turn-outcome-indicator.md#placement-in-the-thread) for the full component.
- **#872 adds a flat sibling, `resetting: ResetStatus?`, and one new `when` arm** — inserted directly above compaction (Reset session's phase belongs below the "something may be wrong" signal(s) above it and above compaction's benign progress). See [Resetting indicator](resetting-indicator.md#placement-in-the-thread) for the full component.
- **[#897](thinking-indicator.md#the-running-tool-897) adds a flat sibling, `runningTool: ToolCall?`, and no new arm.** Like `thinkingProgress`, it decorates the thinking arm's own `else` branch, so every arm above still pre-empts it for free. It is also the one sibling here that is not sourced from a `ThreadViewModel` flow: `ThreadScreen` derives it locally as `if (isBusy) openToolCall(state.items) else null`, `openToolCall` being a pure top-level function beside the screen (`internal fun openToolCall(items: List<ThreadItem>): ToolCall?` — the latest `Running`-status tool row, or `null`). See [Thinking indicator § The running tool](thinking-indicator.md#the-running-tool-897) for the selector and label detail.
- **[#804](https://github.com/pyrycode/pyrycode-mobile/issues/804) had added a flat sibling, `usageLimit: UsageLimitReading?`, and one `when` arm between api-retry and resetting; [#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) removed both.** See the callout above the code block for the full account. The ladder is now turn status only: `api-retry → resetting → compaction → turn outcome → thinking/running tool`.
- **`ComposerStatusGutter = 20dp − 16dp = 4dp`.** The three indicator files each already carry their own 16dp horizontal padding (sized for their old full-bleed foot-of-list mount), so reaching the design's 20dp content gutter needs only the 4dp remainder here, not the full 20dp — passing the full gutter would double the inset and land the indicators' content at 36dp, a fidelity miss that reads as a design error rather than a padding sum.
- **The band collapses when nothing is live.** Every arm still early-returns when its flag is false, so an idle status area emits no node and the composer column's `Arrangement.spacedBy(8.dp)` gap simply doesn't open above the input field.
- **The 12dp gap above the whole `Input area`** (`ComposerTopGap`, the composer column's own top padding) is what used to be the space between the list and the foot-of-list `Column`; it now holds regardless of whether a status arm is showing.
- No longer gated on `hasMessages` in any special way — moving out of the content `Column` entirely means the empty-thread and populated cases share the same composer, so the old "outside the branch" reasoning is moot.

### Thread top overlay placement (post-#1002)

[#1002](https://github.com/pyrycode/pyrycode-mobile/issues/1002) draws
[`ThreadTopOverlay`](thread-top-overlay.md) — the usage-limit, pairing-error and Offline Retry pills this section's
callouts describe leaving `ThreadStatusArea` for — as an **overlap**, not a `Column` child, inside the
message-area `Box` in the content `Column` (not the `bottomBar` column `ThreadStatusArea` lives in):

```kotlin
Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
    if (!state.hasMessages && state.queuedMessages.isEmpty()) {
        EmptyThreadState(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(autoScrollNestedScroll), reverseLayout = true) { … }
    }
    ThreadTopOverlay(
        usageLimit = usageLimit,
        usageLimitDismissed = usageLimit?.dismissalKey() in dismissedUsageLimits,
        onDismissUsageLimit = { usageLimit?.let(onDismissUsageLimit) },
        showRePair = showRePair,
        onRePair = onRePair,
        connectionState = connectionState,
        onRetryConnection = onRetry,
        modifier = Modifier.align(Alignment.TopEnd).padding(start = ComposerGutter, top = TopOverlayTopGap, end = ComposerGutter),
    )
}
```

Wrapping both branches of the empty/populated `if` in one `Box` is what lets one `ThreadTopOverlay` call
cover both — the pre-#1002 code had no shared parent at this level, since the empty and populated branches
each took the `weight(1f)` slot directly. The overlay draws *after* (so *over*) whichever branch rendered,
and `Alignment.TopEnd` plus the overlay's own early-return-to-nothing keeps it from taking layout space —
the list never reflows as a pill appears or clears. See [Thread top overlay](thread-top-overlay.md) for the
composable itself, the dismissal holder `UsageLimitDismissals`, and why this replaced the status-row arms.
The current frame sets `TopOverlayTopGap = 0.dp`: its visible content begins at the message region's
top edge, 12 dp below the completed bar, and its right edge shares the 20 dp gutter with the rows.

### Interrupt-affordance placement (post-#459, retired from the screen in #643)

[#459](../codebase/459.md) originally mounted the stateless [`InterruptAffordance`](interrupt-affordance.md) (the "Stop the running turn" control) at the foot of the content `Column`, immediately below `ThinkingIndicator` — the two stacked visibly whenever `isThinking && isBusy` were both true, a code-review NIT flagged as interim and design-owed at the time.

[#643](../codebase/643.md) applied the Figma `16:8` frame and retired that standalone control from the screen entirely, following desktop's #678 precedent: **the composer's message-input button now carries the stop action as one of two states**, so the waiting signal (in the status area above) and the stop affordance (on the send button) can never stack again. `InterruptAffordance` keeps its file, its own tests and its own doc — it simply has no production call site on `ThreadScreen` any more (`codegraph_callers`: none outside its own file, flagged by code review as dead code worth a follow-up to either retire the file or record what still keeps it alive). See [Interrupt affordance](interrupt-affordance.md#placement--wiring) for the send/stop precedence table and the composer wiring, and [Thread input bar](thread-input-bar.md#shape) for the button itself.

### Stall-promotion-banner placement (post-#396, retired from the screen in #883)

[#396](../codebase/396.md) mounted the stateless `StallPromotionBanner` directly **below** [`ConnectionBanner`](connection-banner.md) in the content `Column`, above the workspace chip / empty state / message list — outside the scrolling `LazyColumn` — as a prominent CTA that reused the overflow menu's "Show the literal screen" navigation (`onShowLiteralScreen`) while `isStalled` held.

[#883](../../specs/architecture/883-retire-literal-screen.md) removed the banner outright, once the daemon dropped the server-side screen-snapshot render path the action opened: `ThreadScreen` no longer renders it, and since it was `isStalled`'s only consumer, `ThreadScreen`'s `isStalled` parameter and `MainActivity`'s collection of `vm.isStalled` were removed too. `ThreadViewModel.isStalled` and the underlying `observeStall` projection are untouched — see [Stall state](stall-state.md), which now has no UI consumer.

### Permission-modal placement (post-#446, moved inline in #1306)

[#446](../codebase/446.md) rendered the hoisted [`currentModal`](current-modal-state.md) (#445; since #816,
already filtered to this thread's own conversation) as the seventh `Scaffold` sibling — a floating dialog
window, outside the content `Column`. **[#1306](permission-modal-overlay.md) removed that sibling for the
`Open` case**: the request now renders as three `LazyColumn` items inside the message list itself (§
[list and status row § Inline permission rows](thread-screen-how-it-works-list-and-status-row.md#inline-permission-rows-and-the-shared-reveal-1306)),
so the reader can scroll history, go Back or switch conversations without answering or cancelling. Only
`Dismissed` still reaches this `when (modalState)` block after the `DeleteConfirmationDialog` block
(`ThreadScreen.kt`) — `Open` now resolves to `Unit` there, since its rendering moved into the list. Full
doc: [Permission-modal overlay](permission-modal-overlay.md).

- **Inline card, not a separate surface.** `permissionRequestItems` renders verbatim `title` / `prompt` /
  `options` (wire **array order**) inside a bordered card, then Cancel below it; `ModalOptionButton` keeps
  its stateless **3-way** ([#452](../codebase/452.md), `isArmed` precedence): the fail-safe-deny
  `defaultOptionId` → filled `Button`, the VM's armed non-default (`armedOptionId`) → `FilledTonalButton`
  (below the default's emphasis), else `OutlinedButton`, each with its `stateDescription` marker.
  `modalClass` is carried but not branched on.
- **Every decision callback now carries the rendered `modalId`.** `onModalOption(modalId, optionId)` /
  `onModalCancel(modalId)` / `onAlwaysAllowChanged(modalId, accepted)` all forward the id the composed item
  was drawn for; `ThreadViewModel` rejects a mismatch against `scopedModal()`'s synchronous read
  ([Modal answer flow § Stale taps](modal-answer-flow.md#stale-taps-carry-the-wrong-modalid-1306)) — a tap
  composed before a replacement can no longer answer, cancel or grant the replacement in one tap.
- **Scoped to this thread's conversation (#816).** Unchanged: the coordinator's fold holds one modal per
  host (not a per-conversation map), but `ThreadViewModel.currentModal` filters it via
  `ModalUiState.scopedTo(conversationId)` before the screen ever sees it: a modal raised for another
  conversation on the same host arrives here as `Hidden`, so it neither renders nor can be answered from
  this thread. A missing/blank `conversation_id` on the wire scopes to no thread rather than every thread.
- **Dismiss stays a snackbar, not a row.** `Dismissed` renders no list item and fires a
  `LaunchedEffect(modalId)` snackbar surfacing a **mapped local** reason (`dismissReasonText`:
  remote/local/timeout + a generic forward-compat fallback). The Scaffold still carries its
  `remember { SnackbarHostState() }` + `snackbarHost`, mirroring the
  [`ArchivedDiscussionsScreen`](archived-discussions-screen.md) dismiss-reason precedent. Keying on `modalId`
  (a sticky terminal state in #445's fold) fires it exactly once per resolution.
- **Security moved from the dialog window to the activity surface (#1306).** Plain `Text` only, bounded by a
  length constant (never [`MarkdownText`](markdown-text.md)/`SelectionContainer`) — unchanged. But the
  dialog's own-window `FLAG_SECURE` and `filterTouchesWhenObscured` are gone along with the dialog; the
  request now relies on the same `QuestionPromptProtection` the #1305 question batch mounts on the activity
  surface, kept for either prompt kind from one call site (§
  [list and status row](thread-screen-how-it-works-list-and-status-row.md#inline-question-rows-and-the-newest-end-reveal-1305)).
  No modal text reaches `rememberSaveable` / saved-instance state. Back / outside-tap no longer have a
  dialog's `dismissOnBackPress`/`dismissOnClickOutside` to disable — they simply don't reach the request at
  all (it is list content, not a dismissable surface); Cancel is still the only path to `onModalCancel`. The
  send-error confidentiality and the fail-safe-deny posture are unchanged; see
  [Permission-modal overlay § Security](permission-modal-overlay.md#security) for the full accounting.

### Slash-command type-ahead placement (post-#885)

[#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) adds a second `OptionsOverlay` mount alongside the footer's, in the same overlay `Box` layer the footer anchor-tracking already draws into (see [Thread composer footer § Wiring in `ThreadScreen`](thread-composer-footer.md#wiring-in-threadscreen)):

```kotlin
var inputAnchor by remember { mutableStateOf<Rect?>(null) }
val imeVisible = WindowInsets.isImeVisible  // @OptIn(ExperimentalLayoutApi::class)

ThreadInputBar(
    // ...
    onAnchorChanged = { inputAnchor = it },
)

SlashCommandTypeAhead(
    text = draft,
    commands = state.slashCommands,
    anchor = inputAnchor?.takeIf { openMenu == null }?.translate(-layerOrigin),
    imeVisible = imeVisible,
    onComplete = onDraftChange,
    resetKey = state.conversationId,
)
```

`inputAnchor` mirrors the existing `footerAnchors` map's own pattern one level up — [`Thread input bar`](thread-input-bar.md)'s new `onAnchorChanged` parameter reports the field's live `boundsInWindow()`, translated into the layer's own coordinates by subtracting `layerOrigin`, the same translation every `OptionsOverlay` anchor in this file already applies. `anchor?.takeIf { openMenu == null }` is what keeps this overlay and the footer's from ever drawing at once — passing `null` closes [`SlashCommandTypeAhead`](slash-command-type-ahead.md) unconditionally whenever a footer menu (`openMenu != null`) is open, rather than relying on z-order or manual dismissal. `resetKey = state.conversationId` matches the conversation-keyed `remember` idiom the footer's own overlay state already uses, so no suggestion state survives a conversation switch. See [Slash-command type-ahead](slash-command-type-ahead.md) for the composable's own state machine (`dismissedFor`, the two `LaunchedEffect`s) and [Thread input bar § Draft binding](thread-input-bar.md#draft-binding--cursor-at-end-undo-and-redo-885-934) for what a pick's `onDraftChange` call requires of the field underneath it.

### Background-tasks panel placement (post-#678)

[#678](https://github.com/pyrycode/pyrycode-mobile/issues/678) draws
[`BackgroundTaskPanel`](thread-composer-footer-actions-menu.md#actions-menu-884) directly inside `ThreadScreen`, right
after the footer's `OptionsOverlay` `Box` closes and before the `WorkspacePicker` mount — not as an eighth
`Scaffold` sibling and not from the `MainActivity` destination block the way
[`QuestionBatchModal`](question-batch-modal.md) is drawn (`MobileReadOnlyModal` opens its own `Dialog`
window, so its place in the composition tree doesn't affect what it draws over):

```kotlin
var backgroundTasksOpen by remember(state.conversationId) { mutableStateOf(false) }
// … footer OptionsOverlay Box …
if (backgroundTasksOpen) {
    BackgroundTaskPanel(roster = state.backgroundTasks, onDismiss = { backgroundTasksOpen = false })
}
WorkspacePicker(...)
```

`backgroundTasksOpen` is a plain `remember`, not `rememberSaveable`, keyed on `state.conversationId` — the
same idiom `openControl` uses one field up: switching conversations drops an open panel, and a process
death never restores one a fresh screen instance never opened. The Actions menu's background-tasks row sets
it (see [Thread composer footer § Actions menu](thread-composer-footer-actions-menu.md#actions-menu-884)); since
[#1043](https://github.com/pyrycode/pyrycode-mobile/issues/1043) the status band's task-count pill (§
[Thinking-indicator placement](#thinking-indicator-placement-post-407-moved-in-643) above) sets the same
flag through the same `{ backgroundTasksOpen = true }` lambda, so the panel now has two openers over one
piece of state rather than a second flag to keep in sync. Closing the
panel — the close glyph or Back, routed through `MobileReadOnlyModal`'s single
`onDismissRequest` — only flips it back, sending nothing and touching no task or conversation state.
[#1496](mobile-modal.md#the-read-only-panel-mobilereadonlymodal) removed the footer Close button and its
`closeLabel` parameter; the panel's sheet now also runs to the screen's bottom edge, matching its Figma
frames.
`state.backgroundTasks` (`BackgroundTaskRoster?`) and `state.backgroundTaskCount` (`Int`) reach
`ThreadUiState` from two defaulted `ThreadViewModel` constructor lambdas bound in `AppModule` to the open
host's `RelayRepositoryCoordinator.observeBackgroundTasks` / `observeLiveBackgroundTaskCount` — the same
per-conversation binding shape `questionBatch` uses — and default to `null` / `0` on the demo destination.
See [Shared mobile modal § Callers](mobile-modal-callers.md#callers) for the panel's own content and trust-boundary
handling.

### `fun retry()` — non-suspend, VM owns the launch

```kotlin
fun retry() {
    viewModelScope.launch { connectionStateSource.retry() }
}
```

The VM exposes `retry()` as a non-`suspend` method; the `viewModelScope.launch` body wraps the source's `suspend fun retry()`. UI callers bind `vm::retry` directly to `ThreadTopOverlay`'s Retry callback without `rememberCoroutineScope { ... }.launch { ... }`. Same shape as `fun sendMessage(text: String)` from #188 — the convention in this codebase is never to expose `suspend` on a VM. If the screen is destroyed mid-call the launch is cancelled, which is fine for the Phase-2 no-op body; in Phase 4 the real source's `suspend fun retry()` may do network I/O, and `viewModelScope` cancellation will propagate as expected.

No `try/catch` around `connectionStateSource.retry()`. Per the `ConnectionStateSource` interface KDoc (#196), failures surface as state transitions (`Offline`), not exceptions; the Phase-2 fake cannot throw. No `.catch { ... }` on the upstream `observe()` either — premature defense.

### `connectionState: StateFlow<ConnectionState>` — same lifetime as `state`

```kotlin
val connectionState: StateFlow<ConnectionState> =
    connectionStateSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Connected)
```

`SharingStarted.WhileSubscribed(5_000)` matches the `state` flow's lifetime policy — both share the same `viewModelScope` and subscription window. If the screen resumes within 5s of leaving the back stack, the existing collector is reused (no `Connected` flash from re-subscription). `initialValue = ConnectionState.Connected` matches the fake's seeded value so the screen's first frame paints with the banner already short-circuited.

The VM's own `connectionStateSource.observe()` call is unchanged by #1318 — what changed is what `ThreadDestinationFactory.thread` (`di/AppModule.kt`) passes as the `ConnectionStateSource`. It now reads `bundle.coordinator.connectionStatus.map { it.toConnectionState() }`, the two-leg model, in place of the relay-only `bundle.supervisor.observe()`: `Connected` now means the pyrycode leg's Noise handshake finished, not just the relay socket opening, so a live-socket-but-unhandshaken host reads `Connecting`. See [Connection state § #1318](connection-state.md) for the mapping and [Relay reconnect supervisor § #1318](relay-reconnect-supervisor.md) for the `toConnectionState()` overload.

### `ThreadTopAppBar` — Figma `16:8` chrome

**[#643](../codebase/643.md) replaced the stock M3 `TopAppBar` with a hand-rolled bar plus a closing rule** — the Figma `16:8` `Top bar` frame (`533:1948`), the same design language [`ChannelListTopBar`](channel-list-screen.md) already shipped for the channel list. `ThreadTopAppBar(title, onBack, onTitleClick, onOverflowClick, overflowExpanded, onOverflowDismiss, onOverflowEvent, isPromoted, mutationsSupported, modifier)` (the `onShowLiteralScreen` parameter [#382](../codebase/382.md) had added here was removed by [#883](../../specs/architecture/883-retire-literal-screen.md)) stays a **public** stateless composable in its own file (`ThreadTopAppBar.kt`); only its body changed. It is now a `Column` of a content `Row` and a `HorizontalDivider`:

- **Back** — a 48dp `IconButton(onClick = onBack)` around the 24dp `ic_thread_back` Figma vector, tinted `onSurface`; `R.string.cd_back` ("Back") remains its accessible name.
- **Title** — `Text(text = title, modifier = Modifier.weight(1f).clickable(onClick = onTitleClick).semantics { role = Role.Button }, style = titleLarge, color = onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)`, sitting between the two controls. `weight(1f)` precedes `.clickable(...)`, so the `Text` measures to the **full title slot**, not just its visible glyphs — the tap area and ripple cover the trailing space after a short title too (harmless: `Row` siblings never overlap, so it can't reach either control, and it is arguably a better target than the stock bar's text-sized one). `maxLines = 1` + `TextOverflow.Ellipsis` is what makes a display name longer than the slot truncate inside it rather than overlap or cover a control. `Role.Button` keeps TalkBack announcing the title as activatable.
- **Overflow** — keeps the `Box { IconButton; ThreadOverflowMenu }` anchor. Its 48dp target contains the Figma `ic_thread_overflow` vector, a 6 × 24dp path tinted `primary` and shifted 4dp upward; `R.string.cd_more_actions` ("More actions") remains its accessible name.
- **Rule** — a 1dp `HorizontalDivider` at y=68, inset 20dp on both sides (`x=20`, width 372dp at the reference viewport). Colour comes from `threadColors.headerRule` at 60% alpha: `inversePrimary` (`#32628D`) in static dark, and `outlineVariant` in static light and wallpaper modes.
- **Geometry.** At 412 × 892 dp, the 61dp top-bar frame starts at (20, 24), its rule is at y=68, and the message region begins at y=97. The visible back and title use the 20dp gutter while 48dp targets extend into available space. `ThreadBarTopGap` raises only this bar; the markdown reader keeps `BarTopGap`, avoiding an unrelated 4dp shift. The title still truncates inside its middle slot.

Under the static dark palette, `ThreadScreen` draws a radial `primaryContainer` glow over the
theme surface with a 30% scrim, matching the live `16:8` root. Its `Scaffold` is transparent in
this mode so the glow reaches the header and blank message region; the composer surround retains
`threadColors.surface`. Static light and wallpaper variants keep the flat thread background.

`PyrycodeMobileTheme` provides the immutable `ThreadColors` palette from the resolved app mode and
wallpaper setting, also used by the [markdown reader](markdown-reader-screen.md#what-it-does).
Changes propagate to already-composed screens without consulting system mode inside either screen.
Global Material roles, bubble/input fills and layout stay unchanged. Keep Scaffold's explicit
`contentColor = onBackground`: the custom canvas is not a global Material role, so automatic
content-colour lookup cannot infer the intended inherited foreground. The reader similarly retains
`onSurface` explicitly. See the [palette plan](../../specs/architecture/1162-thread-reader-canvas.md).

**The inset dispute is settled by current code, in `ChannelListTopBar`'s favour.** `MainActivity`'s root `Scaffold` declares neither a `topBar` nor a `bottomBar`, so the `innerPadding` it hands `PyryNavHost` (via `Modifier.padding(innerPadding)`) is its **whole** `contentWindowInsets` — every destination, including the thread screen, is already padded past the status and navigation bars before `ThreadScreen`'s own per-screen `Scaffold` ever runs. The stock `TopAppBar` this bar replaced was applying a **second** status-bar inset on top of that via `TopAppBarDefaults.windowInsets`; the hand-rolled bar declares no window insets of its own and needs none. The claim that used to stand here — that the outer `Scaffold` "doesn't consume the top inset" — was stale; `ChannelListTopBar`'s own KDoc, written later against the shipped list screen, is the accurate note.

The composable is **stateless** per the project convention — no `remember`, no `MutableState`. All callbacks are caller-owned.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied.

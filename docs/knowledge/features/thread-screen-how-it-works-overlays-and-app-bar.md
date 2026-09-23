# Thread screen — how it works, overlays, retry and the app bar

Split out of [Thread screen](thread-screen.md) on 2026-09-05 to keep that document under the 50000-byte size cap the docs guard enforces. Every section below moved here verbatim and kept its heading, so its anchors are unchanged. Part of [Thread screen](thread-screen.md); see that document for what it does, its edge cases and its links.

### Connection-banner wiring

The Scaffold content slot wraps the [`ConnectionBanner`](connection-banner.md) above the `LazyColumn` in a `Column`:

```kotlin
Column(modifier = Modifier.padding(inner).fillMaxSize()) {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), reverseLayout = true) { … }
}
```

Three design points pinned in #201:

- **Structural, not overlay.** Per the AC, the banner pushes the message list down by its intrinsic height; under `Connected` the banner's early `return` collapses to zero height so the steady-state look is byte-identical to pre-#201. Alternatives (`Box` with manual offset, `Scaffold` content overlay, `topBar = { Column { TopAppBar; ConnectionBanner } }`) all either change the overlay semantics (the AC forbids overlay) or force a refactor of `ThreadTopAppBar`. The `Column` wrapper is structurally minimal.
- **`Modifier.padding(inner)` lives on the outer `Column`, not the `LazyColumn`.** The Scaffold's content-inset wraps the banner *and* the list — applying `padding(inner)` only to the `LazyColumn` (leaving the banner outside the inset) would let the banner draw under the AppBar's status-bar inset on edge-to-edge devices.
- **`Connected` short-circuit is the steady state.** The fake's `FakeConnectionStateSource` always emits `Connected`; the banner's public-entry `when` returns early on `Connected` with no composition. Under normal use the banner is invisible; the integration is exercised by VM unit tests that push `Offline` / `Connecting` / `Reconnecting` through the fake (see [Testing](thread-screen-testing.md#testing) below).

`connectionState: ConnectionState` is a flat parameter rather than a field on `ThreadUiState` — see [`#201`'s ticket notes](../codebase/201.md) for the full rationale. Short version: the existing `state` derivation stays untouched (no `combine(...)` ceremony, no churn to the seven existing tests that pattern-match `ThreadUiState`); connection state is global (every screen would consume the same source under future work) while conversation state is per-screen; reflecting that orthogonality at the type level is cleaner than artificial fusion. The destination block consumes two `collectAsStateWithLifecycle()` calls — the same shape `SettingsViewModel` already uses for its four separate flows. [`#406`](../codebase/406.md) added a **second** such sibling signal to the VM on this same rationale: `val isThinking: StateFlow<Boolean>` (`ThreadViewModel.kt:216`), the live `turn_state` thinking-phase flag reduced from the [coordinator](relay-repository-coordinator.md)'s `liveSessionEvents` seam — a transient, connection-scoped cross-cutting signal kept off `ThreadUiState` so the `state` combine stays zero-touch. [`#407`](../codebase/407.md) then threaded it into `ThreadScreen` as a **third** flat sibling parameter — `isThinking: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:74`), collected at `MainActivity` via `vm.isThinking.collectAsStateWithLifecycle()` exactly parallel to `connectionState` — and rendered the at-work [`ThinkingIndicator`](thinking-indicator.md) at the foot of the content `Column` (see [Thinking-indicator placement (post-#407, moved in #643)](#thinking-indicator-placement-post-407-moved-in-643) below). See [Turn-state thinking flag](turn-state-thinking-flag.md) for the data path. [`#396`](../codebase/396.md) added a **fourth** sibling signal on the *same* rationale: `val isStalled: StateFlow<Boolean>` (`ThreadViewModel.kt:225`, beside `isThinking`), sourced straight off the already-injected `repository.observeStall(conversationId)` (#395) — **no constructor/DI/interface change**, unlike `isThinking` which needed the new `liveSessionEvents` ctor param — and threaded into `ThreadScreen` as a **fourth** flat sibling parameter `isStalled: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:75`), collected at `MainActivity` via `vm.isStalled.collectAsStateWithLifecycle()`. It deviates from the ticket's "hoist into `UiState`" Technical Note deliberately: stall is the same signal class as `isThinking`, so siblinghood is consistent and the 5-arity `state` combine stays untouched. See [Stall state](stall-state.md) for the data path and [Stall-promotion-banner placement (post-#396)](#stall-promotion-banner-placement-post-396) below for the render. [#459](../codebase/459.md) added a **fifth** sibling on the *same* rationale: `val isBusy: StateFlow<Boolean>` (`ThreadViewModel.kt:296`, beside `isThinking`) — declared **identically** to `isThinking` over the same `liveSessionEvents` seam but **broadened** to `thinking` **or** `responding` via a dedicated `busyTransition` reducer (a "a turn is running" signal, not the `thinking`-only one; see [Interrupt affordance](interrupt-affordance.md)) — and threaded into `ThreadScreen` as two defaulted siblings, `isBusy: Boolean = false` plus the flat `onInterrupt: () -> Unit = {}` callback (`:102`), collected at `MainActivity` via `vm.isBusy.collectAsStateWithLifecycle()` with `onInterrupt = vm::onInterrupt`.

`onRetry` binds to `vm::retry` at the destination — method reference, not a fresh lambda, so the binding is stable across recompositions (the lambda allocation only happens once per VM lifecycle, not per recomposition).

### Thinking-indicator placement (post-#407, moved in #643)

[#407](../codebase/407.md) originally mounted the stateless [`ThinkingIndicator`](thinking-indicator.md) as the final child of the content `Column`, at the foot of the list above the `bottomBar`. [#643](../codebase/643.md) moved it — along with the retry and compaction arms it shares a slot with — into the composer itself, as the **`Status area`** band of the Figma `16:8` `Input area` (`533:1957`). It is now the first child of the `bottomBar` `Column`, rendered by a private `ThreadStatusArea` composable (`ThreadScreen.kt:471`) that exists purely so the `bottomBar` lambda stays readable:

```kotlin
bottomBar = {
    Column(Modifier.fillMaxWidth().background(surface).imePadding().padding(top = 12.dp, bottom = 16.dp)) {
        ThreadStatusArea(apiRetry = apiRetry, usageLimit = usageLimit, isCompacting = isCompacting, isThinking = isThinking, thinkingProgress = thinkingProgress)
        ThreadInputBar(onSend = onSendMessage, isBusy = isBusy, onInterrupt = onInterrupt, …)
        ThreadStatusRow(model = …, effort = …, onExpandClick = { sheetVisible = true }, …)
    }
}

@Composable
private fun ThreadStatusArea(
    apiRetry: ApiRetryStatus,
    usageLimit: UsageLimitReading?, // #804
    isCompacting: Boolean,
    isThinking: Boolean,
    thinkingProgress: ThinkingProgress?, // #803
) {
    val slot = Modifier.fillMaxWidth().padding(horizontal = ComposerStatusGutter) // 20dp gutter − the indicators' own 16dp
    when {
        apiRetry != ApiRetryStatus.NotRetrying -> ApiRetryIndicator(status = apiRetry, modifier = slot)
        usageLimit != null -> UsageLimitIndicator(reading = usageLimit, modifier = slot)
        isCompacting -> CompactingIndicator(isCompacting = true, modifier = slot)
        else -> ThinkingIndicator(isThinking = isThinking, modifier = slot, progress = thinkingProgress)
    }
}
```

- **Moved, not rewritten.** The three original arms, their flags and their precedence (api-retry first, then compaction, then thinking — see [API-retry indicator](api-retry-indicator.md#placement-in-the-thread)) are byte-identical to the pre-#643 `when`; only the mount point and the horizontal inset changed at #643. `ThinkingIndicator.kt`, `ApiRetryIndicator.kt` and `CompactingIndicator.kt` were not touched by that move.
- **[#803](thinking-indicator.md) adds a sixth flat sibling, `thinkingProgress: ThinkingProgress?`, and no new arm.** It decorates the thinking arm's own `else` branch, so it rides the precedence above rather than adding to it — retry and compaction still pre-empt a live reading for free. Visibility stays `isThinking`'s alone; see [Thinking indicator § What it does](thinking-indicator.md#what-it-does).
- **[#804](https://github.com/pyrycode/pyrycode-mobile/issues/804) adds a seventh flat sibling, `usageLimit: UsageLimitReading?`, and one new `when` arm** — inserted between api-retry and compaction, since claude's usage-limit report is informational but must not be masked by compaction's benign progress. See [Usage-limit indicator](usage-limit-indicator.md#placement-in-the-thread) for the full component.
- **`ComposerStatusGutter = 20dp − 16dp = 4dp`.** The three indicator files each already carry their own 16dp horizontal padding (sized for their old full-bleed foot-of-list mount), so reaching the design's 20dp content gutter needs only the 4dp remainder here, not the full 20dp — passing the full gutter would double the inset and land the indicators' content at 36dp, a fidelity miss that reads as a design error rather than a padding sum.
- **The band collapses when nothing is live.** Every arm still early-returns when its flag is false, so an idle status area emits no node and the composer column's `Arrangement.spacedBy(8.dp)` gap simply doesn't open above the input field.
- **The 12dp gap above the whole `Input area`** (`ComposerTopGap`, the composer column's own top padding) is what used to be the space between the list and the foot-of-list `Column`; it now holds regardless of whether a status arm is showing.
- No longer gated on `hasMessages` in any special way — moving out of the content `Column` entirely means the empty-thread and populated cases share the same composer, so the old "outside the branch" reasoning is moot.

### Interrupt-affordance placement (post-#459, retired from the screen in #643)

[#459](../codebase/459.md) originally mounted the stateless [`InterruptAffordance`](interrupt-affordance.md) (the "Stop the running turn" control) at the foot of the content `Column`, immediately below `ThinkingIndicator` — the two stacked visibly whenever `isThinking && isBusy` were both true, a code-review NIT flagged as interim and design-owed at the time.

[#643](../codebase/643.md) applied the Figma `16:8` frame and retired that standalone control from the screen entirely, following desktop's #678 precedent: **the composer's message-input button now carries the stop action as one of two states**, so the waiting signal (in the status area above) and the stop affordance (on the send button) can never stack again. `InterruptAffordance` keeps its file, its own tests and its own doc — it simply has no production call site on `ThreadScreen` any more (`codegraph_callers`: none outside its own file, flagged by code review as dead code worth a follow-up to either retire the file or record what still keeps it alive). See [Interrupt affordance](interrupt-affordance.md#placement--wiring) for the send/stop precedence table and the composer wiring, and [Thread input bar](thread-input-bar.md#shape) for the button itself.

### Stall-promotion-banner placement (post-#396)

[#396](../codebase/396.md) mounts the stateless [`StallPromotionBanner`](stall-promotion-banner.md) directly **below** [`ConnectionBanner`](connection-banner.md) in the content `Column` (`ThreadScreen.kt:123`), above the workspace chip / empty state / message list — i.e. **outside** the scrolling `LazyColumn`:

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    StallPromotionBanner(isStalled = isStalled, onShowLiteralScreen = onShowLiteralScreen)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

- **Top, not foot — the counterpoint to `ThinkingIndicator`.** A stall promotion is a "do this now" recommendation, so it takes the most-prominent top slot directly analogous to `ConnectionBanner` (connection-degraded → top banner; parse-degraded/stalled → top banner); the at-work thinking indicator stays at the foot. Being **outside** the `LazyColumn`, it stays pinned while the stall holds rather than scrolling away.
- **Reuses the already-shipped snapshot action (AC #4).** The banner's `onClick` is the **same** `onShowLiteralScreen` (#382) the overflow "Show the literal screen" item triggers — no new `ThreadEvent`, no new navigation, no new data path.
- Driven solely by the hoisted `isStalled` flag (`if (!isStalled) return` inside the composable) — holds no local state, emits nothing when not stalled (AC #1/#2/#3). See [Stall promotion banner](stall-promotion-banner.md) for the composable's shape, a11y (`cd_thread_stall_promotion`), tertiary-container styling (design-owed), and previews.

### Permission-modal overlay placement (post-#446)

[#446](../codebase/446.md) renders the hoisted app-level [`currentModal`](current-modal-state.md) (#445) as the **seventh `Scaffold` sibling** — a `when (modalState)` block after the `DeleteConfirmationDialog` block (`ThreadScreen.kt:314`), **outside** the content `Column` (it is a floating dialog window, not part of the thread layout). `MainActivity` collects `currentModal` (and, since [#452](../codebase/452.md), `armedOptionId`) via `collectAsStateWithLifecycle` and forwards them as the defaulted `modalState` / `armedOptionId` params, exactly like `isThinking` / `isStalled`; `modalSendErrors` is forwarded by reference and collected inside `ThreadScreen` (single-consumer; the snackbar is a screen concern). Full doc: [Permission-modal overlay](permission-modal-overlay.md).

- **Separate surface, not a `LazyColumn` row.** `Open` → a private `PermissionModalOverlay` built on **`BasicAlertDialog`** (not the 2-button `AlertDialog` — the option count varies 4/2), rendering verbatim `title` / `prompt` / `options` (wire **array order**); `ModalOptionButton` is a stateless **3-way** ([#452](../codebase/452.md), `isArmed` precedence): the fail-safe-deny `defaultOptionId` → filled `Button`, the VM's armed non-default (`armedOptionId`) → `FilledTonalButton` (below the default's emphasis), else `OutlinedButton`, each with its `stateDescription` marker. `modalClass` is carried but not branched on. **Live since [#452](../codebase/452.md):** the option-tap forwards verbatim to `onModalOption` (the VM decides arm-vs-send, no UI-side arming), an explicit low-emphasis Cancel `TextButton` reaches `onModalCancel`, and `filterTouchesWhenObscured = true` on the dialog's own window adds a tapjacking net now that taps are live.
- **App-level, not per-conversation.** Modal events carry no `conversation_id`, so there is one `currentModal` across the app and the overlay shows over **whichever thread is active** — no `conversationId` filter (contrast the per-conversation thread items).
- **Dismiss = snackbar, not overlay.** `Dismissed` renders no overlay and fires a `LaunchedEffect(modalId)` snackbar surfacing a **mapped local** reason (`dismissReasonText`: remote/local/timeout + a generic forward-compat fallback). The Scaffold gains a `remember { SnackbarHostState() }` + `snackbarHost` (the only change to the existing Scaffold), mirroring the [`ArchivedDiscussionsScreen`](archived-discussions-screen.md) dismiss-reason precedent. Keying on `modalId` (a sticky terminal state in #445's fold) fires it exactly once per resolution.
- **Security (this slice owns the render-time obligations #445 deferred).** Plain `Text` only (never [`MarkdownText`](markdown-text.md)/`SelectionContainer`), and `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)` sets `FLAG_SECURE` on the **dialog's own window** — the [#381](../codebase/381.md) [`LiteralScreenSurface`](literal-screen-surface.md) precedent flags the **Activity** window, which a dialog draws outside of, so `SecureOn` (not the default `Inherit`) is load-bearing. No modal text reaches `rememberSaveable` / saved-instance state; the mapped-not-echoed dismiss reason is a confidentiality requirement (the snackbar draws in the un-secured Activity window). `dismissOnBackPress`/`dismissOnClickOutside = false` — a permission gate ignores stray taps; cancel is the explicit Cancel button only. [#452](../codebase/452.md) adds the remaining live-tap obligations: **send-error confidentiality** (the `modalSendErrors` event is `Flow<Unit>` + a fixed local `modal_send_failed` string ⇒ structurally no payload reaches the snackbar) and **tapjacking** (`filterTouchesWhenObscured = true` on the dialog's own window, the View-level analog of `SecureOn`, different fabric from the second-confirm UX belt).

### `fun retry()` — non-suspend, VM owns the launch

```kotlin
fun retry() {
    viewModelScope.launch { connectionStateSource.retry() }
}
```

The VM exposes `retry()` as a non-`suspend` method; the `viewModelScope.launch` body wraps the source's `suspend fun retry()`. UI callers bind `vm::retry` directly to `ConnectionBanner`'s `onRetry: () -> Unit` slot without `rememberCoroutineScope { ... }.launch { ... }`. Same shape as `fun sendMessage(text: String)` from #188 — the convention in this codebase is never to expose `suspend` on a VM. If the screen is destroyed mid-call the launch is cancelled, which is fine for the Phase-2 no-op body; in Phase 4 the real source's `suspend fun retry()` may do network I/O, and `viewModelScope` cancellation will propagate as expected.

No `try/catch` around `connectionStateSource.retry()`. Per the `ConnectionStateSource` interface KDoc (#196), failures surface as state transitions (`Offline`), not exceptions; the Phase-2 fake cannot throw. No `.catch { ... }` on the upstream `observe()` either — premature defense.

### `connectionState: StateFlow<ConnectionState>` — same lifetime as `state`

```kotlin
val connectionState: StateFlow<ConnectionState> =
    connectionStateSource.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionState.Connected)
```

`SharingStarted.WhileSubscribed(5_000)` matches the `state` flow's lifetime policy — both share the same `viewModelScope` and subscription window. If the screen resumes within 5s of leaving the back stack, the existing collector is reused (no `Connected` flash from re-subscription). `initialValue = ConnectionState.Connected` matches the fake's seeded value so the screen's first frame paints with the banner already short-circuited.

### `ThreadTopAppBar` — Figma `16:8` chrome

**[#643](../codebase/643.md) replaced the stock M3 `TopAppBar` with a hand-rolled bar plus a closing rule** — the Figma `16:8` `Top bar` frame (`533:1948`), the same design language [`ChannelListTopBar`](channel-list-screen.md) already shipped for the channel list. `ThreadTopAppBar(title, onBack, onTitleClick, onOverflowClick, overflowExpanded, onOverflowDismiss, onOverflowEvent, onShowLiteralScreen, isPromoted, mutationsSupported, modifier)` stays a **public** stateless composable in its own file (`ThreadTopAppBar.kt`); only its body changed. It is now a `Column` of a content `Row` and a `HorizontalDivider`:

- **Back** — a 48dp `IconButton(onClick = onBack)` around a 24dp `Icons.AutoMirrored.Filled.ArrowBack`, `R.string.cd_back` ("Back") reused from every other back-arrow in the app.
- **Title** — `Text(text = title, modifier = Modifier.weight(1f).clickable(onClick = onTitleClick).semantics { role = Role.Button }, style = titleLarge, color = onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)`, sitting between the two controls. `weight(1f)` precedes `.clickable(...)`, so the `Text` measures to the **full title slot**, not just its visible glyphs — the tap area and ripple cover the trailing space after a short title too (harmless: `Row` siblings never overlap, so it can't reach either control, and it is arguably a better target than the stock bar's text-sized one). `maxLines = 1` + `TextOverflow.Ellipsis` is what makes a display name longer than the slot truncate inside it rather than overlap or cover a control. `Role.Button` keeps TalkBack announcing the title as activatable.
- **Overflow** — kept its pre-#643 `Box { IconButton; ThreadOverflowMenu }` wrap unchanged; that `Box` is what anchors the menu directly beneath its own glyph rather than against the row ([#252](../codebase/252.md)), and losing it on the rewrite would have reopened that bug. 48dp touch target around a 24dp `Icons.Filled.MoreVert`, `R.string.cd_more_actions` ("More actions").
- **Rule** — a `HorizontalDivider` closing the bar, **inset 20dp on both sides** (not full-width — the plan's design-source prose said "full-width rule", but the Figma frame has the rule at the same `x=20 w=372` as the content row, and the shipped code and `ChannelListTopBar` both draw it inset; don't repeat "full-width" elsewhere). Colour is `colorScheme.outlineVariant` at 60% alpha — a **recorded divergence** from the design's literal `Schemes/inverse-primary` at 60%, which is a light-scheme primary tone that only reads as a tinted rule against the dark reference frame; `outlineVariant` is M3's divider role and is exactly what the shipped `ChannelListTopBar` maps this same rule to, so both bars stay identical under either scheme.
- **Touch-slack derivation.** Back and overflow keep 48dp `IconButton` touch targets around the design's 24dp glyphs; the bar's own paddings are the design's offsets **less the touch slack** `(48dp − 24dp) / 2 = 12dp`, the identical derivation `ChannelListTopBar` uses for the list's own bar, so both bars land their glyphs on the same 20dp gutter. The constants (`BarGlyphSize`, `BarTouchSize`, `BarGutter`, `BarTopGap`, `BarRuleGap`, `BarBottomGap`, `BAR_RULE_ALPHA`) are file-private here exactly as `ChannelListTopBar`'s are file-private there — deliberately not shared between the two files.

**The inset dispute is settled by current code, in `ChannelListTopBar`'s favour.** `MainActivity`'s root `Scaffold` declares neither a `topBar` nor a `bottomBar`, so the `innerPadding` it hands `PyryNavHost` (via `Modifier.padding(innerPadding)`) is its **whole** `contentWindowInsets` — every destination, including the thread screen, is already padded past the status and navigation bars before `ThreadScreen`'s own per-screen `Scaffold` ever runs. The stock `TopAppBar` this bar replaced was applying a **second** status-bar inset on top of that via `TopAppBarDefaults.windowInsets`; the hand-rolled bar declares no window insets of its own and needs none. The claim that used to stand here — that the outer `Scaffold` "doesn't consume the top inset" — was stale; `ChannelListTopBar`'s own KDoc, written later against the shipped list screen, is the accurate note.

The composable is **stateless** per the project convention — no `remember`, no `MutableState`. All callbacks are caller-owned.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied.

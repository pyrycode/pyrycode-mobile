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

`connectionState: ConnectionState` is a flat parameter rather than a field on `ThreadUiState` — see [`#201`'s ticket notes](../codebase/201.md) for the full rationale. Short version: the existing `state` derivation stays untouched (no `combine(...)` ceremony, no churn to the seven existing tests that pattern-match `ThreadUiState`); connection state is global (every screen would consume the same source under future work) while conversation state is per-screen; reflecting that orthogonality at the type level is cleaner than artificial fusion. The destination block consumes two `collectAsStateWithLifecycle()` calls — the same shape `SettingsViewModel` already uses for its four separate flows. [`#406`](../codebase/406.md) added a **second** such sibling signal to the VM on this same rationale: `val isThinking: StateFlow<Boolean>` (`ThreadViewModel.kt:216`), the live `turn_state` thinking-phase flag reduced from the [coordinator](relay-repository-coordinator.md)'s `liveSessionEvents` seam — a transient, connection-scoped cross-cutting signal kept off `ThreadUiState` so the `state` combine stays zero-touch. [`#407`](../codebase/407.md) then threaded it into `ThreadScreen` as a **third** flat sibling parameter — `isThinking: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:74`), collected at `MainActivity` via `vm.isThinking.collectAsStateWithLifecycle()` exactly parallel to `connectionState` — and rendered the at-work [`ThinkingIndicator`](thinking-indicator.md) at the foot of the content `Column` (see [Thinking-indicator placement (post-#407)](#thinking-indicator-placement-post-407) below). See [Turn-state thinking flag](turn-state-thinking-flag.md) for the data path. [`#396`](../codebase/396.md) added a **fourth** sibling signal on the *same* rationale: `val isStalled: StateFlow<Boolean>` (`ThreadViewModel.kt:225`, beside `isThinking`), sourced straight off the already-injected `repository.observeStall(conversationId)` (#395) — **no constructor/DI/interface change**, unlike `isThinking` which needed the new `liveSessionEvents` ctor param — and threaded into `ThreadScreen` as a **fourth** flat sibling parameter `isStalled: Boolean = false` (defaulted, after `modifier`, `ThreadScreen.kt:75`), collected at `MainActivity` via `vm.isStalled.collectAsStateWithLifecycle()`. It deviates from the ticket's "hoist into `UiState`" Technical Note deliberately: stall is the same signal class as `isThinking`, so siblinghood is consistent and the 5-arity `state` combine stays untouched. See [Stall state](stall-state.md) for the data path and [Stall-promotion-banner placement (post-#396)](#stall-promotion-banner-placement-post-396) below for the render. [#459](../codebase/459.md) added a **fifth** sibling on the *same* rationale: `val isBusy: StateFlow<Boolean>` (`ThreadViewModel.kt:296`, beside `isThinking`) — declared **identically** to `isThinking` over the same `liveSessionEvents` seam but **broadened** to `thinking` **or** `responding` via a dedicated `busyTransition` reducer (a "a turn is running" signal, not the `thinking`-only one; see [Interrupt affordance](interrupt-affordance.md)) — and threaded into `ThreadScreen` as two defaulted siblings, `isBusy: Boolean = false` plus the flat `onInterrupt: () -> Unit = {}` callback (`:102`), collected at `MainActivity` via `vm.isBusy.collectAsStateWithLifecycle()` with `onInterrupt = vm::onInterrupt`.

`onRetry` binds to `vm::retry` at the destination — method reference, not a fresh lambda, so the binding is stable across recompositions (the lambda allocation only happens once per VM lifecycle, not per recomposition).

### Thinking-indicator placement (post-#407)

[#407](../codebase/407.md) mounts the stateless [`ThinkingIndicator`](thinking-indicator.md) as the **final child of the content `Column`**, *after* the `if (!state.hasMessages) EmptyThreadState(...) else { LazyColumn(...) }` block closes and *outside* it (`ThreadScreen.kt:221`):

```kotlin
Column {
    ConnectionBanner(state = connectionState, onRetry = onRetry)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
}
```

- **Outside the branch, not gated on `hasMessages`.** Both arms (`EmptyThreadState` / `LazyColumn`) take `weight(1f)`; the indicator is wrap-height and sits directly below that weighted region, above the `bottomBar` (status row + input). One call site therefore surfaces it identically in the **empty-thread** case (thinking precedes the first assistant text, AC #2) and the populated case — no duplication, no `hasMessages` condition.
- **Foot = bottom / most-recent edge.** `reverseLayout = true` pins list index 0 (newest message) to the bottom of the list region, so the indicator lands directly beneath the latest message and above the composer — the visual "foot" the AC names.
- **Zero-touch to the list.** The `LazyColumn`, its `weight`/`reverseLayout`, and the streaming auto-scroll effects are unchanged; the indicator is a sibling row, not a list item, so it never participates in `itemsIndexed`/keying or the auto-pin loop.
- Driven solely by the hoisted `isThinking` flag (`if (!isThinking) return` inside the composable) — it holds no local state and emits nothing when not thinking (AC #1/#3/#4). See [Thinking indicator](thinking-indicator.md) for the composable's shape, a11y (`cd_thread_thinking`), and previews.

### Interrupt-affordance placement (post-#459)

[#459](../codebase/459.md) mounts the stateless [`InterruptAffordance`](interrupt-affordance.md) (the "Stop the running turn" control) in the same foot-of-list `Column`, **immediately below** [`ThinkingIndicator`](thinking-indicator.md) (`ThreadScreen.kt:279`):

```kotlin
Column {
    ConnectionBanner(...)
    StallPromotionBanner(...)
    // optional WorkspaceChip …
    if (!state.hasMessages) EmptyThreadState(...) else LazyColumn(reverseLayout = true, ...) { … }
    QueuedBacklog(...)
    ThinkingIndicator(isThinking = isThinking, modifier = Modifier.fillMaxWidth())
    InterruptAffordance(isBusy = isBusy, onInterrupt = onInterrupt, modifier = Modifier.fillMaxWidth())
}
```

- **Foot, like `ThinkingIndicator`** — a transient signal-driven affordance, wrap-height, below the weighted message region (`reverseLayout = true` → most-recent edge), not a `LazyColumn` item (list keying / auto-scroll untouched).
- **Gated on the broader `isBusy` flag, not `isThinking`.** `isBusy` is `true` across `thinking` **and** `responding`, so the control stays visible for the whole in-flight turn (the interrupt must work while claude is emitting text, not just while it's pre-text thinking). During `thinking` both flags are `true`, so the spinner and the "Stop" button **stack** — intentional/interim, reconciled by the design-owed Figma 16-8 pass (code-review NIT). Driven solely by the hoisted flag (`if (!isBusy) return` inside the composable); tapping calls `onInterrupt` = `vm::onInterrupt` (the #458 send path). See [Interrupt affordance](interrupt-affordance.md).

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

`ThreadTopAppBar(title, onBack, onTitleClick, onOverflowClick, overflowExpanded, onOverflowDismiss, onOverflowEvent, modifier)` is a **public** stateless composable in its own file (`ThreadTopAppBar.kt`). Three slots:

- `navigationIcon` — `IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, R.string.cd_back) }`. `R.string.cd_back` reused — same string that backs every other back-arrow in the app.
- `title` — `Text(text = title, modifier = Modifier.clickable(onClick = onTitleClick).semantics { role = Role.Button })`. The `clickable` modifier rides on `Text` (not on a wrapping `Row`) so the ripple aligns with the visible text bounds rather than the full title-slot column. `Role.Button` keeps TalkBack announcing the title as activatable. The default `TopAppBar` title slot already renders `titleLarge` against `colorScheme.onSurface`, matching Figma `16:14` — do **not** override `style` or `color`.
- `actions` — `Box { IconButton(onClick = onOverflowClick) { Icon(Icons.Filled.MoreVert, R.string.cd_more_actions) }; ThreadOverflowMenu(expanded = overflowExpanded, onDismiss = onOverflowDismiss, onEvent = onOverflowEvent) }`. `MoreVert` lives at `androidx.compose.material.icons.filled.MoreVert` (non-automirrored — the icon is symmetric, no RTL flip). String `cd_more_actions = "More actions"` introduced in #139; named generically so non-thread overflows can reuse it. The `Box` wrap landed in [#252](../codebase/252.md) — M3's `DropdownMenu` anchors to its parent layout, so wrapping the icon + menu in a single `Box` makes the menu open directly below the icon (the canonical M3 single-icon-overflow pattern); placing the menu as a sibling of the icon directly inside the implicit `actions` `Row` would anchor against the row's bounds, mis-positioning a single-icon overflow.

**`TopAppBar` defaults are correct for this ticket.** Figma `16:8` uses `Schemes/surface` for the bar background, which matches `TopAppBarDefaults.topAppBarColors().containerColor` (= `colorScheme.surface`). Do **not** add `colors = TopAppBarDefaults.topAppBarColors(containerColor = ...)`. No `scrollBehavior` (Figma does not specify collapse-on-scroll; explicitly out of scope per the ticket body). No `windowInsets` override (the outer `MainActivity` `Scaffold` doesn't consume the top inset, so the default already handles status-bar inset correctly).

The composable is **stateless** per the project convention — no `remember`, no `MutableState`, no `rememberSnackbarHostState`. All four callbacks are caller-owned.

### Modifier ordering inside the body

`Modifier.padding(inner).fillMaxSize()` — same shape as `DiscussionListScreen.kt:101`. Do **not** invert to `.fillMaxSize().padding(inner)` (that would draw under the AppBar shadow before applying the inset). No `Modifier.systemBarsPadding()` — the outer `Scaffold` in `MainActivity` already passes `innerPadding` into `PyryNavHost`, and the per-screen `Scaffold` adds its own `inner` for the AppBar; both are applied.

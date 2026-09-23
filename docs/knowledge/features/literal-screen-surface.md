# LiteralScreenSurface

Stateless Compose surface for the manual “Show the literal screen” action. It
renders [LiteralScreenViewModel](literal-screen-viewmodel.md)'s three-state
`LiteralScreenUiState` and dispatches `LiteralScreenEvent`s. The thread overflow
opens `literal_screen/{serverId}/{conversationId}` with the same owning host and
conversation; see [navigation](navigation.md#host-qualified-destinations).

`security-sensitive`: the snapshot text is server-originated and may carry sensitive on-screen content,
so it is rendered **verbatim** as plain monospace, **never logged**, **never persisted**, and screen
capture is **blocked** while it is shown — see [Confidentiality at the render edge](#confidentiality-at-the-render-edge).

## What it does

The screen snapshot is the **always-available, parser-independent floor** of pyrycode ADR 025's
safe-degradation strategy (pyrycode#596): a one-shot text picture of the current claude screen, rendered
server-side, depending on no screen parser. When structured rendering can't keep up, the user falls back
to the verbatim rendered screen. The data path shipped in [#375](../codebase/375.md), the state machine in
[#378](../codebase/378.md); this surface is the face the user actually sees.

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt`. One public
composable + three private content composables + a private `FLAG_SECURE` helper (mirrors
[`ScannerScreen`](scanner-screen.md)'s shape). The ktlint single-public-class filename rule does not fire
— the only public top-level type is a function (see [[ktlint-filename-rule-single-class]]).

## Shape

```kotlin
@Composable
fun LiteralScreenSurface(
    state: LiteralScreenUiState,            // hoisted from the VM (Loading / Content / Error)
    onEvent: (LiteralScreenEvent) -> Unit,  // Request (open) / Retry (refresh + retry)
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Stateless — holds **no** state of its own beyond the `rememberScrollState()` for the scroll container
(UI-only scroll position, non-sensitive, intentionally *not* saveable). The stateful wrapper
(`koinViewModel()` + `collectAsStateWithLifecycle()`) lives in the `literal_screen/{serverId}/{conversationId}`
destination, not here.

A shared `Surface` → `Column` → `TopAppBar` scaffold wraps all three states, so `FLAG_SECURE` and the
once-only `Request` apply for the whole surface lifecycle, not just `Content`:

- **`TopAppBar`** — title (`literal_screen_title`, "Claude's screen") + a back `IconButton`. In the
  **`Content`** state only, a **refresh** `IconButton` (`Icons.Filled.Refresh`) fires
  `LiteralScreenEvent.Retry` (re-fetch in place).
- **`Loading`** → centered `CircularProgressIndicator` with a `contentDescription`.
- **`Content`** → the verbatim snapshot body (see below).
- **`Error`** → a centered, per-`reason` message + a `Retry` `Button`.

### Initial fetch — `Request` once on open

```kotlin
LaunchedEffect(Unit) { onEvent(LiteralScreenEvent.Request) }
```

`key = Unit` fires `Request` **exactly once per surface entry** — a recomposition does not re-fire; a
fresh navigation does (which is correct: re-fetch on next open). The VM is event-driven and does **not**
auto-fetch in `init`, so this is the only `Request` trigger. (Consequence for tests: every recording
`onEvent` sees `Request` first, so a retry/refresh assertion expects `[Request, Retry]`.)

### Verbatim render — the load-bearing rule

```kotlin
Column(Modifier.fillMaxSize().verticalScroll(...).horizontalScroll(...).padding(16.dp)) {
    Text(text = text, fontFamily = FontFamily.Monospace, softWrap = false, …)
}
```

- Rendered byte-for-byte: **never** through [`MarkdownText`](markdown-text.md), **never** pre-processed
  (no `trim`/`replace`/regex/control-code stripping).
- `softWrap = false` preserves the terminal grid (a wrapped line would misrepresent the screen layout);
  the container scrolls on **both** axes instead, so long/tall output stays reachable without altering the
  bytes.
- **No `SelectionContainer`.** Text selection enables one-tap copy to the system clipboard (readable by
  other apps, persists in clipboard history) — a confidentiality leak straight past `FLAG_SECURE`. The
  user reads the screen; they do not copy it out. (This deliberately diverges from `PairingConfirmContent`,
  which *does* use `SelectionContainer` — but the fingerprint there is public; the snapshot is sensitive.)

The bypass is **test-pinned**: feeding `"**bold** \`code\`\n  indented"` and asserting the raw markers
appear literally proves `MarkdownText` was not used (it would have stripped/rendered them) and that no
trim/reflow happened.

### Per-reason error copy

`ErrorContent` resolves a `when (reason)` over the closed [`LiteralScreenError`](literal-screen-viewmodel.md)
enum to a **string resource** — **no server-supplied string is ever shown** (the enum carries no copy by
design):

| Reason | String | Note |
|---|---|---|
| `UnknownConversation` | `literal_screen_error_unknown_conversation` | "This conversation couldn't be found." |
| `ServerError` | `literal_screen_error_server` | "Couldn't load the screen. Try again." |
| `NotConnected` | `literal_screen_error_not_connected` | "Not connected. Reconnect to load the screen." |

Every reason is retryable — "retryable" is expressed by the `Retry` button *existing*, not a per-reason
flag. `NotConnected` is a **normal path** (the snapshot action is always offered, including while
disconnected), so its copy is a calm "reconnect and retry," not an alarm.

## Confidentiality at the render edge

The sensitive text enters this surface already on the trusted side (as `Content.text`; the network→process
boundary was crossed and reviewed in [#375](../codebase/375.md)/[#378](../codebase/378.md)). The concern
here is **confidentiality, not integrity** — the text is displayed, never parsed/interpreted/used to build
a path or command, so there is no injection surface, only leak surfaces. Four are closed here:

- **Screenshot / screen-recording / cast** → `FLAG_SECURE` (below).
- **Clipboard exfiltration** → no `SelectionContainer`.
- **Instance-state persistence across process death** → no `rememberSaveable` / `SavedStateHandle` of the
  text (those serialize to the disk-backed instance-state bundle); the text is read from `state.text` each
  composition and re-fetched on next open. Only the scroll position is `remember`ed (non-saveable).
- **Log / crash-dump capture** → no `Log.*`/`println` in the file; the VM's `Content.toString()` is
  already redacted and the surface never undoes it via `"$state"`, and never builds a `contentDescription`
  from the snapshot text.

This is the render-edge complement to #378's deterministic redaction — belt-and-suspenders, different
fabric, spanning the VM + the surface.

### FLAG_SECURE — the repo's first

There was **no `FLAG_SECURE` precedent** in the repo; this surface introduces the reusable pattern:

```kotlin
@Composable
private fun SecureScreen() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window      // null under createComposeRule → no-op
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
```

- Called once at the top of the surface (outside the `when`), so the flag is held for **every** state and
  **cleared on dispose** — navigating away re-enables capture elsewhere (it does not silently harden
  unrelated screens; the app uses `FLAG_SECURE` nowhere else).
- **Null-safe by design.** The walk returns `null` under `createComposeRule` (a non-`Activity` host), so
  the effect no-ops — no crash, no `Activity` dependency in tests. This is *why* the walk is used instead
  of `LocalActivity.current` (can be absent) or an unchecked `context as Activity` cast. **Copy this
  helper for the next sensitive surface.**

## Wiring

`appModule` registers `viewModel { get<ThreadDestinationFactory>().literal(get()) }`.
The factory reads the entry's `serverId` from `SavedStateHandle` and supplies an
[exact-host reconnecting repository](dependency-injection-host-conversation-source.md#destination-ownership),
or the existing fake singleton for a demo destination. The ViewModel reads the
unchanged host-local `conversationId`; Request and Retry retain the same target across
compatibility selection changes and reconnect.

The `literal_screen/{serverId}/{conversationId}` destination resolves
`koinViewModel<LiteralScreenViewModel>()` inside `HostDestination`, after host
membership is checked. Each new entry owns a fresh ViewModel, including A and B
with equal conversation ids. Back-stack restoration retains both route identifiers;
snapshot text stays out of saved state and is re-fetched on surface entry. Never
hoist the resolution above the destination or register the ViewModel as a `single`.
See [navigation](navigation.md#host-qualified-destinations) for route encoding,
thread-to-literal traversal and unknown/removed-host handling.

## Edge cases / limitations

- **Visual design owed.** Padding, typography scale, empty-state treatment, and horizontal-scroll vs. a
  "wrap" toggle are unsettled until a Figma frame exists (none in the locked file
  `g2HIq2UyPhslEoHRokQmHG` — the Phases 0–2 design predates it). The behavioral floor (verbatim / monospace
  / scrollable / per-reason error / `FLAG_SECURE`) is locked; visual polish + a `@Preview` retrofit land
  with the frame (#343 design-later precedent). Provisional copy + the top-bar refresh placement may move.
- **No `@Preview`.** Repo convention is every screen-level composable carries one ([`ScannerScreen`](scanner-screen.md)
  has light+dark); this surface omits it because design is owed (a non-blocking code-review SHOULD-FIX).
  Owed alongside the visual retrofit.
- **Permanent `Loading` if the transport never returns** — the VM imposes no call deadline (bounded only
  by `viewModelScope` cancellation on screen exit). Unchanged from [#378](../codebase/378.md); a transport
  deadline is a SHOULD-FIX flagged upstream, not gated here (availability, not confidentiality).
- **Accessibility-service / overlay eavesdropping** of on-screen text is a platform-wide residual — no
  per-view defense exists beyond `FLAG_SECURE` (which does not cover a11y reads). Out of scope, no ticket
  (applies app-wide).

## Testing

Component render tests use `createComposeRule` (not `createAndroidComposeRule` — same idiom as
[`ThreadOverflowMenuTest`](thread-overflow-menu.md)): render `LiteralScreenSurface` directly with canned
`state` + recording lambdas; no VM, no Koin. The 7 tests cover loading→spinner, verbatim/markdown-bypass,
each of the three error reasons + retry-fires-`Retry`, `Request`-fired-once-on-open, and
refresh-re-fetches-in-place. `FLAG_SECURE` no-crash is proven implicitly — all tests render under the
non-`Activity` host without crashing. **Monospace is not asserted** (`FontFamily.Monospace` isn't queryable
via the test API); the verbatim assertion + code review cover it. CI gate: `./gradlew test` / `lint` /
`spotlessCheck` (instrumented tests run on-device per repo convention).

`LiteralScreenNavigationTest` separately mounts the production graph and Koin
bindings to cover host-qualified traversal, destination isolation and saved-state
restoration; see [navigation testing](navigation.md#testing).

## Related

- [#381 implementation notes](../codebase/381.md) · spec `docs/specs/architecture/381-literal-screen-render-surface-di.md`
- [LiteralScreenViewModel](literal-screen-viewmodel.md) ([#378](../codebase/378.md)) — the hoisted state this surface renders
- [Conversation repository](conversation-repository.md) — `requestScreenSnapshot`, the consumed read ([#375](../codebase/375.md))
- [Scanner screen](scanner-screen.md) — the `(state, …)` surface + verbatim-render pattern mirrored; [Thread overflow menu](thread-overflow-menu.md) — the compose-test idiom mirrored
- [MarkdownText](markdown-text.md) ([ADR 0002](../decisions/0002-markdown-renderer-library.md)) — the renderer this surface deliberately bypasses
- [#382](../codebase/382.md) — original thread entry point and destination scoping; [#636 design](../../specs/architecture/636-host-owned-thread-routes.md) adds host-qualified ownership.
- **[#396](../codebase/396.md)** (shipped) — the [stall promotion banner](stall-promotion-banner.md): a **second** entry point into the same `literal_screen/{serverId}/{conversationId}` destination, reusing the thread's host-qualified `onShowLiteralScreen` callback. It promotes this action prominently while the conversation is stalled ([stall state](stall-state.md), #395)
- pyrycode ADR 025 § Safe degradation / Security model · pyrycode#596 (Phase 2 structured streaming) · pyrycode#618 (daemon snapshot handler)

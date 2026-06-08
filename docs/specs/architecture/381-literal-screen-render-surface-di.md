# #381 — Literal-screen render surface + ViewModel DI registration

**Size:** S · **Security-sensitive:** yes · **Figma:** N/A (design owed — see Design source)
**Split from #379** (itself split from #372 → #378/#379). Sibling **#382** (`blockedBy #381`) adds the thread entry-point action + the navigation destination that obtains this VM and renders this surface. This slice ships the **stateless render surface + DI registration**; it stands alone (tested + DI-wired before it is reachable, same as #378 merged the VM with zero UI reachability).

## Design source

N/A — the literal-screen view is a new Phase-2-streaming fallback surface with no frame in the locked Figma file `g2HIq2UyPhslEoHRokQmHG` (the Phases 0–2 design predates it). **Visual design owed** — a frame (monospace body, scroll, refresh, loading/error states) lands later; the ACs here are behavioral and design-independent, so visual polish retrofits when the frame exists. Refine-time design-later call per the #343 precedent. Provisional copy + layout choices below are explicitly marked as the floor, not locked design.

## Context

The "show the literal screen" feature gives the user a parser-independent view of the current claude screen — the always-available floor of the Phase-2 degrade strategy (pyrycode#596, ADR 025 § Safe degradation; the `request_snapshot` / `screen_snapshot` wire types were added there). When structured rendering can't keep up, the user can fall back to the verbatim rendered screen.

The state-management half already merged: `LiteralScreenViewModel` (#378, on `main`) exposes a hoisted sealed `LiteralScreenUiState` (`Loading` / `Content(text)` / `Error(reason)`) + `onEvent(LiteralScreenEvent)` (`Request` / `Retry`), with `LiteralScreenError ∈ {UnknownConversation, ServerError, NotConnected}`. The VM is **event-driven, not auto-loading** (it does not fetch in `init`), and was intentionally left **unregistered for DI** with **no UI surface**.

This slice supplies both halves the VM was left without:

1. a **stateless Compose surface** that renders the three UI states and dispatches the two events, and
2. the **Koin registration** that makes the VM resolvable so #382's navigation destination can obtain it.

The snapshot text is **server-originated and may carry sensitive on-screen content** (it is held verbatim by contract). Rendering it pulls three security obligations into this slice — verbatim plain-monospace render (never through `MarkdownText`), never logged / never `rememberSaveable`, and screen-capture blocked (`FLAG_SECURE`) — which is why it is `security-sensitive`. (#378's review propagated these here; per-conversation VM scoping is #382's, see Open questions.)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenViewModel.kt:20-101` — the merged VM this surface consumes. **Extract:** the exact `when (state)` arms (`Loading` / `Content(text)` / `Error(reason)`), the `LiteralScreenError` enum values, the `LiteralScreenEvent` cases, and the redacting `Content.toString()` (line 30) — the surface must not undo it (no `Text("$state")`).
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt:58-100, 363-443` — **the surface pattern to mirror.** A stateless `(state, …) ` composable with `when (state) ->` private content composables; each content composable is a `Surface(color = surface) { Column().fillMaxSize().systemBarsPadding() … }`. Line 408-419 shows `FontFamily.Monospace` text rendered **verbatim** with a `semantics { contentDescription }` (the #343 fingerprint — same "render server string byte-for-byte, never reformat" stance). Lines 363-368 are the design-later/no-Figma precedent comment to echo.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` + `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt` — **the `stringResource` idiom and the exact compose-test idiom to copy:** `createComposeRule()`, `setContent { PyrycodeMobileTheme { … } }`, `onNodeWithText(string(resId))`, `performClick()`, `assertIsDisplayed()`, recording lambdas (`onEvent = { log.add(...) }`), and the `string(resId)` helper (`InstrumentationRegistry…targetContext.getString`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt:69-84` — **the renderer to NOT use.** It parses CommonMark (bold/code/links). `MessageBubble.kt:110,150` routes *assistant* text through it; the snapshot must bypass it entirely.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:92-98` — the `viewModel { … }` registration block. Add one line mirroring `viewModel { ThreadViewModel(get(), get(), get(), get()) }` (line 97).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:95-103` — constructor `(savedStateHandle: SavedStateHandle, repository, …)` + `savedStateHandle.get<String>("conversationId")`. Confirms Koin's `viewModel { }` `get()` resolves `SavedStateHandle`; `LiteralScreenViewModel(savedStateHandle, repository)` has the identical leading shape.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:121-137` — `requestScreenSnapshot` contract (verbatim text; throws `IllegalArgumentException` / `RelayErrorException` / `IllegalStateException`). Context only — the VM already maps these to `LiteralScreenError`; the surface only renders per-reason copy.
- `app/src/main/res/values/strings.xml:54-70` — naming convention (`thread_overflow_*`) and the `</resources>` insertion point for the new strings.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:33, 72` — confirms `androidx.lifecycle.compose.collectAsStateWithLifecycle` + `org.koin.androidx.compose.koinViewModel` are already on the classpath (the stateful route wrapper that uses them is **#382's**, not this slice's — FYI only).
- `docs/specs/architecture/378-literal-screen-snapshot-viewmodel.md` — sibling VM spec; its "Open questions" + "Security review" enumerate the obligations propagated here.
- Memory `ktlint-filename-rule-single-class` — a file whose only public top-level type is a *function* triggers no filename rule; private helpers (`findActivity`, `SecureFlag`) co-located in `LiteralScreenSurface.kt` are fine.

## Design

**One new production file + one one-line edit + new string resources + one new test file.** No change to the VM, the repository, or navigation.

Production source (`.kt`, non-test):
- **New:** `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurface.kt`
- **Modified:** `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` (one line)

Resources / tests:
- **Modified:** `app/src/main/res/values/strings.xml` (new strings)
- **New:** `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenSurfaceTest.kt`

### File: `LiteralScreenSurface.kt`

Package `de.pyryco.mobile.ui.conversations.thread`. One public composable + private content composables + a private `FLAG_SECURE` helper. Mirrors `ScannerScreen.kt`'s shape.

**Public contract:**

```kotlin
@Composable
fun LiteralScreenSurface(
    state: LiteralScreenUiState,
    onEvent: (LiteralScreenEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Stateless — holds no `remember`ed state of its own beyond the `rememberScrollState()` for the scroll container (UI-only scroll position, non-sensitive). The three UI states map to private content composables via `when (state)`:

- **`Loading`** → a centered `CircularProgressIndicator` with a `contentDescription` (loading copy). One `Surface`/`Column` shell like `ScannerErrorContent`.
- **`Content`** → the verbatim snapshot body (see "Verbatim render" below).
- **`Error`** → a centered message resolved **per `reason`** + a `Button` firing `onEvent(LiteralScreenEvent.Retry)`. Reuse the `ScannerErrorContent` layout (message `Text` + action `Button`).

A `TopAppBar` (like `ScannerViewport`) carries the title + a back `IconButton(onClick = onBack)`. In the `Content` state the top bar also shows a **refresh** `IconButton` firing `onEvent(LiteralScreenEvent.Retry)` (re-fetch in place; AC#2). All states share this scaffold so `FLAG_SECURE` and the once-only `Request` apply for the whole surface lifecycle, not just `Content`.

**Initial fetch (AC#1, AC#2):** at the top of the surface,

```kotlin
LaunchedEffect(Unit) { onEvent(LiteralScreenEvent.Request) }
```

`key = Unit` fires it exactly once per surface entry (a recomposition does not re-fire; a fresh navigation does, which is correct — re-fetch on next open). The VM does not auto-fetch in `init`, so this is the only `Request` trigger.

**Verbatim render (AC#1) — the load-bearing rule:**

- Render `Content.text` with `Text(text = state.text, fontFamily = FontFamily.Monospace, softWrap = false)`. **Never** call `MarkdownText` and never pre-process the string (no `trim`, `replace`, regex, control-code stripping). `softWrap = false` preserves the terminal grid (a wrapped line would misrepresent the screen layout); the container scrolls instead of wrapping.
- Wrap the `Text` in a `Column` with **both** `verticalScroll(rememberScrollState())` and `horizontalScroll(rememberScrollState())` so long lines and tall output are reachable without altering the bytes. (Provisional layout — the owed design frame may refine padding/typography; the verbatim + monospace + scrollable invariants are the floor.)
- **Do NOT wrap the snapshot body in `SelectionContainer`.** Text selection enables one-tap copy to the system clipboard, which is readable by other apps + persists in clipboard history — a confidentiality leak straight past the `FLAG_SECURE` protection this slice adds. The user reads the screen; they do not copy it out. (This diverges from `PairingConfirmContent`, which *does* use `SelectionContainer` — but the fingerprint there is public; the snapshot here is sensitive. See Security review › Trust boundaries.)

**Screen-capture block (AC#4) — `FLAG_SECURE`:**

There is **no existing `FLAG_SECURE` precedent in the repo** (grep confirmed zero usages); this slice introduces the pattern. A private, null-safe helper applies and clears the flag with the surface lifecycle:

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

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
```

- Called once at the top of `LiteralScreenSurface` (outside the `when`) so the flag is held for every state while the surface is composed, and **cleared on dispose** (navigating away re-enables capture elsewhere). The app uses `FLAG_SECURE` nowhere else, so add-on-enter / clear-on-exit is safe (no other screen relies on the window flag).
- **No-op safe under test (AC#4):** `createComposeRule` hosts the content in a non-`Activity` context, so `findActivity()` returns `null` and the effect does nothing — no crash, no `Activity` dependency in the test. This is *why* the null-safe `findActivity` walk is used instead of `LocalActivity.current` (which can be absent) or an unchecked `context as Activity` cast.

**No logging (AC#3):** no `Log.*` / `println` anywhere in the file. The snapshot text reaches only the `Text` node; it is never interpolated into a log, a `contentDescription`, or a crash string. (The VM's `Content.toString()` is already redacted; the surface must not reintroduce a leak via `"$state"`.)

**No `rememberSaveable` of the text (AC#3):** the snapshot is read from `state.text` on each composition and never stored in `rememberSaveable` / `SavedStateHandle` (those serialize to the disk-backed instance-state bundle, persisting sensitive content across process death). Only the non-sensitive scroll position lives in `rememberScrollState()` (a plain `remember`, not saveable). Re-fetch on next open replaces restore.

### File: `AppModule.kt` (one line)

After line 97 (`viewModel { ThreadViewModel(get(), get(), get(), get()) }`), add:

```kotlin
viewModel { LiteralScreenViewModel(get(), get()) }
```

`get()` resolves `SavedStateHandle` (Koin's `viewModel { }` scope provides it, exactly as `ThreadViewModel`) and `ConversationRepository` (flag-selected via `conversationRepositoryModule`, #350). This makes the VM **resolvable**; **obtaining** it per-conversation within the nav back-stack-entry scope (where `SavedStateHandle` carries `conversationId`) is #382's job. This slice does not touch navigation or `MainActivity`.

### Strings (`strings.xml`)

Add before `</resources>` (provisional copy — design-owed; developer may adjust wording, keep the keys):

| Key | Purpose | Suggested value |
|---|---|---|
| `literal_screen_title` | Top-bar title | `Claude's screen` |
| `literal_screen_back` | Back-button `contentDescription` | `Back` |
| `literal_screen_refresh` | Refresh-button `contentDescription` | `Refresh` |
| `literal_screen_loading` | Spinner `contentDescription` | `Loading the screen` |
| `literal_screen_retry` | Retry button label | `Try again` |
| `literal_screen_error_unknown_conversation` | `Error(UnknownConversation)` | `This conversation couldn't be found.` |
| `literal_screen_error_server` | `Error(ServerError)` | `Couldn't load the screen. Try again.` |
| `literal_screen_error_not_connected` | `Error(NotConnected)` | `Not connected. Reconnect to load the screen.` |

`NotConnected` is a **normal path** (the snapshot action is always offered, including while disconnected) — its copy is a calm "reconnect and retry," not an alarming error. Each error reason maps to its own string via a `when (reason)` in the `Error` content composable; no server-supplied string is ever shown (the enum carries no copy by design).

### State + concurrency model

- The surface is **stateless** — it renders the VM's hoisted `state` and forwards events. No `MutableState`/`StateFlow` lives here. The stateful wrapper (`koinViewModel()` + `collectAsStateWithLifecycle()`) is #382's.
- `rememberScrollState()` holds only UI scroll position (non-sensitive, intentionally not saveable).
- `FLAG_SECURE` is bound to composition lifecycle via `DisposableEffect(Unit)` — applied on enter, cleared on dispose. No coroutines launched in this slice.
- `LaunchedEffect(Unit)` fires `Request` once; latest-request-wins cancellation lives in the VM (`loadJob`), not here.

### Error handling

The surface does not catch or map errors — the VM already maps the three repository exception types to `LiteralScreenError` (#378). The surface's only error responsibility is rendering: `when (reason)` → the matching string + a `Retry` button. Every reason is retryable (there is no non-retryable error state), so the `Retry` affordance is unconditional on the `Error` state. A transport that never returns leaves the VM in `Loading` (availability, bounded by `viewModelScope` cancellation on screen exit) — unchanged from #378, out of scope here.

## Testing strategy

Instrumented Compose tests only — `./gradlew connectedAndroidTest` locally; CI runs `./gradlew test` + `lint` + `spotlessCheck` (the gate in AC#5). `createComposeRule` (not `createAndroidComposeRule`), same idiom as `ThreadOverflowMenuTest`: render `LiteralScreenSurface` directly with canned `state` + recording lambdas; no VM, no Koin. The `string(resId)` helper + `PyrycodeMobileTheme` wrapper as in `ThreadOverflowMenuTest`.

Scenarios (inputs → expected; developer writes bodies in the project idiom):

- **Loading → progress indicator:** `state = Loading` → the loading `contentDescription` node `assertIsDisplayed()` (e.g. `onNodeWithContentDescription(string(R.string.literal_screen_loading))`).
- **Content renders verbatim, bypassing `MarkdownText` (the AC#1 proof):** `state = Content("**bold** `code`\n  indented")` with a recording `onEvent` → assert the **raw** string `**bold** `code`` is present as text (`onNodeWithText(..., substring = true)`). If `MarkdownText` had been used, the markers would be stripped/rendered and the literal node would be absent — so its presence proves the bypass. Include leading whitespace + an internal newline in the fixture to pin "no trim/reflow."
- **Content monospace:** assert the snapshot text node exists (font-family is not directly queryable via the test API; the verbatim assertion above + code review of `FontFamily.Monospace` covers it — note this in the test comment rather than asserting the typeface).
- **Error copy per reason + retry fires `Retry`:** for each of `UnknownConversation` / `ServerError` / `NotConnected`, `state = Error(reason)` → the matching string `assertIsDisplayed()`, then `onNodeWithText(string(R.string.literal_screen_retry)).performClick()` → recorded events contain `Retry`. (Parameterize or three short tests.)
- **`Request` fired exactly once on open (AC#1/#2):** render any state with a recording `onEvent`; after first composition the recorded list is exactly `[Request]` (the `LaunchedEffect(Unit)` fired once, nothing else). A recomposition (e.g. toggling an unrelated state via `composeTestRule.runOnIdle`) does not append a second `Request`.
- **Refresh re-fetches in place (AC#2):** `state = Content(...)` → click the refresh `contentDescription` node → recorded events contain `Retry`; the surface is still composed (title node still displayed).
- **`FLAG_SECURE` no-crash under test (AC#4):** the above tests *are* the assertion — they all render under `createComposeRule` (non-Activity host) without crashing, proving the null-safe `findActivity()` no-ops. Optionally one explicit `state = Content(...)` render that just asserts the title is displayed, documenting the no-op path.

## Open questions

- **Per-conversation VM scoping is #382's, not this slice's.** This slice registers the VM as resolvable (`viewModel { }`); #382's nav destination obtains it via `koinViewModel()` within the thread route's back-stack-entry scope so (a) `SavedStateHandle` carries `conversationId` and (b) a fresh VM exists per open with **no cross-conversation `text` bleed**. Flagged so #382's architect picks it up (it is itself `security-sensitive`). If #382 instead scoped the VM to a process/activity singleton, one conversation's snapshot would leak into the next — must not happen.
- **Visual design owed.** Padding, typography scale, empty-state treatment, and whether long lines scroll horizontally vs. a "wrap" toggle are unsettled until the Figma frame exists. The behavioral floor (verbatim / monospace / scrollable / per-reason error / FLAG_SECURE) is locked here; a visual-fidelity retrofit lands with the frame (#343 precedent).
- **Refresh affordance placement** (top-bar icon on `Content`) is a provisional choice to satisfy AC#2's "refresh re-fetches in place"; the owed design may move/relabel it. It maps to the existing `Retry` event — no new VM event is added.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No MUST FIX. The sensitive snapshot text enters this slice already on the trusted side — it arrives as `LiteralScreenUiState.Content.text` from the VM (the network→process boundary was crossed and reviewed in #375/#378). This slice's concern is **confidentiality at the render edge, not integrity**: the text is displayed, never parsed/interpreted/used to build a path or command, so there is no injection surface. Two render-edge leak paths are closed *in the design*: (a) the text must bypass `MarkdownText` and any pre-processing (verbatim `Text` only); (b) **no `SelectionContainer`** — text selection would expose a clipboard exfiltration path past `FLAG_SECURE`. Both are spec-mandated above, not left to developer discretion.
- **[Tokens, secrets, credentials]** N/A — this slice handles no tokens. The snapshot may *contain* on-screen secrets, but it is treated wholesale as sensitive (render-only, no copy, no log, no persist); there is no credential lifecycle here.
- **[File / storage operations]** No MUST FIX. Nothing is written to disk. The spec explicitly forbids `rememberSaveable` / `SavedStateHandle` for the text (both serialize to the disk-backed instance-state bundle, persisting sensitive content across process death) — only the non-sensitive scroll position uses a plain (non-saveable) `remember`. No path is constructed from the text.
- **[Inter-process / Android attack surface]** No MUST FIX. No exported component, intent-filter, deep link, content provider, or WebView is added. The surface is an internal composable; the DI registration only makes the VM type-resolvable within the existing Koin graph. The new `FLAG_SECURE` window flag *reduces* the attack surface (blocks screenshot/recording/cast and non-secure-display capture of the sensitive screen) and is scoped to the surface lifecycle (cleared on dispose, so it does not silently harden unrelated screens).
- **[Cryptographic primitives]** N/A — no RNG, hashing, or key handling introduced.
- **[Network & I/O]** N/A in this slice — the surface issues no network call; `requestScreenSnapshot` runs over the existing Noise/WS transport (frame caps, TLS, timeouts owned upstream). The `LaunchedEffect(Unit)` fires the VM event; the VM owns the request lifecycle and cancellation.
- **[Error messages, logs, telemetry]** No MUST FIX — the load-bearing category, covered by **two independent fabrics** (belt-and-suspenders): the stochastic rule "no `Log.*`/`println` in the surface" **plus** the deterministic facts that (a) the `Error` state carries only a closed enum reason mapped to a fixed string resource — no server string, no snapshot text reaches the error path — and (b) the VM's `Content.toString()` is already redacted, so even an accidental `"$state"` cannot spill the text. No `contentDescription` is built from the snapshot text. The verbatim-render and no-`MarkdownText` invariants are test-pinned (the markdown-bypass test).
- **[Concurrency]** No MUST FIX. The surface launches no coroutine. `FLAG_SECURE` is managed by `DisposableEffect(Unit)` (apply-on-enter / clear-on-dispose) — idempotent `addFlags`/`clearFlags`, no shared mutable state, no race. `LaunchedEffect(Unit)` fires `Request` exactly once (keyed on `Unit`); latest-request-wins lives in the VM. Cross-screen `text` bleed is prevented by per-conversation VM scoping — an obligation that lands in **#382** (flagged in Open questions; #382 is `security-sensitive` and runs its own pass).
- **[Threat model alignment]** Addressed. The mobile-specific leakage threats for a sensitive rendered surface are: **screenshot / screen-recording / cast capture** → blocked by `FLAG_SECURE` (this slice); **clipboard exfiltration** → blocked by omitting `SelectionContainer` (this slice); **instance-state persistence across process death** → blocked by forbidding `rememberSaveable`/`SavedStateHandle` for the text (this slice); **log/crash-dump capture** → blocked by no-logging + the redacted `toString` (this + #378). **Accessibility-service / overlay eavesdropping** of on-screen text is a platform-wide residual not specific to this surface and not addressable here (no per-view defense exists beyond `FLAG_SECURE`, which does not cover a11y reads) — OUT OF SCOPE, noted; no ticket, as it applies app-wide. **Cross-conversation text bleed** via VM scope → OUT OF SCOPE → **#382** (the destination owns instance scoping).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-06-08

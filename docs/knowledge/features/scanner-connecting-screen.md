# Scanner Connecting screen

State surface for the in-flight pairing handshake — the waiting screen the user sees after a successful QR scan while the pyrycode server handshake completes. Pre-built ahead of CameraX + relay integration so visual fidelity to Figma node `32:20` is settled before the real connecting window arrives in Phase 4.

## What it does

- Renders the standard `Pair with pyrycode` `TopAppBar` (back arrow + title) over a body that places a 48dp indeterminate `CircularProgressIndicator` above the vertical centre on `colorScheme.surface`, followed by the headline "Connecting to your pyrycode server…" and the server address the user just paired with rendered in monospace.
- Receives `serverAddress: String` verbatim — no validation, parsing, or transformation; whatever the caller supplies is what the second `Text` shows.
- Exposes a required `onBack: () -> Unit` callback wired to the `TopAppBar`'s back-arrow `IconButton` (#122). No `onCancel` of the in-flight pairing — cancellation is the caller's responsibility, not this screen's.

## How it works

Stateless Composable; no `ViewModel`, no `remember`, no `LaunchedEffect`, no `Context` usage. The `CircularProgressIndicator`'s animation runs as long as the composable is in composition — Compose owns it, not this file.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerConnectingScreen(
    serverAddress: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Composition shape since #122 mirrors `ScannerScreen.kt`'s pairing-flow chrome: outer `Surface(color = colorScheme.surface, fillMaxSize)` wraps a `Column(fillMaxSize().systemBarsPadding())` containing the `TopAppBar` and an inner `Column(weight = 1f).fillMaxWidth(), horizontalAlignment = CenterHorizontally`. The inner column's vertical distribution is `Spacer(weight = 316f) → CircularProgressIndicator(size = 48.dp) → Spacer(28.dp) → headline Text → Spacer(16.dp) → server-address Text → Spacer(weight = 372f)`. The 316:372 weighted-spacer split is the Figma `(384 − 68) : (892 − 520)` ratio computed against the area *below* the 68dp TopAppBar (AC2 — proportional, survives different viewport heights); the 28dp / 16dp gaps inside the cluster are pixel-accurate per Figma between spinner, caption, and address, which is why they stay fixed rather than weighted.

### TopAppBar (since #122)

Lifted verbatim from `ScannerScreen.kt`'s pairing-flow chrome: `title = Text("Pair with pyrycode", style = MaterialTheme.typography.titleLarge)`, `navigationIcon = IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }`, `colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, titleContentColor = colorScheme.onSurface, navigationIconContentColor = colorScheme.onSurface)`. The transparent container lets the parent `Surface(color = colorScheme.surface)` show through. The English literal `"Back"` `contentDescription` is intentional (not `R.string.cd_back`) — matches `ScannerScreen.kt`'s pairing-flow grouping; `LicenseScreen.kt` (#91) is the screen that routes through the shared string id.

### Indicator — M3 defaults, zero theming arguments

`CircularProgressIndicator(modifier = Modifier.size(48.dp))` with no other parameters. M3 supplies the indeterminate animation (~270° rotating arc), `colorScheme.primary` for the active arc, `colorScheme.surfaceVariant` for the track, and a 4dp stroke — all of which match the Figma reference at scale. Passing `color =`, `trackColor =`, or `strokeWidth =` would invite hardcoded color literals; not passing them keeps AC#5 satisfied by construction. The Figma file's hand-built 3-cubic-Bézier path is a static export workaround and is intentionally not replicated.

### Typography binding

| Element | M3 role | Notes |
|---|---|---|
| TopAppBar title ("Pair with pyrycode") | `MaterialTheme.typography.titleLarge` | Top-level `style =` argument on the title `Text`; resolved via `Typography()` defaults (no theme override). |
| Headline ("Connecting to your pyrycode server…") | `MaterialTheme.typography.bodyLarge` | Figma binds Roboto Regular 16sp/24sp w/ 0.15 tracking; M3 `bodyLarge` is 16sp/24sp w/ 0.5 letter-spacing. The 0.35sp delta is sub-perceptible at 16sp and resolved at spec time — do not `TextStyle.copy(letterSpacing = …)` to chase it. |
| Server address (`"home.lan:7117"`) | `MaterialTheme.typography.bodyMedium` + `fontFamily = FontFamily.Monospace` as a top-level `Text` parameter | `FontFamily.Monospace` is Compose's platform-monospace alias; no `res/font/` resource needed. The top-level `fontFamily =` argument overrides only the family in the passed `style` — keeps the named-role binding intact (AC#3) while satisfying the monospace requirement without an inlined `TextStyle.copy(...)`. |

## Color binding

| Element | Slot |
|---|---|
| Root `Surface` background | `colorScheme.surface` |
| `TopAppBar` container | `Color.Transparent` (parent `Surface` shows through) |
| `TopAppBar` title + nav-icon content | `colorScheme.onSurface` |
| `CircularProgressIndicator` active arc | `colorScheme.primary` (M3 default — not overridden) |
| `CircularProgressIndicator` track | `colorScheme.surfaceVariant` (M3 default — not overridden) |
| Headline | `colorScheme.onSurfaceVariant` (since #122 — was `onSurface`; AC3 contract) |
| Server address | `colorScheme.onSurfaceVariant` |

No `Color(0x…)` literals anywhere in the file. Typography binds to M3 roles only — no `TextStyle(...)` constructors.

## Configuration / usage

Not yet wired into `MainActivity`'s `NavHost`. The composable is in place for Phase 4: when the CameraX + relay handshake lands, the caller (likely a pairing-flow nav destination or a Scanner-screen state branch) owns the handshake state machine and renders this screen for the duration of the connecting window with the host/port being negotiated. The caller supplies `onBack = { navController.popBackStack() }` (matching the `MainActivity.kt:209/215/225` pattern).

`modifier` is forwarded to the root `Surface` so a host can constrain the screen in tests.

## Top bar (since #122)

The "Pair with pyrycode" `TopAppBar` lives in this screen now, not the NavHost. The #62 rationale — "the top bar is a NavHost concern, not a screen concern" — was walked back in #122 once `ScannerScreen.kt` and the rest of the pairing-flow Figma frames made it clear the bar is part of the screen's visual identity rather than host chrome. The screen-level bar is the new precedent for pairing-flow screens; see [`codebase/122.md`](../codebase/122.md) for the lesson.

## State + concurrency

None. Pure function from `serverAddress` to UI. The indicator's animation is internal to `CircularProgressIndicator` and runs as long as the composable is in composition. No coroutines, no flows, no remember.

## Error handling

N/A. The screen *is* the waiting surface for the connecting state; there is no I/O, no parse step, no network call in this file. Success / failure / timeout transitions are caller-owned and arrive in Phase 4 alongside the handshake state machine.

## Edge cases / limitations

- **No `serverAddress` validation.** Whatever the caller passes is rendered verbatim — `""`, very long strings, IPv6 with brackets, anything. The single `Text` line will ellipsize at viewport width without a special case, which is fine for the connecting window (the caller knows what it tried to pair with).
- **Phase 4 walk-back.** The screen survives Phase 4 wholesale — its sole role is the visual surface for the connecting state, which CameraX + relay integration consumes as-is. The only future additions are the host's state machine and (likely) the `TopAppBar` at the NavHost level.
- **Two `@Preview` composables plus a four-method instrumented test class.** `app/src/androidTest/.../onboarding/ScannerConnectingScreenTest.kt` covers `connectingMessage_renders` (substring match `"Connecting to your pyrycode server"` — substring avoids the U+2026 ellipsis), `serverAddress_rendersVerbatim` (exact match on the `"home.lan:7117"` literal passed as the `serverAddress` arg), and since #122 `topBarTitle_renders` (exact match on `"Pair with pyrycode"` — regression guard against title-text drift) and `backButton_invokesOnBack` (`hasContentDescription("Back")` → `performClick()` → `assertTrue(backInvoked)`, the project's standard click-wiring shape for a single `onBack` lambda). Both previews pass `onBack = {}` since #122. No spinner-position pixel asserts — proportional layout is visually verified via `@Preview` review against Figma 32:20.

## Related

- Issues: https://github.com/pyrycode/pyrycode-mobile/issues/62 (original), https://github.com/pyrycode/pyrycode-mobile/issues/122 (TopAppBar + spinner position)
- Specs: `docs/specs/architecture/62-scanner-connecting-screen.md`, `docs/specs/architecture/122-scanner-connecting-topappbar.md`
- Ticket notes: `../codebase/62.md`, `../codebase/122.md`
- Figma node: `32:20`
- Sibling docs: [Scanner screen](scanner-screen.md), [Scanner Denied screen](scanner-denied-screen.md), [Welcome screen](welcome-screen.md)
- Downstream: Phase 4 CameraX + relay integration owns the handshake state machine and hosts this screen during the connecting window; the caller supplies `onBack = { navController.popBackStack() }`.

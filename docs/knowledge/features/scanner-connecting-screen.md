# Scanner Connecting screen

State surface for the in-flight pairing handshake — the waiting screen the user sees after a successful QR scan while the pyrycode server handshake completes. Pre-built ahead of CameraX + relay integration so visual fidelity to Figma node `32:20` is settled before the real connecting window arrives in Phase 4.

## What it does

- Renders a vertically-centered column on `colorScheme.surface`: a 48dp indeterminate `CircularProgressIndicator`, the headline "Connecting to your pyrycode server…", and the server address the user just paired with rendered in monospace.
- Receives `serverAddress: String` verbatim — no validation, parsing, or transformation; whatever the caller supplies is what the second `Text` shows.
- No callbacks, no `onBack`, no `onCancel`. The screen owns no state and produces no side effects.

## How it works

Stateless Composable; no `ViewModel`, no `remember`, no `LaunchedEffect`, no `Context` usage. The `CircularProgressIndicator`'s animation runs as long as the composable is in composition — Compose owns it, not this file.

```kotlin
@Composable
fun ScannerConnectingScreen(
    serverAddress: String,
    modifier: Modifier = Modifier,
)
```

Composition shape mirrors `ScannerScreen.kt` and `ScannerDeniedScreen.kt`: outer `Surface(color = colorScheme.surface, fillMaxSize)` wraps an inner `Column(fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = CenterHorizontally)`. Vertical distribution is `CircularProgressIndicator(size = 48.dp) → Spacer(24.dp) → headline Text → Spacer(12.dp) → server-address Text`. `Arrangement.Center` is used instead of `ScannerDeniedScreen`'s `Spacer(weight = 1f)` because this screen has no bottom-pinned actions — the three children are a single centered cluster.

### Indicator — M3 defaults, zero theming arguments

`CircularProgressIndicator(modifier = Modifier.size(48.dp))` with no other parameters. M3 supplies the indeterminate animation (~270° rotating arc), `colorScheme.primary` for the active arc, `colorScheme.surfaceVariant` for the track, and a 4dp stroke — all of which match the Figma reference at scale. Passing `color =`, `trackColor =`, or `strokeWidth =` would invite hardcoded color literals; not passing them keeps AC#5 satisfied by construction. The Figma file's hand-built 3-cubic-Bézier path is a static export workaround and is intentionally not replicated.

### Typography binding

| Element | M3 role | Notes |
|---|---|---|
| Headline ("Connecting to your pyrycode server…") | `MaterialTheme.typography.bodyLarge` | Figma binds Roboto Regular 16sp/24sp w/ 0.15 tracking; M3 `bodyLarge` is 16sp/24sp w/ 0.5 letter-spacing. The 0.35sp delta is sub-perceptible at 16sp and resolved at spec time — do not `TextStyle.copy(letterSpacing = …)` to chase it. |
| Server address (`"home.lan:7117"`) | `MaterialTheme.typography.bodyMedium` + `fontFamily = FontFamily.Monospace` as a top-level `Text` parameter | `FontFamily.Monospace` is Compose's platform-monospace alias; no `res/font/` resource needed. The top-level `fontFamily =` argument overrides only the family in the passed `style` — keeps the named-role binding intact (AC#3) while satisfying the monospace requirement without an inlined `TextStyle.copy(...)`. |

## Color binding

| Element | Slot |
|---|---|
| Root `Surface` background | `colorScheme.surface` |
| `CircularProgressIndicator` active arc | `colorScheme.primary` (M3 default — not overridden) |
| `CircularProgressIndicator` track | `colorScheme.surfaceVariant` (M3 default — not overridden) |
| Headline | `colorScheme.onSurface` |
| Server address | `colorScheme.onSurfaceVariant` |

No `Color(0x…)` literals anywhere in the file. Typography binds to M3 roles only — no `TextStyle(...)` constructors.

## Configuration / usage

Not yet wired into `MainActivity`'s `NavHost`. The composable is in place for Phase 4: when the CameraX + relay handshake lands, the caller (likely a pairing-flow nav destination or a Scanner-screen state branch) owns the handshake state machine and renders this screen for the duration of the connecting window with the host/port being negotiated.

`modifier` is forwarded to the root `Surface` so a host can constrain the screen in tests.

## Why no top bar

The Figma frame includes a "Pair with pyrycode" `TopAppBar` with a back affordance; this screen intentionally omits it. Precedent: `ScannerScreen.kt` (Phase 0 stub form — Phase 1.5 #60 added an in-screen `TopAppBar` but at the screen level, not as a host concern) and `ScannerDeniedScreen.kt` both omit it. The AC enumerates exactly three rendered elements (indicator + headline + address) and forbids navigation, so the top bar is deferred to Phase 4 wiring.

## State + concurrency

None. Pure function from `serverAddress` to UI. The indicator's animation is internal to `CircularProgressIndicator` and runs as long as the composable is in composition. No coroutines, no flows, no remember.

## Error handling

N/A. The screen *is* the waiting surface for the connecting state; there is no I/O, no parse step, no network call in this file. Success / failure / timeout transitions are caller-owned and arrive in Phase 4 alongside the handshake state machine.

## Edge cases / limitations

- **No `serverAddress` validation.** Whatever the caller passes is rendered verbatim — `""`, very long strings, IPv6 with brackets, anything. The single `Text` line will ellipsize at viewport width without a special case, which is fine for the connecting window (the caller knows what it tried to pair with).
- **Phase 4 walk-back.** The screen survives Phase 4 wholesale — its sole role is the visual surface for the connecting state, which CameraX + relay integration consumes as-is. The only future additions are the host's state machine and (likely) the `TopAppBar` at the NavHost level.
- **Two `@Preview` composables plus a two-method instrumented test class since #101.** `app/src/androidTest/.../onboarding/ScannerConnectingScreenTest.kt` covers `connectingMessage_renders` (substring match `"Connecting to your pyrycode server"` — substring avoids the U+2026 ellipsis) and `serverAddress_rendersVerbatim` (exact match on the `"home.lan:7117"` literal passed as the `serverAddress` arg). Structure-only: no callback wiring to assert (the screen has none).

## Related

- Issue: https://github.com/pyrycode/pyrycode-mobile/issues/62
- Spec: `docs/specs/architecture/62-scanner-connecting-screen.md`
- Ticket notes: `../codebase/62.md`
- Figma node: `32:20`
- Sibling docs: [Scanner screen](scanner-screen.md), [Scanner Denied screen](scanner-denied-screen.md), [Welcome screen](welcome-screen.md)
- Downstream: Phase 4 CameraX + relay integration owns the handshake state machine and hosts this screen during the connecting window.

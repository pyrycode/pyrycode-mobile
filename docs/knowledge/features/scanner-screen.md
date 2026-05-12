# Scanner screen

Stub QR-pairing screen that stands in for the real CameraX + ML Kit scanner (Phase 4). Closes the onboarding navigation loop between Welcome and the Channel List by giving Welcome's "I already have pyrycode" CTA a real intermediate destination.

## What it does

- Renders a full-screen fake "camera viewport" (rounded square `surfaceVariant` box) with a static scan-line indicator (thin `primary`-colored horizontal bar) and a "Tap to pair" caption below.
- Tap **anywhere** on the screen → flips `AppPreferences.setPairedServerExists(true)` → navigates to `channel_list` with the scanner popped from the back stack.
- No camera, no permissions, no ML Kit. Any tap is success.

## How it works

Pure stateless Composable. No `ViewModel`, no `UiState` / `Event` sealed types, no `Modifier` parameter — a fire-and-forget tap is the entire interaction model.

```kotlin
@Composable
fun ScannerScreen(onTap: () -> Unit)
```

Composition shape: outer `Surface(color = background, fillMaxSize, pointerInput { detectTapGestures { onTap() } })` over an inner `Column` (`systemBarsPadding`, centered) containing a square viewport `Box` + `Spacer` + "Tap to pair" `Text`. The viewport contains the scan-line `Box`. All colors come from `MaterialTheme.colorScheme.*`, all type from `MaterialTheme.typography.titleMedium`.

Two deliberate design points worth knowing:

- **`pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }` over `Modifier.clickable`.** `clickable` applies a Material ripple to the entire screen that fights the camera-viewport metaphor. `detectTapGestures` is the idiomatic shape for "raw tap, no decoration." Not the `clickable(indication = null, interactionSource = ...)` variant either — that requires two extra imports and reads like "I want clickable, minus what makes clickable useful."
- **Tap detection lives on the outer `Surface`, not the inner `Column`.** `enableEdgeToEdge()` makes content draw under system bars; if the gesture detector were on the inner column the inset region wouldn't catch taps. The same reasoning drives `systemBarsPadding()` being on the inner `Column` (visual inset) rather than the `Surface` (which fills edge-to-edge so the tap window is the full screen).

## Configuration / usage

Mounted at the `scanner` route in `PyryNavHost` (see `MainActivity.kt`):

```kotlin
composable(Routes.Scanner) {
    val appPreferences = koinInject<AppPreferences>()
    val scope = rememberCoroutineScope()
    ScannerScreen(
        onTap = {
            scope.launch {
                appPreferences.setPairedServerExists(true)
                navController.navigate(Routes.ChannelList) {
                    popUpTo(Routes.Scanner) { inclusive = true }
                    launchSingleTop = true
                }
            }
        },
    )
}
```

Notes:

- **`koinInject<AppPreferences>()` from `org.koin.compose`**, not a `ScannerViewModel`. Phase 4 will replace this whole `composable(...)` block wholesale; a ViewModel here is over-engineering for a stub that exists to be deleted. The pattern is "destination-block-scoped Koin + `rememberCoroutineScope`" for stub destinations that need a one-shot suspend side-effect.
- **`scope.launch { setPairedServerExists(true); navigate(...) }` is sequential.** Awaiting the DataStore write before navigating matters once #13 lands its `pairedServerExists` collector as the start-destination predicate — fire-and-forget would race the next composition.
- **`popUpTo(Routes.Scanner) { inclusive = true }` + `launchSingleTop = true`.** The `inclusive = true` is what satisfies "scanner is removed from the back stack" (without `inclusive`, `popUpTo(Routes.Scanner)` is a no-op since Scanner is the top). `launchSingleTop` guards against double-tap stacking duplicate ChannelList entries during the in-flight coroutine.

## Why no ViewModel

Stub destinations with no observable state, no `UiState` to expose, and no lifecycle past a single `suspend` write don't need a ViewModel. The MVI conventions in `CLAUDE.md` apply to screens that *have* state to manage — they aren't a mandate to wrap every destination in scaffolding. Phase 4 (real CameraX preview, scanning state machine, error UI) will introduce a `ScannerViewModel` when there is actual state to manage.

## State + concurrency

- **Scope.** `rememberCoroutineScope()` inside the `composable(Routes.Scanner)` block, tied to the destination's composition. Cancels if the destination leaves the back stack mid-write — acceptable for a millisecond-scale DataStore write.
- **Dispatcher.** `Dispatchers.Main.immediate` from `rememberCoroutineScope`; `DataStore.edit` internally hops to IO for the disk write, then resumes back on Main for `navController.navigate(...)`. No manual `withContext` needed.
- **Cancellation race.** If cancellation occurs after the write returns but before `navigate(...)` runs, the preference is set but no navigation happens — and cancellation only happens via the destination leaving the back stack (user already navigated away). Acceptable.

## Error handling

- **DataStore write failures** propagate as `IOException` to the (unregistered) `CoroutineExceptionHandler` → crash. For a Phase 0 stub on a fresh first-run install, a DataStore failure is a real bug that should be loud. No try/catch around the write; #13 / Phase 4 will revisit error UX once pairing has a real failure surface.
- **Unknown routes** can't happen at runtime — `Routes.ChannelList` is registered in the same `NavHost` in the same file.

## Edge cases / limitations

- **Tap-handler keyed on `Unit`, not `onTap`.** The `onTap` callback is bound to a stable `scope.launch { ... }` closure for the destination's lifetime, so re-keying on every recomposition is unnecessary churn. If the surrounding NavHost block ever grows mutable state that the callback needs to read, re-key on `onTap` (or a more specific dependency).
- **No Welcome-pop on success.** Only `Scanner` is popped, not `Welcome` — that's #13's call (conditional start destination). Until #13 lands, a back-press from ChannelList returns to Welcome; tapping "I already have pyrycode" again re-routes through Scanner (the stub ignores already-paired state). Documented intermediate state, not a bug.
- **Static scan-line.** No `rememberInfiniteTransition` animation. Animation polish is explicitly Phase 4's job (see the ticket's "Out of scope"). A motionless line reads as "scan area indicator" well enough for the stub.
- **Visual fidelity is approximate, not pixel-perfect** to Figma node `13:2`. Phase 4 owns 1:1 fidelity; this is a structural stand-in.
- **Phase 4 walk-back.** The `Routes.Scanner` route constant survives the Phase 4 rewrite. Everything inside `composable(Routes.Scanner) { ... }` and the entire `ScannerScreen.kt` file do not. Don't add abstractions here anticipating the Phase 4 shape.

## Related

- Issue: https://github.com/pyrycode/pyrycode-mobile/issues/12
- Spec: `docs/specs/architecture/12-stub-scanner-screen.md`
- Ticket notes: `../codebase/12.md`
- Figma node: `13:2` (approximate)
- Upstream: #8 (NavHost), #11 (`AppPreferences.setPairedServerExists`)
- Downstream: #13 (conditional start destination based on `pairedServerExists`), Phase 4 (CameraX + ML Kit replaces this whole block)
- Sibling docs: [Navigation](navigation.md), [Welcome screen](welcome-screen.md), [App preferences](app-preferences.md)

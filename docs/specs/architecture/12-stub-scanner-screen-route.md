# Spec — Stub Scanner screen + route (#12)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:1-87` — the entire file. The `PyryNavHost` Composable (lines 37-69) is where the new `composable(Routes.Scanner) { ... }` block is added, the `private object Routes` (lines 81-86) is where the new route constant is added, and the existing `composable(Routes.Welcome) { ... }` block's `onPaired` lambda (line 47-49) is the one re-wired from `ChannelList` → `Scanner`. This file is one of two files edited by this ticket.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt` — the whole file. The new `ScannerScreen.kt` is a sibling in the same package and should mirror the same shape: top-level `@Composable` taking lambda callbacks, no inner `Scaffold`, outer `Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize())`, `systemBarsPadding()`, two `@Preview` previews (light + dark) at `widthDp = 412, heightDp = 892` using `PyrycodeMobileTheme(darkTheme = ...)`. Match imports/conventions exactly.
- `app/src/main/java/de/pyryco/mobile/data/preferences/AppPreferences.kt` — confirms `setPairedServerExists` is `suspend` and that the class is constructor-injectable. This drives the coroutine-scope decision below (NavHost-level `rememberCoroutineScope` + `koinInject<AppPreferences>()`, not a ViewModel for a stub).
- `app/src/main/java/de/pyryco/mobile/di/appModule.kt` (path: `app/src/main/java/de/pyryco/mobile/di/`) — confirms `AppPreferences` is registered as a `single { ... }` and therefore retrievable via `koinInject<AppPreferences>()` in composition. No Koin module edits needed for this ticket.
- `docs/specs/architecture/16-settings-placeholder-route.md` — the most recently merged placeholder-route spec. Same shape pattern (route constant + `composable(...)` block + small Composable in dedicated file or inline). Differences for #12: this ticket adds a full-screen visual (not a `Text` placeholder), the destination performs a `suspend` side-effect on tap, and the navigation uses `popUpTo(Routes.Scanner) { inclusive = true }` to remove the stub from the back stack.
- `gradle/libs.versions.toml:14,15,33,37` and `app/build.gradle.kts:43-54` — confirm `androidx.navigation:navigation-compose` and `io.insert-koin:koin-androidx-compose` are both on the classpath. No catalog or build-file edits required.

## Context

#8 stood up the NavHost with `Welcome` and `ChannelList`. #15 / #16 added `ConversationThread` and `Settings` placeholders. #11 landed `AppPreferences.setPairedServerExists(...)` and its Koin binding. The Welcome screen's "I already have pyrycode" button currently navigates straight to `channel_list` — a temporary shortcut from #8 that bypasses the missing pairing step.

This ticket closes that loop. The pairing UX in production (Phase 4) will use CameraX + ML Kit to scan a QR code emitted by the pyrycode CLI; until that backend lands, we need a stand-in that lets us exercise the post-onboarding flow end to end. The stand-in is a screen that *looks* roughly like a camera scanner (viewport surface + scan-line + "Tap to pair" affordance) but accepts any tap as success: set `pairedServerExists = true`, navigate to `channel_list`, drop the scanner from the back stack so a back-press from `channel_list` exits the app rather than returning to a fake camera.

The conditional NavHost start destination based on `pairedServerExists` (which would make this screen reachable only on first run) is **#13's** job, not this one — that ticket is currently `ready:po` and will land separately. After both ship, the flow is: cold-start → start destination chooses (Welcome if not paired, ChannelList if paired) → if Welcome, tap "I already have pyrycode" → Scanner → tap anywhere → ChannelList (with Welcome + Scanner both popped). This ticket builds the middle hop.

## Design

### Route constant

Add to the `private object Routes` in `MainActivity.kt`, between `Welcome` and `ChannelList` (read order matches the user flow):

```kotlin
const val Scanner = "scanner"
```

Plain unparameterized string. Same shape as `Welcome` / `ChannelList` / `Settings`. The placement between `Welcome` and `ChannelList` is purely cosmetic — Kotlin doesn't care about declaration order in an `object`, but reading `Welcome → Scanner → ChannelList → ConversationThread → Settings` matches the navigation order and helps future readers.

### Re-wire Welcome's `onPaired`

In `PyryNavHost`, change the existing `composable(Routes.Welcome) { ... }`'s `onPaired` lambda from:

```kotlin
onPaired = {
    navController.navigate(Routes.ChannelList)
},
```

to:

```kotlin
onPaired = {
    navController.navigate(Routes.Scanner)
},
```

That's the entire diff for the Welcome block. `WelcomeScreen.kt` itself is **not edited** — its callback shape (`onPaired: () -> Unit`) is unchanged; only the destination wired into it changes. Do not rename `onPaired` to e.g. `onContinue` in WelcomeScreen — the button's semantic is still "the user is asserting they have pyrycode set up"; the only thing that's changed is that we now ask them to prove it via the scanner stub instead of trusting the click. The name remains accurate.

### Scanner destination registration

Add a new `composable(...)` block to `PyryNavHost`, placed between the existing `Routes.Welcome` and `Routes.ChannelList` blocks (matching the route-constant order). The block must:

1. Resolve `AppPreferences` from Koin.
2. Remember a `CoroutineScope` for the suspend call.
3. Pass an `onTap: () -> Unit` callback to `ScannerScreen` that:
   - Launches a coroutine that calls `appPreferences.setPairedServerExists(true)`,
   - Then navigates to `channel_list` with `popUpTo(Routes.Scanner) { inclusive = true }` and `launchSingleTop = true`.

Worked example:

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

Design points:

- **`koinInject<AppPreferences>()`** from `org.koin.compose.koinInject` (provided by `io.insert-koin:koin-androidx-compose`, already on classpath). This is the canonical Compose-side accessor for Koin singletons — it reads the running Koin instance and returns the same `AppPreferences` registered in `appModule`. Do **not** introduce a `ScannerViewModel` for this stub: there is no observable state (the screen has no `UiState`/`Event` sealed types — just a fire-and-forget tap), and the Phase 4 rewrite will replace this whole `composable(...)` block wholesale. A ViewModel here is over-engineering for a stub that exists to be deleted.
- **`rememberCoroutineScope()`** ties the coroutine's lifetime to the composition. If the user navigates away (e.g. via system back) before the suspend completes, the coroutine cancels. For a `DataStore.edit` write that finishes in milliseconds this is almost never observable, but it is the right scope choice — `viewModelScope` is unavailable (no VM) and `GlobalScope` is wrong (forbidden by CLAUDE.md conventions and leaks across navigation).
- **`scope.launch { setPairedServerExists(true); navigate(...) }`** awaits the DataStore write before navigating. The alternative — fire-and-forget the write then navigate immediately on the main thread — risks a window where ChannelList composes before the preference flips, which matters once #13 lands (its `pairedServerExists` collector becomes the start-destination predicate). Sequential is correct.
- **`popUpTo(Routes.Scanner) { inclusive = true }`** is what satisfies AC3's "scanner removed from the back stack." Without `inclusive = true`, `popUpTo(Routes.Scanner)` would pop *to* Scanner (inclusive of nothing above it, so a no-op here). With `inclusive = true`, Scanner itself is popped before the new `ChannelList` entry is pushed. The result: back stack is `[Welcome, ChannelList]` after navigation — a back-press from ChannelList returns to Welcome, which is acceptable (Welcome's "I already have pyrycode" tap will re-route through Scanner if it's still the destination, but once #13's conditional start lands, Welcome is gone from the stack on cold start and this becomes moot). We are deliberately **not** popping Welcome too — that's #13's call, not ours, and popping it here would diverge from the AC.
- **`launchSingleTop = true`** prevents double-tap from stacking two `ChannelList` entries. The first tap launches the coroutine; if the user taps again before the coroutine completes (the screen is still composed), a second coroutine fires, also calls `setPairedServerExists(true)` (idempotent) and `navigate(...)` (now Scanner is already gone from the stack, so `popUpTo(Routes.Scanner)` is a no-op warning in logs; `launchSingleTop` prevents a duplicate ChannelList push). Belt-and-suspenders against a real failure mode (user pokes the screen). No need to add an `isNavigating` `MutableState` guard — Compose Navigation handles this correctly with `launchSingleTop` + `popUpTo`.

### `ScannerScreen` composable

New file: `app/src/main/java/de/pyryco/mobile/ui/onboarding/ScannerScreen.kt`. Package `de.pyryco.mobile.ui.onboarding`. Mirror `WelcomeScreen.kt`'s structural shape.

Signature:

```kotlin
@Composable
fun ScannerScreen(
    onTap: () -> Unit,
)
```

Single `onTap` callback. Stateless — no `UiState`/`Event` sealed types (no observable state to manage), no `Modifier` parameter (other onboarding composables in this file don't take one and the outer `Scaffold` already supplies `innerPadding`).

Composition shape:

```kotlin
@Composable
fun ScannerScreen(onTap: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Viewport surface — fake camera preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                // Scan-line indicator — a thin horizontal bar centered in the viewport
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(modifier = Modifier.size(24.dp))
            Text(
                text = "Tap to pair",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}
```

Design points:

- **`pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }`** is the tap-anywhere mechanism. `Modifier.clickable { onTap() }` is the more conventional choice, but it applies a Material ripple to the whole screen which fights the camera-viewport metaphor. `detectTapGestures` gives a tap callback with **no visual feedback**, which is what we want for a "fake scanner accepting any tap" affordance. The `Unit` key is correct — the lambda has no captures that change across recompositions (the `onTap` callback closure is recreated on every recomposition but `pointerInput` doesn't re-key on it; that's fine here because `onTap` is wired to a stable navigation lambda and any pending tap fires the most recent closure via the closure-over-state captured at gesture-detection time). If a developer hits a "stale onTap" issue (they shouldn't — `onTap` is bound to the same `scope.launch { ... }` closure for the life of the destination), the fix is to key on `onTap`; do not pre-emptively change this.
- **No `Modifier.clickable(indication = null, interactionSource = remember { ... }) { onTap() }`** — that works but requires two extra imports (`MutableInteractionSource`, `interactionSource`) and reads like "I want clickable, minus what makes clickable useful." `pointerInput` + `detectTapGestures` is the idiomatic shape for "raw tap, no decoration."
- **Viewport surface** — `Box` with `aspectRatio(1f)`, rounded corners (`RoundedCornerShape(16.dp)`), `surfaceVariant` background. Approximates the Figma `13:2` camera frame without trying to be pixel-perfect (Phase 4 owns visual fidelity per the "Out of scope" list). The square aspect ratio is a deliberate choice: real QR codes are square, and a square viewport telegraphs "this is a camera" without committing to a specific framing.
- **Scan-line indicator** — a single static horizontal `Box` (`fillMaxWidth(0.8f).height(2.dp)`) in `primary` color, centered in the viewport via the outer `Box`'s `contentAlignment = Alignment.Center`. Static, not animated. The ticket explicitly lists "Atmospheric gradients / scan-line glow polish (Phase 4 visual fidelity)" as out of scope, and a `rememberInfiniteTransition` for a stub is over-engineering. A motionless line reads as "scan area indicator" well enough for the stub's purpose.
- **"Tap to pair" affordance** — a single `Text` below the viewport, `titleMedium` style, `onBackground` color. Centered. The text makes the tap-anywhere behaviour discoverable; the lack of a button shape makes it clear (in combination with the camera viewport above) that the *whole screen* accepts the tap, not just the text. Do not wrap this in a `Button` or `OutlinedButton` — that would imply the tap target is the button, which contradicts AC3 ("Tap **anywhere** on `ScannerScreen`").
- **`Surface(color = MaterialTheme.colorScheme.background)`** wraps the whole screen, matching `WelcomeScreen.kt`'s outer-frame pattern. `fillMaxSize()` on the surface, then the tap-detection modifier on the same `Surface` so the whole window catches taps. Putting `pointerInput` on the inner `Column` would miss taps in the system-bar padding region — a real possibility on devices with edge-to-edge insets. Keep it on the `Surface`.
- **`systemBarsPadding()` on the inner `Column`, not the outer `Surface`.** Same pattern as `WelcomeScreen.kt`: the Surface fills edge-to-edge (so the tap catches in the inset region), the inner column inset-respects content. This matters because `enableEdgeToEdge()` in `MainActivity.onCreate` makes content draw under the status bar; without `systemBarsPadding` the viewport box would clip into the status bar visually.

### Required imports

In `MainActivity.kt`:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.ui.onboarding.ScannerScreen
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
```

In `ScannerScreen.kt`:

```kotlin
package de.pyryco.mobile.ui.onboarding

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
```

All ship with the Compose BOM (`2026.02.01`) and `androidx.compose.material3:material3`, already on the classpath. No catalog or build-file edits.

### Previews

Two `@Preview` functions at the bottom of `ScannerScreen.kt`, identical shape to `WelcomeScreen.kt`'s previews:

```kotlin
@Preview(name = "Light", showBackground = true, widthDp = 412, heightDp = 892)
@Composable
private fun ScannerScreenLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ScannerScreen(onTap = {})
    }
}

@Preview(
    name = "Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 892,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ScannerScreenDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        ScannerScreen(onTap = {})
    }
}
```

Pass-through empty lambda for `onTap` — the preview never invokes it. AC4 requires both previews render without crashing; nothing in the composable body depends on a Koin instance, a navigation controller, or a coroutine scope, so the previews render in isolation.

## State + concurrency model

- **Scope:** `rememberCoroutineScope()` inside the `composable(Routes.Scanner)` block. Tied to the destination's composition lifecycle; cancels if the destination leaves the back stack (e.g. system back during the in-flight write — almost never observable for a DataStore write).
- **Dispatcher:** Default Main from `rememberCoroutineScope` (which uses `Dispatchers.Main.immediate` under the hood). `DataStore.edit` internally switches to `Dispatchers.IO` for the disk write; the calling coroutine resumes back on Main for the `navController.navigate(...)` call. No manual `withContext(Dispatchers.IO)` needed.
- **Hot vs cold:** N/A — no Flow collected on this screen. The `AppPreferences.pairedServerExists: Flow<Boolean>` getter is unused here (#13 will collect it at NavHost start-destination resolution time, not in this screen).
- **Shutdown / cancellation:** The launched coroutine cancels with the composition. If cancellation occurs after `setPairedServerExists(true)` returns but before `navController.navigate(...)` runs, the preference is set but no navigation happens — the user is on a Scanner screen that's about to be torn down anyway (since cancellation only happens via the destination leaving the back stack, i.e. via back-press, which means the user already navigated away). This is an acceptable race.

## Error handling

- **DataStore write failures:** `AppPreferences.setPairedServerExists` doesn't try/catch; a failed write throws (`IOException`) inside the launched coroutine. Untrapped exceptions in a `rememberCoroutineScope().launch { ... }` propagate to the `CoroutineExceptionHandler` (none registered → crash). For a Phase 0 stub this is the right behaviour — a DataStore failure on a fresh first-run install is a real bug that should be loud, not silently swallow the pairing claim. Do **not** add a try/catch around the write here; #13 / Phase 4 will revisit error UX once pairing has a real failure surface.
- **Navigation failures:** `navController.navigate(Routes.ChannelList) { ... }` is synchronous and only throws on programmer error (unknown route). The route is registered in the same NavHost in the same file — there's no runtime path to an unknown-route exception.
- **Preview crashes:** AC4 calls these out specifically. The composable body has no runtime dependency on Koin/NavController/coroutines, so previews can't crash from injection failures. If a preview crashes, it's a layout-side bug — fix the composable, not the preview.

## Testing strategy

Per CLAUDE.md's "Test-first" convention, the canonical shape is red → green → refactor with a failing test first. For UI-only stub work in this codebase the convention has been satisfied by AC5 (`./gradlew assembleDebug` passes) plus preview-rendering smoke checks (AC4) — `WelcomeScreen.kt`, the `SettingsPlaceholder`, and the `ConversationThread` placeholder all shipped with the same shape (no `ComposeTestRule` test, no `runTest` coverage). Match that precedent here:

- **Unit (`./gradlew test`):** None required. There's no business logic to unit-test — the `suspend` call is one line of pass-through into DataStore, the navigation is one line of NavController API, and both are exercised by `assembleDebug` + manual smoke.
- **Instrumented (`./gradlew connectedAndroidTest`):** None required. AC3's back-stack invariant (`popUpTo(Routes.Scanner) { inclusive = true }`) is a one-line declarative fact in `MainActivity.kt`; the value of a Compose UI test asserting "after tap, current destination is `channel_list` and back stack contains only `welcome`" is low relative to the cost of writing it, and the entire stub is scheduled for replacement in Phase 4. Manual smoke (run on emulator, tap "I already have pyrycode", tap scanner, confirm we land on Channel List, press back, confirm we exit / return to Welcome) is sufficient.
- **`./gradlew lint`:** Should pass without new warnings.
- **`./gradlew assembleDebug`:** Must pass (AC5).
- **Preview rendering:** Open `ScannerScreen.kt` in Android Studio's Compose preview, confirm both `ScannerScreenLightPreview` and `ScannerScreenDarkPreview` render. AC4.

If a developer wants to add a `ComposeTestRule` test anyway, the shape would be: launch `PyrycodeMobileTheme { ScannerScreen(onTap = capturingLambda) }`, perform `onRoot().performClick()` (or `onNodeWithText("Tap to pair").performClick()`), assert `capturingLambda` was invoked. But this is **not** an AC and should not block the ticket.

## Open questions

- **Tap-handler key:** `pointerInput(Unit)` captures the initial `onTap` closure. If the closure could change across recompositions in a meaningful way (e.g. picking up new state), the key should be `onTap` or a more specific dependency. For this ticket the `onTap` lambda is bound to a stable `scope.launch { ... }` body that doesn't depend on recomposable state, so `Unit` is fine. Flag for revisit only if the surrounding NavHost block ever grows mutable state that the callback needs to read.
- **Welcome-pop on success:** This spec deliberately does not pop Welcome when navigating Scanner → ChannelList (only Scanner pops). After this ticket lands and before #13 lands, a user who has paired via the stub can back-press from ChannelList and land on Welcome again, where re-tapping "I already have pyrycode" routes them back through Scanner (already-paired state ignored by this stub). This is the documented intermediate state; #13's conditional start destination resolves it by skipping Welcome on subsequent launches. Do not preempt #13 here.
- **Phase 4 walk-back:** This whole `composable(Routes.Scanner) { ... }` block, plus the `ScannerScreen` file, will be replaced wholesale by the CameraX + ML Kit implementation. The `Routes.Scanner` constant survives (the route name is stable); everything inside the block does not. Don't add abstractions here that anticipate the Phase 4 shape — they will be wrong.

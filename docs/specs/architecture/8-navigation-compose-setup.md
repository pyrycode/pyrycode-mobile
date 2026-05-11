# Spec — Navigation Compose: Welcome → ChannelList Placeholder (#8)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:1-47` — current entry point. The `Greeting` Composable (lines 33-39) and `GreetingPreview` (lines 41-46) are deleted by AC2; the `Scaffold` body inside `setContent` (lines 22-27) is replaced with a `NavHost`. Keep `enableEdgeToEdge()`, `PyrycodeMobileTheme`, and `Scaffold(modifier = Modifier.fillMaxSize())` — they are still the right outer chrome.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:29-32` — `WelcomeScreen(onPaired: () -> Unit, onSetup: () -> Unit)` signature. This is the consumer; do not change it.
- `gradle/libs.versions.toml` — Compose BOM `2026.02.01` and `activityCompose = "1.8.0"` are already wired. Navigation Compose is **not** in the Compose BOM (separate `androidx.navigation` artifact group) so it needs its own `[versions]` entry and `[libraries]` alias.
- `app/build.gradle.kts:43-60` — dependency block. Add one `implementation(libs.androidx.navigation.compose)` line; do not touch the `androidTest`/`debug` blocks (no nav test deps requested by AC).
- `docs/specs/architecture/7-welcome-screen-scaffold.md` — prior ticket's spec; confirms `WelcomeScreen` is a pure stateless Composable with no `NavController` dependency, so wiring is a one-direction call from this NavHost into it.

## Context

Phase 0 UI scaffolding. `MainActivity` still renders the AGP-template `Greeting("Android")`; this ticket replaces that body with a `NavHost` rooted at the merged `WelcomeScreen` and adds a `channel_list` placeholder destination so that:

- **#14** can later swap the `onPaired` / `onSetup` lambdas for the QR-scanner route and an `Intent.ACTION_VIEW` browser launch without touching the NavHost shape.
- **The data-layer chain** can replace the `channel_list` placeholder with the real Channel List Composable without redoing the activity host.
- **#13** can later substitute a conditional start destination (Welcome vs. ChannelList based on pairing state from DataStore) by changing one argument to `NavHost(...)`.

The conditional start destination, the real Channel List UI, and the real CTA destinations are explicitly out of scope per the ticket body.

## Design

### Dependency wiring

**`gradle/libs.versions.toml`**

Add to `[versions]`:

```toml
navigationCompose = "2.9.5"
```

Add to `[libraries]`:

```toml
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
```

`2.9.5` is the recommended starting pin for Kotlin `2.2.x` + Compose BOM `2026.02.x` + compileSdk 36. If Gradle resolution fails or the build emits a Kotlin-compiler-version mismatch, bump to the latest stable `2.9.x` patch and re-run `./gradlew assembleDebug` — do not downgrade to `2.8.x` (it predates the Kotlin 2.2 toolchain alignment). Do not invent a `[bundles]` entry; one alias is enough.

**`app/build.gradle.kts`**

Add a single line to the `dependencies { ... }` block, alphabetically placed next to the other `androidx-*` implementation lines (sort by alias name so the catalog reads cleanly):

```kotlin
implementation(libs.androidx.navigation.compose)
```

Place it after `implementation(libs.androidx.lifecycle.runtime.ktx)` and before `implementation(libs.kotlinx.datetime)` — that's the alphabetic slot. No version literal inline; the catalog alias is the only allowed form (AC1).

### `MainActivity.kt` rewrite

Replace the entire current file. The new shape:

```kotlin
package de.pyryco.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.pyryco.mobile.ui.onboarding.WelcomeScreen
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PyrycodeMobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PyryNavHost(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun PyryNavHost(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Routes.Welcome,
        modifier = modifier,
    ) {
        composable(Routes.Welcome) {
            WelcomeScreen(
                onPaired = {
                    navController.navigate(Routes.ChannelList)
                },
                onSetup = {
                    // TODO(#14): launch external browser intent to pyrycode install docs.
                },
            )
        }
        composable(Routes.ChannelList) {
            Text("Channel list placeholder")
        }
    }
}

private object Routes {
    const val Welcome = "welcome"
    const val ChannelList = "channel_list"
}
```

Design notes:

- **`PyryNavHost` is `private`** — it's an implementation detail of `MainActivity`, not part of the project's public Composable surface. No preview is required (and a NavHost preview would be useless without a real `NavController`; previews of routed screens live with the screens themselves, like `WelcomeScreen`'s previews from #7).
- **`Routes` is a `private object` with string constants** — pinning the route strings in one place removes the typo class of bugs and makes the swap in #14 / #13 mechanical. Do **not** introduce a `sealed class Route` hierarchy or type-safe navigation (`navigation-compose` 2.8+ supports `@Serializable` route data classes); that's a future refactor, not in this ticket's scope, and would balloon the spec surface.
- **`onPaired` uses `navController.navigate(Routes.ChannelList)`** — no `popUpTo` / `launchSingleTop` options. Back from `channel_list` should return to `welcome` for this scaffolding ticket; #13 will revisit back-stack policy once the conditional start destination lands.
- **`onSetup` is a no-op with a `TODO(#14)` comment** referencing the GitHub issue per AC4. Do not throw, do not log, do not show a Toast — keep it inert. The comment is the only artifact.
- **The `channel_list` route body is literally `Text("Channel list placeholder")`** — no `Surface`, no `Scaffold`, no padding. The outer `Scaffold` in `MainActivity` provides the chrome; the route content is the bare placeholder text per AC5. Later tickets replace this `composable(Routes.ChannelList) { ... }` block wholesale.
- **`enableEdgeToEdge()` stays.** The outer `Scaffold` already passes `innerPadding` into the NavHost; both routes get correct system-bar insets via that path. `WelcomeScreen` also applies its own `systemBarsPadding()` (carried over from #7) — that's redundant with the `Scaffold` padding here, but harmless and out of scope to change. Do not modify `WelcomeScreen`.
- **Import the route constants by name, not by string literals at call sites.** `composable(Routes.Welcome)` and `navController.navigate(Routes.ChannelList)` — never `composable("welcome")` inline.

### Why `PyryNavHost` is a separate Composable

Inlining the `NavHost` block directly inside `setContent { ... }` would also satisfy the AC. The extracted Composable is preferred because:

1. It makes the `setContent` block read at a single level of abstraction (theme → scaffold → nav host), matching the "stateless host" pattern called out in the ticket's Technical Notes.
2. It moves `rememberNavController()` inside a Composable that is unambiguously called from a composition context — no risk of accidentally hoisting it to `MainActivity` field state in a later edit.
3. #13's eventual conditional-start-destination refactor touches one Composable's parameter list instead of editing inside `setContent { ... }`.

The cost is ~5 extra lines and one private symbol. Acceptable.

## State + concurrency model

- **`rememberNavController()`** survives configuration changes via Compose's saver. No `viewModelScope`, no `StateFlow`, no `LaunchedEffect`.
- **No back-stack tuning** for this ticket (no `popUpTo`, no `inclusive = true`, no `launchSingleTop`). Default `navigate(route)` semantics — push onto the stack.
- **No deep-link config** — `composable(Routes.ChannelList)` only, no `deepLinks = listOf(...)`.

## Error handling

N/A. No I/O, no fallible operations. `navigate(Routes.ChannelList)` only fails if the destination is missing from the graph, which is a programming error caught at first run.

## Testing strategy

Per the ticket's Technical Notes, navigation instrumentation tests are **optional and deferred**. Do **not** add `androidx-navigation-testing` to the catalog and do **not** write a `NavController` test for this ticket — Compose Navigation testing utilities need either a `TestNavHostController` or a full instrumented harness, both of which are non-trivial setup that the ticket explicitly says not to block on.

Verification gates for this ticket:

1. **`./gradlew assembleDebug` passes** — the only gate the AC requires. Run from the worktree root.
2. **Manual smoke check (optional, recommended):** `./gradlew installDebug` on an emulator or device, confirm the app boots into the Welcome screen, tap "I already have pyrycode", confirm the screen swaps to a blank surface displaying "Channel list placeholder", confirm system back returns to Welcome. If no device is available, skip — `assembleDebug` is the binding gate.

Do not introduce `ComposeTestRule` for this ticket. The unused `androidx-compose-ui-test-junit4` catalog entry stays unused until a later ticket needs it.

## Open questions

- **Should the secondary "Set up pyrycode first" button log somewhere while #14 is pending?** Spec says no — keep it inert with the `TODO(#14)` comment. The risk of a user tapping it and seeing nothing happen during Phase 0 is acceptable; this is internal scaffolding, not a release build.
- **Type-safe routes (`@Serializable` data classes) vs. string constants?** Spec picks string constants for this ticket because the destinations have no arguments. When the first parameterized route lands (e.g. `thread/{conversationId}`), revisit whether to migrate the whole `Routes` object to type-safe form — that's a future ticket, not this one.

## Out of scope (do not implement)

- Real Channel List UI — later ticket in the data-layer chain replaces the placeholder body.
- Scanner route + external-browser `Intent.ACTION_VIEW` — #14.
- Conditional start destination based on DataStore pairing flag — #13.
- Animation / transition customization (`enterTransition`, `exitTransition`).
- `sealed class Route` / type-safe navigation refactor.
- Navigation instrumentation tests.
- Touching `WelcomeScreen.kt` — its signature is the contract.

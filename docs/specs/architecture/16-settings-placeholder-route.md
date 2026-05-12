# Spec — Settings placeholder route in NavHost (#16)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:1-70` — the entire file. The `PyryNavHost` Composable (lines 35-64) is where the new `composable(...)` block is added, the `private object Routes` (lines 66-70) is where the new route constant is added, and the new stateless `SettingsPlaceholder` composable will be defined in this same file. This is the only file edited by this ticket.
- `docs/specs/architecture/15-conversation-thread-placeholder-route.md` — the immediately preceding placeholder-route ticket (just merged). Same shape: route constant + `composable(...)` block + bare placeholder body, no `Surface` / `Scaffold` inside the destination, no tests beyond `assembleDebug`. Differences for #16: unparameterized route, requires an interactive back-navigation affordance (so the destination body has more than one element and must be factored into a small stateless composable taking `onBack: () -> Unit`).
- `docs/specs/architecture/8-navigation-compose-setup.md:120-126,162` — the NavHost setup's carry-over invariants: (a) `Routes` is a `private object` of string constants, no `sealed class Route` / type-safe `@Serializable` form; (b) destination bodies are bare (no inner `Surface` / `Scaffold`) — the outer `Scaffold` in `MainActivity` already provides chrome. The open question on line 162 about migrating to type-safe routes remains punted (see "Why not type-safe routes" below).
- `gradle/libs.versions.toml` and `app/build.gradle.kts:42-50` — confirm no new dependency is required. The chosen back-affordance (`TextButton`) is part of `androidx.compose.material3:material3`, already on the classpath. **Do not** add `androidx.compose.material:material-icons-core` or `material-icons-extended` for this ticket (see "Back-navigation affordance" below for why).

## Context

#8 stood up the NavHost with two unparameterized destinations: `welcome` and `channel_list`. #15 (just merged at 4e8c4cf) added a third, parameterized `conversation_thread/{conversationId}` placeholder. This ticket adds a fourth, unparameterized destination — `settings` — so the future Channel List TopAppBar's gear icon (separate ticket) has a real route to call `navigate(...)` on.

The destination is intentionally inert: `Text("Settings placeholder")` plus a back-navigation affordance that pops the back stack. Phase 3 will replace the whole `composable(Routes.Settings) { ... }` block wholesale with the real Settings surface (Connection / Appearance / Notifications / Memory / About) — nothing inside this destination needs to survive.

The shape difference from #15 worth calling out: that ticket's placeholder body was a single `Text(...)` inline. This ticket has two elements (text + back affordance) and a callback `onBack: () -> Unit` to wire, which crosses the threshold where factoring a tiny stateless composable reads better than inlining a `Column { ... }` block inside `composable(Routes.Settings) { ... }`. The ticket body asks for this factoring explicitly ("Keep the placeholder Composable stateless — it receives an `onBack: () -> Unit`").

## Design

### Route constant

Add to the `private object Routes` in `MainActivity.kt`, after `ConversationThread`:

```kotlin
const val Settings = "settings"
```

Plain unparameterized string, same shape as `Welcome` and `ChannelList`. No path arguments, no deep-link pattern.

### Stateless placeholder composable

Add a new top-level `private` composable to `MainActivity.kt`, after the `PyryNavHost` function:

```kotlin
@Composable
private fun SettingsPlaceholder(onBack: () -> Unit) {
    Column {
        Text("Settings placeholder")
        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}
```

Design points:

- **`private` visibility.** This composable is a throwaway scoped to `MainActivity.kt`. It will be deleted when Phase 3 builds the real Settings screen. No reason to expose it.
- **Single `onBack: () -> Unit` parameter.** This is the entire surface area. No `Modifier` parameter — placeholder bodies in this file don't take one (see `composable(Routes.ChannelList) { Text(...) }` and `composable(Routes.ConversationThread) { ... Text(...) }`); the outer `Scaffold` in `MainActivity` already applies `innerPadding` at the `NavHost` boundary. Adding a `Modifier = Modifier` parameter for a placeholder is over-engineering for a file that gets rewritten.
- **`Column` layout.** Two children stacked vertically; the simplest layout that satisfies AC2 ("renders `Text("Settings placeholder")` and a back-navigation affordance"). No `Arrangement`, no `Modifier.padding(...)`, no `Modifier.fillMaxSize()`. The placeholder will look ugly — that is the point. Phase 3 replaces it.
- **No state.** Stateless per CLAUDE.md convention. The placeholder takes `onBack` and emits a click; nothing to remember, nothing to hoist.

### Back-navigation affordance

Use `androidx.compose.material3.TextButton`:

```kotlin
TextButton(onClick = onBack) {
    Text("Back")
}
```

**Why `TextButton("Back")` and not `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack`:**

The ticket's AC2 lists the icon variant as an example and explicitly allows "or equivalent Material 3 back-button pattern". `TextButton` is the chosen equivalent here for one reason: `Icons.AutoMirrored.Filled.ArrowBack` lives in the `androidx.compose.material:material-icons-core` artifact, which is **not** currently on the classpath. Verified two ways at architect time:

1. `./gradlew app:dependencies --configuration debugRuntimeClasspath | grep material-icons` returns no rows.
2. The `androidx.compose.material:material:1.10.4` artifact that is on the classpath (transitively, via the ripple chain) bundles only `androidx.compose.material.internal.Icons` — a private set, not the public `androidx.compose.material.icons.*` API. The public icon API requires adding `material-icons-core` or `material-icons-extended` to the catalog.

Adding a new catalog entry + dependency just so a throwaway placeholder can render a glyph that gets deleted in Phase 3 is wasted scope. The right ticket to add `material-icons-core` is the first one that ships durable UI needing it — almost certainly the Channel List TopAppBar ticket (which adds the gear icon that navigates here, plus likely a hamburger / search icon). That ticket can add the catalog entry, add the dependency to `app/build.gradle.kts`, and revisit any earlier `TextButton("Back")` placeholders.

`TextButton` is a real Material 3 button, ships inside `androidx.compose.material3:material3` already on the classpath, and satisfies AC2 verbatim ("equivalent Material 3 back-button pattern"). No new dependency, no catalog edit, no `gradle/libs.versions.toml` change.

**Do not** preemptively add `material-icons-core` in this ticket. That decision belongs to the TopAppBar ticket where the cost amortizes across multiple icons used in non-throwaway code.

### Destination registration

Inside `PyryNavHost`'s `NavHost { ... }` block, after the existing `composable(...)` entry for `Routes.ConversationThread`:

```kotlin
composable(Routes.Settings) {
    SettingsPlaceholder(onBack = { navController.popBackStack() })
}
```

Design points:

- **No `arguments = listOf(...)`.** The route is unparameterized — same shape as the `composable(Routes.ChannelList) { Text(...) }` line above. The `arguments` block is only required when the route pattern contains `{name}` placeholders.
- **`navController.popBackStack()` supplied at the registration site.** Per the ticket Technical Notes, the placeholder is stateless and the route block hands it the back action. This keeps `SettingsPlaceholder` unaware of `NavController` — it could be lifted into a `@Preview` or a test harness without dragging Navigation along. (Even though no test is written for this ticket, the constraint is a free property of the shape.)
- **`popBackStack()` return value is ignored.** `popBackStack()` returns a `Boolean` (false if the back stack was already at the start destination). For the Settings route as currently dispatched-to, this can't happen — there is no path where Settings is the start destination, and the route is not yet `navigate(...)`-able from anywhere (the gear icon wiring is out of scope per #16's Technical Notes). Discarding the return value is the right call; do not add `if (!navController.popBackStack()) { ... }` defensiveness. If a future caller can land on Settings as the start destination, the handling decision belongs to that caller's ticket.

### Required imports

Add to `MainActivity.kt`:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.TextButton
```

`androidx.compose.material3.Text` is already imported (line 10) — no change there. `androidx.compose.runtime.Composable` is already imported (line 11). No removal of imports; the parameterized-route imports added in #15 (`NavType`, `navArgument`) stay.

### Why not type-safe routes

Same answer as #15 §"Why not type-safe routes": #8 punted the migration to "the first parameterized route lands … revisit whether to migrate the whole `Routes` object to type-safe form — that's a future ticket, not this one." That punt landed in #15's spec. Adopting `@Serializable` data classes for one unparameterized route here would force a parallel structure (`Routes.Welcome` / `Routes.ChannelList` / `Routes.Settings` as `String`, `Routes.ConversationThread` as a data class) — strictly worse than either all-strings or all-data-classes. Stay all-strings. The migration ticket, if and when it happens, touches every `composable(...)` block and every future `navigate(...)` call in one pass.

## State + concurrency model

N/A. Same shape as #8 and #15: `rememberNavController()` survives configuration changes via the saver. `SettingsPlaceholder` is stateless — no `remember`, no `LaunchedEffect`, no `viewModelScope`, no `StateFlow`. The `onBack` lambda is captured by `composable { ... }`'s composition; it is stable enough for the recomposition surface this destination has (none) — no `remember(onBack) { ... }` indirection needed.

## Error handling

N/A. `popBackStack()` is a synchronous call that returns `Boolean`; it cannot throw under the navigation graph as registered. No I/O, no parse, no permission. The `Boolean` return value is intentionally discarded — see "Destination registration" above for why no fallback is wired in.

## Testing strategy

Per the ticket body and per the precedent from #8 / #15: **no tests beyond the manual programmatic-navigation check.** Do **not** add `androidx-navigation-testing` to the catalog and do **not** write a `TestNavHostController` test. The behaviour is exercised by downstream tickets that wire the gear icon to `navController.navigate(Routes.Settings)`.

Verification gates:

1. **`./gradlew assembleDebug` passes** — the only gate AC5 requires. Run from the worktree root.
2. **Manual programmatic-navigation check (optional, recommended for AC3 + AC4 confidence):** the simplest path is to temporarily change the `onPaired` lambda on `Routes.Welcome` from `navController.navigate(Routes.ChannelList)` to `navController.navigate(Routes.Settings)`, run `./gradlew installDebug`, tap "I already have pyrycode" on the Welcome screen, confirm the next screen renders `"Settings placeholder"` and a `"Back"` button, tap Back, confirm the Welcome screen restores. **Revert the lambda before committing.** If no device is available, skip — `assembleDebug` is the binding gate. Do not commit the temporary edit even if the smoke check passes.

Do not introduce `ComposeTestRule` or `androidx-navigation-testing` for this ticket.

## Open questions

- **Should the back affordance use an icon once `material-icons-core` is on the classpath?** Yes, but not in this ticket. The TopAppBar ticket that adds `material-icons-core` should also do a sweep of any `TextButton("Back")` placeholders (this one, plus any others added in the same wave) and swap them for `IconButton` + `Icons.AutoMirrored.Filled.ArrowBack` if those placeholders still exist at that point. If they've already been replaced by real screens by the time the TopAppBar ticket lands, no sweep is needed.
- **Should the placeholder include a `Modifier.fillMaxSize()` / `Modifier.padding(16.dp)` so the back button isn't crammed into the top-left corner?** No. Placeholder visual polish is wasted work — the screen exists to prove the route registers and pops back correctly, nothing more. Phase 3 builds the real layout.

## Out of scope (do not implement)

- Wiring the Channel List TopAppBar's gear icon to `navigate(Routes.Settings)` — separate ticket once the TopAppBar exists.
- Real Settings sections (Phase 3: Connection / Appearance / Notifications / Memory / About) and their ViewModels / preferences wiring.
- `androidx.compose.material:material-icons-core` catalog entry / dependency — defer to the TopAppBar ticket.
- `IconButton` + `Icons.AutoMirrored.Filled.ArrowBack` — see above; defer until icons are on the classpath.
- `TopAppBar` chrome around the placeholder. The outer `Scaffold` in `MainActivity` is the only chrome on screen, and Phase 3 will decide what TopAppBar shape (if any) the real Settings screen wants.
- Deep-link support, argument passing, or any non-`String` route shape for Settings.
- Migrating `Routes` to type-safe `@Serializable` data classes (separate ticket; do not start it here).
- Animation / transition customization (`enterTransition`, `exitTransition`) on the Settings destination.
- A `@Preview` for `SettingsPlaceholder`. The file currently has no `@Preview` machinery; don't start that pattern in a throwaway destination.
- Touching `WelcomeScreen.kt`, `channel_list` placeholder, `conversation_thread` placeholder, or any of the existing `Routes` constants.

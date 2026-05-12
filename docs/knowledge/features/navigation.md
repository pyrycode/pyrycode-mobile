# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots the app into the `welcome` route and provides the route graph that subsequent screens plug into. Currently four routes:

- **`welcome`** (start destination) — renders `WelcomeScreen` (#7).
- **`channel_list`** — placeholder `Text("Channel list placeholder")`; the data-layer chain replaces this body with the real Channel List Composable.
- **`conversation_thread/{conversationId}`** — placeholder `Text("Conversation thread placeholder: $conversationId")` proving the path argument round-trips (#15). Phase 2 replaces the body with the real thread UI (streaming markdown, code blocks, tool calls, session-boundary delimiters).
- **`settings`** — placeholder `SettingsPlaceholder(onBack = { navController.popBackStack() })` rendering `Text("Settings placeholder")` plus a `TextButton("Back")` (#16). Exists so the future Channel List TopAppBar gear icon has a real `navigate(...)` target before Phase 3 builds the actual Settings sections.

## How it works

The NavHost lives in a private `PyryNavHost` Composable inside `MainActivity.kt`. `setContent` reads top-down at one level of abstraction:

```kotlin
PyrycodeMobileTheme {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        PyryNavHost(modifier = Modifier.padding(innerPadding))
    }
}
```

`PyryNavHost` calls `rememberNavController()` and declares the graph:

```kotlin
NavHost(navController, startDestination = Routes.Welcome) {
    composable(Routes.Welcome) {
        WelcomeScreen(
            onPaired = { navController.navigate(Routes.ChannelList) },
            onSetup  = { /* TODO(#14): external browser intent */ },
        )
    }
    composable(Routes.ChannelList) { Text("Channel list placeholder") }
    composable(
        route = Routes.ConversationThread,
        arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
    ) { backStackEntry ->
        val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
        Text("Conversation thread placeholder: $conversationId")
    }
    composable(Routes.Settings) {
        SettingsPlaceholder(onBack = { navController.popBackStack() })
    }
}
```

Route strings are pinned in a colocated `private object Routes` (see `MainActivity.kt:81-86`). Call sites use `Routes.Welcome` / `Routes.ChannelList` / `Routes.ConversationThread` / `Routes.Settings`, never inline strings. Concrete navigation targets for parameterized routes are built inline at the call site (e.g. `navController.navigate("conversation_thread/$id")`); lift to a helper when a second caller appears.

Placeholder bodies follow a split rule: bare `Text(...)` stays inline inside the `composable { ... }` block; anything with a callback or more than one child (e.g. Settings, which needs an `onBack` button) factors out into a small `private @Composable` taking `() -> Unit` callbacks. Those placeholder Composables stay `NavController`-free — the registration site supplies `{ navController.popBackStack() }` or `{ navController.navigate(...) }`.

## Adding a route

1. Add a `const val MyRoute = "my_route"` (or `"my_route/{argName}"` for a parameterized route) to `Routes`. Keep the `{name}` placeholder inside the constant — the graph DSL consumes the literal pattern.
2. Add a `composable(Routes.MyRoute) { MyScreen(...) }` block inside `PyryNavHost`. For parameterized routes, declare arguments explicitly: `arguments = listOf(navArgument("argName") { type = NavType.StringType })`. `StringType` is the default, but the explicit form documents the type at the call site and isolates the swap point.
3. Extract path arguments via `backStackEntry.arguments?.getString("argName").orEmpty()` — the `?.` is for the type system; Compose Navigation guarantees presence before composition.
4. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** default `navigate(route)`. No `popUpTo`, no `launchSingleTop`. Revisit when #13 lands the conditional start destination.
- **Insets:** the outer `Scaffold` in `MainActivity` owns system-bar insets and passes them down via the NavHost's `Modifier.padding(innerPadding)`. Screens may apply their own `systemBarsPadding()` on top (harmless double-padding); don't refactor existing screens to drop it.

## Edge cases / limitations

- **No type-safe routes yet.** The first parameterized route (`conversation_thread/{conversationId}`, #15) landed on string constants by design — partially migrating one route while siblings stay as `String` is worse than either end-state. A full migration of `Routes` to `@Serializable` data classes remains a separate, larger future ticket; do not bundle it with a feature ticket.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
- **No navigation instrumentation tests.** `TestNavHostController` setup was deferred per #8. The unused `androidx-compose-ui-test-junit4` catalog entry waits for the ticket that needs it.
- **Pairing-state-conditional start destination is unticketed downstream work (#13).** Today the app always boots into `welcome`.
- **No icon-based back affordance yet.** The `settings` placeholder uses `TextButton("Back")` because `androidx.compose.material:material-icons-core` is not on the classpath, and adding it for throwaway placeholder UI is wasted scope. The first ticket that ships durable UI needing icons (Channel List TopAppBar — gear, etc.) should add the dependency and sweep any surviving `TextButton("Back")` placeholders.
- **`Routes.Settings` has no caller yet.** The destination is intentionally orphaned — the gear-icon wiring lives with the future Channel List TopAppBar ticket. Tapping Back from Settings while it's the start destination would no-op (`popBackStack()` returns `false`); under the current graph this is impossible since `welcome` is the start. The fallback is intentionally not wired.

## Related

- Ticket notes: `../codebase/8.md` (NavHost setup), `../codebase/15.md` (first parameterized route), `../codebase/16.md` (Settings placeholder + interactive-placeholder factoring rule)
- Specs: `docs/specs/architecture/8-navigation-compose-setup.md`, `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/16-settings-placeholder-route.md`
- Consumers: [Welcome screen](welcome-screen.md)
- Follow-ups: #13 (conditional start), #14 (real CTA destinations), data-layer chain (replaces `channel_list` body), Phase 2 thread UI (replaces `conversation_thread/{conversationId}` body), channel-list row → thread navigation wiring, Channel List TopAppBar (wires gear icon to `Routes.Settings` and adds `material-icons-core`), Phase 3 Settings sections (replaces `SettingsPlaceholder`)

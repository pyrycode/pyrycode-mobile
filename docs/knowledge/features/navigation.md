# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots the app into the `welcome` route and provides the route graph that subsequent screens plug into. Currently two routes:

- **`welcome`** (start destination) — renders `WelcomeScreen` (#7).
- **`channel_list`** — placeholder `Text("Channel list placeholder")`; the data-layer chain replaces this body with the real Channel List Composable.

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
}
```

Route strings are pinned in a colocated `private object Routes` (see `MainActivity.kt:57-60`). Call sites use `Routes.Welcome` / `Routes.ChannelList`, never inline strings.

## Adding a route

1. Add a `const val MyRoute = "my_route"` to `Routes`.
2. Add a `composable(Routes.MyRoute) { MyScreen(...) }` block inside `PyryNavHost`.
3. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** default `navigate(route)`. No `popUpTo`, no `launchSingleTop`. Revisit when #13 lands the conditional start destination.
- **Insets:** the outer `Scaffold` in `MainActivity` owns system-bar insets and passes them down via the NavHost's `Modifier.padding(innerPadding)`. Screens may apply their own `systemBarsPadding()` on top (harmless double-padding); don't refactor existing screens to drop it.

## Edge cases / limitations

- **No type-safe routes yet.** When the first parameterized route lands (e.g. `thread/{conversationId}`), reconsider whether to migrate `Routes` to `@Serializable` data classes. The current string-constant form is deliberate for argument-less destinations.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
- **No navigation instrumentation tests.** `TestNavHostController` setup was deferred per #8. The unused `androidx-compose-ui-test-junit4` catalog entry waits for the ticket that needs it.
- **Pairing-state-conditional start destination is unticketed downstream work (#13).** Today the app always boots into `welcome`.

## Related

- Ticket notes: `../codebase/8.md`
- Spec: `docs/specs/architecture/8-navigation-compose-setup.md`
- Consumers: [Welcome screen](welcome-screen.md)
- Follow-ups: #13 (conditional start), #14 (real CTA destinations), data-layer chain (replaces `channel_list` body)

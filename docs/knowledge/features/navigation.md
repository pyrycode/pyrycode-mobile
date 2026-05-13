# Navigation

Single-activity Compose Navigation host. `MainActivity` is the only Activity; all screens are `composable` destinations under one `NavHost`.

## What it does

Boots the app into the route that matches the persisted pairing state (`welcome` on a fresh install, `channel_list` once `AppPreferences.pairedServerExists` is `true`) and provides the route graph that subsequent screens plug into. Currently six routes:

- **`welcome`** (start destination when `pairedServerExists == false`) — renders `WelcomeScreen` (#7).
- **`scanner`** — renders `ScannerScreen` (#12); a tap-anywhere stub that flips `AppPreferences.setPairedServerExists(true)` and navigates to `channel_list` with the scanner popped from the back stack. Phase 4 replaces the body with real CameraX + ML Kit QR pairing. See [Scanner screen](scanner-screen.md).
- **`channel_list`** (start destination when `pairedServerExists == true`) — renders `ChannelListScreen` (#46) backed by `ChannelListViewModel` (#45); destination block resolves the VM via `koinViewModel<…>()`, collects state via `collectAsStateWithLifecycle()`, and translates `ChannelListEvent.RowTapped` inline into `navController.navigate("conversation_thread/$id")`. See [ChannelListScreen](channel-list-screen.md).
- **`discussions`** — renders `DiscussionListScreen` (#24) backed by `DiscussionListViewModel`; sibling of `channel_list` for the unpromoted tier. Destination block follows the same `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape and translates `RowTapped` into `navController.navigate("conversation_thread/$id")` *and* collects `vm.navigationEvents` in a `LaunchedEffect(vm)` for the same nav target (dual wiring — the VM-side path exists so a unit test can assert the nav target; see [DiscussionListViewModel](discussion-list-viewmodel.md)). `BackTapped → navController.popBackStack()`. Reached from the channel list's inline Recent-discussions section's "See all discussions (N) →" link (#69; previously the #26 pill) via `ChannelListEvent.RecentDiscussionsTapped → navController.navigate(Routes.DiscussionList)`. See [DiscussionListScreen](discussion-list-screen.md).
- **`conversation_thread/{conversationId}`** — placeholder `Text("Conversation thread placeholder: $conversationId")` proving the path argument round-trips (#15). Phase 2 replaces the body with the real thread UI (streaming markdown, code blocks, tool calls, session-boundary delimiters).
- **`settings`** — placeholder `SettingsPlaceholder(onBack = { navController.popBackStack() })` rendering `Text("Settings placeholder")` plus a `TextButton("Back")` (#16). Reached by the channel-list TopAppBar's gear `IconButton` (#21) via `ChannelListEvent.SettingsTapped → navController.navigate(Routes.Settings)`; Phase 3 replaces the body with the real Settings sections.

## How it works

The NavHost lives in a private `PyryNavHost` Composable inside `MainActivity.kt`. `setContent` gates `NavHost` composition on a one-shot read of `AppPreferences.pairedServerExists` (#13), painting a neutral `Surface` while DataStore's first emit is in flight:

```kotlin
PyrycodeMobileTheme {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        val appPreferences = koinInject<AppPreferences>()
        val paired: Boolean? by produceState<Boolean?>(
            initialValue = null,
            appPreferences,
        ) {
            value = appPreferences.pairedServerExists.first()
        }
        when (val v = paired) {
            null -> Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {}
            else -> PyryNavHost(
                startDestination = if (v) Routes.ChannelList else Routes.Welcome,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
```

`PyryNavHost(startDestination: String, modifier: Modifier = Modifier)` calls `rememberNavController()` and declares the graph against the forwarded start destination:

```kotlin
NavHost(navController, startDestination = startDestination) {
    composable(Routes.Welcome) {
        val context = LocalContext.current
        WelcomeScreen(
            onPaired = { navController.navigate(Routes.Scanner) },
            onSetup  = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(SetupUrl)),
                )
            },
        )
    }
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
    composable(Routes.ChannelList) {
        val vm = koinViewModel<ChannelListViewModel>()
        val state by vm.state.collectAsStateWithLifecycle()
        ChannelListScreen(
            state = state,
            onEvent = { event ->
                when (event) {
                    is ChannelListEvent.RowTapped ->
                        navController.navigate("conversation_thread/${event.conversationId}")
                    ChannelListEvent.SettingsTapped ->
                        navController.navigate(Routes.Settings)
                    ChannelListEvent.RecentDiscussionsTapped ->
                        navController.navigate(Routes.DiscussionList)
                    ChannelListEvent.CreateDiscussionTapped ->
                        vm.onEvent(event)
                }
            },
        )
    }
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

The `scanner` block is the first destination that resolves a Koin singleton (`koinInject<AppPreferences>()`) and remembers a `CoroutineScope` directly inside the `composable(...)` lambda — a deliberate one-shot shape for stub destinations that need a `suspend` side-effect without an observable state machine. Phase 4 will replace the whole block with a real `ScannerViewModel`-driven screen, so don't lift this pattern into a helper.

Route strings are pinned in a colocated `private object Routes` (see `MainActivity.kt:101-107`). Call sites use `Routes.Welcome` / `Routes.Scanner` / `Routes.ChannelList` / `Routes.ConversationThread` / `Routes.Settings`, never inline strings. Concrete navigation targets for parameterized routes are built inline at the call site (e.g. `navController.navigate("conversation_thread/$id")`); lift to a helper when a second caller appears.

Placeholder bodies follow a split rule: bare `Text(...)` stays inline inside the `composable { ... }` block; anything with a callback or more than one child (e.g. Settings, which needs an `onBack` button) factors out into a small `private @Composable` taking `() -> Unit` callbacks. Those placeholder Composables stay `NavController`-free — the registration site supplies `{ navController.popBackStack() }` or `{ navController.navigate(...) }`.

## Adding a route

1. Add a `const val MyRoute = "my_route"` (or `"my_route/{argName}"` for a parameterized route) to `Routes`. Keep the `{name}` placeholder inside the constant — the graph DSL consumes the literal pattern.
2. Add a `composable(Routes.MyRoute) { MyScreen(...) }` block inside `PyryNavHost`. For parameterized routes, declare arguments explicitly: `arguments = listOf(navArgument("argName") { type = NavType.StringType })`. `StringType` is the default, but the explicit form documents the type at the call site and isolates the swap point.
3. Extract path arguments via `backStackEntry.arguments?.getString("argName").orEmpty()` — the `?.` is for the type system; Compose Navigation guarantees presence before composition.
4. Wire the screen's navigation callbacks via `navController.navigate(...)` in the lambda passed from `PyryNavHost` — keep the screen Composable itself stateless and `NavController`-free.

Screens take navigation as `() -> Unit` callbacks, not a `NavController`. This is what lets routing and destination wiring land in parallel tickets (the #7 / #8 / #14 pattern).

## Configuration

- **Dependency:** `androidx.navigation:navigation-compose`, pinned via `navigationCompose` in `gradle/libs.versions.toml`. Compose BOM does **not** cover this artifact group — it needs its own version pin.
- **Back-stack policy:** default `navigate(route)` for most transitions. The `scanner` → `channel_list` transition is the lone exception: it uses `popUpTo(Routes.Scanner) { inclusive = true }` + `launchSingleTop = true` to drop the stub scanner from the back stack on success (so back-press from `channel_list` doesn't return to a fake camera). Combined with #13's conditional start destination, the returning-paired-user path also sidesteps Welcome entirely — back-press from `channel_list` exits the app in both entry paths (post-Scanner *and* cold launch on a paired install).
- **Start-destination gating:** `NavHost` composition itself is gated on `AppPreferences.pairedServerExists.first()` via `produceState` (#13). The `startDestination` parameter is captured at first composition and is *not* reactive — later flips of the flag (Scanner writing `true` mid-session) do not rewrite the back stack. Mid-session navigation continues through `navController.navigate(...)`, which is correct.
- **Insets:** the outer `Scaffold` in `MainActivity` owns system-bar insets and passes them down via the NavHost's `Modifier.padding(innerPadding)`. Screens may apply their own `systemBarsPadding()` on top (harmless double-padding); don't refactor existing screens to drop it.

## Edge cases / limitations

- **No type-safe routes yet.** The first parameterized route (`conversation_thread/{conversationId}`, #15) landed on string constants by design — partially migrating one route while siblings stay as `String` is worse than either end-state. A full migration of `Routes` to `@Serializable` data classes remains a separate, larger future ticket; do not bundle it with a feature ticket.
- **No deep links, no animations.** `composable(Routes.X) { ... }` only — no `deepLinks = listOf(...)`, no custom `enterTransition` / `exitTransition`.
- **No navigation instrumentation tests.** `TestNavHostController` setup was deferred per #8. The unused `androidx-compose-ui-test-junit4` catalog entry waits for the ticket that needs it.
- **`material-icons-core` now on the classpath (since #21) but `SettingsPlaceholder` still uses `TextButton("Back")`.** The TopAppBar gear ticket added `androidx.compose.material:material-icons-core` for `Icons.Default.Settings`; the sibling sweep of the Settings placeholder's `TextButton("Back")` → icon back-arrow was deliberately deferred (out of scope for the channel-list ticket). Low-priority sweep candidate; bundle with any Settings-touching follow-up.

## Related

- Ticket notes: `../codebase/8.md` (NavHost setup), `../codebase/12.md` (Scanner stub + first destination-block Koin/coroutine wiring), `../codebase/13.md` (conditional start destination + `produceState` gating), `../codebase/14.md` (Welcome `onSetup` → `Intent.ACTION_VIEW` + `LocalContext.current` capture in a `composable` block), `../codebase/15.md` (first parameterized route), `../codebase/16.md` (Settings placeholder + interactive-placeholder factoring rule), `../codebase/46.md` (first VM-backed destination — `koinViewModel<…>()` + `collectAsStateWithLifecycle()` shape, inline `when (event)` → `navigate` translation), `../codebase/21.md` (channel-list `SettingsTapped` → `Routes.Settings` wiring + `material-icons-core` on the classpath), `../codebase/24.md` (`discussions` route — first destination with dual nav wiring + a back-arrow `navigationIcon` reusing `R.string.cd_back`), `../codebase/26.md` (`discussions` route wired into the live graph — `ChannelListEvent.RecentDiscussionsTapped → navController.navigate(Routes.DiscussionList)`)
- Specs: `docs/specs/architecture/8-navigation-compose-setup.md`, `docs/specs/architecture/12-stub-scanner-screen.md`, `docs/specs/architecture/13-conditional-navhost-start-destination.md`, `docs/specs/architecture/15-conversation-thread-placeholder-route.md`, `docs/specs/architecture/16-settings-placeholder-route.md`, `docs/specs/architecture/21-channel-list-top-app-bar.md`
- Consumers: [Welcome screen](welcome-screen.md), [Scanner screen](scanner-screen.md), [App preferences](app-preferences.md) (read by the start-destination gate)
- Follow-ups: Phase 2 thread UI (replaces `conversation_thread/{conversationId}` body — `channel_list` → thread navigation is already wired through `ChannelListEvent.RowTapped` per #46), Phase 3 Settings sections (replaces `SettingsPlaceholder` body — gear → `Routes.Settings` wiring already in place since #21), Phase 4 (replaces `scanner` body with real CameraX + ML Kit), `SettingsPlaceholder` icon back-arrow sweep (`material-icons-core` now classpath-available; bundle with the next Settings-touching ticket)

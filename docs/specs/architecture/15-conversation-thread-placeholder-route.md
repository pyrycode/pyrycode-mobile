# Spec — ConversationThread placeholder route in NavHost (#15)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:1-60` — the entire file. The `PyryNavHost` Composable (lines 33-55) is where the new `composable(...)` block is added, and the `private object Routes` (lines 57-60) is where the new route constant is added. This is the only file edited by this ticket.
- `docs/specs/architecture/8-navigation-compose-setup.md:120-126` — design notes from the NavHost setup. Two carry-over invariants: (a) `Routes` is a `private object` of string constants, no `sealed class Route` / type-safe `@Serializable` form; (b) destination bodies are bare (no `Surface`, no `Scaffold`) — the outer `Scaffold` in `MainActivity` already provides chrome.
- `docs/specs/architecture/8-navigation-compose-setup.md:162` — "Open question" from #8 explicitly defers the type-safe-routes refactor until the first parameterized route lands. **This ticket is that first parameterized route.** The answer remains: stay with string constants for now. Migrating the whole `Routes` object to `@Serializable` data classes is a separate ticket, not bundled here.
- `gradle/libs.versions.toml:14,33` and `app/build.gradle.kts:53` — confirm `androidx-navigation-compose 2.9.5` is already on the classpath. No new dependency required.

## Context

#8 stood up the NavHost with two unparameterized destinations: `welcome` and `channel_list` (the latter a placeholder). This ticket adds a third destination, `conversation_thread/{conversationId}`, also placeholder, so that later tickets in this wave can wire channel-list row taps to a real `navigate(...)` target without simultaneously building the Phase 2 thread UI (streaming markdown, code blocks, tool calls, session-boundary delimiters).

The whole point of the destination is that the path argument round-trips correctly — the placeholder composable must display the received `conversationId` so a downstream developer (or a manual smoke check) can confirm the registration works end to end.

## Design

### Route constant

Add to the `private object Routes` in `MainActivity.kt`:

```kotlin
const val ConversationThread = "conversation_thread/{conversationId}"
```

Two reasons to keep the `{conversationId}` placeholder inside the constant rather than splitting it into a "base" string plus a path-builder helper:

1. The constant is consumed by `composable(...)` in the graph DSL — which requires the literal pattern with `{name}` placeholders. A "base" form would force a second concat at the registration site, making the registration read worse, not better.
2. Callers that build a concrete navigation target (e.g. a later ticket wiring channel-row taps) will write `"conversation_thread/$id"` inline. That is two extra characters compared to a helper function and saves an import. When the second or third caller appears, lift to a helper; not before.

### Destination registration

Inside `PyryNavHost`'s `NavHost { ... }` block, after the existing `composable(Routes.ChannelList) { ... }` entry:

```kotlin
composable(
    route = Routes.ConversationThread,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) { backStackEntry ->
    val conversationId = backStackEntry.arguments?.getString("conversationId").orEmpty()
    Text("Conversation thread placeholder: $conversationId")
}
```

Design points:

- **`arguments = listOf(navArgument(...) { type = NavType.StringType })`** is explicit rather than implicit. `NavType.StringType` is the default, so the explicit declaration is technically redundant. Keep it anyway — it documents the argument's type at the call site, makes a future swap to `NavType.LongType` / `IntType` (if `Conversation.id` ever becomes non-string) a one-token edit, and matches Compose Navigation idiom for parameterized routes. The cost is one extra line.
- **`backStackEntry.arguments?.getString("conversationId").orEmpty()`** — Compose Navigation always populates required path arguments before the composable enters composition, so the `?.` is for the type system, not a real runtime branch. `.orEmpty()` keeps the type as `String` (not `String?`) so the `Text(...)` interpolation reads cleanly. Do **not** add a `?: error(...)` or `requireNotNull(...)` — the registration guarantees presence; the framework contract is the source of truth.
- **The composable body is a bare `Text(...)`** — same shape as the existing `channel_list` placeholder. No `Surface`, no `Modifier.padding(...)`, no `Scaffold`. The outer `Scaffold` in `MainActivity` already provides chrome and the `innerPadding` is already applied at the `NavHost` boundary. Phase 2 will replace this `composable(Routes.ConversationThread) { ... }` block wholesale; nothing inside it needs to survive.
- **The displayed string MUST include the received `conversationId`.** AC2 requires navigating to `conversation_thread/abc-123` to render `abc-123` somewhere on screen — interpolating into the placeholder text satisfies this without ceremony. Wording is at the developer's discretion; the worked example above is fine. Do not display just the id (e.g. `Text(conversationId)`) — including the "Conversation thread placeholder:" prefix makes the placeholder origin obvious to anyone who lands on the screen during downstream debugging.

### Required imports

Add to `MainActivity.kt`:

```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
```

Both ship with `androidx.navigation:navigation-compose:2.9.5`, already on the classpath via `gradle/libs.versions.toml` line 33 / `app/build.gradle.kts` line 53. No catalog or build-file edits.

### Why not type-safe routes

`androidx.navigation:navigation-compose` 2.8+ supports `@Serializable` route data classes that eliminate string parsing and path-argument extraction. It is the strictly better long-term shape. It is **not** in scope here because:

1. #8 explicitly punted the migration to "the first parameterized route lands … revisit whether to migrate the whole `Routes` object to type-safe form — that's a future ticket, not this one."
2. Adopting `@Serializable` data classes for one route forces a parallel structure (`Routes.Welcome` is a `String`, `Routes.ConversationThread` is a `data class`), which is worse than either all-strings or all-data-classes.
3. The migration touches every existing `composable(...)` block, the `startDestination` parameter, and every `navigate(...)` call — that is a separate, larger ticket whose value is independent of #15's behaviour.

If a developer downstream feels strongly that the type-safe form is the right starting shape, the answer is to open a separate ticket that migrates **all** routes in one pass. Do not partially migrate inside #15.

## State + concurrency model

N/A. Same shape as #8: `rememberNavController()` survives configuration changes via the saver; no `viewModelScope`, no `StateFlow`, no `LaunchedEffect`. The path-argument extraction happens during composition off the `NavBackStackEntry` synchronously — no coroutines involved.

## Error handling

N/A. Path arguments declared as `NavType.StringType` and present in the route pattern are guaranteed non-null by the framework before the composable is composed. No I/O, no parse, no permission. A malformed `navigate("conversation_thread")` call (missing the id segment) would fail at `navigate(...)` time with an `IllegalArgumentException` — that is a caller programming error, not something this destination should defend against.

## Testing strategy

Per the ticket body: no tests beyond the manual programmatic-navigation check. Following the precedent from #8, do **not** add `androidx-navigation-testing` to the catalog and do **not** write a `TestNavHostController` test. The behaviour is exercised by downstream tickets that wire real navigation into this route.

Verification gates:

1. **`./gradlew assembleDebug` passes** — the only gate the AC requires. Run from the worktree root.
2. **Manual programmatic-navigation check (optional, recommended for AC2 confidence):** the simplest path is to temporarily change the `onPaired` lambda on `Routes.Welcome` from `navController.navigate(Routes.ChannelList)` to `navController.navigate("conversation_thread/abc-123")`, run `./gradlew installDebug`, tap "I already have pyrycode" on the Welcome screen, confirm the next screen displays `abc-123`. **Revert the lambda before committing.** If no device is available, skip — `assembleDebug` is the binding gate. Do not commit the temporary edit even if the smoke check passes.

Do not introduce `ComposeTestRule` or `androidx-navigation-testing` for this ticket.

## Open questions

- **Should the placeholder display anything besides the `conversationId`?** No — AC2 requires the id be rendered to prove the path argument round-trips; anything more is decoration that gets thrown away in Phase 2. The "Conversation thread placeholder: $conversationId" wording in the worked example is sufficient.
- **Should `Conversation.id` ever stop being a `String`?** Not in this ticket. `data/model/Conversation.kt` currently uses `String` ids (UUIDs from the eventual `conversations.json` registry on the pyrycode CLI side). If the type ever changes, this route's `NavType.StringType` declaration is the one place to update.

## Out of scope (do not implement)

- Real conversation thread UI (Phase 2: streaming markdown, code blocks, tool calls, session-boundary delimiters).
- Channel list row → thread navigation wiring (separate ticket later in this wave).
- ViewModel, repository wiring, message paging (Phase 2).
- Deep link / external URI handling.
- Migrating `Routes` to type-safe `@Serializable` data classes (separate ticket; do not start it here).
- A `Routes.conversationThread(id: String): String` helper. Add it when the second caller appears, not before.
- Animation / transition customization (`enterTransition`, `exitTransition`).
- Touching `WelcomeScreen.kt`, `channel_list` placeholder, `Routes.Welcome`, or `Routes.ChannelList`.

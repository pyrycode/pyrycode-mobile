# Spec — Channel list TopAppBar (#21)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` (full, 99 lines) — current stateless composable + `ChannelListEvent` sealed interface + `ChannelListScreenLoadedPreview`. This is the primary edit target.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:110-122` — the `composable(Routes.ChannelList) { ... }` block where `ChannelListEvent` is currently handled (`RowTapped` branch). Second edit target.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:130-132` — the `composable(Routes.Settings) { ... }` block confirming the destination route name (`Routes.Settings = "settings"` at line 153).
- `app/src/main/res/values/strings.xml` — confirms `app_name` resource already exists with value `"Pyrycode Mobile"`. Reuse it for the title; do **not** introduce a duplicate.
- `app/src/main/java/de/pyryco/mobile/ui/onboarding/WelcomeScreen.kt:60` — currently a literal `"Pyrycode Mobile"`. **Out of scope** for this ticket; do not refactor.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` — exercises the ViewModel only; it does not pattern-match on `ChannelListEvent`, so adding a sealed variant is non-breaking.

## Context

`ChannelListScreen` (#18) currently renders only a `LazyColumn` — no chrome. `Routes.Settings` (#16) is wired into the NavHost but has no entry point. Add a Material 3 `TopAppBar` with the app name as title and a single trailing settings-gear action. The gear flows through the existing `state`/`onEvent` contract: it emits a new `ChannelListEvent` variant that `MainActivity` translates into `navController.navigate(Routes.Settings)`.

## Design

### 1. `ChannelListScreen.kt`

Add a new sealed-interface variant:

```kotlin
sealed interface ChannelListEvent {
    data class RowTapped(val conversationId: String) : ChannelListEvent
    data object SettingsTapped : ChannelListEvent
}
```

Wrap the current `when (state) { ... }` body in a `Scaffold` whose `topBar` slot is a Material 3 `TopAppBar`:

- `title = { Text(stringResource(R.string.app_name)) }` — reuse the existing string resource; no new one needed.
- `actions = { IconButton(onClick = { onEvent(ChannelListEvent.SettingsTapped) }) { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_open_settings)) } }`.
- Use `TopAppBar` from `androidx.compose.material3` (not the deprecated `material` variant). Default colors / center-aligned-vs-small variant: use plain `TopAppBar` (small, leading-aligned title) to match Material 3 defaults — this is the project's first app bar so no existing convention to align with.
- The opt-in for Material 3 components is already enabled project-wide; no extra `@OptIn` annotation needed.

The Scaffold's inner-padding lambda parameter must be applied to the existing content (the `when` block). The cleanest form is to keep the existing `when` body and pass `modifier.padding(inner)` into each branch — or extract the `when` into a private `ChannelListBody(state, onEvent, modifier)` composable and call it as `ChannelListBody(state, onEvent, Modifier.padding(inner))`. **Pick the inline form** (no extraction); it keeps the diff small and the body short enough to stay readable.

The outer `modifier: Modifier = Modifier` parameter is applied to the `Scaffold` itself.

The chrome must render in **every** state (Loading / Empty / Loaded / Error) — that falls out naturally from wrapping the `when` inside the Scaffold body.

### 2. `MainActivity.kt`

In the `composable(Routes.ChannelList) { ... }` block, extend the `when (event)` to handle the new variant:

```kotlin
when (event) {
    is ChannelListEvent.RowTapped ->
        navController.navigate("conversation_thread/${event.conversationId}")
    ChannelListEvent.SettingsTapped ->
        navController.navigate(Routes.Settings)
}
```

The `when` becomes exhaustive on the sealed interface (compiler enforces). No `else` branch.

### 3. New string resource

Add to `app/src/main/res/values/strings.xml`:

```xml
<string name="cd_open_settings">Open settings</string>
```

`cd_` prefix marks it as a content-description string (a-11y). Title reuses the existing `app_name` — do **not** add a duplicate.

### 4. Preview

Update `ChannelListScreenLoadedPreview` (no new preview needed): the existing `ChannelListScreen(state = Loaded, onEvent = {})` call automatically renders the new chrome above the list, satisfying AC #5. No preview-specific code changes required.

## State + concurrency model

None. Pure stateless-composable change. No ViewModel, Flow, or coroutine touchpoints.

## Error handling

N/A — UI-only chrome change. The existing Loading / Empty / Error states continue to render below the new app bar via the Scaffold body.

## Recomposition correctness

- `Icons.Default.Settings` is a stable `ImageVector` singleton — safe to pass into `Icon` directly.
- The `onClick` lambda for `IconButton` captures `onEvent`. `onEvent` is a `(ChannelListEvent) -> Unit` parameter; lambdas that capture only stable parameters are themselves stable, no `remember` needed.
- `stringResource(...)` reads are cheap and recomposition-safe.

## Testing strategy

- **Unit tests (`./gradlew test`)** — existing `ChannelListViewModelTest` must still pass. It does not match on the sealed interface, so adding `SettingsTapped` is non-breaking.
- **No new tests required for this ticket.** AC #6 only requires existing tests to still pass; the project does not yet have a Compose UI test for `ChannelListScreen` (verified — no `ChannelListScreenTest` under `app/src/androidTest` or `app/src/test`). Don't introduce one as part of this XS ticket; that belongs to a separate test-coverage ticket if/when desired.
- **Manual verification scenarios** (developer should run `./gradlew assembleDebug` + visual preview check):
  - Loaded preview shows the app bar with title "Pyrycode Mobile" and a settings gear on the right.
  - Tapping the gear in a running build navigates to the Settings placeholder; back from Settings returns to the channel list (existing `popBackStack` in `SettingsPlaceholder` handles this).
  - App bar still renders in Loading / Empty / Error states (can be exercised by temporarily switching `ChannelListViewModel`'s initial state during dev, or trusted by inspection since the Scaffold wraps all branches uniformly).

## Open questions

None. All decisions are settled in the design above.

## Out of scope (do not touch)

- Refactoring `WelcomeScreen.kt:60`'s literal `"Pyrycode Mobile"` to `stringResource(R.string.app_name)`. Tempting because the architect's note in the AC mentions it, but the ticket's behavioral scope is the channel list. Don't pull that thread.
- Adding `CenterAlignedTopAppBar`, scroll behavior, or scroll-collapse — Material 3 defaults (small, leading-aligned) are sufficient.
- Adding a navigation icon (back arrow / drawer). The channel list is the start destination after pairing; it has no back target.

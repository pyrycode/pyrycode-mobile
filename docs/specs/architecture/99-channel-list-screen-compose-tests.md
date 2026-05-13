---
ticket: 99
title: Compose tests for ChannelListScreen
size: S
---

# Spec: Compose tests for `ChannelListScreen` (#99)

## Files to read first

- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — the canonical pattern: `@RunWith(AndroidJUnit4::class)`, `createComposeRule()`, `PyrycodeMobileTheme { … }` wrap, `hasText` / `hasContentDescription` matchers. Mirror imports and style.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/LicenseScreenTest.kt` — second example, especially the `InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_back)` idiom used to look up content descriptions in tests.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt:51-151` — the composable under test plus `ChannelListEvent` (lines 51–56). Note: the FAB is rendered for both `Loaded` and `Empty` (line 94); the empty-state placeholder text comes from `R.string.channel_list_empty` (line 122).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:18-32` — `ChannelListUiState` shape. `Empty` and `Loaded` both require `recentDiscussions: List<Conversation>` and `recentDiscussionsCount: Int`; tests construct minimal instances with empty discussions.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:38-71` — confirms the row's headline renders `conversation.name` directly as `Text`, so `hasText(channel.name!!)` matches.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — fields needed to build test fixtures (`id`, `name`, `cwd`, `currentSessionId`, `sessionHistory`, `isPromoted`, `lastUsedAt`).
- `app/build.gradle.kts:65-70` — already wires `androidTestImplementation(libs.androidx.compose.ui.test.junit4)` and `debugImplementation(libs.androidx.compose.ui.test.manifest)`. **No build-file change is required for AC #8.**
- `app/src/main/res/values/strings.xml` — confirm the string resource ids referenced below (`app_name`, `cd_open_settings`, `cd_new_discussion`, `channel_list_empty`) exist before the test compiles.

## Design source

N/A — testing infrastructure per ticket body; no Figma anchor needed.

## Context

`ChannelListScreen` is the only Phase 1 surface without instrumented coverage. ViewModel and repository layers are unit-tested, but Compose-side regressions (a removed FAB, a renamed content description, a broken click wiring) only surface at runtime. The screen is stateless — it takes `(state, onEvent)` — so the test does not need Koin, `ChannelListViewModel`, or `FakeConversationRepository`; it constructs `ChannelListUiState` instances directly and inspects `onEvent` invocations via a captured lambda.

## Design

### File location

`app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt`

Package: `de.pyryco.mobile.ui.conversations.list`

### Class shape

Single class `ChannelListScreenTest` with `@get:Rule val composeTestRule = createComposeRule()`, `@RunWith(AndroidJUnit4::class)`. No `@Before` shared setup needed — each test calls `composeTestRule.setContent { … }` itself, matching the existing pattern.

### Helper utilities (private to the test class)

- A small fixture builder, e.g. `private fun channel(id: String, name: String): Conversation`, that returns a promoted `Conversation` with sensible defaults (`cwd = "/tmp"`, `currentSessionId = id + "-s"`, `sessionHistory = emptyList()`, `lastUsedAt = Instant.fromEpochSeconds(0)`). Keeps each test's intent visible without reproducing the full constructor every time.
- An event-capture helper: a `mutableListOf<ChannelListEvent>()` plus a lambda `events::add` passed as `onEvent`. Inspect the list after `performClick()` for the asserted event.
- Look up content descriptions via `InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.…)` (per `LicenseScreenTest`). Same for the empty-state body string.

### Test cases (one `@Test` per acceptance criterion; bullet-pointed scenarios — developer writes the bodies)

1. **`topAppBar_rendersTitleAndSettingsAction_whenLoaded`** — set content with `Loaded(channels = listOf(channel("c1", "alpha")), recentDiscussions = emptyList(), recentDiscussionsCount = 0)`. Assert `hasText(<app_name>)` exists; assert `hasContentDescription(<cd_open_settings>)` is displayed.
2. **`channelList_rendersEachChannelName_whenLoaded`** — set content with `Loaded(channels = listOf(channel("c1", "alpha"), channel("c2", "bravo")), …)`. Assert each name (`"alpha"`, `"bravo"`) exists via `onNode(hasText(name)).assertExists()`. Use `assertExists()` (not `assertIsDisplayed()`) because rows live inside `LazyColumn`; off-screen nodes still exist in the semantic tree.
3. **`channelRow_emitsRowTappedWithId`** — same `Loaded` state; capture events; `onNode(hasText("alpha")).performClick()`; assert `events == listOf(ChannelListEvent.RowTapped("c1"))`.
4. **`fab_emitsCreateDiscussionTapped`** — set content with `Loaded` (or `Empty` — either works since the FAB renders for both per `ChannelListScreen.kt:94`; pick `Loaded` for consistency with case 3); `onNode(hasContentDescription(<cd_new_discussion>)).performClick()`; assert `events == listOf(ChannelListEvent.CreateDiscussionTapped)`.
5. **`settingsGear_emitsSettingsTapped`** — same `Loaded` state; click `hasContentDescription(<cd_open_settings>)`; assert `events == listOf(ChannelListEvent.SettingsTapped)`.
6. **`emptyState_rendersPlaceholder_whenEmpty`** — set content with `Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0)`; assert `hasText(<channel_list_empty>)` is displayed.

### State + concurrency model

None. The composable is synchronous; `composeTestRule.setContent { … }` plus immediate finder/assert is sufficient. No coroutine awaits, no `runTest`, no idling resources.

### Error handling

The `ChannelListUiState.Error` and `Loading` branches are intentionally **not** covered by this ticket — none of the acceptance criteria touch them. Adding them would scope-creep; defer to a follow-up if either branch sees regressions in practice. (Evidence-based fix selection — neither has been observed as a regression source.)

### Testing strategy

Instrumented (`./gradlew connectedDebugAndroidTest`) — `createComposeRule()` requires a real Android runtime. No Robolectric. The build wiring (`androidx.compose.ui.test.junit4` + `androidx.compose.ui.test.manifest`) is already in place at `app/build.gradle.kts:66,69`, so AC #8 resolves to "leave the build file alone."

Run locally with a connected emulator (API 33+ to match `minSdk = 33`):

```
./gradlew connectedDebugAndroidTest
```

The full instrumented suite (existing `SettingsScreenTest`, `LicenseScreenTest`, plus the new file) must remain green.

### Recompose / fixture caveats

- Constructing `Conversation` with `lastUsedAt = Instant.fromEpochSeconds(0)` is fine — `ConversationRow` formats relative time but doesn't crash on epoch-zero. Keep timestamps deterministic (no `Clock.System.now()`) so tests don't drift.
- Channel names in fixtures should be unique and unlikely to collide with other on-screen text (avoid `"Settings"`, `"Pyrycode"`, `"channels"`). `"alpha"` / `"bravo"` are safe.
- Don't pass empty/blank names — `ConversationRow` falls back to `"Untitled channel"` (line 39–40), which would defeat the rendering assertion.

## Open questions

None.

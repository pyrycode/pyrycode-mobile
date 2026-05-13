# Spec — `Recent discussions (N) →` pill on Channel list (#26)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` (full file, ~70 lines) — current `ChannelListUiState` shape and the single-flow `stateIn` pipeline; you'll widen this with a second flow.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` (full file) — Scaffold layout, `when (state)` body rendering, existing `@Preview`s to extend.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:116-140` — the `Routes.ChannelList` composable: how `ChannelListEvent`s map to `navController.navigate(...)` and where the discussions route name lives (`Routes.DiscussionList = "discussions"`, line 195).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-44` — `observeConversations(filter)` contract; reuse with `ConversationFilter.Discussions`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt` — `stubRepo(source)` helper currently returns one shared flow for every filter; this matters because the new ViewModel will subscribe twice (Channels + Discussions). See "Testing strategy" below.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt` — for visual weight calibration: the pill should sit visibly *below* a row without competing for primary attention.
- `app/src/main/res/values/strings.xml` — add the pill's user-visible string + content description here.

## Context

The Channel list (#18) shows promoted conversations. Discussions live behind a drilldown (#24). Without a visible entry on the main list, the drilldown is undiscoverable. Add a small pill below the channel list that shows the live discussions count and navigates to `Routes.DiscussionList` on tap.

## Design

### UiState shape

Widen `ChannelListUiState.Loaded` and `ChannelListUiState.Empty` to carry the count. Loading / Error don't need it — the pill is only meaningful once we know the data layer is healthy.

```kotlin
sealed interface ChannelListUiState {
    data object Loading : ChannelListUiState
    data class Empty(val recentDiscussionsCount: Int) : ChannelListUiState
    data class Loaded(
        val channels: List<Conversation>,
        val recentDiscussionsCount: Int,
    ) : ChannelListUiState
    data class Error(val message: String) : ChannelListUiState
}
```

**No default values.** Required positional params force the ViewModel to populate the count consistently and surface any missed call site at compile time. Existing call sites (5: two in `ChannelListViewModel`, two in `ChannelListScreen` previews, one in `ChannelListViewModelTest`) will need updates — trivial cascade.

### ViewModel

Combine two flows in `ChannelListViewModel.state`:

- `repository.observeConversations(ConversationFilter.Channels)` — existing
- `repository.observeConversations(ConversationFilter.Discussions).map { it.size }` — new

Use `combine(channelsFlow, discussionsCountFlow) { channels, count -> ... }` to fold both into a single `ChannelListUiState`. Keep the existing `.catch` (any throw from either upstream collapses to `Error`) and `.stateIn` (same `SharingStarted.WhileSubscribed` and `STOP_TIMEOUT_MILLIS`). Don't introduce a separate `StateFlow` for the count — the AC requires the count to live on `UiState`.

`ChannelListEvent` gains one case:

```kotlin
data object RecentDiscussionsTapped : ChannelListEvent
```

The ViewModel handles it as a no-op (same pattern as `SettingsTapped` — navigation is hoisted to `MainActivity`'s NavHost wiring).

### Composable

New file: `app/src/main/java/de/pyryco/mobile/ui/conversations/components/RecentDiscussionsPill.kt`. Stateless. Signature:

```kotlin
@Composable
fun RecentDiscussionsPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Behavior:

- If `count <= 0` → render nothing (early return). The screen always calls it; the guard is internal so the screen branches don't have to repeat it.
- Otherwise renders a Material 3 `Surface` with `tonalElevation` (or `AssistChip` — pick `Surface`+`Row` if `AssistChip`'s built-in chrome competes with the channel rows; otherwise `AssistChip` is fine) containing the text `Recent discussions (N) →`.
- Horizontal padding matches the surrounding `ConversationRow` so it visually aligns with the list.
- The trailing `→` is part of the label string (not a separate icon) — keep it as plain text per the AC's exact wording, no `Icons.AutoMirrored.Filled.ArrowForward`.
- Content description: a stringResource that conveys both the action and the count (e.g. `"Open recent discussions, %d available"`).

### Screen layout

`ChannelListScreen`'s body (`when (state)`) changes for `Loaded` and `Empty`:

- **`Loaded`** — wrap the existing `LazyColumn` in a `Column(bodyModifier.fillMaxSize())`. `LazyColumn` takes `Modifier.weight(1f)`; pill renders after it.
- **`Empty`** — wrap in a `Column(bodyModifier.fillMaxSize())`. Pill renders **first** (at the top, just below the TopAppBar), then the centered empty-state `CenteredText` with `Modifier.weight(1f)` so it stays centered in the remaining space.
- **`Loading` / `Error`** — unchanged; no pill.

This matches the AC: "below the LazyColumn (and above the empty state when the channel list is empty)".

The FAB-guard condition on line 63 (`state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty`) still works after `Empty` becomes a data class — `is` checks are unchanged.

### Navigation

In `MainActivity` `composable(Routes.ChannelList)`, extend the `onEvent` handler with one branch:

```kotlin
ChannelListEvent.RecentDiscussionsTapped ->
    navController.navigate(Routes.DiscussionList)
```

No back-stack manipulation needed; default push semantics are correct (user lands on Discussion drilldown; back returns to channel list).

### Strings (add to `strings.xml`)

- `recent_discussions_pill_label` = `"Recent discussions (%d) →"` — `String.format`-style, used via `stringResource(R.string.recent_discussions_pill_label, count)`.
- `cd_recent_discussions_pill` = `"Open recent discussions, %d available"` — content description.

Plurals not needed: "discussions" reads naturally for 1 and N (matches the Discord/Slack convention referenced in the ticket). If product later objects, swap to `<plurals>` — out of scope here.

## State + concurrency model

- One `viewModelScope`-bound `StateFlow<ChannelListUiState>` (existing). The new `combine` runs upstream of `.catch`/`.stateIn`, so error semantics are unchanged: any throw from either source collapses to `Error`.
- Both upstream flows are cold; `SharingStarted.WhileSubscribed(5_000)` keeps them shared across recompositions and rotation, with the 5-second grace period the rest of the app uses.
- Default dispatcher (`Dispatchers.Main.immediate` via `viewModelScope`) is correct — repository decides its own IO dispatcher under the hood.
- No new cancellation considerations; the screen exits → both upstream flows unsubscribe after the timeout.

## Error handling

- Failure in *either* upstream flow → `ChannelListUiState.Error(message)`. The user sees the existing error state; pill is not rendered (pill only shows for `Loaded`/`Empty`). This is the right tradeoff: if we can't trust the channels list, we shouldn't claim a count is accurate either.
- `count == 0` is not an error — it's a normal state where the pill silently absent.
- No new permission / IO / parse failure modes introduced.

## Testing strategy

### Unit (`./gradlew test`)

Extend `ChannelListViewModelTest`. The existing `stubRepo(source)` helper returns the same flow for **every** filter — that no longer matches the new ViewModel's behaviour (it now subscribes twice with different filters and the second subscription's emissions feed the count). Update `stubRepo` to accept *two* sources keyed by filter:

```kotlin
private fun stubRepo(
    channels: Flow<List<Conversation>>,
    discussions: Flow<List<Conversation>>,
): ConversationRepository
```

Old call sites (`stubRepo(source)`) become `stubRepo(source, emptyFlow())` or supply both. The `erroringRepo` helper can stay single-throwing (any subscription throws → `.catch` handles it).

New / updated scenarios as bullets — write them in the project's existing `runTest { ... advanceUntilIdle() ... assertEquals(...) }` idiom:

- **Loaded carries discussions count.** Channels flow emits one channel; discussions flow emits a list of 3 discussions. Expect `Loaded(channels = [one], recentDiscussionsCount = 3)`.
- **Empty carries discussions count.** Channels emits empty; discussions emits 2. Expect `Empty(recentDiscussionsCount = 2)`.
- **Count updates reactively.** Channels emits one channel; discussions emits 1, then 5. Collect `state` and assert the latest is `Loaded(..., recentDiscussionsCount = 5)`. (Mirror the existing `loaded_whenSourceEmitsNonEmpty` shape.)
- **Loading until both flows have emitted.** Neither source has emitted yet → `state.value == Loading`. Then channels emits; still `Loading` until discussions emits. Assert this — it's a `combine` guarantee but worth pinning so a future refactor (e.g. `combineTransform` or seeding one side) can't regress it silently.
- **Error collapses regardless of source.** When the channels flow throws → `Error`. Separately: when the discussions flow throws → `Error`. Two test methods. Reuse the `erroringRepo` pattern but parametrize which filter throws.
- **RecentDiscussionsTapped is a ViewModel no-op.** `vm.onEvent(RecentDiscussionsTapped)` does not crash, does not emit on `navigationEvents`, does not mutate `state`. (Mirrors how `SettingsTapped` is already a no-op — same shape as the existing test coverage there, even though there's no current assertion for it.)
- **Existing tests update to the new `Empty` / `Loaded` constructors.** The two `assertEquals(ChannelListUiState.Empty, ...)` / `assertEquals(ChannelListUiState.Loaded(...), ...)` lines become `Empty(0)` / `Loaded([...], 0)` when the discussions source emits empty.

### Compose / UI

Compose UI tests are not currently in the repo for this screen (no `ComposeTestRule` infrastructure in `app/src/androidTest/`); don't add it as part of this ticket. Coverage comes from:

- **Two new `@Preview`s in `ChannelListScreen.kt`:**
  - `ChannelListScreenLoadedWithPillPreview` — Loaded with 1 channel and `recentDiscussionsCount = 1`. AC explicitly calls out `N = 1`.
  - `ChannelListScreenEmptyWithPillPreview` — Empty with `recentDiscussionsCount = 5`. AC explicitly calls out `N = 5`.
- **One new `@Preview` in `RecentDiscussionsPill.kt`:**
  - `RecentDiscussionsPillPreview` — `count = 3` on the default theme. (Don't bother with the `count = 0` preview — the composable renders nothing, which Compose Studio won't show usefully.)
- Update existing `ChannelListScreenLoadedPreview` / `ChannelListScreenEmptyPreview` constructors to `Loaded(previewChannels, recentDiscussionsCount = 0)` and `Empty(0)` so they keep compiling and still represent the "no discussions" baseline.

## Open questions

- **Pill chrome (Surface vs AssistChip).** Left to implementer judgment per the PO note ("filled-tonal or elevated pill — pick what matches the existing Channel list visual weight"). Start with `Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium)` containing a `Row` with text. If the result looks heavier than a `ConversationRow`, switch to `AssistChip` with `elevatedAssistChipElevation()`. Document the final choice in the PR description.
- **Pluralization.** Deferred: a `<plurals>` resource for `recent_discussions_pill_label`. Not in this ticket. If product files a follow-up, the swap is mechanical (one line in `strings.xml`, one line in the composable).

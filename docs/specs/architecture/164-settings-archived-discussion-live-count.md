# Spec: Settings — live archived discussion count (#164)

## Context

The Storage section's "Archived discussions" row in `SettingsScreen` currently has no supporting text. Figma 17:2 specifies a supporting line `"N archived"`, where N is the live count of archived **unpromoted** conversations — the exact same set surfaced by `ArchivedDiscussionsScreen`.

The data is already available: `ConversationRepository.observeConversations(ConversationFilter.Archived)` is a cold `Flow<List<Conversation>>` that `ArchivedDiscussionsViewModel` consumes today, filtering with `!it.isPromoted` to drop archived channels. We mirror that filter so the count on the row and the list on the destination screen never disagree.

`SettingsViewModel` currently has no data-layer dependency; this ticket adds `ConversationRepository` as its second constructor argument and exposes a new `StateFlow<Int>`. `MainActivity.kt` (the only call site) collects the new flow and passes it through to `SettingsScreen`. DI in `AppModule.kt` already provides `ConversationRepository` to other ViewModels; one extra `get()` wires it into `SettingsViewModel`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=17-2

The Settings screen Storage section uses standard M3 `ListItem` rows. The "Archived discussions" row (node `17:93`/`17:94`) renders a body-large headline (`Schemes/on-surface`) over a body-small supporting line (`Schemes/on-surface-variant`) reading `"11 archived"` in the reference frame, with a trailing chevron — visually identical to other two-line rows like "Theme / System". No new icons, no color/typography tokens to add; supporting text simply switches the row to the two-line `ListItem` variant the row machinery already supports.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` — current shape: two `StateFlow`s built via `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue=…)`; new count flow uses the same pattern.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsScreen.kt:192-197` — the Storage section's Archived discussions row that gains supporting text. Lines 47-56: the composable signature that gains a new parameter. Lines 298-326: the two `@Preview` functions that must be updated to pass a placeholder count.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt:40-49` — the filter to mirror exactly (`ConversationFilter.Archived` + `.filter { !it.isPromoted }`).
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-58` — interface contract; `observeConversations(ConversationFilter.Archived)` and the `ConversationFilter` enum.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — the fake used in DI today; `SettingsViewModelTest` will instantiate it directly and drive it with `createDiscussion()` + `archive(id)` to set up scenarios.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:30` — `viewModel { SettingsViewModel(get()) }` becomes `viewModel { SettingsViewModel(get(), get()) }`. The `ConversationRepository` binding already exists on line 27.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:199-211` — the SETTINGS composable route. Add one `collectAsStateWithLifecycle` for the count and pass it to `SettingsScreen`.
- `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt` — existing test scaffolding (`UnconfinedTestDispatcher`, `Dispatchers.setMain`, `TestScope`, `runTest`, `advanceUntilIdle`). New count tests follow the same shape.
- `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt` — Compose test scaffold using `createComposeRule()` and `performScrollTo()`. New test follows the same shape.
- `app/src/main/res/values/strings.xml:25-29` — string resources for the settings rows; the new format string lives here.

## Design

### Data flow

```
ConversationRepository.observeConversations(ConversationFilter.Archived)
        │   Flow<List<Conversation>>
        ▼
SettingsViewModel.archivedDiscussionCount: StateFlow<Int>
        │   .map { it.count { c -> !c.isPromoted } }
        │   .stateIn(viewModelScope, WhileSubscribed(5_000), initialValue = 0)
        ▼
MainActivity SETTINGS route
        │   val count by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
        ▼
SettingsScreen(archivedDiscussionCount = count, …)
        │
        ▼
Storage › "Archived discussions" row
        supporting = stringResource(R.string.archived_discussions_count_supporting, count)
```

### `SettingsViewModel` changes

Add a second constructor parameter and a third public `StateFlow`. No sealed `UiState` — the existing VM already exposes separate primitive flows (`themeMode`, `useWallpaperColors`); a third flow matches that pattern. Introducing a `SettingsUiState` here would be premature abstraction.

Contract sketch:

```kotlin
class SettingsViewModel(
    private val appPreferences: AppPreferences,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    // existing themeMode, useWallpaperColors, onSelectTheme, onToggleUseWallpaperColors unchanged

    val archivedDiscussionCount: StateFlow<Int> = /* see "Flow shape" below */
}
```

**Flow shape.** Mirror `ArchivedDiscussionsViewModel.state`'s filter exactly — `observeConversations(ConversationFilter.Archived)` → `count { !it.isPromoted }` → `stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), initialValue = 0)`. Use the existing `STOP_TIMEOUT_MILLIS = 5_000L` companion constant. Add a `.catch { emit(0) }` upstream of `stateIn` so an error in the data layer falls back to "0 archived" rather than tearing down the flow — this row is supportive metadata, not the primary content of the screen, so swallowing the error and not surfacing a banner matches the screen's purpose. (Contrast: `ArchivedDiscussionsViewModel` surfaces an `Error` state because the list IS the screen's primary content.)

**Initial value.** `0`. The Figma empty-state behavior (row stays present, reads "0 archived") matches a perfectly valid value for an unsubscribed/loading state. No nullable sentinel; no "loading" placeholder.

### `SettingsScreen` changes

Add `archivedDiscussionCount: Int` as a new parameter (placed adjacent to `onOpenArchivedDiscussions` since both relate to the same row). Update the Storage-section row at line 192-197 from:

```kotlin
SettingsRow(
    headline = stringResource(R.string.archived_discussions_settings_row),
    trailing = { ChevronIcon() },
    onClick = onOpenArchivedDiscussions,
)
```

to additionally pass:

```kotlin
supporting = stringResource(
    R.string.archived_discussions_count_supporting,
    archivedDiscussionCount,
),
```

The existing `SettingsRow` composable (line 239) already accepts `supporting: String? = null` and renders it as `ListItem.supportingContent` — no row-machinery changes needed. The row stays clickable via `onClick = onOpenArchivedDiscussions` regardless of count.

Update both `@Preview` functions (`SettingsScreenLightPreview` and `SettingsScreenDarkPreview`) to pass a representative count (`archivedDiscussionCount = 11` to match Figma's reference frame, or `0` — pick one and apply to both; recommendation: `11` so the supporting text is visible in the preview pane).

### `MainActivity` changes

Inside the `composable(Routes.SETTINGS)` block (line 199-211), add one line:

```kotlin
val archivedDiscussionCount by vm.archivedDiscussionCount.collectAsStateWithLifecycle()
```

and pass it as `archivedDiscussionCount = archivedDiscussionCount` in the `SettingsScreen(…)` call. No other route or call site changes.

### `AppModule` changes

Single line: `viewModel { SettingsViewModel(get()) }` → `viewModel { SettingsViewModel(get(), get()) }`. Koin will resolve the second `get()` to the existing `ConversationRepository` binding on line 27.

### `strings.xml` changes

Add one new resource adjacent to `archived_discussions_settings_row`:

```xml
<string name="archived_discussions_count_supporting">%1$d archived</string>
```

Plurals (`<plurals name="…">`) are intentionally not used: the codebase has not yet adopted a plurals convention (existing rows like `"0 plugins"` are hardcoded), and English `"0 archived" / "1 archived" / "11 archived"` are all grammatical without inflection. If localization later requires different grammatical handling, the switch to a plurals resource is mechanical and isolated to this one string. YAGNI.

## State + concurrency model

- **Dispatcher.** `viewModelScope` (Main); `observeConversations` is a cold flow in the existing repo and any background work it does is the repo's concern.
- **Sharing.** `SharingStarted.WhileSubscribed(5_000L)` — identical to the other two flows in this VM. The 5 s grace lets configuration changes (rotation) avoid a tear-down/re-subscription churn.
- **Initial value.** `0`. Emitted synchronously by `stateIn` before the upstream emits; the row will render "0 archived" for the first frame and update once the repo's first emission arrives.
- **Cancellation.** All flows are scoped to `viewModelScope`; cleared automatically when the VM is cleared. No manual jobs to track.
- **Error handling.** `.catch { emit(0) }` upstream of `stateIn`. Rationale above. Code-side: the only realistic failure for the in-process `FakeConversationRepository` is bugs; Phase 4's real backend client may throw, and a settings row should not become a primary error surface for it.

## Testing strategy

### Unit (`./gradlew test`)

Append to `app/src/test/java/de/pyryco/mobile/ui/settings/SettingsViewModelTest.kt`. Reuse the existing dispatcher/`runTest` scaffolding. Instantiate `FakeConversationRepository()` directly (it's the same fake DI binds in `AppModule`); seed scenarios by calling its `createDiscussion()` and `archive(id)` suspend functions inside `runTest`. Constructor calls become `SettingsViewModel(prefs, repo)` everywhere — the existing nine theme/wallpaper tests need that one extra arg threaded through.

New test scenarios (one `@Test` each):

- **`archivedDiscussionCount_initialValue_isZero`** — fresh fake (no archived conversations); collect the flow; assert `vm.archivedDiscussionCount.value == 0`.
- **`archivedDiscussionCount_reflectsArchivedUnpromotedConversations`** — `createDiscussion()` × 3, then `archive(id)` on all three; `advanceUntilIdle`; assert count == 3.
- **`archivedDiscussionCount_excludesPromotedArchivedConversations`** — `createDiscussion()`, then `promote(id, "ch")`, then `archive(id)` (an archived channel); also create one unpromoted-archived discussion; assert count == 1 (the channel is excluded). This is the AC anti-double-counting check: matches `ArchivedDiscussionsViewModel`'s `!it.isPromoted` filter.
- **`archivedDiscussionCount_updates_whenConversationArchived`** — seed empty; assert 0; `archive(id)` an existing discussion; `advanceUntilIdle`; assert 1.
- **`archivedDiscussionCount_updates_whenConversationUnarchived`** — seed one archived discussion; assert 1; `unarchive(id)`; `advanceUntilIdle`; assert 0.

If `FakeConversationRepository`'s API doesn't expose a direct way to produce a promoted-archived channel (e.g. if `archive()` only works on un-promoted), use `promote(id, name)` first then `archive(id)` — verify the fake supports that chain by reading the file before writing the test. If it doesn't, drop the "excludes promoted" test and add a comment in the spec follow-up; the runtime guarantee is still upheld because `observeConversations(Archived)` would also include channels, and our `count { !it.isPromoted }` filters them out by construction.

The existing nine tests in this file need one mechanical update: every `SettingsViewModel(prefs)` call becomes `SettingsViewModel(prefs, FakeConversationRepository())`. This is a default-parameter-shaped cascade (10 sites), but all within a single test file and structurally identical — well within budget. (If preferred, extract a small `private fun makeVm(prefs: AppPreferences, repo: ConversationRepository = FakeConversationRepository()) = SettingsViewModel(prefs, repo)` helper to keep the existing tests one-arg.)

### Compose UI (`./gradlew connectedAndroidTest`)

Append to `app/src/androidTest/java/de/pyryco/mobile/ui/settings/SettingsScreenTest.kt`. New test:

- **`archivedDiscussionsRow_rendersSupportingTextWithCount`** — set content with `SettingsScreen(…, archivedDiscussionCount = 11, …)`; assert that a node with text `"11 archived"` exists (use `hasText(..., substring = true)` with `performScrollTo()`, following the existing tests' pattern). One representative non-zero count is sufficient per the ticket AC; the unit tests cover the value transitions.

The four existing tests in this file each need `archivedDiscussionCount = 0` (or any value) passed to `SettingsScreen(…)` to compile — mechanical update.

## Error handling

The only failure surface is the repository flow. Handled by `.catch { emit(0) }` as above. The supporting text always renders; never blank, never a placeholder, never an error message in this row. Bugs in the repo are caught by tests + the dedicated `ArchivedDiscussionsScreen` error UI (which IS the primary surface for archive errors); Settings is read-only metadata about that screen.

No network, no IO, no permissions involved at this phase (data layer is fake-only until Phase 4).

## Open questions

None blocking. One judgment call to flag to the implementer:

- **Test helper vs cascading constructor edits.** The nine existing `SettingsViewModelTest` tests call `SettingsViewModel(prefs)`. Two equally good choices: (a) update each call site to `SettingsViewModel(prefs, FakeConversationRepository())`, or (b) extract a `makeVm(...)` helper with a default `repo` arg. Either works; (b) is slightly more elegant if the tests later need to assert anything about repo isolation. Implementer's choice — neither is wrong.

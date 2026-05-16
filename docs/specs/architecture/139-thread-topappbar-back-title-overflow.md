# Spec — ThreadScreen TopAppBar (back + conversation name + overflow) (#139)

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:22-84` — the current screen + the **private** `ThreadTopBar` placeholder (lines 45-62) this ticket replaces. Both `@Preview` composables at the bottom must keep rendering after the swap.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:1-22` — the existing `ThreadUiState` (single field `conversationId`) and the `SavedStateHandle`-only VM. Both widen in this ticket.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-19, 69` — the `observeConversations(filter): Flow<List<Conversation>>` surface and the `ConversationFilter` enum. The VM consumes `ConversationFilter.All` here; **no new interface method**.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:32-46` — the `observeConversations` impl. Confirms `ConversationFilter.All -> true` returns *every* conversation (including archived) and that the flow re-emits on `state.update { ... }`. The seeded channel names (e.g. `"Personal"`, `"Pyrycode Mobile"`, `"Joi Pilates"`) and discussion `name = null` rows are what the new title field renders.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` — fields used by the title fallback: `name: String?`, `isPromoted: Boolean`.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt:164-166` — the **existing** `private fun Conversation.displayName(): String` extension already used by the archive screen. This ticket re-declares the same private extension inside `ThreadViewModel.kt`; see § Design / Display-name derivation for why we **do not extract to a shared helper** in this ticket.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:193-203, 247` — the `composable(Routes.CONVERSATION_THREAD)` destination block and the route constant. **Both stay unchanged** in this ticket. The new `onTitleClick` / `onOverflowClick` lambdas default to `{}` in the `ThreadScreen` signature, so the destination keeps passing only `state` and `onBack`.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt:12, 33` — the existing `ThreadViewModel(get())` binding; one-line edit to `ThreadViewModel(get(), get())` after the constructor widens.
- `app/src/main/res/values/strings.xml:7` — `cd_back` ("Back"); already exists, the new top bar keeps using it. **Add one new entry** `cd_more_actions` ("More actions") for the overflow icon (see § Design / Strings).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:1-29` — current three tests. All three need adapting because the constructor signature changes; new tests added for `displayName` behaviour (channel name, discussion fallback, missing id fallback, rename re-emit).
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModelTest.kt:1-60` — **canonical test idiom for a `stateIn(... WhileSubscribed)`-backed VM**: `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `resetMain()` in `@After`, body uses `runTest { val collector = launch { vm.state.collect {} }; advanceUntilIdle(); ... }`. The thread VM tests adopt this shape after the widening.
- `docs/specs/architecture/126-thread-screen-skeleton-nav-route-viewmodel.md` — the precursor spec that established the screen + VM shape. Its "Why a `data class`, not a `sealed interface`" note (lines 56-67) still applies; the widening here keeps `data class ThreadUiState`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Top App Bar region of node `16:8`: a single row inside the dark `Schemes/surface` background, three slots — a 48dp leading frame containing the back arrow icon (24dp, `on-surface`), a `titleLarge` text in `on-surface` (`#e0e2e8` dark) showing the conversation's display name, then a flex-spacer that pushes a 48dp trailing frame containing the `more_vert` overflow icon (24dp, `on-surface`) to the right edge. No elevation, no scroll-collapse behaviour, no actions row beyond the single overflow icon.

## Context

#126 stood up `ThreadScreen` with a `private ThreadTopBar` placeholder that renders `state.conversationId` (the UUID-shaped path argument) as the title. The ticket body of #126 explicitly deferred the real title plumbing so #126 could stay free of any data-layer reach. This ticket is the next slice: replace the placeholder with the real top bar from Figma node `16:8` and surface the conversation's `displayName` instead of the raw id.

The top bar exposes three callbacks:

- `onBack` — already wired by #126 to `navController.popBackStack()` from the destination block.
- `onTitleClick` — entry point for the rename dialog landing in **#141**.
- `onOverflowClick` — entry point for the context-aware overflow menu landing in **#140**.

Both new callbacks are **stub no-ops in this ticket** and downstream tickets replace them. There is no rename UI and no overflow menu rendering yet.

The Technical Notes in the ticket body offered three plumbing options for `displayName` (route nav arg, new `observeConversation(id)` on the repo, or filtering the existing `observeConversations(All)` flow inside the VM). This spec picks **option (c)** — see § Design / Why option (c) — and stays at **4 production `.kt` files modified or added**, within the S budget.

## Design

### Files (4 production `.kt` files)

| File | Action |
| --- | --- |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` | **New** — stateless `@Composable` with the real top bar. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` | **Edit** — drop the private `ThreadTopBar`, call `ThreadTopAppBar`, widen signature with two defaulted lambdas, update previews. |
| `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` | **Edit** — add `displayName` to `ThreadUiState`; widen constructor with `ConversationRepository`; switch state to a `stateIn(...)`-driven `StateFlow` derived from `observeConversations(All)`. |
| `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` | **Edit** — one-line change: `viewModel { ThreadViewModel(get(), get()) }`. |

Plus non-counting (resource + test) changes:

- `app/src/main/res/values/strings.xml` — add `cd_more_actions`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — adapt three existing tests, add new ones.

`MainActivity.kt` is **not** modified. The new lambdas have `{}` defaults; the destination block keeps its existing two-argument call to `ThreadScreen`.

### `ThreadTopAppBar` — new stateless composable

Signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Body shape (no full implementation — sketch only):

- `TopAppBar(modifier = modifier, ...)`.
- `navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back)) } }`.
- `title = { Text(text = title, modifier = Modifier.clickable(onClick = onTitleClick).semantics { role = Role.Button }) }`. The default `TopAppBar` title slot already renders `titleLarge` against `colorScheme.onSurface`, matching Figma `16:14`; **do not** override `style` or `color`. The `clickable` modifier carries the title-tap semantics (rename entry); `Role.Button` keeps TalkBack announcing it as activatable.
- `actions = { IconButton(onClick = onOverflowClick) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_actions)) } }`.

Design notes:

- **`TopAppBar` colors and elevation defaults are correct for this ticket.** Figma `16:8` uses `Schemes/surface` for the bar background, which matches `TopAppBarDefaults.topAppBarColors()` (= `colorScheme.surface`). Do **not** add `colors = TopAppBarDefaults.topAppBarColors(containerColor = ...)`.
- **No scroll behaviour.** Figma does not specify collapse-on-scroll or elevation overlay; `scrollBehavior` is left at the default. The Out-of-Scope section of the ticket explicitly excludes this.
- **No `windowInsets` override.** The inherited default already accounts for status bar inset because `MainActivity`'s outer `Scaffold` does not consume the top inset.
- **Clickable title vs. clickable Row.** The `clickable` modifier sits on `Text` rather than on a wrapping `Row` so the ripple aligns with the visible text bounds — wider than the text by a small `Material` ripple padding but never the whole title-slot column, which would feel sloppy in narrow titles. Acceptable tradeoff for the rename entry; #141 may revisit if the design moves to a "tap anywhere in the bar to rename" gesture.
- **Icon imports.** Reuse the icon family already used by `ThreadScreen.kt:7-8` (`androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.automirrored.filled.ArrowBack`). `MoreVert` lives at `androidx.compose.material.icons.filled.MoreVert` (non-automirrored).
- **The composable is `public` (default visibility).** It can be `@Preview`d directly in the same file if the developer wants to lock the top-bar shape independently of the screen; previews are still optional for this ticket.

The composable is **stateless** per the project convention. It owns no `remember { ... }` state, no `MutableState`, no Material `rememberSnackbarHostState`. All three callbacks are caller-owned.

### `ThreadUiState` — gain `displayName: String`

```kotlin
data class ThreadUiState(
    val conversationId: String,
    val displayName: String,
)
```

Field semantics:

- `conversationId` — unchanged. Still the path-arg id. Stays in `UiState` because downstream tickets (#128 message bubble) consume it as the key for `observeMessages(...)`; removing it now would force re-derivation.
- `displayName` — **always a `String`** (never `null`). Falls back through three layers (see § Display-name derivation): real `name`, "Untitled channel"/"Untitled discussion" string, then the raw `conversationId` if the lookup fails entirely. The `String` type means the `Text(title)` composable never needs a null check.

### `ThreadViewModel` — widen constructor; switch to `stateIn(...)`

Constructor signature:

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    repository: ConversationRepository,
) : ViewModel()
```

State pipeline (single `val state: StateFlow<ThreadUiState>`):

1. Read `conversationId: String = savedStateHandle.get<String>("conversationId").orEmpty()` once at construction.
2. Drive state from `repository.observeConversations(ConversationFilter.All).map { list -> ... }`.
3. Inside the `map`: `val conv = list.firstOrNull { it.id == conversationId }`, then build `ThreadUiState(conversationId, displayName = conv?.displayName() ?: conversationId)`.
4. Materialize as `StateFlow` via `.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = ThreadUiState(conversationId, displayName = conversationId))`.

Helper at file scope (same shape as the existing private extension in `ArchivedDiscussionsScreen.kt:164-166`):

```kotlin
private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
```

Design notes:

- **`SharingStarted.WhileSubscribed(5_000)`** — same lifetime policy as `ChannelListViewModel` / `DiscussionListViewModel`. Re-uses the upstream subscription across configuration changes (rotation) without leaking when the screen leaves the back stack.
- **`initialValue` falls back to `conversationId`.** Before the upstream flow's first emission lands, the AppBar renders the path id — the same visual fallback the placeholder used. The first emission arrives synchronously from `MutableStateFlow` inside the fake repo, so the placeholder is invisible in practice; it exists for type safety and process-death restoration.
- **`firstOrNull { it.id == conversationId }`** — `O(n)` scan per emission across the conversation list. For Phase 0 fakes this is at most ~5 records; for Phase 4 Ktor-backed implementations the cache layer or a future `observeConversation(id)` upgrade will tighten this. Acceptable now; flagged in Open Questions.
- **`ConversationFilter.All`** — includes archived rows. The thread screen is reachable via deep links (in future) and from the archive screen (also future); restricting to non-archived would silently break those paths. Same reasoning as the fake's `All -> true` branch — pass everything, filter by id.
- **`private fun Conversation.displayName()`** is **a duplicate** of the same private extension in `ArchivedDiscussionsScreen.kt:164-166`. See § Display-name derivation for why this ticket re-declares rather than extracts.
- **No `viewModelScope.launch { ... }` body.** The flow plumbing is declarative; `stateIn` owns the launch implicitly.
- **No `ThreadEvent` sealed type.** All three UI callbacks (`onBack`, `onTitleClick`, `onOverflowClick`) are direct `() -> Unit`s exposed by the screen, not VM-owned events. #140 and #141 introduce the dialog/menu state management at the destination block; whether they fold into a `ThreadEvent` sealed type is their call, not this ticket's.

### Display-name derivation — no extraction (yet)

`ArchivedDiscussionsScreen.kt:164-166` already defines:

```kotlin
private fun Conversation.displayName(): String =
    name?.takeIf { it.isNotBlank() }
        ?: if (isPromoted) "Untitled channel" else "Untitled discussion"
```

This ticket re-declares the **same** private extension at file scope in `ThreadViewModel.kt`. Decision rationale:

- **Extraction would push past the file-count red line.** Promoting to `internal fun` in a new file (e.g. `ui/conversations/ConversationDisplayName.kt`) plus deleting the private copy in `ArchivedDiscussionsScreen.kt` would make this a **5-`.kt`-file** spec, busting the S-budget self-check. The deterministic counter does not yield to "but it's only an extraction".
- **Two occurrences is below the canonical extract-on-third-use threshold.** When the third caller arrives (likely #140's overflow menu, which may show "Rename …" with the same fallback text), that ticket extracts.
- **The fallback strings (`"Untitled channel"` / `"Untitled discussion"`) are duplicated as Kotlin literals already.** `strings.xml` has `untitled_discussion` but the existing displayName extension does **not** use `stringResource(...)` — VMs have no `Context`, and the archive screen has been shipping with Kotlin literals since #176. Maintaining symmetry with the existing pattern is more important than tightening the string-resource layering in this ticket.

Open Questions captures the extraction follow-up.

### `ThreadScreen` — swap the placeholder, widen the signature, update previews

New signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
    modifier: Modifier = Modifier,
)
```

Body change:

- Replace the `topBar = { ThreadTopBar(title = state.conversationId, onBack = onBack) }` line with `topBar = { ThreadTopAppBar(title = state.displayName, onBack = onBack, onTitleClick = onTitleClick, onOverflowClick = onOverflowClick) }`.
- **Delete the private `ThreadTopBar` composable** (lines 45-62) outright. It was the placeholder; `ThreadTopAppBar` replaces it entirely.
- The `LazyColumn` body stays untouched (still `items(emptyList<Unit>())`).

Why `onTitleClick` / `onOverflowClick` default to `{}` rather than being hoisted to `MainActivity`:

- **The ticket body says "wired to no-op stubs in `ThreadScreen`".** Defaults at the call signature satisfy that literally — the stubs *live* in `ThreadScreen`'s signature.
- **`MainActivity` stays out of this ticket's file count.** #140 and #141 each own a separate destination-block edit (dialog visibility state, menu visibility state). Pushing one of those edits forward now buys nothing and risks a stale partial wiring.
- **Defaults of `() -> Unit = {}` are an established pattern in this codebase** (e.g. `ChannelListScreen`'s optional `onEvent` defaults).

Preview update: both `@Preview` composables (`ThreadScreenLightPreview`, `ThreadScreenDarkPreview`) widen the `ThreadUiState(...)` constructor to add `displayName = "kitchenclaw refactor"`. The seed string matches the Figma `16:14` content. The previews **do not** need to pass `onTitleClick` / `onOverflowClick` — the defaults apply.

Remove now-unused imports from `ThreadScreen.kt`:

- `androidx.compose.material.icons.Icons`
- `androidx.compose.material.icons.automirrored.filled.ArrowBack`
- `androidx.compose.material3.Icon`
- `androidx.compose.material3.IconButton`
- `androidx.compose.material3.Text`
- `androidx.compose.material3.TopAppBar`
- `androidx.compose.ui.res.stringResource`
- `de.pyryco.mobile.R` (only the `cd_back` reference is removed — verify ktlint flags the import)

If lint reports any of the above as still used (e.g. preview code keeps `R` for some other reason), keep them. The principle is "no dead imports", verified at build time.

### MainActivity destination block — unchanged

The block at `MainActivity.kt:193-203` keeps its current form:

```kotlin
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
    )
}
```

`onTitleClick` and `onOverflowClick` fall through to their `{}` defaults. #141 and #140 each add one named parameter here when they wire real handlers.

### Koin wiring

`app/src/main/java/de/pyryco/mobile/di/AppModule.kt:33` changes from:

```kotlin
viewModel { ThreadViewModel(get()) }
```

to:

```kotlin
viewModel { ThreadViewModel(get(), get()) }
```

The first `get()` resolves to `SavedStateHandle` (auto-injected by Koin from the current `LocalViewModelStoreOwner`, which is the `NavBackStackEntry`). The second `get()` resolves to the `ConversationRepository` singleton already bound at line 28 (`single { FakeConversationRepository() } bind ConversationRepository::class`).

No new imports — `ThreadViewModel` is already imported (line 12). No new dependencies. No `gradle/libs.versions.toml` edit.

### Strings

Add one entry to `app/src/main/res/values/strings.xml`:

```xml
<string name="cd_more_actions">More actions</string>
```

Naming follows the existing `cd_*` content-description convention (`cd_open_settings`, `cd_new_discussion`, `cd_back`, etc.). The string is intentionally generic ("More actions" rather than "Thread overflow") so #140's overflow on other screens (if any) can reuse it.

`cd_back` is unchanged and remains the back-icon content description.

## State + concurrency model

- **One `StateFlow<ThreadUiState>`** per VM. Hot via `stateIn(viewModelScope, WhileSubscribed(5_000), initialValue)`. Cold downstream from `repository.observeConversations(All)` until the first subscriber.
- **`WhileSubscribed(5_000)` retention.** Across screen rotations the upstream subscription survives because the VM survives; if the screen leaves the back stack and stays gone for 5s, the subscription cancels. The same lifetime as `ChannelListViewModel`/`DiscussionListViewModel` — no novelty here.
- **`Dispatchers.Main` for `map { ... }`.** No I/O is performed in the map block — just a list scan and a string fallback. `stateIn` consumes on the immediate dispatcher; no explicit `.flowOn(...)` is needed.
- **Rename re-emission (free).** When #141's rename success path calls `repository.rename(conversationId, newName)`, the fake updates `state` (line 156-161 of `FakeConversationRepository.kt`), and the upstream `observeConversations(All)` flow re-emits a list with the updated conversation. Our `.map { ... }` recomputes `displayName`; `stateIn` publishes the new `ThreadUiState`; `collectAsStateWithLifecycle()` triggers recomposition. **The "live rename" path the ticket body lists under Out-of-Scope is wired automatically by option (c)** — no extra plumbing required in #141 beyond calling `repository.rename(...)`.
- **Process-death restoration.** `SavedStateHandle` re-injects `conversationId` after process recreation. The VM constructor reads it identically on fresh start vs. restoration. `stateIn` re-subscribes on first `collectAsStateWithLifecycle()` call after restoration.
- **No `Channel`, no `MutableSharedFlow` for navigation events.** No one-shot side effects in this ticket.

## Error handling

N/A for this ticket.

- **`firstOrNull` returns `null`** if the path id matches no conversation in the upstream list. The fallback chain (`conv?.displayName() ?: conversationId`) collapses to the raw id — the screen still renders, just with an id-shaped title. Path is not user-reachable in production (every nav edge passes a real id), but the property is observable and the test pins it (`state_collapsesMissingConversationToConversationIdFallback`).
- **`SavedStateHandle.get<String>("conversationId").orEmpty()`** — same defensive `.orEmpty()` as #126. Compose Navigation guarantees the argument; the `.orEmpty()` is for the type system only.
- **No `.catch { ... }` on the upstream flow.** `observeConversations` is a fake-backed `MutableStateFlow.map { ... }` chain — it cannot throw. When the Phase 4 Ktor-backed impl arrives, a future ticket wraps the upstream with the project's standard error projection.
- **No `try/catch` in `displayName()`.** The extension is pure pattern matching on a non-null `Conversation`.

## Testing strategy

### `ThreadViewModelTest` — JVM unit tests

All three existing tests adapt because the constructor signature changes from `(SavedStateHandle)` to `(SavedStateHandle, ConversationRepository)`. Adopt the `runTest` + `UnconfinedTestDispatcher` + `Dispatchers.setMain/resetMain` idiom from `ChannelListViewModelTest:1-60`.

Tests (bullet-pointed scenarios, written in the existing JUnit style — the developer writes the function bodies):

- **`state_initialValue_isConversationIdPlaceholderBeforeSubscription`** — construct VM with a real `FakeConversationRepository()` (default seeds) and a `SavedStateHandle` carrying `"conversationId" to "seed-channel-personal"`. Without launching a `collect { }`, assert `vm.state.value == ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal")`. **Pins the `stateIn` `initialValue` contract.** No `runTest` needed for this one — `state.value` is synchronously readable before subscription.
- **`state_resolvedTitle_isChannelNameForSeededChannel`** — same setup. Wrap in `runTest { Dispatchers.setMain(UnconfinedTestDispatcher()); ... }`. Launch a collector, `advanceUntilIdle()`, assert `vm.state.value.displayName == "Personal"` (the seeded channel name for `seed-channel-personal`). **Pins the happy-path channel-name resolution.**
- **`state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion`** — same setup but `conversationId = "seed-discussion-a"` (seeded with `name = null`, `isPromoted = false`). After `advanceUntilIdle()`, assert `displayName == "Untitled discussion"`. **Pins the discussion fallback.**
- **`state_resolvedTitle_isUntitledChannelForUnnamedChannel`** — construct a `FakeConversationRepository(initialMessages = emptyMap())` and rely on a custom test repo or — simpler — pass a minimal `ConversationRepository` stub whose `observeConversations(All)` returns a `flowOf(listOf(Conversation(id = "x", name = null, isPromoted = true, ...)))`. After collect + idle, assert `displayName == "Untitled channel"`. **Pins the channel fallback.** (Both `name = null` and `name = ""` paths collapse via `name?.takeIf { it.isNotBlank() }` — one test exercising `null` covers both.)
- **`state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing`** — pass a stub whose `observeConversations(All)` returns `flowOf(emptyList())`, with `conversationId = "ghost-id"`. After collect + idle, assert `displayName == "ghost-id"`. **Pins the missing-conversation fallback.**
- **`state_displayName_reemitsOnRename`** — construct `FakeConversationRepository()` (default seeds), VM bound to `seed-channel-personal`. Collect into a list with `val emissions = mutableListOf<ThreadUiState>(); val job = launch { vm.state.collect { emissions += it } }; advanceUntilIdle()`. Then call `repository.rename("seed-channel-personal", "Personal — renamed")` inside the same `runTest` block. `advanceUntilIdle()`. Assert the last emission's `displayName == "Personal — renamed"`. **Pins the load-bearing re-emit promise** that #141 inherits "for free" from option (c).
- **`state_collapsesAbsentConversationIdToEmptyString`** — adapted from existing test. `SavedStateHandle(initialState = emptyMap())`, VM with default `FakeConversationRepository()`, assert `vm.state.value.conversationId == ""` synchronously. **Pins the `.orEmpty()` fallback** (path not user-reachable but contract-observable).

`SavedStateHandle` test artifact is already on `testImplementation` (transitively pulled via `lifecycle-viewmodel-savedstate`). No `gradle/libs.versions.toml` edit needed.

### Compose UI tests — out of scope

Same rationale as #126: the project has no `ComposeTestRule` infrastructure. Visual smoke verification is by:

1. `./gradlew installDebug` → tap a seeded channel row → AppBar shows the channel name (e.g. "Personal"), not the id.
2. Tap back arrow → returns to the channel list.
3. Tap title → no visible effect (no-op stub); confirm via TalkBack that the title announces as a button-role.
4. Tap overflow icon → no visible effect (no-op stub); confirm content description "More actions" via TalkBack.
5. Tap a seeded discussion row → AppBar shows "Untitled discussion".

The developer reports the smoke check in the PR description; code-review takes it as the AC5 (`./gradlew assembleDebug && ./gradlew lint` + previews) acceptance signal.

### Build + lint

`./gradlew assembleDebug` must pass. `./gradlew lint` must pass with no new warnings — both `@Preview` composables in `ThreadScreen.kt` must keep rendering under light + dark themes.

`./gradlew test` runs the adapted `ThreadViewModelTest` alongside the existing suite; all green.

## Why option (c) (and not (a) or (b))

| Option | Files touched | Tradeoff |
| --- | --- | --- |
| (a) Second nav arg `displayName` | Route constant + 4 call sites in `MainActivity.kt` + URL-encoding at each + ViewModel + ThreadUiState | URL-encoding for unicode / spaces / slashes at every call site, no live-rename re-emission, scatters the fallback string logic across call sites. |
| (b) New `observeConversation(id): Flow<Conversation?>` on repo | Interface + Fake impl + VM + state + screen + test = 5+ `.kt` files | Cleanest contract long-term; busts the S file-count red line (≥ 5). Defer to a separate ticket if needed. |
| **(c) Filter `observeConversations(All)` in VM** | VM + state + screen + new top-bar file + AppModule = 4 `.kt` files | One-call-line scan in VM, free rename re-emission via existing flow, no repo-surface change. **Picked.** |

Option (c) buys the live-rename re-emission as a side effect; option (a) cannot. Option (b) is the cleanest interface but spends a file budget on something option (c) achieves in the VM. When the Phase 4 Ktor-backed impl lands and the per-emission `firstOrNull` scan becomes a real cost, a follow-up introduces `observeConversation(id)` — at that point we have a real cause and a real cost to point at.

## Open questions

- **Extract `Conversation.displayName()` to a shared helper.** Two private copies now (`ArchivedDiscussionsScreen.kt:164` and `ThreadViewModel.kt`). When a third caller appears (likely #140's overflow menu rename label), the architect of that ticket extracts to `internal fun Conversation.displayName()` in a sibling file under `ui/conversations/` and deletes both private copies. Flagged here so it isn't forgotten.
- **`stringResource` for fallback titles.** `strings.xml:24` has `untitled_discussion` but the existing displayName extension uses a Kotlin literal. ViewModels have no `Context`, so any move to `stringResource` requires either pushing the fallback into the UI layer or passing a `Resources`-aware helper. **Not in scope here**; revisit if/when the app needs localization beyond English.
- **Title-tap ripple bounds.** Currently the ripple aligns with the visible `Text` bounds. If #141's UX shows the rename dialog from anywhere in the bar (not just on the title), this composable widens the clickable region to a wrapping `Row`. Design source `16:8` does not specify; this is #141's call.
- **Per-emission `firstOrNull` cost.** Acceptable for Phase 0 fakes; flagged for Phase 4. The follow-up is a dedicated `observeConversation(id)` repo method (option (b)).

# 252 — Mount ThreadOverflowMenu in ThreadTopAppBar and wire to ViewModel

## Context

Ticket #203 landed the stateless `ThreadOverflowMenu` composable (a Material 3 `DropdownMenu` rendering five `DropdownMenuItem`s in fixed order), the sealed `ThreadEvent` hierarchy (`NewSession`, `Rename`, `ChangeWorkspace`, `Archive`, `ChannelInfo`), and `ThreadViewModel.onOverflowEvent(event)` with the **Archive** path wired through to `ConversationRepository.archive(id)`. The four non-Archive cases route to empty handler bodies on the ViewModel — their full implementations land in #141 (rename dialog), #143 (workspace picker), #144 (channel info sheet), and a forthcoming new-session backend ticket.

The thread surface has a `MoreVert` overflow `IconButton` inside `ThreadTopAppBar`'s actions slot whose `onOverflowClick` is currently a no-op passed by `ThreadScreen` (which itself defaults the parameter — `MainActivity` does not bind anything). This ticket mounts the existing `ThreadOverflowMenu` composable next to the icon, gives `ThreadScreen` ownership of the menu's `expanded: Boolean` state via `rememberSaveable`, swaps `ThreadScreen`'s parameter surface from `onOverflowClick` to `onOverflowEvent: (ThreadEvent) -> Unit`, and adds the missing binding in `MainActivity`'s `composable(Routes.CONVERSATION_THREAD)` block.

The work is mechanical wiring. No ViewModel changes. No new strings (the menu items already use `R.string.thread_overflow_*`). No new dependencies.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Top-bar layout already implemented in `ThreadTopAppBar`: M3 `TopAppBar` with `ArrowBack` navigation icon (left), `title-large` text label (centre, clickable), and the `more_vert` `IconButton` in the actions slot (right). The Figma file does not include a dedicated frame for the open-menu state — the existing `ThreadOverflowMenu` composable owns that rendering. This ticket only anchors the menu inside the top-bar actions slot so it positions correctly below the icon per Material 3 conventions.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt:20-57` — current composable; you'll add three parameters and wrap the actions slot in a `Box`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt:11-59` — existing stateless menu. Its parameters (`expanded`, `onDismiss`, `onEvent`) are the contract the top bar feeds.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:51-77` — composable signature and the `topBar` slot call site you'll edit. Line 67 (`sheetVisible by rememberSaveable { mutableStateOf(false) }`) is the existing pattern for screen-owned saveable boolean state — mirror it for `overflowExpanded`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:267-456` — all four `@Preview` composables call `ThreadScreen(...)` without passing `onOverflowClick`, so removing the parameter doesn't break them; no preview edits needed.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:23-33` — `ThreadEvent` sealed interface (already exists, do not modify).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:133-145` — `onOverflowEvent(event)`; this is the function you'll bind in `MainActivity`. Do not modify.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:197-215` — the `composable(Routes.CONVERSATION_THREAD)` block whose `ThreadScreen(...)` call needs the new `onOverflowEvent = vm::onOverflowEvent` argument added.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenuTest.kt` — existing test patterns (test rule, `string()` helper using `InstrumentationRegistry`, dismiss-then-event ordering assertion shape, `PyrycodeMobileTheme` wrapper). Mirror the same conventions in the new `ThreadScreenOverflowTest`.

## Design

Three production-file edits + one new test file. No new packages, no new types.

### 1. `ThreadTopAppBar.kt` — add menu anchor + three parameters

Add three new parameters (placement: after `onOverflowClick`, before `modifier`):

```kotlin
@Composable
fun ThreadTopAppBar(
    title: String,
    onBack: () -> Unit,
    onTitleClick: () -> Unit,
    onOverflowClick: () -> Unit,
    overflowExpanded: Boolean,
    onOverflowDismiss: () -> Unit,
    onOverflowEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

The actions slot wraps the existing `IconButton` and the newly mounted `ThreadOverflowMenu` in a `Box` so the M3 `DropdownMenu` anchors below the icon. Signature only (developer writes the body following the existing pattern at `ThreadTopAppBar.kt:48-55`):

- `actions = { Box { IconButton(onClick = onOverflowClick) { Icon(MoreVert, …) }; ThreadOverflowMenu(expanded = overflowExpanded, onDismiss = onOverflowDismiss, onEvent = onOverflowEvent) } }`

Why `Box` and not the existing `Row` implicit in M3's `actions` slot: M3's `DropdownMenu` anchors to its parent layout. Putting both children inside a `Box` whose only content is the icon makes the menu open directly below the icon (the canonical M3 pattern). Adding the `DropdownMenu` as a sibling of the `IconButton` directly inside the `actions` `Row` also works but anchors against the row's bounds, which is wrong here (single-icon actions). Use `Box`.

Imports to add: `androidx.compose.foundation.layout.Box` (likely already imported elsewhere — verify).

### 2. `ThreadScreen.kt` — own expanded state, swap one parameter

Replace the existing `onOverflowClick: () -> Unit = {}` parameter on line 61 with `onOverflowEvent: (ThreadEvent) -> Unit = {}`. (The parameter is **renamed in place** so callers with defaults don't break; only `MainActivity` adds a new binding.)

Inside the function body, mirror the existing `sheetVisible` pattern at line 67:

```kotlin
var overflowExpanded by rememberSaveable { mutableStateOf(false) }
```

Update the `ThreadTopAppBar(...)` call inside the `topBar = { … }` slot (currently lines 71-76) to pass the three new args:

- `onOverflowClick = { overflowExpanded = true }`
- `overflowExpanded = overflowExpanded`
- `onOverflowDismiss = { overflowExpanded = false }`
- `onOverflowEvent = onOverflowEvent`

`overflowExpanded` lives in `ThreadScreen` (not the ViewModel) by design — it is local UI state, scoped to the screen instance, must survive configuration changes (hence `rememberSaveable`), and has no semantic meaning at the data/repository layer. Same scope as `sheetVisible` for `StatusSheet` on line 67.

Imports to add: `de.pyryco.mobile.ui.conversations.thread.ThreadEvent` is already in the same package, no import needed. `mutableStateOf`, `setValue`, `getValue`, and `rememberSaveable` are already imported (used by `sheetVisible`).

### 3. `MainActivity.kt` — bind `onOverflowEvent`

In the `composable(Routes.CONVERSATION_THREAD)` block (lines 197-215), add a single argument to the `ThreadScreen(...)` call:

- `onOverflowEvent = vm::onOverflowEvent`

Position it next to the other ViewModel-bound callbacks (after `onRetry = vm::retry` and before `onModelSelected = vm::onModelSelected` reads cleanly). No other changes to `MainActivity` — the previous comment in the ticket about "removing onOverflowClick binding" is moot because there is no current binding to remove (the parameter currently defaults).

Imports to add: `de.pyryco.mobile.ui.conversations.thread.ThreadEvent` is not needed in `MainActivity` because the binding is a method reference; the type is inferred. Do not add an unused import.

### 4. New test file: `ThreadScreenOverflowTest.kt`

Path: `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenOverflowTest.kt`.

This is an instrumented Compose test (`androidTest/`, not unit `test/`) because `DropdownMenu` requires a real Android `Activity`/window for popup-window positioning — the existing `ThreadOverflowMenuTest` is also in `androidTest/` for the same reason.

Follow the conventions of `ThreadOverflowMenuTest.kt`:
- `@RunWith(AndroidJUnit4::class)` + `createComposeRule()`
- `string(resId: Int)` helper via `InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)`
- Wrap `setContent` in `PyrycodeMobileTheme { ThreadScreen(...) }`
- Build a minimal `ThreadUiState` with `displayName = "Test channel"`, `isPromoted = true`, `hasMessages = false` (or a tiny `items` list — `hasMessages = false` simplifies layout, no need for a `LazyColumn`)
- Pass `connectionState = ConnectionState.Connected`, `onBack = {}`, `onSendMessage = {}`, `onRetry = {}`. For `onOverflowEvent`, pass a lambda that appends the event to a `mutableListOf<ThreadEvent>()`.

### Test scenarios (developer writes the test bodies; spec defines the contracts)

These map 1:1 to AC #4. Use the same `cd_more_actions` content description and `R.string.thread_overflow_*` labels as `ThreadOverflowMenuTest`.

| Scenario | Setup | Action | Assertion |
|---|---|---|---|
| **Tap overflow icon → menu shows all five items** | `expanded` defaults `false`; capture nothing | Click node with `contentDescription = cd_more_actions` | All five menu item labels (`thread_overflow_new_session`, `_rename`, `_change_workspace`, `_archive`, `_channel_info`) are displayed. |
| **Tap NewSession → fires NewSession, menu closes** | Capture events in `events: MutableList<ThreadEvent>` | Click overflow icon; click `thread_overflow_new_session` | `events == listOf(ThreadEvent.NewSession)`; `thread_overflow_new_session` is no longer displayed. |
| **Tap Rename → fires Rename, menu closes** | Same | Click overflow icon; click `thread_overflow_rename` | `events == listOf(ThreadEvent.Rename)`; menu closed. |
| **Tap ChangeWorkspace → fires ChangeWorkspace, menu closes** | Same | Click overflow icon; click `thread_overflow_change_workspace` | `events == listOf(ThreadEvent.ChangeWorkspace)`; menu closed. |
| **Tap Archive → fires Archive, menu closes** | Same | Click overflow icon; click `thread_overflow_archive` | `events == listOf(ThreadEvent.Archive)`; menu closed. |
| **Tap ChannelInfo → fires ChannelInfo, menu closes** | Same | Click overflow icon; click `thread_overflow_channel_info` | `events == listOf(ThreadEvent.ChannelInfo)`; menu closed. |
| **Back-press dismisses open menu without firing an event** | Capture events | Click overflow icon; press back (`Espresso.pressBack()` or `composeTestRule.activity.onBackPressedDispatcher.onBackPressed()`) | `events.isEmpty()`; menu items no longer displayed. |

Notes:
- AC #4(c) says "tapping outside the open menu dismisses it." In Compose tests, simulating a literal tap outside the popup scrim is brittle because `DropdownMenu` renders into a separate platform window. Back-press exercises the same `onDismissRequest` path (it's the documented M3 dismiss surface) and is deterministic — acceptable as the dismissal proof. If the developer prefers a literal outside-tap, that's fine too; the contract is "menu dismissed AND no event fired."
- Each "menu closed" assertion uses `assertDoesNotExist()` (not `assertIsNotDisplayed()`) on one of the menu item nodes, because the dropdown removes its content from composition on dismiss rather than just hiding it.

## State + concurrency model

- `overflowExpanded: Boolean` is local UI state owned by `ThreadScreen`, stored via `rememberSaveable { mutableStateOf(false) }`. Lifecycle: same as the screen composition. Survives configuration changes (rotation, dark/light theme toggle). Lost on process death only after the screen is also off the back stack — acceptable; the menu has no persistent semantics.
- No coroutines, no flows, no `LaunchedEffect` introduced by this ticket. All state transitions are synchronous lambdas (click handler → `expanded = true`; dismiss → `expanded = false`; menu item tap → `onDismiss(); onEvent(...)`, both already implemented inside `ThreadOverflowMenu`).
- `ThreadViewModel.onOverflowEvent` (already exists) is the only async surface; `viewModelScope.launch` for the Archive case is unchanged.

## Error handling

N/A. No failure modes introduced by this ticket. `ThreadOverflowMenu` cannot fail. `ThreadViewModel.onOverflowEvent` swallows non-Archive events (intentional, per #203); the Archive failure path is the existing `repository.archive` contract and outside this ticket's scope.

## Testing strategy

- **New instrumented test:** `ThreadScreenOverflowTest.kt` per § Design step 4 — covers AC #4 (a)/(b)/(c).
- **Unchanged unit tests:** `ThreadViewModelTest.kt`, `ThreadScreenCutoffTest.kt` — no changes expected; if they assert against the `onOverflowClick` parameter name, update the references but no behavioural changes.
- **Unchanged instrumented tests:** `ThreadOverflowMenuTest.kt` is the unit-of-composable test for `ThreadOverflowMenu`; this ticket adds the surface-integration test that asserts the screen mounts and wires the menu correctly.
- Commands:
  - `./gradlew test` — unit tests; should pass without changes (or with minor reference-name updates if any).
  - `./gradlew connectedAndroidTest` — instrumented tests including the new file. Requires a connected device or emulator.
  - `./gradlew lint` — Android Lint.

## Open questions

None. The contract for the four non-Archive `ThreadEvent` cases (which ViewModel does nothing today and will eventually wire to dialogs/sheets/backend) is owned by #141, #143, #144, and the new-session ticket — out of scope here.

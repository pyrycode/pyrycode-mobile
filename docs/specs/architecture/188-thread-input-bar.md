# 188 — Thread input bar (text input + send + voice stub)

## Context

The thread screen ([ThreadScreen.kt:14](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt)) currently has a top app bar and an empty `LazyColumn` body. Ticket #187 added `ConversationRepository.sendMessage(conversationId, text)` to the data layer and wired the fake. This ticket adds the bottom-of-screen input bar: a text field, a filled-tonal send button, and a stub mic icon (voice input ships in Phase 6).

The text field's input is purely UI-local — it never reaches `ThreadUiState`. The ViewModel's only new responsibility is forwarding non-blank text to the repository.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The composer (subframe `16:61`) is a `Row` with two children: a rounded-28dp pill (bg `surfaceContainerHigh`, height 48dp, weight 1f) containing the text field on the left and an `Icons.Outlined.Mic` button on the right (40dp tap target, 22dp icon); and a separate circular 48dp send button (also bg `surfaceContainerHigh`, `Icons.Outlined.ArrowUpward` 22dp icon) to the right of the pill. The composer row has 8dp gap, 12dp horizontal padding, 8dp top / 12dp bottom padding, and a 1dp top border in `outlineVariant`.

**AC reconciliation.** The ticket's AC #2 prose ("mic on the left, TextField in the middle, send on the right") describes a different ordering than Figma. Figma is canonical — follow the layout above. The AC's *structural* claims (mic `IconButton` exists with Toast onClick, multi-line text field with IME action Send, filled-tonal send `IconButton` disabled when blank) all hold under the Figma layout.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` (entire file, 75 lines) — current Scaffold shape and preview pattern to extend.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` (entire file, 49 lines) — current ViewModel; `sendMessage` is added here. Note `repository` is currently an unmarked constructor param used only inline in the `state` initializer; promote to `private val` so the new method can call it.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:57-66` — `sendMessage` contract: `suspend`, returns `Message`, throws `IllegalArgumentException` on unknown id (out of scope here; the ViewModel only ever passes its own observed id), caller responsible for non-blank validation.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:193-203` — the NavHost composable that mounts `ThreadScreen`; this is where `onSendMessage = vm::sendMessage` gets wired.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` — sibling thread component; mirror its file-level organization and import style for the new `ThreadInputBar.kt`.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` — existing test setup (`Dispatchers.setMain(UnconfinedTestDispatcher())`, `runTest`, `advanceUntilIdle`); extend with two new tests for `sendMessage`.
- `app/build.gradle.kts:83-84` and `gradle/libs.versions.toml:42-43` — confirms `androidx.compose.material:material-icons-extended` is already on the classpath; `Icons.Outlined.Mic` and `Icons.Outlined.ArrowUpward` are available without a new dependency.

## Design

### New composable: `ThreadInputBar`

File: `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`.

Use the **stateful + stateless overload pattern** so previews can render both empty and filled states without poking internals:

```kotlin
// Stateful — used by ThreadScreen
@Composable
fun ThreadInputBar(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
)

// Stateless — used by previews and (future) UI tests
@Composable
fun ThreadInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Behavior of the **stateful** overload: holds `var text by rememberSaveable { mutableStateOf("") }`, delegates to the stateless overload, and on send invokes `onSend(text)` then resets `text` to `""` — but only when `text.isNotBlank()`. Blank input must not invoke the upstream `onSend` and must not clear the field (no-op).

Behavior of the **stateless** overload:

- Root: `Row` with `Modifier.fillMaxWidth().imePadding().background(MaterialTheme.colorScheme.surface).border(top = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)` — top border drawn via a `Modifier.drawBehind` or a `Divider` child at the top of an enclosing `Column`. Pick whichever is simpler in the implementation; both are acceptable.
- Padding: 12.dp horizontal, 8.dp top, 12.dp bottom.
- `Arrangement.spacedBy(8.dp)`, `verticalAlignment = Alignment.Bottom` (so the pill grows upward as the text wraps).
- **Pill** (`Surface` with `shape = RoundedCornerShape(28.dp)`, `color = MaterialTheme.colorScheme.surfaceContainerHigh`, `modifier = Modifier.weight(1f).heightIn(min = 48.dp)`): contains a `Row` (4.dp gap, vertical alignment center, padding `start = 16.dp, end = 4.dp, vertical = 4.dp`) with:
  - **`BasicTextField`** (or a `TextField` with `TextFieldDefaults.colors` overridden to transparent — `BasicTextField` is simpler and the cleaner fit here since the pill already supplies the container styling). Modifier: `Modifier.weight(1f)`. Configure:
    - `value = text`, `onValueChange = onTextChange`.
    - `textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)`.
    - `singleLine = false`, `maxLines = 5` (multi-line, capped so the pill does not overrun the screen).
    - `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)`.
    - `keyboardActions = KeyboardActions(onSend = { onSend() })`.
    - `decorationBox` providing the placeholder: when `text.isEmpty()` render `Text("Message", style = bodyLarge, color = onSurfaceVariant.copy(alpha = 0.6f))` underneath the `innerTextField()`.
  - **Mic `IconButton`** (`modifier = Modifier.size(40.dp)`): `Icons.Outlined.Mic` 22.dp, `tint = MaterialTheme.colorScheme.onSurfaceVariant`. `onClick` calls a local lambda that reads `LocalContext.current` and shows the Toast — see *Voice stub* below.
- **Send `FilledTonalIconButton`** (`modifier = Modifier.size(48.dp)`):
  - `colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)` — matches Figma; the default `FilledTonalIconButton` uses `secondaryContainer` which does not match the design.
  - `enabled = text.isNotBlank()`.
  - `onClick = onSend`.
  - Icon: `Icons.Outlined.ArrowUpward`, 22.dp, `tint = MaterialTheme.colorScheme.onSurface` (or rely on the disabled-state default when `enabled = false`).

### Voice stub

The Toast lives entirely in the composable layer — do NOT route it through the ViewModel. Inside the **stateless** overload, capture `val context = LocalContext.current` once at the composable scope, and pass an `onMicClick = { Toast.makeText(context, "Voice input — Phase 6", Toast.LENGTH_SHORT).show() }` to the mic `IconButton`. Use exact string `"Voice input — Phase 6"` (em dash, not hyphen — copy from this spec).

### `ThreadViewModel.sendMessage(text: String)`

Add to [ThreadViewModel.kt](../../../app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt):

```kotlin
fun sendMessage(text: String) {
    if (text.isBlank()) return
    viewModelScope.launch {
        repository.sendMessage(state.value.conversationId, text)
    }
}
```

Requires promoting the constructor parameter to `private val repository: ConversationRepository`. The trim/blank policy is the ViewModel's defense in depth: the UI already disables the send button when blank, but the ViewModel double-checks because the IME `Send` action can fire even when the field is blank on some keyboards. The repository contract states the *caller* is responsible for blank rejection.

Fire-and-forget is intentional. `repository.sendMessage` returns the appended `Message`, but the UI re-renders from the cold `observeConversations` / (future) `observeMessages` flow, so the return value is discarded here. Errors are out of scope per the ticket — the only failure mode is `IllegalArgumentException` on unknown conversation id, and the only id this ViewModel ever passes is the one it observed at construction.

### `ThreadScreen` changes

Add one new parameter and mount the input bar:

```kotlin
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,   // <-- new, no default
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
)
```

Mount `ThreadInputBar` in the Scaffold's `bottomBar` slot, passing `onSend = onSendMessage`. Keep the existing `topBar` and `LazyColumn` body unchanged. Update both `@Preview` composables in this file to pass `onSendMessage = {}` so they still compile.

### IME handling

Apply `Modifier.imePadding()` on the **`ThreadInputBar` root `Row`** (already specified above). Do NOT apply it on the Scaffold or the body modifier — that would shift the entire screen including the top app bar. Putting it only on the input bar lifts the bar above the keyboard while the `LazyColumn` (which uses `reverseLayout = true` and is bottom-anchored) keeps its latest items visible immediately above the lifted bar.

### `MainActivity.kt` wiring

[MainActivity.kt:199-202](../../../app/src/main/java/de/pyryco/mobile/MainActivity.kt) — pass `vm::sendMessage` into `ThreadScreen`:

```kotlin
ThreadScreen(
    state = state,
    onBack = { navController.popBackStack() },
    onSendMessage = vm::sendMessage,
)
```

That is the entire NavHost edit.

## State + concurrency model

- The input bar's text state is `rememberSaveable { mutableStateOf("") }` inside the stateful `ThreadInputBar` overload. Survives configuration changes, never reaches `ThreadUiState`.
- `ThreadViewModel.sendMessage` launches inside `viewModelScope` (cancelled on `onCleared`). No new `StateFlow`s; the existing `state: StateFlow<ThreadUiState>` is untouched.
- No dispatcher choice required — `FakeConversationRepository.sendMessage` does no IO and `viewModelScope.launch` defaults to `Dispatchers.Main.immediate` via `Dispatchers.setMain` in tests / the main dispatcher in production. The Ktor-backed Phase 4 implementation will internally dispatch its IO; the ViewModel does not need to switch contexts.

## Error handling

Out of scope. The only failure mode is the documented `IllegalArgumentException` on unknown conversation id, which cannot happen at this call site (the ViewModel observed the id at construction). If `repository.sendMessage` throws, the coroutine fails silently — acceptable for Phase 0. Phase 4 will add user-visible error surfacing when the real network client lands.

Whitespace-only input is rejected twice as belt-and-suspenders: UI disables the button (`enabled = text.isNotBlank()`), and the ViewModel early-returns (`if (text.isBlank()) return`). Don't move either check.

## Testing strategy

Unit (`./gradlew test`) only. No instrumented or Compose UI tests in this ticket — the screen-level Compose UI test surface is being seeded in a separate ticket.

### `ThreadViewModelTest.kt` — add two new test functions:

1. **`sendMessage_blankText_isNoOp`** — instantiate with `FakeConversationRepository()` and `SavedStateHandle(mapOf("conversationId" to "seed-channel-personal"))`. Collect `repository.observeMessages("seed-channel-personal")` into a list; capture the initial message count. Call `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")` then `advanceUntilIdle()`. Assert the observed message count is unchanged.

2. **`sendMessage_nonBlankText_appendsToConversation`** — same setup as above. Capture initial count, call `vm.sendMessage("Hello world")`, `advanceUntilIdle()`, assert count incremented by exactly 1 and the newly appended `Message` has `content = "Hello world"`, `role = Role.User`, and `sessionId` equal to the seeded conversation's `currentSessionId`.

Both tests use the real `FakeConversationRepository` (not a recording mock) — the seeded `seed-channel-personal` conversation exists, supports `sendMessage`, and `observeMessages` re-emits on append. Use the existing `Dispatchers.setMain(UnconfinedTestDispatcher())` setup already in this test file. Use `runTest { ... }` and the `launch { vm.state.collect {} } ... collector.cancel()` pattern visible at [ThreadViewModelTest.kt:51-60](../../../app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt) where you need to subscribe to make `WhileSubscribed(5_000)` flow live; the `observeMessages` collector is the one that matters for these tests.

The Toast / mic onClick is intentionally untested. Toast is a system service; verifying it would require Robolectric or instrumented tests. Phase 6 replaces this stub anyway.

## Preview composables

Inside `ThreadInputBar.kt`, four `@Preview` composables matching the pattern in `ThreadScreen.kt:46-74`:

- `ThreadInputBar — Light, Empty` (`darkTheme = false`, `text = ""`)
- `ThreadInputBar — Light, Filled` (`darkTheme = false`, `text = "Drafting a reply…"`)
- `ThreadInputBar — Dark, Empty` (`darkTheme = true`, `text = ""`)
- `ThreadInputBar — Dark, Filled` (`darkTheme = true`, `text = "Drafting a reply…"`)

All four call the **stateless** overload directly with literal `text`, `onTextChange = {}`, `onSend = {}`. `showBackground = true`, `widthDp = 412` to match `ThreadScreen`.

Existing `ThreadScreen` previews (`ThreadScreenLightPreview`, `ThreadScreenDarkPreview`) get updated to pass `onSendMessage = {}` but don't gain new variants — those previews are a thread-screen-level concern and already render the input bar in its empty state via the stateful overload.

## Open questions

None. The AC/Figma reconciliation is resolved in *Design source* above.

## Estimated production-code footprint

- `ThreadInputBar.kt` (new): ~85 lines (stateful overload ~12, stateless overload ~55, four previews ~18).
- `ThreadViewModel.kt`: +6 lines (the method) + 1-token change (`repository` → `private val repository`).
- `ThreadScreen.kt`: +3 lines (new parameter, mount in `bottomBar`) + 2-line update to both previews.
- `MainActivity.kt`: +1 line.

Total: ~95–100 lines of production code across 4 files. Within S.

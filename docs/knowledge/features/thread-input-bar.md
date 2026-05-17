# Thread input bar

Bottom-anchored composer for the thread screen at the `conversation_thread/{conversationId}` route. Landed in [#188](../codebase/188.md) as the first VM-owned action on [Thread screen](thread-screen.md) — a multi-line text field inside a rounded pill, a mic `IconButton` stub (real voice input ships in Phase 6), and a filled-tonal send button. Builds on the `ConversationRepository.sendMessage(conversationId, text)` mutator added in [#187](../codebase/187.md).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`). Figma reference: subframe `16:61` of the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Mounted in `ThreadScreen`'s `bottomBar` slot. The user types into a `BasicTextField` inside a 28dp rounded-corner pill (`surfaceContainerHigh`); the right-edge mic `IconButton` shows an Android `Toast` reading `"Voice input — Phase 6"` and nothing else; the separate circular send button to the right of the pill is disabled while `text.isBlank()` and otherwise calls `onSend(text)` then clears the field. The IME `Send` action fires the same send path. The input bar lifts above the soft keyboard via `Modifier.imePadding()` so the composer stays visible while the user types; the `LazyColumn` body (`reverseLayout = true`) keeps the latest messages pinned just above the lifted bar.

## Shape

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

The stateful overload holds `var text by rememberSaveable { mutableStateOf("") }` and delegates to the stateless overload; on send it invokes `onSend(text)` and resets `text = ""` **only when `text.isNotBlank()`**. Blank input is a UI no-op (button is also disabled, but the IME `Send` action can still fire on some keyboards). The stateless overload is what the four previews call directly.

The two-overload pattern matches the project convention for composables that need both an internal-state default and a fully-hoisted variant for previews/tests (compare with workspace-picker / list state).

## How it works

### Root layout — `Column` + `HorizontalDivider` + `Row`

The outer `Column` carries `Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).imePadding()`. The 1.dp top border is drawn as a child `HorizontalDivider(thickness = 1.dp, color = outlineVariant)` at the top of the column — picked over the alternative `Modifier.drawBehind { ... }` because `HorizontalDivider` is a one-import M3 primitive that respects theme tokens without manual `density.toPx()` math. Both shapes were called out as acceptable in the spec; the divider variant won on simplicity.

The inner composer `Row` carries padding `start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp`, `Arrangement.spacedBy(8.dp)`, and `verticalAlignment = Alignment.Bottom` so the pill grows upward as text wraps while the send button stays anchored to the bottom edge of the row.

### Pill — `Surface(shape = RoundedCornerShape(28.dp))`

The 28dp rounded-corner pill is a `Surface` with `color = surfaceContainerHigh`, `Modifier.weight(1f).heightIn(min = 48.dp)`. Inside, a nested `Row` (padding `start = 16.dp, end = 4.dp, vertical = 4.dp`, 4.dp gap, vertical-center alignment) holds the text field on the left and the mic icon on the right. The asymmetric end padding leaves room for the 40.dp mic tap target without inflating the pill width past the Figma spec.

### `BasicTextField` — not `TextField`

`BasicTextField` is the right primitive here because the pill `Surface` already supplies the container styling (color, shape, height). A material `TextField` would have to override `TextFieldDefaults.colors` to transparent on every container slot, which is more code than the `BasicTextField + decorationBox` variant. Configuration:

- `value = text`, `onValueChange = onTextChange`, `Modifier.weight(1f)`.
- `textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface)`.
- `cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)` — `BasicTextField`'s default cursor is solid black, which fails against dark theme. Explicit `cursorBrush` mapped to `primary` matches the M3 `TextField` baseline.
- `singleLine = false, maxLines = 5` — multi-line, capped at 5 visible lines so the pill never overruns the screen on long pastes.
- `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)` + `keyboardActions = KeyboardActions(onSend = { onSend() })` — the IME `Send` action invokes the same path as the send button, so the user can submit without leaving the keyboard.
- `decorationBox = { innerTextField -> Box { if (text.isEmpty()) Text("Message", color = onSurfaceVariant.copy(alpha = 0.6f)); innerTextField() } }` — the placeholder renders behind `innerTextField` when the field is empty. `text.isEmpty()` (not `isBlank()`) is intentional — a leading space shouldn't clobber the placeholder visually mid-typing.

### Mic stub — `Toast` only, never through the ViewModel

Inside the stateless overload, `val context = LocalContext.current` and `val voiceToast = stringResource(R.string.voice_input_toast)` are captured once at composable scope. The mic `IconButton`'s `onClick` is `{ Toast.makeText(context, voiceToast, Toast.LENGTH_SHORT).show() }` — no ViewModel involvement, no event dispatch. The toast string is **`"Voice input — Phase 6"` (em dash, not hyphen)** held in `R.string.voice_input_toast`; using a string resource (rather than a Kotlin literal at the call site as the spec drafted) keeps the user-visible copy on the translation surface and matches the project's `cd_*` content-description pattern. The mic icon is `Icons.Outlined.Mic` 22.dp inside a 40.dp `IconButton`, tinted `onSurfaceVariant`.

The composable owns the Android `Context` because routing voice through the ViewModel would have meant either threading a `Context` through the VM (forbidden — VMs have no `Context`) or introducing a one-off `Event` for a stub that gets deleted in Phase 6. `LocalContext.current` is the conventional Compose escape hatch for one-shot `Context`-bound side effects.

### Send button — `FilledTonalIconButton` with overridden container color

```kotlin
FilledTonalIconButton(
    onClick = onSend,
    enabled = text.isNotBlank(),
    shape = CircleShape,
    colors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ),
    modifier = Modifier.size(48.dp),
) { Icon(Icons.Outlined.ArrowUpward, contentDescription = R.string.cd_send_message, modifier = Modifier.size(22.dp)) }
```

Two non-default overrides:

- **`shape = CircleShape`** — the M3 default `FilledTonalIconButton` shape is a rounded rectangle; Figma `16:61` shows a 48.dp circle. `CircleShape` is one import and one line.
- **`containerColor = surfaceContainerHigh`** — the M3 default is `secondaryContainer`, which renders as a tinted teal/lavender depending on dynamic-color seed. Figma uses the same `surfaceContainerHigh` as the pill so the two surfaces read as one composer cluster.

The `enabled = text.isNotBlank()` toggle is the UI half of the belt-and-suspenders blank rejection (the VM half is `if (text.isBlank()) return` inside `ThreadViewModel.sendMessage`). The disabled-state colors come from `IconButtonDefaults` automatically (lower alpha on `onSurface`); no manual disabled-color plumbing.

### IME handling — `Modifier.imePadding()` on the input bar, not the screen

`Modifier.imePadding()` lives on the outer `Column` of `ThreadInputBar`. It does **not** belong on the `Scaffold` body modifier or the screen root — that would shift the top app bar and the `LazyColumn` upward when the keyboard appears, which is wrong. Putting it only on the input bar lifts the bar above the keyboard while the rest of the screen stays stationary; the `LazyColumn`'s `reverseLayout = true` ordering keeps the latest item visible immediately above the lifted bar.

### `ThreadScreen` mount point

```kotlin
fun ThreadScreen(
    state: ThreadUiState,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,   // <-- new in #188, no default
    modifier: Modifier = Modifier,
    onTitleClick: () -> Unit = {},
    onOverflowClick: () -> Unit = {},
) {
    Scaffold(
        topBar = { ThreadTopAppBar(title = state.displayName, …) },
        bottomBar = { ThreadInputBar(onSend = onSendMessage) },
    ) { inner -> LazyColumn(reverseLayout = true, …) { items(emptyList<Unit>()) { } } }
}
```

The screen signature **stays a flat callback list (`onBack`, `onSendMessage`, `onTitleClick`, `onOverflowClick`)** rather than collapsing into a sealed `ThreadEvent`. The #139 [Thread screen](thread-screen.md) doc predicted "the signature widens to `(state, onBack, onEvent)` — or the three callbacks fold into a then-introduced `ThreadEvent` — when the first VM-owned event lands (#133's composer send is the most likely first)." When #188 actually shipped, the flat callback shape was cheaper than a sealed-event scaffold for one event arm; the `ThreadEvent` fold is deferred again to the first ticket that lands a *second* VM-owned event (#140 overflow menu or #141 rename, depending on whose handler talks to the VM rather than to nav).

### `ThreadViewModel.sendMessage`

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
) : ViewModel() {
    // ... existing state pipeline unchanged ...

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            repository.sendMessage(state.value.conversationId, text)
        }
    }
}
```

`repository` is promoted from an unmarked constructor parameter to `private val repository` so the new method can call it (the pre-#188 VM only referenced `repository` inline inside the `state` initializer). The `if (text.isBlank()) return` early-return is the VM half of belt-and-suspenders blank rejection — UI disables the button, VM double-checks. The repository contract explicitly disclaims trim/blank validation (see [Conversation repository](conversation-repository.md)).

Fire-and-forget by design. `repository.sendMessage` returns the persisted `Message`, but the UI re-renders from the [conversation repository](conversation-repository.md) flows (the eventual `observeMessages` subscription #128 will wire), so the return value is discarded here. The only documented failure mode is `IllegalArgumentException` on unknown conversation id, which cannot happen at this call site — the VM only ever passes its own observed `conversationId`. Phase 4 will add user-visible error surfacing when the real network client lands; Phase 0 lets the coroutine fail silently per the spec.

`viewModelScope.launch` defaults to `Dispatchers.Main.immediate`. `FakeConversationRepository.sendMessage` does no I/O (the `state.update` CAS is non-suspending), so no `withContext(Dispatchers.IO)` is needed. The Phase 4 Ktor-backed impl will dispatch its own I/O internally; the VM does not switch contexts.

## Wiring

### `MainActivity.kt` — one-line edit

```kotlin
// MainActivity.kt:197-208
composable(
    route = Routes.CONVERSATION_THREAD,
    arguments = listOf(navArgument("conversationId") { type = NavType.StringType }),
) {
    val vm = koinViewModel<ThreadViewModel>()
    val state by vm.state.collectAsStateWithLifecycle()
    ThreadScreen(
        state = state,
        onBack = { navController.popBackStack() },
        onSendMessage = vm::sendMessage,   // <-- new in #188
    )
}
```

The Koin binding `viewModel { ThreadViewModel(get(), get()) }` at `di/AppModule.kt` is unchanged — the constructor signature did not change, only the visibility of the `repository` parameter (`val` → `private val`).

### Strings (`res/values/strings.xml`)

Three new entries added in #188:

- `thread_input_placeholder` = `"Message"` — the empty-state placeholder inside the pill.
- `cd_voice_input` = `"Voice input"` — content description for the mic `IconButton`.
- `cd_send_message` = `"Send message"` — content description for the send button.
- `voice_input_toast` = `"Voice input — Phase 6"` — the toast copy (em dash). The exact literal matters; do not change to a hyphen.

## State + concurrency

- The input bar's text state is `rememberSaveable { mutableStateOf("") }` inside the stateful overload — survives configuration changes (rotation) but never reaches `ThreadUiState`. The text is purely UI-local until send fires.
- `ThreadViewModel.sendMessage` launches inside `viewModelScope` (cancelled on `onCleared`). No new `StateFlow`s; the existing `state: StateFlow<ThreadUiState>` is untouched.
- Blank-rejection happens twice (UI `enabled = text.isNotBlank()`, VM `if (text.isBlank()) return`). Don't remove either check.

## Error handling

Out of scope at Phase 0. The only documented failure mode of `repository.sendMessage` is `IllegalArgumentException` on unknown conversation id, which cannot happen at this call site (the VM observed the id at construction and the navigation entry passes the same id). If `repository.sendMessage` does throw, the coroutine fails silently. Phase 4 adds user-visible error surfacing when the Ktor-backed remote client lands.

## Testing

Unit tests only — `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`. The Compose UI surface for the input bar will be seeded in a separate ticket; the codebase has no thread-screen `androidTest` infrastructure yet.

Two new test methods (the file already had seven from #126/#139):

1. **`sendMessage_blankText_isNoOp`** — instantiates `ThreadViewModel(SavedStateHandle("conversationId" to "seed-channel-personal"), FakeConversationRepository())`, collects `repository.observeMessages("seed-channel-personal")` into a list, captures the initial message count, calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`, then `advanceUntilIdle()`. Asserts the observed message count is unchanged. Pins the VM-side blank rejection.
2. **`sendMessage_nonBlankText_appendsToConversation`** — same setup, captures initial count, calls `vm.sendMessage("Hello world")`, `advanceUntilIdle()`. Asserts the observed count incremented by exactly 1 and the appended `ThreadItem.MessageItem` has `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"` (the seeded channel's `currentSessionId`). Pins the VM → repository forwarding contract.

Both tests use the live `FakeConversationRepository()` rather than a recording mock — `seed-channel-personal` is a real seeded record and `observeMessages` re-emits on append, so the end-to-end path is the assertion surface. Uses the existing `Dispatchers.setMain(UnconfinedTestDispatcher())` / `runTest { launch collector ... collector.cancel() }` scaffold already in the file.

The Toast / mic onClick is intentionally untested — Toast is a system service and verifying it would require Robolectric or an instrumented test. Phase 6 replaces the stub anyway.

## Previews

Four `@Preview`s at the bottom of `ThreadInputBar.kt`, all calling the **stateless** overload with literal `text`, `onTextChange = {}`, `onSend = {}`, `showBackground = true`, `widthDp = 412`:

- `InputBar — Light, Empty` (`darkTheme = false`, `text = ""`)
- `InputBar — Light, Filled` (`darkTheme = false`, `text = "Drafting a reply…"`)
- `InputBar — Dark, Empty` (`darkTheme = true`, `text = ""`)
- `InputBar — Dark, Filled` (`darkTheme = true`, `text = "Drafting a reply…"`)

The existing `ThreadScreenLightPreview` / `ThreadScreenDarkPreview` in `ThreadScreen.kt` now pass `onSendMessage = {}` and render the input bar in its empty state via the stateful overload — no new screen-level preview variants.

## Edge cases / limitations

- **No error surfacing.** A `repository.sendMessage` failure is silent at Phase 0. Phase 4 fixes this when the real network client lands and errors become user-visible.
- **No optimistic echo of the sent message in the UI.** `ThreadScreen` does not yet render the thread (no `observeMessages` subscription on `ThreadViewModel`) — the user sees an empty body before *and* after sending. #128 wires the message list; once it lands, the appended `Message` will re-emit through the flow and the user will see their text appear. Until then, the only feedback that send fired is the cleared input field.
- **The mic button is a stub.** It only shows a Toast. Real voice input ships in Phase 6 and will replace the `onClick` body — likely with an event routed through the ViewModel and a new `Activity` or modal flow.
- **`maxLines = 5` is a soft cap on visible lines, not on content length.** The user can paste 50 lines; only 5 are visible and the field scrolls internally. No character cap on `text` is enforced anywhere in the stack.
- **No undo for the cleared field.** Once the user taps send (or the IME `Send` action), `text` is reset to `""`. There is no "restore last draft" affordance. Acceptable for Phase 0; revisit if user research surfaces accidental sends.
- **No autocomplete, no slash-commands, no `/clear` plumbing yet.** The text field is a plain `BasicTextField`. The pyrycode CLI's `/clear` / `/compact` slash commands land at the conversations-model layer in pyrycode Phase 2 and surface here in mobile Phase 3+; out of scope.
- **Keyboard `Send` action vs. multi-line entry.** Some keyboards render the IME `Send` action as a glyph; others fall through to `Done` or `Enter`. The `KeyboardActions(onSend = …)` callback fires only for `ImeAction.Send`. Multi-line entry via `Enter` is the OS's responsibility under `singleLine = false`; no manual `\n` handling needed.

## Related

- Ticket notes: [`../codebase/188.md`](../codebase/188.md) (this implementation), [`../codebase/187.md`](../codebase/187.md) (the `sendMessage` repository mutator this consumes)
- Specs: `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/187-sendmessage-on-conversation-repository.md`
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into; pre-#188 had a placeholder `bottomBar = null`); [Conversation repository](conversation-repository.md) (the `sendMessage` mutator the VM forwards to); [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination lives under); [Dependency injection](dependency-injection.md) (the unchanged Koin `viewModel { ThreadViewModel(get(), get()) }` binding)
- Sibling thread-screen slices: #128 message list, #134 connection banner, #135 session-boundary delimiter, #137/#138 empty states, #140 overflow menu, #141 rename dialog, #145 status row — each replaces or extends one slot in the same `Scaffold`
- Phase 6 replaces the mic stub with real voice input
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (parent thread screen), subframe `16:61` (the composer specifically)

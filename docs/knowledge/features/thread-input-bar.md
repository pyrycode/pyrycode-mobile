# Thread input bar

The middle band of the thread screen's composer, at the `conversation_thread/{conversationId}` route. Landed in [#188](../codebase/188.md) as the first VM-owned action on [Thread screen](thread-screen.md) — a multi-line text field inside a rounded pill, a mic `IconButton` stub (real voice input ships in Phase 6), and a filled-tonal send button. [#643](../codebase/643.md) applied the Figma `16:8` `Input area` frame: the pill became the design's `Input large` field, the mic stub was removed, and the one send button now carries a stop variant too. Builds on the `ConversationRepository.sendMessage(conversationId, text)` mutator added in [#187](../codebase/187.md).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`). Figma reference: `Input large` (`347:6635`), the middle of the three `Input area` bands (`533:1957`) on the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Mounted by [`ThreadScreen`](thread-screen.md) as the middle child of its `bottomBar` composer column (see "`ThreadScreen` mount point" below), between the status area above and the model/effort footer below. The user types into a `BasicTextField` inside a 6dp-cornered, 52dp-tall field (`surfaceContainerHigh`); at its trailing edge a 48dp message-input button carries one of two actions — send when the field holds text, stop the running turn when it's blank and a turn is in flight, disabled-send when it's blank and idle (see [Shape](#shape) below). The IME `Send` action fires the same send path as the button. There is no mic control any more; voice input remains a Phase 6 feature with no interim stub.

## Shape

```kotlin
// Stateful — used by ThreadScreen
@Composable
fun ThreadInputBar(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onInterrupt: () -> Unit = {},
)

// Stateless — used by previews and UI tests
@Composable
fun ThreadInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onInterrupt: () -> Unit = {},
)
```

[#643](../codebase/643.md) added `isBusy` and `onInterrupt` to both overloads, defaulted so the pre-existing previews and call sites stay one-liners. The stateful overload holds `var text by rememberSaveable { mutableStateOf("") }` and delegates to the stateless overload; on send it invokes `onSend(text)` and resets `text = ""` **only when `text.isNotBlank()`**. Blank input is a UI no-op (button is also disabled when idle, but the IME `Send` action can still fire on some keyboards). The stateless overload is what the previews call directly.

The two-overload pattern matches the project convention for composables that need both an internal-state default and a fully-hoisted variant for previews/tests (compare with workspace-picker / list state).

### The message-input button — one control, two actions

[#643](../codebase/643.md) retired the standalone foot-of-list `InterruptAffordance` (see [Interrupt affordance](interrupt-affordance.md#placement--wiring)) and folded its stop action into this button, following desktop's #678 precedent instead of inventing a third placement:

| `text` | `isBusy` | description | action | enabled |
|---|---|---|---|---|
| non-blank | either | `cd_send_message` ("Send message") | `onSend` | yes |
| blank | `true` | `cd_thread_interrupt` ("Stop the running turn") | `onInterrupt` | yes |
| blank | `false` | `cd_send_message` ("Send message") | — | no |

Text present wins over an in-flight turn **deliberately**: sending while the agent is busy is a shipped path (the daemon queues it and [`QueuedBacklog`](queued-backlog-section.md) renders it, #461/#467), so a stop variant that pre-empted a typed message would silently remove the only tap that reaches it. Stop therefore owns the button exactly when the composer is empty — the state anyone actually reaching for stop is in. One consequence worth knowing for anyone touching this path: **stop is unreachable while a draft sits in the composer** — the user must clear the field first. Deliberate and settled (queue-while-busy is real, the design has exactly one button slot), but a real thing to watch during a live turn.

Both states draw the same filled-circle silhouette so the control reads as one button in two states: `Icons.Filled.ArrowCircleUp` for send, `Icons.Filled.StopCircle` for stop, both tinted `colorScheme.primary` inside a container-less 48dp `IconButton`.

**Known gap, not yet fixed: the disabled state has no visual dimming.** The icon's `tint` is hardcoded to `colorScheme.primary` regardless of `enabled`, and `Icon(tint = ...)` overrides the `LocalContentColor` that `IconButton` would otherwise swap to `disabledContentColor` — so the idle-and-empty resting state draws the identical full-strength circle-chevron as the live send button. Semantics are correct (`assertIsNotEnabled` passes, TalkBack announces "disabled"); only the visual half is missing. Flagged in review as a non-blocking SHOULD FIX and shipped as-is — a case where the accessibility test passes while the visual reads as a live, tappable control. Fix by gating the tint's alpha on the same expression that gates `enabled` (e.g. `primary.copy(alpha = if (stopping || text.isNotBlank()) 1f else 0.38f)`) if this is picked up.

## How it works

### Root — `Surface(shape = RoundedCornerShape(6.dp))`, no divider, no background of its own

[#643](../codebase/643.md) replaced the composable's own outer `Column` (divider + surface + `imePadding()`) with a bare `Surface(shape = FieldCorner /* 6.dp */, color = surfaceContainerHigh, modifier = modifier.fillMaxWidth().heightIn(min = FieldMinHeight /* 52.dp */))` — the design's `Input large` field, sized and positioned by its caller. The divider, the surface background and `imePadding()` all moved up to the composer column `ThreadScreen` owns (see "`ThreadScreen` mount point" and "IME handling" below); the design draws no rule above the input area, so there is nothing left here to replace the old divider.

Inside, a `Row` (padding `start = FieldLeadingInset /* 16.dp */, end = FieldTrailingInset /* 4.dp */`, vertical-center alignment) holds the text field on the left and the message-input button on the right. The asymmetric end inset is the design's overlap: the field's own padding stops 4dp short of the trailing edge, and the 48dp button sits in that remaining space — not the old pill-plus-tap-target arithmetic.

### `BasicTextField` — not `TextField`

`BasicTextField` is the right primitive here because the field `Surface` already supplies the container styling (color, shape, height). A material `TextField` would have to override `TextFieldDefaults.colors` to transparent on every container slot, which is more code than the `BasicTextField + decorationBox` variant. Configuration (carried over verbatim through #643's rewrite):

- `value = text`, `onValueChange = onTextChange`, `Modifier.weight(1f).padding(vertical = FieldTextVerticalInset /* 12.dp */)` — the design's `Text area` `py-12`; keeps wrapped text off the container's edge as the field grows.
- `textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface)`.
- `cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)` — `BasicTextField`'s default cursor is solid black, which fails against dark theme. Explicit `cursorBrush` mapped to `primary` matches the M3 `TextField` baseline.
- `singleLine = false, maxLines = 5` — multi-line, capped at 5 visible lines so the field never overruns the screen on long pastes.
- `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)` + `keyboardActions = KeyboardActions(onSend = { onSend() })` — the IME `Send` action invokes the same path as the message-input button, so the user can submit without leaving the keyboard.
- `decorationBox = { innerTextField -> Box { if (text.isEmpty()) Text("Message", color = onSurfaceVariant.copy(alpha = 0.6f)); innerTextField() } }` — the placeholder renders behind `innerTextField` when the field is empty. `text.isEmpty()` (not `isBlank()`) is intentional — a leading space shouldn't clobber the placeholder visually mid-typing.

### The mic stub is gone

[#643](../codebase/643.md) removed the mic `IconButton` and its `Toast` stub entirely, along with `R.string.cd_voice_input` and `R.string.voice_input_toast` (no remaining reference anywhere in `app/src` or `scripts/`). The design's `Input large` holds exactly one button slot, and the ticket's own rule against shipping inert controls ahead of their features applied directly — the mic only ever raised a "Voice input — Phase 6" toast with no ViewModel involvement. Removing it also dropped the composable's `LocalContext` dependency. Voice input, when it ships in Phase 6, gets a fresh design pass rather than reviving this stub.

### The message-input button lives in [Shape](#shape), not here

The button itself — its two-state icon, content description and enabled logic — is documented under [Shape](#shape) above, since the send/stop precedence is part of the composable's public contract, not an implementation detail.

### IME handling — `Modifier.imePadding()` moved to the composer column

**Moved in [#643](../codebase/643.md).** `Modifier.imePadding()` no longer lives on this composable — it lives on the `Column` [`ThreadScreen`](thread-screen.md) mounts in its `bottomBar` slot (see "`ThreadScreen` mount point" below), one level up, alongside that column's own `background(surface)` and its 12dp/16dp top/bottom padding. It still does **not** belong on the `Scaffold` body modifier or the screen root — that would shift the top app bar and the message list upward when the keyboard appears, which is wrong. Lifting the whole three-band composer column (status area + field + footer) as one unit above the keyboard, while the header and the list stay stationary, is the same rule as before #643, just applied one level higher now that the composer is three bands instead of one. The `LazyColumn`'s `reverseLayout = true` ordering keeps the latest item visible immediately above the lifted composer.

### `ThreadScreen` mount point — three-part `Input area`

**Rebuilt in [#643](../codebase/643.md).** `ThreadInputBar` is no longer the whole `bottomBar`; it is the middle child of a composer `Column` that also owns the status area above it and the model/effort footer below it:

```kotlin
Scaffold(
    topBar = { ThreadTopAppBar(title = state.displayName, …) },
    bottomBar = {
        Column(
            Modifier.fillMaxWidth().background(colorScheme.surface).imePadding()
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThreadStatusArea(apiRetry = apiRetry, usageLimit = usageLimit, isCompacting = isCompacting, isThinking = isThinking) // #804 added usageLimit
            ThreadInputBar(
                onSend = onSendMessage,
                modifier = Modifier.padding(horizontal = 20.dp),
                isBusy = isBusy,
                onInterrupt = onInterrupt,
            )
            ThreadStatusRow(model = …, effort = …, onExpandClick = { sheetVisible = true }, modifier = Modifier.padding(horizontal = 20.dp))
        }
    },
) { inner -> LazyColumn(reverseLayout = true, …) { … } }
```

`ThreadInputBar` itself only ever received the 20dp horizontal gutter as a plain `modifier` `padding` from its caller — the composable has no opinion about the gutter or the surrounding column; see [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md#thinking-indicator-placement-post-407-moved-in-643) for the status area's own gutter arithmetic and [Thread composer footer](thread-composer-footer.md) for the footer.

The screen signature **stays a flat callback list** rather than collapsing into a sealed `ThreadEvent` — #643 added `isBusy` / `onInterrupt` as two more flat parameters (both already existed on `ThreadScreen` since #459; #643 only threaded them one slot lower, into the composer) rather than folding anything into an event type. The #139 [Thread screen](thread-screen.md) doc's original prediction that the signature would fold into `ThreadEvent` after the first VM-owned event has not come to pass across nine further slices; the flat-callback shape keeps winning on cost.

### `ThreadViewModel.sendMessage`

```kotlin
class ThreadViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: ConversationRepository,
) : ViewModel() {
    // ... existing state pipeline unchanged ...

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        launchGuardedRepoCall {                 // guarded #490 (was viewModelScope.launch)
            repository.sendMessage(state.value.conversationId, text)
        }
    }
}
```

`repository` is promoted from an unmarked constructor parameter to `private val repository` so the new method can call it (the pre-#188 VM only referenced `repository` inline inside the `state` initializer). The `if (text.isBlank()) return` early-return is the VM half of belt-and-suspenders blank rejection — UI disables the button, VM double-checks. The repository contract explicitly disclaims trim/blank validation (see [Conversation repository](conversation-repository.md)).

Fire-and-forget by design. `repository.sendMessage` returns the persisted `Message`, but the UI re-renders from the [conversation repository](conversation-repository.md) flows (the eventual `observeMessages` subscription #128 will wire), so the return value is discarded here. **Guarded since #490** — the launch routes through [`launchGuardedRepoCall`](guarded-repo-launch.md), which inertly swallows the three relay failure types (`RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException`) so a failed send under the relay repository can't crash the process. The `IllegalArgumentException`-on-unknown-id failure mode cannot happen at this call site (the VM only ever passes its own observed `conversationId`) and the guard deliberately leaves IAE uncaught anyway (fail-fast). Phase 4 still owes the **user-visible error surface**; the guard is crash-safety only — a swallowed send fails quietly.

`viewModelScope.launch` defaults to `Dispatchers.Main.immediate`. `FakeConversationRepository.sendMessage` does no I/O (the `state.update` CAS is non-suspending), so no `withContext(Dispatchers.IO)` is needed. The Phase 4 Ktor-backed impl will dispatch its own I/O internally; the VM does not switch contexts.

## Wiring

### `MainActivity.kt` — the #188-era call site (shape only; `ThreadScreen` has grown many more collected flags since)

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
        onSendMessage = vm::sendMessage,   // <-- new in #188
    )
}
```

`onSendMessage` is still forwarded exactly like this; `isBusy` / `onInterrupt` (since #459) are two of the many further defaulted parameters `MainActivity` now collects and passes alongside it — see [Interrupt affordance § Placement & wiring](interrupt-affordance.md#placement--wiring) for that pair specifically. The Koin binding `viewModel { ThreadViewModel(get(), get()) }` at `di/AppModule.kt` is unchanged by any of this — no constructor, DI or repository change has ever come from a thread-screen composer ticket.

### Strings (`res/values/strings.xml`)

- `thread_input_placeholder` = `"Message"` — the empty-state placeholder inside the field. Added in #188.
- `cd_send_message` = `"Send message"` — content description for the message-input button in its send state. Added in #188; also the e2e suites' thread-arrival marker (see [Shape](#shape)).
- `cd_thread_interrupt` = `"Stop the running turn"` — content description for the same button in its stop state. Added in #459 for the standalone `InterruptAffordance`; reused as-is by #643's button, no new string.

**Removed in [#643](../codebase/643.md):** `cd_voice_input` ("Voice input") and `voice_input_toast` ("Voice input — Phase 6", em dash) went with the mic stub. Neither had a test reference.

## State + concurrency

- The input bar's text state is `rememberSaveable { mutableStateOf("") }` inside the stateful overload — survives configuration changes (rotation) but never reaches `ThreadUiState`. The text is purely UI-local until send fires.
- `ThreadViewModel.sendMessage` launches inside `viewModelScope` (cancelled on `onCleared`). No new `StateFlow`s; the existing `state: StateFlow<ThreadUiState>` is untouched.
- Blank-rejection happens twice (UI `enabled = text.isNotBlank()`, VM `if (text.isBlank()) return`). Don't remove either check.

## Error handling

**Crash-guarded since #490; no user-facing surface yet.** The launch routes through [`launchGuardedRepoCall`](guarded-repo-launch.md), which inertly swallows the three relay failure types (`RelayErrorException` / `IllegalStateException` / `UnsupportedOperationException`) so a failed send under the relay repository fails **quietly** instead of killing the process. The `IllegalArgumentException`-on-unknown-id mode cannot happen here (the VM observed the id at construction; the navigation entry passes the same id) and the guard leaves IAE uncaught by design (fail-fast). Phase 4 still adds the **user-visible** error surfacing when the real client lands.

## Testing

Unit tests only — `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`. The Compose UI surface for the input bar will be seeded in a separate ticket; the codebase has no thread-screen `androidTest` infrastructure yet.

Two new test methods (the file already had seven from #126/#139):

1. **`sendMessage_blankText_isNoOp`** — instantiates `ThreadViewModel(SavedStateHandle("conversationId" to "seed-channel-personal"), FakeConversationRepository())`, collects `repository.observeMessages("seed-channel-personal")` into a list, captures the initial message count, calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`, then `advanceUntilIdle()`. Asserts the observed message count is unchanged. Pins the VM-side blank rejection.
2. **`sendMessage_nonBlankText_appendsToConversation`** — same setup, captures initial count, calls `vm.sendMessage("Hello world")`, `advanceUntilIdle()`. Asserts the observed count incremented by exactly 1 and the appended `ThreadItem.MessageItem` has `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"` (the seeded channel's `currentSessionId`). Pins the VM → repository forwarding contract.

Both tests use the live `FakeConversationRepository()` rather than a recording mock — `seed-channel-personal` is a real seeded record and `observeMessages` re-emits on append, so the end-to-end path is the assertion surface. Uses the existing `Dispatchers.setMain(UnconfinedTestDispatcher())` / `runTest { launch collector ... collector.cancel() }` scaffold already in the file.

The old mic `Toast` onClick was intentionally never unit-tested; it went with the stub in #643, so there is nothing left to skip.

**Instrumented, added in [#643](../codebase/643.md):** `ThreadFrameTest.kt` (`app/src/androidTest/…/thread/`) covers the send/stop precedence directly — `inputButton_sendsWhenTextPresent`, `inputButton_stopsWhileBusyWithEmptyField`, and an additive sixth case `inputButton_isDisabledSendWhenIdleAndEmpty` beyond the plan's original five, plus `busyThread_hasExactlyOneStopControl` at the `ThreadScreen` level. See [Interrupt affordance § Testing](interrupt-affordance.md#testing) and [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md) for the header-side coverage. Pre-existing `ScriptedThreadRenderTest`'s `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd` stayed green unchanged against the relocated control — it finds `cd_thread_interrupt` by description regardless of where the control lives.

## Previews

Five `@Preview`s at the bottom of `ThreadInputBar.kt`, all calling the **stateless** overload with literal `text`, `onTextChange = {}`, `onSend = {}`, `showBackground = true`, `widthDp = 372` (narrowed from 412 in #643 to match the design's 20dp-inset content width rather than the full reference frame):

- `InputBar — Light, Empty` (`darkTheme = false`, `text = ""`)
- `InputBar — Light, Filled` (`darkTheme = false`, `text = "Drafting a reply…"`)
- `InputBar — Dark, Empty` (`darkTheme = true`, `text = ""`)
- `InputBar — Dark, Filled` (`darkTheme = true`, `text = "Drafting a reply…"`)
- `InputBar — Dark, Stop variant` (`darkTheme = true`, `text = ""`, `isBusy = true`) — added in #643 for the `StopCircle` glyph.

The existing `ThreadScreenLightPreview` / `ThreadScreenDarkPreview` in `ThreadScreen.kt` still pass `onSendMessage = {}` and render the composer in its empty/idle state via the stateful overload — no new screen-level preview variants from #643.

## Edge cases / limitations

- **No error *surface* (but crash-guarded since #490).** A `repository.sendMessage` failure is swallowed quietly by [`launchGuardedRepoCall`](guarded-repo-launch.md) — no crash, no user-visible message. Phase 4 adds the user-visible surfacing when the real network client lands.
- **`maxLines = 5` is a soft cap on visible lines, not on content length.** The user can paste 50 lines; only 5 are visible and the field scrolls internally. No character cap on `text` is enforced anywhere in the stack.
- **No undo for the cleared field.** Once the user taps send (or the IME `Send` action), `text` is reset to `""`. There is no "restore last draft" affordance.
- **No autocomplete, no slash-commands, no `/clear` plumbing yet.** The text field is a plain `BasicTextField`. The pyrycode CLI's `/clear` / `/compact` slash commands land at the conversations-model layer in pyrycode Phase 2 and surface here in mobile Phase 3+; out of scope.
- **Keyboard `Send` action vs. multi-line entry.** Some keyboards render the IME `Send` action as a glyph; others fall through to `Done` or `Enter`. The `KeyboardActions(onSend = …)` callback fires only for `ImeAction.Send`. Multi-line entry via `Enter` is the OS's responsibility under `singleLine = false`; no manual `\n` handling needed.
- **Stop is unreachable while the field holds a draft** — see [The message-input button](#the-message-input-button--one-control-two-actions) above; this is the live #643 consequence that superseded the old "mic is a stub" limitation.
- **The disabled send button has no visual dimming** — see [The message-input button](#the-message-input-button--one-control-two-actions) above; a shipped, non-blocking gap, not a design limitation.
- **No voice input, no interim stub.** Removed in #643; Phase 6 owns a fresh design for it whenever it lands.

## Related

- Ticket notes: [`../codebase/188.md`](../codebase/188.md) (original implementation), [`../codebase/187.md`](../codebase/187.md) (the `sendMessage` repository mutator this consumes), [`../codebase/459.md`](../codebase/459.md) (added `isBusy`/`onInterrupt` to `ThreadScreen`, pre-#643), [`../codebase/643.md`](../codebase/643.md) (the Figma `16:8` frame — `Input large` field, mic removal, send/stop button, three-part composer)
- Specs: `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/187-sendmessage-on-conversation-repository.md`, `docs/specs/architecture/643-thread-header-and-composer-layout.md`
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into) and [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md) (the `ThreadTopAppBar` rewrite and the status-area/interrupt relocation, both part of the same #643 pass); [Conversation repository](conversation-repository.md) (the `sendMessage` mutator the VM forwards to); [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination lives under); [Dependency injection](dependency-injection.md) (the unchanged Koin `viewModel { ThreadViewModel(get(), get()) }` binding)
- Sibling: [Interrupt affordance](interrupt-affordance.md) — the composable #643 retired from the screen; its stop action lives on this button now
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (parent thread screen), `Input large` (`347:6635`, the field itself), `Input area` (`533:1957`, the three-band composer this field is the middle of)

# Thread input bar

The middle band of the thread screen's composer, at the `conversation_thread/{conversationId}` route. Landed in [#188](../codebase/188.md) as the first VM-owned action on [Thread screen](thread-screen.md) — a multi-line text field inside a rounded pill, a mic `IconButton` stub (real voice input ships in Phase 6), and a filled-tonal send button. [#643](../codebase/643.md) applied the Figma `16:8` `Input area` frame: the pill became the design's `Input large` field, the mic stub was removed, and the one send button now carries a stop variant too. Builds on the `ConversationRepository.sendMessage(conversationId, text)` mutator added in [#187](../codebase/187.md).

Package: `de.pyryco.mobile.ui.conversations.thread` (`app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt`). Figma reference: `Input large` (`347:6635`), the middle of the three `Input area` bands (`533:1957`) on the [thread screen node](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8).

## What it does

Mounted by [`ThreadScreen`](thread-screen.md) as the middle child of its `bottomBar` composer column (see "`ThreadScreen` mount point" below), between the status area above and the model/effort footer below. The user types into a `BasicTextField` inside a 6dp-cornered field with a 52dp minimum height; at its trailing edge a 48dp message-input button carries one of two actions — send when the field holds text, stop the running turn when it's blank and a turn is in flight, disabled-send when it's blank and idle (see [Shape](#shape) below). The IME `Send` action fires the same send path as the button. There is no mic control any more; voice input remains a Phase 6 feature with no interim stub.

The composer-specific fill is `#003355` at 41% opacity when the app resolves to dark
mode with wallpaper colours off, in empty, focused and typed states. Light mode
and both wallpaper palettes retain their selected scheme's `surfaceContainerHigh`.
Placeholder and entered text use `bodyMedium` (14sp/20sp) across themes; multiline
input grows to five visible lines before scrolling internally.

## Shape

```kotlin
// Stateful — used by ThreadScreen
@Composable
fun ThreadInputBar(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    isBusy: Boolean = false,
    onInterrupt: () -> Unit = {},
    onAnchorChanged: (Rect) -> Unit = {},
    hasAttachments: Boolean = false,
    sending: Boolean = false,
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
    onAnchorChanged: (Rect) -> Unit = {},
    hasAttachments: Boolean = false,
    sending: Boolean = false,
    onImagesReceived: ((List<Uri>) -> Unit)? = null,
)
```

[#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) added `onImagesReceived`, defaulted `null` so every pre-#934 call site still compiles and renders a field with no `contentReceiver` at all. Non-null on `ThreadScreen`'s own call — see [§ Image paste into the field](#image-paste-into-the-field-934).

[#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) added `onAnchorChanged`, defaulted on both overloads so no existing call site changes. It reports the field's `boundsInWindow()` with `left` moved in by `FieldLeadingInset` (16dp) on every frame the field's own position changes, so a row of the [slash-command type-ahead](slash-command-type-ahead.md)'s `OptionsOverlay` lines its text up with the composer's own typed text. See [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934) for the other #885 change to this file.

[#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) added `hasAttachments` and `sending`, both defaulted so every pre-#933 call site still compiles. They come from the chat's own pending-attachment strip — see [Thread screen § Composer pending attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) — not from anything local to this composable.

[#643](../codebase/643.md) added `isBusy` and `onInterrupt` to both overloads, defaulted so the pre-existing previews and call sites stay one-liners. The stateful overload holds `var text by rememberSaveable { mutableStateOf("") }` and delegates to the stateless overload; on send it invokes `onSend(text)` and resets `text = ""` **only when `text.isNotBlank()`**. Blank input is a UI no-op (button is also disabled when idle, but the IME `Send` action can still fire on some keyboards). The stateless overload is what the previews call directly.

The two-overload pattern matches the project convention for composables that need both an internal-state default and a fully-hoisted variant for previews/tests (compare with workspace-picker / list state).

### The message-input button — one control, two actions

[#643](../codebase/643.md) retired the standalone foot-of-list `InterruptAffordance` (see [Interrupt affordance](interrupt-affordance.md#placement--wiring)) and folded its stop action into this button, following desktop's #678 precedent instead of inventing a third placement:

| `text` | `hasAttachments` | `isBusy` | `sending` | description | action | enabled |
|---|---|---|---|---|---|---|
| non-blank | either | either | either | `cd_send_message` ("Send message") | `onSend` | yes |
| blank | `true` | either | `false` | `cd_send_message` ("Send message") | `onSend` | yes |
| blank | `true` | either | `true` | `cd_send_message` ("Send message") | `onSend` | no |
| blank | `false` | `true` | either | `cd_thread_interrupt` ("Stop the running turn") | `onInterrupt` | yes |
| blank | `false` | `false` | either | `cd_send_message` ("Send message") | — | no |

Text present wins over an in-flight turn **deliberately**: sending while the agent is busy is a shipped path (the daemon queues it and [`QueuedBacklog`](queued-backlog-section.md) renders it, #461/#467), so a stop variant that pre-empted a typed message would silently remove the only tap that reaches it. Stop therefore owns the button exactly when the composer is empty **and holds no attachment** — [#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) widened `stopping = isBusy && text.isBlank() && !hasAttachments`, since an attachment with no text is still something to send, not the empty state stop is for. One consequence worth knowing for anyone touching this path: **stop is unreachable while a draft sits in the composer, or while an attachment is pending** — the user must clear both first. Deliberate and settled (queue-while-busy is real, the design has exactly one button slot), but a real thing to watch during a live turn.

`enabled = stopping || (!sending && (text.isNotBlank() || hasAttachments))` (also #933): attachments alone enable Send, and `sending` — true for as long as [`ThreadViewModel.sendWithAttachments`](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) is uploading and sending — disables it outright rather than letting it fall through to stop, so a second tap during an in-flight attachment send does nothing.

Both states draw the same filled-circle silhouette so the control reads as one button in two states: `Icons.Filled.ArrowCircleUp` for send, `Icons.Filled.StopCircle` for stop, both tinted `colorScheme.primary` inside a container-less 48dp `IconButton`.

**Known gap, not yet fixed: the disabled state has no visual dimming.** The icon's `tint` is hardcoded to `colorScheme.primary` regardless of `enabled`, and `Icon(tint = ...)` overrides the `LocalContentColor` that `IconButton` would otherwise swap to `disabledContentColor` — so the idle-and-empty resting state draws the identical full-strength circle-chevron as the live send button. Semantics are correct (`assertIsNotEnabled` passes, TalkBack announces "disabled"); only the visual half is missing. Flagged in review as a non-blocking SHOULD FIX and shipped as-is — a case where the accessibility test passes while the visual reads as a live, tappable control. Fix by gating the tint's alpha on the same expression that gates `enabled` (e.g. `primary.copy(alpha = if (stopping || text.isNotBlank()) 1f else 0.38f)`) if this is picked up.

## How it works

### Root — `Surface(shape = RoundedCornerShape(6.dp))`, no divider, no background of its own

The root is a bare `Surface(shape = FieldCorner /* 6.dp */, color = MaterialTheme.colorScheme.composerFieldContainer, modifier = modifier.fillMaxWidth().heightIn(min = FieldMinHeight /* 52.dp */))` — the design's `Input large` field, sized and positioned by its caller. [#643](../codebase/643.md) removed the composable's outer `Column`; the divider, the surface background and `imePadding()` all moved up to the composer column `ThreadScreen` owns (see "`ThreadScreen` mount point" and "IME handling" below); the design draws no rule above the input area, so there is nothing left here to replace the old divider.

[`composerFieldContainer`](../../../app/src/main/java/de/pyryco/mobile/ui/theme/ComposerColors.kt)
is a `ColorScheme` extension backed by `LocalComposerFieldContainer`.
`PyrycodeMobileTheme` provides `onPrimaryDark.copy(alpha = 0.41f)` only when
`darkTheme && !dynamicColor`; otherwise it provides the selected scheme's
`surfaceContainerHigh`. Resolve this from the effective app theme, not a fresh
system-theme check in the field: a user-selected Dark or Light mode must win over
the opposite system setting. Keep the role separate from
[modal field colours](mobile-modal.md#layout-and-theme), which derive their tint
from the active palette even with wallpaper colours enabled. Shared Material
palette and typography tokens retain their values. See the
[#1160 design plan](../../specs/architecture/1160-dark-composer-field.md).

Inside, a `Row` (padding `start = FieldLeadingInset /* 16.dp */, end = FieldTrailingInset /* 4.dp */`, vertical-center alignment) holds the text field on the left and the message-input button on the right. The asymmetric end inset is the design's overlap: the field's own padding stops 4dp short of the trailing edge, and the 48dp button sits in that remaining space — not the old pill-plus-tap-target arithmetic.

### `BasicTextField` — not `TextField`

`BasicTextField` is the right primitive here because the field `Surface` already supplies the container styling (color, shape, height). A material `TextField` would have to override `TextFieldDefaults.colors` to transparent on every container slot, which is more code than the `BasicTextField + decorator` variant. [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) moved the field from the `value`/`onValueChange` overload to the `state: TextFieldState` overload — the only one that can receive pasted content, see [§ Image paste into the field](#image-paste-into-the-field-934) — so several of these settings changed name without changing behaviour. Configuration:

- `state = fieldState` (a heap-only `TextFieldState`, not the `text`/`onTextChange` pair directly — see [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934)), `inputTransformation = reportEdits`, `Modifier.weight(1f).padding(vertical = FieldTextVerticalInset /* 12.dp */).then(if (onImagesReceived != null) Modifier.contentReceiver(imageReceiver) else Modifier)` — the design's `Text area` `py-12`; keeps wrapped text off the container's edge as the field grows.
- `textStyle = MaterialTheme.typography.bodyMedium.copy(color = onSurface)` — 14sp size and 20sp line height across themes.
- `cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)` — `BasicTextField`'s default cursor is solid black, which fails against dark theme. Explicit `cursorBrush` mapped to `primary` matches the M3 `TextField` baseline.
- `lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 5)` — multi-line, capped at 5 visible lines so the field never overruns the screen on long pastes. Renamed from `singleLine = false, maxLines = 5` by the #934 field migration; same cap.
- `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send)` + `onKeyboardAction = { onSend() }` — the IME `Send` action invokes the same path as the message-input button, so the user can submit without leaving the keyboard. Renamed from `KeyboardActions(onSend = ...)` by the #934 migration; same trigger.
- `decorator = { innerTextField -> Box { if (fieldState.text.isEmpty()) Text("Message", style = MaterialTheme.typography.bodyMedium, color = onSurfaceVariant.copy(alpha = 0.6f)); innerTextField() } }` — the placeholder renders behind `innerTextField` when the field is empty. `fieldState.text.isEmpty()` (not `isBlank()`) is intentional — a leading space shouldn't clobber the placeholder visually mid-typing, and reading the field's own text (rather than the hoisted `text`) keeps the placeholder correct immediately after an undo, before the draft has caught up. Renamed from `decorationBox` by the #934 migration.

### Draft binding — cursor-at-end, undo and redo (#885, #934)

Found by the [slash-command type-ahead](slash-command-type-ahead.md)'s pick test.
Before #885, the stateless overload passed the hoisted `text: String` straight to
`BasicTextField(value: String, ...)`, which keeps the field's *previous* cursor
offset whenever the string changes from outside the field — indistinguishable, from
the field's point of view, from the user having typed at that same offset. Picking
`/model ` from a draft of `/mo` left the cursor at offset 3 (where the user had left
it), so typing `opus` next landed inside the name instead of after it:
`/moopusdel `, not `/model opus`.

\#885 fixed this with a caller-held `TextFieldValue`. [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934)
moved the field onto `BasicTextField(state: TextFieldState, ...)` instead — the only
overload `Modifier.contentReceiver` (the paste entry point, see
[§ Image paste into the field](#image-paste-into-the-field-934)) works with — and rebuilt
the same rule on top of it, plus closed a second desync the migration introduced.

**The state is a plain `remember`, never `rememberTextFieldState`:**

```kotlin
val fieldState = remember { TextFieldState(text, TextRange(text.length)) }
```

`rememberTextFieldState` is `rememberSaveable(saver = TextFieldState.Saver)` under
Compose foundation 1.10.4, and that saver writes the field's text *and its whole
undo history* — deleted text included — into the activity's saved-state Bundle on
every `onStop`. That breaks [#789's heap-only draft contract](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
(the draft must never cross into `system_server`) and, for a large paste, can throw
`TransactionTooLargeException` and crash the app on backgrounding. This shipped
wrong on the first pass — the plan's own Security review claimed "drafts stay
heap-only" while the Design section specified `rememberTextFieldState` — and was
caught in verification, not before. `ThreadInputBarDraftBindingTest` (shared,
Robolectric) types a draft and asserts it is absent from
`SaveableStateRegistry.performSave()`; it fails if the state becomes saveable again.
**Any future `TextFieldState` field holding private content must use the same plain
`remember`, not the `rememberTextFieldState` default.**

**Outward, user edits → draft, via an `InputTransformation`:**

```kotlin
val reportEdits = remember {
    InputTransformation {
        val edited = toString()
        accountedText = edited
        if (edited != currentText) {
            textAtLastEdit = currentText
            currentOnTextChange(edited)
        }
    }
}
```

Every edit that changes the field's text — typing, a text paste, an IME edit —
records `textAtLastEdit` (what the draft was just before this edit) and calls
`onTextChange`. A programmatic `setTextAndPlaceCursorAtEnd` does not run input
transformations, so an outside change never echoes back through this path.

**Inward, outside change → field, via `LaunchedEffect(text)`:**

```kotlin
LaunchedEffect(text) {
    val shown = fieldState.text.toString()
    if (shown == text) {
        textAtLastEdit = null
    } else if (text != textAtLastEdit) {
        accountedText = text
        fieldState.setTextAndPlaceCursorAtEnd(text)
    }
}
```

If `text` still equals `textAtLastEdit`, the field keeps its own value and cursor —
this is the asynchronous [draft round trip](thread-screen-composer-drafts-and-attachments.md#composer-draft-ownership)
lagging behind the field's own keystroke, not a genuine outside change, and treating
it as one would fight the cursor on ordinary typing. Otherwise the field adopts
`text` with the cursor at the end, which covers both a completion pick and the field
clearing after a send. Once `text` catches up to what the field shows, `textAtLastEdit`
clears, so a later send that returns the draft to that same remembered value is still
adopted as a real outside change.

**Undo and redo bypass `InputTransformation` — found in verification, not by design.**
In foundation 1.10.4, `TextUndoOperationKt.undo`/`redo` write the field's buffer
directly and never call `commitEditAsUser`, so a hardware-keyboard Ctrl+Z changed
what the field showed without calling `onTextChange`: the draft (what Send actually
sends) stayed on the pre-undo text while the field itself showed the undone value.
The fix adds a third path, `accountedText` plus a `snapshotFlow` collector, that
catches whatever the other two miss:

```kotlin
var accountedText by remember { mutableStateOf(text) }
LaunchedEffect(fieldState) {
    snapshotFlow { fieldState.text.toString() }.collect { shown ->
        if (shown != accountedText) {
            accountedText = shown
            textAtLastEdit = currentText
            currentOnTextChange(shown)
        }
    }
}
```

`accountedText` is set by whichever of the three paths last put text in the field —
the `InputTransformation` for a user edit, the inward effect for an outside change —
before that path changes the field, so neither one is reported again by the
collector. Only undo, redo, or any other path that bypasses both is left over, and
the collector reports it to `onTextChange` the same way a typed edit would.
`ThreadInputBarUndoDeviceTest` pins this; it is device-only because Robolectric's
`KeyCharacterMap` ignores the Ctrl meta state, so a test that sends Ctrl+Z there
actually types a "z" and would pass green without exercising undo at all — see
[development-verification.md](development-verification.md) for the general version of
this limitation.

Both overloads' signatures are otherwise unchanged; see the ticket's plan Revisions
(`docs/specs/architecture/885-slash-command-type-ahead.md` for the original fix,
`docs/specs/architecture/934-paste-images-into-composer.md` for the migration and the
undo fix) for the mutation-checked tests that pin each rule.

### Image paste into the field (#934)

[Modifier.contentReceiver](https://developer.android.com/reference/kotlin/androidx/compose/foundation/content/package-summary)
(`@ExperimentalFoundationApi`) is the entry point for both the clipboard's Paste and a
keyboard's image insert (`commitContent`), and only the `TextFieldState` overload of
`BasicTextField` reads `Modifier.contentReceiver` at all — Compose foundation
1.10.4's legacy `value`/`onValueChange` field ignores it entirely (checked in
bytecode: only `TextFieldDecoratorModifierNode` / `TextFieldSelectionState` read
`ReceiveContentConfiguration`). That is why the #934 field migration above was
necessary for a feature that is not itself about the cursor.

The field only gets the modifier when a receiver is supplied: `.then(if (onImagesReceived
!= null) Modifier.contentReceiver(imageReceiver) else Modifier)`. `imageReceiver` is a
`ReceiveContentListener` built once per package name:

```kotlin
ReceiveContentListener { content ->
    val receive = currentOnImagesReceived ?: return@ReceiveContentListener content
    val description = content.clipMetadata.clipDescription
    val images = mutableListOf<Uri>()
    val rest = content.consume { item ->
        val image = isPastedImageItem(item, description, ownPackage)
        if (image) images += item.uri
        image
    }
    if (images.isNotEmpty()) receive(images)
    rest
}
```

`content.consume { }` classifies each clip item and returns the items it does **not**
consume; the field pastes those as text, the same as before #934. Classification
(`isPastedImageItem` in `AttachmentPicker.kt`) is synchronous and makes no binder
call — safe to run inline inside `onReceive` — and applies the same
[`isForeignContentUri`](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments)
trust boundary a pick already uses, so a clip pointing at this app's own provider is
never accepted. `[NIT, noted in review]` an item the listener consumes but whose
provider later types as non-image (checked off the main thread by
`describePastedImage`) is dropped rather than falling through to the field as text —
it was already taken out of the clip by `content.consume`, so there is nothing left
to paste. This is the plan's documented behaviour, not a bug.

The accepted URIs are handed to `onImagesReceived`
(`rememberPastedImageReceiver` in `AttachmentPicker.kt`, mirroring
`rememberAttachmentPicker`), which describes them off the main thread and joins the
same [`addPickedAttachments`](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments)
sink a pick uses — see that document for the size/count refusals, the send-time
bounded read, and the provider-type re-check. `ThreadScreen` is the only production
caller and passes `onImagesReceived = rememberPastedImageReceiver(onAttachmentsPicked)`;
no other call site changes because the parameter defaults to `null`.

A `TextFieldState` field also exposes a `ScrollBy` semantics action that the legacy
field did not — see [development-verification.md](development-verification.md) for
the test-selector fallout this had on unrelated list-scroll assertions.

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

`ThreadInputBarStyleTest` (`app/src/sharedTest`, native graphics) samples the rendered
fill in empty, focused and typed states for static and wallpaper palettes, with
app and system themes deliberately opposed. Compare the translucent fill after
compositing over the host background, not the raw navy RGB value. Check both
placeholder and entered `TextLayoutResult` styles for 14sp/20sp; a correct theme
token alone does not prove the field uses it. The suite also checks the 52dp
minimum, 48dp send target, wrapping and unchanged height between five and six
lines. Send/stop, draft and paste coverage below protects the existing behaviour.

ViewModel unit tests live in
`app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`.

Two new test methods (the file already had seven from #126/#139):

1. **`sendMessage_blankText_isNoOp`** — instantiates `ThreadViewModel(SavedStateHandle("conversationId" to "seed-channel-personal"), FakeConversationRepository())`, collects `repository.observeMessages("seed-channel-personal")` into a list, captures the initial message count, calls `vm.sendMessage("")` then `vm.sendMessage("   \n\t ")`, then `advanceUntilIdle()`. Asserts the observed message count is unchanged. Pins the VM-side blank rejection.
2. **`sendMessage_nonBlankText_appendsToConversation`** — same setup, captures initial count, calls `vm.sendMessage("Hello world")`, `advanceUntilIdle()`. Asserts the observed count incremented by exactly 1 and the appended `ThreadItem.MessageItem` has `content = "Hello world"`, `role = Role.User`, `sessionId = "seed-session-personal"` (the seeded channel's `currentSessionId`). Pins the VM → repository forwarding contract.

Both tests use the live `FakeConversationRepository()` rather than a recording mock — `seed-channel-personal` is a real seeded record and `observeMessages` re-emits on append, so the end-to-end path is the assertion surface. Uses the existing `Dispatchers.setMain(UnconfinedTestDispatcher())` / `runTest { launch collector ... collector.cancel() }` scaffold already in the file.

The old mic `Toast` onClick was intentionally never unit-tested; it went with the stub in #643, so there is nothing left to skip.

**Instrumented, added in [#643](../codebase/643.md):** `ThreadFrameTest.kt` (`app/src/androidTest/…/thread/`) covers the send/stop precedence directly — `inputButton_sendsWhenTextPresent`, `inputButton_stopsWhileBusyWithEmptyField`, and an additive sixth case `inputButton_isDisabledSendWhenIdleAndEmpty` beyond the plan's original five, plus `busyThread_hasExactlyOneStopControl` at the `ThreadScreen` level. See [Interrupt affordance § Testing](interrupt-affordance.md#testing) and [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md) for the header-side coverage. Pre-existing `ScriptedThreadRenderTest`'s `interrupt_shownWhileBusy_invokesOnTap_goneAfterTurnEnd` stayed green unchanged against the relocated control — it finds `cd_thread_interrupt` by description regardless of where the control lives. `SlashCommandTypeAheadScreenTest` and `ThreadFrameTest` both stayed green across the #934 `TextFieldState` migration — they are the regression suite for [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934), not just this composable's own coverage.

**Added in [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934):**

- `AttachmentPasteTest` (`app/src/test`, Robolectric for `ClipData`) — `isPastedImageItem` accepts a foreign `content:` URI under an `image/png` clip description and refuses a text-only item, a `file:` URI, this app's own authority, and a `content:` URI under an `application/pdf` description; `describePastedImage` keeps an `image/jpeg`-typed URI and drops a `text/plain`-typed one against a Robolectric-registered provider.
- `ComposerPasteTest` (`app/src/sharedTest`, Robolectric) — a `SemanticsActions.PasteText` image paste reaches the attachment path and adds no text; a text paste inserts text into the draft and adds no attachment. Proves the open question in the ticket's plan: `SemanticsActions.PasteText` on a `TextFieldState` field goes through `contentReceiver` the same way a real user Paste does, both under Robolectric and on the device — no text-toolbar workaround was needed.
- `ThreadInputBarDraftBindingTest` (`app/src/sharedTest`, Robolectric) — pins the heap-only fix in [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934): a typed draft must not appear in `SaveableStateRegistry.performSave()`.
- `ThreadInputBarUndoDeviceTest` (`app/src/androidTest`, device-only) — a hardware Ctrl+Z reaches the draft; see [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934) for why this is device-only.
- `ComposerImagePasteDeviceTest` (`app/src/androidTest`, device-only, AC#2) — inserts a real PNG through `MediaStore`, puts it on the real clipboard with `ClipData.newUri`, drives the field's `PasteText` semantics action, and asserts one strip tile named for the file. Device-only because it needs the real clipboard service and a real `MediaStore` provider with its grant and `getType` — Robolectric's clipboard and resolver are shadows and cannot prove the platform path the AC names.

No rung-3 scenario for #934: pasting adds an attachment locally with no daemon exchange; the live attachment exchange belongs to [#674](https://github.com/pyrycode/pyrycode-mobile/issues/674).

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
- **Slash-command completion, not slash-command execution.** [#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) added the [type-ahead](slash-command-type-ahead.md) that completes a typed `/name`, but the text field itself is still a plain `BasicTextField` with no knowledge of commands — a completed `/clear` is ordinary text that reaches the daemon through the same send path as any other message. The pyrycode CLI's `/clear` / `/compact` behavior lives at the conversations-model layer, not in this composable.
- **`fieldState` / `textAtLastEdit` / `accountedText` are unkeyed `remember`s.** A reused composition slot (e.g. a `LazyColumn` cell reuse, which does not currently apply to this composable's own single mount) would keep the previous conversation's field text in memory until the next outside change. What renders is still correct, because the inward `LaunchedEffect(text)` adopts the current `text` regardless — see [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934). Flagged as a non-blocking review NIT in #885; keying on the conversation id would be tidier if slot reuse ever becomes a real concern. **Must stay a plain `remember`, never `rememberSaveable` / `rememberTextFieldState`** — see [§ Draft binding](#draft-binding--cursor-at-end-undo-and-redo-885-934) for why #934 found this load-bearing rather than cosmetic: the field holds live message content, and the saveable saver would put it, undo history included, in the saved-state Bundle.
- **A consumed paste item that turns out non-image is dropped, not pasted as text.** [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934)'s `imageReceiver` decides per item from the clip's declared type before any provider call; an item it accepts is removed from what the field receives, so if the provider's own `getType` later disagrees the item is gone, not returned to the field. See [§ Image paste into the field](#image-paste-into-the-field-934). Noted in review as a known behaviour, not a defect.
- **Keyboard `Send` action vs. multi-line entry.** Some keyboards render the IME `Send` action as a glyph; others fall through to `Done` or `Enter`. The `KeyboardActions(onSend = …)` callback fires only for `ImeAction.Send`. Multi-line entry via `Enter` is the OS's responsibility under `singleLine = false`; no manual `\n` handling needed.
- **Stop is unreachable while the field holds a draft** — see [The message-input button](#the-message-input-button--one-control-two-actions) above; this is the live #643 consequence that superseded the old "mic is a stub" limitation.
- **The disabled send button has no visual dimming** — see [The message-input button](#the-message-input-button--one-control-two-actions) above; a shipped, non-blocking gap, not a design limitation.
- **No voice input, no interim stub.** Removed in #643; Phase 6 owns a fresh design for it whenever it lands.

## Related

- Ticket notes: [`../codebase/188.md`](../codebase/188.md) (original implementation), [`../codebase/187.md`](../codebase/187.md) (the `sendMessage` repository mutator this consumes), [`../codebase/459.md`](../codebase/459.md) (added `isBusy`/`onInterrupt` to `ThreadScreen`, pre-#643), [`../codebase/643.md`](../codebase/643.md) (the Figma `16:8` frame — `Input large` field, mic removal, send/stop button, three-part composer)
- Specs: `docs/specs/architecture/188-thread-input-bar.md`, `docs/specs/architecture/187-sendmessage-on-conversation-repository.md`, `docs/specs/architecture/643-thread-header-and-composer-layout.md`, `docs/specs/architecture/885-slash-command-type-ahead.md` (`onAnchorChanged` + the original `TextFieldValue` cursor fix), `docs/specs/architecture/934-paste-images-into-composer.md` (the `TextFieldState` migration, the heap-only fix, the undo/redo fix, and the image paste receiver)
- Parent: [Thread screen](thread-screen.md) (the screen this mounts into) and [Thread screen — overlays and app bar](thread-screen-how-it-works-overlays-and-app-bar.md) (the `ThreadTopAppBar` rewrite and the status-area/interrupt relocation, both part of the same #643 pass); [Conversation repository](conversation-repository.md) (the `sendMessage` mutator the VM forwards to); [Navigation](navigation.md) (the `conversation_thread/{conversationId}` route this destination lives under); [Dependency injection](dependency-injection.md) (the unchanged Koin `viewModel { ThreadViewModel(get(), get()) }` binding)
- Sibling: [Slash-command type-ahead](slash-command-type-ahead.md) — the [#885](https://github.com/pyrycode/pyrycode-mobile/issues/885) caller of `onAnchorChanged`, and the reason `text` can now change from outside the field mid-composition
- Sibling: [Interrupt affordance](interrupt-affordance.md) — the composable #643 retired from the screen; its stop action lives on this button now
- Sibling: [Thread screen — composer drafts and attachments](thread-screen-composer-drafts-and-attachments.md#composer-pending-attachments) — the [#933](https://github.com/pyrycode/pyrycode-mobile/issues/933) picker/strip and `addPickedAttachments` sink a [#934](https://github.com/pyrycode/pyrycode-mobile/issues/934) paste joins; also `isForeignContentUri`, the trust boundary [§ Image paste into the field](#image-paste-into-the-field-934) reuses
- Figma: [`16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) (parent thread screen), `Input large` (`347:6635`, the field itself), `Input area` (`533:1957`, the three-band composer this field is the middle of). No new visual for #934 — a pasted image reuses the existing `Attachment area` tile (`390:7136`).

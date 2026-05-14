# #78 — Promote-discussion confirmation dialog

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:46-56` — `DiscussionListEvent` sealed interface; you'll add two new variants here
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:58-108` — top-level `DiscussionListScreen` composable; add the `AlertDialog` overlay when `state.pendingPromotion != null` here
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt:18-30` — `DiscussionListUiState` sealed interface; add `pendingPromotion` to the `Loaded` variant
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModel.kt:41-76` — the existing state assembly + `onEvent` handler; you'll replace the `.map` with a `combine(upstream, pendingPromotion)` and fill in three new arms
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:18-47` — interface surface; `promote(conversationId, name, workspace)` is the only method this ticket calls. No interface changes.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt:103-122` — existing `promote` impl; understand what it mutates so the VM test can assert observable side-effects via `observeConversations`
- `app/src/test/java/de/pyryco/mobile/ui/conversations/list/DiscussionListViewModelTest.kt` — existing VM test conventions (`Dispatchers.setMain(UnconfinedTestDispatcher())`, hand-rolled `stubRepo` with `MutableSharedFlow`, `TODO("not used")` on unused methods). Replace `saveAsChannelRequested_isNoOp_inPhase0` and add the new tests in this file using the same conventions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` — the only existing Compose UI test file in the project; mirror its `createComposeRule` + `setContent { PyrycodeMobileTheme { … } }` + `events::add` pattern in the new `DiscussionListScreenTest.kt`. No instrumented test exists for `DiscussionListScreen` yet — this ticket adds the first one for it.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationRow.kt:39-42` — the existing `displayName` fallback (`conversation.name?.takeIf { it.isNotBlank() } ?: "Untitled discussion"`); reuse the *exact same* logic to derive the dialog's display label so the body string and the row text stay in sync
- `app/src/main/res/values/strings.xml` — string-resource file; add the 4 new strings listed below

## Design source

N/A — standard Material 3 `AlertDialog` with default surface, typography, and button styling per the ticket body. No custom visual treatment; the visual-fidelity check is intentionally skipped for this ticket. A follow-up ticket pins the richer promotion dialog (user-entered name + workspace radio group) to Figma when design lands.

## Context

#25 shipped the long-press menu + right-to-left swipe affordances on `DiscussionListScreen`, both firing `DiscussionListEvent.SaveAsChannelRequested(id)`. The VM currently handles that event as `Unit` with a `// TODO(phase 2): show promotion dialog and call repository.promote(...)`. This ticket wires that no-op to a minimal Material 3 confirmation dialog whose Confirm button calls `repository.promote(...)` directly. The dialog *is* the promotion flow — there is no separate "richer" dialog underneath, and there is no user-editable name or workspace picker in this ticket (those land later when their Figma is ready).

`ConversationRepository.promote(conversationId, name, workspace = null)` already exists and is implemented on `FakeConversationRepository`. `name` is non-null and required — the dialog supplies an auto-derived default (see § Name derivation). Delete and archive confirmations are out of scope (no UI surfaces today; fold a dialog in when those surfaces land).

## Design

### Event surface — two new variants on `DiscussionListEvent`

```kotlin
sealed interface DiscussionListEvent {
    data class RowTapped(val conversationId: String) : DiscussionListEvent
    data class SaveAsChannelRequested(val conversationId: String) : DiscussionListEvent
    data object PromoteConfirmed : DiscussionListEvent       // NEW — user tapped Confirm
    data object PromoteCancelled : DiscussionListEvent       // NEW — user tapped Cancel, scrim, or back
    data object BackTapped : DiscussionListEvent
}
```

`PromoteConfirmed` and `PromoteCancelled` are `data object` (no payload) — the VM knows which discussion is pending from its own `pendingPromotion` state. Don't pass the conversation id back through the event; that would let the screen and the VM disagree about which discussion is being promoted, and the screen has no business knowing that detail at confirm-time.

### State surface — `pendingPromotion` on the `Loaded` variant

```kotlin
sealed interface DiscussionListUiState {
    data object Loading : DiscussionListUiState
    data object Empty : DiscussionListUiState
    data class Loaded(
        val discussions: List<Conversation>,
        val pendingPromotion: PendingPromotion? = null,      // NEW — default null preserves existing call sites
    ) : DiscussionListUiState
    data class Error(val message: String) : DiscussionListUiState
}

data class PendingPromotion(
    val conversationId: String,
    val sourceName: String?,   // verbatim `Conversation.name` at request time; null/blank means unnamed
)
```

Why the default `pendingPromotion = null`:

- The existing preview in `DiscussionListScreen.kt:213` and the `loaded_containsOnlyUnpromoted_inSourceOrder` test (line 100 of the VM test file) both construct `Loaded(discussions)` positionally. A default keeps both unchanged.
- The combine in the VM (see below) always supplies a value, so prod codepaths are unaffected by the default.

`PendingPromotion` lives in `DiscussionListViewModel.kt` next to `DiscussionListUiState`. Single field beyond `conversationId` keeps it minimal — the dialog body and the `repository.promote(name = …)` argument are both derived from `sourceName` (see § Name derivation).

### ViewModel — combine upstream with a private `pendingPromotion` flow

```kotlin
class DiscussionListViewModel(private val repository: ConversationRepository) : ViewModel() {
    private val pendingPromotion = MutableStateFlow<PendingPromotion?>(null)

    val state: StateFlow<DiscussionListUiState> =
        combine(
            repository.observeConversations(ConversationFilter.Discussions),
            pendingPromotion,
        ) { discussions, pending -> /* project to Loading/Empty/Loaded(discussions, pending)/… */ }
            .catch { /* unchanged — emit Error */ }
            .stateIn(viewModelScope, WhileSubscribed(STOP_TIMEOUT_MILLIS), Loading)

    // existing navigationChannel unchanged

    fun onEvent(event: DiscussionListEvent) = when (event) {
        is RowTapped -> /* unchanged */
        is SaveAsChannelRequested -> openPromotionDialog(event.conversationId)
        PromoteConfirmed -> confirmPromotion()
        PromoteCancelled -> pendingPromotion.value = null
        BackTapped -> Unit
    }
}
```

The three behaviour seams:

- **`openPromotionDialog(conversationId)`** — read the current `state.value`. If it is `Loaded` and the id is present in `discussions`, snapshot the discussion's `name` and set `pendingPromotion.value = PendingPromotion(conversationId, conversation.name)`. If the state is not `Loaded`, or the id is not in the list (already-promoted race after a stale emission), do nothing. **Don't** re-query the repository — the spec explicitly forbids re-fetching for the display label, and "stale state" is preferable to an inconsistent dialog.
- **`confirmPromotion()`** — snapshot `pendingPromotion.value` into a local; if non-null, set `pendingPromotion.value = null` *first* (dismisses the dialog deterministically before the suspend point), then `viewModelScope.launch { repository.promote(snapshot.conversationId, derivedChannelName(snapshot.sourceName), workspace = null) }`. Clearing first prevents a double-tap from launching two promote calls and prevents the user from seeing a stale dialog during the suspend.
- **`PromoteCancelled`** — single-line: `pendingPromotion.value = null`.

### State assembly — `combine` semantics and the Empty edge

The combine projection has four arms:

| upstream emits      | pendingPromotion | resulting state                              |
| ------------------- | ---------------- | -------------------------------------------- |
| `emptyList()`       | any              | `Empty`                                      |
| non-empty list      | `null`           | `Loaded(discussions, pendingPromotion=null)` |
| non-empty list      | non-null         | `Loaded(discussions, pendingPromotion=pp)`   |
| (initialValue)      | (initialValue)   | `Loading` (until the first combine emission) |

If upstream emits `emptyList()` while a dialog is open (extreme edge — the only discussion got externally archived), state collapses to `Empty` and the dialog disappears. That's intentional: the user can't promote what no longer exists in the list. The `pendingPromotion` flow value stays non-null in that case but is unobservable; the next legitimate `Loaded` emission would re-show it. To avoid the stale revival, clear `pendingPromotion` when the upstream emits empty:

```kotlin
combine(upstream, pendingPromotion) { discussions, pending ->
    when {
        discussions.isEmpty() -> Empty.also { if (pending != null) pendingPromotion.value = null }
        else -> Loaded(discussions, pending)
    }
}
```

Side-effecting inside `combine` is awkward but correct here — the alternative (a separate `onEach { … }`) costs more cognitive load. Document the inline `also` with a one-line comment in the impl.

### Name derivation — single helper, two consumers

```kotlin
private fun derivedChannelName(sourceName: String?): String =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled channel"

private fun displayLabel(sourceName: String?): String =
    sourceName?.takeIf { it.isNotBlank() } ?: "Untitled discussion"
```

Two distinct strings on purpose:

- **`displayLabel`** matches `ConversationRow.displayName` (`"Untitled discussion"` for unnamed discussions) so the dialog body text matches what the user sees on the row — "Save 'Untitled discussion' as a channel?" reads consistently with the row label they just long-pressed.
- **`derivedChannelName`** is what gets persisted as `Conversation.name` post-promote. For unnamed discussions this becomes `"Untitled channel"` because the conversation is now in the channels tier and "Untitled discussion" would be a misleading channel name.

For named discussions, both functions return the same `sourceName` — the discussion's existing name becomes the channel's name verbatim. The ticket body called out "preview text, truncated; falling back to 'Untitled channel' if no preview is available"; given that `DiscussionListScreen` passes `lastMessage = null` to `ConversationRow` (the discussion list has no message-preview surface today), the only "preview" available is `Conversation.name`. Using that as both the dialog identifier and the post-promote name is the simplest faithful reading.

### Composable — render the dialog as an overlay when `state.pendingPromotion != null`

Inside `DiscussionListScreen`, after the `Scaffold { … }` block, add an `if (state is DiscussionListUiState.Loaded && state.pendingPromotion != null) { PromotionConfirmationDialog(...) }` overlay. The dialog is a private composable in the same file:

```kotlin
@Composable
private fun PromotionConfirmationDialog(
    pending: PendingPromotion,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // M3 AlertDialog with:
    //   title    = stringResource(R.string.promote_dialog_title)
    //   text     = stringResource(R.string.promote_dialog_body, displayLabel(pending.sourceName))
    //   confirm  = TextButton(onClick = onConfirm) { Text(stringResource(R.string.promote_dialog_confirm)) }
    //   dismiss  = TextButton(onClick = onDismiss) { Text(stringResource(R.string.promote_dialog_cancel)) }
    //   onDismissRequest = onDismiss   // scrim + back both route here
}
```

The `displayLabel` helper is declared once at file scope alongside `derivedChannelName`. Both the dialog text and the VM's `repository.promote(name = …)` call go through these helpers.

The dialog renders outside the `Scaffold`'s body slot — `AlertDialog` already manages its own window/scrim; placing it inside `LazyColumn` items would scope it incorrectly.

### Wiring — `onConfirm` and `onDismiss` route through `onEvent`

```kotlin
PromotionConfirmationDialog(
    pending = state.pendingPromotion,
    onConfirm = { onEvent(DiscussionListEvent.PromoteConfirmed) },
    onDismiss = { onEvent(DiscussionListEvent.PromoteCancelled) },
)
```

Stateless composable contract preserved — the dialog roundtrips through the existing `(state, onEvent)` shape. No new `MutableState` is hoisted into the screen.

### Destination block (no change required)

`PyryNavHost`'s destination wiring for `Routes.DISCUSSION_LIST` already forwards `SaveAsChannelRequested` to `vm.onEvent(event)`. The two new events (`PromoteConfirmed`, `PromoteCancelled`) also forward to the VM — add them to the `when` in the destination block alongside the existing `SaveAsChannelRequested` arm. The `RowTapped` / `BackTapped` arms remain unchanged. This is the smallest possible navigation edit.

## State + concurrency model

- **`pendingPromotion: MutableStateFlow<PendingPromotion?>`** — private, owned by the VM, lifecycle-bound to `viewModelScope`. Single writer (the VM's `onEvent` handler); UI reads via the combined `state` flow only.
- **`viewModelScope.launch { repository.promote(...) }`** — fire-and-forget. The dialog dismissal is *not* awaited on the promote completion; we clear `pendingPromotion` *before* launching, so the UI updates immediately and the suspend completes in the background. The next `observeConversations` emission drops the now-promoted discussion from the list. No loading spinner, no in-flight UI state.
- **Dispatcher** — inherited from `viewModelScope` (Main.immediate). `FakeConversationRepository.promote` is in-memory `state.update { … }`; no IO dispatcher switch needed. Phase 4's `RemoteConversationRepository` will do its own IO-bound switching internally — the VM stays dispatcher-neutral.
- **Cancellation** — none required. The promote call is short and best-effort; if the user backs out of the screen between confirm and emission, the launch completes in background and the conversation still promotes (which is the correct behaviour — they confirmed).
- **No `Channel` for the dialog** — `MutableStateFlow<PendingPromotion?>` is the right primitive because the dialog is a *state*, not an *event*. Configuration changes (rotation) preserve the dialog correctly because `state` is collected via `collectAsStateWithLifecycle` and `WhileSubscribed(5_000)` keeps the upstream hot across the rotation grace window.

## Error handling

- **`repository.promote` throws (e.g. `IllegalStateException("unknown conversation $id")` from the fake)** — `viewModelScope.launch { repository.promote(...) }` will surface the exception to the uncaught-exception handler. Phase 0 has no error-surfacing surface for this; the VM intentionally does not catch. The ticket body explicitly scopes this to a confirmation dialog with no failure-handling — adding a try/catch + error banner would expand scope and pre-build infrastructure that the rest of the app does not yet have. **If you're tempted to add error-handling here, don't** — the next ticket that needs it will introduce a project-wide pattern.
- **Stale conversation id (already promoted / archived between long-press and confirm)** — `openPromotionDialog` filters by current `Loaded.discussions`; if the id is gone, no dialog opens (silent). At confirm time, if `repository.promote` throws because the conversation no longer exists, see previous bullet.
- **No retry** — single-shot. The fake never fails for a valid id; failure paths are Phase 4 territory.

## Testing strategy

### VM unit tests (`DiscussionListViewModelTest.kt`)

Replace the existing `saveAsChannelRequested_isNoOp_inPhase0` test with three new tests, mirroring the file's existing conventions (hand-rolled `stubRepo` with `MutableSharedFlow`, `Dispatchers.setMain(UnconfinedTestDispatcher())`, `launch { vm.state.collect { } }` to hold `WhileSubscribed` hot, `advanceUntilIdle()` between trigger and assertion):

- **`saveAsChannelRequested_setsPendingPromotion_whenIdIsInLoadedDiscussions`** — emit `Loaded([d1, d2])`, dispatch `SaveAsChannelRequested("d1")`, assert `state.value` is `Loaded(_, pendingPromotion = PendingPromotion("d1", d1.name))`.
- **`saveAsChannelRequested_isNoOp_whenIdIsNotInDiscussions`** — emit `Loaded([d1])`, dispatch `SaveAsChannelRequested("unknown")`, assert `state.value.pendingPromotion == null`.
- **`promoteCancelled_clearsPendingPromotion`** — set up a pending promotion first (via `SaveAsChannelRequested`), dispatch `PromoteCancelled`, assert `state.value.pendingPromotion == null` and **no** promote call was made (use a recording repo that asserts on `promote` invocation).
- **`promoteConfirmed_callsRepositoryPromote_withDerivedName_andClearsPending`** — case A: pending source name is non-blank (`"foo"`) → assert `repository.promote("d1", "foo", null)` was called. Case B: pending source name is `null` → assert `repository.promote("d1", "Untitled channel", null)`. Case C: pending source name is `"   "` (blank) → assert `"Untitled channel"`. In all cases, after `advanceUntilIdle()`, assert `state.value.pendingPromotion == null`.
- **`promoteConfirmed_isNoOp_whenNoPendingPromotion`** — fresh VM, dispatch `PromoteConfirmed` without prior `SaveAsChannelRequested`, assert no `repository.promote` call.

The recording repo can wrap a `MutableSharedFlow` for `observeConversations` and capture `(id, name, workspace)` triples for each `promote` call into a list. The other methods stay `TODO("not used")`.

The existing `loaded_containsOnlyUnpromoted_inSourceOrder` test compares to `DiscussionListUiState.Loaded(discussions)` (positional, no pending arg) — that comparison still passes because the new default `pendingPromotion = null` makes `Loaded(discussions)` and `Loaded(discussions, null)` equal. Don't change that test.

### Compose UI tests (new `DiscussionListScreenTest.kt` under `app/src/androidTest/.../ui/conversations/list/`)

Mirror `ChannelListScreenTest.kt`'s conventions (`createComposeRule`, `setContent { PyrycodeMobileTheme { … } }`, `events::add` for event capture, `InstrumentationRegistry…getString(...)` for string resources). Drive the screen via `state` props directly — these are composable-level tests, not VM tests.

- **`dialog_appears_when_pendingPromotion_isNonNull`** — render with `state = Loaded(listOf(d1), pendingPromotion = PendingPromotion("d1", "alpha"))`; assert the dialog title and a node containing `"alpha"` (substring of the body string) are displayed.
- **`dialog_does_not_appear_when_pendingPromotion_isNull`** — render with `state = Loaded(listOf(d1), pendingPromotion = null)`; assert the dialog title is NOT displayed.
- **`confirm_button_emits_PromoteConfirmed`** — render with a pending promotion; capture events; click the Confirm button by its label string; assert the emitted list equals `[PromoteConfirmed]`.
- **`cancel_button_emits_PromoteCancelled`** — same setup; click the Cancel button; assert `[PromoteCancelled]`.
- **`dialog_body_uses_sourceName_when_present`** — pending = `PendingPromotion("d1", "ad-hoc kotlin question")`; assert a node containing `"ad-hoc kotlin question"` is displayed.
- **`dialog_body_uses_untitled_discussion_fallback_when_sourceName_isNull`** — pending = `PendingPromotion("d1", null)`; assert a node containing `"Untitled discussion"` is displayed.
- **(Optional, defer if Compose APIs make it painful)** **`back_press_emits_PromoteCancelled`** — use `Espresso.pressBack()` after `composeTestRule.activityRule` setup, or rely on `onDismissRequest` routing through the existing `AlertDialog` semantics. If pressing back from a Compose-rule-only test (no activity) is awkward in this codebase, **drop this test and document the gap** — the M3 `AlertDialog` defaults route both scrim taps and back-press through `onDismissRequest`, so by passing `onDismiss = { onEvent(PromoteCancelled) }` we get the AC behaviour by construction. The cancel-button test above is sufficient evidence.

AC #6 (d) says "scrim/back dismiss without calling promote". The composable-level tests cover the event emission; the VM tests cover that `PromoteCancelled` does not call `promote`. Together they satisfy the AC without a dedicated scrim-tap test (Compose's `AlertDialog` does not expose a stable test tag for the scrim).

### Manual smoke

After landing, run the debug build on a device/emulator and exercise: long-press a discussion row → "Save as channel…" → dialog appears with the row's label → Confirm → dialog dismisses → row drops from the discussion list and appears in the channel list. Then repeat with Cancel and verify the row stays in place. This is not a substitute for the instrumented tests, but it catches navigation-wiring regressions the unit tests miss (specifically: that the destination block forwards the two new events to the VM).

## Open questions

- **Truncation of long discussion names in the dialog body.** Spec is silent — the M3 `AlertDialog`'s body text wraps by default. If a discussion happens to have a 200-character name, the dialog will be tall. Acceptable for Phase 0 (no fixed Figma constraint); revisit when the richer dialog lands.
- **String pluralization / locale.** All strings are English-only and non-plural in the existing codebase; no `plurals` resource is needed here. The four new strings follow the existing `<feature>_<role>` naming.

## Strings to add (`app/src/main/res/values/strings.xml`)

- `promote_dialog_title` → "Save as channel?"
- `promote_dialog_body` → "Save \"%1$s\" as a channel?" (uses `stringResource(R.string.promote_dialog_body, displayLabel(...))`)
- `promote_dialog_confirm` → "Save as channel"
- `promote_dialog_cancel` → "Cancel"

The body string keeps the AC's exact phrasing — `Save "<preview>" as a channel?`. Use the Android escaped-quote form `\"` for the literal quotes around the placeholder.

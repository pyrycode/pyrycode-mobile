# Spec: Archive / Delete actions from ChannelInfoSheet (#227)

Wire the Channel Info sheet's placeholder **Archive** and **Delete** buttons to real
repository mutations plus a one-shot pop-back to the channel list. Delete is gated behind
a Material 3 confirmation dialog. Pop-back rides the project's established
`Channel` + `receiveAsFlow` nav pattern.

## Files to read first

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:25-52` — `ThreadEvent` sealed hierarchy; you add 3 members here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:56-76` — `ThreadUiState`; add one `deleteConfirmVisible` field.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:91-165` — the `pending*` `MutableStateFlow`s, the `transientDialogs` combine (3 flows today → 4), and the top-level `state` combine. You add `pendingDeleteConfirm` to `transientDialogs`, **not** the outer 5-arg combine.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt:214-248` — `onOverflowEvent` `when`; the **only** exhaustive match over `ThreadEvent` (confirmed via `codegraph_impact ThreadEvent`). You extend the `Archive` branch and add `Delete`/`DeleteConfirm`/`DeleteDismiss`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt:50-54,125-135` — **copy this nav pattern exactly**: `sealed interface ...Navigation`, `Channel<...>(capacity = Channel.BUFFERED)`, `val navigationEvents = …receiveAsFlow()`, and the "do the suspend repo call, *then* `send` the nav event" ordering inside one `viewModelScope.launch`.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt:137-147` — `ChannelListNavigation` collection site (`LaunchedEffect(vm) { vm.navigationEvents.collect { … } }`); you add the twin block to the thread `composable` at lines 197-218.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:257-275` — the `ChannelInfoSheet` invocation with the placeholder `onArchive`/`onDelete` lambdas + the stale comment naming this ticket. You rewrite the two lambdas and delete the comment.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt:219-236` — how `RenameDialog`/`SaveAsChannelDialog` are rendered from state flags; the delete dialog render slots in here the same way.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/DiscussionListScreen.kt:115-153` — **the confirmation-dialog convention (#78)**: a private `…ConfirmationDialog` composable using `AlertDialog(onDismissRequest, title, text, confirmButton=TextButton, dismissButton=TextButton)`, all `stringResource`, body interpolating a name via `stringResource(id, name)`. Mirror this for `DeleteConfirmationDialog`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt:40-61` — `archive` (throws on unknown id) and `delete` (default `error(...)`; tolerant of unknown ids). **No interface change.**
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt:627-658` — existing archive tests you update; `:897-963` — `RecordingRepo` (it overrides `archive` but **not** `delete`, so it inherits the throwing default — you must add a `delete` override + `deleteCalls`).
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenChannelInfoTest.kt:98-116` — the two instrumented tests (`tapping_archive_emits_only_dismiss`, `tapping_delete_emits_only_dismiss`) whose expected events change.
- `app/src/main/res/values/strings.xml` — add the 4 dialog strings next to `promote_dialog_*`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=20-48

The Channel Info bottom sheet (`surface-container-low`, 28dp corners) with an **Actions** section of four `FilledTonalButton`s (`secondary-container` / `on-secondary-container`, `label-large`) in a 2×2 grid: Rename, Change workspace, **Archive** (`20:95`), **Delete** (`20:97`). The sheet and these buttons already render (landed #226) — **#227 changes only their behavior, not their appearance.** No new pixels.

The delete-confirmation **dialog has no Figma counterpart** — it is a standard Material 3 `AlertDialog` (default surface/typography/buttons), following the project confirmation-dialog convention (cf. #78, `PromotionConfirmationDialog`). Visual-fidelity check intentionally skipped per ticket — N/A by convention, not omission.

## Context

`#226` wired `ChannelInfoSheet` into `ThreadScreen` with display + delegating actions; its Archive/Delete buttons are placeholder no-ops that only emit `ThreadEvent.ChannelInfoDismiss` (see the in-code comment at `ThreadScreen.kt:268-269` naming this ticket). `#251` landed `ThreadEvent.Archive` (archives in place, no nav); `#252` surface-wired it into the overflow menu. `delete` shipped on the repository in `#218`. This ticket fills the two placeholder lambdas with real behavior.

## Design

### Decision 1 — Reuse `ThreadEvent.Archive` (do not add a sheet-scoped archive event)

The sheet's Archive button emits the existing `ThreadEvent.Archive`. Its handler is extended from "archive in place" to "archive → close sheet → pop back to list." This is the more coherent behavior for **both** entry points: after archiving, the conversation leaves the active list, so remaining on its now-orphaned thread is incoherent. The in-place archive from `#251`/`#252` was the half-done placeholder the `ThreadScreen` comment anticipated; reuse *completes* it rather than coupling an unrelated concern.

**Consequence (intentional, acceptable per ticket):** the overflow-menu Archive path now also closes-and-pops. The overflow menu still *emits* `ThreadEvent.Archive` unchanged — only the VM handler behavior changes — so `ThreadScreenOverflowTest`/`ThreadOverflowMenuTest` (which assert event emission, verified at `ThreadScreenOverflowTest.kt:107` / `ThreadOverflowMenuTest.kt:152`) are unaffected.

### Decision 2 — New delete surface on `ThreadEvent`

Add three `data object` members to the `ThreadEvent` sealed interface:

- `Delete` — open the confirm dialog. **No repository call.** Sheet stays open.
- `DeleteConfirm` — proceed: delete, close dialog + sheet, pop back.
- `DeleteDismiss` — cancel: close dialog only; sheet stays open; nothing deleted.

Naming: `Confirm`/`Dismiss` over `Submit`/`Dismiss`. `Dismiss` keeps the dominant `ThreadEvent` suffix (`RenameDismiss`, `ChannelInfoDismiss`); `Confirm` reads correctly for a no-input irreversible action and matches the AC's "confirm" language (the `Submit` pairs in this hierarchy carry form input — delete has none).

### Decision 3 — One-shot nav via `Channel` + `receiveAsFlow`, collected in `MainActivity`

Add to `ThreadViewModel`, copying `ChannelListViewModel`:

```kotlin
sealed interface ThreadNavigation { data object PopBack : ThreadNavigation }
// in the VM:
private val navigationChannel = Channel<ThreadNavigation>(capacity = Channel.BUFFERED)
val navigationEvents: Flow<ThreadNavigation> = navigationChannel.receiveAsFlow()
```

A single-member sealed interface mirrors `ChannelListNavigation` (also single-member) and leaves room for future thread nav. Collected in **`MainActivity`**, not the screen — the consumer (`navController.popBackStack()`) lives in the NavHost. Add to the thread `composable` (alongside `state`/`connectionState` collection):

```kotlin
LaunchedEffect(vm) {
    vm.navigationEvents.collect { event ->
        when (event) { ThreadNavigation.PopBack -> navController.popBackStack() }
    }
}
```

This is the `ChannelListNavigation` collection twin (`MainActivity.kt:140-147`) and calls the same `popBackStack()` that `onBack` already calls — no need to route the programmatic pop through the `onBack` lambda. (Contrast `ArchivedDiscussionsViewModel.effects`, collected *in-screen* — correct there because that effect drives an in-screen snackbar, not navigation. Pop-back is a NavHost concern, so it collects in `MainActivity`.)

### Decision 4 — Delete-confirm dialog state + render

Add `pendingDeleteConfirm = MutableStateFlow(false)` and fold it into `transientDialogs` (3 → 4 input flows; `combine` has a 4-arg overload, and the outer `state` combine stays at 5 args). Surface as `ThreadUiState.deleteConfirmVisible: Boolean = false`.

Render in `ThreadScreen`, gated on `state.deleteConfirmVisible`, alongside the other dialog renders (and independent of the `if (state.channelInfoOpen)` sheet block, so the dialog layers over the still-open sheet):

- `if (state.deleteConfirmVisible) { DeleteConfirmationDialog(displayName = state.displayName, onConfirm = { onOverflowEvent(ThreadEvent.DeleteConfirm) }, onDismiss = { onOverflowEvent(ThreadEvent.DeleteDismiss) }) }`
- `DeleteConfirmationDialog` is a private composable in `ThreadScreen.kt` mirroring `PromotionConfirmationDialog` (`DiscussionListScreen.kt:125-153`): `AlertDialog` with `onDismissRequest = onDismiss` (handles scrim/back tap = cancel, AC #3), `title`, `text` (irreversible warning interpolating `displayName`), `confirmButton`/`dismissButton` as `TextButton`s, all `stringResource`.

Sheet-button wiring in the `ChannelInfoSheet(...)` call — replace the placeholder lambdas and delete the stale comment:

- `onArchive = { onOverflowEvent(ThreadEvent.Archive) }`
- `onDelete = { onOverflowEvent(ThreadEvent.Delete) }`

### Handler contracts (in `onOverflowEvent`)

- `Archive` → `pendingChannelInfo.value = false` (close sheet), then `viewModelScope.launch { repository.archive(state.value.conversationId); navigationChannel.send(ThreadNavigation.PopBack) }`.
- `Delete` → `pendingDeleteConfirm.value = true`. (No repo call — AC #2.)
- `DeleteConfirm` → `pendingDeleteConfirm.value = false; pendingChannelInfo.value = false`, then `viewModelScope.launch { repository.delete(state.value.conversationId); navigationChannel.send(ThreadNavigation.PopBack) }`.
- `DeleteDismiss` → `pendingDeleteConfirm.value = false`. (Sheet untouched — AC #3.)

### String resources (`strings.xml`)

Add next to `promote_dialog_*`:
- `delete_dialog_title` — e.g. `"Delete conversation?"`
- `delete_dialog_body` — irreversible warning with a `%1$s` placeholder for the name, e.g. `"This permanently deletes \"%1$s\" and all its sessions and messages. This can’t be undone."`
- `delete_dialog_confirm` — `"Delete"`
- `delete_dialog_cancel` — `"Cancel"`

## State + concurrency model

- **Single source of state** unchanged: `ThreadViewModel.state: StateFlow<ThreadUiState>`. `deleteConfirmVisible` flows through the existing `pending* → transientDialogs → state` pipeline; no parallel mutable UI state.
- **Mutation-before-nav ordering is load-bearing.** Inside each launched coroutine the suspend repo call (`archive`/`delete`) runs to completion *before* `navigationChannel.send(PopBack)`. Once `MainActivity` receives `PopBack` and calls `popBackStack()`, the thread destination is removed and `ThreadViewModel` is cleared (its `viewModelScope` cancelled). Because the mutation has already returned by the time we `send`, the cancellation cannot truncate it. This is the exact ordering `ChannelListViewModel.CreateDiscussionTapped` relies on.
- **Dispatcher:** default `viewModelScope` (Main) — repo calls are `suspend` and the fake is in-memory; no manual dispatcher switch (matches every existing handler).
- **One-shot guarantee (AC #5):** `Channel(BUFFERED).receiveAsFlow()` delivers each element to its single collector exactly once and does **not** replay consumed elements (unlike a `StateFlow` flag). On rotation, `LaunchedEffect(vm)` re-collects the *same* VM's flow, but the already-consumed `PopBack` is gone — no second pop. This is precisely why the project uses a `Channel` for nav rather than a boolean. No extra de-dupe state needed.

## Error handling

- `archive` throws `IllegalArgumentException` on unknown id; `delete` is a silent no-op on unknown id (per interface contract). In Phase 0 the id always exists (it came from a live thread), so no failure path is reachable. Do **not** add a defensive try/catch or error UI — no such failure has been observed, and the sibling `ArchivedDiscussionsViewModel.RestoreRequested` only wraps `runCatching` because *its* AC scoped a success-snackbar; #227 has no such surface. Match the in-place archive precedent: bare `repository.archive(...)`/`repository.delete(...)`.
- Dialog dismissal (scrim tap, back press) routes through `AlertDialog.onDismissRequest` → `DeleteDismiss` → non-destructive close (AC #3).

## Testing strategy

Unit (`./gradlew test`, `runTest` + `TestScope`, existing `ThreadViewModelTest` harness — `makeVm`, `RecordingRepo`, `launch { vm.state.collect {} }` + `advanceUntilIdle()`):

- **Fixture:** add `deleteCalls: MutableList<String>` + `override suspend fun delete(conversationId) { deleteCalls += conversationId }` to `RecordingRepo` (else it inherits the throwing default and `DeleteConfirm` tests crash). To assert nav, collect `navigationEvents` into a list via a second `launch`.
- **Archive (update existing `onOverflowEvent_archive_…`):** still `archiveCalls == ["…"]`; additionally `channelInfoOpen` ends `false` and exactly one `ThreadNavigation.PopBack` is collected.
- **Delete tap:** `Delete` → `state.deleteConfirmVisible == true`, `deleteCalls.isEmpty()`, no nav event.
- **Delete cancel:** open then `DeleteDismiss` → `deleteConfirmVisible == false`, `channelInfoOpen` unchanged (`true`), `deleteCalls.isEmpty()`, no nav event.
- **Delete confirm:** `DeleteConfirm` → `deleteCalls == ["…"]`, `deleteConfirmVisible == false`, `channelInfoOpen == false`, exactly one `PopBack`.
- **One-shot:** after consuming the `PopBack` from a confirm/archive, no further element arrives (collected list size stays 1); a second `Archive`/`DeleteConfirm` produces its own single event.

Instrumented (`./gradlew connectedAndroidTest`, extend `ThreadScreenChannelInfoTest` — `ComposeTestRule`, `events: MutableList<ThreadEvent>` sink):

- **Update** `tapping_archive_emits_only_dismiss` → expect `[ThreadEvent.Archive]`.
- **Update** `tapping_delete_emits_only_dismiss` → expect `[ThreadEvent.Delete]`.
- **Add** dialog tests: with a state where `deleteConfirmVisible = true`, assert the dialog title/body are displayed; tapping the confirm **Delete** emits `[ThreadEvent.DeleteConfirm]`; tapping **Cancel** emits `[ThreadEvent.DeleteDismiss]`.

## Open questions

- **Dialog title wording for discussions vs channels.** The info sheet is reachable for both promoted (channel) and unpromoted (discussion) conversations. The proposed `delete_dialog_title` ("Delete conversation?") is type-neutral and safe; if the team prefers "Delete channel?" the developer can branch on `state.isPromoted`, but type-neutral copy is the simpler default and avoids a second string. Defer to default unless review objects.

## Size

S. Production: `ThreadViewModel.kt`, `ThreadScreen.kt`, `MainActivity.kt` (3 files, ~85 LOC) + `strings.xml` (resource, not a production `.kt`). New public type: `ThreadNavigation` (1). `ThreadEvent` gains 3 `data object` members (additive to an existing sealed type) and the single exhaustive `when` (`onOverflowEvent`, verified sole consumer via `codegraph_impact`) gains 3 branches — no edit fan-out cascade. No new state machine with reject branches. Within all red lines; no split.

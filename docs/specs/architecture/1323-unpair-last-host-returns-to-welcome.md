# #1323 — Return to Welcome after unpairing the last host

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/host/HostEditor.kt` → `HostEditorController.confirmUnpair` — the one production caller of `PairedServerCollectionStore.remove`; closes the editor only after the removal hook and the workspace clear finish.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `ChannelListViewModel.confirmHostUnpair`, `hostNavigationEvents` — the channel list's controller and its existing one-shot navigation flow pattern.
- `app/src/main/java/de/pyryco/mobile/ui/settings/SettingsViewModel.kt` → `SettingsViewModel.confirmHostUnpair` — Settings' own controller instance (#751).
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost` `CHANNEL_LIST` and `SETTINGS` destinations; the `PAIR_CODE_ROUTE` `Complete` branch, whose `popUpTo(navController.graph.id) { inclusive = true }` is the clear-the-stack idiom reused here.
- `app/src/main/java/de/pyryco/mobile/di/ObservablePairedServerStore.kt`, `di/RelayConnectionRegistry.kt` → `revision`, `hostConnections` — considered and rejected as the trigger (see Design).
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/SettingsNavigationTest.kt` → `start` — the in-memory store + registry + Koin fixture the new navigation test copies.
- `app/src/androidTest/java/de/pyryco/mobile/ui/onboarding/PairCodeScreenTest.kt` — `ViewModelProvider(nav.getBackStackEntry(route))[X::class.java]` reaches the Koin-created view model of a live destination.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=6-32

Welcome is reused unchanged; this ticket only navigates to it. No visual change, so no fidelity check applies. (The Figma MCP was not authorised in this session; nothing here depends on reading the node.)

## Context

Unpairing the only host leaves the operator on an empty channel list, and `PyryNavHost` chooses Welcome only at launch. Desktop's `runUnpairServer` re-reads the servers after a successful unpair and calls `onLastServerUnpaired` when none remain. Mobile copies that.

**Settings entry.** Since #1239 the Settings modal shows only notifications and draws no host editor, so the operator cannot currently unpair from Settings. `SettingsViewModel` still owns a `HostEditorController` and its unpair API. The ticket asks for both entries, so the Settings route collects the same signal; the navigation test drives that route's view model directly, since no control exists to tap. Noted in the PR so the product owner can decide whether the Settings host editor should return or its API be removed.

## Design

**Trigger lives in the controller, not in a store observer.** `HostEditorController` gains

- `val lastHostUnpaired: Flow<Unit>` — fires once after a successful unpair that left no saved host.

In `confirmUnpair`, after the existing `removeDefaultWorkspace` and the `compareAndSet(pending, null)` close, it re-reads `pairedServers.list()`; an empty list sends on a conflated `Channel<Unit>` exposed via `receiveAsFlow()`. A throwing `list()` is logged (`event=host_last_check_failed`, content-free) and treated as "hosts remain": staying on the list is the safe default. Cancellation is rethrown as elsewhere.

Rejected: observing `hostConnections` / `revision` in `PyryNavHost` for a non-empty → empty edge. It would navigate as soon as the registry reconciles, which happens *during* `confirmUnpair` (the revision bumps before `onHostRemoved` and the workspace clear). Popping the channel list clears its view model and cancels `viewModelScope`, cutting that cleanup short. Signalling after the cleanup, from the controller, avoids the race and matches desktop's "re-read after unpair".

Both view models expose it: `val lastHostUnpaired: Flow<Unit> = hostEditor.lastHostUnpaired` (and the Settings equivalent).

`PyryNavHost`: the `CHANNEL_LIST` and `SETTINGS` destinations each add `LaunchedEffect(vm) { vm.lastHostUnpaired.collect { navController.returnToWelcome() } }`, beside the existing `hostNavigationEvents` collector. A private `NavHostController.returnToWelcome()` navigates to `Routes.WELCOME` with `popUpTo(graph.id) { inclusive = true }` and `launchSingleTop = true`, so Welcome is the only entry and Back leaves the app.

## State + concurrency model

The channel is owned by the controller, sent from the unpair coroutine in the owner's `viewModelScope`, and collected in the destination's composition-bound `LaunchedEffect`. Conflated and `trySend`, so a send while nothing collects (rotation) is kept for the next collector and never suspends the unpair. Navigation pops the owner, clearing its view model; the send has already happened by then.

## Error handling

A failed removal already stays on the confirmation (`unpairFailed`) and never reaches the re-read. A failed re-read stays on the list, logged without ids.

## Testing strategy

New shared Robolectric test `app/src/sharedTest/java/de/pyryco/mobile/ui/host/UnpairNavigationTest.kt`, reusing `SettingsNavigationTest`'s fixture shape (mutable in-memory store behind the real `ObservablePairedServerStore`, real registry, in-memory `AppPreferences`, `PyryNavHost` from `CHANNEL_LIST`):

- Channel list, one host: tap `Edit host <name>`, `Unpair host`, confirm → current route is `WELCOME`, and the back stack holds nothing before it (`previousBackStackEntry == null`), so Back cannot reach the list.
- Settings, one host: open Settings, drive its `SettingsViewModel` (from its back stack entry) through open → request → confirm → `WELCOME`, nothing behind it.
- Channel list, two hosts: unpair one → still on `CHANNEL_LIST`.

Existing `HostChannelListViewModelTest` and `SettingsViewModelTest` unpair cases must stay green (run scoped).

## Open questions

- Whether the channel list's confirm button label is exactly `Unpair host` or a separate confirm label — resolve against `EditHostModal` while writing the test.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: the host-editor / pairing overview should record that a last-host unpair returns to Welcome, and the Settings entry's current unreachability.

## Revisions

- Open question resolved, no design change: the unpair confirmation is decided by the shell's `OK` (`EditHostModal` routes `onSubmit` to `onUnpairConfirmed` while confirming), so the channel-list test taps `Unpair host`, sees `Unpair host?`, then `OK`.

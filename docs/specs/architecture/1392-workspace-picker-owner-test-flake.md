# #1392 — flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges flake

## Files read

- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/LiteralScreenNavigationTest.kt` → `flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges`, `assertOwnerPicker`, `start` — the flaky test; `start` gives the relay registry and factory `Dispatchers.Main.immediate`, but the host source comes from `conversationRepositoryModule(true)` unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/NavigationPeer.kt` → `NavigationPeer.outbound` — the frame record the assertion reads.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `openAddWorkspace`, `addWorkspaceRecent` — `openAddWorkspace` rejects a host missing from `hostSource.snapshots.value` (`code=unknown_host`) and sends nothing.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource.relay`, `reconcile` — `snapshots` is published by `reconcile` on its own scope, `Dispatchers.Default` by default.
- `app/src/main/java/de/pyryco/mobile/di/RelayConnectionFactory.kt`, `data/network/NoiseSessionPump.kt` → `send`, `data/repository/RelayRequests.kt` → `sendAndAwaitReply`, `data/repository/WorkspaceCommands.kt` → `recentWorkspaces` — the rest of the request path, all on `Main.immediate` in this test.

## Design source

N/A — test-only fix, no UI change.

## Change

Diagnosis: the step Compose idling does not cover is `HostConversationSource.reconcile`, which publishes `snapshots` from a `Dispatchers.Default` coroutine. Every other step on the path (registry, coordinator, pump, `sendAndAwaitReply`, `NavigationPeer.send`) runs on `Main.immediate`, which Robolectric's idling drains. When the Default thread lags under full-suite load, `openAddWorkspace(a)` runs before host A is in `snapshots`, rejects it as an unknown host, and no `recent_workspaces` frame is ever sent. Reproduced locally by adding a 400 ms `Thread.sleep` before `reconcile` in `HostConversationSource`'s `init` collector: the test fails at the same assertion; a bounded wait on the frame alone then times out, which shows the open itself was rejected, not merely late.

The fix, test-only: before opening, `compose.waitUntil` (5 s) until the Koin-resolved `HostConversationSource.snapshots` holds host A. In `assertOwnerPicker`, the positive `recent_workspaces` check becomes a bounded `compose.waitUntil` on peer A's `outbound`; `assertNoOtherPickerCalls` and the `/B/recent` absence check keep their meaning. `NavigationPeer.outbound` becomes a `CopyOnWriteArrayList`, because on a device `waitUntil` reads it from the instrumentation thread while sends append on main. No production change: the gate in `openAddWorkspace` is correct product behaviour (the list cannot offer a host it does not hold).

## Testing strategy

The fixed test passes with the diagnostic delay in place (all four `LiteralScreenNavigationTest` methods) and without it. `ArchiveNavigationTest` also reads `NavigationPeer.outbound` and is rerun.

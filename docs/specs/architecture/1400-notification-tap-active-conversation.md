# #1400 — Open a notification tap only for an active conversation

## Files read

- `app/src/main/java/de/pyryco/mobile/MainActivity.kt` → `PyryNavHost`'s `LaunchedEffect(openTarget)` — the tap effect this ticket guards; `NavHostController.openThread` — the push it gates.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource.snapshots`, `withRows` — one snapshot per held host; `channels`/`chats` already exclude archived rows, and a cache restore lands before the live list.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → `ThreadDestinationFactory.isSavedHost`, `hostConversationModule` — the saved-host check stays; `HostConversationSource` is already a Koin singleton, so no factory change is needed.
- `app/src/main/java/de/pyryco/mobile/data/cache/ConversationCache.kt` → `readConversations` — the seam the navigation test drives rows through.
- `app/src/sharedTest/java/de/pyryco/mobile/notifications/NotificationTapNavigationTest.kt` — existing graph test (saved host opens, unsaved host stays); extended here.
- `app/src/sharedTest/java/de/pyryco/mobile/di/InertConversationCache.kt` — delegate for the test cache.
- `docs/knowledge/features/navigation.md` § `conversation_thread` — the tap route as documented.

Overlap: `origin/feature/1311`, `1325`, `1329` edit thread-destination arguments in `MainActivity.kt`; none touches the tap effect. A later merge may touch the file.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

N/A for visual fidelity — navigation only. The user lands on the existing channel list (Sidebar); nothing new is drawn.

## Context

The tap opens any conversation on a saved host, including archived or deleted ones. Desktop (`notificationRowFor`, desktop #1597) opens only a row the host's list holds unarchived. Mobile already has the equivalent list in `HostConversationSource.snapshots`.

## Design

In `PyryNavHost`, inject `HostConversationSource` and change the tap effect to:

1. No target → nothing.
2. `destinations.isSavedHost(target.serverId)` false → log `notification_tap_rejected code=unknown_host`, stay. (Unchanged; the activity is exported.)
3. Wait with `withTimeoutOrNull(NOTIFICATION_TAP_ROW_WAIT)` for `snapshots.first { it.holdsActive(target) }`.
4. Found → log `notification_tap_accepted`, `navController.openThread(target)`. Timed out → log `notification_tap_rejected code=inactive_conversation`, stay on the list.

`private fun List<HostConversationSnapshot>.holdsActive(target)`: some snapshot with `serverId == target.serverId` holds a row with `id == target.conversationId` in `channels + chats`.

`private val NOTIFICATION_TAP_ROW_WAIT = 5.seconds` (top-level in `MainActivity.kt`).

Why wait-until-present rather than resolve-on-first-rows: a snapshot cannot tell "not loaded yet" from "loaded and empty", and cached rows can predate a conversation created since. Waiting for the target to appear is the only rule that never opens an unchecked row and still opens one that arrives late. An archived/deleted/unknown target therefore stays on the list after the wait; the user is already on the list during it, so the wait is invisible. Desktop resolves immediately because its list is always live.

## State + concurrency model

The wait runs in the `LaunchedEffect(openTarget)` coroutine (composition scope): leaving the nav host cancels it. `snapshots` is a hot `StateFlow`; `first {}` checks the current value immediately, so a warm or cached tap opens at once. A recreated activity passes `openTarget = null` (unchanged #685 rule), so a rotation during the wait drops the tap — accepted, it never opens anything unchecked.

## Error handling

No new failure surface: a timeout is the "stay on list" outcome. Logs are content-free (static codes only, no ids).

## Testing strategy

Extend `NotificationTapNavigationTest` (shared, Robolectric). Its graph uses `HostConversationSource.relay` with a `ConversationCache`; the test replaces `InertConversationCache` with a delegate whose `readConversations(SAVED)` returns rows from a `CompletableDeferred`, so rows land through the real cache-restore path.

- Active channel row present → thread opens above the channel list (existing test, now seeded with the row).
- Active chat (unpromoted) row present → opens.
- Archived row only → stays on list after advancing past the wait.
- Unknown conversation (other rows present) → stays on list.
- Unsaved host → stays on list (existing).
- Rows arrive after the tap, within the wait → opens.
- Rows never arrive: advance the main clock past `NOTIFICATION_TAP_ROW_WAIT`, then deliver the active row → still on list. Delivering after the wait proves the timeout actually elapsed (a real-time wait still pending would open the thread and fail).

No rung-3 scenario: this guards an existing flow's rejection path; no new operator-facing happy path.

## Open questions

- Does `mainClock.advanceTimeBy` drive `withTimeoutOrNull` in a `LaunchedEffect` under Robolectric? The never-arrive test is written so a "no" fails loudly; resolve in Phase B.

## Documentation handoff (pending — documentation stage)

`docs/knowledge/features/navigation.md` (`conversation_thread` entry, and the linked push-messaging tap-route section as fits): describe the tap rule — saved host, row present and active in the host's snapshot, bounded cold-start wait (`NOTIFICATION_TAP_ROW_WAIT`, 5 s), fallback to the channel list.

## Revisions

- **2026-10-01, implementation.** `NOTIFICATION_TAP_ROW_WAIT` is `internal`, not `private`, so the navigation test advances the clock by the real constant rather than a copy. The open question is resolved: `mainClock.advanceTimeBy` does drive `withTimeoutOrNull` in the effect, and the never-arrive test proves it by releasing the row after the wait and still staying on the list. The test rebinds `HostConversationSource` on `Dispatchers.Main.immediate`, as it already does for the registry, because the compose test's effect dispatcher resumes on the emitting thread and a `Dispatchers.Default` publish would navigate off the main thread. In production the effect runs on the UI dispatcher.

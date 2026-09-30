# #1330 — Title an alert with its conversation's name

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt` → `AttentionNotifier.post` (sets the title to `app_name` today), `agentOf` (the per-host lookup the new `nameOf` mirrors).
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttentionNotifier` single, where `agentOf` is wired over `HostConversationSource.snapshots`.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` → `withNotifier` harness and `theAgentLookupReadsOnlyTheAlertsOwnHostAndIsNullWhenMissing`, the shape the new tests follow.
- `../pyrycode-desktop/src/main/fireNotification.ts` → `notificationTitle` (#1593), the contract copied here.

## Design source

N/A — notifications have no Figma frame (ticket says so).

## Change

`AttentionNotifier` gains a constructor lambda `nameOf: (serverId, conversationId) -> String?` beside `agentOf`. A new top-level `internal fun List<HostConversationSnapshot>.nameOf(serverId, conversationId): String?` returns `Conversation.name` of the matching row in that host's `channels + chats`, null when the host or row is missing — same host-first keying as `agentOf`, so the same id on another host never names it. A new `internal fun notificationTitle(name: String?): String?` cleans the name like desktop's `notificationTitle`: walk by code point, drop control characters (`Character.isISOControl`, which is exactly `\p{Cc}`), stop after 80 kept code points, trim; null when the input is null or the result is empty. `post` sets the title to `notificationTitle(nameOf(...)) ?: getString(R.string.app_name)`. The body text is untouched. The name is never logged. `AppModule` wires `nameOf` over `source.snapshots.value.nameOf(...)`, as `agentOf` is. The notifier has only these two constructor call sites (the test harness and `AppModule`).

Overlap note: `feature/1305` and `feature/1318` also edit `AppModule.kt` in unrelated blocks; this edit is one additive line in the `AttentionNotifier` single.

## Testing strategy

Robolectric unit tests in `AttentionNotifierTest`:
- a named conversation's alert carries the name as title, body unchanged; the same id on another host with a different name gets that host's name.
- an unlisted conversation, a null name, and a name empty after cleaning (only controls and spaces) get `app_name`.
- `notificationTitle` drops control characters, caps at 80 code points, never splits a surrogate pair (81 emoji → 80 emoji, 160 chars), and trims.
- `nameOf` reads only the alert's own host and is null when missing.

## Documentation handoff

None requested by the ticket.

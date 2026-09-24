# #1022 — AttentionNotifier posts nothing for a muted conversation

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionNotifier.kt` → `AttentionNotifier.handle` — the dedup-then-gates `when` the new gate joins; `AttentionNotifier` constructor is the injection point.
- `app/src/main/java/de/pyryco/mobile/di/AppModule.kt` → the `AttentionNotifier` single (#685) — wires `notificationsEnabled` from `AppPreferences`; the mute lookup is wired beside it.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSource.snapshots`, `HostConversationSnapshot`, `withRows` — per-host rows keyed by `serverId`; `withRows` drops archived rows from both `channels` and `chats`.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation.muted` (#999) — `false` when an older daemon omits it.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt` → `withNotifier` — the test file's one construction site.

## Design source

N/A — no UI. The ticket is a notification gate with no visible surface of its own.

## Context

Mute is a host-stored flag (#999) reported on each host's conversation rows; each client silences its own alerts. This adds the phone's silencing. No ADR needed.

## Design

- `AttentionNotifier` gains a constructor parameter `isMuted: (serverId: String, conversationId: String) -> Boolean`, placed after `notificationsEnabled`. Required, no default: the two construction sites (`AppModule`, the test's `withNotifier`) both pass it.
- `handle`'s `when` gains a branch `isMuted(alert.serverId, alert.conversationId) -> "muted"` after `disabled` and before `no_permission`. It sits after the ledger dedup like the other gates, so a muted alert is recorded and spent: unmuting later never posts it. Applies to both `Kind`s.
- A top-level `internal fun List<HostConversationSnapshot>.isMuted(serverId: String, conversationId: String): Boolean` in `AttentionNotifier.kt`: true only when the snapshot whose `serverId` matches holds a row (in `channels` or `chats`) with that `id` and `muted == true`. A host with no snapshot, or a conversation missing from its host's rows, is not muted (fail open). Keying by host first keeps the same conversation id on another host independent.
- `AppModule` resolves the `HostConversationSource` once in the single and passes `isMuted = { serverId, conversationId -> source.snapshots.value.isMuted(serverId, conversationId) }`. It reads the alert's own host's rows, not the selected-host `ConversationRepository`.

## State + concurrency model

`snapshots` is a `StateFlow`; `.value` is a lock-free read, safe from the notifier's IO collector. No new jobs or scopes.

## Error handling

No new failure mode. Absent data fails open to alerting. Log line is the existing `event=attention_alert outcome=muted kind=<turn|prompt>` — content-free, no ids.

Known edge: `withRows` excludes archived rows, so an archived muted conversation reads as missing and alerts. Archived conversations are not expected to produce turns; failing open is the ticket's stated safe side.

## Testing strategy

Robolectric unit tests in `AttentionNotifierTest` (`./gradlew testDebugUnitTest --tests`); `withNotifier` passes a mutable muted-set lookup.

- A muted conversation's turn and prompt both post nothing.
- A muted alert is spent: unmuting and re-emitting the same alert posts nothing.
- A conversation muted on `host-a` still alerts for the same id on `host-b`, via the notifier.
- `List<HostConversationSnapshot>.isMuted`: muted in `channels` and in `chats` → true; unmuted row → false; id missing from its host's rows → false; host with no snapshot → false; same id muted on another host → false.
- Existing tests cover the unmuted path posting as today.

No operator-facing live flow beyond the existing alert path, so no rung-3 scenario.

## Open questions

None.

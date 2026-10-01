# #1332 — Archive shows the most recently archived first

## Files read

- `app/src/main/java/de/pyryco/mobile/data/network/ConversationsPayload.kt` → `ConversationSummaryDto`, `toConversation` — the `conversations` list decode boundary that gains `archived_at`.
- `app/src/main/java/de/pyryco/mobile/data/network/ConversationResponseDto.kt` → `ConversationResponseDto` — the `conversation_updated` record; it does not carry `archived_at` and stays unchanged.
- `app/src/main/java/de/pyryco/mobile/data/model/Conversation.kt` → `Conversation` — gains `archivedAt`; home of the archive key and order.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationListProjection.kt` → `upsertConversation` (the keep-the-stored-`agent` rule the new merge sits beside), `project` (the `lastUsedAt` sort every filter shares).
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt` → `state` — partitions the `Archived` stream into the two tabs.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt` → `ArchiveRow`, `formatArchiveRelativeTime` — the subtitle input.
- `app/src/main/java/de/pyryco/mobile/data/cache/FileConversationCache.kt` → `CachedConversation` — persists `Conversation` without `agent`; see Context for why `archivedAt` stays out of it.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` → `interactiveTurn_archiveRestore_roundTripsListMembership`, `archivedIds`, `createChatOn`, `renameOpenThread`, `cleanupCreatedConversation` — the shape and helpers of the new scenario.
- `docs/knowledge/features/archived-discussions-screen.md` § "Edge cases / limitations" — records that the subtitle used `lastUsedAt` for want of an `archivedAt`; this ticket closes that follow-up (a).
- Daemon: `../pyrycode/docs/protocol-mobile.md`, the `conversations` row of the message table — `archived_at` contract. Daemon handlers confirm `archive_conversation` and `rename_conversation` never bump `last_used_at` (only create and send do), which the e2e scenario relies on.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Visuals do not change: the same avatar-free `ArchiveRow` (`titleMedium` name, `bodySmall` "Archived …" subtitle, trailing restore icon) under the two-tab header. Only the row order and the instant the subtitle is computed from change. No fidelity re-check is needed beyond the existing layout tests staying green.

## Context

The daemon (pyrycode#2698) now reports `archived_at` on every `conversations` row. The owner decided the Archive screen orders newest-archived first, identically on desktop and mobile. Today both the order and the subtitle use `lastUsedAt`.

Not persisted in the conversation cache: `CachedConversation` already omits `agent`, and the cache is a pre-connection placeholder that the live snapshot replaces. Offline, the Archive screen falls back to `lastUsedAt` order — the same rule a legacy row follows — until the first live list arrives. No ADR needed.

In-flight overlap: #1311, #1325 and #1329 also touch `InteractiveStreamE2ETest.kt`; this ticket only appends one `@Test` method, so a later merge is additive.

## Design

### Wire decode (`ConversationsPayload.kt`)

- `ConversationSummaryDto` gains `@SerialName("archived_at") val archivedAt: String? = null`. Kept a raw string so an unparsable time cannot fail the whole list; a non-string JSON value still fails the decode (strict `MobileJson`, no `isLenient`), like a malformed `last_used_at`.
- `toConversation` maps it through a private `parseArchivedAt(String?): Instant?` — `Instant.parse` inside `catch (IllegalArgumentException)` (kotlinx-datetime's `DateTimeFormatException` is one), returning `null` on failure.

### Domain (`Conversation.kt`)

- `Conversation.archivedAt: Instant? = null`. Defaulted, so every existing constructor call compiles unchanged.
- `val Conversation.archiveKey: Instant` — `archivedAt ?: lastUsedAt`.
- `val ArchiveOrder: Comparator<Conversation>` — `archiveKey` descending, then `id` ascending (`String` comparison, i.e. UTF-16 code units).

Both live in `data/model` so the rule is defined once and stays portable.

### Update merge (`ConversationListProjection.upsertConversation`)

`conversation_updated` never carries `archived_at`, so the decoded record always yields `archivedAt = null`. Beside the existing agent rule: when the row already exists and the incoming record is still archived, keep the stored `archivedAt`; otherwise (unarchived, or a row not yet held) the stored value is `null`. A row archived from this phone therefore has no stamp until the next `conversations` snapshot, which the Archive screen's subscription requests.

`project` is unchanged: every filter, `Archived` included, keeps `sortedByDescending { lastUsedAt }`.

### Ordering (`ArchivedDiscussionsViewModel.state`)

Each tab sorts separately: `channels` and `discussions` are each `.sortedWith(ArchiveOrder)` after the partition.

Deviation from the ticket's technical note, which suggested sorting the projection's `Archived` arm: the view model is the single consumer that shows this order, it sits above both the remote and the fake repository, and the acceptance criterion's proof is a view-model test fed by a stub repository — a projection-only sort would leave that test unable to observe the order. The Settings count is the other `Archived` consumer and is order-free.

### Subtitle (`ArchiveRow`)

`formatArchiveRelativeTime(conversation.archiveKey)`. The formatter's parameter is renamed from `lastUsedAt` to `instant`; its buckets are unchanged.

## State + concurrency model

No new jobs, flows or dispatchers. The sort runs inside the existing `combine` transform. The projection merge stays inside the existing atomic `MutableStateFlow.update`.

## Error handling

- Unparsable `archived_at` string → `null`, row kept, falls back to `lastUsedAt`.
- Non-string `archived_at` → the whole `conversations` payload fails to decode and `applySnapshot` drops it, exactly as a malformed `last_used_at` does today.
- Nothing is logged; the timestamp is never logged.

## Testing strategy

Unit (`./gradlew testDebugUnitTest`):

- `ConversationsPayloadTest` — five decode cases: key absent → `null`; `null` → `null`; unparsable string → `null` with the rest of the row intact; valid RFC3339 → that instant; number → the payload decode throws.
- `ConversationListProjectionTest` (new, `data/repository`) — after a snapshot holding a stamped archived row, a `conversation_updated` record for it that is still archived keeps `archivedAt`; one that is unarchived clears it.
- `ArchivedDiscussionsViewModelTest` — one snapshot mixing stamped and legacy rows across both tiers: each tab newest-key first, a legacy row placed by `lastUsedAt` between stamped rows, an equal key broken by id ascending (the input order deliberately disagrees with the expected order and with `lastUsedAt` order).

Shared screen test (Robolectric, `app/src/sharedTest/.../ui/conversations/components/ArchiveRowTest`): a row with `archivedAt` 8 days ago and `lastUsedAt` 90 days ago shows "Archived 1 week ago" and not "Archived 3 months ago".

Rung 3 (live, `InteractiveStreamE2ETest`): `interactiveTurn_archiveTwoChats_listsSecondArchivedFirst`.

- Create chat A, rename it to a unique name; create chat B (newer `last_used_at`), rename it. Archive **B first**, then reopen and archive **A**. Rename and archive do not bump `last_used_at`, so the old `lastUsedAt` order would put B first and the archive order puts A first — the scenario fails on the pre-change code.
- Open Archive (default Discussions tab), wait until both restore buttons exist with A's above B's (the screen first draws from the held projection, where neither row has a stamp yet, and settles when its fresh `list_conversations` reply lands), then assert A's restore button is the topmost one on screen.
- Zero real-Claude turns. Both chats are deleted in `finally`.
- No rung-4 twin: the order depends on the daemon's real `archived_at`, which the scripted fixture does not author.

Evidence for the live scenario is the dispatcher's live gate run after verification.

## Open questions

- None blocking. Whether kotlinx-datetime's `Instant.parse` accepts a numeric offset (`+02:00`) is not relied on: the daemon sends UTC `Z`.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/archived-discussions-screen.md` with the archive order rule (key `archived_at` falling back to `last_used_at`, descending, id ascending tie-break, subtitle from the same key, legacy rows placed by `last_used_at`), and retire the "subtitle uses `lastUsedAt`" limitation and follow-up (a).

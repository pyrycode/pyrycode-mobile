# #1399 — Leave a conversation archived elsewhere

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `conversations` (the shared `observeConversations(ConversationFilter.All)` flow, which keeps archived rows), `sendArchive` (success-only `PopBack`), `navigationChannel`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` → `archive` flips `archived = true` in the same state `observeConversations` maps, so the fake already reproduces "own archive fires both paths".
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt` → `onOverflowEvent_archive_archivesClosesSheetAndPopsBack`, `navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed`, `RecordingRepo`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259 — the Sidebar the user lands on. No new visuals: navigation only, so the visual-fidelity check does not apply.

## Change

`conversations` gains an `onEach` before its `shareIn`: when the emitted list holds this `conversationId` with `archived = true`, the VM leaves for the list. Placing it upstream of `shareIn` runs it once per list emission, whatever the subscriber count, and opens no extra `list_conversations` subscription. A missing row, a rename or a move does not pop (desktop #653 parity). Leaving goes through one private `leaveForList()` that sends `ThreadNavigation.PopBack` behind an `AtomicBoolean` latch; `sendArchive` calls it too, so its own archive — whose reply folds into the list before `archive` returns — pops exactly once whichever path arrives first. Delete keeps its own unconditional `PopBack` (a deleted row disappears and never shows archived). One content-free `RelayLog.d` line, `event=thread_left_archived`, on the list-driven exit.

## Testing strategy

New `ThreadViewModelTest` cases:

- a list update marking the open row archived (fake repo, archived elsewhere via the repo) → exactly one `PopBack`;
- the thread's own Archive on the seeded fake, where both paths fire → exactly one `PopBack`;
- a rename through the repo → no `PopBack`.

`navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed` asserted a second Archive tap yields a second `PopBack`, which the AC now forbids; its "second trigger" becomes `DeleteConfirm`, which keeps the channel-semantics intent.

## Documentation handoff

Pending for the documentation stage: `docs/knowledge/features/thread-screen-how-it-works-state.md` — state that the thread leaves for the list when its row turns archived from any source, once.

# #1331 — Sort channels and chats alphabetically under each host

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/HostWorkspaceGroup.kt` → `groupConversationsByWorkspace` — the pure helper the new comparator sits beside.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ConversationTree`, `treeHost` — the render point; `treeHost` reads `host.channels` / `host.chats` and draws `R.string.untitled_discussion` for an unnamed row. Row keys already carry host + conversation id.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `HostChannelListState`, `HostChannelListEntry` — the state the tree renders; unchanged.
- `app/src/main/java/de/pyryco/mobile/di/HostConversationSource.kt` → `HostConversationSnapshot` — source of `channels` / `chats`; unchanged (its newest-first order still feeds other readers).
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `entry`, `conversation`, `setTree` — fixtures the new screen tests reuse.

In flight: `feature/1334` edits `treeHost`'s row padding in `ChannelListScreen.kt`; different block, built through.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

Sidebar tree. Visuals do not change; only row order within each host's Channels and Chats sections changes, so the visual-fidelity check is a no-op beyond order.

## Context

The owner fixed one sort rule for both apps (ticket body, "The sort rule", items 1–6). Mobile draws newest first today; desktop draws daemon order. Sorting at the render point keeps `ConversationListProjection.project` and every other reader of the snapshot order untouched.

## Design

**`HostWorkspaceGroup.kt`** gains two internal pure functions:

- `conversationSortKey(label: String): String` — trim, `Normalizer.normalize(_, NFKD)`, strip `\p{Mn}+`, `lowercase()`.
- `conversationLabelComparator(placeholder: String): Comparator<Conversation>` — label = `name` when non-blank else `placeholder`; compares by sort key, then trimmed label, then `id`, all via `String.compareTo` (UTF-16 code units).

**`ChannelListScreen.kt`**: `ConversationTree` resolves `stringResource(R.string.untitled_discussion)` and computes, under `remember(hostState.hosts, untitled)`, one `SortedSections(channels, chats)` per host entry. `treeHost` takes the sorted channels and chats as parameters instead of reading `host.channels` / `host.chats`. Row drawing, keys, selection and edit targets are unchanged — they already address the row by `host.serverId` + `conversation.id`.

## State + concurrency model

None new. The sort is a synchronous, pure derivation recomputed whenever `hostState.hosts` changes (every emission), so a rename, auto-name or new chat moves on the next frame.

## Error handling

None: total ordering over strings, no failure modes.

## Testing strategy

- Unit `ConversationLabelComparatorTest` (`app/src/test/.../list/`): the worked example (beta, Alpha, alpha, Émile, zeta, unnamed → Alpha, alpha, beta, Émile, Untitled discussion, zeta); "Chat 10" before "Chat 2"; whitespace trimming; blank name → placeholder; tie on key → trimmed label ("Alpha" before "alpha"); full tie → id.
- Screen tests in `ChannelListScreenTest` (sharedTest, Robolectric):
  - two hosts whose rows interleave alphabetically, two snapshots fed in sequence (rename, auto-name of an unnamed chat, a new chat) → drawn row order per section asserted by bounds top after each emission.
  - rename the selected row so it moves; highlight follows the moved row, and its pen tap emits the target with host + id.

## Open questions

None.

## Documentation handoff

Pending for the documentation stage: add the sort rule (scope, label, key, order, ties, live re-sort) to `docs/knowledge/features/channel-list-screen-tree-and-controls.md`, in the section describing the tree's host sections.

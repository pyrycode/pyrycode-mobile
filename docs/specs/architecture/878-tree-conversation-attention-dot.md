# #878 — Draw each conversation's attention state in the tree

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConversationTreeRows.kt` → `TreeConversationRow`, `IdleStatusDot`, `LegDot`, `TreeRowsPreviewMatrix` — the row that always draws the idle ring; `LegDot` is the in-file precedent for a dot that carries its own description via `clearAndSetSemantics` inside a merging row.
- `app/src/main/java/de/pyryco/mobile/di/ConversationAttention.kt` → `ConversationAttention` — the five-state enum #877 landed (WaitingForAnswer, Running, Failed, Unread, Idle).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListViewModel.kt` → `HostChannelListEntry.attentionFor` — every row's one state, Idle by default.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `treeSection` — the one call site of `TreeConversationRow`, which already holds the row's `entry`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ConnectionStatusLine.kt` → `ConnectionLegCategory.color` — confirms `MaterialTheme.colorScheme.success` / `.warning` extension tokens.
- `pyrycode-desktop/src/renderer/src/screens/channels/ConversationStatusDot.tsx` and `channels.css` § `.conversation-status-dot*` — reference: a shared 1px `primary` ring on every state, a per-state fill inside it, and the working fill blinking opacity 1 → 0.3 → 1 over 2s ease-in-out.
- `app/src/androidTest/.../components/ConversationTreeRowsTest.kt`, `app/src/androidTest/.../list/ChannelListScreenTest.kt` → `setBoundedContent`, `entry`, `setTree` — the fixtures the new tests reuse.

In-flight overlap: #904 edits another block of `ChannelListScreen.kt` and `ChannelListScreenTest.kt`; #875/#883/#889/#895/#904 append to `strings.xml`. All additive, no dependency — a later merge may touch those files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8 (Sidebar adaptation instance `133-259`)

Each `Channel` row leads with an 8dp status dot before the `bodySmall` name, 12dp gap. The frame's rows carry the ring and a few filled variants; the ticket fixes the mapping from desktop: the `primary` 1dp ring on every state, empty for Idle, filled `tertiary` (blinking) for Running, `success` for Unread, `warning` for Waiting for answer, and `error` for Failed, which has no desktop counterpart.

## Change

`TreeConversationRow` gains `attention: ConversationAttention = ConversationAttention.Idle`, after `modifier` so every existing positional call still compiles. `IdleStatusDot` becomes a private `ConversationStatusDot(attention)`: an 8dp box with the existing 1dp `primary` ring on every state and a fill chosen by an exhaustive `when` (Idle → none, Running → `tertiary`, Unread → `success`, WaitingForAnswer → `warning`, Failed → `error`). Running's blink is a `rememberInfiniteTransition` alpha (1f → 0.3f, 2000ms `FastOutSlowInEasing`-style ease-in-out, reverse) created only in the Running branch and read in a `graphicsLayer` lambda, so a blinking dot never recomposes the row. The dot sets `clearAndSetSemantics { contentDescription = … }` from an exhaustive `ConversationAttention` → string-resource mapping; the row's `selectable` merges it with the name, so TalkBack reads e.g. "Running, kitchenclaw refactor".

`treeSection` passes `attention = entry.attentionFor(row.conversation.id)`. The preview matrix gains one row per state.

Strings: `cd_conversation_attention_waiting` "Waiting for your answer", `_running` "Running", `_failed` "Failed", `_unread` "Unread", `_idle` "Idle". Client-owned literals; no daemon text reaches the dot, and nothing is logged (the state is already logged where #877 derives it).

Reduced motion is not handled on the Compose side here: Compose's `MotionDurationScale` follows the system animator scale, and the AC does not ask for more. Compose tests run with infinite animations cancelled by the test `InfiniteAnimationPolicy`, so the blink cannot hang idleness.

## Testing strategy

Compose tests (`app/src/androidTest/`):

- `ConversationTreeRowsTest`: one row over a `mutableStateOf(ConversationAttention)`; for each of the five entries, set the state and assert the unmerged tree holds exactly that state's description and none of the other four. Covers AC2 (all five distinct) and the "dot changes when the state changes" half of AC1.
- `ChannelListScreenTest`: an entry with `attention = mapOf(id to Running)` beside an absent row; assert the channel row carries the Running description and the other row carries Idle — the view-model state reaches the row through the screen (AC1).

No unit test: there is no new logic outside composition. AC3 (live run) belongs to #676; this ticket is not an operator-facing daemon flow of its own, so no rung-3 scenario here.

## Documentation handoff

None named by the ticket. Pending for the documentation stage: fold the dot's state → colour/description mapping into the channel-list tree's feature overview.

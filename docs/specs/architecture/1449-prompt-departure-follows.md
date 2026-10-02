# #1449: a reader on a prompt follows again when the prompt leaves

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadListFollow.kt`: `followStep`, `ListFrame`
  and `FollowNewestEnd`, the #1314 follow rule this ticket extends.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the `promptRowCount` and the
  `FollowNewestEnd` call in the message `LazyColumn`, where the prompt rows sit at indices below the message
  rows.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt`:
  `permissionRequestItems`, the three keyed permission rows.
- `app/src/sharedTest/.../thread/ThreadScreenFollowTest.kt` and `app/src/test/.../thread/ThreadListFollowTest.kt`:
  the screen and pure-rule coverage of #1314.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt`:
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`, its #1312 `performScrollToNode`
  on the token, and `awaitPromptDialog`, which scrolls the list to the permission card.
- `docs/knowledge/features/thread-screen-subagent-tool-rows.md` § The newest-end follow rule (#1314), and
  `thread-screen-how-it-works-list-and-status-row.md` on the #1304 mask. Lesson carried: a frame whose anchor
  key or offset changes is a scroll and recomputes following from position; prompt content and sizes stay
  masked so editing a card never pulls the list.

## Design source

Figma node `16-8` in the ticket. This is a behaviour fix to the existing thread list and adds no visuals, so
the visual check has nothing new to compare.

## Context

The confirmed cause, reproduced by the new screen test before any fix:

1. A reader at index 0 while the prompt is open, with the prompt resolved elsewhere and a short reply
   arriving, is followed correctly on `main`. The ticket's leading suspect does not reproduce: the keyed
   scroll position cannot find the vanished `permission-cancel` row, keeps index 0 at offset 0, and the
   key-change frame recomputes `following = true`.
2. A reader resting on the prompt's card, with the Cancel row just out of view at index 0, is not following.
   The live test leaves the list there: `awaitPromptDialog` calls `performScrollToNode` on the card, and when
   that runs before the follow pin has brought the newly inserted prompt rows into view, the card is not yet
   composed and the action scrolls to the card's index. A card taller than the viewport has the same effect.
   Whether the poll lands before or after the pin is timing, which is why the method flakes.
3. When the prompt leaves, the anchor row (the card) vanishes. The keyed position keeps index 1, which now
   names an older message row, so `followStep` reads a key change, recomputes from position, and stays not
   following. The reply arrives at index 0 below the viewport and is never composed: `threadHeldToken=true`.

A reader on a prompt is reading the newest content in the thread. When that content leaves, nothing newer
than the message rows is left, so the reader is at the newest end and should follow. No decision record is
needed; this refines the #1314 rule.

## Design

`ListFrame` gains `promptRows: Int = 0`: how many prompt rows lead the list in this frame's composition.
`FollowNewestEnd` gains a `promptRows: Int` parameter, read through `rememberUpdatedState` like the others,
and `ThreadScreen` passes its existing `promptRowCount`.

`followStep` gains one rule, ahead of the scroll check: when the previous frame's anchor was a prompt row
(`previous.anchorIndex < previous.promptRows`) and the prompt has left (`current.promptRows == 0`), the step
is `following = true, pin = true`. The pin's `scrollToItem(0)` forgets the stale key, so the next frame lands
on the newest message row, reads as a scroll at the end and keeps following.

Unchanged: a reader whose anchor is a message row while a prompt is open (scrolled into history) is not
touched when the prompt leaves; prompt content and sizes stay masked while a prompt is mounted, so the #1304
guarantee holds; a prompt replaced by another keeps today's behaviour.

## State and concurrency model

No new jobs or flows. The rule runs inside the existing `snapshotFlow` collector in `FollowNewestEnd`, on the
same frame value. A frame whose composition already dropped the prompt while its layout still shows the old
anchor also satisfies the rule, so the pin may fire one frame earlier; the follow-up frame is a scroll at the
end either way. A pin refused under a resting finger leaves `following = true` off index 0, which the
existing retry handles.

## Error handling

None new. `pinToNewest` already absorbs a refused scroll.

## Testing strategy

- `ThreadScreenFollowTest.a_reader_on_a_prompt_resolved_elsewhere_follows_the_reply_that_follows`: a
  following reader, a permission prompt pinned in, the list rested on the card with Cancel out of view
  (`performScrollToIndex(1)`, where the live test's scroll can leave it), the prompt dismissed, the tool row
  done, a short complete reply; the reply is displayed with no scroll by the test. Red on `main`.
- `ThreadScreenFollowTest.a_reader_in_history_while_a_prompt_is_open_stays_there_when_it_is_resolved`: the
  rule does not pull a history reader.
- `ThreadListFollowTest`: the prompt leaving from under the reader follows and pins; the prompt leaving
  while the anchor is a message row changes nothing.
- The existing `ThreadInlineQuestionTest`, `ThreadScreenModalTest` and `ThreadScreenNewestRowTest` run
  unchanged for #1304 and the #1305/#1306 reveal.
- Live: remove the #1312 `performScrollToNode` for the token in
  `interactiveTurn_operatorBypass_permissionControlReflectsTheRunningChild`.

## Open Questions

- Should a prompt replaced by another (a second outstanding prompt) also pin a reader who was on the first?
  Left as today: the reader stays on the new prompt's rows at the same indices.

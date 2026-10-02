# #1509 — A short stream starts under the header

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: the thread `LazyColumn` inside `ThreadScreen`'s `thread-message-region` box, which is `reverseLayout = true` with the default arrangement.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadHistoryRows.kt`: `isNearOldestEnd`, which reads the oldest row's offset from the viewport bottom.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadListFollow.kt`: `FollowNewestEnd`, which reads the first visible index and offset.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenModalTest.kt`: the `ThreadScreen` setup this ticket's test copies for an open permission request.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=640-2646

One message sits directly under the existing 12 dp gap below the top bar, and empty canvas fills the space down to the composer. The pending permission request in `639:2242` is anchored to the top in the same way. There are no new tokens or components.

## Change

For a reversed list, the `LazyColumn` default is `Arrangement.Bottom`, so a stream shorter than the viewport sits on the composer. This change passes `verticalArrangement = Arrangement.Top` instead. Under `reverseLayout` that arrangement places a short stream at the top of the viewport. An overflowing stream fills the viewport, so no arrangement applies to it, and its scrolling is unchanged. On a short list, `FollowNewestEnd` still reads index 0 at offset 0. The oldest row of a short list still ends at the viewport's far edge, so `isNearOldestEnd` stays true. `EmptyThreadState` is a separate branch and does not change.

## Testing strategy

New `ThreadScreenShortStreamTest` under `app/src/sharedTest`. For a one-message thread, it asserts that the message's top sits near the top of `thread-message-region` and that empty space lies below it. For a thread holding only a pending permission request, it asserts the same about `permission-request-card`. It should fail on the bottom-anchored list. `ThreadScreenFollowTest` and `ThreadScreenHistoryTest` run unchanged.

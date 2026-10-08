# #1954 — Folded thread row content types

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadRow.kt`: `ThreadRow`, `foldQueuedRows`, `foldToolRuns` and `listKey` define the folded entries and stable identities.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` constructs `itemsIndexed(reversedRows)` and exposes its list through `LocalThreadListCompositionObserver`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageBubble` dispatches `Role.Tool` to `ToolCallRow`, and user/assistant roles to bubbles.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt`: `ThreadItem` supplies the remaining delivered kinds.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ToolRunCollapseTest.kt`: existing production-screen folding fixtures and interaction coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadReadViewportTest.kt`: production list observer and actual layout metadata access.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadRowsTest.kt`: existing pure folded-row and key contracts.
- `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`: source reversal and key continuity are existing contracts to preserve.
- `docs/knowledge/features/thread-screen-testing.md`: the existing observer avoids adding a testing parameter to the screen.
- `docs/knowledge/features/development-verification-gates.md`: screen tests belong in `sharedTest` and use `AndroidJUnit4` under Robolectric.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected the thread screenshot and structured context, including its message and tool children. The dark thread has alternating message bubbles, rule/label session delimiters, compact bordered tool rows, a translucent header and a bottom composer. Existing Material 3 body-medium typography and theme colour roles remain unchanged; this ticket changes list metadata only.

## Change

Add an internal exhaustive `ThreadRow.contentType(): String` beside `listKey`, returning static kind tokens for message bubbles, individual tools (selected by `Role.Tool`, matching `MessageBubble`), tool-run headers, queued rows, agent-start markers, and each remaining delivered `ThreadItem` kind. User and assistant messages share the bubble type; identity, text, position, nesting and expansion do not enter the mapping. Pass that value through `itemsIndexed`'s `contentType` lambda. Keys, folding, rendering, auxiliary prompt/history slots and state/concurrency behavior remain unchanged. No logging event is added for this metadata-only change. Remote feature overlap check found none.

The written-work forecast is approximately 200 lines across two production files, two test files and this plan; zero new exported types, zero consumer signature changes, one acceptance criterion and no new error branches. This is one independently verifiable reuse contract and stays within the sizing limits.

## Testing strategy

First add `ThreadRowContentTypeTest` under `sharedTest`: mount the real `ThreadScreen` with two different messages, a lone tool, a collapsed tool run and a queued row; read `layoutInfo.visibleItemsInfo.contentType` through the existing observer. Scroll to each keyed item when needed. Assert all types are non-null, the four rendered kinds are distinct, and the two messages share a type. Watch the test fail on the current null metadata before implementation.

Add pure mapping coverage under `test` for every sealed row/item arm and stability across message identity/text/role, tool identity, run expansion, queue correlation and agent completion. Run existing `ThreadRowsTest`, `ToolRunFoldTest`, `ToolRunCollapseTest`, `ThreadScreenFollowTest` and `ThreadScreenShortStreamTest` alongside new tests. Run lint, assembleDebug, Android-test compilation, Spotless and the required final whole unit/shared suite and pre-verify checks. This metadata-only optimization adds no operator-facing flow or device-only test, so it requires no new real-Claude scenario.

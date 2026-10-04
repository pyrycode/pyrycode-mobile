# Streaming reveal on thread open (#1753)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` owns the destination's composition and passes delivered rows to `MessageBubble`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `AssistantMessage` routes streaming content into `StreamingAssistantBody`; its `produceState` retains the reveal position across content updates.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: existing bubble rendering, caret and copy assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRowToggleTest.kt`: existing real-screen mounting pattern.
- `docs/knowledge/features/thread-screen.md`, `message-bubble.md`, `message-bubble-testing.md`, `streaming-assistant-turns.md`: preserve the streaming body's fixed width and composition-owned coroutine cancellation. The overview's claim that a content update resets `produceState` is stale: the producer restarts, but its remembered value survives.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected the design context and screenshot. The thread has left assistant and right user bubbles, a translucent header and composer, and inline session rules. Existing `bodyMedium`, `bodySmall`, bubble colour roles, 20dp gutters and bubble padding remain unchanged; this fix changes only reveal initialization.

## Change

Remember `Clock.System.now()` in `ThreadScreen`, keyed by conversation id, and pass it through a defaulted optional `MessageBubble.threadOpenedAt` parameter and the private assistant renderer. Seed `StreamingAssistantBody` at `content.length` when the message timestamp precedes opening, otherwise at zero. Only the initial value changes: content updates keep the remembered reveal position and the existing 50-character-per-second producer reveals appended text. A fresh thread composition captures a fresh opening time. Standalone bubble callers retain their existing zero-start default. No wire, repository, timestamp generation, caret or layout changes are required.

Overlapping branches #1642 and #1747 touch other blocks of `ThreadScreen`; these edits are additive and local. Repository search supplements codegraph's missing Compose callers: only the thread caller needs updating. Forecast: about 180 written lines in four files, no new exported production types, one updated consumer, two acceptance criteria and no new failure branches; within all builder limits.

## Testing strategy

Add paused-clock shared Compose tests through the real `ThreadScreen`: pre-open content is immediately present, appended text reveals progressively without hiding its prefix, reopening shows all arrived content immediately, and a newly arriving row reveals from zero. Observe the pre-open assertion fail before implementation. Run these tests and existing `MessageBubbleTest`, `MessageBubblePaletteTest`, `MessageMetaRowToggleTest` and `ScriptedThreadRenderTest`, plus lint, assemble, androidTest compilation and forced Spotless verification. Run the existing deterministic `stream` scenario. The operator-facing real-Claude reopen scenario and its held-stream rung-4 twin will be filed as a focused follow-up using the established e2e harness; this ticket's timing contract is proved by the paused-clock tests.

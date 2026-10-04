# Streaming reveal on thread open (#1753)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` owns the destination's composition and passes delivered rows to `MessageBubble`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `AssistantMessage` routes streaming content into `StreamingAssistantBody`; its `produceState` retains the reveal position across content updates.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: existing bubble rendering, caret and copy assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageMetaRowToggleTest.kt`: existing real-screen mounting pattern.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadStreamingRevealTest.kt`: new real-screen paused-clock regression coverage.
- `docs/knowledge/features/thread-screen.md`, `message-bubble.md`, `message-bubble-testing.md`, `streaming-assistant-turns.md`: preserve the streaming body's fixed width and composition-owned coroutine cancellation. The overview's claim that a content update resets `produceState` is stale: the producer restarts, but its remembered value survives.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected the design context and screenshot. The thread has left assistant and right user bubbles, a translucent header and composer, and inline session rules. Existing `bodyMedium`, `bodySmall`, bubble colour roles, 20dp gutters and bubble padding remain unchanged; this fix changes only reveal initialization.

## Change

Remember `Clock.System.now()` in `ThreadScreen`, keyed by conversation id, and pass it through a defaulted optional `MessageBubble.threadOpenedAt` parameter and the private assistant renderer. Seed `StreamingAssistantBody` at `content.length` when the message timestamp precedes opening, otherwise at zero. Only the initial value changes: content updates keep the remembered reveal position and the existing 50-character-per-second producer reveals appended text. A fresh thread composition captures a fresh opening time. Standalone bubble callers retain their existing zero-start default. No wire, repository, timestamp generation, caret or layout changes are required.

Overlapping branches #1642 and #1747 touch other blocks of `ThreadScreen`; these edits are additive and local. Repository search supplements codegraph's missing Compose callers: only the thread caller needs updating. Forecast: about 180 written lines in four files, no new exported production types, one updated consumer, two acceptance criteria and no new failure branches; within all builder limits.

## Testing strategy

Add paused-clock shared Compose tests through the real `ThreadScreen`: pre-open content is immediately present, appended text reveals progressively without hiding its prefix, reopening shows all arrived content immediately, and a newly arriving row reveals from zero. Observe the pre-open assertion fail before implementation. Run these tests and existing `MessageBubbleTest`, `MessageBubblePaletteTest`, `MessageMetaRowToggleTest` and `ScriptedThreadRenderTest`, plus lint, assemble, androidTest compilation and forced Spotless verification. Run the existing deterministic `stream` scenario. The operator-facing real-Claude reopen scenario and its held-stream rung-4 twin are filed as focused follow-up #1762 using the established e2e harness; this ticket's timing contract is proved by the paused-clock tests.

## Revisions

### 2026-10-04 — synthetic first-arrival timestamp (PR #1764 review)

The original timestamp-generation assumption missed `ThreadFold.render`: a live delta can beat the repository projection, and its synthetic row borrowed the previous message's timestamp (or epoch zero). That incorrectly made new text immediate. Add a defaulted `receivedAt: Instant` to `ThreadInput.Live`, captured with the phone clock when the ViewModel maps the live event. Store the first delta's value in `StreamingTurn.startedAt` and render it as the synthetic message timestamp. Appends, ignored deltas and projection changes preserve this value; a new turn captures its own arrival. Reduction and rendering remain pure, and no wire types, repository timestamps, jobs or error branches change. Reopening an existing synthetic row uses its original arrival, while replacing it with a repository row under the same lazy key retains the reveal position.

Additional files read: `ThreadFold.kt` (`ThreadInput.Live`, `StreamingTurn`, `reduceDelta`, `render`), `ThreadViewModel.kt` (`threadItems`), `ThreadFoldSegmentTest.kt` (synthetic suppression), and `ThreadScreenFollowTest.kt` (resting-finger arrival and release). Repository search supplements codegraph's incomplete caller graph: one constructor reference requires adapting to a lambda; the optional input timestamp keeps existing callers compatible. Branch #1642 touches other ViewModel blocks; keep the mapping edit local. Rework forecast: about 160 additional written lines, no new exported production types, two changed constructor consumers and no new reject branches; cumulative scope remains below all limits.

Drive the paused-clock arrival regression through the real fold with both empty and historical projections. Cover pre-open synthetic text, progressive appends, same-key repository replacement and reopening. `ThreadFoldArrivalTest` unit assertions pin first-arrival capture and retention. Repair the follow fixture with a current timestamp for the arriving reply, preserving its offscreen-arrival and follow-after-release assertions. Rerun the entire follow class, fold/streaming ViewModel coverage and the original focused checks. Figma thread and assistant body context/screenshots were inspected again; the repair changes timing only.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/thread-screen.md`, What it does, and `message-bubble.md`, Streaming variant — progressive reveal + blinking caret: immediate arrived text on opening/reopening, progressive post-open appends and the standalone bubble's zero-start default.
- `docs/knowledge/features/message-bubble.md`, Streaming variant — progressive reveal + blinking caret, and `streaming-assistant-turns.md`, Lifecycle, errors, edge cases: correct the stale reset-on-content-change claim; the producer restarts while retaining the remembered reveal position.
- `docs/knowledge/features/streaming-assistant-turns.md`, The fold: synthetic timestamps now capture the first live delta's phone-clock arrival rather than the previous row's time.
- `docs/knowledge/features/message-bubble-testing.md`, Testing, and `thread-screen-testing.md`, Testing: fold-path paused-clock regressions, realistic arrival fixtures and pending live/deterministic reopen coverage in #1762.

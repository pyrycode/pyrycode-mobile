# Word-based streaming reveal with bounded catch-up

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `StreamingAssistantBody` retains the revealed prefix in `produceState`; its content key restarts the producer, not the state.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleTest.kt`: existing streaming caret, copy, and bubble geometry coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadRenderTest.kt`: real thread rendering through the repository fold.
- `docs/knowledge/features/message-bubble.md` and `message-bubble-testing.md`: retain independent caret lifetime and the streaming width; historical claims about resetting the prefix are superseded by current Compose behavior.
- `docs/knowledge/features/thread-screen.md` and `streaming-assistant-turns.md`: the existing thread owns message identity and streaming finalization.

## Design source

N/A. Motion only, on the existing thread frame: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

## Change

Replace the one-character/20 ms reveal with whitespace-delimited words every 33 ms (about 30 words/second). An internal pure `nextStreamingRevealLength(content, revealedLength, stepsRemaining)` returns the next whole-word boundary, including adjacent whitespace and the last arrived word even without trailing whitespace. Divide remaining words across the remaining ticks, rounding up, so any fixed arrived snapshot finishes within 15 ticks (495 ms). The existing content-keyed producer starts a new 15-tick budget for each snapshot and keeps its revealed prefix; the caret, rendering, and cancellation on disposal stay unchanged. No new types, failure modes, or dependencies. Forecast: about 120 written lines including this plan and tests, two acceptance criteria, one production caller, zero reject branches.

Overlap: #1753 adds the initial revealed prefix to `StreamingAssistantBody`; this change stays within its reveal loop and constants, preserving that independent initialization.

## Testing strategy

Write unit assertions first for one-word steps, spaces/tabs/newlines, a final word without whitespace, a retained prefix, empty/finished text, and a 2000-character backlog reaching its end within 500 ms. Run existing `MessageBubbleTest`, `MessageBubbleSelectionTest`, `MessageBubblePaletteTest`, and `ScriptedThreadRenderTest` for caret, rendering, geometry, and interaction regressions. Run the scripted `stream` scenario, lint, assembleDebug, formatting and forced formatting check. File a small real-Claude reveal observation follow-up (transient timing, manually gated as in #482), including a deterministic held-stream twin; this ticket's timing contract is proved by unit tests rather than live delta timing.

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

Replace the one-character/20 ms reveal with whitespace-delimited words every 33 ms (about 30 words/second). An internal pure `nextStreamingRevealLength(content, revealedLength, stepsRemaining)` returns the next whole-word boundary, including adjacent whitespace and the last arrived word even without trailing whitespace. Divide remaining words across the remaining ticks, rounding up. A composition-lifetime producer consumes the latest content through `rememberUpdatedState`, without restarting its delay or countdown on arrivals. Reset the 15-tick budget only when caught up; while behind, count down to revealing all currently arrived text on the deadline tick. Every arrived snapshot is therefore revealed within 495 ms even during continuous arrivals. The revealed prefix, independent caret, rendering, and cancellation on disposal stay unchanged. No new types, failure modes, or dependencies. Revised forecast: about 210 written lines including plan and tests, two acceptance criteria, one production caller, zero reject branches.

Overlap: #1753 adds the initial revealed prefix to `StreamingAssistantBody`; this change stays within its reveal loop and constants, preserving that independent initialization.

## Testing strategy

Write unit assertions first for one-word steps, spaces/tabs/newlines, a final word without whitespace, a retained prefix, empty/finished text, and a 2000-character backlog reaching its end within 500 ms. Run existing `MessageBubbleTest`, `MessageBubbleSelectionTest`, `MessageBubblePaletteTest`, and `ScriptedThreadRenderTest` for caret, rendering, geometry, and interaction regressions. Run the scripted `stream` scenario, lint, assembleDebug, formatting and forced formatting check. Real-Claude reveal observation and its deterministic held-stream twin are handed off to #1765 (transient timing, manually gated as in #482); this ticket's timing contract is proved by unit tests rather than live delta timing.

Add a paused Compose-clock regression in `MessageBubbleTest`: append every 16 ms for two seconds, faster than the reveal delay, asserting progress, a monotonically retained word-aligned prefix, and visibility of each snapshot within about 500 ms (including one 16 ms presentation frame). Begin with a 2000-character backlog, then append while caught up, and prove final catch-up while still streaming. Also compile shared tests for androidTest.

## Documentation handoff

Pending for the documentation stage, per PR #1767's verifier:

- `docs/knowledge/features/message-bubble.md`, "Streaming variant" and "Edge cases / limitations": word cadence, stable reveal clock, catch-up countdown, retained prefix and disposal cancellation; correct historical claims that a content-keyed producer resets its prefix.
- `docs/knowledge/features/message-bubble-testing.md`, "Testing": step-function coverage and frequent-arrival Compose regression.
- `docs/knowledge/features/streaming-assistant-turns.md`, "Lifecycle, errors, edge cases": replace the historical character/restart description with the actual reveal lifetime; carry forward pending observation coverage in #1765 without claiming execution.

## Revisions

2026-10-04: PR #1767's MUST FIX demonstrated that arrivals faster than 33 ms cancel every initial delay. Prefix retention alone cannot preserve the reveal clock or deadline. The revised Change uses a stable producer and latest-content state; arrivals no longer reset the catch-up budget. Add repeated-append Compose coverage before repairing the producer.

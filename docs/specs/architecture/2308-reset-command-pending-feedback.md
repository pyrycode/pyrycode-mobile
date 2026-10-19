# Reset commands without pending feedback (#2308)

## Files read

- `app/src/main/java/de/pyryco/mobile/data/repository/RemoteConversationRepository.kt`: `messageCommands` owns the authoritative registration callback and its legacy-route verdict.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt`: `sendMessage` preserves receipt/error handling and chooses legacy feedback from that verdict.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/AuthoritativeThreadPresentation.kt`: `projectAuthoritativeThread` appends unresolved sends as Sending rows.
- `app/src/test/java/de/pyryco/mobile/data/repository/AuthoritativeSendRepositoryTest.kt`: `repository`, `accepted` and `Pump` provide negotiated repository regression fixtures.
- `docs/knowledge/features/remote-conversation-repository-send-create-promote-rename.md`: ordinary acknowledgements mean receipt only; suppressing all pending rows on ack would violate reconciliation.
- `docs/knowledge/features/remote-conversation-repository-testing.md`: use `runCurrent` to drain the background inbound collector.
- `docs/knowledge/features/thread-screen.md`: authoritative and legacy rows have separate ownership.
- `/work/Projects/pyrycode/docs/protocol-mobile.md`: `send_message` is the wire source of truth for intercepted literals.
- `/work/Projects/pyrycode/internal/relay/handlers/send_message.go`: `conversationResetCommand` uses case-sensitive literals and Go Unicode whitespace boundaries.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Read design context and screenshot on 2026-10-19. The dark thread places right-aligned user bubbles around a rule/label/rule Session reset delimiter, with the composer below. Existing Material theme colours and body typography remain; this repair only removes erroneous provisional rows, requiring no component geometry or asset changes.

## Change

Suppress pending registration for daemon-intercepted reset commands, rather than settling on acknowledgement or boundaries. Add `DaemonResetCommands.kt` with one shared set containing `/clear` and `/reset`, plus an internal first-token classifier using the daemon's Unicode whitespace set (including NEL and NBSP, excluding the JVM-only file/group/record/unit separators). In the authoritative registration callback, retain the retired-source check and authoritative ownership result while bypassing registration and identity lookup for matching text. Sending, list preview, ack/error handling, ordinary pending reconciliation and legacy/demo feedback keep their existing contracts. Emit a content-free structured event when feedback is skipped.

One independently useful behaviour: intercepted commands never create immortal local feedback. Forecast: approximately 220 written lines including plan and matrix regressions; no exported types, signature migrations, new state or reject branches; two acceptance criteria. The nearest analogue #1355 wrote 106 inserted / 23 deleted lines in the send path and repository tests. This remains below all sizing ceilings.

Overlap: #1573, #1947, #2175 and #2245 touch `RemoteConversationRepository`; their diffs change other blocks. Keep the callback guard additive and local. Production capability activation in #2245 is coordination only; existing fake pumps already negotiate thread + interactive.

## Testing strategy

Write failing tests first in `AuthoritativeSendRepositoryTest`: both aliases with surrounding/trailing whitespace, arguments, tabs/newlines and all daemon Unicode whitespace boundaries must leave pending and projected rows absent before/after an ack-only reply, with no legacy snapshot echo. A subsequent ordinary send remains Sending after ack until matching authoritative delivery, then occurs exactly once as the last row. Negative controls include longer names, case changes, later tokens, zero-width space and JVM-only whitespace. A reset must preserve unrelated pending feedback, including across repeated resets.

Run affected repository, reconciliation, projection and existing shared screen coverage on Linux, plus lint, assembleDebug, formatting and final pre-verify after merging main. Full regression/device/scripted suites belong to the dispatcher. No live evidence or documentation change is required by this ticket.

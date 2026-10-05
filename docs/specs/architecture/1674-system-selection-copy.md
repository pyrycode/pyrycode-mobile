# Finished reply system selection Copy coverage

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`: arrival, connection, chat creation, `sendFromPhone`, host conversation identity helpers and live reply waits.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/DeterministicInteractiveStreamE2ETest.kt`: `arriveInSeededThread`, `typeAndSend`, seeded conversation lookup.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageBubbleSelectionTest.kt`: partial clipboard assertion analogue; its substituted toolbar is unsuitable for this ticket.
- `scripts/e2e-emulator.sh`: deterministic scenario dispatch and curated live selection.
- `scripts/android-test-gate.py` and `scripts/test_android_test_gate.py`: scenario enumeration, fresh counted XML and curated-method consistency checks.
- `scripts/e2e-fixtures/ping.jsonl`: completing raw stream-json fixture shape.
- `docs/knowledge/features/message-bubble-testing.md` and `message-bubble.md`: finished-only selection and platform toolbar semantics.
- `docs/knowledge/features/thread-screen.md`: real thread ownership of bubble rendering.
- `docs/e2e-interactive-stream.md`: rung-3/rung-4 harness contracts and evidence ladder.
- `docs/knowledge/features/development-verification.md`: focused device evidence ownership.

## Design source

Figma: N/A: test-only coverage of #1638's existing bubbles; selection handles and Copy are system UI with no separate frame. No visual change is requested.

## Context

Existing bubble coverage replaces the toolbar. This ticket verifies a partial selection through Android's actual Copy action on the phone thread, with live and deterministic variants of the same behavior. Production bubbles remain unchanged. No decision record is needed.

## Design

Add one runnable method to each e2e class. The live method creates a fresh chat and requests exactly `amber cobalt jade` as one plain-text reply. The deterministic method opens the seeded channel and uses a new single-fragment `selection-copy.jsonl` fixture with that reply.

A test-only helper in `app/src/androidTest/.../e2e/FinishedReplySelection.kt` waits on the phone repository for the exact assistant reply with `isStreaming == false`, then waits for the exact body text in the unmerged tree. The prompt is different from the reply, so it cannot match the echoed user body. Obtain the text layout to long-press inside the independently known middle word `cobalt`, using real pointer input. Seed unrelated text into Android's platform clipboard, invoke the actual system Copy item with Espresso, and assert the clipboard is exactly `cobalt` and shorter than the finished reply. No menu or clipboard providers are substituted, and no production tags or dependencies are added.

Register `selection-copy` in the shell scenario dispatch and Python gate scenario enumeration, and register the live method in the curated list. Existing gate consistency tests cover the live floor and list.

Overlap: #1603, #1682, #1689, #1690, #1691, #1693, #1695, #1727, #1735 and #1775 touch the e2e classes or shell harness; their changes concern other methods or additive registrations. Keep this change local and additive.

Forecast: approximately 230–350 written lines including plan and tests; no new production types, no changed consumer signatures, three acceptance criteria and no new state-machine branches. One deliverable with two harness variants; within the ticket limits.

## State and concurrency model

The helper uses bounded test-only `runBlocking`/`withTimeout` collection of the existing repository flow. Compose waits are bounded. No production jobs or state change; the existing activity test rule and isolated harness own teardown.

## Error handling

Failure to finalize, locate a body/layout, open Copy or replace the clipboard fails the selected test. A stale unrelated clipboard cannot pass. Harness errors remain errors, and empty or skipped XML is not evidence.

## Testing strategy

Device-only execution is required for Android's real floating selection menu and platform clipboard. Add a failing gate registration assertion first, observe failure, then wire the scenario. Run the focused `selection-copy` scripted gate and inspect fresh XML for the named deterministic twin, non-zero executed count, zero failures and zero skips. Run affected Python gate tests, compile Android tests, lint, assembleDebug and forced Spotless checks. The new test exercises existing production selection, so no production change is needed for green.

Register `InteractiveStreamE2ETest#interactiveTurn_finishedReply_systemCopyCopiesSelectedWord` for the curated live suite and list its qualified name in the PR. The dispatcher owns full live execution after verification; require fresh counted evidence naming this method as passed. No focused live run is required or claimed.

## Open Questions

None. If the device exposes a different system-menu locator, record the verified locator adjustment under Revisions.

## Documentation handoff

Pending documentation stage: update `docs/e2e-interactive-stream.md`, section "What rung 3 is made of", with the scenario, live method and deterministic twin. Document running `python3 scripts/android-test-gate.py scripted selection-copy` and record counted deterministic evidence and dispatcher-produced full live evidence. Documentation records that evidence rather than obtaining it.

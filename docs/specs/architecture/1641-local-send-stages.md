# #1641 — Sending and waiting before a daemon turn

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `sendInLocalWindow`, `sendMessage`, `sendWithAttachments`, and the availability/turn-state collectors own the local window.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadStatusArea`, `statusArm`, and `StatusReading` select one reading and retain one glyph.
- `app/src/main/java/de/pyryco/mobile/MainActivity.kt`: the thread destination collects and passes the window.
- `app/src/main/res/values/strings.xml`: `thread_thinking_label` and agent-specific status copy establish the client-string pattern.
- `app/src/main/java/de/pyryco/mobile/data/repository/MessageCommands.kt`: `sendMessage` returns only after the correlated acknowledgement, not a started turn.
- `app/src/main/java/de/pyryco/mobile/data/repository/RelayRequests.kt`: `sendAndAwaitReply` correlates acknowledgement by request envelope id.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/StatusArmTest.kt`: existing precedence table.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelLocalSendTest.kt`: gated sends and existing window closure/no-window cases.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusGlyphRotationTest.kt`: controlled animation clock and rotation continuity.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedThreadHarness.kt`: real repository → ViewModel → screen wiring.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedStatusLineTest.kt`: running-turn render regression guard.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadStatusBandTest.kt`: band geometry and glyph ownership.
- `docs/knowledge/features/thread-screen.md`, `thread-screen-how-it-works-list-and-status-row.md`, and `thinking-indicator.md`: preserve running-turn precedence, persistent band, independent rotation and daemon-only token readings.
- `docs/knowledge/features/development-verification-gates.md`: shared screen tests run with Robolectric; exact text measurement needs native graphics.
- Sibling `pyrycode/docs/protocol-mobile.md`: single source of truth for `send_message`, correlated `ack`, and `turn_state`; no wire changes.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=134-5013

Inspected the Input area design context and screenshot. The status row sits above the composer with a leading snowflake, an 8dp gap and primary-coloured M3 bodySmall text (12sp, 16sp line height). Reuse the existing 14 × 16dp glyph, rotation, row placement and typography; this ticket changes only the local label. The surrounding composer/attachment/footer design is outside this change.

## Context

The old local window reads Thinking before Claude has started. A daemon acknowledgement establishes acceptance only; a crash-looping child can leave that window open indefinitely. This ticket makes the acceptance boundary visible without adding a timeout or an interruptible turn. No decision record is needed.

Sizing: one deliverable, five acceptance criteria, approximately 650–850 written lines including tests and plan, one new exported enum, fewer than ten consumers needing simultaneous edits, and three closure categories. This stays within every builder limit. The nearest analogue is #1311; its running-turn implementation is retained. In-flight #1631, #1642 and #1646 share files but change separate menu, queue and geometry blocks; edits here stay local. #1678 owns session-error closure and its pill and is not implemented here.

## Design

Introduce `LocalSendStage` in the thread UI package with `None`, `Sending`, and `Waiting`. Replace the Boolean `localSendPending` flow and screen parameter with `localSendStage`, collected by MainActivity and the scripted harness.

`sendInLocalWindow` opens Sending immediately before calling the repository. On successful return it changes only its still-current Sending window to Waiting and retains the existing accepted-send signal. Each open receives a generation; closing invalidates it. A completion for a closed or replaced generation cannot reopen or mutate a later window, and a failed older send cannot close a newer one.

Any first turn-state phase for this conversation, failed current send, or existing availability change closes the window. Another conversation's turn-state and unrelated live events do not. Blank/refused/upload-failed paths continue returning before opening it.

Add Sending and Waiting arms after running-tool/thinking/working. Both suppress the previous outcome. Higher connection/reset/retry/compaction/stall readings and the screen's permission/question override retain their precedence. Both stages rotate the existing glyph without setting `isBusy` or enabling Stop. Dedicated plain Text readings use bodySmall, primary and existing vertical padding, so no token count can enter either. Use client strings `thread_sending_label`, `thread_waiting_label`, and `thread_waiting_label_codex` beside the thinking label.

## State and concurrency model

All local-window writes remain on viewModelScope's Main dispatcher, alongside the existing send and live-event/availability collectors. The stage is a hot StateFlow; no new job, dispatcher or timer is introduced. Generation comparison after suspension protects closure and overlapping sends. Scope cancellation retains the existing send failure/cleanup path; connection backgrounding uses the existing availability closure.

## Error handling

Preserve repository exception handling and content-free logs. Add a static waiting-stage lifecycle log only when an acknowledgement advances a current window. Failed sends close their own current window and continue through the existing guarded call handling. Upload errors never open a window. No new error pill, timeout or wire rejection is added.

## Testing strategy

Write and observe a failing assertion for Sending versus Thinking before implementing. Extend StatusArmTest for both local stages, stale-outcome suppression, all higher arms and each running-turn arm. Extend ThreadViewModelLocalSendTest for Sending → Waiting → None, all turn-state phases before/after acknowledgement, other-conversation isolation, availability closure before acknowledgement, overlapping send completions and attachment sends; retain blank/refused/upload-failed and accepted-send signal tests.

Add ScriptedLocalSendTest under sharedTest with AndroidJUnit4. Keep controlled outbound capture/correlated-ack scripting in that test; expose only small generic envelope and send hooks on ScriptedThreadHarness, and wire its real composer callback/draft. Drive real repository sends from the rendered composer, independently hold acknowledgement and first turn-state, prove both agents' copy, absence of thinking/tokens/Stop, indefinite Waiting, and closure before acknowledgement. Preserve existing running-turn scripted coverage.

Adapt glyph rotation tests to both stages, continuity across Sending → Waiting, closure and reduced motion; include stages in band geometry/precedence coverage. Run focused unit/shared classes, lint, assembleDebug, compileDebugAndroidTestKotlin and forced spotlessCheck after spotlessApply. No device-only test or stream fixture changes are needed.

Retain `InteractiveStreamE2ETest.interactiveTurn_toolThenText_statusBandNeverEmptyWhileBusy` unchanged. The dispatcher owns the fresh full live suite: executed/failed/skipped counts and confirmation this method ran and passed remain pending. Controlled local transitions are rung-2 proof, not live label races.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thinking-indicator.md`, section “Working and stalled (#1311)”, and `docs/knowledge/features/thread-screen-how-it-works-list-and-status-row.md`, section “The arm order (#1311)”, to describe the new local stages and late-ack protection instead of local Thinking.

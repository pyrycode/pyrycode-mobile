# #965 — live proof: stop a real turn and reset a real session from the phone

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt`
  → `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (the reset method this ticket extends),
  `interactiveTurn_peerQueue_staysConsistentAcrossClients` (the #849 permission-prompt hold and the
  conversation-id discovery via `hostConversationIds`), `sendFromPhone`, `twoHostArg`, the companion's
  `WAIT_PROMPT` comment (why a hold waits on a command, never a bare `sleep`).
- `app/src/androidTest/java/de/pyryco/mobile/e2e/SecondClientPeer.kt` → `awaitPermissionModal`,
  `allowOnce`, `awaitFrame` — the privileged peer that answers the prompt the phone cannot.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadInputBar.kt` → `ThreadInputBar`: the
  composer's one button is Stop (`cd_thread_interrupt`, "Stop the running turn") exactly while the turn is
  busy and the composer is empty.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`: the
  slot ranks resetting above turn outcome; both render as a merged content description equal to the label.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt` →
  `turnOutcomeReport` (`stop_reason: cancelled` → `Interrupted`), `TurnOutcomeIndicator` (label leads with
  `thread_turn_outcome_interrupted`, optionally followed by " · Claude reports …").
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` → `turnOutcome` /
  `nextTurnOutcome`: the report persists until the next turn's `thinking`/`responding`, so it is a durable
  post-condition up to the follow-up send.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ResettingIndicator.kt` →
  `resettingLabelRes`: wrapping-up label and the three restarting labels.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ScriptedResettingTest.kt` — the
  deterministic proof of the restarting phase this ticket leaves in place.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/SessionBoundaryAssertions.kt` →
  `awaitDisplayedSessionBoundary`.
- `docs/knowledge/features/interrupt-send-path.md` — interrupt is fire-and-forget; completion is the
  conversation's `turn_end` with `stop_reason: cancelled`.
- `docs/knowledge/features/permission-modal-overlay.md` — a permission prompt draws as a `MobileGateModal`
  dialog over the thread, on the unprivileged phone too (#687 matched it there).
- `../pyrycode/cmd/pyry/main.go` → `activeSessionStarter.resetThenRotate`; `../pyrycode/cmd/pyry/session_reset.go`
  → `conversationReset.wrapUp`, `wrapUpPromptText`: `wrapping_up` rises before the wrap-up and stays up
  until the wrap-up turn (always run for a live child, up to 400 words, notes enabled or not) has ended;
  `restarting` spans only `startFreshRunner`'s kill and respawn.
- `scripts/e2e-emulator.sh` → the `LIVE` `TEST_TARGET` list; `scripts/android-test-gate.py` → `LIVE_MINIMUM`.
- `docs/e2e-interactive-stream.md` § Live mode, § Verification status, the #482 spinner note.

## Design source

**Figma:** N/A — test-only ticket; no UI changes.

## Context

Stop and Reset session have unit and scripted (rung 2) coverage only. This adds rung-3 live proof on the
curated `LIVE` gate. No production code changes. No ADR warranted.

## Design

### A. New method `interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain`

**The hold.** A turn is held open by a shell command that never returns on its own:
`STOP_HOLD_PROMPT` asks claude to run, in the foreground, `python3 -c "import threading; threading.Event().wait()"`
(waits on an event nothing sets) and then reply with a fixed token `STOP_HOLD_REPLY`. Nothing in the test
waits on time: the command ends only when killed — by the interrupt, or, as a ceiling far outside the test's
step, by the Bash tool's own two-minute timeout.

**Why not stop during #849's pending permission prompt.** A `python3` command raises a permission prompt,
and the phone draws that prompt as a modal dialog over the composer. A stop tap then would reach the
composer through a dialog a real user cannot tap past. So the prompt is still the gate, but the #848 peer
(paired `--allow-remote-permissions`) allows it once; the dialog closes and the command runs, holding the turn
with the composer reachable.

Steps:
1. Channel list, connected; create a chat and discover its conversation id the way #849 does
   (`hostConversationIds` before/after). Open the `SecondClientPeer` on host A.
2. The phone sends `STOP_HOLD_PROMPT` (`sendFromPhone`).
3. The peer `awaitPermissionModal(conversationId)` then `allowOnce` — confirmed by `modal_dismissed`.
4. The phone waits until no permission dialog is on screen and the composer's Stop control
   (`cd_thread_interrupt`) is present, then taps it.
5. **Turn ends, Interrupted outcome (AC1).** The peer's first `turn_end` for the conversation carries
   `stop_reason == "cancelled"`; the phone's status area shows a node whose content description starts with
   `thread_turn_outcome_interrupted`; the Stop control is gone.
6. **Follow-up gets a real reply in the same conversation (AC1).** The phone sends `PING_PROMPT` in the same
   open thread; `awaitDisplayedPingReply`; the peer sees `turn_end` occurrence 2 for the same conversation id.
   Negative check: no bubble carries `STOP_HOLD_REPLY` — the interrupted turn never finished.

Real-claude cost: **two turns** (the interrupted hold and the ping).

### B. Extend `interactiveTurn_newSession_rendersSessionBoundaryDelimiter` (AC2)

After the Reset-session tap, before the existing delimiter wait:
- Wait until the status area shows the wrapping-up label (`thread_resetting_wrapping_up`) and assert the
  delimiter explanation is still absent at that moment.
- Wait until none of the four resetting labels (wrapping-up + three restarting variants) is on screen.
- Then the existing `awaitDisplayedSessionBoundary`.

**Why wrapping-up is causally held.** The daemon raises `wrapping_up` before its wrap-up turn and lowers it
only after that turn — a real claude turn writing a handoff note of up to 400 words — has ended, whether or
not notes are stored. The window is a full claude round trip, not a scheduling race like #482's first token.

**Restarting is not asserted live.** It spans only the kill and respawn in `startFreshRunner`; no lever holds
it open. Restarting stays proven only by `ScriptedResettingTest`, and the PR says so.

Turn cost unchanged (the ping plus the daemon's own wrap-up turn).

### C. Gate lists (AC3)

- Append the new method to the `LIVE` `TEST_TARGET` in `scripts/e2e-emulator.sh` (after the #687 entry).
- `LIVE_MINIMUM` in `scripts/android-test-gate.py`: 20 → 21.

Constants added to the companion: `STOP_HOLD_PROMPT`, `STOP_HOLD_REPLY` (a token shared by no other prompt
and without the word "ping"). Resource strings are read with the existing `string(id)` helper.

## State + concurrency model

Test-only. Peer calls run in `runBlocking` with `withTimeout`, as in #849. The peer is closed in `finally`.

## Error handling

Every wait is bounded (`THREAD_TIMEOUT_MS`, `REPLY_TIMEOUT_MS`); a claude that refuses the command or
backgrounds it ends the turn itself, so no Interrupted outcome appears and the method fails — never a false
pass.

## Testing strategy

The methods are rung-3 live scenarios; they run only under `python3 scripts/android-test-gate.py live`
after the verifier (the ticket carries `needs-real-claude`). Builder checks: `compileDebugAndroidTestKotlin`,
`spotlessApply`, `lint`, `assembleDebug`. No rung-4 twin: the scripted twin of the reset phases already exists
(`ScriptedResettingTest`), and stop is covered at rung 2 by `ScriptedThreadRenderTest`.

## Open questions

- Does claude run the never-returning command in the foreground as asked? If it refuses or backgrounds it, the
  live run fails at step 5 with a clear message; resolve then by rewording the prompt.

## Documentation handoff (pending — documentation stage)

After the live gate passes, update `docs/e2e-interactive-stream.md` § Live mode: add
`interactiveTurn_stopRunningTurn_showsInterruptedThenRepliesAgain` to the curated list, and update the method
count (to 21) and the real-claude turn count (+2). Note that the reset method now also asserts the
wrapping-up phase live, and that restarting is proven only by `ScriptedResettingTest`.

In-flight overlap check: no other `feature/*` branch touches these three files.

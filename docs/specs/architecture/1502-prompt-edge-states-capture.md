# #1502: capture the refused-answer, send-failure and disconnected prompt states against `668:3051`

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/PromptsDesignCaptureTest.kt`: `open`, `show`, `answer`,
  `permission` and `secureCapture` (with `expectSecure`). The three new methods are added here.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt`: the `ThreadViewModel` override passes no
  `answerQuestionBatch`, so a Continue always succeeds. The failing-send input is added here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` (read only): `sendQuestion`
  maps an `IllegalStateException` from the send to `QuestionSendPhase.Failed`; `answerRejected` reads
  `HostModalState.rejectedConversations`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` (read only): the
  `permission-rejection` item, tagged `PERMISSION_REJECTION_TEST_TAG` (`thread-permission-rejection`).
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/QuestionBatchModal.kt` (read only): the
  `question-send-failed` line in `QuestionBatchActions`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` (read only):
  `ModalOptionButton` and `ModalCancelButton` take `enabled = connected`; `AlwaysAllowOffer`'s toggleable row
  does not, so the grant stays usable.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` (read only): reaches
  "Reconnecting in 12s" through `connectionState = ConnectionState.Reconnecting(12)`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=668-3051

Three 412x892 frames in the "Client planning" fixture (draft "My message", "Cxt high: 84%", tune icon):
`668:3054` the Default pill "Your answer was rejected." with its X at the stream's top; `668:3094` the
answered batch with "Couldn't send. Try again." in the error colour 12 px above enabled Cancel and Continue;
`668:3169` the session-grant permission while "Reconnecting in 12s", choices and Cancel at 38 %, grant usable,
Actions without its chevron and Send dimmed. This ticket changes no UI; the frames are the comparison targets.

## Change

`DesignInputs` gains `failQuestionSends`, a volatile flag the override's `answerQuestionBatch` reads: while it
is true the send throws `IllegalStateException`, which `sendQuestion` reports as `QuestionSendPhase.Failed`.
False by default, so every existing capture is unchanged.

`PromptsDesignCaptureTest` gains three methods, each opening "Client planning" through `open`:

- `permissionRejectedFrame`: sets `hostModal` to `rejectedConversations` holding the open chat, waits for
  `answerRejected`, asserts the rejection tag and "Your answer was rejected." are displayed, then captures
  `permission-rejected` against `668:3054` with `expectSecure = false` (no prompt shows).
- `questionSendFailedFrame`: sets `failQuestionSends`, answers the batch as `636:3540`, taps Continue, waits
  for `QuestionSendPhase.Failed`, asserts `question-send-failed` displayed and Cancel and Continue enabled,
  then captures `question-send-failed` against `668:3094`.
- `permissionDisconnectedFrame`: shows the grant permission, sets `connectionState` to `Reconnecting(12)`,
  waits for "Reconnecting in 12s", asserts Allow once, Reject once and Cancel not enabled and the grant row
  enabled, then captures `permission-disconnected` against `668:3169`.

The whole class runs once on `pixel8Api35` with `requireRealSystemBars=true`; the run's XML replaces
`prompts-results.xml`. The three captures are compared with the exports through `scripts/design-compare.py`,
and `design-1220/prompts/index.md` gets an item per frame in the existing shape, with mismatches routed, and
loses the three `## Gaps` entries.

## Testing strategy

Device-only by necessity, like the rest of the class: real pixels on the full image. Each method's
assertions fail if its input does not reach the screen (no rejection pill, no failure line, enabled choices).
`./gradlew compileDebugAndroidTestKotlin` covers the other design classes that build `DesignInputs`.

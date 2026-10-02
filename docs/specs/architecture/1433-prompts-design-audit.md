# #1433 — Audit in-conversation questions and permissions in the assembled app

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt` — `launch`, `capture`, `openKeyboard`, `insets`; `capture` reads pixels with `UiAutomation.takeScreenshot`.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt` — `hostModal`, `questionBatch`, `contextUsage`, `thread`; the override passes no question draft store.
- `app/src/androidTest/java/de/pyryco/mobile/design/ViewportRule.kt` — `@Viewport` size and font scale.
- `app/src/androidTest/java/de/pyryco/mobile/design/OnboardingDesignCaptureTest.kt` — the analogue audit class.
- `app/src/androidTest/assets/design-1220/README.md` — folder layout and the per-item index format.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionCaptureTest.kt` and `QuestionBatchModalCaptureTest.kt` — the #1305 and #1306 component-host captures and their fixtures; both draw the decor view because the prompt sets `FLAG_SECURE`.
- `app/src/main/java/de/pyryco/mobile/data/model/ModalUiState.kt` — `HostModalState`, `scopedTo`.
- `app/src/main/java/de/pyryco/mobile/data/model/QuestionBatch.kt` — `QuestionBatch.conversationId`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — the question collector keeps only a batch whose `conversationId` is the open thread's.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadPermissionModal.kt` — `permissionRequestItems`, `PermissionRequestCard`, `PermissionContext`.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — `createChannel` adds an empty channel the list shows.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=635-2036

The Questions and permissions board, re-read 2026-10-02: four question frames (`636:3279`, `636:3540`,
`636:3803`, `636:4066`), six permission frames (`639:2242`, `639:2451`, `639:2666`, `639:2882`, `639:3099`,
`639:3308`), the switching pair `640:2440` and `640:2646`, and the specification `640:2838`. Each frame is the
"Client planning" chat with the draft "My message" and 84 % context, the prompt in the stream above the
footer, server text inert, safe default as the filled primary button.

## Context

#1305 and #1306 moved questions and permissions into their own conversation. Their captures were taken on a
component host. This audit captures the assembled `MainActivity` through the #1430 harness and routes every
mismatch. No production code changes.

## Design

One capture class, `PromptsDesignCaptureTest` in `de.pyryco.mobile.design`, writes
`design-1220/prompts/`. Evidence and `index.md` are committed under
`app/src/androidTest/assets/design-1220/prompts/`.

Setup per method: `design.paired = true`; channels created on the fake repository through Koin
(`createChannel`) before launch, so the thread is empty as in Figma; `contextUsage` at 84 %; open the
channel from the list; type "My message" into the composer before the prompt arrives; then set
`questionBatch` or `hostModal` with a fixture copied from the frames' text.

Methods and frames:

| Method | Viewport | Frames |
|---|---|---|
| `questionFrames` | 412x892 | `636:3279` unanswered, `636:3540` Kotlin, Kotlin and Other "Web" selected |
| `questionKeyboardFrame` | 412x792 | `636:3803`: Other focused, test IME open |
| `questionCompactFrame` | 320x700 at 150 % | `636:4066`, scrolled to the end; every control scrolled to and displayed |
| `permissionFrames` | 412x892 | `639:2242`, `639:2451`, `639:2666`, `639:2882`, `639:3099` |
| `permissionCompactFrame` | 320x700 at 150 % | `639:3308`: grant ticked, Allow once armed; every control reachable |
| `promptsWaitInTheirOwnChats` | 412x892 | `640:2440`, `640:2646`, plus each prompt chat |

States are reached through the UI where it can: option taps, the grant checkbox, the Allow once tap that
arms, the Other field. The permission and trust prompts replace the held prompt with a new `modalId` per
frame.

**Secure window capture.** Both prompts set `FLAG_SECURE` on the activity window, so the harness's
`UiAutomation` screenshot is blacked out. The class draws the decor view into a bitmap, as the #1305 and
#1306 captures did, and writes the same `.png` and `.txt` pair `DesignCapture.capture` writes, adding
`secure=true`. Platform bars are not drawn; the README already excludes them from comparison. With the
keyboard open the bitmap is cropped to the window height minus the IME inset.

**Keyboard viewport.** Figma's `636:3803` is 552 px of app above a 340 px keyboard. The harness's test IME
is 240 px, so the method runs at 412x792, leaving the same 552 px of app height.

Comparisons: `scripts/design-compare.py` for each capture against its `get_screenshot` export.

## State and concurrency model

Test-only. Inputs are hot `MutableStateFlow`s in `DesignInputs`; the test waits on view-model state or
semantics with `waitUntil` before each capture.

## Error handling

A capture fails on a blank bitmap or a missing secure flag while a prompt is open. A control the frame
needs that cannot be scrolled to and displayed fails the method, and the index records it.

## Testing strategy

Device-only by nature: real pixels on `pixel8Api35` with `requireRealSystemBars=true`, a real IME inset and
real `wm size` and font scale. Run with the README's focused command, class-scoped. The JUnit XML is
committed as `prompts-results.xml`.

The harness override passes no question draft store, so each thread opening builds a fresh store and a
question draft does not survive leaving the chat inside the harness. Draft retention across chats is
therefore not judged from these captures; the index says so and names the production binding.

## Open Questions

- Whether the channel list at this commit resembles `640:2440` closely enough to judge row by row, or only
  that the list stays reachable while prompts wait. Resolve from the capture.

## Documentation handoff

None. The README already describes how audits add a subfolder.

## Revisions

### 2026-10-02 — verifier rework on PR #1492

- **Secure flag is asserted.** The verifier found that `secureCapture` recorded `FLAG_SECURE` but never
  asserted it. It now takes `expectSecure` (default `true`) and fails when the window's flag differs. The
  list and the prompt-free chat expect `false`. It also restores `DesignCapture.capture`'s not-black check.
  Under `requireRealSystemBars=true` it now reads the decor view's own root insets, because `insets()`
  substitutes synthetic bars and could never fail the check.
- **The composer draft goes through the view model, not typing.** The plan said the test types "My message".
  `open()` calls `ThreadViewModel.onDraftChange`, the call the composer's text field makes. Typing would open
  the IME in every frame that Figma draws without a keyboard, and each capture would then have to close it.
  The new contract: the draft is set through `onDraftChange`, and only the keyboard frame and the switching
  sequence use the keyboard.
- **The Other text goes through the view model; focus goes through the UI.** The plan said the Other field is
  reached through the UI. Option toggles are tapped, but the text "Web" is sent as
  `QuestionModalEvent.OtherTextChanged`, for the same reason. The keyboard frame still focuses the Other field
  by tap and opens the test IME.
- **The switching sequence closes the keyboard before capturing.** The verifier found `switch-other-chat` captured
  with the IME open, because the visibility check ran before the IME appeared. The method now opens the
  keyboard on the composer with `openKeyboard`, which waits for the IME, types, closes it with `closeKeyboard`,
  asserts no IME inset, and then captures the full 892 px for `640:2646`.
- **No-leak checks read view-model state.** The question and permission no-leak checks now assert
  `questionModal == null` and `currentModal !is Open` on the open thread's view model, instead of counting
  lazily composed nodes.
- **Verdicts are measured against `get_metadata`.** The re-audit found layout mismatches that the first pass had
  marked "match": the gap between permission choices, `PermissionContext` spacing, the Other row in
  `QuestionBlock`, and the compact button heights. They are routed to the new #1501. Three frameless prompt states
  are listed under Gaps and routed to #1502.

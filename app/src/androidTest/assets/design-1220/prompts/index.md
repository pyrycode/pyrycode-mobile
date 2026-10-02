# Questions and permissions audit (#1433)

- **App commit:** `main` at `ddebd393` (the merge base of `feature/1433` at the rework capture), plus the
  test-only `PromptsDesignCaptureTest`. The first pass captured at `4c755aa6`; no file under `app/src/main`
  changed between the two.
- **Figma:** the Questions and permissions board `635:2036` of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported
  with `get_screenshot` on 2026-10-02. Gaps, sizes and positions are measured against `get_metadata`
  coordinates, not by eye. Behaviour reference: the specification `640:2838`.
- **Capture:** `PromptsDesignCaptureTest` on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`, density 1.0, fixed dark theme, real 24 px status and navigation bars. Each `.txt`
  records the measured values.
- **Result:** `prompts-results.xml`, the whole class in one run after the second rework: 6 executed, 0 failures.
  Every capture here comes from that run, except the four `question-*` captures, re-captured for #1484, and the
  six permission and trust items, re-captured for #1483 below.
- **#1484 re-capture:** `feature/1484` (merge base `ca50716f`) changed the question actions. The whole class ran
  again in one run on the same device and arguments: 6 executed, 0 failed, 0 skipped, now in `prompts-results.xml`.
  The `question-unanswered`, `question-answered`, `question-keyboard` and `question-compact` captures and their
  comparisons come from that run; `question-answered.png` came out byte-identical. The switching captures are kept
  from the #1433 run, and the permission and trust captures from the #1483 run.
- **Re-capture for #1483:** the six permission and trust items below were re-captured on `feature/1483`, from a
  whole-class run of `PromptsDesignCaptureTest` on 2026-10-02 (6 executed; `permissionFrames` and
  `permissionCompactFrame` passed; `questionKeyboardFrame` failed on the known 332 px keyboard, see that item).
  A run that starts with `permissionFrames` draws its first capture over a 63 px navigation bar, so the whole class
  ran to keep every bar at 24 px. The switching captures stay #1433's; `prompts-results.xml` and the question
  captures are #1484's.

**How these captures differ from onboarding's.** Both prompts set `FLAG_SECURE` on the activity window while
they show, which blacks out the harness's `UiAutomation` screenshot. The class draws the decor view into a
bitmap instead (`decorViewDraw=true secure=true` in each `.txt`), as #1305 and #1306 did. The platform's
status and navigation bars are therefore not drawn; the app's own content under them is.

**Fixture.** Each frame is an empty channel "Client planning" created on the fake repository, the composer
draft "My message", 84 % context, and the frame's own prompt text. Choices, the grant checkbox and the
arming tap go through the UI. The draft "My message" is set through the view model's `onDraftChange`, the call
the composer makes, and the Other text "Web" through `OtherTextChanged`, so no keyboard opens outside the
keyboard frame. The keyboard frame focuses the Other field through the UI.

**Footer, every frame.** The shipped footer reads "Cxt high: 84%" in the warning colour where every frame reads
"Cxt: 84%", and it draws the Status opener (tune icon) beside the paperclip. Each item records this under
Typography, Colour and Icon paths and routes it to #1485, which holds the design decision.

**Bars.** The app keeps the thread inside the 24 px status bar and the frames have none, so the app's header
sits 24 px lower than Figma's. As in the onboarding audit, that move is not a mismatch; everything else is
compared at 1:1.

**Top-bar title at 150 %.** In both compact frames (`636:4066`, `639:3308`) the title "Client planning" is
about 26 px from cap to descender in the app and about 34 px in Figma. Android 14+ scales large text
non-linearly, which likely explains it, but the board then draws something the platform cannot render. It is a
mismatch in both items, routed to #1501, which decides whether the board's compact frames show platform
scaling or the app matches the frames.

### Questions · Unanswered — `636:3279`

- **Owning ticket:** #1305 (inline questions), #1299 (question components)
- **Capture:** `question-unanswered.png` (412x892, 1.0)
- **Side-by-side:** `question-unanswered-side-by-side.png`
- **Overlay:** `question-unanswered-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: each question card is about 6 px taller than Figma's 242 px (the Other row, see Spacing). Otherwise as Figma inside the bars: the 24 px status bar pushes the stream down, so "Claude has questions" and the LANGUAGE header scroll under the top bar; the batch is reachable by scrolling |
| Padding | match: 20 px gutters, 16 px card padding |
| Spacing | mismatch in the Other row: Figma puts the field 28 px below the "Other" label's top and the radio top-aligned with the label; the app's field sits about 6 px lower and its radio is centred in a 48 dp row, about 10 px below the label. Kotlin to Rust rows match at 56 px; actions 12 px below the card |
| Typography | mismatch: footer "Cxt high: 84%". Question, option label and description, header and actions match |
| Colour | mismatch: footer label in the warning colour. The disabled Continue matches since #1484: the primary fill and on-primary label at 38 % opacity, as `636:3535` |
| Borders | match: primary-container card border, outlined Cancel |
| Radii | match |
| Icon paths | mismatch: footer tune icon. Header snowflake glyphs, radios and checkboxes match |
| Component state | match: nothing selected, Continue disabled, "Waiting for answers" |

- **Routed:** #1501 (Other row spacing and radio alignment), #1485 (footer). Disabled Continue fixed by #1484

### Questions · Answers selected — `636:3540`

- **Owning ticket:** #1305, #1299
- **Capture:** `question-answered.png` (412x892, 1.0)
- **Side-by-side:** `question-answered-side-by-side.png`
- **Overlay:** `question-answered-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: cards about 6 px taller, as Unanswered |
| Padding | match |
| Spacing | mismatch: the Other row, as Unanswered |
| Typography | mismatch: footer "Cxt high: 84%". The "Web" draft and every label match |
| Colour | mismatch: footer warning colour. Selected radio and checkboxes and the enabled Continue match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. Filled radio and checkmarks match |
| Component state | match: Kotlin; Kotlin and Other "Web"; Continue enabled |

- **Routed:** #1501 (Other row), #1485 (footer)

### Questions · Other and keyboard — `636:3803`

- **Owning ticket:** #1305
- **Capture:** `question-keyboard.png` (412x792 window, 240 px test keyboard, 412x552 app area, 1.0)
- **Side-by-side:** `question-keyboard-side-by-side.png`
- **Overlay:** `question-keyboard-overlay.png`
- The test IME is 240 px and the frame's keyboard is 340 px, so the window is 792 px tall and the app has the
  frame's 552 px above the keyboard. The capture is cropped at the keyboard's top. The test asserts the 240 px
  inset, and the `.txt` records the keyboard that showed: one run of this rework drew a 332 px keyboard.
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | match since #1484: at the focus scroll position the focused Other field and both Cancel and Continue are fully in view, the actions' bottom at the stream's bottom edge above "Waiting for answers", as Figma. The focus scroll places the actions first, so the position no longer depends on the IME animation; the test asserts the field and both actions inside the message region |
| Padding | match |
| Spacing | mismatch: the Other row as Unanswered. The actions sit 4 px above the stream's edge, from the batch's item gutter |
| Typography | mismatch: footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: Other focused with "Web" (the caret blinks and is in its off phase in this capture); the draft is kept |

- **Clipping and reach:** nothing is covered at the focus scroll position (`assertAboveComposer` in the test,
  without a scroll of its own). Both actions also scroll into view with the keyboard still open (`reachable`).
- **Routed:** #1501 (Other row), #1485 (footer). Actions under the footer fixed by #1484

### Questions · Compact text at 150% — `636:4066`

- **Owning ticket:** #1305
- **Capture:** `question-compact.png` (320x700, 1.5)
- **Side-by-side:** `question-compact-side-by-side.png`
- **Overlay:** `question-compact-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Figma's Cancel and Continue are 52 px tall (118 and 143.5 px wide); the app's are about 42 px. Since #1484 the stacked pair sits at the card's start edge with Cancel centred over Continue, as `636:4325` |
| Padding | match |
| Spacing | mismatch: the Other row, as Unanswered |
| Typography | mismatch: the top-bar title is about 26 px against Figma's 34 px (see "Top-bar title at 150 %"); the action labels are smaller than the frame's linear 150 %; the footer reads "Cxt h…". Every label wraps |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, both questions, Cancel
  and Continue each scroll into view and display.
- **Routed:** #1501 (button heights and their smaller labels, the top-bar title, Other row), #1485 (footer, truncation)

### Permission · Safe default — `639:2242`

- **Owning ticket:** #1306 (inline permissions), #1300 (permission components), #1483 (frame fixes)
- **Capture:** `permission-safe-default.png` (412x892, 1.0)
- **Side-by-side:** `permission-safe-default-side-by-side.png`
- **Overlay:** `permission-safe-default-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the request is anchored to the stream's end with empty space above it; Figma starts it under the header. The title "Permission required" is the card's first line and Cancel is start-aligned 12 px under the card, as Figma |
| Padding | match: 16 px card padding, 20 px gutters |
| Spacing | mismatch: Figma puts Reject once 8 px below Allow once (y 0–40, then 48); the app shows 16 px, because the 48 dp touch floor around each 40 dp button moves layout. In the context block Figma measures 28 px from label to value, 36 px between the Reason and Folder groups and 36 px before the choices; the app measures 24, about 28 and about 28 px |
| Typography | mismatch: footer "Cxt high: 84%". The title (16 px Medium, on-background), the "Folder" label and every other style match |
| Colour | mismatch: footer warning colour. Filled primary safe default and outlined Allow once match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: the status row reads "Waiting for permission" beside the snowflake. Server order and the Reject once default match |

- **Routed:** #1509 (placement), #1501 (choice gap, context spacing), #1485 (footer)

### Permission · Session grant offered — `639:2451`

- **Owning ticket:** #1306, #818 (session grant), #1483
- **Capture:** `permission-grant-offered.png` (412x892, 1.0)
- **Side-by-side:** `permission-grant-offered-side-by-side.png`
- **Overlay:** `permission-grant-offered-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: placement, as Safe default. Title inside the card and start-aligned Cancel match |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default. The checkbox row and rule text match |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The unchecked checkbox matches |
| Component state | match: "Waiting for permission"; grant offered and unchecked |

- **Routed:** #1509, #1501, #1485

### Permission · Session grant selected — `639:2666`

- **Owning ticket:** #1306, #818, #1483
- **Capture:** `permission-grant-selected.png` (412x892, 1.0)
- **Side-by-side:** `permission-grant-selected-side-by-side.png`
- **Overlay:** `permission-grant-selected-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: placement, as Safe default. Title inside the card and start-aligned Cancel match |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour. The checked checkbox matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The checkmark matches |
| Component state | match: "Waiting for permission"; ticked by a tap, with nothing armed or answered |

- **Routed:** #1509, #1501, #1485

### Permission · Confirm Allow once (armed) — `639:2882`

- **Owning ticket:** #1306, #451 (arm then confirm), #1483
- **Capture:** `permission-armed.png` (412x892, 1.0)
- **Side-by-side:** `permission-armed-side-by-side.png`
- **Overlay:** `permission-armed-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: placement, as Safe default. Title inside the card and start-aligned Cancel match |
| Padding | match |
| Spacing | mismatch: "Tap Allow once again to confirm." sits between Allow once and Reject once as Figma, but about 12 px from each button where Figma has 8 px, the touch-floor gap of Safe default; context block as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". The hint (12 px Medium, on-primary-container), title and "Folder" label match |
| Colour | mismatch: footer warning colour. The tonal armed fill matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; armed by the first tap with its hint, grant still ticked and nothing sent |

- **Routed:** #1509 (placement), #1501 (choice and hint gaps, context spacing), #1485

### Trust · Safe default — `639:3099`

- **Owning ticket:** #1306, #1483
- **Capture:** `trust-safe-default.png` (412x892, 1.0)
- **Side-by-side:** `trust-safe-default-side-by-side.png`
- **Overlay:** `trust-safe-default-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: placement, as Safe default. "Trust this folder?" inside the card and start-aligned Cancel match |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour. "Don't trust" filled as the default matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; server order and the default |

- **Routed:** #1509, #1501, #1485

### Permission · Compact text at 150% — `639:3308`

- **Owning ticket:** #1306, #1483
- **Capture:** `permission-compact.png` (320x700, 1.5)
- **Side-by-side:** `permission-compact-side-by-side.png`
- **Overlay:** `permission-compact-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Figma's Allow once, Reject once and Cancel are 52 px tall (Cancel 118 px wide); the app's are about 42 px. Title inside the card and start-aligned Cancel match |
| Padding | match |
| Spacing | mismatch: the hint sits under the armed choice as Figma, with the touch-floor gaps of Safe default; context block as Safe default |
| Typography | mismatch: the top-bar title is about 26 px against Figma's 34 px (see "Top-bar title at 150 %"); the button labels are smaller than the frame's linear 150 %; footer "Cxt h…". The hint and "Folder" label match. Every label wraps |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; grant ticked and Allow once armed with its hint |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, prompt, grant, both
  choices and Cancel each scroll into view and display.
- **Routed:** #1501 (button heights and their smaller labels, the top-bar title, choice gaps, context spacing), #1485 (footer, truncation)

### Switch chats while prompts wait — `640:2437`

- **Owning ticket:** #1305, #1306, #1338 (list waiting marks), #1337 (grant draft per conversation)
- **Capture:** `switch-list.png` against `640:2440` (412x892, 1.0); `switch-other-chat.png` against `640:2646`,
  keyboard closed (412x892, 1.0); `switch-question-chat.png`, the question in "Client planning" (412x892, 1.0);
  `switch-permission-chat.png`, the permission in "kitchenclaw refactor" (412x892, 1.0)
- **Side-by-side:** `switch-list-side-by-side.png`, `switch-other-chat-side-by-side.png`
- **Overlay:** `switch-list-overlay.png`, `switch-other-chat-overlay.png`
- `promptsWaitInTheirOwnChats` holds a question for "Client planning" and a permission for "kitchenclaw refactor"
  at once. Each chat shows only its own prompt, asserted on the view model's `questionModal` and
  `currentModal`. "Release notes" shows neither and takes a draft typed through the keyboard, which is closed
  again before the capture. Back on "Client planning" the question is still there.
- `640:2646` draws "Release notes" with an earlier message and a "Ready" status line. The harness channel is
  empty, so the app shows its empty state. The thread surface itself is #1432's audit.
- `640:2440` is the full channel tree with servers and folders. #1431 audits Channel List `15:8`, not this
  frame, so this item judges that the list stays usable while both prompts wait, and the waiting marks below.
- **Specification `640:2838`, asserted on the view models:**
  - Item 3, switching away clears the arm: Allow once is armed in "kitchenclaw refactor", the test leaves for
    "Release notes" and returns, and `armedOptionId` is null with the permission still shown.
  - Composer drafts stay with their chat: back in "Release notes" the draft is "Release notes draft", and back
    in "Client planning" it is still "My message". Neither is set again on return.
- **Not judged here, with the reason and the owner:**
  - **List waiting marks.** `640:2440` draws "Client planning" with a filled status dot and the other rows
    hollow. The harness feeds prompts to the thread's view model only, and the list reads
    `ConversationAttention` from the host source, so every row in `switch-list.png` is idle. Routed to #1507,
    a harness input and a capture against `640:2440`.
  - **Item 4, the session-grant checkbox kept across chats.** The override passes no `PermissionDraftStore`
    (`DesignInputs`), so each thread opening builds a fresh one and a ticked grant cannot survive the switch in
    the harness. Production binds the shared store; #1337 owns it and covers it in unit tests.
  - **Question drafts across chats.** The override passes no question draft store either, so each thread
    opening builds a fresh one. Production binds the host's shared store, which #1305 covers in unit tests.
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Component state | match for what is judged: each prompt inside its own conversation, the list and another chat open and usable while both wait, the arm cleared on return and composer drafts kept. Not judged: the list's waiting marks (every row idle in the capture, against the filled dot in `640:2440`), the grant draft and question drafts across chats, as listed above |

- **Routed:** #1507 (list waiting marks against `640:2440`)

## Gaps

Prompt states reachable from `MainActivity` that have no frame on board `635:2036` (re-read 2026-10-02).
They are not captured here, and #1502 asks for their frames:

- **Refused permission answer:** the `permission-rejection` item in `ThreadScreen.kt`, a `NoticePill` reading
  `permission_answer_rejected` in the slot the card held. Owning ticket: #1340. Routed: #1502.
- **Question send failure:** the `question-send-failed` line in `QuestionBatchActions` (`QuestionBatchModal.kt`).
  Owning ticket: #1305. Routed: #1502.
- **Prompts while disconnected:** answers are disabled while the host is not connected. Owning ticket:
  #1321. Routed: #1502.

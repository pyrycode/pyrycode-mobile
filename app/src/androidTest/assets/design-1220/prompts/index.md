# Questions and permissions audit (#1433)

- **App commit:** `main` at `ddebd393` (the merge base of `feature/1433` at the rework capture), plus the
  test-only `PromptsDesignCaptureTest`. The first pass captured at `4c755aa6`; no file under `app/src/main`
  changed between the two. Every capture now is `feature/1501`'s, as the re-capture bullets below say.
- **Figma:** the Questions and permissions board `635:2036` of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported
  with `get_screenshot` on 2026-10-02. Gaps, sizes and positions are measured against `get_metadata`
  coordinates, not by eye. Behaviour reference: the specification `640:2838`.
- **Capture:** `PromptsDesignCaptureTest` on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`, density 1.0, fixed dark theme, real 24 px status and navigation bars. Each `.txt`
  records the measured values.
- **Result:** `prompts-results.xml` is #1501's whole-class run, below: 6 executed, 0 failed, 0 skipped. Every
  capture in this folder now comes from that run.
- **#1484 re-capture:** `feature/1484` (merge base `ca50716f`) changed the question actions. The whole class ran
  again in one run on the same device and arguments: 6 executed, 0 failed, 0 skipped. The `question-unanswered`,
  `question-answered`, `question-keyboard` and `question-compact` captures and their comparisons come from that
  run; `question-answered.png` came out byte-identical. After the rework that limits the actions reveal to the last
  question, the whole class ran once more on `feature/1484` with `main` at `9fef692a` merged in: 6 executed, 0
  failed, 0 skipped, and all four question captures came out byte-identical. #1501's run has since replaced
  them.
- **Re-capture for #1483:** the six permission and trust items below were re-captured on `feature/1483`, from a
  whole-class run of `PromptsDesignCaptureTest` on 2026-10-02 (6 executed; `permissionFrames` and
  `permissionCompactFrame` passed; `questionKeyboardFrame` failed on the known 332 px keyboard, see that item).
  A run that starts with `permissionFrames` draws its first capture over a 63 px navigation bar, so the whole class
  ran to keep every bar at 24 px. #1501's run has since replaced them.
- **#1501 re-capture:** `feature/1501` (merge base `e3f57945`) changed the permission context and choice spacing
  and the Other row. The whole class ran once on the same device and arguments, starting with
  `questionCompactFrame`: 6 executed, 0 failed, 0 skipped, now in `prompts-results.xml`. Every `.png` and `.txt`
  here, and every comparison, comes from that run; the question keyboard capture showed the 240 px test IME. Each
  `.txt` now also records `spPx`, the pixels the platform renders 14, 16, 20, 22, 24 and 28 sp as at the capture's
  font scale.
- **#1543 redraw:** the two compact frames (`639:3308`, `636:4066`) were redrawn at Android's 150 % on 2026-10-02
  and exported again with `get_screenshot`. Type sizes and line boxes follow `spPx`; the 11 and 12 sp sizes, which
  `spPx` does not list, scale by 1.5 in the platform's table. The button labels' letter spacing is 1.5 times the
  1x value, and Cancel and Continue hug their labels. All of it is overrides inside the two frames; no shared
  component or variable changed. The captures stay #1501's, because #1543 changes only Figma, and
  `scripts/design-compare.py` rebuilt the two compact comparisons.
- **Hint letter spacing (#1543 follow-up):** the armed hint in `639:2882` and `639:3308` had no letter spacing,
  where the app's `labelMedium` tracks 0.5 sp. It now has 0.5 px at 1x and 0.75 px at 150 %. Both frames were
  exported again on 2026-10-02 and their comparisons rebuilt; the captures did not change.

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

**Compact frames at 150 %: the platform's scaling (#1501, #1543).** API 35 at font scale 1.5 renders 14 sp as
22 px, 16 sp as 23 px, 22 sp as 27 px, 24 sp as 28 px and 28 sp as 29.3 px (`spPx` in `permission-compact.txt`
and `question-compact.txt`). #1501 found that the two compact frames (`636:4066`, `639:3308`) scaled every type
size and line box linearly, and #1543 redrew them from that table. Against the redraw, the top-bar title, the
button labels and the 43 px buttons match. Two rules in the app that the table does not capture account for what
remains:

- Once font scaling is non-linear, Compose sets a line's height from the converted font size and the style's
  ratio of line height to font size (`resolveLineHeightInPx` in ui-text 1.10.4), not from the converted
  line-height sp. 14/20 sp text gets a 31.4 px line where the frames draw 26, and 12/16 sp text gets 24 where
  they draw 23. It shows wherever the app keeps the whole line box: the context rows and the question line, which
  set `Trim.None`, and the Material default styles `titleMedium` and `labelMedium` (choice rows, grant label,
  hint).
- The styles `Type.kt` defines set no line-height style, so a single line trims to the font's own height, about
  1.17 times its size. The 23 px button labels sit in 27 px, which gives 43 px buttons against the frames' 44, and
  `ModalCancelButton`'s 7 px padding, with its 1 px border inside it, gives 41.

Mobile and desktop share the same components and text styles, so neither changes to close these gaps (decision
2026-10-02). Both compact items record them as mismatches, not routed. The tolerance for the two items is 2 px for
heights, gaps and box sizes. Text is compared by glyph height, and a run up to 3 px wider counts as rendering.

### Questions · Unanswered — `636:3279`

- **Owning ticket:** #1305 (inline questions), #1299 (question components)
- **Capture:** `question-unanswered.png` (412x892, 1.0)
- **Side-by-side:** `question-unanswered-side-by-side.png`
- **Overlay:** `question-unanswered-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | match since #1501: each question card is 240 px against Figma's 242 px; Figma's 1 px border sits inside its 16 px padding and the app's draws over it. As Figma inside the bars: the 24 px status bar pushes the stream down, so "Claude has questions" and the LANGUAGE header scroll under the top bar; the batch is reachable by scrolling |
| Padding | match: 20 px gutters, 16 px card padding |
| Spacing | match since #1501: the radio is top-aligned with the "Other" label and the 32 px field sits 28 px below the label's top, as Figma; the field keeps a 48 dp touch target outside layout. Kotlin to Rust rows match at 56 px; actions 12 px below the card |
| Typography | mismatch: footer "Cxt high: 84%". Question, option label and description, header and actions match |
| Colour | mismatch: footer label in the warning colour. The disabled Continue matches since #1484: the primary fill and on-primary label at 38 % opacity, as `636:3535` |
| Borders | match: primary-container card border, outlined Cancel |
| Radii | match |
| Icon paths | mismatch: footer tune icon. Header snowflake glyphs, radios and checkboxes match |
| Component state | match: nothing selected, Continue disabled, "Waiting for answers" |

- **Routed:** #1485 (footer). Other row fixed by #1501, disabled Continue by #1484

### Questions · Answers selected — `636:3540`

- **Owning ticket:** #1305, #1299
- **Capture:** `question-answered.png` (412x892, 1.0)
- **Side-by-side:** `question-answered-side-by-side.png`
- **Overlay:** `question-answered-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | match since #1501: cards 240 px, as Unanswered |
| Padding | match |
| Spacing | match since #1501: the Other row, as Unanswered |
| Typography | mismatch: footer "Cxt high: 84%". The "Web" draft and every label match |
| Colour | mismatch: footer warning colour. Selected radio and checkboxes and the enabled Continue match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. Filled radio and checkmarks match |
| Component state | match: Kotlin; Kotlin and Other "Web"; Continue enabled |

- **Routed:** #1485 (footer). Other row fixed by #1501

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
| Geometry | match since #1484: at the focus scroll position the focused Other field and both Cancel and Continue are fully in view, the actions' bottom at the stream's bottom edge above "Waiting for answers", as Figma. The last question's focus scroll places the actions first, so the position no longer depends on the IME animation; an earlier question's field brings only itself into view; the test asserts the field and both actions inside the message region |
| Padding | match |
| Spacing | match since #1501: the Other row as Unanswered. The actions sit 4 px above the stream's edge, from the batch's item gutter |
| Typography | mismatch: footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: Other focused with "Web" (the caret blinks and is in its off phase in this capture); the draft is kept |

- **Clipping and reach:** nothing is covered at the focus scroll position (`assertAboveComposer` in the test,
  without a scroll of its own). Both actions also scroll into view with the keyboard still open (`reachable`).
- **Routed:** #1485 (footer). Other row fixed by #1501, actions under the footer by #1484

### Questions · Compact text at 150% — `636:4066`

- **Owning ticket:** #1305
- **Capture:** `question-compact.png` (320x700, 1.5)
- **Side-by-side:** `question-compact-side-by-side.png`
- **Overlay:** `question-compact-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the question card is 273 px tall against the frame's 261, see Spacing. Cancel (43 × 115 px against 44 × 115) and Continue (43 × 140 against 44 × 138) match within 2 px, and the stacked pair sits at the card's start edge with Cancel centred over Continue, as `636:4325`. The top bar's rule is 2 px higher |
| Padding | match |
| Spacing | mismatch, Compose's line boxes (see "Compact frames at 150 %"): under the question line the first choice starts 4 px lower; each choice row is 50 px against the frame's 48, 2 px more per row; and the "Other" field sits 6 px lower under its label. Under the card the app puts Cancel 11 px down and Continue 17 px below Cancel, where the frame has 16 and 12: Material's 48 px touch target around each 43 px button takes layout space. The composer box is 52 px against the frame's 58 |
| Typography | match: the top-bar title is 26 px from cap to descender in both, and the question, choice and action text sits at the frame's sizes within 2 px of glyph height, the action labels within 2 px of width. Mismatch: the choice labels and descriptions run up to 13 px wider ("A systems language" 175 px against 162), the app's `labelMedium` letter spacing (0.75 px here), which the frame's choice text does not have; the footer reads "Cxt h…". Every label wraps |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, both questions, Cancel
  and Continue each scroll into view and display.
- **Routed:** #1485 (footer, truncation). The line-box, action-gap and composer gaps are recorded, not routed (see
  "Compact frames at 150 %"). The choice text's missing letter spacing is a gap in the frame, not yet routed.
  Scaling redrawn by #1543, Other row fixed by #1501

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
| Spacing | match since #1501: Reject once 8 px below Allow once, each 40 px surface keeping a 48 dp touch target outside layout; in the context block 28 px from label to value, 16 px between the Reason and Folder groups and 16 px before the choices, as `639:2242`. Above the context, the prompt line's default trimmed leading puts it 3 px nearer the title and the Reason label 2 px nearer the prompt than Figma; #1501 did not measure or change it |
| Typography | mismatch: footer "Cxt high: 84%". The title (16 px Medium, on-background), the "Folder" label and every other style match |
| Colour | mismatch: footer warning colour. Filled primary safe default and outlined Allow once match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: the status row reads "Waiting for permission" beside the snowflake. Server order and the Reject once default match |

- **Routed:** #1509 (placement), #1485 (footer). Choice gap and context spacing fixed by #1501

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
| Spacing | match since #1501: choice gap and context block, as Safe default. The checkbox row and rule text match |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The unchecked checkbox matches |
| Component state | match: "Waiting for permission"; grant offered and unchecked |

- **Routed:** #1509, #1485. Choice gap and context spacing fixed by #1501

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
| Spacing | match since #1501: choice gap and context block, as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour. The checked checkbox matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The checkmark matches |
| Component state | match: "Waiting for permission"; ticked by a tap, with nothing armed or answered |

- **Routed:** #1509, #1485. Choice gap and context spacing fixed by #1501

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
| Spacing | match since #1501: "Tap Allow once again to confirm." sits between Allow once and Reject once 8 px from each, as Figma; context block as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". The hint (12 px Medium, on-primary-container, 0.5 px letter spacing since the #1543 follow-up, 189 px wide in both), title and "Folder" label match |
| Colour | mismatch: footer warning colour. The tonal armed fill matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; armed by the first tap with its hint, grant still ticked and nothing sent |

- **Routed:** #1509 (placement), #1485. Choice and hint gaps and context spacing fixed by #1501

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
| Spacing | match since #1501: choice gap and context block, as Safe default |
| Typography | mismatch: footer "Cxt high: 84%". Title and "Folder" label match |
| Colour | mismatch: footer warning colour. "Don't trust" filled as the default matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; server order and the default |

- **Routed:** #1509, #1485. Choice gap and context spacing fixed by #1501

### Permission · Compact text at 150% — `639:3308`

- **Owning ticket:** #1306, #1483
- **Capture:** `permission-compact.png` (320x700, 1.5)
- **Side-by-side:** `permission-compact-side-by-side.png`
- **Overlay:** `permission-compact-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Cancel is 41 px tall against the frame's 44, `ModalCancelButton`'s 7 px padding around a 27 px trimmed label (see "Compact frames at 150 %"); its 113 px width matches the frame's 115. Allow once and Reject once (43 × 248 px against 44 × 248), the title inside the card and start-aligned Cancel match. The top bar's rule is 2 px higher |
| Padding | match |
| Spacing | mismatch, Compose's line boxes (see "Compact frames at 150 %"): the "Folder" value sits 40 px under its label against the frame's 34, glyph top to glyph top, and the session grant 3 px further under the value. From the grant down, the rule text, both choices, the hint's 8 px gaps and Cancel 12 px under the card match within 2 px. The composer box is 52 px against the frame's 58 |
| Typography | mismatch: footer "Cxt h…". The top-bar title (26 px from cap to descender in both), the button labels (23 px, widths within 2 px), the hint (0.75 px letter spacing since the #1543 follow-up, each line within 1 px of width) and the "Folder" label match. Every label wraps |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: "Waiting for permission"; grant ticked and Allow once armed with its hint |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, prompt, grant, both
  choices and Cancel each scroll into view and display.
- **Routed:** #1485 (footer, truncation). The line-box, Cancel-height and composer gaps are recorded, not routed (see "Compact frames at 150 %"). Scaling redrawn by #1543, choice gaps and context spacing
  fixed by #1501

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

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
- **Result:** `prompts-results.xml`, the whole class in one run: 6 executed, 0 failures. Every capture here
  comes from that run.

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

**Platform text scaling.** At 150 % Android 14+ scales large text non-linearly, so the top bar title grows
less than in Figma's linear 150 % frames (`636:4066`, `639:3308`). That is the platform, not the app.

### Questions · Unanswered — `636:3279`

- **Owning ticket:** #1305 (inline questions), #1299 (question components)
- **Capture:** `question-unanswered.png` (412x892, 1.0) · **Side-by-side:** `question-unanswered-side-by-side.png` · **Overlay:** `question-unanswered-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: each question card is about 6 px taller than Figma's 242 px (the Other row, see Spacing). Otherwise as Figma inside the bars: the 24 px status bar pushes the stream down, so "Claude has questions" and the LANGUAGE header scroll under the top bar; the batch is reachable by scrolling |
| Padding | match: 20 px gutters, 16 px card padding |
| Spacing | mismatch in the Other row: Figma puts the field 28 px below the "Other" label's top and the radio top-aligned with the label; the app's field sits about 6 px lower and its radio is centred in a 48 dp row, about 10 px below the label. Kotlin to Rust rows match at 56 px; actions 12 px below the card |
| Typography | mismatch: footer "Cxt high: 84%". Question, option label and description, header and actions match |
| Colour | mismatch: disabled Continue is near-black with dim text, where Figma fills it blue-grey; footer label in the warning colour |
| Borders | match: primary-container card border, outlined Cancel |
| Radii | match |
| Icon paths | mismatch: footer tune icon. Header snowflake glyphs, radios and checkboxes match |
| Component state | match: nothing selected, Continue disabled, "Waiting for answers" |

- **Routed:** #1484 (disabled Continue), #1501 (Other row spacing and radio alignment), #1485 (footer)

### Questions · Answers selected — `636:3540`

- **Owning ticket:** #1305, #1299
- **Capture:** `question-answered.png` (412x892, 1.0) · **Side-by-side:** `question-answered-side-by-side.png` · **Overlay:** `question-answered-overlay.png`

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
- **Capture:** `question-keyboard.png` (412x792 window, 240 px test keyboard, 412x552 app area, 1.0) · **Side-by-side:** `question-keyboard-side-by-side.png` · **Overlay:** `question-keyboard-overlay.png`
- The test IME is 240 px and the frame's keyboard is 340 px, so the window is 792 px tall and the app has the
  frame's 552 px above the keyboard. The capture is cropped at the keyboard's top.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the focused Other field is in view, but Cancel and Continue are hidden under the composer footer; Figma shows both above "Waiting for answers". The first pass found them half under; the focus scroll offset varies, and both runs hide them |
| Padding | match |
| Spacing | mismatch: the actions have no gap above the footer (see Geometry); the Other row as Unanswered |
| Typography | mismatch: footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match: Other focused with "Web" and the caret; the draft is kept |

- **Clipping and reach:** Cancel and Continue are covered by the footer at the focus scroll position. Both
  scroll into view and display with the keyboard still open (`reachable` in the test). No control is unreachable.
- **Routed:** #1484 (actions under the footer), #1501 (Other row), #1485 (footer)

### Questions · Compact text at 150% — `636:4066`

- **Owning ticket:** #1305
- **Capture:** `question-compact.png` (320x700, 1.5) · **Side-by-side:** `question-compact-side-by-side.png` · **Overlay:** `question-compact-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Cancel and Continue stack, but centred, where Figma stacks them at the card's start edge. Figma's Cancel and Continue are 52 px tall (118 and 143.5 px wide); the app's are about 42 px |
| Padding | match |
| Spacing | mismatch: the Other row, as Unanswered |
| Typography | mismatch: the action labels are smaller than the frame's linear 150 %, and the footer reads "Cxt h…". Every label wraps; the top bar title is smaller because of platform scaling (see above) |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | match |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, both questions, Cancel
  and Continue each scroll into view and display.
- **Routed:** #1484 (stacked action alignment), #1501 (button heights, Other row), #1485 (footer, truncation)

### Permission · Safe default — `639:2242`

- **Owning ticket:** #1306 (inline permissions), #1300 (permission components)
- **Capture:** `permission-safe-default.png` (412x892, 1.0) · **Side-by-side:** `permission-safe-default-side-by-side.png` · **Overlay:** `permission-safe-default-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the request is anchored to the stream's end with empty space above it; Figma starts it under the header. The title "Permission required" sits above the card; Figma puts it inside as the first line. Cancel is centred; Figma start-aligns it |
| Padding | match: 16 px card padding, 20 px gutters |
| Spacing | mismatch: Figma puts Reject once 8 px below Allow once (y 0–40, then 48); the app shows 16 px, because the 48 dp touch floor around each 40 dp button moves layout. In the context block Figma measures 28 px from label to value, 36 px between the Reason and Folder groups and 36 px before the choices; the app measures 24, about 28 and about 28 px |
| Typography | mismatch: the path label reads "Blocked path" where Figma reads "Folder"; footer "Cxt high: 84%". Styles match |
| Colour | mismatch: footer warning colour. Filled primary safe default and outlined Allow once match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Server order and the Reject once default match |

- **Routed:** #1483 (placement, title, Cancel, label, status text), #1501 (choice gap, context spacing), #1485 (footer)

### Permission · Session grant offered — `639:2451`

- **Owning ticket:** #1306, #818 (session grant)
- **Capture:** `permission-grant-offered.png` (412x892, 1.0) · **Side-by-side:** `permission-grant-offered-side-by-side.png` · **Overlay:** `permission-grant-offered-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default. The checkbox row and rule text match |
| Typography | mismatch: "Blocked path" label, as Safe default; footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The unchecked checkbox matches |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Grant offered and unchecked matches |

- **Routed:** #1483, #1501, #1485

### Permission · Session grant selected — `639:2666`

- **Owning ticket:** #1306, #818
- **Capture:** `permission-grant-selected.png` (412x892, 1.0) · **Side-by-side:** `permission-grant-selected-side-by-side.png` · **Overlay:** `permission-grant-selected-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default |
| Typography | mismatch: "Blocked path" label, as Safe default; footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour. The checked checkbox matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon. The checkmark matches |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Ticked by a tap, with nothing armed or answered, matches |

- **Routed:** #1483, #1501, #1485

### Permission · Confirm Allow once (armed) — `639:2882`

- **Owning ticket:** #1306, #451 (arm then confirm)
- **Capture:** `permission-armed.png` (412x892, 1.0) · **Side-by-side:** `permission-armed-side-by-side.png` · **Overlay:** `permission-armed-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | mismatch: no hint line between Allow once and Reject once, which sit 16 px apart; context block as Safe default |
| Typography | mismatch: "Tap Allow once again to confirm." is missing; "Blocked path" label; footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour. The tonal armed fill matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Armed by the first tap, grant still ticked and nothing sent all match |

- **Routed:** #1483 (hint, label, status text), #1501 (gap, context spacing), #1485

### Trust · Safe default — `639:3099`

- **Owning ticket:** #1306
- **Capture:** `trust-safe-default.png` (412x892, 1.0) · **Side-by-side:** `trust-safe-default-side-by-side.png` · **Overlay:** `trust-safe-default-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default ("Trust this folder?" above the card, Cancel centred, request at the stream's end) |
| Padding | match |
| Spacing | mismatch: choice gap and context block, as Safe default |
| Typography | mismatch: "Blocked path" label, as Safe default; footer "Cxt high: 84%" |
| Colour | mismatch: footer warning colour. "Don't trust" filled as the default matches |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Server order and the default match |

- **Routed:** #1483, #1501, #1485

### Permission · Compact text at 150% — `639:3308`

- **Owning ticket:** #1306
- **Capture:** `permission-compact.png` (320x700, 1.5) · **Side-by-side:** `permission-compact-side-by-side.png` · **Overlay:** `permission-compact-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: "Permission required" sits above the card instead of inside it as the first line; Cancel is centred where Figma start-aligns it. Figma's Allow once, Reject once and Cancel are 52 px tall (Cancel 118 px wide); the app's are about 42 px |
| Padding | match |
| Spacing | mismatch: no confirm hint under the armed choice, which sits about 14 px above Reject once; context block as Safe default |
| Typography | mismatch: "Blocked path" label; the button labels are smaller than the frame's linear 150 %; footer "Cxt h…". Every label wraps |
| Colour | mismatch: footer warning colour |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: footer tune icon |
| Component state | mismatch: the status row shows the glyph without "Waiting for permission". Grant ticked and Allow once armed match |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, prompt, grant, both
  choices and Cancel each scroll into view and display.
- **Routed:** #1483 (title, Cancel, hint, label, status text), #1501 (button heights, context spacing), #1485 (footer, truncation)

### Switch chats while prompts wait — `640:2437`

- **Owning ticket:** #1305, #1306, #1338 (list waiting marks)
- **Captures:** each at (412x892, 1.0): `switch-question-chat.png` (question in "Client planning"),
  `switch-list.png` against `640:2440`, `switch-other-chat.png` against `640:2646` with the keyboard closed,
  and `switch-permission-chat.png` (permission in "kitchenclaw refactor"). Side-by-side and overlay for the
  list and the other chat.
- `promptsWaitInTheirOwnChats` holds a question for "Client planning" and a permission for "kitchenclaw refactor"
  at once. Each chat shows only its own prompt, asserted on the view model's `questionModal` and
  `currentModal`. "Release notes" shows neither and takes a draft typed through the keyboard, which is closed
  again before the capture. Back on "Client planning" the question is still there.
- `640:2646` draws "Release notes" with an earlier message and a "Ready" status line. The harness channel is
  empty, so the app shows its empty state. The thread surface itself is #1432's audit.
- `640:2440` is the full channel tree with servers and folders; the list surface itself is #1431's audit, so
  this item judges only that the list stays usable and each prompt stays in its chat.
- **List waiting marks are not judged here.** The override feeds prompts to the thread's view model only; the
  list reads waiting state from the host coordinator, so both chats show as idle in `switch-list.png`. #1338's
  own tests cover the marks.
- **Question drafts across chats are not judged here.** The harness override passes no question draft store
  (`DesignInputs`), so each thread opening builds a fresh one. Production binds the host's shared store, which
  #1305 covers in unit tests.

| Aspect | Verdict |
|---|---|
| Component state | match: each prompt inside its own conversation; the list and another chat open and usable while both wait |

- **Routed:** none from this item; the list surface is audited by #1431

## Gaps

Prompt states reachable from `MainActivity` that have no frame on board `635:2036` (re-read 2026-10-02).
They are not captured here, and #1502 asks for their frames:

- **Refused permission answer:** the `permission-rejection` item in `ThreadScreen.kt`, a `NoticePill` reading
  `permission_answer_rejected` in the slot the card held. Owning ticket: #1340. Routed: #1502.
- **Question send failure:** the `question-send-failed` line in `QuestionBatchActions` (`QuestionBatchModal.kt`).
  Owning ticket: #1305. Routed: #1502.
- **Prompts while disconnected:** answers are disabled while the host is not connected. Owning ticket:
  #1321. Routed: #1502.

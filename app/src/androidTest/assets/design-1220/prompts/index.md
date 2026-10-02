# Questions and permissions audit (#1433)

- **App commit:** `main` at `4c755aa6`, plus the test-only `PromptsDesignCaptureTest` on `feature/1433`.
- **Figma:** the Questions and permissions board `635:2036` of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported
  with `get_screenshot` on 2026-10-02. Behaviour reference: the specification `640:2838`.
- **Capture:** `PromptsDesignCaptureTest` on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`, density 1.0, fixed dark theme, real 24 px status and navigation bars. Each `.txt`
  records the measured values.
- **Result:** `prompts-results.xml`, the whole class: 6 executed, 1 failure (`promptsWaitInTheirOwnChats`, a
  test wait on an offscreen title, since fixed); `switch-results.xml`, that method rerun alone: 1 executed, 0
  failures. The frame captures come from the first run, the switching captures from the second.

**How these captures differ from onboarding's.** Both prompts set `FLAG_SECURE` on the activity window while
they show, which blacks out the harness's `UiAutomation` screenshot. The class draws the decor view into a
bitmap instead (`decorViewDraw=true secure=true` in each `.txt`), as #1305 and #1306 did. The platform's
status and navigation bars are therefore not drawn; the app's own content under them is.

**Fixture.** Each frame is an empty channel "Client planning" created on the fake repository, the composer
draft "My message", 84 % context, and the frame's own prompt text. Choices are tapped through the UI; the
Other text "Web" is entered through the view model's `OtherTextChanged` so no keyboard opens outside the
keyboard frame.

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
| Geometry | match inside the bars: the 24 px status bar pushes the stream down, so "Claude has questions" and the LANGUAGE header scroll under the top bar; the batch itself is reachable by scrolling |
| Padding | match: 20 px gutters, 16 px card padding |
| Spacing | match within 4 px: option rows, Other field, card gap, actions 12 px below the card |
| Typography | match: question, option label and description, header, actions |
| Colour | mismatch: disabled Continue is near-black with dim text; Figma fills it blue-grey |
| Borders | match: primary-container card border, outlined Cancel |
| Radii | match |
| Icon paths | match: header snowflake glyphs, radios, checkboxes |
| Component state | match: nothing selected, Continue disabled, "Waiting for answers" |

- **Routed:** #1484 (disabled Continue), #1485 (footer)

### Questions · Answers selected — `636:3540`

- **Owning ticket:** #1305, #1299
- **Capture:** `question-answered.png` (412x892, 1.0) · **Side-by-side:** `question-answered-side-by-side.png` · **Overlay:** `question-answered-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars, as Unanswered |
| Padding | match |
| Spacing | match within 4 px |
| Typography | match, including the "Web" draft |
| Colour | match: selected radio and checkboxes, enabled Continue |
| Borders | match |
| Radii | match |
| Icon paths | match: filled radio, checkmarks |
| Component state | match: Kotlin; Kotlin and Other "Web"; Continue enabled |

- **Routed:** #1485 (footer)

### Questions · Other and keyboard — `636:3803`

- **Owning ticket:** #1305
- **Capture:** `question-keyboard.png` (412x792 window, 240 px test keyboard, 412x552 app area, 1.0) · **Side-by-side:** `question-keyboard-side-by-side.png` · **Overlay:** `question-keyboard-overlay.png`
- The test IME is 240 px and the frame's keyboard is 340 px, so the window is 792 px tall and the app has the
  frame's 552 px above the keyboard. The capture is cropped at the keyboard's top.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the focused Other field is in view, but Cancel and Continue sit half under the composer footer; Figma shows both above "Waiting for answers" |
| Padding | match |
| Spacing | mismatch: no gap between the actions and the footer (see geometry) |
| Typography | match |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match: Other focused with "Web" and the caret; the draft is kept |

- **Clipping and reach:** Cancel and Continue are overlapped by the footer at the focus scroll position. Both
  scroll into view and display with the keyboard still open (`reachable` in the test). No control is unreachable.
- **Routed:** #1484 (actions under the footer), #1485 (footer)

### Questions · Compact text at 150% — `636:4066`

- **Owning ticket:** #1305
- **Capture:** `question-compact.png` (320x700, 1.5) · **Side-by-side:** `question-compact-side-by-side.png` · **Overlay:** `question-compact-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Cancel and Continue stack, but centred; Figma stacks them at the card's start edge |
| Padding | match |
| Spacing | match |
| Typography | match: every label wraps; top bar title smaller by platform scaling (see above) |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, both questions, Cancel
  and Continue each scroll into view and display.
- **Routed:** #1484 (stacked action alignment), #1485 (footer truncation)

### Permission · Safe default — `639:2242`

- **Owning ticket:** #1306 (inline permissions), #1300 (permission components)
- **Capture:** `permission-safe-default.png` (412x892, 1.0) · **Side-by-side:** `permission-safe-default-side-by-side.png` · **Overlay:** `permission-safe-default-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the request is anchored to the stream's end with empty space above it; Figma starts it under the header. The title "Permission required" sits above the card; Figma puts it inside as the first line. Cancel is centred; Figma start-aligns it |
| Padding | match: 16 px card padding, 20 px gutters |
| Spacing | match within 4 px inside the card |
| Typography | mismatch: the path label reads "Blocked path"; Figma reads "Folder". Styles match |
| Colour | match: filled primary safe default, outlined Allow once |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: the status row shows the glyph without "Waiting for permission" |
| Component state | match: server order, Reject once is the default |

- **Routed:** #1483 (placement, title, Cancel, label, status text), #1485 (footer)

### Permission · Session grant offered — `639:2451`

- **Owning ticket:** #1306, #818 (session grant)
- **Capture:** `permission-grant-offered.png` · **Side-by-side:** `permission-grant-offered-side-by-side.png` · **Overlay:** `permission-grant-offered-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | match: checkbox row and rule text |
| Typography | mismatch: "Blocked path" label, as Safe default |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match: unchecked checkbox; status text missing as Safe default |
| Component state | match: grant offered, unchecked |

- **Routed:** #1483, #1485

### Permission · Session grant selected — `639:2666`

- **Owning ticket:** #1306, #818
- **Capture:** `permission-grant-selected.png` · **Side-by-side:** `permission-grant-selected-side-by-side.png` · **Overlay:** `permission-grant-selected-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | match |
| Typography | mismatch: "Blocked path" label |
| Colour | match: checked checkbox |
| Borders | match |
| Radii | match |
| Icon paths | match: checkmark |
| Component state | match: ticked by a tap; nothing armed or answered |

- **Routed:** #1483, #1485

### Permission · Confirm Allow once (armed) — `639:2882`

- **Owning ticket:** #1306, #451 (arm then confirm)
- **Capture:** `permission-armed.png` · **Side-by-side:** `permission-armed-side-by-side.png` · **Overlay:** `permission-armed-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default |
| Padding | match |
| Spacing | mismatch: no hint line between Allow once and Reject once |
| Typography | mismatch: "Tap Allow once again to confirm." is missing; "Blocked path" label |
| Colour | match: tonal armed fill |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match: armed by the first tap, grant still ticked, nothing sent |

- **Routed:** #1483, #1485

### Trust · Safe default — `639:3099`

- **Owning ticket:** #1306
- **Capture:** `trust-safe-default.png` · **Side-by-side:** `trust-safe-default-side-by-side.png` · **Overlay:** `trust-safe-default-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: as Safe default ("Trust this folder?" above the card, Cancel centred, request at the stream's end) |
| Padding | match |
| Spacing | match |
| Typography | mismatch: "Blocked path" label |
| Colour | match: "Don't trust" filled as the default |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: status text missing |
| Component state | match |

- **Routed:** #1483, #1485

### Permission · Compact text at 150% — `639:3308`

- **Owning ticket:** #1306
- **Capture:** `permission-compact.png` (320x700, 1.5) · **Side-by-side:** `permission-compact-side-by-side.png` · **Overlay:** `permission-compact-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Cancel centred; Figma start-aligns it |
| Padding | match |
| Spacing | mismatch: no confirm hint under the armed choice |
| Typography | match: every label wraps; "Blocked path" label as above |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: status text missing |
| Component state | match: grant ticked, Allow once armed |

- **Clipping and reach:** the footer's context label truncates to "Cxt h…". The title, prompt, grant, both
  choices and Cancel each scroll into view and display.
- **Routed:** #1483, #1485

### Switch chats while prompts wait — `640:2437`

- **Owning ticket:** #1305, #1306, #1338 (list waiting marks)
- **Captures:** `switch-question-chat.png` (question in "Client planning"), `switch-list.png` against
  `640:2440`, `switch-other-chat.png` against `640:2646`, `switch-permission-chat.png` (permission in
  "kitchenclaw refactor") · side-by-side and overlay for the list and the other chat
- `promptsWaitInTheirOwnChats` holds a question for "Client planning" and a permission for "kitchenclaw refactor"
  at once. Each chat shows only its own prompt; "Release notes" shows neither and takes a typed draft in its
  composer; back on "Client planning" the question is still there.
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

None found: every prompt state reachable from `MainActivity` has a frame on the board.

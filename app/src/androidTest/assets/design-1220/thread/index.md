# Thread audit (#1432)

- **App commit:** `main` at `3cebb1ad` (the `main` merged into `feature/1432`), plus the test-only
  `ThreadDesignCaptureTest` on `feature/1432`. Between the first pass's `4c755aa6` and `3cebb1ad` only onboarding
  production files changed. The compact captures (`compact-*.png`) were retaken at `main` `85466a15` (the merge in
  `feature/1432` at `da65538a`), after #1510's status-bar fix; see "Compact, keyboard and menus".
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, re-read and every frame re-exported with `get_screenshot` on
  2026-10-02 at 13:10 EEST. Since the first pass, `16:8`, `600:1694`, `620:1577`, `627:1740`, `627:4657`,
  `627:4910`, `627:5466`, `646:4707` and `568:3139` changed, and the section **Thread states · 2026-10-02**
  (`674:5852`) added six frames. The Components page (`347:5692`) supplies `figma-390-7145.png` (Attachment) and
  `figma-620-1576.png` (Thread notification).
- **Capture:** `ThreadDesignCaptureTest` (six methods) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0 and font scale 1.0, and 320x700 at font scale 1.5 for
  `compactAt320By700`. Fixed dark theme, real 24 px status and navigation bars. Each `.txt` records the measured
  values.
- **Result:** `thread-results.xml`, 6 executed, 0 failures. Two methods were rerun after fixture fixes, and their
  captures replace the full run's: `threadStatusFramesAt412By892` waits for the bubble photo's decode
  (`thread-rerun-results.xml`, 1 executed, 0 failures), and `runConfigurationAndReaderAt412By892` uses a model menu
  that supports auto mode (`run-configuration-rerun-results.xml`, 1 executed, 0 failures). `compactAt320By700` was
  rerun in the second rework (`compact-rerun-results.xml`, 1 executed, 0 failures) and its captures replace the
  earlier ones. The 412x892 images come from `thread-results.xml` and its two reruns, which ran before the second
  rework changed the waits and the panel helpers; the dispatcher's UI gate shows the current waits pass, but did not
  produce these images.
- **Strict waits:** every frame state waits for its marker text ("Connecting…", "Offline · Retry", "Thinking",
  "2 tasks running", "Pairing error", "Switch back to", "Sonnet", "Manual approval", "Builder Pipeline Plan",
  "No background tasks", "No background-task report yet" and so on) and fails the run if it does not appear within 5 s. The bubble photo's decode gets 10 s. No capture is taken of a state that did not render.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames have no
system chrome, and the thread lays out inside the bars: every thread element sits 24 px below its frame position at
the top and 24 px above it at the bottom. That shift is not counted as a mismatch.

**Inputs.** States are reached through `DesignInputs` (connection state, live events, task roster and count, pairing
rejection, context usage) and the class's own repository override, which adds the frames' photo and PDF messages,
notice and refusal rows, the clear and idle-evict delimiters, the held turn phase, a usage-limit reading, the
refusal offer, the photo file behind `retrieveAttachment` and one markdown note. Images are generated placeholder
shapes, not the frames' photo. Message text is partly the demo seed's, and timestamps follow the emulator's `en-US`
locale ("5/10/26 - 12:00 PM" for the frames' "13.01.2026 - 13:55"). Neither is compared.

**Routed defects.** #1494 refusal model names · #1496 inset sheets and the task panel's Close · #1497 Run
configuration · #1498 workspace delimiter in the seed · #1499 Offline and usage-limit pills · #1485 compact footer ·
#1512 delimiter rule inset · #1513 photo above text and photo bubble width · #1529 states with no frame (Gaps) ·
#1532 PDF tile not dimmed while disconnected · #1533 reader list indent · #1534 task panel spacing · #1118 agent switch
(pending) · #1510 dark status-bar icons (fixed after these captures; see Status bar). #1493, #1495 and #1500 asked
for these captures against the updated frames; their verdicts are below, and the states #1500 could not cover moved
to #1529.

## Composer and footer

The status band, input and footer appear on every thread frame, and the four-tile attachment strip on `16:8`,
`674:5853`, `620:1577`, `646:4707`, `627:5466`, `627:*` and `568:3139`. All those captures stage the strip. These
verdicts apply to each of those frames, which list only what differs.

| Aspect | Verdict |
|---|---|
| Geometry | match: band, strip, input and footer stack in the frame's order and heights; footer one row at 412 px |
| Padding | match: 20 px gutters |
| Spacing | match |
| Typography | match: input, band label and footer |
| Colour | match: "Cxt high: 84%" in the error colour, as every updated frame draws it; PDF tile outline and label in primary, as Input area `134:5013` now draws them, on the connected frames. The disconnected frames `627:1740`, `627:4657` and `627:4910` dim the PDF tile; see those frames |
| Borders | match: input has none; PDF tile outlined |
| Radii | match: input and tile corners |
| Icon paths | match: snowflake, remove badges, PDF glyph, paperclip and tune Status opener |
| Component state | match: send enabled with text, Actions with chevron while connected |

- **Routed:** #1532 for the PDF tile while disconnected. The compact footer is under "Keyboard open / Compact
  150%".

### Conversation Thread — `16:8`

- **Owning ticket:** #1206 (frame), #933 (strip), #1290 (message attachments), #875 (refusal row)
- **Capture:** `thread.png` (412x892, 1.0)
- **Side-by-side:** `thread-side-by-side.png`
- **Overlay:** `thread-overlay.png`
- **Verdict:** mismatch
- Photo message, refusal row without an offer, PDF message, thinking turn, 84 % context, four staged attachments.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the user bubble draws its text first and the photo below it; the frame draws the photo at the top of the bubble. The refusal title wraps to two lines (see `620:1577`). The photo message bubble is about 272 px wide where the frame's (`I533:1956;132:4567`) is 222 px, so the bubble and photo sit about 50 px further left. Photo size, the other bubble widths and the file row match |
| Padding | match: 20 px bubble padding |
| Spacing | match |
| Typography | mismatch: refusal model names (see `620:1577`). File row name middle-ellipsized ("Filename of th…ttachment.pdf"), as the updated frame |
| Colour | match: file row icon and name in the light on-secondary-container tone the updated frame uses |
| Borders | match: header divider, PDF outline |
| Radii | match: bubbles, photo corners |
| Icon paths | match: back, overflow, copy, document outline, snowflake |
| Component state | match: thinking, send enabled, photo loaded |

- **Routed:** #1513 (photo position and photo bubble width), #1494 (model names). The frame's PDF row between two paragraphs cannot be
  expressed by the app's message model; #1513 asks for that decision too.

### Conversation Thread / Tool row — `674:5853`

- **Owning ticket:** #1208 (#1315, #1316)
- **Capture:** `tool-row.png` (412x892, 1.0)
- **Side-by-side:** `tool-row-side-by-side.png`
- **Overlay:** `tool-row-overlay.png`
- **Verdict:** match

| Aspect | Verdict |
|---|---|
| Geometry | match: tool row 372 px wide in the gutter, 36 px tall |
| Padding | match: 12 px inner padding |
| Spacing | match: about 16 px to the bubbles above and below |
| Typography | match: "Read" label and monospace path ellipsized at the end |
| Colour | match: orange tool label, outline and check |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match: check |
| Component state | match: finished tool |

- **Routed:** none. The seed's streaming reply and its workspace delimiter (#1498) sit above the row; neither is part
  of this frame's verdict.

### Notification text — `620:1577`

- **Owning ticket:** #875 (refusal row), #1290 (message attachment)
- **Capture:** `notification-text.png` (412x892, 1.0)
- **Side-by-side:** `notification-text-side-by-side.png`
- **Overlay:** `notification-text-overlay.png`
- **Verdict:** mismatch
- Reference components: `figma-620-1576.png`, `figma-390-7145.png`.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the refusal title wraps to two lines where the frame's is one |
| Padding | match |
| Spacing | match: Show details sits 4 px under the title |
| Typography | mismatch: models are named by raw identifier in a monospace span ("`claude-opus-5-5`"); the frame uses display names in the body style ("Opus") |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match: document outline with folded corner and "PDF" |
| Component state | match: collapsed refusal with Show details |

- **Routed:** #1494

### Refusal switch back — `646:4707`

- **Owning ticket:** #1360
- **Capture:** `refusal-switch-back.png` (412x892, 1.0)
- **Side-by-side:** `refusal-switch-back-side-by-side.png`
- **Overlay:** `refusal-switch-back-overlay.png`
- **Verdict:** mismatch

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the two-line title pushes the button down one line; the button is wider for the identifier |
| Padding | match: 16 px button side padding |
| Spacing | match: 12 px from Show details to the button |
| Typography | mismatch: "Switch back to `claude-opus-5-5`" with a monospace identifier, frame "Switch back to Opus" |
| Colour | match: outlined primary button |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: offer armed, not pending |

- **Routed:** #1494

### Session notice — `627:5466`

- **Owning ticket:** #1113 (agent prefix), #875
- **Capture:** `session-notice.png` (412x892, 1.0)
- **Side-by-side:** `session-notice-side-by-side.png`
- **Overlay:** `session-notice-overlay.png`
- **Verdict:** match

| Aspect | Verdict |
|---|---|
| Geometry | match |
| Padding | match |
| Spacing | match |
| Typography | match: "Warning · Claude:" in medium weight, as the updated frame |
| Colour | match: on-surface-variant text |
| Borders | match (none) |
| Radii | match (none) |
| Icon paths | match (none) |
| Component state | match: warning notice |

- **Routed:** none. The first pass's weight finding on #1494 no longer holds.

### Connecting — `627:1740`, Reconnecting — `627:4657`

- **Owning ticket:** #1283 (connection states), #1312 (snowflake), #1319 (disabled footer)
- **Capture:** `connecting.png`, `reconnecting.png` (412x892, 1.0)
- **Side-by-side:** `connecting-side-by-side.png`, `reconnecting-side-by-side.png`
- **Overlay:** `connecting-overlay.png`, `reconnecting-overlay.png`
- **Verdict:** mismatch

| Aspect | Verdict |
|---|---|
| Geometry | match: snowflake then text in the band, as the updated frames |
| Padding | match |
| Spacing | match |
| Typography | match: "Connecting…", "Reconnecting in 12s" |
| Colour | mismatch: the staged PDF tile's outline, glyph and "PDF" label stay in primary; the frames dim them. Send dimmed and paperclip and tune at full strength match |
| Borders | match |
| Radii | match |
| Icon paths | match: snowflake |
| Component state | match: Actions without its chevron, Send disabled |

- **Routed:** #1532

### Offline — `627:4910`

- **Owning ticket:** #1283
- **Capture:** `offline.png` (412x892, 1.0)
- **Side-by-side:** `offline-side-by-side.png`
- **Overlay:** `offline-overlay.png`
- **Verdict:** mismatch

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the Offline · Retry pill is drawn 144 px wide with its text at the left; the frame's pill hugs its text (about 92 px) at the right edge |
| Padding | mismatch: pill text inset (see geometry) |
| Spacing | match: pill 12 px under the header |
| Typography | match |
| Colour | mismatch: the staged PDF tile stays in primary where the frame dims it, as in Connecting. Error-container pill and dimmed Send match |
| Borders | match |
| Radii | match: pill radius |
| Icon paths | match: the band shows the snowflake alone, as the updated frame |
| Component state | match: Actions and Send disabled, as the updated frame |

- **Routed:** #1499 (pill), #1532 (PDF tile)

### Task count pill — `568:3139`, with the usage-limit and pairing-error pills

- **Owning ticket:** #1043 (count pill), #1002 and #1115 (usage-limit pill), #842 (pairing error)
- **Capture:** `task-count-pill.png` (412x892, 1.0)
- **Side-by-side:** `task-count-pill-side-by-side.png`
- **Overlay:** `task-count-pill-overlay.png`
- **Verdict:** mismatch

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the usage-limit pill spans the message area on two lines; the frame's hugs its text on one line at the right. The count pill and pairing-error pill match |
| Padding | match |
| Spacing | match: 12 px between the overlay pills |
| Typography | mismatch: usage-limit copy is "Claude reports usage-limit status: allowed_warning · 94% spent"; the frame reads "Nearly at usage limit - 7-day window" |
| Colour | match: primary-container count and usage pills, error-container pairing pill |
| Borders | match |
| Radii | match |
| Icon paths | match: dismiss X on the usage pill |
| Component state | match: warning dismissible, two tasks running |

- **Routed:** #1499

### Background tasks — Populated `568:877`, Capped `568:932`, Empty `568:981`, Never reported `568:997`

- **Owning ticket:** #1295 (and #1041, #1218)
- **Capture:** `tasks-populated.png`, `tasks-capped.png`, `tasks-empty.png`, `tasks-never-reported.png` (412x892, 1.0)
- **Side-by-side:** `tasks-<state>-side-by-side.png`
- **Overlay:** `tasks-<state>-overlay.png`
- **Verdict:** mismatch
- Roster fixtures copy the frames' tasks, progress, patches and cut reports.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the sheet ends above the navigation bar with rounded bottom corners; the frames run it to the screen's bottom. Header, groups and cards match |
| Padding | match: 28 px sheet gutters, 14 px card padding |
| Spacing | mismatch in Populated and Capped, measured at x 31 with the 24 px bar shift removed. Capped: the "Partial list" banner ends at 137 against the frame's 143, the first card starts at 156 against 163, and card 1 ends at 334 against 342. Populated: content drifts higher down the panel, by 3, 4, 5, 7, 8 and then 9 px. Empty and Never reported match |
| Typography | match: title, group headings, monospace commands and patches, meta lines. In Capped the second patch fits one line where the frame wraps "roste" by a few pixels, and card 1 wraps "staticcheck" / "-checks" where the frame wraps "staticcheck -" / "checks" |
| Colour | match: card, tag and cut-marker colours |
| Borders | match: header divider, dashed cut markers, empty-state rings |
| Radii | mismatch: rounded bottom corners (see geometry) |
| Icon paths | match: close X, tag dots, empty and never-reported rings |
| Component state | mismatch: an outlined Close button at the bottom of all four panels; the frames close from the header X only |

- **Routed:** #1496 (Close button, inset sheet), #1534 (spacing)

### Run configuration / Sonnet selected / Dark — `600:1694`

- **Owning ticket:** #1195
- **Capture:** `run-configuration.png` (412x892, 1.0)
- **Side-by-side:** `run-configuration-side-by-side.png`
- **Overlay:** `run-configuration-overlay.png`
- **Verdict:** mismatch
- Four-model menu with real resolved identifiers and auto mode, Sonnet at high effort, Manual approval. No "Default"
  option in any spelling (asserted with a case-insensitive substring match).

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: each model row is two lines tall, so Effort and the sections below sit lower, and with the inset sheet "Bypass approvals" falls below the visible area (it scrolls into view). The sheet ends above the navigation bar with rounded bottom corners; the frame runs it to the screen's bottom |
| Padding | match |
| Spacing | match: Permission heading and row spacing |
| Typography | mismatch: a resolved-identifier second line under every model ("claude-sonnet-5"); effort options read "low/medium/high/max" where the frame reads "Low/Medium/High/Max" |
| Colour | match: radios, labels and Done |
| Borders | match: header divider |
| Radii | mismatch: rounded bottom sheet corners (see geometry); Done matches |
| Icon paths | match: close X, radios |
| Component state | match: Permission section with the frame's six rows in its order, Manual approval selected |

- **Routed:** #1497 (model rows, effort case), #1496 (the `MobileModalShell` inset shared with the task panel)

### Markdown Reader — `553:2574`

- **Owning ticket:** #1291
- **Capture:** `markdown-reader.png` (412x892, 1.0)
- **Side-by-side:** `markdown-reader-side-by-side.png`
- **Overlay:** `markdown-reader-overlay.png`
- **Verdict:** mismatch
- Opened through the published view model's `onOpenMarkdownLink` with the frame's note.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the first list item's wrapped line ("version") hangs under the item text at x≈34; the frame returns it to the 20 px gutter. Header, divider, headings, code block and quote sit at the frame's coordinates inside the bars |
| Padding | match: 20 px gutters |
| Spacing | match |
| Typography | match: heading, body, link, code and quote styles at 24 px line height. The first paragraph breaks after "2026-09-01." where the frame breaks after "since"; that line is within 2 px of the measure in both |
| Colour | match |
| Borders | match: header divider, quote bar |
| Radii | match: code block |
| Icon paths | match: back, overflow |
| Component state | match: rendered note |

- **Routed:** #1533. It also allows the frame to change instead, if a hanging indent is preferred.

### Conversation Thread / Session delimiter — `675:3682`

- **Owning ticket:** #1207 (#1358)
- **Capture:** `session-delimiter.png` (412x892, 1.0)
- **Side-by-side:** `session-delimiter-side-by-side.png`
- **Overlay:** `session-delimiter-overlay.png`
- **Verdict:** mismatch
- A clear boundary ("New session — …") and an idle-evict boundary ("Idle session ended — …"), idle band.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the rules run from the 20 px gutter to the label; the frame insets them a further 20 px on each side (x 40–372) |
| Padding | match |
| Spacing | match: gaps to the bubbles and the explanatory line under the label overlay within 2 px |
| Typography | match: label and explanatory line |
| Colour | match: primary label, on-surface-variant line, outline rules; above-delimiter bubbles de-emphasized |
| Borders | match: 1 px rules |
| Radii | match (none) |
| Icon paths | match: snowflake alone in the idle band |
| Component state | match: both reasons |

- **Routed:** #1512

### Conversation Thread / Overflow menu — `675:5883`

- **Owning ticket:** #1199
- **Capture:** `overflow-menu.png` (412x892, 1.0)
- **Side-by-side:** `overflow-menu-side-by-side.png`
- **Overlay:** `overflow-menu-overlay.png`
- **Verdict:** match
- Open over the delimiter state.

| Aspect | Verdict |
|---|---|
| Geometry | match: menu anchored under the overflow button at the right gutter, four 48 px rows |
| Padding | match: 12 px row inset |
| Spacing | match |
| Typography | match: Reset session, Rename, Archive, Channel info |
| Colour | match: menu surface and labels |
| Borders | match (none) |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: no workspace action (asserted) |

- **Routed:** none. Delimiter rules as above (#1512).

### Conversation Thread / Actions menu — `675:5938`

- **Owning ticket:** #884
- **Capture:** `actions-menu.png` (412x892, 1.0)
- **Side-by-side:** `actions-menu-side-by-side.png`
- **Overlay:** `actions-menu-overlay.png`
- **Verdict:** match

| Aspect | Verdict |
|---|---|
| Geometry | match: menu opens upward from Actions at the left gutter, over the band and input |
| Padding | match |
| Spacing | match: 28 px rows |
| Typography | match: Reset session, Compact session, Knowledge capture, Background tasks (0) |
| Colour | match: primary labels on the container surface |
| Borders | match |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: no workspace action (asserted) |

- **Routed:** none. The seed's workspace delimiter in the background is #1498's.

### Conversation Thread / Keyboard open — `675:6160`

- **Owning ticket:** #1149
- **Capture:** `keyboard.png` (412x892, 1.0)
- **Side-by-side:** `keyboard-side-by-side.png`
- **Overlay:** `keyboard-overlay.png`
- **Verdict:** match
- The test IME stands in for the frame's 240 px keyboard placeholder; the keyboard itself is not compared.
- The teardrop over the footer row in `keyboard.png` and `compact-keyboard.png` is the focused field's text-selection
  handle, a popup above the layout, not a layout overlap.

| Aspect | Verdict |
|---|---|
| Geometry | match: band, input and footer sit directly above the keyboard; the message list shrinks |
| Padding | match |
| Spacing | match |
| Typography | match |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match: focused input with "My message" |

- **Routed:** none

### Conversation Thread / Keyboard open / Compact 150% — `676:3981`

- **Owning ticket:** #1149 (keyboard), #1347 and #1412 (footer label)
- **Capture:** `compact-keyboard.png` (320x700, 1.5)
- **Side-by-side:** `compact-keyboard-side-by-side.png`
- **Overlay:** `compact-keyboard-overlay.png`
- **Verdict:** mismatch

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the footer stays one row and truncates its context label; the frame moves "Cxt high: 84%" to its own line under Actions, with the paperclip and tune on the first row |
| Padding | match |
| Spacing | match: band and count pill above the input |
| Typography | mismatch: "Cxt h…" hides the percentage (see geometry) |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match: thinking, two tasks running, focused input |

- **Routed:** #1485

### Codex agent switch — Switching `578:3248`, Switch confirm `578:3442`

- **Owning ticket:** #1118, open on 2026-10-02.
- Not captured. Both frames are pending #1118; `figma-578-3248.png` and `figma-578-3442.png` are kept for it.

## Status bar

The frames draw no status bar; the reference is light bar icons on the dark canvas (#1510). The 412x892 captures
were taken on `main` at `3cebb1ad`, before #1510's fix merged at `8fa9df64`, on an emulator in light mode. In
`thread.png`, `tool-row.png`, `notification-text.png`, `refusal-switch-back.png`, `session-notice.png`,
`connecting.png`, `reconnecting.png`, `offline.png`, `task-count-pill.png`, `session-delimiter.png`,
`overflow-menu.png`, `actions-menu.png`, `keyboard.png` and `markdown-reader.png` the clock and icons are dark
(no pixel in the top 24 px brighter than 19 of 255). That is the #1510 defect, not counted in their Colour verdicts.
The sheet captures (`run-configuration.png`, `tasks-*.png`) draw light icons over the sheet's scrim. #1510 is closed
and merged; the compact captures, retaken after it, all draw light status-bar icons.

## Removed controls

Checked on every capture: no removed footer selector (the footer is Actions, Cxt, paperclip and the Status
opener), no "Default" option in Run configuration at 412x892 or 320x700 (asserted in `openRunConfiguration` with a
case-insensitive substring match), no thread workspace chip, and no clickable workspace row in the overflow or
Actions menu at either size (asserted in `noWorkspaceAction`). The seeded thread does still show a "Workspace
changed to ~/Workspace/pyrycode-mobile" session delimiter, which is plain text, not an action; #1498 removes it.

## Compact, keyboard and menus

Compact captures are 320x700 at font scale 1.5, taken at `main` `85466a15` with real 24 px status and navigation
bars. Only `676:3981` has a frame at that size; the rest are checked for clipping, overlap and unreachable controls.
Thread-side production changes between `3cebb1ad` and `85466a15` are #1509 (short-stream top anchoring, which does
not apply to the seeded thread's full list) and #1510 (status-bar icons).

| State | Capture | Result |
|---|---|---|
| Thread, compact | `compact-thread.png` | Header, band, count pill, input and footer fit. Bubbles keep their width rule, so body text wraps at two or three words a line but is not clipped. The context label truncates to "Cxt h…" (#1485) |
| Keyboard open, compact | `compact-keyboard.png` | Compared with `676:3981` above. Input, footer and band stay above the keyboard; no control is hidden |
| Offline pill, compact | `compact-offline.png` | "Offline · Retry" fits on one line at the right; no overlap with the header |
| Offline with pairing error, compact | `compact-offline-overlays.png` | "Pairing error - Re-pair" (waited for) replaces the Offline pill in `ThreadTopOverlay`; it overlays the top bubble as designed. No clipping |
| Usage pill, refusal offer and strip, compact | `compact-notices.png` | The usage-limit pill wraps to three lines across the message area (#1499) but keeps its dismiss X reachable. The refusal row and "Switch back to" button wrap and stay inside the gutter; the four tiles and their remove badges fit above the input. The second image tile shows its PNG glyph because its thumbnail had not loaded at capture; the tile's size and badge are unchanged |
| Overflow menu, compact | `compact-overflow-menu.png` | All four rows visible and reachable; no workspace action |
| Actions menu, compact | `compact-actions-menu.png` | All four rows visible above the footer; it covers the count pill while open, which is the menu's overlay, not a layout overlap |
| Task panel, compact | `compact-tasks.png` | Title wraps to two lines beside the close X; cards scroll and the Close button stays reachable (#1496 removes it; the test closes through the header X) |
| Run configuration, compact | `compact-run-configuration.png` | Model and effort rows fit, and Done is pinned, displayed and reachable (asserted). The capture ends at "Running model"; the Permission section lies below it in the sheet's scrolling column. The test asserts only that the "Auto approval" row is composed, not that it or "Bypass approvals" scrolls into view, so reaching the Permission rows at this size is not shown |

Approved geometry is unchanged: the audit changes no production code.

## Gaps

The six states the first pass listed here now have frames in `674:5852` and are audited above.

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Codex agent switch | none | #1118 | pending #1118 |
| Status-band arms `Resetting`, `ApiRetry`, `Compacting`, `TurnOutcome`, `Working`, `Stalled`, `RunningTool` | none | #1312, #897, #803 | #1529 |
| `StoppedTurn`, `CompactionBoundary` and `UnrecognizedMessage` rows | none | #1356, #874, #1358, #608 | #1529 |
| Slash-command type-ahead and thread snackbars | none | #885, #1149 | #1529 |
| Send button's Stop variant (busy turn, empty draft) | none | #459, #643 | #1529 |
| History tail rows: loading, retry, dead end, offline | none | #777, #778, #1352 | #1529 |
| Composer strip while sending: "Uploading… N%" and dimmed tiles | none | #1327 | #1529 |
| Top overlay Error pills: failed MCP server, and a usage-limit reading that is not a warning | none | #1345, #1002, #1115 | #1529 |

No Mobile-page frame or Components-page component shows these states on 2026-10-02, so they are not captured. The
Input area component `134:5013` has no Stop variant, and `ThreadInputBar` says so. For the two Error pills, the
Components page's Pill `State=Error` (`347:6619`) gives the pill style, which the pairing-error pill already matches
in `568:3139`, but no frame shows their text, tap target or place in the stack. The
owning tickets are closed; #1529 asks for a frame or a recorded out-of-reference decision for each. The
thread dialogs (Channel Info `668:5355` and `668:5460`, Rename `671:5664`, Save as channel `671:5718`, Delete
confirmation `673:3665`) belong to #1431's list-side audit in `design-1220/list/`.

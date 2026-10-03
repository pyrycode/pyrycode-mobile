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
- **#1529 pass:** `main` at `7537ec9b` plus the test-only methods `rowAndNoticeFramesAt412By892`,
  `historyTailFramesAt412By892` and `uploadingFrameAt412By892` on `feature/1529`, on `pixel8Api35` with
  `requireRealSystemBars=true` (`1529-results.xml`, 3 executed, 0 failures). The nine frames of section **Thread
  states · #1529 · 2026-10-02** (`685:3991`) were exported with `get_screenshot` on 2026-10-03. See "Thread states
  (#1529)".
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

**Routed defects.** #1494 refusal model names (fixed; `620:1577` and `646:4707` retaken) · #1496 inset sheets and the task panel's Close · #1497 Run
configuration (fixed; `600:1694` retaken) · #1498 workspace delimiter in the seed · #1499 Offline and usage-limit pills (usage copy fixed by #1519; `568:3139` retaken) · #1485 compact footer ·
#1512 delimiter rule inset · #1513 photo above text and photo bubble width · #1603 turn outcome pill · #1604 failure
pill · #1605 history tail gutter and spacing · #1606 stale Stop-variant comment · #1607 type-ahead row spacing ·
#1608 unrecognized and stopped-turn row spacing ·
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
| Geometry | mismatch: the user bubble draws its text first and the photo below it; the frame draws the photo at the top of the bubble. The refusal title wraps to two lines because this capture's repository override seeds no model menu, so the names stay monospace by design (`620:1577` seeds the menu and is one line). The photo message bubble is about 272 px wide where the frame's (`I533:1956;132:4567`) is 222 px, so the bubble and photo sit about 50 px further left. Photo size, the other bubble widths and the file row match |
| Padding | match: 20 px bubble padding |
| Spacing | match |
| Typography | mismatch: refusal model names render as raw monospace identifiers because this capture seeds no model menu, by design (`620:1577` seeds the menu and matches). File row name middle-ellipsized ("Filename of th…ttachment.pdf"), as the updated frame |
| Colour | match: file row icon and name in the light on-secondary-container tone the updated frame uses |
| Borders | match: header divider, PDF outline |
| Radii | match: bubbles, photo corners |
| Icon paths | match: back, overflow, copy, document outline, snowflake |
| Component state | match: thinking, send enabled, photo loaded |

- **Routed:** #1513 (photo position and photo bubble width). The frame's PDF row between two paragraphs cannot be
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

- **Owning ticket:** #875 (refusal row), #1290 (message attachment), #1494 (model names)
- **Capture:** `notification-text.png` (412x892, 1.0), retaken for #1494 with the menu seeded
  (`notice-1494-results.xml`, `threadNoticeFramesAt412By892`, 1 executed, 0 failures)
- **Side-by-side:** `notification-text-side-by-side.png`
- **Overlay:** `notification-text-overlay.png`
- **Verdict:** match
- Reference components: `figma-620-1576.png`, `figma-390-7145.png`.

| Aspect | Verdict |
|---|---|
| Geometry | match: "Refused on Opus, continued on Sonnet" on one line, as the frame |
| Padding | match |
| Spacing | match: Show details sits 4 px under the title |
| Typography | match: the menu's labels "Opus" and "Sonnet" in the title's body style and colour (#1494). An identifier the menu does not know keeps its monospace span |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match: document outline with folded corner and "PDF" |
| Component state | match: collapsed refusal with Show details |

- **Routed:** none

### Refusal switch back — `646:4707`

- **Owning ticket:** #1360, #1494 (model names)
- **Capture:** `refusal-switch-back.png` (412x892, 1.0), retaken for #1494 with the menu seeded
  (`notice-1494-results.xml`, 1 executed, 0 failures)
- **Side-by-side:** `refusal-switch-back-side-by-side.png`
- **Overlay:** `refusal-switch-back-overlay.png`
- **Verdict:** match

| Aspect | Verdict |
|---|---|
| Geometry | match: one-line title; the button is about 150 px wide, as the frame's |
| Padding | match: 16 px button side padding |
| Spacing | match: 12 px from Show details to the button |
| Typography | match: "Switch back to Opus" in the button's style, the menu label its own span (#1494) |
| Colour | match: outlined primary button |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: offer armed, not pending |

- **Routed:** none

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

- **Owning ticket:** #1043 (count pill), #1002 and #1519 (usage-limit pill), #842 (pairing error)
- **Capture:** `task-count-pill.png` (412x892, 1.0)
- **Side-by-side:** `task-count-pill-side-by-side.png`
- **Overlay:** `task-count-pill-overlay.png`
- **Verdict:** match
- **Recaptured for #1519** with `threadStatusFramesAt412By892` on `pixel8Api35`, `requireRealSystemBars=true`
  (`1519-results.xml`, 2 executed with `compactAt320By700`, 0 failures). `figma-568-3139.png` was re-exported on
  2026-10-03; only the message area's filler changed. Only `task-count-pill*.png` and `compact-notices.png` were
  replaced from that run.

| Aspect | Verdict |
|---|---|
| Geometry | match: the usage-limit pill hugs its text on one line at the right, x 149–391 against the frame's 154–391 (device glyphs 5 px wider), 24 px lower for the status bar. The count pill and pairing-error pill match |
| Padding | match |
| Spacing | match: 12 px between the overlay pills |
| Typography | match: "Nearly at usage limit - 7-day window", the frame's copy |
| Colour | match: primary-container count and usage pills, error-container pairing pill |
| Borders | match |
| Radii | match |
| Icon paths | match: dismiss X on the usage pill |
| Component state | match: warning dismissible, two tasks running |

- **Routed:** #1499, then #1519 (usage-limit copy; fixed, `568:3139` retaken)

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
- **Verdict:** mismatch (inset sheet only, #1496)
- Four-model menu with real resolved identifiers and auto mode, Sonnet at high effort, Manual approval. No "Default"
  option in any spelling (asserted with a case-insensitive substring match).
- **Recaptured for #1497** with `runConfigurationAndReaderAt412By892` on `pixel8Api35`, `requireRealSystemBars=true`
  (`1497-results.xml`, 1 executed, 0 failures), and compared again with `scripts/design-compare.py`. Only
  the `run-configuration` PNGs (capture, side-by-side, overlay) were replaced; `markdown-reader.png` keeps its #1432 capture.

| Aspect | Verdict |
|---|---|
| Geometry | match apart from the inset: each model row is one line, so Effort sits at its frame position plus the 24 px bar shift and the sections below stay within 2 px of it, and "Bypass approvals" is in view. The sheet ends above the navigation bar with rounded bottom corners; the frame runs it to the screen's bottom |
| Padding | match |
| Spacing | match: Permission heading and row spacing |
| Typography | match: model rows show the label only, and effort options read "Low/Medium/High/Max" |
| Colour | match: radios, labels and Done |
| Borders | match: header divider |
| Radii | mismatch: rounded bottom sheet corners (see geometry); Done matches |
| Icon paths | match: close X, radios |
| Component state | match: Permission section with the frame's six rows in its order, Manual approval selected |

- **Routed:** #1496 (the `MobileModalShell` inset shared with the task panel). #1497's model rows and effort case are fixed

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

## Thread states (#1529)

Nine frames in `685:3991`, drawn on 2026-10-02 for the states the first pass listed under Gaps. The class's
repository override adds the frames' inputs: two unrecognized rows, a stopped-turn row with its `TurnEnd`, an
`archive` that fails, a slash-command menu on the fake, a `requestHistory` held on a test gate, a
`repositoryAvailable` flow, and an `uploadAttachment` that reports 4 of 10 chunks and then suspends. Every state
waits strictly for its marker ("Payload truncated by the daemon.", "Stopped: context too long, compact or reset",
"Couldn't archive this conversation. Try again.", "Open the config panel", the history row's text after its
`historyTail` reading, the strip's "Uploading… 40%" state description). The seed's messages stand in for the frames'
filler bubbles and are not compared. Composer and footer verdicts are as in "Composer and footer".

Three frames draw designs Juhana changed on 2026-10-02 (turn outcome, failure notice, history tail gutter). The
captures show what the app draws today, so those verdicts are expected mismatches with their own tickets.

### Turn outcome — `685:3992`

- **Owning ticket:** #1357 (recovery advice), #1356 (stopped-turn row)
- **Capture:** `turn-outcome.png` (412x892, 1.0)
- **Side-by-side:** `turn-outcome-side-by-side.png`
- **Overlay:** `turn-outcome-overlay.png`
- **Verdict:** mismatch (changed design)
- A `TurnEnd` with `isError` and `terminalReason` `prompt_too_long`, and the matching `StoppedTurn` row.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the outcome sits in the status band beside the snowflake; the frame moves it to a pill in the top overlay's right-aligned stack, under the header, and leaves the band at the snowflake alone. The stopped-turn row has 20 px from the bubble above to its text; the frame has 30 |
| Padding | match: stopped-turn row at the 20 px gutter |
| Spacing | mismatch: the stopped-turn row's 10 px (see geometry) |
| Typography | mismatch: the band pill reads "Context too long. Compact or reset the session." on two lines plus a separate "Compact" pill; the frame's pill reads "Context too long - Compact" on one line. The stopped-turn row's text and style match |
| Colour | match: error-container pill |
| Borders | match |
| Radii | match: pill radius |
| Icon paths | mismatch: the band pill has a leading error icon; the frame's pill has none |
| Component state | mismatch: Compact is its own pill; in the frame the whole pill runs Compact |

- **Routed:** #1603 (the pill), #1608 (the stopped-turn row's spacing)

### Unrecognized message — `685:4112`

- **Owning ticket:** #608
- **Capture:** `unrecognized-message.png` (412x892, 1.0)
- **Side-by-side:** `unrecognized-message-side-by-side.png`
- **Overlay:** `unrecognized-message-overlay.png`
- **Verdict:** mismatch
- A collapsed `thinking_delta` assistant-block row, and a `server_tool_use` whole-message row expanded by a tap,
  truncated, with the frame's payload.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: rows are 372 px wide at the 20 px gutter as in the frame, but the collapsed row is 34 px tall against 36 and the expanded row 96 against 100 |
| Padding | match: icon and text insets within 1 px |
| Spacing | mismatch: 12 px between the two rows; the frame has 16 |
| Typography | mismatch: label, monospace type and site label, payload and truncation note match in style, but the payload breaks inside a token (`"n` / `ame"`) where the frame breaks after the comma |
| Colour | match: orange message type, outline, on-surface-variant text |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match: warning triangle |
| Component state | match: collapsed ellipsized, expanded with the truncation note |

- **Routed:** #1608

### Slash-command type-ahead — `685:4232`

- **Owning ticket:** #885, #1149 (keyboard)
- **Capture:** `slash-type-ahead.png` (412x892, 1.0, test IME 240 px)
- **Side-by-side:** `slash-type-ahead-side-by-side.png`
- **Overlay:** `slash-type-ahead-overlay.png`
- **Verdict:** mismatch
- `/co` typed with the keyboard open against a six-command menu; `/clear` and `/help` are filtered out, leaving the
  frame's four rows in its order. The "+N not listed" caption is not drawn, as in the frame.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: 240 px wide at x 24–263 and its foot 4 px above the input, as the frame, but the four rows span 342–549 against 326–549 |
| Padding | match: 12 px text inset |
| Spacing | mismatch: each row is 4 px shorter (pitch 57, 41 and 56 px against 61, 45 and 60), and the description starts 15 px under the label's top against 17 |
| Typography | match: primary label with its argument hint, two-line descriptions wrapping at the frame's words |
| Colour | match: surface and label colours |
| Borders | match |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: four matches, no caption |

- **Routed:** #1607. The test IME stands in for the frame's keyboard placeholder, as in `675:6160`.

### Failure notice — `685:4337`

- **Owning ticket:** #556 (archive failure)
- **Capture:** `failure-notice.png` (412x892, 1.0)
- **Side-by-side:** `failure-notice-side-by-side.png`
- **Overlay:** `failure-notice-overlay.png`
- **Verdict:** mismatch (changed design)
- Archive from the overflow menu, failing in the override.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: a 388 px bottom snackbar above the band; the frame draws a pill hugging its text at the right of the top overlay, under the header |
| Padding | mismatch: snackbar insets, not the pill's |
| Spacing | mismatch: see geometry |
| Typography | match: "Couldn't archive this conversation. Try again." The snackbar's body style is larger than the pill's |
| Colour | mismatch: inverse-surface snackbar; the frame's pill is error-container |
| Borders | match (none) |
| Radii | mismatch: snackbar corners, not the pill's |
| Icon paths | match (none, no X) |
| Component state | match: hides itself after the snackbar's duration (waited for) |

- **Routed:** #1604

### History tail — Loading `689:4281`, Retry `689:4330`, Dead end `689:4379`, Offline `689:4427`

- **Owning ticket:** #777, #778, #1352
- **Capture:** `history-loading.png`, `history-retry.png`, `history-dead-end.png`, `history-offline.png` (412x892, 1.0)
- **Side-by-side:** `history-<state>-side-by-side.png`
- **Overlay:** `history-<state>-overlay.png`
- **Verdict:** mismatch
- The open's newest-page ask held (Loading), failed retryably (Retry), retried and failed permanently (Dead end),
  then the view model's `repositoryAvailable` set false (Offline). The connection state stays Connected, so the
  Offline capture shows the row without the Offline pill, as the frame does. The list is scrolled to the row.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: Retry, Dead end and Offline draw the error-container surface edge to edge (x 0–411); the frames keep it in the 20 px gutter (x 20–391), Juhana's change. Retry is 40 px tall against 44; Dead end and Offline 38 against 40. Loading's spinner and label sit at the frame's position |
| Padding | match: text 20 px inside the surface (it starts at 21 against the frame's 41 because of the gutter) |
| Spacing | mismatch: the row sits flush on the oldest bubble; the frames leave 16 px |
| Typography | match: labels and "Try again" |
| Colour | match: error-container surface, primary spinner, on-surface-variant loading label |
| Borders | match (none) |
| Radii | match |
| Icon paths | match: 16 px spinner |
| Component state | match: Try again only in Retry |

- **Routed:** #1605

### Uploading attachments — `689:4475`

- **Owning ticket:** #1327
- **Capture:** `uploading-attachments.png` (412x892, 1.0)
- **Side-by-side:** `uploading-attachments-side-by-side.png`
- **Overlay:** `uploading-attachments-overlay.png`
- **Verdict:** match
- The frames' four staged tiles, `sendMessage`, and the first upload held at 4 of 10 chunks. The strip's state
  description reads "Uploading… 40%" (waited for); nothing draws it.

| Aspect | Verdict |
|---|---|
| Geometry | match: tiles at x 20–65 and their frame row, rings over each tile's top-right corner |
| Padding | match |
| Spacing | match |
| Typography | match: PDF label |
| Colour | match: tiles at 55 % opacity, primary rings, Send dimmed |
| Borders | match: PDF tile outline |
| Radii | match |
| Icon paths | match: no remove badges; the first ring determinate at 40 %. The other three are indeterminate, so the arc each shows depends on the animation's phase at capture |
| Component state | match: sending, Send disabled |

- **Routed:** none

### No separate frame

Decided on #1529 (2026-10-02); each state's reference is the node named. None is captured.

| State | Reference |
|---|---|
| `Resetting`, `ApiRetry`, `Compacting`, `Working`, `RunningTool` band arms | The band's "Thinking…" reading in `16:8` and Input area `134:5013`: the snowflake, then one body-small line in the primary colour. The app's strings are the reference for the words |
| `Stalled` band arm | The same line in the error colour, the "Cxt high" label's colour |
| `StoppedTurn` row | Thread notification, No details state, `620:1574`, with the stopped text; shown in context in `685:3992` above |
| `CompactionBoundary` row | The rule row of the session delimiter frame `675:3682`, with the compaction label and no explanation line |
| Send button's Stop variant | Message input button component `Action=Stop`, `114:3549`, on the Desktop page. `ThreadInputBar`'s comment that the component has no Stop variant is out of date (#1606) |
| Top overlay Error pills: failed MCP server, and a usage-limit reading that is not a warning | Pill `347:6619`, Error state, X off, in the right-aligned top stack `568:3139` draws for the pairing error. Each pill's text is the app's string and the whole pill is the tap target. #1519 owns the usage-limit wording |

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
| Usage pill, refusal offer and strip, compact | `compact-notices.png` | The usage-limit pill wraps to two lines without truncating, "Nearly at usage limit - 7-day" over "window", and keeps its dismiss X reachable (#1519). The refusal row and "Switch back to" button wrap and stay inside the gutter; the four tiles and their remove badges fit above the input. In the #1519 retake all three image tiles show their thumbnails |
| Overflow menu, compact | `compact-overflow-menu.png` | All four rows visible and reachable; no workspace action |
| Actions menu, compact | `compact-actions-menu.png` | All four rows visible above the footer; it covers the count pill while open, which is the menu's overlay, not a layout overlap |
| Task panel, compact | `compact-tasks.png` | Title wraps to two lines beside the close X; cards scroll and the Close button stays reachable (#1496 removes it; the test closes through the header X) |
| Run configuration, compact | `compact-run-configuration.png` | Model and effort rows fit, and Done is pinned, displayed and reachable (asserted). The capture ends at "Running model"; the Permission section lies below it in the sheet's scrolling column. The test asserts only that the "Auto approval" row is composed, not that it or "Bypass approvals" scrolls into view, so reaching the Permission rows at this size is not shown. Captured before #1497, so it still shows two-line model rows and lowercase effort labels |

Approved geometry is unchanged: the audit changes no production code.

## Gaps

The six states the first pass listed here now have frames in `674:5852`, and the #1529 states have frames in
`685:3991` or a recorded decision; both are audited above.

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Codex agent switch | none | #1118 | pending #1118 |

The thread dialogs (Channel Info `668:5355` and `668:5460`, Rename `671:5664`, Save as channel `671:5718`, Delete
confirmation `673:3665`) belong to #1431's list-side audit in `design-1220/list/`.

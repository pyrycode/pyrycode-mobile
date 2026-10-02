# Thread audit (#1432)

- **App commit:** `main` at `4c755aa6` (the `main` merged into `feature/1432`), plus the test-only
  `ThreadDesignCaptureTest` on `feature/1432`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, re-read and exported with `get_screenshot` on 2026-10-02. Every
  frame the ticket names is present at 412x892. The Components page (`347:5692`) supplies `figma-390-7145.png`
  (Attachment) and `figma-620-1576.png` (Thread notification); it has no tool row, delimiter or menu component.
- **Capture:** `ThreadDesignCaptureTest` (six methods) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0 and font scale 1.0, and 320x700 at font scale 1.5 for the
  compact method. Fixed dark theme, real 24 px status and navigation bars. Each `.txt` records the measured values.
- **Result:** `thread-results.xml`, 6 executed, 0 failures, 0 errors, 0 skipped.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames have no
system chrome, and the thread lays out inside the bars: every thread element sits 24 px below its frame position at
the top and 24 px above it at the bottom. That shift is not counted as a mismatch.

**Inputs.** States are reached through `DesignInputs` (connection state, live events, task roster and count, pairing
rejection, context usage) and the class's own repository override, which adds the frames' attachment message, notice
and refusal rows, the held turn phase, a usage-limit reading, the refusal offer and one markdown note. The composer
strip's images are MediaStore PNGs, so their thumbnails load as from the picker; their content is a placeholder
shape, not the frames' photo. Message text is the demo seed's, not the frames' lorem ipsum, and timestamps follow the
emulator's `en-US` locale ("5/10/26 - 11:45 AM" for the frames' "13.01.2026 - 13:55"). Neither is compared.

**Routed defects.** #1499 Top overlay pills · #1493 connection states · #1494 notification rows · #1495 attachments ·
#1496 task panel · #1497 Run configuration · #1498 workspace delimiter · #1485 composer footer (filed by #1433; the
thread frames show the same differences) · #1500 states with no frame · #1118 agent switch (pending).

## Composer and footer (shared by every thread frame)

The status band, attachment strip, input and footer appear on `16:8`, `627:*` and `568:3139`; verdicts here apply to
each of those frames, which list only what differs.

| Aspect | Verdict |
|---|---|
| Geometry | match: band, strip, input and footer stack in the frame's order and heights |
| Padding | match: 20 px gutters |
| Spacing | match |
| Typography | match: input and "Thinking…" label |
| Colour | mismatch: the footer reads "Cxt high: 84%" in the error colour where every frame reads "Cxt: 84%" in primary |
| Borders | match: input has none, as in the frame |
| Radii | match: input and tile corners |
| Icon paths | mismatch: the footer adds the tune Status opener after the paperclip; the frames have the paperclip only |
| Component state | match: send enabled with text, Actions with chevron |

- **Routed:** #1485

### Conversation Thread — `16:8`

- **Owning ticket:** #1206 (frame), #933 (strip), #1312 (status band)
- **Capture:** `thread.png` · **Side-by-side:** `thread-side-by-side.png` · **Overlay:** `thread-overlay.png`
- Thinking turn, 84 % context, four staged attachments (image, image, PDF, image).

| Aspect | Verdict |
|---|---|
| Geometry | match: header, divider, bubbles, strip and composer at the frame's coordinates inside the bars; assistant bubbles 20–292 px and user bubbles end at 392 px, as in the frame |
| Padding | match: 20 px bubble padding |
| Spacing | match |
| Typography | match: title, body, timestamp and band label styles |
| Colour | mismatch: the strip's PDF tile outline and label are bright light blue, the frame's are muted blue; footer as above |
| Borders | match: header divider, tool row outline |
| Radii | match |
| Icon paths | match: back, overflow, copy, snowflake, remove badge; footer as above |
| Component state | match: thinking, send enabled |

- **Routed:** #1495 (PDF tile colour), #1485 (footer)

### Notification text — `620:1577`

- **Owning ticket:** #875 (refusal row), #1290 (message attachment)
- **Capture:** `notification-text.png` · **Side-by-side:** `notification-text-side-by-side.png` · **Overlay:** `notification-text-overlay.png`
- Reference components: `figma-620-1576.png`, `figma-390-7145.png`.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the refusal title wraps to two lines where the frame's is one |
| Padding | match |
| Spacing | match: Show details sits 4 px under the title |
| Typography | mismatch: models are named by raw identifier in a monospace span ("`claude-opus-5-5`"), the frame uses display names in the body style ("Opus"); the attachment filename is middle-ellipsized where the frame cuts it at the bubble edge |
| Colour | mismatch: the attachment row's document icon and filename are light grey, the component's are muted blue |
| Borders | match |
| Radii | match |
| Icon paths | match: document outline with folded corner and "PDF" |
| Component state | match: collapsed refusal with Show details |

- **Routed:** #1494 (model names), #1495 (attachment colour and truncation)

### Refusal switch back — `646:4707`

- **Owning ticket:** #1360
- **Capture:** `refusal-switch-back.png` · **Side-by-side:** `refusal-switch-back-side-by-side.png` · **Overlay:** `refusal-switch-back-overlay.png`

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
- **Capture:** `session-notice.png` · **Side-by-side:** `session-notice-side-by-side.png` · **Overlay:** `session-notice-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match |
| Padding | match |
| Spacing | match |
| Typography | mismatch: "Warning · Claude:" is medium weight; the frame's line is one regular weight |
| Colour | match: on-surface-variant text |
| Borders | match (none) |
| Radii | match (none) |
| Icon paths | match (none) |
| Component state | match: warning notice |

- **Routed:** #1494

### Connecting — `627:1740`, Reconnecting — `627:4657`

- **Owning ticket:** #1283 (connection states), #1312 (snowflake)
- **Capture:** `connecting.png`, `reconnecting.png` · **Side-by-side:** `connecting-side-by-side.png`, `reconnecting-side-by-side.png` · **Overlay:** `connecting-overlay.png`, `reconnecting-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the band's text starts 22 px right of the frame's, after the snowflake |
| Padding | match |
| Spacing | match |
| Typography | match: "Connecting…", "Reconnecting in 12s" |
| Colour | mismatch: Actions and Send are dimmed while disconnected; the frames show both enabled |
| Borders | match |
| Radii | match |
| Icon paths | mismatch: snowflake drawn in the band; the frames have none in these states |
| Component state | mismatch: Actions disabled without its chevron, Send disabled |

- **Routed:** #1493, #1485 (footer)

### Offline — `627:4910`

- **Owning ticket:** #1283
- **Capture:** `offline.png` · **Side-by-side:** `offline-side-by-side.png` · **Overlay:** `offline-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the Offline · Retry pill is drawn 144 px wide with its text at the left; the frame's pill hugs its text (about 92 px) at the right edge |
| Padding | mismatch: pill text inset (see geometry) |
| Spacing | match: pill 12 px under the header |
| Typography | match |
| Colour | match: error container pill; Actions and Send dimmed as on Connecting |
| Borders | match |
| Radii | match: pill radius |
| Icon paths | match |
| Component state | mismatch: Actions and Send disabled, as on Connecting |

- **Routed:** #1499 (pill), #1493 (disabled controls)

### Task count pill — `568:3139`, with the usage-limit and pairing-error pills

- **Owning ticket:** #1043 (count pill), #1002 and #1115 (usage-limit pill), #842 (pairing error)
- **Capture:** `task-count-pill.png` · **Side-by-side:** `task-count-pill-side-by-side.png` · **Overlay:** `task-count-pill-overlay.png`

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
- **Capture:** `tasks-populated.png`, `tasks-capped.png`, `tasks-empty.png`, `tasks-never-reported.png` · **Side-by-side** and **Overlay:** `tasks-<state>-side-by-side.png`, `tasks-<state>-overlay.png`
- Roster fixtures copy the frames' tasks, progress, patches and cut reports.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the sheet ends above the navigation bar with rounded bottom corners; the frames run it to the screen's bottom. Header, groups and cards match |
| Padding | match: 28 px sheet gutters, 14 px card padding |
| Spacing | match |
| Typography | match: title, group headings, monospace commands and patches, meta lines. In Capped the second patch fits one line where the frame wraps "roste" by a few pixels |
| Colour | match: card, tag and cut-marker colours |
| Borders | match: header divider, dashed cut markers, empty-state rings |
| Radii | match |
| Icon paths | match: close X, tag dots, empty and never-reported rings |
| Component state | mismatch: an outlined Close button at the bottom of all four panels; the frames close from the header X only |

- **Routed:** #1496

### Run configuration / Sonnet selected / Dark — `600:1694`

- **Owning ticket:** #1195
- **Capture:** `run-configuration.png` · **Side-by-side:** `run-configuration-side-by-side.png` · **Overlay:** `run-configuration-overlay.png`
- Four-model menu with real resolved identifiers, Sonnet at high effort. No "Default" option is present (asserted).

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: each model row is two lines tall, so Effort and the sections below sit lower than the frame's |
| Padding | match |
| Spacing | match |
| Typography | mismatch: a resolved-identifier second line under every model ("claude-sonnet-5"); effort options read "low/medium/high/max" where the frame reads "Low/Medium/High/Max" |
| Colour | match: radio, labels and Done |
| Borders | match: header divider |
| Radii | match: sheet and Done |
| Icon paths | match: close X, radios |
| Component state | mismatch: a Permission section ("Permission mode unavailable") the frame does not have |

- **Routed:** #1497

### Markdown Reader — `553:2574`

- **Owning ticket:** #1291
- **Capture:** `markdown-reader.png` · **Side-by-side:** `markdown-reader-side-by-side.png` · **Overlay:** `markdown-reader-overlay.png`
- Opened through the published view model's `onOpenMarkdownLink` with the frame's note.

| Aspect | Verdict |
|---|---|
| Geometry | match: header, divider, headings, list, code block and quote at the frame's coordinates inside the bars |
| Padding | match: 20 px gutters |
| Spacing | match |
| Typography | match: heading, body, link, code and quote styles at 24 px line height. The first paragraph breaks after "2026-09-01." where the frame breaks after "since"; that line is within 2 px of the measure in both |
| Colour | match |
| Borders | match: header divider, quote bar |
| Radii | match: code block |
| Icon paths | match: back, overflow |
| Component state | match: rendered note |

- **Routed:** none

### Codex agent switch — Switching `578:3248`, Switch confirm `578:3442`

- **Owning ticket:** #1118, open on 2026-10-02.
- Not captured. Both frames are pending #1118; `figma-578-3248.png` and `figma-578-3442.png` are kept for it.

## Removed controls

Checked on every capture: no removed footer selector (the footer is Actions, Cxt, paperclip and the Status
opener), no "Default" option in Run configuration (asserted in `runConfigurationAndReaderAt412By892`), no thread
workspace chip, and no clickable workspace row in the overflow or Actions menu (asserted in
`menusAndKeyboard`). The seeded thread does still show a "Workspace changed to ~/Workspace/pyrycode-mobile" session
delimiter, which is plain text, not an action; #1498 removes it.

## Compact, keyboard and menus

Compact captures are 320x700 at font scale 1.5 and have no frame at that size, so they are checked for clipping,
overlap and unreachable controls rather than compared.

| State | Capture | Result |
|---|---|---|
| Thread, compact | `compact-thread.png` | Header, band, count pill, input and footer fit. Bubbles keep their width rule, so body text wraps at two or three words a line but is not clipped. The footer's context label truncates to "Cxt h…", hiding the percentage (#1485) |
| Offline with pairing error, compact | `compact-offline-overlays.png` | Pairing error pill overlays the top bubble as designed; Offline is absent because the pairing error replaces it in `ThreadTopOverlay`. No clipping |
| Overflow menu, compact | `compact-overflow-menu.png` | All four rows visible and reachable; no workspace action |
| Actions menu, compact | `compact-actions-menu.png` | All four rows visible above the footer; it covers the count pill while open, which is the menu's overlay, not a layout overlap |
| Keyboard open, compact | `compact-keyboard.png` | Input, footer and band stay above the keyboard; the message list shrinks. No control is hidden |
| Overflow menu, 412x892 | `overflow-menu.png` | Rows reachable, no workspace action. No frame (#1500) |
| Actions menu, 412x892 | `actions-menu.png` | Rows reachable, no workspace action. No frame (#1500) |
| Keyboard open, 412x892 | `keyboard.png` | Input and footer above the test IME; nothing clipped. No frame (#1500) |

Approved geometry is unchanged: the audit changes no production code.

## Gaps

States reachable from `MainActivity` or a `ThreadScreen` overlay with no current frame or component. They are
captured here; #1500 asks for frames or a decision.

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Tool row (`ToolCallRow`) | `thread.png` | #1208 | #1500 |
| Session boundary delimiter | `overflow-menu.png` | #1207 | #1500 |
| Thread overflow menu | `overflow-menu.png` | #1199 | #1500 |
| Footer Actions menu | `actions-menu.png` | #884 | #1500 |
| Composer with the keyboard open | `keyboard.png` | #1149 | #1500 |
| Codex agent switch | none | #1118 | pending #1118 |

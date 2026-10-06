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
- **#1540 pass:** `main` at `2ce6beec` plus the test-only `refusalStateFramesAt412By892` (`1540-results.xml`, 1
  executed, 0 failures), for the refusal row's Expanded, Switch back pending and Switch back failed components
  (`620:1570`, `646:4694`, `646:4700`), exported on 2026-10-03. See "Refusal row states (#1540)".
- **#1615 repair (2026-10-05):** the switch-back captures below replace the #1494/#1540
  readings for armed, pending and failed offers. The final rework's full `pixel8Api35` selection ran with
  `requireRealSystemBars=true`: [device XML](switch-back-1615-rework-device-results.xml), **20 executed/passed,
  0 failed, 0 errors, 0 skipped**, including `ThreadDesignCaptureTest#threadNoticeFramesAt412By892` and
  `#refusalStateFramesAt412By892`, both passed. All four nonblank frames are 412×892, density/font scale 1.0,
  hardware accelerated, `syntheticBars=false`, with visible real 24 px status/navigation bars. See the
  [measurements, crop coordinates and hashes](switch-back-1615-measurements.txt) and frame sidecars.
  [Unit XML](switch-back-1615-rework-unit-results.xml) records **50 executed/passed, 0 failed/errors/skipped**;
  [scripted XML](switch-back-1615-rework-scripted-results.xml) records **1 executed/passed, 0 failed/errors/skipped**,
  `DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`.
  The [verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1794#issuecomment-5990057647) confirmed
  current Figma exports for `646:4694`, `646:4700` and `646:4707` are pixel-identical to the retained references.
  These are builder captures and verifier inspection; documentation ran no new tests. The unrelated UI focus
  failure is tracked in #1797; the full dispatcher scripted sweep was not run and is not claimed green.
- **Strict waits:** every frame state waits for its marker text ("Connecting…", "Offline · Retry", "Thinking",
  "2 tasks running", "Pairing error", "Switch back to", "Sonnet", "Manual approval", "Builder Pipeline Plan",
  "No background tasks", "No background-task report yet" and so on) and fails the run if it does not appear within 5 s. The bubble photo's decode gets 10 s. No capture is taken of a state that did not render.

Verdicts compare each capture with its frame at 1:1 in the side-by-side and overlay images. Figma's frames have no
system chrome, and the thread lays out inside the bars: every thread element sits 24 px below its frame position at
the top and 24 px above it at the bottom. That shift is not counted as a mismatch.

**Inputs.** States are reached through `DesignInputs` (connection state, live events, task roster and count, pairing
rejection, context usage) and the class's own repository override, which adds the frames' photo and PDF messages,
notice and refusal rows, the clear and idle-evict delimiters, the held turn phase, a usage-limit reading, the
refusal offer, the photo file behind `retrieveAttachment` and one markdown note. The #1529 pass's methods add three
more override hooks: `requestHistory`, held on a test-owned gate to drive the history tail through Loading, Retry
and Dead end; a failing `archive`, for the failure notice; and a suspending `uploadAttachment` that reports partial
chunk progress, for the uploading frame. A `repositoryAvailable` flow drives the history tail's Offline state.
`requestHistory` and `repositoryAvailable` keep the fake's own behaviour unless a test sets them, so none of the six
earlier methods is affected. Images are generated placeholder shapes, not the frames' photo. Message text is partly
the demo seed's, and timestamps follow the emulator's `en-US` locale ("5/10/26 - 12:00 PM" for the frames'
"13.01.2026 - 13:55"). Neither is compared.

**Routed defects.** #1494 refusal model names (fixed; `620:1577` and `646:4707` retaken) · #1496 inset sheets and the task panel's Close · #1497 Run
configuration (fixed; `600:1694` retaken) · #1498 workspace delimiter in the seed · #1499 Offline and usage-limit pills (usage copy fixed by #1519; `568:3139` retaken) · #1485 compact footer ·
#1512 delimiter rule inset · #1513 photo above text and photo bubble width · #1603 turn outcome pill · #1604 failure
pill · #1605 history tail gutter and spacing · #1606 Stop glyph and reference (fixed; verdict below) · #1607 type-ahead row spacing ·
#1608 unrecognized and stopped-turn row spacing · #1614 expanded refusal row spacing and attribution weight ·
#1615 switch-back button height and gaps, and its failure snackbar (fixed; verdicts below) ·
#1532 PDF tile not dimmed while disconnected · #1533 reader list indent · #1534 task panel spacing · #1118 agent switch
(pending) · #1510 dark status-bar icons (fixed after these captures; see Status bar). #1493, #1495 and #1500 asked
for these captures against the updated frames; their verdicts are below, and the states #1500 could not cover moved
to #1529.

**#1619 routed defects (Reachable states, #1539).** #1622 queued-row spacing (drop-button loss already fixed by
#1642) · #1623 (closed) / #1626 nested tool rows' Stop-vs-Send and path shortening, both frame-side questions ·
#1624 (closed, fixed by #1848) / #1850 attachment Retry row's residual 8 px · #1625 (closed, fixed by #1848) ·
#1851 the dismissal notice and the thread/reader Saved confirmations' move to a top-overlay Default pill.

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

### Stop button — `114:3549` (#1606)

- **Reference:** [Message input button, `Action=Stop`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=114-3549), Desktop page of the Mobile file.
- **Capture:** [stop-1606-412x892.png](stop-1606-412x892.png), captured 2026-10-05 by `ComposerFieldCaptureTest.typedAndStopAt412By892` with Connected, busy-turn, empty-draft state.
- **Configuration:** [sidecar](stop-1606-412x892.png.txt): full `pixel8Api35`, API 35, 412×892, density 1.0, font scale 1.0, static dark, dynamic colour off; actual `decorView.draw` pixels.
- **Focused test evidence:** [stop-1606-api35-results.xml](stop-1606-api35-results.xml) names `typedAndStopAt412By892` and records **1 executed, 1 passed, 0 failures, 0 errors, 0 skipped**. This was a separate focused device run, not a count inferred from the complete UI gate.
- **Comparison assets:** [retained Figma export](figma-stop-114-3549.png), [48×48 button crop](stop-1606-button.png), [side-by-side](stop-1606-side-by-side.png), [overlay](stop-1606-overlay.png).
- **Verdict:** match for silhouette, size, placement and primary tint. Both glyphs occupy `(10,10)-(38,38)` in the container-less 48×48 button, centered at `(24,24)`, with foreground RGB `(157,203,252)`. The full 28px circle and clear rounded-square cutout match. Three edge pixels differ at the 50% coverage threshold; rasterizer antialiasing and host backgrounds differ.
- **Comparison limit:** the [verifier](https://github.com/pyrycode/pyrycode-mobile/pull/1792#issuecomment-5989365222) independently inspected the retained export and capture, but could not refresh the remote Figma frame. This verdict uses the retained export and the plan's inspected geometry.

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

- **Owning ticket:** #1360, #1494 (model names), #1615 (geometry and inline feedback)
- **Capture:** `refusal-switch-back.png` (412x892, 1.0), retaken after #1615 overflow repair with the menu seeded
  (`switch-back-1615-rework-device-results.xml`, `threadNoticeFramesAt412By892` passed)
- **Side-by-side:** `refusal-switch-back-side-by-side.png`
- **Overlay:** `refusal-switch-back-overlay.png`
- **Verdict:** match

| Aspect | Verdict |
|---|---|
| Geometry | match: one-line title; outline at row-relative y=56..87 (32 px), 150 px wide against Figma's 151 |
| Padding | match: 16 px button side padding |
| Spacing | match: 12 clear px after visible Show details pixels (y=35..43) before the outline at y=56 |
| Typography | match: "Switch back to Opus" in the button's style, the menu label its own span (#1494) |
| Colour | match: outlined primary button |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: offer armed, not pending |

- **Routed:** none. The 48 dp touch bounds extend beyond the visible outline without reserving extra layout
  space. Upper/lower extension taps invoke switch-back independently of details; see the final device XML.
  The 32 dp outline is a minimum: wrapped destinations and enlarged fonts grow it (overflow evidence below).

### Refusal row states (#1540)

The expanded state below retains #1540's capture and verdict. Pending and failed switch-back states use the
final #1615 builder rework captures and current retained 1× Figma component exports. Comparisons align row
origins without scaling. Crops span x=20..392: armed/pending y=564..660 (96 px high), failed/immediate-failed
y=544..660 (116 px high), with exclusive right/bottom coordinates. Positions below are relative to the row
origin. The failed export is padded to the crop height in `figma-646-4700-padded.png`. This alignment excludes
system chrome from component measurements; real bars remain visible in each full frame.

### Notification expanded — `620:1570`

- **Owning ticket:** #875 (refusal row, closed)
- **Capture:** `notification-expanded.png` (412x892, 1.0), row `notification-expanded-row.png` (y 546 to 654)
- **Side-by-side:** `notification-expanded-side-by-side.png`
- **Overlay:** `notification-expanded-overlay.png`
- **Verdict:** mismatch
- The refusal's explanation is the component's prose; the row adds the "Claude: " attribution itself.

| Aspect | Verdict |
|---|---|
| Geometry | match: title on one line, explanation on two, the same line breaks as the component |
| Padding | match: 20 px gutter |
| Spacing | mismatch: the explanation starts at 36 px against 40 (4 px gap under the title against 8), and Hide details at 81 against 87 |
| Typography | mismatch: "Claude:" is a medium-weight span; the component draws the attribution in the explanation's regular body-medium. Title, explanation and Hide details styles match |
| Colour | match: title on-surface-variant, explanation on-surface, Hide details primary |
| Borders | match (none) |
| Radii | match (none) |
| Icon paths | match (none) |
| Component state | match: expanded, toggle reads Hide details |

- **Routed:** #1614

### Refusal switch back pending — `646:4694`

- **Owning ticket:** #1360 (switch back), #1615 (repair)
- **Capture:** `refusal-switch-back-pending.png` (412x892, 1.0), row `refusal-switch-back-pending-row.png` (y 564 to 660)
- **Side-by-side:** `refusal-switch-back-pending-side-by-side.png`
- **Overlay:** `refusal-switch-back-pending-overlay.png`
- **Verdict:** match
- Taken after the tap, while the held write is outstanding; the test waits for the button to be disabled.

| Aspect | Verdict |
|---|---|
| Geometry | match: outline y=56..87 (32 px) in both app and Figma; width 150 px against 151 |
| Padding | match: 16 px button side padding |
| Spacing | match: 12 clear px after visible Show details pixels (y=35..43) before the outline at y=56 |
| Typography | match: "Switch back to Opus" in the button's style |
| Colour | match: outline and label at the component's 38 % opacity |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: disabled while pending, no failed line |

- **Routed:** none. Pending uses alpha 0.38; extension taps invoke neither switch-back nor details.

### Refusal switch back failed — `646:4700`

- **Owning ticket:** #1360 (switch back), #1615 (repair)
- **Capture:** `refusal-switch-back-failed.png` (412x892, 1.0), row `refusal-switch-back-failed-row.png` (y 544 to 660),
  retaken after #1615 repair; no snackbar dismissal is needed
- **Side-by-side:** `refusal-switch-back-failed-side-by-side.png`
- **Overlay:** `refusal-switch-back-failed-overlay.png`
- **Verdict:** match
- **Immediate failure:** [full frame](refusal-switch-back-failed-immediate.png),
  [row crop](refusal-switch-back-failed-immediate-row.png),
  [side-by-side](refusal-switch-back-failed-immediate-side-by-side.png) and
  [overlay](refusal-switch-back-failed-immediate-overlay.png). `refusalStateFramesAt412By892` asserts
  the inline retry message and absence of the shared run-configuration snackbar before timer advancement
  or dismissal. Immediate and settled frames have identical hashes.
- **Feedback decision:** both relay refusal and connection/write failure retain an enabled, retryable offer
  with “Could not change the model — try again.” immediately. Only switch-back suppresses the shared error
  signal; ordinary model/effort edits retain snackbar feedback. Retry clears the inline failure while pending;
  cancellation neither reverts nor reports. The final unit XML includes passing
  `aRefusedOrFailedWrite_keepsTheOfferMarkedFailed_untilTheNextTap`,
  `ordinaryModelAndEffortFailures_stillEmitSharedFeedback` and `cancelledSwitchBack_doesNotRevertOrReportFailure`.
- **Historical evidence:** `refusal-switch-back-failed-snackbar.*` shows the duplicate snackbar before #1615;
  it is not evidence of the current failure state.

| Aspect | Verdict |
|---|---|
| Geometry | match: outline y=56..87 (32 px); width 150 px against Figma's 151 |
| Padding | match: 16 px button side padding |
| Spacing | match: failed text pixels y=95..105, 8 px from the last outline pixel (7 clear px); 12 clear px after Show details before the outline |
| Typography | match: the failed line in body-small, the button label in its style |
| Colour | match: armed primary button, failed line in the error colour |
| Borders | match: 1 px outline |
| Radii | match |
| Icon paths | match (none) |
| Component state | match: enabled retry button and inline failure immediately, without a duplicate snackbar |

- **Routed:** none.
- **Overflow repair:** 32 dp is the minimum visible height with 8 dp vertical label padding. Long unknown
  destinations and enlarged fonts grow the outline and place the retry line below every label line. Final
  unit/device XML both include passing `longUnknownModel_paintsEveryLineInsideTheOutline` (128-character
  identifier) and `enlargedModelText_paintsEveryLineInsideTheOutline` (2× font scale), asserting no visual
  overflow, last-line/outline containment and physical action routing. The row is pinned to 412 dp inside
  its density provider with native fonts; semantics presence alone would miss the first implementation's
  clipped destination. Short-label geometry and the independent 48 dp touch target remain covered by
  `visibleOutline_matchesTheCollapsedDesign_andBothTouchExtensionsInvokeSwitchBack`.

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

### Conversation Thread / Overflow menu — `533:1958` (#1666)

Juhana’s 2026-10-03 decision on #1666 supersedes the old `675:5883` Material menu audit.
The header now reuses the composer’s Options overlay in Actions mode: bodySmall text, 12/6dp
row insets, 2dp column inset, 6dp corners, and no selected indicator or subset caption.
The #1631 approved Background tasks addition stays immediately after Channel info.

- **Current captures:** `overflow-menu.png` (412×892, 1.0) and `compact-overflow-menu.png`
  (320×700, 1.5), replaced for #1666. Sidecars identify MainActivity, API 35, static dark,
  hardware rendering, real 24px bars and no IME. `1666-capture-results.xml` records both capture
  methods passed: 2 executed, 0 failed, 0 skipped.
- **Appearance reference:** existing composer Actions component, Options overlay `533:1958`.
  The direct Figma screenshot is 1×1, so no exact open-menu pixel match is claimed. Light theme
  is covered by shared palette tests, without an independently inspected light header capture.
- **Placement:** 4dp below live button bounds, shared horizontal alignment and 8dp edge clamps;
  compact available height scrolls. Real keyboard reachability is separate device evidence in
  `1666-rework-results.xml`, not shown by these keyboard-closed captures.
- **Historical comparisons:** `overflow-menu-side-by-side.png` and `overflow-menu-overlay.png`
  retain the #1432/#1500 comparison of the former four-row, 48px Material menu with `675:5883`.
  They were not regenerated for #1666 and do not compare the current replacement PNGs.

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
captures show what the app draws today, so those verdicts are expected mismatches with their own tickets. #1747
has since moved the failure notice to the design, and #1603 moved turn recovery to the top overlay.

### Turn outcome — `685:3992`

- **Owning ticket:** #1603 (overlay recovery), #1357 (copy/classification), #1356 (stopped-turn row)
- **Capture:** `turn-outcome.png` (412x892, 1.0, real system bars, API 35)
- **Side-by-side:** `turn-outcome-side-by-side.png`
- **Overlay:** `turn-outcome-overlay.png`
- **Verdict:** pill matches, with a 4px platform text-metric difference; stopped-row spacing remains mismatched
- A `TurnEnd` with `isError` and `terminalReason` `prompt_too_long`, and the matching `StoppedTurn` row.
- Fresh hardware capture at revision `e047e9c0`, 2026-10-05; `turn-outcome.txt` confirms
  density/font scale 1.0 and `syntheticBars=false`. Retained `turn-outcome-1603-results.xml`
  records `ThreadDesignCaptureTest#rowAndNoticeFramesAt412By892`: 1 executed/passed,
  0 failed, 0 skipped, timestamp `2026-10-05T17:22:34`. Comparisons were regenerated.

| Aspect | Verdict |
|---|---|
| Geometry | pill match: right-aligned top overlay, 176×24px at x=216..391/y=121..144; removing the real 24px status bar gives top 97px and a 20px right gutter. Figma width is 172px; shared Android typography hugs the complete copy 4px wider. No message space reserved; idle band keeps the snowflake. Stopped-row mismatch: 20px above its text against 30px in the frame |
| Padding | match: pill 8px horizontal/4px vertical; stopped row at 20px gutter |
| Spacing | pill match: 12dp visible gap with preceding notices and following transient error, measured independently of expanded action bounds. Stopped-row 10px discrepancy remains #1608 |
| Typography | match: exact “Context too long - Compact”, one line in shared body-small; stopped-row text/style match. Retain shared typography rather than force the Figma width |
| Colour | match: error-container background and error text |
| Borders | match: none |
| Radii | match: 6dp corners |
| Icon paths | match: no leading icon, X or separate Compact pill |
| Component state | match: the whole context pill invokes existing Compact once when published, with a merged at-least-48dp target extending downward. Otherwise inert; billing/sign-in retain agent-specific inert copy. Following transient pixels cannot dispatch Compact |

Whole-pill action and coexistence are supported by native geometry/pointer tests;
`turn-outcome-1603-scripted-results.xml` records 1 executed/passed, 0 failed/skipped for
`DeterministicInteractiveStreamE2ETest#interactiveTurn_seededChannel_contextOverflowCompactReachesDaemon`,
proving daemon `/compact` delivery and notice clearing. The final dispatcher scripted-all
run also passed that method (15 executed/passed, 0 failed/skipped).

Dispatcher full live suite on `5eeeeb8254`, run `2026-10-05T18-11-46-185Z`: 57 executed,
57 passed, 0 failed, 0 skipped. The gate report confirms
`InteractiveStreamE2ETest#interactiveTurn_reconnect_slashCommandsAndCompactStillWork`
executed and passed, covering the shared Compact path. This is full-suite evidence,
not a separate focused run; the deterministic scenario proves this pill's tap path.

- **Routed:** #1608 (stopped-turn row spacing). Other captures' owning-ticket verdicts remain unchanged.

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

- **Owning ticket:** #556 (archive failure), #1747 (Error pill migration)
- **Capture:** `failure-notice.png` (412x892, 1.0, real system bars, API 35)
- **Side-by-side:** `failure-notice-side-by-side.png`
- **Overlay:** `failure-notice-overlay.png`
- **Verdict:** match, with one font-metric difference
- Archive from the overflow menu, failing in the override. Recaptured by #1747 after the move from the bottom
  snackbar to the top overlay's Error pill.

| Aspect | Verdict |
|---|---|
| Geometry | match: the pill hugs its text at the right of the top overlay, ending at the 20 px gutter, 97 px below the app-area top, which is 28 px under the measured header through its rule. It is 24 px tall, as in the frame. The text-hug width is 6 px wider than the frame's with the app's shared typography |
| Padding | match: 8 px horizontal, 4 px vertical |
| Spacing | match: below every persistent notice, with the overlay's 12 px gap when one is present |
| Typography | match: body-small "Couldn't archive this conversation. Try again.", right-aligned |
| Colour | match: error-container surface, error text |
| Borders | match (none) |
| Radii | match: 6 px corners |
| Icon paths | match (none, no X) |
| Component state | match: inert, no tap action; expires after the Short snackbar time, adjusted for accessibility |

- **Routed:** none. The width difference is the shared typography's, not this notice's.

### Reader error — `696:5101`

- **Owning ticket:** #1747
- **Capture:** `reader-error.png` (412x892, 1.0, real system bars, API 35)
- **Side-by-side:** `reader-error-side-by-side.png`
- **Overlay:** `reader-error-overlay.png`
- **Verdict:** match, with a copy difference
- The reader's Refresh failing through the production reader. The frame draws the Save failure; the capture
  shows the reachable Refresh failure, whose client-owned copy is "Couldn't open file". The same pill and
  placement serve both.

| Aspect | Verdict |
|---|---|
| Geometry | match: right-aligned at the 20 px gutter, 28 px under the measured reader bar through its rule; the body does not move |
| Padding | match: 8 px horizontal, 4 px vertical |
| Spacing | match |
| Typography | mismatch by design: "Couldn't open file" against the frame's "Couldn't save file"; same body-small style |
| Colour | match: error-container surface, error text |
| Borders | match (none) |
| Radii | match: 6 px corners |
| Icon paths | match (none, no X) |
| Component state | match: inert, expires like the thread pill. Saved still uses the bottom snackbar |

- **Routed:** none. The unresolved Saved and dismissed-elsewhere differences, and #1619's non-error scope
  conflict, stay open; this entry does not settle them.

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
| Spacing | mismatch, all four states: the row sits flush on the oldest bubble; the frames leave 16 px |
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

### Reachable states (#1539)

Six frames in section *Reachable states · #1539 · 2026-10-02* (`696:4676`), captured and compared against `main`
at `59eecec1` (includes #1848, which fixed the attachment tile colours/weights, the empty-thread text size and
the last-row-to-band gap) with `ThreadDesignCaptureTest#queuedAndToolRowFramesAt412By892`,
`#attachmentAndEmptyFramesAt412By892` and `#dismissalNoticeFrameAt412By892` on the full `pixel8Api35` image,
`requireRealSystemBars=true`: 412x892 px, density/font scale 1.0, real 24 px bars, hardware accelerated. Every
frame was re-exported with `get_screenshot` on 2026-10-06; the capture test rounds on the fake graph's demo
seed, so the chat history visible above each row is not compared, only the state under audit. `1619-pixel8-results.xml`
records 3 executed, 0 failures. The reader's "Couldn't open file" arm of `696:5101` is not recaptured here: it
already has its own verdict above, taken by #1747. Earlier pipeline rounds on this ticket (PR #1627, since
closed unmerged) captured these same six frames against an older `main`; this pass replaces that evidence with
fresh captures against current `main`, per the rework instruction to judge against current `main` rather than
older captures.

### Queued message row — `696:4677`

- **Owning ticket:** #1622 (open; the drop-button-disappears finding no longer reproduces, see below)
- **Capture:** `queued-messages.png`, plus `queued-long.png` (a third, longer row added as evidence) (412x892, 1.0)
- **Side-by-side:** `queued-messages-side-by-side.png`, `queued-long-side-by-side.png`
- **Overlay:** `queued-messages-overlay.png`, `queued-long-overlay.png`
- **Verdict:** mismatch (spacing only)
- `QueuedMessageRow`'s bubble `Surface` carries `weight(1f, fill = false)` since #1642 (`034a4958`), so the
  drop target survives on every wrapping row, including the long third row `queued-long.png` adds: all three
  rows' X icons measure at x 362–373 (centre ≈367.5), matching the frame's centred-at-368 spec within a
  device pixel. The remaining difference is the gap between two queued rows: a pixel scan at a fixed column
  finds the first bubble ends at y=684 in both the app and the frame (same bottom anchor), but the second
  bubble starts at y=692 in the app against y=700 in the frame — an 8 px gap against the frame's 16 px, from
  `QueuedRowVerticalPadding`.

| Aspect | Verdict |
|---|---|
| Geometry | match: drop button fully inside the 20 px gutter on every row, including the long wrapping one; bubble bottom-anchors identically to the frame |
| Padding | match: bubble horizontal/vertical padding and the two-line row's wrap break are identical in the app and the frame |
| Spacing | mismatch: 8 px between queued rows against the frame's 16 px |
| Typography | match |
| Colour | match |
| Borders | match |
| Radii | match |
| Icon paths | match: waiting glyph, close X |
| Component state | match: no Send-now button drawn. #1642's Send-now action is gated on the session reporting `capabilities.mid_turn_input: true`, absent on the capture's seeded session; its absence here is the approved, decided behaviour this README's "Approved additions without a separate frame" already records, not a mismatch |

- **Routed:** #1622 (spacing only; a comment on the issue records that the button fix already landed)

### Sub-agent tool rows — `696:4795`

- **Owning ticket:** #1623 (closed, "change done in figma"); #1626 (open, frame-side questions)
- **Capture:** `tool-rows-nested.png` (412x892, 1.0)
- **Side-by-side:** `tool-rows-nested-side-by-side.png`
- **Overlay:** `tool-rows-nested-overlay.png`
- **Verdict:** match (row spacing); two aspects remain routed to #1626
- #1623 closed on 2026-10-03 as a Figma-side change, but a same-day follow-up found the exported frame still
  drew the rows 12 px apart. The frame exported fresh today draws every row joined with no gap, the same
  `joinsNextToolRow` style the app draws (#1577): #1623's frame edit landed, just later than its own closure
  comment implied.

| Aspect | Verdict |
|---|---|
| Geometry | match: rows joined with shared/overlapping borders in both the app and the frame; row heights, the 16 px indent per nesting level and the 20 px right gutter match |
| Padding | match |
| Spacing | match: 0 px between rows in both, across the nesting-depth change from the Read failure to the second Agent call |
| Typography | mismatch by design, routed #1626: the failed Read row's path keeps four segments (`shortenToolPath`); the frame shortens it to two |
| Colour | match: statuses, the Grep result count, the elapsed reading |
| Borders | match |
| Radii | match |
| Icon paths | match: running spinner, done check, failed outline |
| Component state | mismatch by design, routed #1626: the frame shows Stop while the composer holds text; the app's rule shows Send with text (`ThreadInputBar`, #643). The status band's own wording ("Running Agent…") follows the app's strings per the existing `RunningTool` band-arm decision below ("No separate frame"), which is not a new mismatch |

- **Routed:** #1626 (both remaining differences already tracked there; no new ticket needed)

### Message attachment states — `696:4913`

- **Owning ticket:** #1624 (closed, fixed by #1848); #1850 (open, residual spacing)
- **Capture:** `attachment-states.png` (412x892, 1.0)
- **Side-by-side:** `attachment-states-side-by-side.png`
- **Overlay:** `attachment-states-overlay.png`
- **Verdict:** mismatch (small residual)
- #1848 fixed the tile colour, weight and name/state dimming #1624 found. A fresh pixel scan of the file-tile
  column finds the row pitch for the tile with no Retry control matches the frame exactly (58 px in both, log
  tile to PDF tile); the pitch from the PDF tile (which carries Retry) to the YAML tile is 106 px in the app
  against the frame's 98 px, an 8 px excess from `AttachmentFileRow`'s `TextButton`'s default touch-target
  height.

| Aspect | Verdict |
|---|---|
| Geometry | match: placeholder/spinner and bubble outer size within 2 px |
| Padding | match: the name-to-state gap is 5 px in both, for every tile |
| Spacing | mismatch: the Retry-bearing tile pushes the following tile 8 px further than the frame |
| Typography | match: type label regular weight |
| Colour | match: tile glyph/outline/type label in the primary colour, file name at full on-secondary-container strength, only the state line dimmed |
| Borders | match |
| Radii | match |
| Icon paths | match |
| Component state | match: Loading, Couldn't load file with Retry, File not found |

- **Routed:** #1850

### Empty thread — `696:4989`

- **Owning ticket:** #1625 (closed, fixed by #1848)
- **Capture:** `empty-thread.png` (412x892, 1.0)
- **Side-by-side:** `empty-thread-side-by-side.png`
- **Overlay:** `empty-thread-overlay.png`
- **Verdict:** match
- #1848's switch from `bodyMedium` to `bodySmall` lands exactly on the frame: the side-by-side shows no
  visible difference and the overlay shows no ghosting on "Send a message to get started".

| Aspect | Verdict |
|---|---|
| Geometry | match |
| Padding | match |
| Spacing | match |
| Typography | match: body-small |
| Colour | match: on-surface-variant |
| Borders | match (none) |
| Radii | match (none) |
| Icon paths | match (none) |
| Component state | match |

- **Routed:** none

### Prompt resolved elsewhere (dismissal notice) — `696:5065`

- **Owning ticket:** none before this audit; #1851 (new)
- **Capture:** `prompt-resolved-elsewhere.png` (412x892, 1.0)
- **Side-by-side:** `prompt-resolved-elsewhere-side-by-side.png`
- **Overlay:** `prompt-resolved-elsewhere-overlay.png`
- **Verdict:** mismatch — Juhana's call: the app's bottom snackbar is a gap to own, not a match, against the
  frame's top-overlay pill
- `ThreadScreen`'s `ModalUiState.Dismissed` branch still calls `snackbarHostState.showSnackbar(reason)`. #1604
  moved every *error* notice on this screen to the top-overlay stack but explicitly excluded this non-error
  dismissal from its scope, and closed before any successor picked it up; #1619's own routing record flagged
  the conflict as unresolved. The frame draws "Resolved on another device" as a Default pill, right-aligned in
  the top overlay under the header.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: bottom-anchored, near-full-width snackbar against a right-aligned top-overlay pill |
| Padding | mismatch: snackbar's own padding against the pill's 8/4 px |
| Spacing | mismatch: no relation to the overlay stack's 12 px gap, since it is not in that stack |
| Typography | match: same string, `dismissReasonText("remote")` → "Resolved on another device" |
| Colour | mismatch: default snackbar surface against primary-container/on-primary-container |
| Borders | match (none) |
| Radii | mismatch: snackbar's own radius against the pill's 6 px |
| Icon paths | match (none, no X on either) |
| Component state | match: inert, auto-dismisses |

- **Routed:** #1851

### Reader notices — `696:5101` (Saved arm)

- **Owning ticket:** #1747 (closed; see "Reader error — `696:5101`" above for the error arm, which matches);
  #1851 (new, this arm)
- **Capture:** none; not recaptured by this pass
- **Verdict:** unverified for Save failed, mismatch for Saved (Juhana's call)
- The error arm ("Couldn't open file") already has a match verdict above, taken by #1747. Save failed and
  Saved settle only after the system's create-document picker returns; the harness cannot drive that picker
  without a new test dependency, the same limitation the original #1619 pipeline round recorded. Save failed
  shares the error pill and placement already verified; Saved is a Default (non-error) pill the app does not
  yet draw, the same gap as the dismissal notice above.

- **Routed:** #1851 (Saved); Save failed stays unverified, no new ticket — it shares #1747's already-verified
  error treatment

### No separate frame

Decided on #1529 (2026-10-02); each state's reference is the node named. Only the Stop variant has a later capture, recorded above for #1606.

| State | Reference |
|---|---|
| `Resetting`, `ApiRetry`, `Compacting`, `Working`, `RunningTool` band arms | The band's "Thinking…" reading in `16:8` and Input area `134:5013`: the snowflake, then one body-small line in the primary colour. The app's strings are the reference for the words |
| `Stalled` band arm | The same line in the error colour, the "Cxt high" label's colour |
| `StoppedTurn` row | Thread notification, No details state, `620:1574`, with the stopped text; shown in context in `685:3992` above |
| `CompactionBoundary` row | The rule row of the session delimiter frame `675:3682`, with the compaction label and no explanation line |
| Send button's Stop variant | Message input button component `Action=Stop`, `114:3549`, on the Desktop page. #1606 corrected the comment and glyph; its fresh capture is compared above |
| Top overlay Error pills: failed MCP server, and a usage-limit reading that is not a warning | Pill `347:6619`, Error state, X off, in the right-aligned top stack `568:3139` draws for the pairing error. Each pill's text is the app's string and the whole pill is the tap target. #1519 owns the usage-limit wording |

## Status bar

The frames draw no status bar; the reference is light bar icons on the dark canvas (#1510). The 412x892 captures
were taken on `main` at `3cebb1ad`, before #1510's fix merged at `8fa9df64`, on an emulator in light mode. In
`thread.png`, `tool-row.png`, `notification-text.png`, `refusal-switch-back.png`, `session-notice.png`,
`connecting.png`, `reconnecting.png`, `offline.png`, `task-count-pill.png`, `session-delimiter.png`,
`actions-menu.png`, `keyboard.png` and `markdown-reader.png` the clock and icons are dark
(no pixel in the top 24 px brighter than 19 of 255). That is the #1510 defect, not counted in their Colour verdicts.
The replacement #1666 overflow captures have real light system-bar icons, as recorded in their sidecars. The sheet captures (`run-configuration.png`, `tasks-*.png`) draw light icons over the sheet’s scrim. #1510 is closed
and merged; the compact captures, retaken after it, all draw light status-bar icons.

## Removed controls

Checked on every capture: no removed footer selector (the footer is Actions, Cxt, paperclip and the Status
opener), no "Default" option in Run configuration at 412x892 or 320x700 (asserted in `openRunConfiguration` with a
case-insensitive substring match), no thread workspace chip, and no clickable workspace row in the overflow or
Actions menu at either size (asserted in `noWorkspaceAction`). The seeded thread does still show a "Workspace
changed to ~/Workspace/pyrycode-mobile" session delimiter, which is plain text, not an action; #1498 removes it.

## Compact, keyboard and menus

Except the #1666 replacement overflow capture above, compact captures are 320x700 at font scale 1.5, taken at `main` `85466a15` with real 24 px status and navigation
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
| Overflow menu, compact | `compact-overflow-menu.png` | #1666 replacement: shared Actions rows below the live header button, including Background tasks after Channel info; bounded scrolling, no workspace action. Keyboard is closed |
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

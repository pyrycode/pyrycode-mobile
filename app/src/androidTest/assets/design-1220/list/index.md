# List-side audit (#1431)

- **App commit:** `main` at `ddebd393`, plus the test-only `ListDesignCaptureTest` on `feature/1431`. No file
  under `app/src/main/` differs from the first run's `4c755aa6`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-02.
  Channel Info `20:48` is a 412x596 sheet; its comparison pads the export onto a 412x892 canvas at the bottom.
  #1488 retired `20:48`: the capture now names the full-height `668:5355`, below.
- **Capture:** `ListDesignCaptureTest` (two methods) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0 and font scale 1.0, and 320x700 at 150 % font scale
  (`-compact`), fixed dark theme, real 24 px bars. Each surface starts from a fresh `MainActivity` launch on the
  paired demo host. Archive's capture archives three demo channels and restores them afterwards; Edit host's
  installs a store that answers `loadById` for the demo host, through Koin in the capture class. Archive's tabs
  are tapped through the device's input (`input tap`), so the capture shows the state a finger leaves. This is
  the third run; the second run's captures differ from these only inside the status bar and in Archive's tapped
  tab fill (see Archive).
- **Measuring:** positions are raw image y of the first and last bright text rows (luminance over 150); the
  shell's 24 px move inside the system bars is removed before comparing, per the README. A table or row that
  quotes raw y says so.
- **Status bar:** not judged per surface. Within this run its icons are light on `channel-list*.png`,
  `archive.png`, `settings*.png`, `channel-info*.png` and every modal capture, and dark on `archive-compact.png`,
  `archive-discussions*.png` and `thread-menu*.png` (no pixel above luminance 150 in the top 24 px). The
  appearance changes with the capture, not with the surface, so it is no Colour verdict here. The thread's dark
  icons are handed to the thread audit, #1432, with the Archive recurrence as a lead.
- **Result:** the third run had 2 executed, 0 failures, 0 errors, 0 skipped. `list-results.xml` now holds #1504's
  run (see List states), with the same counts.

No audit declares app-wide parity; #1434 owns that verdict.

### Channel List — `15:8`

- **Owning ticket:** #737 (bar), #738 (tree)
- **Capture:** `channel-list.png` (412x892, 1.0), `channel-list-compact.png` (320x700, 1.5) · **Side-by-side:** `channel-list-side-by-side.png` · **Overlay:** `channel-list-overlay.png`
- Host separation: one host row (Demo) with its own Channels and Chats folders. No workspace grouping and no
  workspace label appear; the walk asserts that no text and no content description on the list or in Settings
  contains "workspace".
- Host-to-host spacing is unverified: the demo pairs one host, and #1187 sets 16 dp between host containers
  where the frame stacks four. Routed with #1486.
- Three row states the frame draws are unverified: the selected row (lighter rounded fill with the pen), the
  second row's darker fill, and collapsed folders and hosts. The capture reaches none of them, although source
  reaches both: a row is selected after tapping it and pressing Back, and a folder or host collapses on tap.
  Routed with #1486.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: with the 24 px bar removed, the whole screen sits 4 px above the frame, top bar included (gear and archive glyphs y 28–51 against 32–55, host text 101–112 against 105–118), so the offset is the top bar's top gap, not the rows. The second run's "20 px below" did not remove the bar |
| Padding | match: 20 px tree gutter, toolbar rule inset; the rule moves up 4 px with the bar |
| Spacing | match: 28 px row pitch |
| Typography | match: host, folder and row styles |
| Colour | mismatch: rows have no green, pink or blue status dots (`ConversationStatusDot` fills WaitingForAnswer, Running and Unread; the demo rows reach none of them, so the state exists in code and was not reached); the frame's blue radial glow behind the tree (RGB 19,74,116 at 200,200, 8,33,52 at 300,350, flat 11,14,17 by y≈700) is missing, the app canvas is a flat 11,14,17 while Archive in the same build draws its glow. `docs/knowledge/features/app-preferences.md` put the glow outside the root palette contract; that earlier allowance does not excuse the deviation |
| Borders | match: toolbar rule, 4 px high with the bar as in Geometry |
| Radii | unverified: no row is selected in the capture, so the frame's rounded selected-row fill and the second row's darker fill were not compared (routed with #1486) |
| Icon paths | mismatch: every row and the host row draw an edit pen; the frame shows one only on the selected row |
| Component state | match for expanded folders and hosts only; the frame's selected row, darker-filled row and collapsed folders and hosts are unverified (routed with #1486). The frame's Apps folder is not a requirement: #1187 records "Apps is deferred by the user; sample app rows are not requirements", and `ConversationTreeSection` holds only Host, Channels and Chats |

- `15:8` draws the expanded "Pyry" host with a right chevron and the expanded "MB Game dev" host with a down
  chevron. The frame is inconsistent with itself; the app draws a down chevron on every expanded host, which
  stands. Do not change the app toward the right chevron.

- **Compact:** no clipping or overlap; every row, pen and plus stays reachable.
- **Menu-open:** the list has no row or folder menu at this commit (`ChannelListScreen` and `ConversationTreeRows` hold no `DropdownMenu`; rows open editors through pens). The only menu on a list-side path is the thread overflow menu (`thread-menu.png`, `thread-menu-compact.png`), which opens fully inside the window with every entry reachable.
- **Routed:** #1486

### Create chat failure — Error-pill reuse `685:4337`

- **Owning ticket:** [#1748](https://github.com/pyrycode/pyrycode-mobile/issues/1748).
- **Reference:** [thread notice `685:4337`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337),
  reusing [Error component `347:6619`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619)
  with X hidden and list placement authorised by #1604. No new list frame exists;
  only notice styling and measured-header-relative placement are judged.
- **Capture:** [create-chat-failed.png](create-chat-failed.png),
  [metadata](create-chat-failed.txt), [Figma export](figma-685-4337.png),
  [side-by-side](create-chat-failed-side-by-side.png), [overlay](create-chat-failed-overlay.png).
  `ListDesignCaptureTest.failedCreateChatNoticeAt412By892` drives the real Chats plus
  through a failing repository in `MainActivity`. API 35 metadata records 412×892px,
  density/font scale 1, fixed dark theme, hardware rendering, `syntheticBars=false`,
  real 24px status/navigation insets and zero IME insets; real bars were required.
- **Execution:** retained builder [API 35 XML](create-chat-failed-api35-results.xml),
  2026-10-04 14:28:27: 1 executed, 0 failed/errors/skipped, with the named capture method
  present and passed. Repaired-caller [API 33 XML](create-chat-failed-api33-results.xml),
  15:59:14: 5 executed, 0 failed/errors/skipped; the same capture method and all four
  `CreateChatFailureNoticeTest` methods are present and passed. These are focused builder
  runs, not documentation or verifier reruns.
- **Inspection/provenance:** the final verifier freshly inspected Figma frame/component
  screenshots on 2026-10-04 and passed PR #1758 at `1bd8e11c`. The retained API 35 PNG
  predates the toolbar-menu merge and polite-semantics repair: it proves notice appearance,
  not the final toolbar glyph. Fresh API 33 execution and final JVM tests verify the
  repaired caller, header-relative geometry and Settings/Archive menu actions. TalkBack
  audio was not exercised; polite announcement is covered by semantics assertions.

| Aspect | Verdict |
|---|---|
| Geometry / spacing | match: background x=173..392, y=149..171; 20px right gutter, 28px below the measured header, within 20px side gutters; tree layout unchanged |
| Padding | match: reused shared component's 8dp horizontal / 4dp vertical padding |
| Typography | right-aligned body-small label and unchanged failure text; inherited trimmed line box yields a 22px background versus Figma's 24px |
| Colour / radii / shadow | match: errorContainer/error, 6dp corners and overlay shadow through the shared Error pill |
| Component state | match: no X, tap or dismissal action and no error snackbar; repaired caller has polite live-region semantics and accessibility-adjusted Short expiry |

**Verdict:** authorised notice reuse and placement match, with the inherited single-line
height mismatch deferred to [#1757](https://github.com/pyrycode/pyrycode-mobile/issues/1757).
This is not a list-frame or app-wide parity verdict.

### Restore failure — Error-pill reuse `685:4337`

- **Owning ticket:** [#1749](https://github.com/pyrycode/pyrycode-mobile/issues/1749).
- **Reference:** [thread notice `685:4337`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=685-4337)
  and [Error component `347:6619`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6619),
  X hidden, with placement reuse authorised by #1604. Thread contents are not an Archive frame reference.
- **Capture:** [restore-failed.png](restore-failed.png), [metadata](restore-failed.txt),
  [retained Figma export](figma-685-4337.png), [side-by-side](restore-failed-side-by-side.png)
  and [overlay](restore-failed-overlay.png). `ListDesignCaptureTest.failedRestoreNoticeAt412By892`
  taps a restore in real `MainActivity` with a scoped failing Archive ViewModel binding.
  Replacing the list host source alone would not affect Archive's direct demo repository.
- **Viewport:** API 35, 412×892px, density/font scale 1, fixed dark theme, hardware rendering,
  `syntheticBars=false`, real 24px top/bottom bars and zero IME insets. Real bars were required.
- **Execution:** builder's retained [API 35 XML](restore-failed-api35-results.xml),
  timestamp `2026-10-04T18:09:41`: 4 executed, 0 failures/errors/skipped. The named
  `failedRestoreNoticeAt412By892` method and all three `ArchiveAppearanceCaptureTest`
  methods are present and passed. This was a focused builder run, not a documentation rerun.
- **Behavior evidence:** [verifier PASS on PR #1772](https://github.com/pyrycode/pyrycode-mobile/pull/1772#issuecomment-5983544593)
  at `e83886ef` reports fresh full unit XML with 4067 executed, 0 failures/errors/skipped,
  including all seven passing `ArchiveRestoreNoticeTest` methods. These cover placement,
  unchanged geometry and usable header controls, inert/polite semantics, success/error
  distinction, expiry, accessibility flags, sequential notices and screen-exit cancellation.
  The capture holds the Compose clock for hardware pixels; it makes no timing claim.
- **Provenance limit:** the verifier inspected the retained export, images, metadata and XML;
  a fresh remote Figma screenshot and the original Gradle console run were not independently
  observed. The architect recorded frame/component inspection on 2026-10-04.

| Aspect | Verdict |
|---|---|
| Geometry / spacing | match: container x=117..392, y=188..210, 20px right gutter, 28px below the complete header ending at y=160; host label, tabs and rows retain their layout |
| Padding | match: shared 8dp horizontal / 4dp vertical padding |
| Typography | right-aligned body-small unchanged local text; inherited 22px single-line background versus reference's 24px |
| Colour / radii / shadow | match: shared errorContainer/error colours, 6dp corners and overlay shadow |
| Component state | match: no X, tap or dismissal action, no failure snackbar; success retains its existing snackbar |

**Verdict:** authorised Error-pill reuse and header-relative placement match. The inherited
height mismatch is routed to [#1757](https://github.com/pyrycode/pyrycode-mobile/issues/1757).
This judges notice reuse, not Archive-frame or app-wide parity.

### Archive — `18:2`

- **Owning ticket:** #1265; re-audited by #1487
- **Capture:** `archive.png`, `archive-compact.png` (Channels tab, three archived demo channels); Discussions tab `archive-discussions.png`, `archive-discussions-compact.png` · **Side-by-side:** `archive-side-by-side.png` · **Overlay:** `archive-overlay.png`
- These five captures and the comparison come from #1487's run on `feature/1487` (the #1487 changes on `main` at
  `b2a27867`): `ListDesignCaptureTest` on `pixel8Api35` with `requireRealSystemBars=true`, 2 executed, 0 failed. They
  replace the third run's Archive captures; every other surface in this file keeps the third run's evidence.
  `figma-18-2.png` is a fresh export of the updated frame, which draws the host label (2026-10-02 design decision).
- Archive opens on Channels (`ArchivedDiscussionsViewModel` seeds `ArchiveTab.Channels`), so the `archive` capture
  needs no tap. The Discussions capture follows a device `input tap`; the walk's `tap` waits for the tab to report
  selected and then for Compose idle before capturing.

| Aspect | Verdict |
|---|---|
| Geometry | match: with the 24 px bar removed, host label rows 71–79 against 72–81, tab labels 105–118 against 106–119, indicator at 134 in both, first row title 157–171 in both. The host label ("Demo" here, "Pyry" in the frame) is in the frame since the 2026-10-02 design decision |
| Padding | match: 16 px gutters, restore icons at x 365–384 |
| Spacing | match: rows repeat every 66 px (titles at 157, 223, 289 after the bar, as in the frame); the second row's subtitle starts at 247 in both |
| Typography | match: title, host label, tab labels, row title and subtitle |
| Colour | match: the selected tab has no fill. In `archive-discussions.png`, taken after the device tap, the tapped tab reads 13,45,68 and 11,30,43 at x 215 and 400 (y 140), the same as the untapped tab in `archive.png`. The earlier fill was the default press ripple caught mid-fade: a probe on the same image sampled it at 27,45,59 200 ms after the tap, 15,34,48 at 400 ms and the base colour from 800 ms on, with touch mode on and no node focused |
| Borders | match: tab indicator under the selected tab and the divider |
| Radii | match (none) |
| Icon paths | match: back arrow, restore icon |
| Component state | match: opens with Channels selected, as the frame does |

- **Compact:** match: at 320x700 and 150 % font scale "Channels (3)" and "Discussions (1)" each stay on one line
  inside their tabs, above the indicator, with their counts visible. The labels step their size down only when a tab
  is too narrow. Rows and restore icons stay reachable.
- **Routed:** none; #1487 closed the four differences.

### Channel Info Sheet — `20:48`

- **Superseded by #1488:** the sheet now matches the full-height frames `668:5355` (Top) and `668:5460`
  (Scrolled to Actions), and `ListDesignCaptureTest` names `668:5355`. The images and verdicts in this section
  are the #1431 audit against `20:48`. Current evidence: `app/src/androidTest/assets/channel-info-1488/`.
- **Owning ticket:** #1266
- **Capture:** `channel-info.png`, `channel-info-compact.png` · **Side-by-side:** `channel-info-side-by-side.png` · **Overlay:** `channel-info-overlay.png`
- Reached from the thread's overflow menu, Channel info.
- The first About row reads "Folder" where the frame reads "Workspace". That is deliberate: workspaces are
  retired, and the row is not a mismatch.
- The 412x892 capture stops at the Actions header. The Rename, Archive and Delete buttons and the "Channel ID"
  footer sit below the fold and were not compared, so every verdict below covers the sheet down to the Actions
  header only. The rest is unverified and routed with #1488.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the sheet fills the window; the frame is a 596 px sheet |
| Padding | match: 16 px content gutters, section label inset |
| Spacing | mismatch: About rows repeat every 35 px against the frame's 40 (label tops 155, 190, 226, 260, 295 against 129, 169, 210, 249, 289 in the 596 px export); the About label to the first row is 28 px against 32. The second run recorded a match |
| Typography | match down to the Actions header: title, section labels, monospace path; the Actions buttons and the Channel ID footer are unverified |
| Colour | match down to the Actions header: sheet surface, secondary values; the Actions buttons and the Channel ID footer are unverified |
| Borders | match (none) |
| Radii | match down to the Actions header: top corners, drag handle; the Actions buttons are unverified |
| Icon paths | match: close icon |
| Component state | mismatch: Session, System prompt and MCP servers sections sit before Actions, which falls below the fold; Memory plugins reads "Status unknown" where the frame shows "None" with a "+ Install" action (`ChannelInfoSheet` has the None and Install state; the demo session reports no plugin state, so it was not reached); the frame's Change workspace action is retired and must not be restored |

- **Compact:** no clipping; long labels wrap and values stay right-aligned; Actions needs scrolling.
- **Routed:** #1488

### Settings / Notifications modal / Dark — `17:2`

- **Owning ticket:** #1239
- **Capture:** `settings.png`, `settings-compact.png` · **Side-by-side:** `settings-side-by-side.png` · **Overlay:** `settings-overlay.png`
- Notifications only, with the "Notification sound — Default" row.

| Text | Figma y | App y | After the 24 px bar |
|---|---|---|---|
| Title | 30 | 54 | 0 |
| Notifications | 90 | 111 | -3 |
| Push notifications | 134 | 155 | -3 |
| Push switch | 137 | 157 | -4 |
| Notification sound | 218 | 236 | -6 |
| Chevron | 228 | 245 | -7 |
| Default | 242 | 256 | -10 |

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars: the shell spans 24–868; horizontal edges and the header match |
| Padding | match: 28 px gutters |
| Spacing | mismatch: per the table, the section label sits 3 px closer to the header, the Notification sound row 6 px higher and its "Default" line 10 px higher; title to supporting line in that row is 20 px against 24 (the Push row keeps 24); Done sits 29 px above the shell's bottom edge against 25 |
| Typography | match: glyph heights of title, section label and row text |
| Colour | match: modal surface, switch, Done |
| Borders | match: header rule |
| Radii | match: modal, close button, Done |
| Icon paths | match: close, chevron |
| Component state | match: notifications on |

- **Compact:** no clipping; the push row wraps to four lines and the switch and Done stay reachable.
- **Routed:** #1503. The first run recorded Spacing as a match; the side-by-side contradicts it.

### Modal › Edit host — `533:2369`

- **Owning ticket:** #1277
- **Capture:** `edit-host.png`, `edit-host-compact.png`; keyboard open on the name field `edit-host-keyboard.png`, `edit-host-keyboard-compact.png` (`imePx` bottom 240) · **Side-by-side:** `edit-host-side-by-side.png`, `edit-host-keyboard-side-by-side.png` · **Overlay:** `edit-host-overlay.png`, `edit-host-keyboard-overlay.png`
- Opened from the host row's pen ("Edit host Demo"). The demo values ("demo", "wss://demo.invalid", "Demo")
  are shorter than the frame's, so value truncation is judged on the compact capture.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch, routed to #1588: the centred field block still sits 3 px high (Server identity glyph top y 346 against 349), down from 7–10 px. The cause is no longer the header (resolved; see Spacing) but `MobileModal`'s content slot, which starts 21 px below the header rule against the frame's 25; the rule-to-footer midpoint is 446 in both. The block's own internal geometry is within 1 px (identity glyph to field top 87.5 against 88, field top to Unpair outline 71.5 against 72). Cancel and OK keep the frame's 25 px from the shell's bottom edge |
| Padding | match: 28 px gutters, field text inset 16 px, Unpair host at x 28–155 |
| Spacing | match: the header (title 30 px below the shell's top, rule at 64) now matches both `17:2` and the frame, so the #1431 header mismatch is resolved and out of scope. "Host name:" to the field is 14.5 px against 15 px, within 1 px (#1489: `TextMotion.Animated` plus the frame's untrimmed 20 px line box, where the theme's styles carry no `lineHeightStyle` and Compose had trimmed the label to ≈16 px). Identity rows keep the 32 px pitch |
| Typography | match: "Server identity:" ink x 29–122 against 29–122 and "Relay address:" 29–119 against 29–119, both exact; value start x 137/134 against 136/133, within 1 px (#1489: density-1.0 hinting rounds every glyph advance to a whole pixel, adding 4 px over 16 glyphs — not device Roboto metrics as the code comment previously said; `TextMotion.Animated` disables the hinting) |
| Colour | match: shell surface, field fill, outlined Unpair host and Cancel, filled OK, close |
| Borders | match: header rule, outlines |
| Radii | match: shell, field, buttons |
| Icon paths | match: close |
| Component state | match: name filled, OK enabled |

- **Compact:** no clipping or overlap; the identity labels wrap to two lines (weighted 2:5 against the value's
  3:5, widened from 1/3 after #1489's unhinted label no longer fit a third of the row) and the relay value
  ellipsizes; every control stays on screen. `ListDesignCaptureTest.assertIdentityLabelsWrapOnlyBetweenWords`
  checks on the device that the break falls only between words — Robolectric's font metrics pass this
  unhinted-width case even when the device breaks mid-word, so the check cannot move to a shared test.
- **Keyboard-open:** at 412x892 the field, Unpair host, Cancel and OK sit above the keyboard. At 320x700 and
  150 % the field, Cancel and OK stay above it and Unpair host scrolls under the action bar; the walk scrolls to
  it and asserts it is displayed, so it stays reachable. No overlap.
- **Routed:** #1489 closed the label width, label-to-field gap and Unpair action height mismatches and the
  header disagreement (resolved upstream in Figma). The residual 3 px block offset is routed to #1588.

### Modal › Edit host unpair confirmation — `671:5620`

- **Owning ticket:** #1489 (frame assigned; capture was previously untagged, see Gaps history below)
- **Capture:** `edit-host-unpair.png`, `edit-host-unpair-compact.png` · **Side-by-side:**
  `edit-host-unpair-side-by-side.png` · **Overlay:** `edit-host-unpair-overlay.png`
- Opened from Edit host's outlined "Unpair host" button. Titled "Unpair host?".

| Aspect | Verdict |
|---|---|
| Geometry | mismatch, routed to #1588: the message sits 4 px high (glyph top y 427 against 431), the confirmation's share of the same `MobileModal` slot offset as Edit host above |
| Copy | match: `edit_host_unpair_confirm_body` now reads "%1$s will be removed from this phone: its pairing and its connection. Pairing it again needs its QR code.", matching `671:5620` word for word with the host name substituted for "Pyrybox" and no "workspace" anywhere in the string |
| Typography | match: body-medium message on `onPrimaryContainer`, unhinted via the same `TextMotion.Animated` line box as the identity values |
| Colour | match: shell surface, outlined Cancel, filled OK |
| Borders, Radii, Icon paths | match: same shell as Edit host |
| Component state | match: OK enabled |

- **Compact:** no clipping or overlap.

## List states — `670:5299` (#1504)

- **Run:** `main` at `3f3dd500` plus the test-only `ListDesignCaptureTest` on `feature/1504`, same device and
  arguments as above, 2026-10-03: `list-results.xml`, 2 executed, 0 failures, 0 errors, 0 skipped. Only this
  section's captures and the retagged `edit-channel*` are committed from that run; every section above keeps its
  own run's evidence.
- **Figma:** the section List states · 2026-10-02 `670:5299`, exported with `get_screenshot` on 2026-10-03 at
  412x892 (`figma-<node>.png`).
- **Measuring:** modal rows quote raw image y, as #1588 does: a modal's shell spans the window inside the bars,
  so its header and footer sit 24 px from the frame's and the rule-to-footer midpoint (446) is shared. Full-screen
  surfaces quote y with the 24 px bar removed. Data differs from the frames on purpose (the demo host is "Demo",
  its channels "Joi Pilates", "Personal" and "Pyrycode Mobile", its chats unnamed); text content and text widths
  are not judged. A dialog-window capture's sidecar `imePx` reads the activity window's insets, not the dialog's, so
  dialog sidecars do not always report `bottom=0`. Unsettled capture timing can produce image/inset
  disagreement, as in the old `save-as-channel.txt` capture. Establish keyboard state from the focused
  dialog window before and after capture; Activity insets alone cannot establish it.
- **Inputs:** Archive's empty Discussions tab unarchives the demo's archived discussion around its capture. The
  host rows swap the harness's demo host for three test hosts in the capture class (`hostStates`): "Pyry" on the
  demo rows with `RelayLinkStatus.Offline`, and row-less "MB Second brain" (`PairingRejected`) and "MB Game dev"
  (`UpdateRequired(null)`); the walk then collapses the five folders the frame draws collapsed. No file under
  `app/src/main/` changed.

### Create channel — `671:5558`

- **Owning ticket:** #958
- **Capture:** `create-channel.png`, `create-channel-compact.png` · **Side-by-side:** `create-channel-side-by-side.png` · **Overlay:** `create-channel-overlay.png`
- Opened from the Channels section's plus ("New channel on Demo").

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the field block sits high, "Channel name:" glyph top 333 against 335 and the prompt field top 443 against 450; 3 px of it is the shell offset #1588 owns (name field top 355 against 358) |
| Padding | match: 28 px gutters, field text inset 16 px |
| Spacing | mismatch: name field bottom to prompt field top 36 px against 39; label to its field within 1 px; Cancel and OK 25 px above the shell's bottom edge in both |
| Typography | match: title, field labels, buttons |
| Colour | match: shell, field fills (0,38,66), outlined Cancel, disabled OK |
| Borders | match: header rule, Cancel outline |
| Radii | match: shell, fields, buttons |
| Icon paths | match: close |
| Component state | match: both fields empty, OK disabled until a name is typed. The name field holds focus with a caret and the keyboard closed; the frame draws no caret |

- **Compact:** no clipping; both fields, Cancel and OK on screen.
- **Routed:** #1588 (shell offset), #1651 (field gap)

### Edit channel — `671:5415`

- **Owning tickets:** #667; settled capture #1862.
- **Fresh evidence:** [default capture](1862-edit-channel.png), [sidecar](1862-edit-channel.txt),
  [compact capture](1862-edit-channel-compact.png), [compact sidecar](1862-edit-channel-compact.txt),
  [Figma export](1862-figma-edit-channel.png), [side-by-side](1862-edit-channel-side-by-side.png),
  [overlay](1862-edit-channel-overlay.png), [inset-aligned footer comparison](1862-edit-channel-footer-side-by-side.png)
  and [footer overlay](1862-edit-channel-footer-overlay.png). These supersede this form's #1504 evidence.
- **Provenance:** captured 2026-10-08 on `feature/1862`, base `f702aa78`, plan `355a98ff` plus
  sequencing changes; captured test source blob `fa7e2845e6f203181c8c6cba15ab50b4f1df3e4e`.
  Later source changes only reorder imports/wrap assertions. Full configured `pixel8Api35`, API 35
  `google_apis_playstore`, hardware rendering, fixed dark, density/font scale 1, 412×892 px,
  `syntheticBars=false`, real 24 px status/navigation insets, `requireRealSystemBars=true`.
  [Command and measurements](1862-evidence.txt) retain capture times and the exact focused `--rerun`
  command selecting both list walks. [Fresh XML](1862-results.xml), `2026-10-08T07:13:14` UTC:
  **2 executed/passed, 0 failed/errors/skipped**; `ListDesignCaptureTest.listFramesAt412By892`
  and `listFramesAt320By700LargeText` are present and passed.
- Reached through the channel thread’s More actions → Edit. The walk identifies the modal's own label/title and focused dialog root,
  observes visible IME with a positive dialog inset, then sends physical Back. The same form/window
  and footer remain; hidden IME and zero dialog inset are checked before and after hardware capture.
  Production opening focus behavior is unchanged.

| Aspect | Verdict |
|---|---|
| Field gap | pass: exact fill bands y=295..347 and 387..499; name-well-bottom to prompt-well-top 40 dp in app and frame (12 dp between complete blocks plus 20 dp prompt label and 8 dp label gap), within 2 dp |
| Footer surfaces | pass: Cancel 91×40 dp and OK 63×40 dp versus frame 92×40 and 62×40; widths differ by 1 dp, heights exact, within 2 dp; compare visible surfaces separately from 48 dp touch targets |
| Footer spacing | pass: 20 dp horizontal gap in both; app surfaces end at y=844, so 892 − 24 navigation inset − 844 = 24 dp bottom clearance, matching frame 892 − 868 = 24 dp |
| Typography | exact frame roles: titleLarge 22/28/400/0; emphasized labelLarge 14/20/600/0.1; bodyMedium 14/20/400/0.25; emphasized bodyLarge 16/24/500/0.5 (size/line height/weight/tracking) |
| Colour | exact frame tokens: onPrimaryFixed shell #001D34, onPrimaryContainer labels #CFE4FF, onPrimary wells #003355 at 41% (composite #002642), onBackground field text #E0E2E8, primary/onPrimary footer #9DCBFC/#003355 |
| Component state | match: keyboard closed with the same focused name field; fixture text and cursor/handle are not judged |

- **Insets:** the real status bar shifts the header down 24 px; navigation shifts the footer up 24 px.
  The centered field midpoint is preserved: do not subtract the status inset from field coordinates.
  Full comparisons retain raw bars; footer crops translate the frame by the navigation inset without rescaling.
- **Compact:** Archive channel remains reachable by scrolling while the IME is open; after Back, the form and both footer actions remain reachable.
- **Verdict:** only Default `671:5415` and the requested field/footer measurements are judged.
  All requested sizes/spacing pass within 2 dp; colour/type roles match exactly. No visual follow-up required.
  [Verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1952#issuecomment-6055286158)
  independently confirms frames and retained evidence. Routine ATD gate results supplement this full-device
  pixel evidence; they do not replace it.

### Edit chat — `671:5499`

- **Owning ticket:** #827
- Not captured: unreachable from `MainActivity` since #1563. `ChannelListEvent.TreeChatEditTapped`, the only route to
  `ChannelListViewModel.openChatEditor`, is constructed nowhere, and a chat is renamed from its thread's More actions,
  Rename (`671:5664`, below). Listed under Gaps.

### Rename — `671:5664`

- **Owning ticket:** #957
- **Capture:** `rename.png`, `rename-compact.png` · **Side-by-side:** `rename-side-by-side.png` · **Overlay:** `rename-overlay.png`
- Opened from the first chat's thread, More actions, Rename (an unpromoted conversation's menu; a channel's opens
  Edit). The demo chat is unnamed, so the field holds "Untitled discussion".

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: "Name" glyph top 408 against 411 and the field 429–480 against 434–486; 3 px is #1588's shell offset |
| Padding | match: 28 px gutters, value inset 16 px |
| Spacing | mismatch: label glyph top to field top 21 px against 23; footer 25 px above the shell's bottom edge in both |
| Typography | match: title, label, value, buttons |
| Colour | match: shell, field fill, outlined Cancel, disabled Save |
| Borders | match: header rule, Cancel outline |
| Radii | match: shell, field, buttons |
| Icon paths | match: close |
| Component state | mismatch: the prefilled name is selected (highlighted), the frame draws it unselected; Save disabled until the name changes, as the frame's dimmed Save |

- **Compact:** the keyboard opens at 320x700; the field, Cancel and Save stay above it.
- **Routed:** #1588, #1651

### Save as channel — `671:5718`

- **Owning tickets:** #957; settled capture #1862.
- **Fresh evidence:** [default capture](1862-save-as-channel.png), [sidecar](1862-save-as-channel.txt),
  [compact capture](1862-save-as-channel-compact.png), [compact sidecar](1862-save-as-channel-compact.txt),
  [Figma export](1862-figma-save-as-channel.png), [side-by-side](1862-save-as-channel-side-by-side.png),
  [overlay](1862-save-as-channel-overlay.png), [inset-aligned footer comparison](1862-save-as-channel-footer-side-by-side.png)
  and [footer overlay](1862-save-as-channel-footer-overlay.png). These supersede this form's #1504 evidence.
- **Provenance:** captured 2026-10-08 on `feature/1862`, base `f702aa78`, plan `355a98ff` plus
  sequencing changes; captured test source blob `fa7e2845e6f203181c8c6cba15ab50b4f1df3e4e`.
  Later source changes only reorder imports/wrap assertions. Full configured `pixel8Api35`, API 35
  `google_apis_playstore`, hardware rendering, fixed dark, density/font scale 1, 412×892 px,
  `syntheticBars=false`, real 24 px status/navigation insets, `requireRealSystemBars=true`.
  [Command and measurements](1862-evidence.txt) retain capture times and the exact focused `--rerun`
  command selecting both list walks. [Fresh XML](1862-results.xml), `2026-10-08T07:13:14` UTC:
  **2 executed/passed, 0 failed/errors/skipped**; `ListDesignCaptureTest.listFramesAt412By892`
  and `listFramesAt320By700LargeText` are present and passed.
- Reached through the first chat’s More actions → Save as channel; the unnamed chat prefills "New channel". The walk identifies the modal's own label/title and focused dialog root,
  observes visible IME with a positive dialog inset, then sends physical Back. The same form/window
  and footer remain; hidden IME and zero dialog inset are checked before and after hardware capture.
  Production opening focus behavior is unchanged.

| Aspect | Verdict |
|---|---|
| Field gap | pass: exact fill bands y=359..411 and 451..563; name-well-bottom to prompt-well-top 40 dp in app and frame (12 dp between complete blocks plus 20 dp prompt label and 8 dp label gap), within 2 dp |
| Footer surfaces | pass: Cancel 91×40 dp and OK 63×40 dp versus frame 92×40 and 62×40; widths differ by 1 dp, heights exact, within 2 dp; compare visible surfaces separately from 48 dp touch targets |
| Footer spacing | pass: 20 dp horizontal gap in both; app surfaces end at y=844, so 892 − 24 navigation inset − 844 = 24 dp bottom clearance, matching frame 892 − 868 = 24 dp |
| Typography | exact frame roles: titleLarge 22/28/400/0; emphasized labelLarge 14/20/600/0.1; bodyMedium 14/20/400/0.25; emphasized bodyLarge 16/24/500/0.5 (size/line height/weight/tracking) |
| Colour | exact frame tokens: onPrimaryFixed shell #001D34, onPrimaryContainer labels #CFE4FF, onPrimary wells #003355 at 41% (composite #002642), onBackground field text #E0E2E8, primary/onPrimary footer #9DCBFC/#003355 |
| Component state | match: keyboard closed with the same focused name field; fixture text and cursor/handle are not judged |

- **Insets:** the real status bar shifts the header down 24 px; navigation shifts the footer up 24 px.
  The centered field midpoint is preserved: do not subtract the status inset from field coordinates.
  Full comparisons retain raw bars; footer crops translate the frame by the navigation inset without rescaling.
- **Compact:** The form and Cancel/OK remain reachable after Back at 320×700 dp and 1.5× font scale.
- **Verdict:** only Prefilled `671:5718` and the requested field/footer measurements are judged.
  All requested sizes/spacing pass within 2 dp; colour/type roles match exactly. No visual follow-up required.
  [Verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1952#issuecomment-6055286158)
  independently confirms frames and retained evidence. Routine ATD gate results supplement this full-device
  pixel evidence; they do not replace it.

### Delete confirmation — `673:3665`

- **Owning tickets:** #227 (flow), #1848 (close Channel Info), #1861 (geometry).
- **Fresh evidence:** [default capture](1861-delete-confirmation.png), [sidecar](1861-delete-confirmation.txt), [compact capture](1861-delete-confirmation-compact.png), [compact sidecar](1861-delete-confirmation-compact.txt), [Figma export](1861-figma-673-3665.png), [surface comparison](1861-delete-surface-side-by-side.png) and [overlay](1861-delete-surface-overlay.png). These supersede the #1504 Delete measurements only; other modal states retain their own evidence.
- **Provenance:** captured implementation/test revision `5eecf2dc5d95c585481c9facf61da0a12440fa61`, full `pixel8Api35` API 35 `google_apis_playstore` image, density 1, static dark, real 24 px status/navigation bars, `requireRealSystemBars=true`. Default captured 2026-10-07 00:31:29 UTC; compact 00:28:19 UTC. [Fresh XML](1861-delete-results.xml), timestamp 00:32:27 UTC, records 2 executed/passed, 0 failed, 0 errors, 0 skipped: `ListDesignCaptureTest.listFramesAt412By892` and `listFramesAt320By700LargeText`. The builder's `--rerun` selected these two methods; it was not the entire device suite. [Run command, hashes and measurements](1861-delete-evidence.txt) retain full provenance.
- Reached through Channel Info → Delete in `ListDesignCaptureTest.walk`, using the frame name `kitchenclaw refactor`. Complete visual audit covers Default Delete only. The comparison uses the retained Figma export; the verifier did not independently retrieve Figma.

| Aspect | Verdict |
|---|---|
| Geometry | pass: solid surface bounds (48,336)..(364,556), 316×220 dp; removing the real 24 px status inset gives (48,312), exactly the frame; size and position within 2 dp |
| Padding | pass: 24 dp content padding, within 1 dp |
| Spacing | pass: 16 dp title-to-body, 24 dp body-to-actions and 8 dp between actions, native shared assertions within 1 dp; three untrimmed body lines occupy 60 dp |
| Typography | match: headlineSmall 24/32, bodyMedium 14/20, labelLarge 14/20; untrimmed line boxes preserve theme values |
| Colour | match: surfaceContainerHigh #272A2F, onSurface #E0E2E8 title, onSurfaceVariant #C2C7CF body, primary #9DCBFC actions |
| Borders | match (none) |
| Radii | pass: 28 dp extraLarge corners; solid-fill corner profiles differ from Figma by at most 1 px, within 2 dp |
| Icon paths | match (none) |
| Component state | match: Channel Info is closed behind the scrim; fixture thread content is not part of the modal audit |

- **Accessibility and dismissal:** 40 dp visible actions retain at least 48 dp touch targets extending into blank space. The actual 320×700 dp / 1.5× text capture shows a growing 272×400 dp surface with complete body and both readable, reachable actions. Both device walks check Cancel, actual Back, far outside taps and physical taps 12 dp beside both surface edges; dismissal leaves Channel Info closed and the fake conversation undeleted. Shared fake checks confirm single confirm/dismiss callbacks.
- **Repair evidence:** [ATD XML](1861-delete-atd-results.xml), 2026-10-07 00:22:45 UTC: both capture methods executed/passed, 2 executed, 0 failed/errors/skipped. [Verifier PASS](https://github.com/pyrycode/pyrycode-mobile/pull/1863#issuecomment-6028660713) also records the configured UI gate: 192 executed/passed, 0 failed/errors, 1 skipped, including both methods.
- **Routed:** the previous #1651 Delete geometry and sheet-state mismatches are resolved by #1848 and #1861.

### Host rows: disconnected, re-pair required, update required — `672:3493`

- **Owning ticket:** #840, #1336 (disconnected), #842 (re-pair), #1009 (update)
- **Capture:** `host-rows.png`, `host-rows-compact.png` · **Side-by-side:** `host-rows-side-by-side.png` · **Overlay:** `host-rows-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match: with the bar removed every host, folder and row text band starts at the frame's y within 1 px (hosts 105, 285, 385; folders 134, 242, 314, 342) |
| Padding | mismatch: the status control and the pen sit 12 px further right (pen x 374–385 against 362–373) |
| Spacing | mismatch: the update caption's top to the next folder's text top is 27 px against 28, so the Game dev folders sit 2 px high |
| Typography | match: host, folder and row styles; caption body-small |
| Colour | match: glyph and host name in `error` (255,180,171 in both), controls in `primary`, caption in `onSurfaceVariant`, canvas glow |
| Borders | match: toolbar rule |
| Radii | match (none) |
| Icon paths | mismatch: disconnected and re-pair draw Material Power (6 px wide) where the frame draws the Pair plug (10 px); update draws Material Download (8 px) where the frame draws the Update glyph (12 px) |
| Component state | match: no Channels or Chats plus on any host while it is not connected (#1336); the caption reads "Update Pyrycode to use this host."; folds as drawn |

- The frame's channel rows carry status dots in colours the demo rows do not reach; that is data, verified by #1524.
- **Compact:** the update caption wraps to two lines; every host, control and folder stays on screen.
- **Routed:** #1650

### Archive, empty Channels tab — `673:3577`

- **Owning ticket:** #1265
- **Capture:** `archive-empty-channels.png`, `archive-empty-channels-compact.png` · **Side-by-side:** `archive-empty-channels-side-by-side.png` · **Overlay:** `archive-empty-channels-overlay.png`
- Archive before the walk archives a channel: Channels (0), Discussions (1).

| Aspect | Verdict |
|---|---|
| Geometry | match: with the bar removed title 24–39, tab labels 105–118 against 106–119, indicator at 134, "No archived channels" 483–494 in both |
| Padding | match: 16 px gutters, empty text centred (x 126–284 against 126–285) |
| Spacing | match |
| Typography | match: title, host label, tabs, empty text |
| Colour | match: canvas glow, selected tab without fill, indicator |
| Borders | match: indicator and divider |
| Radii | match (none) |
| Icon paths | match: back arrow |
| Component state | match: Channels selected and empty |

- **Compact:** both tab labels on one line with counts; the empty text centred.
- **Routed:** none

### Archive, empty Discussions tab — `673:3621`

- **Owning ticket:** #1265
- **Capture:** `archive-empty-discussions.png`, `archive-empty-discussions-compact.png` · **Side-by-side:** `archive-empty-discussions-side-by-side.png` · **Overlay:** `archive-empty-discussions-overlay.png`
- Channels (3) archived, the archived discussion restored, Discussions tab tapped through the device's input.

| Aspect | Verdict |
|---|---|
| Geometry | match: as the empty Channels tab; indicator under Discussions (x 206–411), "No archived discussions" 483–494 in both |
| Padding | match: empty text centred (x 115–295 against 115–296) |
| Spacing | match |
| Typography | match |
| Colour | match: the tapped tab has no fill |
| Borders | match |
| Radii | match (none) |
| Icon paths | match: back arrow |
| Component state | match: Discussions selected and empty |

- **Compact:** as the Channels tab.
- **Routed:** none

## Gaps

States reachable from `MainActivity` with no current Mobile frame, plus Edit chat, the one framed state that is not
reachable and so has no capture. Every state #1431 listed here now has a frame in List states `670:5299` and a
section above. The saving, failure, snackbar and Archive loading and error states raised
on #1504 have no frame; #1592 asks for frames or an out-of-reference decision.

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Edit chat modal `671:5499` (unreachable since #1563; chats are renamed from the thread's More actions, Rename) | none | #827 | none: not reachable |
| Archive, Discussions tab with rows | `archive-discussions.png`, `archive-discussions-compact.png` | #1265 | #1487 |
| List-side saving, failure and snackbar states; Archive loading and error | none | #958, #957, #827, #667, #1277, #1265 | #1592 |

Create folder and the pickers' new-folder dialog are reachable only from `AddWorkspaceModal` and `WorkspacePicker`
(below), so they are not reachable. Paste code is the Pair Screen, audited in `onboarding/`.

## Retired workspace reachability

| Surface | Call chain from `MainActivity` | Where it ends | Reachable |
|---|---|---|---|
| `AddWorkspaceModal` | channel-list route → `ChannelListScreen` → `AddWorkspaceModalBinding`, drawn while `hostState.addWorkspace` is non-null | the only writer of a non-null `addWorkspace` is `ChannelListViewModel.openAddWorkspace`, which nothing calls; the route's event `when` has no opening event | no |
| `EditWorkspaceModal` | channel-list route → `ChannelListScreen` → `WorkspaceEditorModal`, drawn while `hostState.workspaceEditor` is non-null | the only writer is `ChannelListViewModel.openWorkspaceEditor`, which nothing calls; the tree draws no workspace rows | no |
| `WorkspacePicker` from Settings | Settings route → `SettingsScreen(pushNotifications, onTogglePushNotifications, onDismissRequest)` | `SettingsScreen` takes no picker callback and draws no picker; `SettingsViewModel.onDefaultWorkspaceTapped`, the only writer of `workspacePickerServerId`, has no caller | no |
| `WorkspacePicker` from the thread | thread route → `ThreadScreen` → `WorkspacePicker(visible = state.workspacePickerVisible)` | `pendingWorkspacePicker` turns true only in `ThreadViewModel.onWorkspaceChipTapped`, passed as `ThreadScreen`'s `onWorkspaceChipTapped` but never invoked inside it, and in `onOverflowEvent`'s `ThreadEvent.ChangeWorkspace` branch, which no UI emits (`ThreadOverflowMenu` has no Change workspace entry) | no |

None is reachable, so none gets a defect. The code stays dead until a cleanup ticket removes it.

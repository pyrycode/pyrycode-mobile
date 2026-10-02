# List-side audit (#1431)

- **App commit:** `main` at `ddebd393`, plus the test-only `ListDesignCaptureTest` on `feature/1431`. No file
  under `app/src/main/` differs from the first run's `4c755aa6`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-02.
  Channel Info `20:48` is a 412x596 sheet; its comparison pads the export onto a 412x892 canvas at the bottom.
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
- **Result:** `list-results.xml`, 2 executed, 0 failures, 0 errors, 0 skipped.

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
| Geometry | mismatch: the centred field block sits 7–10 px higher than the frame (Server identity y 342 against 349, field top 427 against 437), more than the 2 px the shell's 844 px height inside the bars accounts for. Cancel and OK keep the frame's 25 px from the shell's bottom edge |
| Padding | match: 28 px gutters, field text inset 16 px, Unpair host at x 28–155 |
| Spacing | mismatch: the title starts 30 px below the shell's top against 34 (header rule 64 against 68), the same 30 px `17:2` uses, so the two frames disagree; "Host name:" to the field is 12 px against 15. Identity rows keep the 32 px pitch |
| Typography | mismatch: "Server identity:" runs 4 px wider (x 29–126 against 29–122) and "Relay address:" 2 px wider, pushing values 3–4 px right; glyph heights match |
| Colour | match: shell surface, field fill, outlined Unpair host and Cancel, filled OK, close |
| Borders | match: header rule, outlines |
| Radii | match: shell, field, buttons |
| Icon paths | match: close |
| Component state | match: name filled, OK enabled |

- **Compact:** no clipping or overlap; the identity labels wrap to two lines and the relay value ellipsizes;
  every control stays on screen.
- **Keyboard-open:** at 412x892 the field, Unpair host, Cancel and OK sit above the keyboard. At 320x700 and
  150 % the field, Cancel and OK stay above it and Unpair host scrolls under the action bar; the walk scrolls to
  it and asserts it is displayed, so it stays reachable. No overlap.
- **Routed:** #1489 (rescoped from the harness gap the first run named, which this run closed)

## Gaps

States reachable from `MainActivity` with no current Mobile frame. On 2026-10-02 the Mobile page holds no channel-list
variant beyond `15:8`, which draws every host connected, and no empty Archive tab; the Components page (`347:5692`)
holds only the bare `Icon=Pair` (`486:995`) and `Icon=Update` (`581:1606`) glyphs, with no host-row state.

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Edit channel modal (row pen) | `edit-channel.png`, `edit-channel-compact.png` (it opens with its name field focused and the keyboard up; at 412x892 every field and action sits above the keyboard, at 320x700 the prompt field is cut by the action bar and Mute and Archive channel sit below it; the walk scrolls to Archive channel with the keyboard up and asserts it is displayed, so both stay reachable) | #667 | #1504 |
| Edit chat modal (row pen) | none | #827 | #1504 |
| Create channel modal (Channels plus) | none | #958 | #1504 |
| Unpair host confirmation (Edit host) | `edit-host-unpair.png`, `edit-host-unpair-compact.png`; its copy names a "saved workspace" | #745 | #1504, copy in #1489 |
| Archive, Discussions tab | `archive-discussions.png`, `archive-discussions-compact.png` | #1265 | #1487 |
| Rename dialog and Save as channel (thread overflow) | none | #957 | #1504 |
| Disconnected host row | none; `TreeHostRow` draws the glyph and name in `error` and adds a plug control ("Reconnect <host>"), `treeHost` hides the Channels and Chats plus buttons, and `MainActivity` handles `TreeHostReconnectTapped`. The source notes "the reference has no disconnected-host variant" | #840, #1336 | #1504 |
| Re-pair-required host row | none; the same treatment for `RelayLinkStatus.PairingRejected`, whose control emits `TreeHostRePairTapped`, which `MainActivity` routes to re-pair | #842 | #1504 |
| Update-required host row | none; a download control and the update caption under the row for `RelayLinkStatus.UpdateRequired`, emitting `TreeHostUpdateTapped` | #1009 | #1504 |
| Archive, empty tabs | none; `ArchivedDiscussionsScreen` shows `archived_empty_channels` ("No archived channels") or `archived_empty_discussions` ("No archived discussions"). The Channels tab is empty in the demo before the walk archives channels | #1265 | #1504 |
| Delete confirmation (Channel Info, Delete) | none; `ThreadScreen`'s `DeleteConfirmationDialog`, drawn while `state.deleteConfirmVisible` is true, which `ThreadEvent.Delete` from the sheet's Delete action sets | #227 | #1504 |

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

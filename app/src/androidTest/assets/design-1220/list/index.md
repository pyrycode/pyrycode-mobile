# List-side audit (#1431)

- **App commit:** `main` at `4c755aa6`, plus the test-only `ListDesignCaptureTest` on `feature/1431`.
- **Figma:** Mobile page of `g2HIq2UyPhslEoHRokQmHG`, inspected and exported with `get_screenshot` on 2026-10-02.
  Channel Info `20:48` is a 412x596 sheet; its comparison pads the export onto a 412x892 canvas at the bottom.
- **Capture:** `ListDesignCaptureTest` (two methods) on the full `pixel8Api35` image (API 35) with
  `requireRealSystemBars=true`: 412x892 px at density 1.0 and font scale 1.0, and 320x700 at 150 % font scale
  (`-compact`), fixed dark theme, real 24 px bars. Each surface starts from a fresh `MainActivity` launch on the
  paired demo host.
- **Result:** `list-results.xml`, 2 executed, 0 failures, 0 errors, 0 skipped.

No audit declares app-wide parity; #1434 owns that verdict.

### Channel List — `15:8`

- **Owning ticket:** #737 (bar), #738 (tree)
- **Capture:** `channel-list.png` (412x892, 1.0), `channel-list-compact.png` (320x700, 1.5) · **Side-by-side:** `channel-list-side-by-side.png` · **Overlay:** `channel-list-overlay.png`
- Host separation: one host row (Demo) with its own Channels and Chats folders. No workspace grouping and no workspace label appear.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: host and folder rows sit about 20 px below the frame (host text at y≈131 against 111) |
| Padding | match: 20 px tree gutter, toolbar rule inset |
| Spacing | match: 28 px row pitch |
| Typography | match: host, folder and row styles |
| Colour | mismatch: status-bar icons are dark on the dark canvas; rows have no green, pink or blue status dots |
| Borders | match: toolbar rule |
| Radii | match (no row selected) |
| Icon paths | mismatch: every row and the host row draw an edit pen; the frame shows one only on the selected row |
| Component state | match: folders expanded; the frame's user folder (Apps) has no app counterpart in the demo data |

- **Compact:** no clipping or overlap; every row, pen and plus stays reachable.
- **Menu-open:** the list has no row or folder menu at this commit (`ChannelListScreen` and `ConversationTreeRows` hold no `DropdownMenu`; rows open editors through pens). The only menu on a list-side path is the thread overflow menu (`thread-menu.png`, `thread-menu-compact.png`), which opens fully inside the window with every entry reachable.
- **Routed:** #1486

### Archive — `18:2`

- **Owning ticket:** #1265
- **Capture:** `archive.png`, `archive-compact.png`; Discussions tab `archive-discussions.png`, `archive-discussions-compact.png` · **Side-by-side:** `archive-side-by-side.png` · **Overlay:** `archive-overlay.png`

| Aspect | Verdict |
|---|---|
| Geometry | match: top bar, tabs and rows at the frame's positions, after the host label (a recorded adaptation) |
| Padding | match: 16 px gutters |
| Spacing | match |
| Typography | match: title, tab labels, row title and subtitle |
| Colour | mismatch: status-bar icons are dark on the dark canvas |
| Borders | match: tab indicator and divider |
| Radii | match (none) |
| Icon paths | match: back arrow, restore icon |
| Component state | mismatch: with no archived channel, Discussions is selected on open; the frame opens on Channels |

- **Compact:** mismatch: "Discussions (1)" wraps to two lines and overruns the tab indicator.
- **Routed:** #1487

### Channel Info Sheet — `20:48`

- **Owning ticket:** #1266
- **Capture:** `channel-info.png`, `channel-info-compact.png` · **Side-by-side:** `channel-info-side-by-side.png` · **Overlay:** `channel-info-overlay.png`
- Reached from the thread's overflow menu, Channel info.

| Aspect | Verdict |
|---|---|
| Geometry | mismatch: the sheet fills the window; the frame is a 596 px sheet |
| Padding | match: 16 px content gutters, section label inset |
| Spacing | match: row pitch in About |
| Typography | match: title, section labels, monospace path |
| Colour | match: sheet surface, secondary values |
| Borders | match (none) |
| Radii | match: top corners, drag handle |
| Icon paths | match: close icon |
| Component state | mismatch: Session, System prompt and MCP servers sections sit before Actions, which falls below the fold; the frame's Change workspace action is retired and must not be restored |

- **Compact:** no clipping; long labels wrap and values stay right-aligned; Actions needs scrolling.
- **Routed:** #1488

### Settings / Notifications modal / Dark — `17:2`

- **Owning ticket:** #1239
- **Capture:** `settings.png`, `settings-compact.png` · **Side-by-side:** `settings-side-by-side.png` · **Overlay:** `settings-overlay.png`
- Notifications only, with the "Notification sound — Default" row.

| Aspect | Verdict |
|---|---|
| Geometry | match inside the bars: the modal moves 24 px down with the status bar |
| Padding | match: 28 px gutters |
| Spacing | match |
| Typography | match: title, section label, row text |
| Colour | match: modal surface, switch, Done |
| Borders | match: header rule |
| Radii | match: modal, close button, Done |
| Icon paths | match: close, chevron |
| Component state | match: notifications on |

- **Compact:** no clipping; the push row wraps to four lines and the switch and Done stay reachable.
- **Routed:** none

### Modal › Edit host — `533:2369`

- **Owning ticket:** #1277
- **Capture:** none. The harness's startup store answers `list()` but not `loadById`, so the host editor rejects the
  demo host (`host_editor_open_rejected code=unknown_host`). Fixing that edits `DesignCapture`, outside this
  audit's write scope. Default, compact and keyboard-open states are unverified.
- **Routed:** #1489

## Gaps

States reachable from `MainActivity` with no current Mobile frame:

| State | Capture | Owning ticket | Routed |
|---|---|---|---|
| Edit channel modal (row pen) | `edit-channel.png`, `edit-channel-compact.png` | #667 | #1434 (parity verdict) |
| Edit chat modal (row pen) | none | #827 | #1434 |
| Create channel modal (Channels plus) | none | #958 | #1434 |
| Unpair host confirmation (Edit host) | none | #745 | #1489 |
| Archive, Discussions tab | `archive-discussions.png` | #1265 | #1487 |
| Rename and Save as channel | thread overflow, audited with the thread (#1432) | #957 | #1432 |

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

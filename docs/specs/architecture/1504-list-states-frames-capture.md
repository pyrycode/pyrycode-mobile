# #1504 — capture the list-side states against List states `670:5299`

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt` — `walk`, `openFirstChannel`, `relaunch`, `awaitText`, `tap`, `archiveChannels`: the walk this ticket extends. #1563 already reaches Edit channel through the thread's More actions, Edit.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt` — `install` replaces the app's `HostConversationSource` with one connected demo host; `uninstall` restores the app's. Used unchanged.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt` — `launch`, `capture`, `openMenu`. Used unchanged.
- `app/src/androidTest/assets/design-1220/list/index.md` and `design-1220/README.md` — the per-item format and the Gaps table this ticket empties.
- `docs/specs/architecture/1431-list-side-design-audit.md` — the analogue; its revisions are the lessons: each surface starts from a fresh launch, assert content only the target state shows before capturing, quote measured positions with the 24 px bar removed.
- `di/HostConversationSource.kt` — the `internal` constructor over `StateFlow<List<HostConversationConnection>>`; a `null` repository gives a host with no rows. `ChannelListViewModel` reads hosts only from `snapshots`.
- `ui/conversations/components/ConversationTreeRows.kt` — `TreeHostRow` (error accent for every `isDisconnected` status, Power control for disconnected and `PairingRejected`, Download control plus caption for `UpdateRequired`), `foldActionLabel` ("Collapse <name>").
- `ui/conversations/list/ChannelListScreen.kt` — `treeHost` (no section plus while disconnected), `CreateChannelModalBinding`, `ChatEditorModal`; `TREE_CHAT_ROW_TEST_TAG`. `ChannelListEvent.TreeChatEditTapped` has no constructor call anywhere in `app/src`.
- `ui/conversations/thread/ThreadOverflowMenu.kt` — an unpromoted thread's menu has "Save as channel…" and "Rename"; `ThreadScreen.kt` binds `RenameDialog`, `SaveAsChannelDialog` and `DeleteConfirmationDialog`; `ChannelInfoSheet`'s "Delete" `ActionCell`.
- `ui/settings/ArchivedDiscussionsScreen.kt` — `archived_empty_channels`, `archived_empty_discussions`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=670-5299

List states · 2026-10-02: Edit channel `671:5415`, Edit chat `671:5499`, Create channel `671:5558` (empty fields, OK disabled), Rename `671:5664` and Save as channel `671:5718` (both prefilled, full-height `MobileModal` shells), Delete confirmation `673:3665` (Material alert dialog over the canvas glow), host rows `672:3493` (three hosts: "Pyry" disconnected and expanded, "MB Second brain" re-pair with both folders collapsed, "MB Game dev" update-required with the caption "Update Pyrycode to use this host." and both folders collapsed; host glyph and name in `error`, controls in `primary`, Pair plug for disconnected and re-pair, Update glyph for update), Archive empty Channels `673:3577` and empty Discussions `673:3621`. All 412x892, fixed dark theme. Exports `figma-<node>.png` go beside the captures.

## Context

The #1431 audit listed these states as gaps; `670:5299` now draws each. Test and evidence only: no file under `app/src/main/` changes. No decision record.

Edit chat `671:5499` is not captured. Since #1563 no row draws a pen, and `TreeChatEditTapped`, the only route to `ChannelListViewModel.openChatEditor`, is never constructed, so the modal is not reachable from `MainActivity`. A test-only call into the view model would capture a modal the shipped app cannot show, against the README's rule that audits compare reachable states. It stays in the Gaps table as unreachable, with its frame id; the previous builder's note on this ticket asked for that decision and none came, so this plan makes it and the PR says so.

## Design

`ListDesignCaptureTest.walk` gains these steps, each from a fresh `relaunch()` and each waiting on text only the target state shows:

| Capture | Frame | Path |
|---|---|---|
| `create-channel` | `671:5558` | channel list, "New channel on Demo" → "Create channel" |
| `archive-empty-channels` | `673:3577` | Archive before `archiveChannels` → "No archived channels" |
| `archive-empty-discussions` | `673:3621` | inside `archiveChannels`, the archived discussions are unarchived through `FakeConversationRepository.unarchive` and re-archived in a `finally` → "No archived discussions" on the Discussions tab |
| `edit-channel` | `671:5415` | unchanged path, retagged from `none` |
| `rename` | `671:5664` | first `TREE_CHAT_ROW_TEST_TAG` row, More actions, Rename → the name field |
| `save-as-channel` | `671:5718` | first chat row, More actions, "Save as channel…" → the dialog title |
| `delete-confirmation` | `673:3665` | after the Channel Info capture, scroll to "Delete", click → "Delete conversation?" |
| `host-rows` | `672:3493` | `hostStates { … }` below, then collapse the folds the frame draws collapsed |

`hostStates(block)` (private, in the capture class): reads the `HostConversationSource` Koin currently holds (the one `DesignInputs.install` put there), loads a `single<HostConversationSource>` built over three `HostConversationConnection`s — "Pyry" on the demo `FakeConversationRepository` with `RelayLinkStatus.Offline`, "MB Second brain" with no repository and `PairingRejected`, "MB Game dev" with no repository and `UpdateRequired(null)` — runs `block`, then in a `finally` reloads the previous instance and disposes its own. This mirrors the Edit host walk's store override; `DesignInputs` stays unchanged.

The index gains one section per captured frame in the README format, the Edit channel row leaves Gaps for its own section, and Gaps keeps only Edit chat (unreachable) and the states #1592 owns. `scripts/design-compare.py` produces each side-by-side and overlay. `list-results.xml` is replaced by this run's XML.

Overlap: no other in-flight branch touches these files.

## State and concurrency model

Test-only. The extra source runs on its own scope until `dispose`; restoration happens in `finally` so a failed step leaves the harness's source in place for later steps and for `DesignCapture`'s teardown.

## Error handling

A navigation that misses its state fails `waitUntil` or `assertIsDisplayed`; a blank frame or synthetic bars fail `capture`.

## Testing strategy

Device-only, as #1431: real pixels from the real `MainActivity` at a `wm` viewport. `./gradlew :app:pixel8Api35DebugAndroidTest --rerun` with `class=de.pyryco.mobile.design.ListDesignCaptureTest` and `requireRealSystemBars=true`, both methods; the XML is copied to `list/list-results.xml` and its executed, failed and skipped counts are quoted in the index header and the PR. `compileDebugAndroidTestKotlin` before the device run. No rung-3 scenario: an audit, not an operator flow.

## Open Questions

- Whether Create channel opens with its name field focused and the keyboard up, as Edit channel does. The frame draws no keyboard; the capture records whatever the app opens with, and the index's Component state says which.
- Whether the chat thread's Rename and Save as channel dialogs open with the keyboard up; same treatment.

## Documentation handoff

None from the ticket. Lessons, if any, go in the PR.

## Revisions

- **2026-10-03, device run and comparison:** (1) Open Questions resolved: Create channel opens with its name focused
  and the keyboard closed; Rename opens with its name selected and the keyboard closed at 412x892 (up at 320x700);
  Save as channel opens, like Edit channel, with the keyboard up. The walk captures each state as it opens, so the
  Edit channel and Save as channel footers could not be compared with the frames' keyboard-closed footers; the
  index marks their Geometry unverified and routes the keyboard-closed captures with the other modal mismatches to
  #1651 instead of spending another device run inside this ticket. (2) Mismatches go to two new scoped defects,
  because every owning ticket is closed: #1650 for the host rows' controls and glyphs, #1651 for the modals' field
  gaps, the Delete confirmation's spacing and backdrop, and the keyboard-closed captures. The shell offset stays
  with #1588. (3) The index's run header records that only this ticket's captures and the retagged `edit-channel*`
  come from this run.

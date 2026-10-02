# #1431 — list-side design audit and retired workspace routes

## Files read

- `app/src/androidTest/assets/design-1220/README.md` and `onboarding/index.md` — the index format and the evidence layout this audit follows.
- `app/src/androidTest/java/de/pyryco/mobile/design/` — `ViewportRule`, `DesignCapture` (`paired`, `launch`, `capture`, `openKeyboard`, `openMenu`), `DesignInputs`, and `OnboardingDesignCaptureTest` as the shape to mirror. Used unchanged.
- `MainActivity.kt` — the channel-list route's event `when` (no branch calls `openAddWorkspace` or `openWorkspaceEditor`), the thread route (`onWorkspaceChipTapped = vm::onWorkspaceChipTapped`) and the Settings route (`SettingsScreen` gets only notifications and dismiss); `Routes.DISCUSSION_LIST` has a destination but no `navigate` call.
- `ui/conversations/list/ChannelListScreen.kt` — `ChannelListTopBar`, `ConversationTree`/`treeHost` (host row, Channels and Chats sections, conversation rows with pens), `AddWorkspaceModalBinding`, `WorkspaceEditorModal`.
- `ui/conversations/list/ChannelListViewModel.kt` — `openAddWorkspace` and `openWorkspaceEditor`, the only writers of a non-null `addWorkspace` and `workspaceEditor`.
- `ui/settings/SettingsViewModel.kt` — `onDefaultWorkspaceTapped`, the only writer of `pendingWorkspacePicker`; `ui/settings/SettingsScreen.kt` — Notifications section and the Notification sound row.
- `ui/conversations/thread/ThreadViewModel.kt` (`onWorkspaceChipTapped`, the `ThreadEvent.ChangeWorkspace` branch of `onOverflowEvent`), `ThreadScreen.kt` (`WorkspacePicker(visible = state.workspacePickerVisible)`, the unused `onWorkspaceChipTapped` parameter), `ThreadOverflowMenu.kt` (Channel info entry, no Change workspace entry).
- `ui/settings/ArchivedDiscussionsScreen.kt`, `ui/host/HostEditor.kt`, `ui/conversations/components/ChannelInfoSheet.kt` — the audited surfaces.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Mobile page, re-read 2026-10-02: Channel List `15:8`, Archive `18:2`, Channel Info Sheet `20:48` (412×596 bottom sheet), Settings / Notifications modal / Dark `17:2`, Modal › Edit host `533:2369`. Fixed dark theme only. `Workspace Picker Sheet` `20:2` is retired product and not audited. No Mobile frame covers Edit channel, Edit chat, Create channel, Unpair confirmation or Archive's Discussions tab; those are gaps.

## Context

Split from #1220. Audits the list-side surfaces of the assembled app with the #1430 harness, and settles from source whether the retired workspace surfaces are reachable. Test and evidence only: no file under `app/src/main/` changes. No decision record.

## Design

- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt` — two methods on `ViewportRule`, `createEmptyComposeRule` and `DesignCapture` with `paired = true`:
  - default 412×892 at font scale 1.0: channel list; Settings; Archive (Channels tab, Discussions tab); Edit host, then Edit host with the keyboard on its name field; Edit channel through a row pen (gap); the thread overflow menu open, then Channel Info.
  - `@Viewport("320x700", fontScale = 1.5f)`: the same walk, names suffixed `-compact`.
  Each step waits on a visible text or content description before `capture`, so a missed navigation fails rather than captures the wrong screen.
- `app/src/androidTest/assets/design-1220/list/` — PNG and `.txt` captures, Figma exports, `scripts/design-compare.py` side-by-sides and overlays, the run's JUnit XML, and `index.md` in the README's per-item format with a Gaps section and a Retired workspace reachability section.
- Channel Info is a 412×596 sheet; its export is padded onto a 412×892 canvas at the bottom before comparison, in scratch, so `design-compare.py` does not stretch it.

Overlap: none expected; `design-1220/list/` and the new class are this ticket's alone.

## State and concurrency model

Test-only; the harness owns scopes and restoration.

## Error handling

A navigation that does not reach its screen fails `waitUntil`/`assertIsDisplayed`; a blank frame or synthetic bars fail `capture`.

## Testing strategy

Device-only (real pixels, real `MainActivity`, `wm` viewport and IME): `./gradlew :app:pixel8Api35DebugAndroidTest --rerun` with `class=de.pyryco.mobile.design.ListDesignCaptureTest` and `requireRealSystemBars=true`. The XML is copied to `list/list-results.xml`. No rung-3 scenario: an audit, not an operator flow.

## Open Questions

- Whether the list has any row or folder menu to open. Source shows none at this commit (pens open modals); the index records the menu-open check against the thread overflow menu, the only menu on the path to a list-side surface.

## Revisions

- **2026-10-02, device runs:** (1) Each surface starts from a fresh `design.launch()` instead of dismissing the previous one, because Back from Archive did not return to the list within the wait and one surface's dismissal path should not steer the next. (2) Edit host is not captured: `DesignCapture`'s startup store answers `list()` only, so the host editor rejects the demo host (`host_editor_open_rejected code=unknown_host`). Fixing it edits the harness, outside this ticket's write scope; routed to #1489 with the keyboard-open state. (3) The Open Question resolved as planned: no list menu exists, and the menu-open check uses the thread overflow menu.
- **2026-10-02, review rework (verifier verdict on #1490):** (1) Edit host is captured, superseding (2) above: the capture class installs, through Koin, a `PairedServerCollectionStore` that wraps the harness's startup store and answers `loadById` for the demo host. `DesignCapture` is unchanged and restores its original store afterwards. The walk captures `edit-host`, `edit-host-keyboard` and the Unpair confirmation (a gap) at both viewports. `DesignCapture.openKeyboard` waits on the activity window, which a modal's dialog keeps unfocused, so the class drives the keyboard from the dialog's own view (`ViewRootForTest.view`). At 320x700 it asserts Unpair host stays reachable with the keyboard open. (2) Archive's `18:2` capture is the Channels tab, which the first run never reached because the view model always opens on Discussions. The walk archives three demo channels through `FakeConversationRepository` and restores them in a `finally`. It taps the tabs through `input tap`, so the tapped tab's fill is what a finger leaves, not a test-click artefact. (3) The list and Settings assert that no text contains "workspace". (4) Routing: Settings spacing goes to #1503, the missing frames to #1504, #1487 is corrected and #1489 rescoped to the Edit host mismatches. No gap routes to #1434.
- **2026-10-02, second review rework (verifier's second-round verdict on #1490):** (1) The Edit channel step scrolls to Archive channel after its capture and asserts it is displayed, so the compact keyboard-up state's reachability is checked rather than noted. (2) `assertNoWorkspaceText` also counts content descriptions containing "workspace", because a returning workspace row would show only its folder name. (3) Host-to-host spacing is not captured: the demo pairs one host, and the verdict allowed recording it as unverified instead of building a second host's data in the capture class. The index marks it unverified and #1486 carries it. (4) The index's Channel List geometry is re-derived with the 24 px bar removed (the screen sits 4 px high, top bar included), the missing canvas glow is a Colour mismatch, Channel Info's About spacing and Memory plugins state are mismatches, Archive's header offset is 23–24 px, and the Channel Info Delete confirmation is a gap routed to #1504. #1486, #1487 and #1488 are corrected to match.

## Documentation handoff

Pending for the documentation stage:

- `docs/knowledge/features/` design-capture topic (the overview that covers the `design-1220` harness): a modal `Dialog` keeps the activity window unfocused, so drive its keyboard from the dialog's own view (`ViewRootForTest.view`); before a capture, assert content only the target state shows, because a click on an already-selected tab leaves the capture unchanged; in a frame with no status bar, check whether the top bar moved with the content before calling an offset a row offset.
- `docs/knowledge/features/app-preferences.md`, the canvas glow note: the Channel List glow, once placed outside the root palette contract, is now a routed mismatch (#1486). Record it in the #1434 inventory when `design-1220/list/` is indexed.

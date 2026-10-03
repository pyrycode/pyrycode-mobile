# Design audit evidence (#1220)

The assembled app, captured through the real `MainActivity`, compared with the current Mobile page of
Figma file `g2HIq2UyPhslEoHRokQmHG`. #1430 built the harness and audited onboarding. Each later audit
(#1431, #1432, #1433) writes only its own subfolder and its own `index.md`. No audit declares app-wide
parity; #1434 owns that verdict.

- **App commit:** `main` at `97ee8d75` (the last `main` merged into #1430 before its captures). Each subfolder's `index.md` records its own.
- **Figma inspection:** 2026-10-02 for `onboarding/`. Each subfolder records its own date; the page is the source of truth.
- **Theme:** the app's fixed dark theme only. Light mode, system theme and dynamic colour are out of scope.

## Folder layout

```
design-1220/
├── README.md                 # this file: layout, index format, harness and commands
├── smoke/                    # harness self-checks (compact large text, thread override, menu)
│   ├── smoke-results.xml     # JUnit XML: DesignHarnessSmokeTest and the two classes on ViewportRule
│   └── first-run-results.xml # the first smoke run's XML, 1 failure, behind the plan's 2026-10-01 revision
└── <surface-group>/          # one per audit, e.g. onboarding/
    ├── index.md              # one entry per audited frame or state
    ├── <state>.png           # the app capture, from the device run
    ├── <state>.txt           # capture metadata: Figma node, size, density, font scale, bars, IME, API
    ├── figma-<node>.png      # get_screenshot export of the node, node id with "-" for ":"
    ├── <state>-side-by-side.png  # scripts/design-compare.py: Figma left, app right
    ├── <state>-overlay.png       # scripts/design-compare.py: 50/50 blend
    └── <surface>-results.xml # the device run's JUnit XML
```

## Per-item index format

Each audited item in a subfolder's `index.md` is one section:

```markdown
### <Frame name> — `<node id>`

- **Owning ticket:** #N (the ticket that implemented or last changed this surface)
- **Capture:** `<state>.png` (viewport, font scale)
- **Side-by-side:** `<state>-side-by-side.png`
- **Overlay:** `<state>-overlay.png`
- **Verdict:**

| Aspect | Verdict |
|---|---|
| Geometry | match / mismatch: … |
| Padding | … |
| Spacing | … |
| Typography | … |
| Colour | … |
| Borders | … |
| Radii | … |
| Icon paths | … |
| Component state | … |

- **Routed:** #N for each mismatch, or "none"
```

A state reachable from `MainActivity` with no current Figma frame is listed under **Gaps** with its
owning ticket and the routed issue. Platform system bars replace Figma's chrome-free outer frame and are
not a mismatch; everything inside the app's window is compared. A host row's connection states —
disconnected, re-pair-required, update-required — count as reachable list-side states under this rule,
even though the audited frame draws every host connected (#1431).

## Harness

All under `app/src/androidTest/java/de/pyryco/mobile/design/`. No file under `app/src/main/` is changed.

- `ViewportRule` with `@Viewport("WxH", fontScale = 1.5f)` — the outermost rule (`order = 0`). Applies
  density 160, the size (default `412x892`) and the system font scale before any activity launches,
  and restores all three afterwards.
- `DesignCapture(rule)` — use with `createEmptyComposeRule()` at `order = 2`. Selects the test IME, grants
  camera and notification permission (not revoked: a revoke kills the instrumentation process), pins dark theme with wallpaper colours off, controls the startup
  paired snapshot (`design.paired = true` opens the channel list), installs the Koin override, and
  restores everything. `launch()`, `capture(folder, name, figmaNode)`, `openKeyboard(node)`,
  `closeKeyboard()`, `openMenu(anchor)`, `insets()`.
- `DesignInputs` (`design.inputs`) — the Koin override, loaded over the app graph. It redefines
  `ThreadViewModel`, `ScannerViewModel` and `PairCodeViewModel`. Set these before or after launch; the open
  thread collects them:
  `connectionState`, `liveSessionEvents`, `hostModal` (a `HostModalState` of permission and trust
  prompts), `questionBatch`, `failQuestionSends` (while true, the Continue tap's `answerQuestionBatch`
  throws `IllegalStateException`, which `sendQuestion` reports as `QuestionSendPhase.Failed`; false by
  default, so it leaves every other capture unchanged), `backgroundTasks`, `backgroundTaskCount`,
  `pairingRejected`, `attachmentOffers`, `sessionFacts`, `contextUsage`. The fake repository still supplies
  messages, session settings, the model menu and the slash menu. The question batch, roster, count and
  repository flows
  apply to whichever thread opens. `hostModal` does not: the view model scopes it with
  `HostModalState.scopedTo`, so a prompt shows only when its `conversationId` is the open thread's. The thread marks
  its conversation viewed as production does. The override passes no app draft stores, so the view model
  reads `questionBatch` itself. At `97ee8d75` no thread code reads `observeAttachmentOffers`, so
  `attachmentOffers` is wired but nothing subscribes to it yet.
  `install()` also replaces the app's `HostConversationSource` with one demo host whose `modals` is
  `hostModal` and whose `questionBatches` is `questionBatch` mapped to a list, so the channel list marks
  each prompt's conversation Waiting the same way the thread does (#1507). `uninstall()` restores the
  app's instance.
- Pairing inputs, shared by the scanner and the pair-code screen: `pairingStatus` is what the post-confirm
  wait observes. `null` holds the connecting state until the 30 s `PAIRING_VERIFICATION_DEADLINE_MS`, then
  fails as unavailable with Retry. `RelayLinkStatus.DaemonAbsent` fails the same way at once, and
  `PairingRejected` fails without Retry. Both save to the in-memory `pairedHosts` instead of the Keystore;
  seed an entry to give a re-pair target its stored name. `holdSaves = true` suspends saves, holding the
  pair-code screen in Saving. `thread`, `scanner` and `pairCode` hold the view models the override
  built, for event-only states such as the scanner's denied or camera-error state.
- `DesignHarnessSmokeTest` proves the compact 320x700 at 150 % capture and that every thread input
  reaches the thread's view model.

`capture` writes `<additionalTestOutputDir>/design-1220/<folder>/`. It fails on a blank frame and, under
`requireRealSystemBars=true`, on synthetic bars. ATD framebuffers can be black, so evidence comes from
the full `pixel8Api35` image only.

## Commands

```bash
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.<YourAuditTest>' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
# captures: app/build/intermediates/managed_device_android_test_additional_output/debugAndroidTest/pixel8Api35DebugAndroidTest/design-1220/
# results:  app/build/outputs/androidTest-results/managedDevice/debug/pixel8Api35/

python3 scripts/design-compare.py <state>.png figma-<node>.png <folder>/<state>
```

## App-wide inventory (#1434)

Every screen, modal, sheet, menu and material UI state reachable from the `MainActivity` routes and the
`ThreadScreen` overlays, joined from the four audit indexes and checked against source and Figma.

- **App commit:** `main` at `5dfa507a`. No code or test source changed for this inventory; the audits' own
  commits are in their indexes.
- **Figma inspection:** the Mobile page `0:1` and the Components page `347:5692`, read with `get_metadata` on
  2026-10-02, and the Mobile page's section Reachable states · #1539 `696:4676` re-read the same day after it
  was drawn. The audit rows were re-read against the indexes as merged at `5dfa507a`.
- **Status:** each row takes one of these values.
  - `audited, match` and `audited, mismatch` have a verdict in the linked index section, or, for the launch
    splash, on the issue that compared it.
  - `audited, unverified` was in an audit's scope, but the capture did not reach the state; its linked issue
    owns the check.
  - `audited for clipping` has no frame at that size; the audit judged clipping and reachability only.
  - `frame only` is a current frame that no audit has compared yet; its linked issue owns the capture.
  - `no separate frame` is drawn by an existing frame or component, by a decision recorded on the linked issue.
  - `component only` is a Components-page variant with no Mobile frame and no capture yet.
  - `gap` is a reachable state with no frame or component; its linked issue asks for one or for an
    out-of-reference decision.
  - An `audited` row's verdict can include gaps its audit recorded as accepted by decision rather than routed;
    the row says so.
  - `out of scope` is drawn by the platform, not the app.
  - `not shipped` is a frame for a feature the app does not have; it does not count toward parity.
  - `retired` is a frame replaced by a later one.
- **Linked issues** are the routed mismatches and gaps; each one's state at `5dfa507a` is under
  [Linked issues](#linked-issues). An `audited` row whose only issues are closed matched, or was fixed after
  its capture; no app-wide re-check follows (see [Verdict](#verdict)). When an issue closed as a family
  root, its split children are linked on the same row.

### Onboarding

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Welcome | `6:32` | audited, match | `onboarding/index.md` › Welcome | #1212 | none |
| Scanner | `13:2` | audited, match | › Scanner | #1213 | none |
| Scanner — Denied | `32:2` | audited, match | › Scanner — Denied | #1213 | none |
| Scanner — Camera error; scanner invalid-QR and save-failed errors (`ScannerUiState.Error`, including `SAVE_FAILED_MESSAGE`, same layout) | `654:5032` | audited, match | › Scanner — Camera error | #326 | none |
| Pairing — Confirm fingerprint (scanner and code path) | `654:4834` | audited, match | › Pairing — Confirm fingerprint | #1270, #1214 | none |
| Pairing — Connecting | `654:4882` | audited, mismatch | › Pairing — Connecting | #1386 | #1461 |
| Pairing — Verification failed, retry | `654:4932` | audited, match | › Pairing — Verification failed, retry | #1386 | none |
| Pairing — Verification failed, rejected | `654:4982` | audited, match | › Pairing — Verification failed, rejected | #1386 | none |
| Pair Screen, keyboard closed and open | `533:2147` | audited, mismatch | › Pair Screen; › Pair Screen with the keyboard open | #1269, #1149 | #1462 |
| Pair code — Saving, Connecting | `663:2887`, `663:2963` | audited, mismatch | › Pair code — Saving; › Pair code — Connecting | #1269, #1385, #1464 | #1462, #1506 |
| Pair code — Verification failed, retry and rejected | `663:3039`, `663:3115` | audited, mismatch | › Pair code — Verification failed, retry; › … rejected | #1385 | #1462, #1506 |
| Pair code — Invalid code | `663:3191` | audited, mismatch | › Pair code — Invalid code | #1269 | #1462, #1506 |
| Re-pair, Re-pair — Wrong host | `663:3266`, `663:3331` | audited, mismatch | › Re-pair; › Re-pair — Wrong host | #842 | #1462, #1506 |
| Scanner — Connecting | `32:20` | retired | replaced by `654:4882` | — | none |
| Launch splash window | `701:5001`, icon spec `701:5005` | audited, mismatch (fixed by #1545, which compared a cold launch on the emulator display with `701:5001`) | no index row: the harness cannot capture the splash; the comparison is on #1545 | #1545 | #1539, #1545 |

### Channel list (sidebar)

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Channel List, connected host, expanded tree | `15:8` | audited, mismatch | `list/index.md` › Channel List | #737, #738 | #1486 (family root), #1521, #1522, #1523, #1525 |
| Selected and pressed (darker-filled) conversation rows, edit pen on the selected row | `15:8` | audited, mismatch (fixed and verified against `15:8` by #1523) | › Channel List | #737, #738 | #1486, #1523 |
| Host-row edit pen | `15:8` draws none | audited, mismatch | › Channel List (Icon paths) | #744 | #1525 |
| Collapsed folders and hosts | `15:8` | audited, mismatch (verified against `15:8` by #1521's screen test) | › Channel List | #738 | #1486, #1521 |
| Conversation status dot: Waiting, Running, Unread | `15:8` | audited, mismatch (verified against the frame's dot nodes by #1524) | › Channel List (Colour) | #738 | #1486, #1524 |
| Disconnected, re-pair-required and update-required host rows | `672:3493` | frame only | `list/index.md` › Gaps | #840, #1336, #842, #1009 | #1504 |
| Waiting marks while prompts wait | `640:2440` | audited, unverified | `prompts/index.md` › Switch chats while prompts wait (`switch-list.png` captured, marks unjudged) | #1338 | #1507 |
| Create-chat failure snackbar | none | gap | #1504's comment | #958 | #1504 |

### Thread, composer and footer

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Composer band, strip, input and footer (every thread frame) | `16:8` and the thread frames | audited, mismatch | `thread/index.md` › Composer and footer | per frame below; #933 (strip) | #1532, #1495 |
| Conversation Thread | `16:8` | audited, mismatch | › Conversation Thread | #1206, #933, #1290, #875 | #1513, #1494, #1495 |
| Connecting, Reconnecting | `627:1740`, `627:4657` | audited, mismatch | › Connecting, Reconnecting | #1283, #1312, #1319 | #1532, #1493 |
| Offline | `627:4910` | audited, mismatch | › Offline | #1283 | #1499, #1532, #1493 |
| Task count, usage-limit and pairing-error pills | `568:3139` | audited, mismatch | › Task count pill | #1043, #1002, #1115, #842 | #1499 (family root), #1519 |
| Session delimiter (clear and idle-evict) | `675:3682` | audited, mismatch | › Session delimiter | #1207, #1358 | #1512, #1498, #1500 |
| Session boundary without the explanation line, and full-opacity rows above it | `675:3682`, `675:5883` (decision on #1578: the frames keep their `Explanation` node and faded rows; the app draws the `Rule row` alone) | no separate frame | › Session delimiter | #1578 | #1580 (closed, not needed) |
| Overflow menu | `675:5883` | audited, match | › Overflow menu | #1199 | #1500 |
| Actions menu | `675:5938` | audited, match | › Actions menu | #884 | #1500 |
| Keyboard open | `675:6160` | audited, match | › Keyboard open | #1149 | #1500 |
| Keyboard open, compact 150 % | `676:3981` | audited, mismatch | › Keyboard open / Compact 150% | #1149, #1347, #1412 | #1485, #1500; Line-box and Cancel-height gaps accepted by decision, not routed (`prompts/index.md` › Compact frames at 150 %) |
| Compact thread, notices, menus, task panel, Run configuration | none at 320x700 | audited for clipping | › Compact, keyboard and menus | #1149, #1347, #1412 (compact layout); #1199, #884 (menus); #1295 (task panel); #1195 (Run configuration); #1002 (usage-limit pill) | #1485, #1499 (family root), #1519, #1496 |
| Status bar icons | none | audited, mismatch (captured with the dark-icon defect, fixed after capture) | › Status bar | #1149 (edge-to-edge window) | #1510 |
| Turn outcome pill and stopped-turn row | `685:3992`, `620:1574` | frame only | `thread/index.md` › Gaps | #1356, #897 | #1529 |
| Slash-command type-ahead | `685:4232` | frame only | › Gaps | #885 | #1529 |
| Failure notice (thread snackbars) | `685:4337` | frame only | › Gaps | #1149 | #1529 |
| History tail: loading, retry, dead end, offline | `689:4281`, `689:4330`, `689:4379`, `689:4427` | frame only | › Gaps | #777, #778, #1352 | #1529 |
| Status-band arms `Resetting`, `ApiRetry`, `Compacting`, `Working`, `RunningTool`, `Stalled` | `16:8` band, `134:5013` (decision on #1529) | no separate frame | › Gaps | #1312, #897, #803 | #1529 |
| Send button's Stop variant | `114:3549` (decision on #1529) | no separate frame | › Gaps | #459, #643 | #1529 |
| Top overlay Error pills: failed MCP server, non-warning usage limit | `347:6619` (decision on #1529) | no separate frame | › Gaps | #1345, #1002, #1115 | #1529 |
| Prompt resolved elsewhere: dismissal notice | `696:5065` | audited, mismatch (changed design) | `thread/index.md` › Prompt resolved elsewhere | #446, #1337 | #1539, #1604, #1626 |
| Empty thread | `696:4989` | audited, mismatch | `thread/index.md` › Empty thread | none named (see `empty-thread-state.md`) | #1539, #1625 |
| Codex agent switch: Switching, Switch confirm | `578:3248`, `578:3442` | not shipped | › Codex agent switch | #1118 | #1118 (does not count toward parity while open) |

### Messages and tools

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Message meta row hidden until the bubble is tapped; at most one visible, hidden while streaming | `132:4446`, `132:4435` (decision on [#1621](https://github.com/pyrycode/pyrycode-mobile/issues/1621): the component keeps drawing the unchanged timestamp and copy row; the app hides it until tap) | no separate frame | not separately audited | #1621 | #1621 |
| Tool row, finished | `674:5853` | audited, match | `thread/index.md` › Tool row | #1208, #1315, #1316 | #1500 |
| Tool row running and failed, nested sub-agent rows | `696:4795` | audited, mismatch | `thread/index.md` › Sub-agent tool rows | #811, #895, #896, #1315, #1316, #1577 | #1539, #1623, #1626, #1630 |
| Refusal row, collapsed | `620:1577` | audited, mismatch | › Notification text | #875 | #1494 |
| Refusal switch back, armed | `646:4707` | audited, mismatch | › Refusal switch back | #1360 | #1494 |
| Refusal expanded; switch back pending and failed | `620:1570`, `646:4694`, `646:4700` | component only | not audited | #875, #1360 | #1540 |
| Session notice (warning) | `627:5466` | audited, match | › Session notice | #1113, #875 | none |
| Unrecognized message, collapsed and expanded | `685:4112` | frame only | › Gaps | #608 | #1529 |
| Compaction boundary row | `675:3682` rule (decision on #1529) | no separate frame | › Gaps | #874 | #1529 |
| Queued message row, with its drop action | `696:4677` | audited, mismatch | `thread/index.md` › Queued messages | #1161 | #1539, #1622, #1626, #1630 |

### Attachments

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Staged strip, four tiles, while connected | `16:8`, `390:7145` | audited, match | `thread/index.md` › Composer and footer | #933 | #1495 |
| Staged PDF tile while disconnected | `627:1740`, `627:4657`, `627:4910` | audited, mismatch | › Connecting, Reconnecting; › Offline | #1319 | #1532, #1495 |
| Photo and PDF messages in bubbles | `16:8`, `620:1577`, File field `132:4605` | audited, mismatch | › Conversation Thread | #1290 | #1513, #1495 |
| Strip while sending ("Uploading… N%") | `689:4475` | frame only | › Gaps | #1327 | #1529 |
| Message attachment loading, failed with retry, not found | `696:4913` | audited, mismatch | `thread/index.md` › Message attachment states | #1290 | #1539, #1624, #1630 |
| Message attachment not yet requested | `16:8` File field (decision on #1539) | no separate frame | not audited | #1290 | #1539 |
| System file picker | none | out of scope | — | #933 | none |

### Markdown reader

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Markdown Reader | `553:2574` | audited, mismatch | `thread/index.md` › Markdown Reader | #1291 | #1533 |
| Linked Markdown reader (`Routes.MARKDOWN_LINK`, `LinkedMarkdownReaderDestination`, opened by `ThreadNavigation.OpenLinkedMarkdown`) | `553:2574` | audited, mismatch | as Markdown Reader: it draws the same `MarkdownReaderScreen` | #1291 | #1533 |
| Reader notices (save failed, saved, open failed) | `696:5101` | audited, mismatch (changed design; open failed captured, the save arms share its host) | `thread/index.md` › Markdown Reader notice | #1291 | #1539, #1604 |
| Reader overflow menu | `675:5883` (decision on #1539) | no separate frame | not audited | #1291 | #1539 |

### Archive

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Archive, Channels tab | `18:2` | audited, mismatch | `list/index.md` › Archive | #1265 | #1487 |
| Archive, Discussions tab | none | gap | `list/index.md` › Gaps | #1265 | #1487 |
| Archive, empty Channels and Discussions | `673:3577`, `673:3621` | frame only | › Gaps | #1265 | #1504 |
| Restore snackbars | none | gap | #1504's comment | #1265 | #1504 |
| Loading ("Loading…") and load error ("Couldn't load archived discussions: …") | none | gap | #1504's second comment | #1265 | #1504 |

### Channel Info

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Channel Info, top of the sheet | `668:5355` (was `20:48`) | frame only | `list/index.md` › Channel Info Sheet, audited against the retired `20:48` | #1266 | #1488 |
| Channel Info, scrolled to Actions | `668:5460` | frame only | › Channel Info Sheet (Actions unverified) | #1266 | #1488 |
| System prompt editor while loading, unavailable, editing, saving or failed; MCP server rows with Reconnect and toggle | `668:5355`, `668:5460` (decision on #1539) | no separate frame | not audited | #1342, #1344 | #1539, #1488 |
| Delete confirmation | `673:3665` | frame only | `list/index.md` › Gaps | #227 | #1504 |

### Settings

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Settings / Notifications modal | `17:2` | audited, mismatch | `list/index.md` › Settings | #1239 | #1503 |

### Forms

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Edit host, keyboard closed and open | `533:2369` | audited, mismatch | `list/index.md` › Modal › Edit host | #1277 | #1489 |
| Edit host save and unpair failure messages | none | gap | #1504's comment | #1277 | #1504 |
| Edit host saving | none | gap | #1504's second comment | #1277 | #1504 |
| Unpair host confirmation | `671:5620` | frame only | `list/index.md` › Gaps | #745 | #1504, #1489 |
| Edit channel | `671:5415` | frame only | › Gaps | #667 | #1504 |
| Edit channel saving, archive failed, save failed, host unavailable | none | gap | #1504's second comment | #667 | #1504 |
| Edit chat | `671:5499` | frame only | › Gaps | #827 | #1504 |
| Edit chat saving, archive failed, save failed, host unavailable | none | gap | #1504's second comment | #827 | #1504 |
| Create channel | `671:5558` | frame only | › Gaps | #958 | #1504 |
| Create channel saving, create failed, prompt failed with the name locked, host unavailable | none | gap | #1504's second comment | #958 | #1504 |
| Rename | `671:5664` | frame only | › Gaps | #957 | #1504 |
| Save as channel | `671:5718` | frame only | › Gaps | #957 | #1504 |
| Save as channel saving, save failed, prompt failed with the name locked | none | gap | #1504's second comment | #957 | #1504 |

### Run configuration

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Run configuration (`StatusSheet`), Sonnet selected | `600:1694` | audited, mismatch | `thread/index.md` › Run configuration | #1195 | #1497, #1496 |
| Model or effort write pending ("Model · applying…", "Effort · applying…", rows disabled) | none | gap | #1539's comment | #1195 | #1539 |
| Permission write pending ("Permission · applying…", rows disabled) | none | gap | #1539's comment | #1195 | #1539 |
| Read-only: controls disabled while not writable or disconnected | none | gap | #1539's comment | #1195 | #1539 |
| Model menu unavailable or empty ("Model list unavailable", "This server published no selectable models.") | none | gap | #1539's comment | #1195 | #1539 |
| Truncated model menu ("N shown · M not listed") | none | gap | #1539's comment | #1195 | #1539 |
| Effort note and model-selection note captions | none | gap | #1539's comment | #1195 | #1539 |
| Permission mode unavailable; running model and context "Not reported yet" | none | gap | #1539's comment | #1195 | #1539 |

### Background tasks

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Populated, Capped, Empty, Never reported | `568:877`, `568:932`, `568:981`, `568:997` | audited, mismatch | `thread/index.md` › Background tasks | #1295, #1041, #1218 | #1496, #1534 |

### Questions and permissions

| Surface or state | Node | Status | Audit row | Owner | Linked issues |
|---|---|---|---|---|---|
| Questions · Unanswered | `636:3279` | audited, mismatch | `prompts/index.md` › Questions · Unanswered | #1305, #1299 | #1485; #1501 and #1484 fixed |
| Questions · Answers selected | `636:3540` | audited, mismatch | › Questions · Answers selected | #1305, #1299 | #1485; #1501 fixed |
| Questions · Other and keyboard | `636:3803` | audited, mismatch | › Questions · Other and keyboard | #1305 | #1485; #1501 and #1484 fixed |
| Questions · Compact 150 % | `636:4066` | audited, mismatch | › Questions · Compact text at 150% | #1305 | #1485; #1543 redrew the frame, #1501 fixed; Line-box and Cancel-height gaps accepted by decision, not routed (`prompts/index.md` › Compact frames at 150 %) |
| Permission · Safe default | `639:2242` | audited, mismatch | › Permission · Safe default | #1306, #1300, #1483 | #1509, #1485; #1501 fixed |
| Permission · Session grant offered, selected | `639:2451`, `639:2666` | audited, mismatch | › Permission · Session grant offered; › … selected | #1306, #818, #1483 | #1509, #1485; #1501 fixed |
| Permission · Confirm Allow once | `639:2882` | audited, mismatch | › Permission · Confirm Allow once (armed) | #1306, #451, #1483 | #1509, #1485; #1501 fixed |
| Trust · Safe default | `639:3099` | audited, mismatch | › Trust · Safe default | #1306, #1483 | #1509, #1485; #1501 fixed |
| Permission · Compact 150 % | `639:3308` | audited, mismatch | › Permission · Compact text at 150% | #1306, #1483 | #1485; #1543 redrew the frame, #1501 fixed; Line-box and Cancel-height gaps accepted by decision, not routed (`prompts/index.md` › Compact frames at 150 %) |
| Switch chats while prompts wait | `640:2437` | audited, unverified | › Switch chats while prompts wait (the list's waiting marks unjudged) | #1305, #1306, #1337, #1338 | #1507 |
| Refused answer, send failure, prompts while disconnected | `668:3054`, `668:3094`, `668:3169` | frame only | `prompts/index.md` › Gaps | #1340, #1305, #1321 | #1502 |

## Coverage check (#1434)

**Frames no audit compared.** On 2026-10-02 the Mobile page holds four sections drawn after the audits'
inspections: Channel Info `668:5355` and `668:5460` (#1488), List states `670:5299` (#1504), Prompt edge
states `668:3051` (#1502) and Thread states · #1529 `685:3991` (#1529). Each open issue's acceptance criteria
already require the capture with the shared harness and `scripts/design-compare.py`, so they are listed as
`frame only` above rather than captured here. The Components page's Thread notification states `Expanded`,
`Switch back pending` and `Switch back failed` were never captured; #1540 captures them. Every other Mobile
frame is audited above, retired, or listed under Outside the inventory.

**Reachable states with no reference.** This check found the queued message row, tool rows while running or
failed and nested sub-agent rows, message attachment loading and failure states, the empty thread, the
prompt-dismissal notice, the reader's overflow menu and notices, Channel Info's System prompt and MCP
interactive states, and the launch splash window, and routed them to #1539. Its design decision on 2026-10-02
drew the section Reachable states · #1539 `696:4676` and recorded the rest as needing no separate frame, so
those rows are now `frame only` or `no separate frame`, and #1539 owns their capture. The Run configuration
sheet's pending, read-only, menu-unavailable, truncated-menu, note and not-reported states, found after that
decision, have no frame either; a comment on #1539 routes them there as `gap` rows. Archive's loading and
load-error states and the form modals' saving, failure, host-unavailable and name-locked states have no frame
in List states `670:5299`, which draws each form at rest; a second comment on #1504 routes them there. The
Rename dialog has no saving or failure state. A sweep of `ui/` for `loading =` and `error =` bindings,
`UiState.Loading` and `UiState.Error` branches and `showSnackbar(` calls maps every hit to a row above or to
an unreachable or retired surface.

**Outside the inventory.** Platform-drawn surfaces are not the app's to match: the system file picker, the
camera permission dialog the scanner requests, and the notifications permission dialog `MainActivity` requests.
So are the pages and apps that app actions open: the browser for Channel Info's Memory plugin Install
(`MEMORY_PLUGIN_DOCS_URL`) and for the Welcome setup link (`SETUP_URL`), the Play Store opened from an
update-required host row, the system app-details page Scanner — Denied's Open settings opens
(`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`), the share chooser and the save-to-document picker. The Mobile page's section Launcher
icon `703:5001`, drawn after this inspection, is the home-screen icon rather than a surface reachable from the
`MainActivity` routes; #1546 owns it. The sections Play store assets `718:5001` (Play icon `718:5002`, Feature
graphic `718:5004`) and Play store screenshots `720:5001` (`720:7834` to `720:7842`), drawn on 2026-10-02, are
store-listing assets, not app surfaces, so they are outside the inventory too.

**Unreachable in source.** No new defect; listed so a later inventory does not count them as gaps.

| Surface | Evidence |
|---|---|
| Discussion list (`Routes.DISCUSSION_LIST`, `DiscussionListScreen` with its menu and promotion dialog) | the destination exists, but no `navigate` call targets `Routes.DISCUSSION_LIST` |
| About (`Routes.ABOUT`, `AboutScreen`) | the destination exists, but no `navigate` call targets `Routes.ABOUT` |
| Settings model, effort and theme pickers (`ModelPickerDialog`, `EffortPickerDialog`, `ThemePickerDialog`) | defined only; `SettingsScreen` takes no picker callback |
| `PasteCodeDialog` | defined only; the scanner's paste link opens the Pair Screen |
| `InterruptAffordance` | used only by its own previews |
| Footer Model, Effort and Permission menus (`FooterControl`) | the footer draws only Actions, the context label, the paperclip and the Status opener (`thread/index.md` › Removed controls) |
| `DebugBundleModal` (`ui/settings/DebugBundleDownload.kt`) | called only from its own preview; `SettingsScreen` never draws it |
| `CreateChatModal` and `MobileGateModal` (`ui/components/`) | `CreateChatModal` is called only from its own preview, and only it uses `MobileGateModal`; the Chats plus calls `createChat` directly |
| Empty channel list ("To pair a host…", `channel_list_empty`) | not a steady state: `ChannelListScreen` draws it only when no host is saved, the list is the start destination only while a host is paired, and the last unpair returns to Welcome (`returnToWelcome` on `lastHostUnpaired`) |

**Retired workspace surfaces.** Add workspace, Edit workspace and both workspace pickers are unreachable, with
the call chains in `list/index.md` › Retired workspace reachability. `Create folder` opens only from those.
Figma still holds `Workspace Picker Sheet` (`20:2`); it is excluded from the inventory. No retired surface is
reachable, so none links an issue.

## Linked issues

State of every issue linked above, read with `gh issue view` against `main` at `5dfa507a` on 2026-10-02.

| Issue | State | Subject |
|---|---|---|
| #1118 | open | Codex agent switch (frames `578:3248`, `578:3442`); halted, not counted toward parity |
| #1461 | closed | Pairing — Connecting loading button |
| #1462 | closed | Pair Screen glow |
| #1484 | closed | question actions under the footer, disabled Continue |
| #1485 | closed | composer footer band and compact context label |
| #1486 | closed | Channel List against `15:8` (family root; split into #1521 to #1525) |
| #1487 | open | Archive default tab, tapped fill, row pitch, large-text wrap |
| #1488 | open | Channel Info against `668:5355` and `668:5460` |
| #1489 | open | Edit host layout and unpair copy |
| #1493 | closed | capture the connection states against the updated `627:1740`, `627:4657`, `627:4910`; verdicts already in `thread/index.md` |
| #1494 | open | refusal row model names |
| #1495 | open | capture the attachment tile and file row against the updated `16:8` and `132:4605`; verdicts already in `thread/index.md` |
| #1496 | closed | background-task panel Close button and inset sheet |
| #1497 | open | Run configuration model rows, effort case, Permission section |
| #1498 | closed | "Workspace changed" delimiter |
| #1499 | closed | Offline pill (family root; the usage-limit pill split into #1519) |
| #1500 | open | capture the Thread states frames `674:5852`; verdicts already in `thread/index.md` |
| #1501 | closed | prompt spacing and button heights |
| #1502 | open | capture the prompt edge states `668:3051` |
| #1503 | open | Settings rows spacing |
| #1504 | open | capture the List states `670:5299` and list snackbars; Archive loading and error, form saving and failure states (comment) |
| #1506 | open | pair-code error text and ellipsis |
| #1507 | open | capture the list's waiting marks against `640:2440` |
| #1509 | closed | short stream starts under the header |
| #1510 | closed | status-bar icons |
| #1512 | open | session delimiter rule inset |
| #1513 | open | photo above text, photo bubble width |
| #1519 | open | usage-limit pill copy against `568:3139` |
| #1521 | closed | channel list top bar offset, host spacing and collapsed rows |
| #1522 | closed | channel tree canvas glow |
| #1523 | closed | edit pen only on the selected row; selected and pressed fills |
| #1524 | closed | conversation status dot colours |
| #1525 | open | decide the host-row edit pen |
| #1529 | open | thread states: capture `685:3991` |
| #1532 | closed | PDF tile while disconnected |
| #1533 | closed | reader list indent |
| #1534 | closed | background-task panel spacing |
| #1539 | open | frames or decisions for the uncovered states (filed by #1434); capture of `696:4676`; Run configuration states (comment) |
| #1540 | open | capture the refusal row's component states (filed by #1434) |
| #1541 | closed, not planned | re-verify app-wide parity (filed by #1434); retired by the owner, see [Verdict](#verdict) |
| #1543 | closed | redraw the compact prompt frames at Android's 150 % |
| #1545 | closed | launch splash against `701:5001` |
| #1546 | open | launcher icon against `703:5001` |

## Verdict

**Parity not reached.** 20 linked issues that count toward parity are open at `5dfa507a`:

- #1487, #1488, #1489, #1494, #1495, #1497, #1500, #1502, #1503, #1504
- #1506, #1507, #1512, #1513, #1519, #1525, #1529, #1539, #1540, #1546

Issue #1118 is also open, but its Codex agent switch frames draw a feature the app has not shipped, so they
do not count until #1118 ships.

**No re-verification follows.** #1434 filed #1541 to re-verify parity once these issues close, as its
acceptance criteria asked. Juhana closed #1541 as not planned on 2026-10-02: Mobile is done when it runs on
Juhana's phone through Play internal testing, and no new design audit rounds start. The open issues above
drain on their own, each through its own capture and comparison; with no app-wide re-check, they are the only
route to the `frame only` captures and the `gap` rows. This is an owner-decided deviation from the ticket's re-verification criterion.

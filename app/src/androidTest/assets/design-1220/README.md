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
not a mismatch; everything inside the app's window is compared.

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
  prompts), `questionBatch`, `backgroundTasks`, `backgroundTaskCount`, `pairingRejected`,
  `attachmentOffers`, `sessionFacts`, `contextUsage`. The fake repository still supplies messages, session
  settings, the model menu and the slash menu. The question batch, roster, count and repository flows
  apply to whichever thread opens. `hostModal` does not: the view model scopes it with
  `HostModalState.scopedTo`, so a prompt shows only when its `conversationId` is the open thread's. The thread marks
  its conversation viewed as production does. The override passes no app draft stores, so the view model
  reads `questionBatch` itself. At `97ee8d75` no thread code reads `observeAttachmentOffers`, so
  `attachmentOffers` is wired but nothing subscribes to it yet.
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

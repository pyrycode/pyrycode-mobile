# Design audit evidence (#1220)

The assembled app, captured through the real `MainActivity`, compared with the current Mobile page of
Figma file `g2HIq2UyPhslEoHRokQmHG`. #1430 built the harness and audited onboarding. Each later audit
(#1431, #1432, #1433) writes only its own subfolder and its own `index.md`. No audit declares app-wide
parity; #1434 owns that verdict.

- **App commit:** `main` at `2e1e2e46` (the commit #1430 branched from). Each subfolder's `index.md` records its own.
- **Figma inspection:** 2026-10-01 for `onboarding/`. Each subfolder records its own date; the page is the source of truth.
- **Theme:** the app's fixed dark theme only. Light mode, system theme and dynamic colour are out of scope.

## Folder layout

```
design-1220/
├── README.md                 # this file: layout, index format, harness and commands
├── smoke/                    # harness self-checks (compact large text, thread override, menu)
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
  camera and notification permission, pins dark theme with wallpaper colours off, controls the startup
  paired snapshot (`design.paired = true` opens the channel list), installs the Koin override, and
  restores everything. `launch()`, `capture(folder, name, figmaNode)`, `openKeyboard(node)`,
  `closeKeyboard()`, `openMenu(anchor)`, `insets()`.
- `DesignInputs` (`design.inputs`) — the Koin override, loaded over the app graph. Set these before or
  after launch; the open thread collects them:
  `connectionState`, `liveSessionEvents`, `hostModal` (permission and trust prompts), `questionBatch`,
  `backgroundTasks`, `backgroundTaskCount`, `pairingRejected`, `attachmentOffers`, `sessionFacts`,
  `contextUsage`. The fake repository still supplies messages, session settings, the model menu and the
  slash menu. `pairingStatus` is what the scanner's post-confirm wait observes (`null` keeps it
  connecting), and Confirm records to `savedPairings` instead of the Keystore. `thread` and `scanner`
  hold the view models the override built, for event-only states such as the scanner's denied state.
  Inputs ignore the conversation id: they apply to whichever thread opens. The override passes no app
  draft stores, so the view model reads `questionBatch` itself. At `2e1e2e46` no thread code reads
  `observeAttachmentOffers`, so `attachmentOffers` is wired but nothing subscribes to it yet.
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

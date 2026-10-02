# #1432 — design audit: thread, composer and thread status states

## Files read

- `app/src/androidTest/assets/design-1220/README.md` — folder layout, per-item index format, capture and compare commands. This audit writes only `design-1220/thread/`.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignInputs.kt` — the Koin override: `connectionState`, `liveSessionEvents`, `backgroundTasks`, `backgroundTaskCount`, `pairingRejected`, `contextUsage`, `sessionFacts`, `attachmentOffers`, and the published `thread` view model. It does not override `observeMessages`, `observeLiveRefusalEvents` or `observeAnnouncedModel`, so refusal and banner rows need this audit's own override (see Design).
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt` — `launch`, `capture(folder, name, figmaNode)`, `openKeyboard`, `openMenu`, `paired`.
- `app/src/androidTest/java/de/pyryco/mobile/design/OnboardingDesignCaptureTest.kt` — the analogue capture class: one method per path, states driven through inputs and the published view model.
- `app/src/main/java/de/pyryco/mobile/data/repository/FakeConversationRepository.kt` — the demo graph's seed channels (`seed-channel-pyrycode-mobile` carries a tool row and a streaming markdown reply), `setSessionSettingsReading`, `setModelMenu`.
- `app/src/main/java/de/pyryco/mobile/data/repository/ConversationRepository.kt` — `ThreadItem.Banner`, `ThreadItem.ModelRefusal`, `observeLiveRefusalEvents`, `observeAnnouncedModel`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt` — `switchBackOffer` needs a refusal offer and a settings reading that names a session.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`, `ThreadComposerFooter.kt`, `ThreadOverflowMenu.kt`, `ThreadTopOverlay.kt`, `BackgroundTaskPanel.kt`, `MarkdownReaderScreen.kt` — what each frame's state needs and where the overlay, sheet and menus open from.
- `docs/knowledge/features/development-verification.md` § "Compose evidence" — ATD black frames; evidence comes from the full `pixel8Api35` image with `requireRealSystemBars=true`.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Mobile page re-read 2026-10-02; every frame the ticket names is present, all 412×892: Conversation Thread `16:8`, Markdown Reader `553:2574`, Notification text `620:1577`, Connecting `627:1740`, Reconnecting `627:4657`, Offline `627:4910`, Session notice `627:5466`, Refusal switch back `646:4707`, Run configuration / Sonnet selected / Dark `600:1694`, Background tasks Populated `568:877`, Capped `568:932`, Empty `568:981`, Never reported `568:997`, Task count pill `568:3139`, Codex agent switch Switching `578:3248` and Switch confirm `578:3442`. Components page `347:5692` for message, tool and attachment variants. Exports from `get_screenshot` at 412×892. Fixed dark theme only.

## Context

#1220's audit, split by surface. #1430 built the harness and audited onboarding; this slice audits the thread surfaces of the assembled app. No production code changes; every mismatch is routed to its owning ticket or a scoped defect. No decision record.

## Design

Test-only. New files:

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` — `ViewportRule`, empty compose rule, `DesignCapture` with `paired = true`. Opens the seeded "Pyrycode Mobile" channel from the list. Methods, each capturing into `design-1220/thread/`:
  - `threadFramesAt412By892` — base thread (`16:8`) with a thinking turn (`TurnState.Thinking`) and `contextUsage`; connecting, reconnecting and offline (`connectionState`) for `627:*`; the usage-limit and pairing-error overlays (`pairingRejected`) as `16:8`'s variant; task count pill (`backgroundTaskCount`, `568:3139`).
  - `threadNoticesAt412By892` — a session-notice banner (`627:5466`) and the refusal row with and without the switch-back offer (`620:1577`, `646:4707`), through this class's own thread-items override.
  - `backgroundTaskPanelAt412By892` — populated, capped, empty and never-reported rosters (`568:877`, `568:932`, `568:981`, `568:997`).
  - `runConfigurationAt412By892` — the run-configuration sheet with a four-model menu and Sonnet selected (`600:1694`), asserting no "Default" option.
  - `markdownReaderAt412By892` — the reader route (`553:2574`) if the assembled app reaches it from the demo graph; otherwise listed as a gap.
  - `menusAndKeyboardAt412By892` — overflow menu and Actions menu open (menu-open check, no workspace action), composer keyboard-open.
  - `compactAt320By700` (`@Viewport("320x700", 1.5f)`) — thread, keyboard and menu at compact large text.
- **Thread-items override** (private to the test class): a Koin `viewModel` definition built like `DesignInputs`' own, over the same `design.inputs` flows, whose repository also appends this class's `extraItems` to `observeMessages` and serves `observeLiveRefusalEvents` from this class. It publishes the view model to `design.inputs.thread`. `DesignCapture` restores the app definition afterwards, as for `DesignInputs`.
- `app/src/androidTest/assets/design-1220/thread/` — captures, `.txt` metadata, `figma-<node>.png` exports, side-by-side and overlay from `scripts/design-compare.py`, the JUnit XML, and `index.md` in the README's per-item format with Gaps and a compact, keyboard and menu section.
- Agent switch (`578:3248`, `578:3442`): #1118 is open, so both frames are recorded as pending #1118 with no capture.

No in-flight overlap: every file is new and inside this audit's own folder or class.

## State and concurrency model

Test-only. Input flows are owned by `DesignInputs` and this class for one test; the thread view model collects them in `viewModelScope`, cancelled when the scenario closes.

## Error handling

A state that never appears fails its `waitUntil`; a missing frame is then recorded as a gap rather than captured wrong. Blank frames and synthetic bars fail `capture`.

## Testing strategy

Device-only (real activity, real pixels, IME, `wm` resizing), on the full image:

```
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

`compileDebugAndroidTestKotlin`, `lint`, `assembleDebug` and `spotlessCheck` locally. No rung-3 scenario: an audit, not an operator-facing flow.

## Open Questions

- Whether the markdown reader and composer attachment tiles are reachable in the demo graph without a production change. If not, they are gaps.
- Whether the base frame's thinking label holds long enough to capture after `TurnState.Thinking`.

## Revisions

### 2026-10-02 — methods, thread inputs and open questions

- **Methods.** The seven planned methods became six: `threadStatusFramesAt412By892`, `threadNoticeFramesAt412By892`, `backgroundTaskPanelAt412By892`, `runConfigurationAndReaderAt412By892`, `menusAndKeyboardAt412By892` and `compactAt320By700`. The reader shares a launch with Run configuration; coverage is unchanged.
- **Thread inputs.** The first captures showed no "Thinking…" label: the status band reads `observeTurnPhase`, which the live event alone does not set. The class's override now also serves `observeTurnPhase` and `observeUsageLimit` (for `568:3139`'s usage pill). The composer strip is staged through the view model's `addPickedAttachments`, with MediaStore PNGs so image thumbnails load; `@After` removes the staged files. The draft is set to the frames' "My message" on open, because the draft store outlives one test in the process. Task and model fixtures copy the frames' data, and model rows carry real resolved identifiers.
- **Compact comparisons.** Compact captures have no 320x700 frame, so they get no side-by-side or overlay; the index checks them for clipping, overlap and reachability.
- **Open questions resolved.** The markdown reader is reachable through `onOpenMarkdownLink` over the override's `readWorkspaceFile`, and composer tiles through `addPickedAttachments`; neither is a gap. The thinking label holds once the turn phase is held.

### 2026-10-02 — rework after review

Driven by the verifier's FAIL on PR #1491.

- **Figma moved.** The Mobile page gained **Thread states · 2026-10-02** (`674:5852`), and nine of the ticket's
  frames were redrawn from the shipped app (footer, Permission section, disabled footer when disconnected, file row
  colour). Every frame is re-exported and re-judged. The six new frames are captured: tool row `674:5853`, session
  delimiter `675:3682`, overflow menu `675:5883`, Actions menu `675:5938`, keyboard open `675:6160` and compact
  keyboard `676:3981`. The last gets a side-by-side and overlay; the other compact captures still have no frame.
- **Strict waits.** The `soft` helper is gone. Every frame state waits for its marker text and fails the run if it
  does not render, as `## Error handling` says. The "Default" check is a case-insensitive substring match and runs
  after "Sonnet" and "Manual approval" have rendered.
- **Thread inputs.** The override also serves `retrieveAttachment` with a generated photo, so `16:8`'s image bubble
  is captured; the capture waits for the view model's ready state and then for the decode's progress indicator to
  clear. The extra items add the clear and idle-evict delimiters. The notice and refusal frames stage the strip.
  Model rows support auto mode, as the frame's Sonnet does.
- **Compact coverage.** `compactAt320By700` adds the Offline pill alone, the usage pill with the refusal offer and
  strip, the task panel and Run configuration with Done asserted displayed. The compact keyboard runs last, because
  the focused composer's cursor handle is its own popup root and confuses `openMenu`'s root count.
- **Cleanup.** `@After` clears the draft and the fake's model menu and session-settings reading for the seeded
  channel, and deletes the staged and kept image files.
- **Reruns.** Two methods were rerun after fixture fixes; the index names both results files.

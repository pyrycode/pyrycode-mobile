# #1540: Capture the refusal row's expanded, switch-back pending and switch-back failed states

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `threadNoticeFramesAt412By892`
  (the refusal captures and how they seed the model menu and the session settings reading), `install()` (the
  repository override, which gains a held `setSessionSettings`), `refusal()`, `await`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModel.kt`: `onSwitchBack` marks the offer
  pending, then `sendSessionSettings` awaits `repository.setSessionSettings`; an `IllegalStateException` reverts the
  offer to failed and sends one `sessionSettingsErrors` signal. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ModelRefusalRow.kt`: the explanation's
  "<agent>: " attribution span, the pending button's alpha and the failed line. Read only.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `sessionSettingsErrors` shows the
  `session_settings_failed` snackbar. Read only.
- `app/src/androidTest/assets/design-1220/thread/index.md`: the **Notification text** and **Refusal switch back**
  entries (the per-item format) and the **Routed defects** line.
- `scripts/design-compare.py`: side-by-side and overlay output; it scales the Figma export to the app image's size,
  so the component is compared against a crop of the capture of the same size.

No in-flight branch touches these files.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=620-1576

Three 372 px wide states of the Thread notification component set `620:1576` on the Components page: Expanded
`620:1570` (372x108: title in body-medium on-surface-variant, the "Claude: " attributed explanation in body-medium
on-surface, "Hide details" in label-medium primary, 8 px gaps), Switch back pending `646:4694` (372x96: the switch
back offer with its outlined button at 38 % opacity) and Switch back failed `646:4700` (372x116: the armed button,
then "Could not change the model — try again." in body-small error). No bubble, border or icon.

## Change

A new method `refusalStateFramesAt412By892` in `ThreadDesignCaptureTest` opens the thread fixture as
`threadNoticeFramesAt412By892` does: attachment strip, context usage, the four-model menu seeded, then the PDF
message and a refusal whose explanation is the component's prose. It taps Show details, waits for "Hide details" and
captures `notification-expanded` (`620:1570`). It collapses the row, arms the offer with the Sonnet settings reading
and a session-scope refusal event, holds the override's `setSessionSettings` on a test-owned `CompletableDeferred`,
taps "Switch back to Opus", waits for the offer's pending state and captures `refusal-switch-back-pending`
(`646:4694`). It then completes the gate exceptionally with an `IllegalStateException`, waits for the failed line
and for the run-configuration snackbar, and captures `refusal-switch-back-failed` (`646:4700`).

The override's `setSessionSettings` awaits the gate only while one is set and otherwise delegates to the fake, so no
existing method changes behaviour. Nothing under `app/src/main/` changes.

Each capture is compared with its component by cropping the row from the capture (x 20 to 392, from the row's title
top, the component's height) and running `scripts/design-compare.py` on the crop and the component's 1x export.
Crops and exports are committed beside the captures as `<name>-row.png` and `figma-<node>.png`. Each state gets an
entry in `design-1220/thread/index.md` in the **Notification text** format; mismatches route to #875 (expanded) or
#1360 (switch back), or to a new scoped defect, and join **Routed defects**.

## Testing strategy

Device-only: the captures need real pixels on the `pixel8Api35` image with real system bars, as every
`ThreadDesignCaptureTest` method. Focused run:

```bash
./gradlew :app:pixel8Api35DebugAndroidTest --rerun \
  '-Pandroid.testInstrumentationRunnerArguments.class=de.pyryco.mobile.design.ThreadDesignCaptureTest#refusalStateFramesAt412By892' \
  -Pandroid.testInstrumentationRunnerArguments.requireRealSystemBars=true --console=plain
```

Its results XML is committed as `design-1220/thread/1540-results.xml`. Each state waits strictly for its marker, so a
state that never renders fails the run. `compileDebugAndroidTestKotlin`, `lint`, `assembleDebug` and
`spotlessCheck` locally. No rung-3 scenario: an audit, not an operator-facing flow.

## Revisions

- 2026-10-03: the first device run showed the run-configuration snackbar, which a failed switch-back also sends,
  covering the row and its failed line. The failed state now has two captures: `refusal-switch-back-failed-snackbar`
  taken while the snackbar shows, as evidence for the verdict, and `refusal-switch-back-failed`, the compared one,
  taken after the snackbar dismisses (waited for up to 15 s). The app's failed line sits lower than the component's,
  so the failed crop is 128 px tall and the export is padded to that height as `figma-646-4700-padded.png`.
- 2026-10-03: #875 and #1360 are closed, so the mismatches route to new scoped defects: #1614 for the expanded row
  and #1615 for the switch-back button and the failure snackbar.

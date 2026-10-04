# Match footer control spacing (#1659)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooter.kt`: `ThreadComposerFooter`, `FooterTextRow` and `FooterButton` separate first-row alignment from wrapped context.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: composer placement and `frameHeightWithTouchOverflow` retain bottom touch overflow without reserving visible padding.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadComposerFooterWidthTest.kt`: width, context wrapping and control separation assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameTest.kt`: pointer routing at the input/footer boundary.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt` and `DesignCapture.kt`: fake real-activity captures and actual IME visibility/inset checks.
- `docs/knowledge/features/thread-composer-footer.md`, `thread-composer-footer-testing.md` and `thread-screen.md`: preserve Actions/context presentation and existing picker/sheet wiring.
- `docs/knowledge/features/development-verification-compose-evidence.md` and `development-verification-emulator-evidence.md`: full-image nonblank pixels and real IME evidence are required; ATD metadata is insufficient.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957

Read design context and screenshot on 2026-10-04. Input footer uses 12/16/4/0 dp left/right/top/bottom padding. Its trailing controls are 60 × 16 dp: two centred 24 × 16 dp boxes separated by 12 dp, with the existing 11 × 12 dp paperclip and 16 × 16 dp tune assets in the primary colour. Actions uses bodySmall; the new context circle is outside this ticket.

## Change

Replace the trailing 32 dp boxes and 16 dp gap with 24 dp boxes and a 12 dp gap, centring icons in a distinct 16 dp visual band. Apply asymmetric footer padding inside the unchanged 20 dp composer gutter. Keep bottom touch overflow outside the visible frame and Compose's expanded touch targets, with real pointer tests proving routing at both icon centres and the input boundary. Align the visual band with the first Actions row while preserving context wrapping. Existing picker, sheet, state and callbacks remain unchanged; no new types, state, errors or dependencies.

Overlaps with #1283, #1631, #1642 and #1646 in ThreadScreen, and #1619/#1646 in ThreadDesignCaptureTest are independent; edits stay local and additive. Forecast: about 350 written lines across two production files, existing tests and this plan; three acceptance criteria, no signature migration.

## Testing strategy

Update width assertions and add exact visual geometry and coordinate-tap routing checks before production edits; observe the geometry failure. Run ThreadComposerFooterWidthTest, ThreadComposerFooterTest, ThreadComposerFooterLayoutTest and ThreadFrameTest. Keep the existing compact high-context wrapping assertion.

Add focused fake-thread captures at 412 × 892 dp/default font and 320 × 700 dp/1.5× font, before and with the keyboard. Device-only reason: real IME visibility/positive inset and nonblank full-emulator PNGs. Assert icon separation and visibility above the keyboard, capture footer geometry, and compare a fresh default dark footer crop at logical size with the Figma footer. Run the selected methods on pixel8Api35 with requireRealSystemBars=true, plus existing menu/keyboard capture coverage where applicable. This geometry-only change adds no daemon flow and needs no new real-Claude scenario.

Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck before handoff. Inspect executed test counts and retain capture evidence under app/src/androidTest/assets/1659-footer/.

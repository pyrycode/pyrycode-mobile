# Settle channel modal captures (#1862)

## Files read

- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt` — `walk`, `awaitModalFocus`, `awaitModalKeyboard`: reuse Edit host's dialog-window sequencing and preserve compact Archive reachability.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt` — `capture`: rejects synthetic bars and blank hardware screenshots; its sidecar reads Activity insets.
- `app/src/main/java/de/pyryco/mobile/ui/components/ChannelFormFields.kt` — `ChannelFormFields`: own name/prompt tags and initial focus, used unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/components/EditChannelModal.kt` — `EditChannelModal`: form, Mute and Archive, used unchanged.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/SaveAsChannelDialog.kt` — `SaveAsChannelDialog`: prefilled name and empty prompt, used unchanged.
- `docs/knowledge/features/mobile-modal.md`, `save-as-channel-dialog.md`, `channel-list-screen.md` — caller focus, 40 dp visible actions versus 48 dp touch targets, shared shell spacing from merged #1588.
- `docs/knowledge/features/development-verification-compose-evidence.md` — dialog-root IME reads, real Pixel 8 bars and fresh intermediates instead of stale outputs.
- `app/src/androidTest/assets/design-1220/list/index.md` — Edit/Save entries and Measuring note requiring documentation-stage correction.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=671-5415 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=671-5718, inspected 2026-10-08.

Edit channel / Default and Save as channel / Prefilled use the full-height dark editing shell, centered field block and Cancel/OK footer. Wells are 52/112 dp high with 12 dp between blocks, including the prompt's 20 dp label and 8 dp label gap (40 dp well-to-well). Footer surfaces are 40 dp high, separated by 20 dp, with 24 dp bottom clearance. Tokens: `onPrimaryFixed` shell, `onPrimaryContainer` labels, `onPrimary` at 41% wells, `onBackground` field text, `primary`/`onPrimary` footer; `titleLarge`, emphasized `labelLarge`, `bodyMedium`, emphasized `bodyLarge`.

## Change

Replace Edit's any-text-field wait with the modal's own label/title. For each form, identify its dialog root, wait for window focus, focus the tagged name field and observe visible IME with positive dialog inset. Preserve Archive reachability while the IME is open. Press Back, wait for invisible IME and zero dialog inset, then assert the same form/window and footer remain before capture. Production focus and layout do not change. Retain only fresh Edit/Save PNGs and sidecars, Figma exports, comparisons, dialog-inset evidence and XML under `app/src/androidTest/assets/design-1220/list/`, prefixed `1862-`. No overlapping in-flight branch touches the capture test. Forecast: under 300 written lines, zero new exported symbols/signature consumers, three criteria and no state-machine branches.

## Testing strategy

Test first: add keyboard-closed assertions to the existing walk and demonstrate failure before sequencing changes. Device-only reason: real IME/window focus, hardware framebuffer and system bars. Run both `ListDesignCaptureTest` walks on full configured `pixel8Api35` with `requireRealSystemBars=true`; retain fresh XML and executed/passed/failed/skipped counts confirming `listFramesAt412By892`. Compare only the requested two default frames, separating real status/navigation insets from frame coordinates and visible footer surfaces from touch targets. Spacing/sizes tolerate 2 dp; colours/type must match frame roles. File a focused follow-up for remaining mismatch instead of editing production layout. Run lint, assembly, Android-test compilation and formatting, then after the final main merge run the complete unit/shared suite, assembly and `scripts/pre-verify.py --gradle`. No real-Claude scenario: capture sequencing only.

## Documentation handoff

Pending documentation stage: `app/src/androidTest/assets/design-1220/list/index.md`, Measuring note — sidecars do not always report IME bottom zero; image/inset disagreement can arise from unsettled timing. Update only Edit channel and Save as channel entries with fresh run provenance, measured field/footer comparisons and any follow-up links. Builder retains measurements alongside artifacts; leaves this documentation file unchanged.

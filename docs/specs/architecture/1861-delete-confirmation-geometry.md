# #1861 — Delete confirmation geometry

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `DeleteConfirmationDialog` and `frameHeightWithTouchOverflow`; keep screen event routing and reuse invisible touch overflow.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreenChannelInfoTest.kt`: existing Delete/Cancel callback assertions.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/thread/ThreadViewModelTest.kt`: Delete closes Channel Info before confirmation; dismissal does not reopen it; confirmation deletes once and navigates back.
- `app/src/androidTest/java/de/pyryco/mobile/design/ListDesignCaptureTest.kt`: `walk` opens Default Delete through Channel Info.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: hardware capture requires nonblank pixels and real bars.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt`: existing headlineSmall, bodyMedium and labelLarge roles.
- `docs/knowledge/features/thread-screen.md`, `channel-info-sheet.md`, `mobile-modal.md`: caller-owned dismissal, theme roles and visible geometry versus touch regions; current code supersedes the older still-open-sheet description.
- `docs/knowledge/features/development-verification-gates.md`, `development-verification-compose-evidence.md`: dialog window sizing needs Robolectric qualifiers, native text measurement and real-bar device evidence.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=673-3665

Inspected design context and screenshot on 2026-10-07. A 316×220 dp rounded alert surface sits at (48,312) in the screen area. It has 24 dp padding, 28 dp corners, a 16 dp title/body gap, 24 dp body/actions gap and 8 dp action gap. Surface uses `surfaceContainerHigh` (#272A2F), title `onSurface` (#E0E2E8) / headlineSmall (24/32), body `onSurfaceVariant` (#C2C7CF) / bodyMedium (14/20), and actions `primary` (#9DCBFC) / labelLarge (14/20). Actions have 12/10 dp horizontal/vertical padding, with 40 dp visible height. No icons or image assets occur in the dialog.

## Change

Replace the convenience AlertDialog in `DeleteConfirmationDialog` with a caller-controlled Dialog and Material Surface using `MaterialTheme.shapes.extraLarge`, bounded to 316 dp with small-screen horizontal clearance. Compose the title, body and right-aligned TextButtons with the frame's gaps and styles. Reuse `frameHeightWithTouchOverflow` to let the 48 dp action targets extend 4 dp into adjacent blank space while reserving the 40 dp visible action row. Text and surface height grow naturally when content wraps; a scrolling content column keeps constrained content reachable. Cancel, Back and outside dismissal continue to call onDismiss; Delete calls onConfirm. No ViewModel, wire, repository, state or logging contract changes.

Overlap: #1827 edits separate message-rendering blocks in ThreadScreen; keep this edit local and additive.

## Testing strategy

Write geometry assertions first and observe failure against the existing dialog. Shared native-rendering tests check the 316×220 reference, 24/16/24/8 spacing, 48 dp touch bounds, pointer action routing, wrapped-content growth and readable/reachable actions at 320×700 / 1.5× text. Existing ThreadScreenChannelInfoTest and focused ThreadViewModel Delete tests preserve dismissal/deletion contracts. Back and outside dismissal use the real dialog window in focused device checks if Robolectric cannot dispatch them.

Run `ListDesignCaptureTest.listFramesAt412By892` on full pixel8Api35 with requireRealSystemBars=true, retaining only Default Delete's fresh capture, sidecar, Figma comparison and counted XML under `app/src/androidTest/assets/design-1220/list/`. This is device-only because hardware pixels and real system bars are required. Add geometry and Channel Info absence assertions at the Delete point of the existing walk; other modal states are not audited. The existing rung-3 `InteractiveStreamE2ETest.interactiveTurn_deleteConversation_removesFromListAndClosesThread` remains dispatcher-owned full-live-suite acceptance, requiring fresh counts and confirmation of that method.

Focused lint/build/Android-test compilation and forced Spotless checks precede the final full unit/shared suite, assembleDebug and pre-verify after merging main. Forecast: approximately 450–650 written lines, no new exported types, no simultaneous consumer updates, three acceptance criteria and no new error branches; within the sizing limits.

## Documentation handoff

Pending for the documentation stage: update `app/src/androidTest/assets/design-1220/list/index.md`, “Delete confirmation — 673:3665”, with the fresh measurements, tolerance verdict and run provenance. Builder retains the evidence and reports measurements in the PR; documentation does not run captures.

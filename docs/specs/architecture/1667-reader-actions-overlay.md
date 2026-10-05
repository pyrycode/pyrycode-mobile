# Reader Actions overlay (#1667)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen`, `MarkdownReaderTopBar`, `MarkdownReaderMenu` and `RefreshableMarkdownReader`; keep current document-action callbacks and refresh lifecycle.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/OptionsOverlay.kt`: `OptionsOverlay`, `OptionsColumn` and `AnchoredOptions`; reuse Actions rows, Below geometry, scroll capacity and consumed dismissal.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt`: `ThreadScreen` translates live window bounds by the full-size layer origin.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt`: `ThreadOverflowMenu` builds client-owned action rows and dismisses before dispatch.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt`: existing copy, open, save and refresh assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt`: existing geometry and physical pointer isolation assertions.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderCaptureTest.kt`: reference and compact enlarged-text captures.
- `docs/knowledge/features/markdown-reader-screen.md` and `markdown-reader-menu.md`: current document, copy, refresh and capture contracts.
- `docs/knowledge/features/options-overlay.md`: layer-relative anchors and shared palette; semantic visibility alone does not prove compact reachability.
- `docs/knowledge/features/development-verification-compose-evidence.md` and `development-verification-gates.md`: native text measurement, pointer controls and test count evidence.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1958

Inspected through Figma MCP on 2026-10-05. Structured context shows the 6dp rounded surface, 2dp vertical column inset, 12dp horizontal/6dp vertical row insets and M3 bodySmall labels. Its screenshot is 1 × 1 pixels, so the existing composer Actions overlay is the appearance reference, as the ticket requires; no exact open-menu Figma pixel match is claimed. Reuse its static-dark onPrimary/onPrimaryFixed/primary roles and established light surfaceContainerLowest/primary roles, with no selected indicator or subset caption.

## Change

Replace the reader's Material dropdown with `OptionsOverlay(actions = true, placement = Below, selectedValue = "", notListed = 0)`. Hoist remembered menu visibility and live overflow-button window bounds into `MarkdownReaderScreen`, tracking its full-size Box origin and translating the anchor before mounting the overlay last, above content, chrome and snackbar. `MarkdownReaderTopBar` receives open/anchor callbacks instead of document actions. Keep six client-owned resource/callback pairs in the existing order; selection closes before invoking the latest callback. Existing copy/open/save closures continue to use the displayed document after refresh. The shared overlay supplies Back dismissal, outside-tap consumption, 4dp below-anchor gap, horizontal alignment, 8dp edge clamps and scrolling. Add a defaulted column modifier to the shared overlay so `markdown-reader-menu` identifies the actual options column without altering existing callers. Menu visibility survives recomposition, but is not saved across configuration changes. No document-processing, transport, refresh jobs or resources change.

Overlap: #1747 changes reader error notices and their tests; preserve local additive edits around menu wiring and independent test methods.

Sizing: one presentation deliverable, five acceptance criteria, approximately 450–600 total written lines including tests and plan, no new exported type, no required existing consumer migration, and no new error branch. The existing overlay callers retain defaults.

## Testing strategy

Write placement and Actions semantics regressions first and observe failure on the old dropdown. Run `MarkdownReaderScreenTest`, `MarkdownReaderDesignTest`, `OptionsOverlayColoursTest` and existing composer/header overlay coverage. Add reader light/dark row palette and typography checks, exact below-button placement and horizontal clamp checks with a nonzero host origin, live-anchor/recomposition checks, compact enlarged-text last-row scrolling and physical pointer action routing. Outside taps over Back and body links must dismiss without navigation or link activation, with positive taps after dismissal; system Back must dismiss without calling reader Back. Retain all six action/refresh assertions and prove refreshed text is used by copies, open and save. Adapt `MarkdownReaderCaptureTest` to capture the same-window menu and retain reference and compact menu captures; it remains device-only because it uses display shell overrides and real exported screenshots. Run that focused class on the managed device and read executed/failed/skipped counts. Run lint, assembleDebug, compileDebugAndroidTestKotlin, spotlessApply and forced spotlessCheck.

The existing rung-3 `InteractiveStreamE2ETest.interactiveTurn_markdownLink_opensLiveNoteInReader` already reaches Refresh; no new live scenario or focused live run. Dispatcher owns a fresh full live suite after verification (`Live tests: all`); hand off executed/failed/skipped counts and explicit named-method pass confirmation as pending, without claiming live success.

## Documentation handoff

Pending for the documentation stage:

- `app/src/androidTest/assets/design-1220/README.md`, Reader overflow menu row: cite `533:1958` and Juhana's #1667 decision, superseding `675:5883` while retaining the no-separate-reader-frame record.
- `docs/knowledge/features/markdown-reader-menu.md`, presentation, and `markdown-reader-screen.md`, menu/capture references: describe the shared Below Actions overlay and current captures.
- `docs/knowledge/features/options-overlay.md`, callers/testing: include the reader's column tag, Actions appearance and placement coverage.
- Record dispatcher-produced full live evidence, including counts and confirmation that `interactiveTurn_markdownLink_opensLiveNoteInReader` ran and passed. Documentation does not produce live evidence.

# Run configuration shared modal (#1195)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/StatusSheet.kt` → `StatusSheet`, `StatusSheetContent`, `ModelSection`, `EffortChipRow`, `RunningModelSection`, `ContextWindowSection`, `PermissionSection` — existing rendering and reported-state contracts.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen` — owns visibility, callbacks, and agent-switch confirmation; no new host state is needed.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileDismissModal`, `MobileModalShell` — shared full-height shell, pinned footer, close control, safe insets and scroll behaviour.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StatusSheetTest.kt` → `StatusSheetTest` — published-menu, pending, permission and reported-reading proof.
- `docs/knowledge/features/status-sheet.md` → Shape and What it does — existing behavioural contract and documentation handoff.
- `docs/knowledge/features/mobile-modal.md` → caller contract — single filled dismissal action.
- `docs/knowledge/features/development-verification.md` → Where a screen test goes and Compose evidence — Robolectric versus device capture requirements.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=598-1565; current dark state `600:1694`, shared modal `533:2369`, component `489:1942` (inspected 2026-09-28).

The 412 × 892 dark frame is a full-height navy modal with 28 dp side and 24 dp top insets, a title-large header, circular close control and thin separator. Content is top aligned with 28 dp between Model, Effort, Running model and Context window; Model has vertically stacked 20 dp tertiary radio circles and Effort uses two columns. A single primary Done button sits at bottom right. The frame omits Permission, pending, rejection, unavailable and long-list states; production retains them in the same visual system.

## Context

Run configuration still uses `ModalBottomSheet`, while the other forms use `MobileModalShell`. This is a presentation change. The published choices, confirmed or pending selection, reported readings and callbacks remain sourced by `ThreadScreen`.

## Design

- `StatusSheet` delegates its presentation to `MobileDismissModal(title = "Run configuration", actionLabel = "Done")`. Close, Done and Back call `onDismiss` and never write. The host's existing one-write callbacks and agent-switch confirmation remain intact.
- `StatusSheetContent` renders only the body. Group sections in design order: Model, Effort, Running model, Context window, then the production Permission section. Use the modal's top-aligned scroll area and pinned footer; no nested whole-body scroll. Each group owns its label, rows and supporting notes.
- Preserve `ThreadModelChoice` order and details, `ThreadEffortChoice` order, exact raw write values, inherited/unknown selection notes and partial-list count. No hardcoded design examples or Default row.
- Model rows and two-column Effort rows use shared radio-row presentation: a visible 20 dp tertiary ring, body-medium label and 12 dp gap, with a row-level selectable target of at least 48 dp. The visual circle has no Material `RadioButton` internal padding. The groups use `selectableGroup`; selection state and enabled state stay on the row. On narrow widths labels wrap rather than disappear.
- Keep Running model and Context window separate, using the existing data and honest unavailable text. Keep effort notes and Permission pending/choice states below the designed groups. Styling uses `MaterialTheme` tokens and the shared shell's colors.

## State and concurrency model

The screen retains its `sheetVisible` composition flag. `MobileDismissModal` owns no settings job. `ThreadScreen` sends one existing action per selection and closes the modal; the ViewModel's existing pending state and confirmation path remain unchanged. Back/Close/Done only change visibility. No new flow, dispatcher or job is introduced.

## Error handling

An unavailable menu, empty menu, unmatched selection, partial menu, read-only session, pending write, reported-reading absence and Permission absence keep their existing explanatory states. Rejection and agent-switch confirmation remain owned by the existing host/ViewModel. The Figma frame supplies no visual variants for these; apply modal typography and spacing without changing their meaning.

## Testing strategy

- Extend the shared `StatusSheetTest` with a modal-shell dismissal test and radio semantics/callback assertions. Run it RED before production edits, then GREEN with `testDebugUnitTest --tests`.
- Verify long lists, compact width and enlarged text with a shared Compose test using scroll-to on the final row and Done. Existing tests continue proving unavailable, pending, read-only, partial-list, permission and reported-reading cases.
- Add a device-only capture test for real pixels at 412 × 892 and compact width; compare a current emulator PNG with Figma node `600:1694` and attach a labelled overlay/difference to the PR. Check relevant keyboard/menu states for clipping. Real pixels require `androidTest`.
- Run scoped unit tests, lint, assembleDebug and androidTest compilation. The dispatcher owns aggregate and live gates. This UI presentation change has no new daemon-facing operator action, so no new real-Claude scenario is required.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/status-sheet.md` under **Shape** and **What it does**, plus its hosting/tests companion `docs/knowledge/features/status-sheet-hosting-tests-and-edge-cases.md`, to describe the shared modal, radio layout, dismissal and retained Permission section. Record the missing Figma states and the emulator/Figma visual comparison there.

## Open questions

- Whether the existing `MobileDismissModal` can keep the Done action visible with the longest published menus at enlarged text. Resolve by a focused layout test and record any adjustment below.

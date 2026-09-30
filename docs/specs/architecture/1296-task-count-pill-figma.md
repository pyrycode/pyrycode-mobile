# 1296 — Task-count pill Figma alignment

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `ThreadScreen` — status-band layout, live count, and panel opening.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt` → `NoticePill` — existing 6 dp shape, theme colours, body-small type, and 8/4 dp padding; shared callers retain this contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator` — the adjacent reading measures 24 dp high at normal text scale.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt` → `figmaVariant_anchorsTaskPillAtTheInputAreasTopRight`, count and tap tests — existing focused regression surface.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameCaptureTest.kt` → `referenceFrame_emptyPopulatedTaskAndMenu` — real-device capture fixture and compact-text checks.
- `docs/knowledge/features/thread-screen.md` § What it does — 412 × 892 thread frame and composer positioning.
- `docs/knowledge/features/shared-typography.md` § Roles — `bodySmall` is Roboto 12/16 sp, 0.4 sp tracking.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes — shared Compose test and real-device capture conventions.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-3139

Inspected on 2026-09-30: frame `568:3139`, status area `I568:3155;111:3525`, and pill `568:3162`. The dark 412 × 892 frame places a 104 × 24 dp, 6 dp rounded primary-container pill at (288, 696), flush with the 20 dp right gutter. Its on-primary-container `bodySmall` label has 8 dp horizontal and 4 dp vertical padding; the thinking reading sits left in the same 24 dp band. No separate Figma state depicts the pill alone, compact width, enlarged text, or an open keyboard/menu.

## Change

Keep `NoticePill`, plural resources, and the existing panel callback. In `ThreadStatusArea`, let the adjacent 24 dp reading and pill set the band height naturally; the current 28 dp minimum raises the pill and composer by 4 dp in the combined state. Adjust only the local band layout needed to match the reference. Strengthen `TaskCountPillTest` to measure the pill surface at the Figma position and size, verify the adjacent and alone states, live count and zero transitions, and preserve the panel action at compact width and enlarged text. Extend the existing capture fixture for a labelled real-emulator 412 × 892 comparison.

## Testing strategy

- Run `TaskCountPillTest` red then green under Robolectric native graphics, plus affected frame, overflow, and attachment coverage.
- Run the focused shared capture test on the managed API 33 emulator and inspect its fresh XML and nonblank PNG; compare it with the current Figma render at the same logical viewport and retain a labelled comparison under `app/src/androidTest/assets/`.
- Run `lint`, `assembleDebug`, Android-test Kotlin compilation, and forced `spotlessCheck`. The dispatcher runs the full suite and device gate.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen.md` § What it does and the status-band description to record the corrected task-pill geometry and link the comparison artifact.

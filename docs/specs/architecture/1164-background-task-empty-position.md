# Background-task empty position (#1164)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` — `BackgroundTaskPanel`, `EmptyReading` and the empty/unreported previews: current centering and ring layout.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` — `MobileReadOnlyModal`, `MobileModalShell`: pinned chrome, compact scrolling and minimum content height.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt` — existing reading, partial-notice and dismissal coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt` — `show`: constrained modal test pattern.
- `docs/knowledge/features/mobile-modal.md` — Layout and theme / Callers: preserve shared chrome, footer Close and compact scrolling.
- `docs/knowledge/features/development-verification.md` — Where a screen test goes / Compose evidence: shared tests, forced viewport size and native graphics.
- `app/build.gradle.kts` and `gradle/libs.versions.toml` — existing shared Compose/Robolectric test support; no dependencies needed.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-981 (Empty); https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-997 (Never reported).

Both inspected frames place a horizontally centered 32dp ring 160dp below the content start, with 12dp gaps before the title and supporting text. Preserve the existing solid/dashed ring, titleMedium/bodyMedium typography and outline/onSurface/onSurfaceVariant roles. The shared shell's larger accessible header controls and extra footer Close remain deliberate existing differences, so the invariant is the content-relative inset rather than the frame's absolute 249dp coordinate.

## Change

Add 160dp top padding to `EmptyReading` and let the existing trailing weighted spacer absorb slack for every roster state, rather than only populated lists. Padding stays inside the shell's scrollable content; its pinned and compact modes continue to own scrolling. Keep the partial notice before the empty reading when present. Add an optional standard `Modifier` to `BackgroundTaskPanel`, forwarded to the modal, to allow bounded viewport tests. No existing caller needs updating; no new state, jobs, error modes or logging events are introduced. Existing content-free open/dismiss logs remain applicable.

Size check: one deliverable, one production file, fewer than 25 production lines and approximately 200 total written lines including tests and this plan; zero new exported types, zero consumer updates, two acceptance criteria, zero error branches. This agrees with the ticket's XS estimate. Refreshed remote feature branches have no overlap with the planned files.

## Testing strategy

Add `BackgroundTaskPanelLayoutTest` under sharedTest. First observe the content-offset assertions fail against current centering. At 412dp width prove both readings start after 160dp plus the existing 32dp ring and 12dp gap; vary height to reject viewport-dependent centering. At a shorter pinned height and a compact height prove the header, supporting text and both Close actions are reachable and dismissal still works. Run existing `BackgroundTaskPanelTest` alongside the new class, then Spotless, lint, assembleDebug and compileDebugAndroidTestKotlin. Compare the rendered empty states with the inspected Figma screenshots. Existing light/dark previews cover both states. This is a positional retune of an existing flow; it adds no daemon interaction or new operator flow requiring a new live scenario.

## Documentation handoff

No documentation-only acceptance criteria or explicit handoff appears in the ticket. Pending for the documentation stage: update `docs/knowledge/features/mobile-modal.md`, “Callers” / `BackgroundTaskPanel`, to describe the 160dp empty-state inset and retained short-height scrolling.

## Revisions

- 2026-09-27: The width assertion showed that `ForcedSize` outside a dialog leaves Robolectric's window at 320dp. The native-graphics layout fixture sets a 412dp Robolectric window (no editable fields) and supplies `requiredSize` through the planned modifier. The content-relative offset and both short-height modes pass. Native light/dark renders place the ring at 265dp from the shell top: the reference's 249dp plus the retained accessible header geometry, as anticipated above.

# Populated Archive rows (#1159)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt` — `ArchiveRow` owns the avatar, text gutter and restore glyph; its light/dark previews already cover the row.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt` — `LoadedBody` shares the row across both tabs; `ArchivedDiscussionsScreen` owns host identity and success feedback.
- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModel.kt` — `onEvent` restores the selected id and emits feedback, unchanged.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchiveNavigationTest.kt` — `restoreReachesOnlyTheOwnerAndLeavesTheOtherHostsMatchingIdArchived` already proves removal, snackbar and host isolation through the production graph.
- `app/src/test/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsViewModelTest.kt` — existing partition, restore and effect assertions.
- `app/src/androidTest/java/de/pyryco/mobile/e2e/InteractiveStreamE2ETest.kt` — `interactiveTurn_twoHostsDefaultsAndArchive_stayPerHost` supplies existing daemon archive/restore coverage.
- `app/src/main/res/drawable/ic_modal_close.xml` — existing exported Figma vector pattern, tinted by Compose.
- `gradle/libs.versions.toml`, `app/build.gradle.kts` — existing icon dependencies; no dependency addition needed.
- `docs/knowledge/features/archived-discussions-screen.md` and `archived-discussions-screen-how-it-works.md` — the avatar and Refresh were historical choices intentionally superseded here; preserve newer host ownership.
- `docs/knowledge/features/development-verification.md` — shared screen tests run on Robolectric; native graphics for visual capture.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Read design context and the frame screenshot, plus the restore glyph screenshot at `18:24`. Rows `18:19`, `18:27` and `18:35` contain an avatar-free text column at 16dp, 12dp vertical padding, a 2dp text gap, and a trailing 40dp button with a 22dp counter-clockwise outline arrow. Retain M3 `titleMedium`/`bodySmall`, `onSurface`/`onSurfaceVariant`, subtitle alpha 0.75, and the 12dp gap before the button.

## Change

Remove `ConversationAvatar` from `ArchiveRow`. Replace `Icons.Filled.Refresh` with a local drawable converted from the supplied Figma arrow SVG, retaining its 22-unit geometry and using the existing theme tint. No signature, state, coroutine, error or logging changes: the callback, accessibility label, host-owner line, tabs, timestamps and screen insets remain intact. The existing screen has deliberate newer host identity beyond the Figma frame, preserved by this ticket.

Size check: one deliverable, one production Kotlin file plus one drawable, about 150 total written lines including plan and test, no exported declarations, no changed consumers, two acceptance criteria, no new error branches. The issue estimates six production lines in one file; the exported asset and focused proof account for the extra files. No overlapping in-flight feature branch touches the row or existing navigation test (checked after fetching origin). Codegraph context did not locate the row and its callees query was empty; source reads filled that index gap.

## Testing strategy

Add a shared `ArchivedDiscussionsLayoutTest`: switch between populated tabs and assert title/subtitle start at 16dp, trailing icon geometry, and preserved host label. Run it before implementation to observe the existing 68dp inset fail, then after the change. Use native graphics and capture the requested screen to a temporary artifact for visual comparison in light/dark. Reuse `ArchiveNavigationTest` and `ArchivedDiscussionsViewModelTest` for selected restore, feedback and ownership. Run focused unit tests, Spotless, lint, debug assembly and Android-test compilation. This is a visual retune of an existing flow; the existing rung-3 archive scenario remains the live proof, with full-suite/device/live execution owned by the dispatcher.

## Documentation handoff

The ticket specifies no documentation-only acceptance criterion. Pending for the documentation stage: update `docs/knowledge/features/archived-discussions-screen-how-it-works.md`, section `ArchiveRow`, and `docs/knowledge/features/archived-discussions-screen.md`, manual verification step 2, to describe avatar-free rows and the exported counter-clockwise restore arrow.

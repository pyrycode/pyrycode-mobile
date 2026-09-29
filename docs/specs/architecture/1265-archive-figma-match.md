# 1265 — Archive Figma match

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsScreen.kt` → `ArchivedDiscussionsScreen`, `LoadedBody`: header, host line, tabs and list presentation.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ArchiveRow.kt` → `ArchiveRow`: text, restore control and row geometry.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`, `Color.kt` → `PyrycodeMobileTheme`: fixed dark Material roles already supplied by #1225 and #1226.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchivedDiscussionsLayoutTest.kt` → `assertPopulatedTabs`: existing geometry and both-tab coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/settings/ArchiveNavigationTest.kt` → `restoreReachesOnlyTheOwnerAndLeavesTheOtherHostsMatchingIdArchived`: retained host and restore behavior.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/list/SharedDarkColourCaptureTest.kt` → `sidebarAt412By892`: real pixel capture pattern.
- `docs/knowledge/features/archived-discussions-screen.md` → archive behavior, previews and reference history; `docs/knowledge/features/development-verification.md` → shared versus device-only proof.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=18-2

Inspected 2026-09-29. Node `18:2` is a 412 × 892 static-dark Channels state: a 64dp header with 24dp back glyph and M3 `titleLarge`, a 48dp two-tab strip with `labelLarge` and 2dp primary indicator, then three 66dp rows with `titleMedium`, `bodySmall`, 22dp restore glyph and 16dp side insets. `Schemes/Background`, `On Surface`, `On Surface Variant`, `Outline Variant` and `Primary` back a blue radial glow; the frame has no host line. No Discussions, empty, or enlarged-text reference state is present in this node, so those states retain the established semantic content and are checked for layout and actions, not claimed as pixel matches.

## Context

Archive currently uses default M3 top bar and secondary tabs against a flat Scaffold surface. This misses the reference's glow and exact tab/header geometry. Its host line is needed for a multi-host archive even though the reference omits it.

## Design

Keep `ArchivedDiscussionsScreen(state, onEvent, effects, hostName)` and `ArchiveRow` contracts. Draw the static-dark radial background behind the whole screen while retaining Material scheme surfaces for other theme modes. Use a local 64dp header and 48dp tab strip with a 2dp selected rule and role-based text colours. Place the host label below the header, before tabs, with a bounded single line; it is an explicit adaptation for the selected host. Preserve list partition, counts, empty copy, events, snackbar effects and row fallback names. Keep 16dp row gutters and the existing exported restore vector; allow text to ellipsize within the weighted column so it cannot overlap the fixed restore target. Update the screen previews to static dark populated Channels and Discussions plus both empty states.

## State + concurrency model

No new state or jobs. `ArchivedDiscussionsViewModel` still owns the selected tab and host-scoped repository. The existing `LaunchedEffect` collects restore effects for snackbar feedback and cancels with composition.

## Error handling

Loading and error copy remain under the same header and host context. Restore errors continue through the existing payload-free failure snackbar.

## Testing strategy

- Add a failing shared Compose layout assertion for the 64dp header, 48dp tabs, selected indicator, row placement and long-name/control separation; retain the existing two-tab and host-navigation assertions.
- Add a device-only capture test because actual pixels are required. Capture static-dark Channels and Discussions at 412 × 892, each tab's empty state, and a compact 280 × 400 enlarged-text case on API 33. Save device PNGs, matching Figma render, labelled comparison or difference image, context and focused XML under `app/src/androidTest/assets/archive-1265/`. Note reference states that cannot be rendered from Figma.
- Run focused shared tests, focused device test, `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and `spotlessApply`. No new live-Claude scenario: this changes presentation of an existing operator flow whose archive/restore scenario already exists.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/archived-discussions-screen.md` sections “Configuration / usage”, “Edge cases / limitations” and “Previews” with the current dark visual treatment, host-label adaptation, reference gaps and capture evidence.

## Open questions

- Can the Figma render be downloaded into the worktree for a durable comparison? If the sandbox blocks its short-lived URL, use the inline render and record the exact artifact gap without claiming a pixel comparison.

## Revisions

- 2026-09-29: The Figma render was saved through the inline screenshot response, resolving the artifact question. The first real device comparison showed a dark inherited back glyph and a broad glow; the implementation now supplies the `onSurface` tint and a tighter role-based radial gradient. The 4dp list top inset aligns the reference's first row.
- 2026-09-29: The reference labels weeks and months while shared `formatRelativeTime` switches to calendar dates after seven days. `ArchiveRow` now uses an Archive-local elapsed weeks/months formatter, still based on the existing `lastUsedAt` model field. A failing shared screen test preceded that change. The compact device capture exposed a misaligned selected indicator when the other tab wrapped; one shared indicator row now keeps the underline at the strip's bottom.
- 2026-09-29 verifier rework: `CenteredText` inherited a dark default after the transparent Scaffold change. It now uses `onSurface`; a dark pixel assertion covers empty, loading and error copy. Header, tab and indicator bounds have stable test tags and assertions in both shared and compact device tests. Refresh the device captures after this color correction.

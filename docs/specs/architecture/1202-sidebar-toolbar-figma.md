# #1202 — Figma sidebar toolbar and shell

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreen.kt` → `ChannelListScreen`, `ChannelListTopBar`, `ChannelListBarEntry`, `sidebarRuleColor`: current panel fill, fixed bar, icon targets, and rule.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/list/ChannelListScreenTest.kt` → `assertToolbarGeometry`, `tallTree_reachesItsLastRowInOneScrollContainer`: current geometry and scroll behaviour proof.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Color.kt` → `primaryDark`, `surfaceDark`: seeded dark roles corresponding to the Figma tint and panel.
- `docs/knowledge/features/channel-list-screen-how-it-works.md` § “The list's own top bar”: prior bar geometry and panel treatment; documentation-stage handoff below supersedes the spacing and glyph description.
- `docs/knowledge/features/development-verification.md` § “Where a screen test goes” and “Compose evidence”: shared geometry tests and device-only pixel captures.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=133-259

Inspected `133:259`, related `15:8` and `132:3902` on 2026-09-28. The visible sidebar is a full-height dark panel with 20 dp content gutters. Its fixed bar places three light-primary vector glyphs in 24 dp frames, with Settings and Archive centres 44 dp apart, and closes with a 1 dp primary-tinted rule at y=68; the first host begins at y=93. `133:259` exports at 412 × 888; `15:8` is the available 412 × 892 reference and has a different atmospheric background. The hidden title and floating controls are not part of the visible target.

## Context

`ChannelListTopBar` currently uses Material approximations and moves the second glyph eight dp right to fit two 48 dp buttons. This changes both the artwork and visible spacing from the current instance. #1225 supplied the shared dark roles; #1203 owns rows. No in-flight feature branch overlaps the planned screen or test file after a remote refresh.

## Design

Keep `ChannelListScreen`'s stateless contract, `Scaffold` placement, events, navigation wiring, and panel surface treatment. Replace only the three toolbar glyphs with VectorDrawable resources derived from the exact exported Figma SVG paths: gear 22 × 24, archive 24 × 21, add-host 24 × 24. Render each in a 24 dp frame at `colorScheme.primary`, which matches the design's dark `Schemes/Primary` role. The current dark panel compositing and `sidebarRuleColor` already express the target's scrim overlay and 60% primary rule; pixel evidence will confirm them.

Set visual frames at x=20, 64 and width−44, all with top=28 relative to the panel. Use adjacent, non-overlapping 44 dp semantic click regions around the left frames (and a separate right region), preserving names and events. The 1 dp rule remains within the 20 dp gutters at y=68; its bottom-to-first-host gap remains 24 dp. A 44 dp region is the largest non-overlapping target possible for centres 44 dp apart. It stays fixed outside `LazyColumn`, including on empty lists and after scroll. Keep the archive event emitted without selecting a host; the existing navigation handler decides whether it opens a destination.

## State + concurrency model

No new state, flow, job or dispatcher. The toolbar remains recomposed from `(hostState, onEvent)` and stays outside the scrolling list.

## Error handling

No new I/O or parsing. Existing navigation behaviour and empty-selection archive guard remain in their current owner.

## Testing strategy

- Update `ChannelListScreenTest` geometry assertions for the 44 dp non-overlapping click regions, exact glyph frames, rule, and first-host offset. Keep the empty, populated, and after-scroll event/name tests.
- A device-only screenshot test under `app/src/androidTest/` captures real pixels at 412 × 892, compact width, and enlarged text. Save actual captures in `app/src/androidTest/assets/sidebar-1202/`; pair them with current Figma renders and labelled comparison overlays. Run the focused managed-device class and inspect fresh XML and captures.
- Run scoped `testDebugUnitTest`, Android lint, debug assembly, and androidTest compilation. This styling fix does not add an operator-facing flow, so no live Claude scenario is needed.

## Documentation handoff

Pending documentation stage: update the toolbar and panel-colour descriptions under “The list's own top bar” in `docs/knowledge/features/channel-list-screen-how-it-works.md` to name the shipped Figma vector icons, 44 dp centre spacing with distinct targets, separator geometry, and panel surface treatment.

## Open questions

- Does the device pixel comparison show any dark panel or rule mismatch after the vector and geometry change? Resolve against `133:259`, treating `15:8`'s atmospheric background as a conflicting outer context and recording it in the PR.

## Revisions

- A direct sample of the `133:259` render found its rule at RGB (34, 65, 92), over a panel at (11, 14, 17). The existing dark `sidebarRuleColor` yields those values, so the original rule helper remains shared with the tree. The Figma archive SVG rasterizes its half-dp vertical centering to y=30 at the test density, so the geometry test asserts the rendered coordinate.
- Pixel 8 API 35 captures at 412 × 892 show the panel RGB (11, 14, 17) and a rule within one RGB value of the Figma export. The exact vector paths and geometry align; only platform rasterization differs at antialiased edges. `133:259` has no 412 × 892, empty, compact, or enlarged-text state, so the available `15:8` viewport and device-only captures document those reference limits.

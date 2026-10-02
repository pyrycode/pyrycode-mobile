# #1522 — Channel list canvas glow (Figma 15:8)

## Files read

- `ui/conversations/list/ChannelListScreen.kt` — `ChannelListScreen`'s `Scaffold` `containerColor`, today a flat 30 % scrim over `surface` in dark.
- `ui/conversations/thread/ThreadScreen.kt` — the `drawWithCache` frame glow (`FrameGlowRadius`, `FRAME_GLOW_STOP`, `FRAME_SCRIM_ALPHA`): surface, glow, then the 30 % scrim, with a transparent `Scaffold`.
- `ui/settings/ArchivedDiscussionsScreen.kt` — the `LocalStaticDarkPalette`-gated `Brush.radialGradient` precedent.
- `ui/theme/Color.kt` — `primaryContainerDark` `#134A74` and `onPrimaryDark` `#003355` are the gradient's two stops.
- `sharedTest/.../list/ChannelListColoursTest.kt` — the dark panel assertions expect the flat 11,14,17 canvas and change with this ticket.
- Overlap: #1521 edits the top bar's gap constants in `ChannelListScreen.kt`; this change stays inside `ChannelListScreen`'s `Scaffold` call.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8

Flat `Schemes/Surface` `rgb(16,20,24)`, then a radial glow from `Schemes/Primary Container` `#134A74` at 0 to transparent `Schemes/On Primary` `#003355` at stop 0.76, elliptical and rotated by the gradient transform `matrix(43.8 -11.95 13.523 49.567 196 265)` on a radius of 10, then the sidebar's 30 % black overlay. The glow spans the whole frame, top bar included. The export samples at (300,350) 8,33,52; (10,300) 8,26,39; (206,2) 11,31,45; (400,500) 9,18,25; (200,700) 11,14,17. Figma's (200,200) falls on the selected row in the export, not on bare canvas, so it is not a glow sample.

## Change

Under `LocalStaticDarkPalette`, `ChannelListScreen` draws surface, the elliptical glow and the 30 % scrim behind a transparent `Scaffold` (`drawWithCache` on the `Scaffold` modifier). The ellipse is a `ShaderBrush` whose `RadialGradientShader` of radius 10 carries Figma's gradient transform as the shader's local matrix, scaled by density, with its x origin at `196/412` of the width as the thread frame does. The exact transform is used rather than the thread's circular approximation because a model of both against the export puts the ellipse within 1–2 RGB units at every bare-canvas sample. Every other palette keeps today's `containerColor` and draws nothing extra. No colour is a literal: the stops are `primaryContainer` and `onPrimary.copy(alpha = 0f)`, the base `surface`, the overlay `scrim`.

## Testing strategy

`ChannelListColoursTest`: the two dark tests move to `@Config(qualifiers = "w412dp-h892dp-mdpi")` and assert the export's bare-canvas samples above within 3 RGB units instead of the flat panel, keeping the toolbar-rule and selected-row assertions; both write the 412 × 892 capture to the runner's temp directory for the PR. The light test is unchanged and still asserts the flat 248,249,255 panel. `SharedDarkColourCaptureTest` (device) keeps its exact-colour row and dot checks, which the glow does not touch.

## Documentation handoff

- `docs/knowledge/features/app-preferences.md`, the `15:8` comparison paragraph saying the flat canvas "is a routed Colour mismatch (#1486)": pending for the documentation stage — update to say the list now draws the glow (#1522).

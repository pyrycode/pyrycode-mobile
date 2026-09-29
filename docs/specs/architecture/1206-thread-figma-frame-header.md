# Thread Figma frame and header (#1206)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadScreen`, `ThreadStatusArea`: Scaffold slots, reverse-layout list, top overlay, composer and task-count pill anchors.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopAppBar.kt` → `ThreadTopAppBar`: header geometry, controls, divider and menu anchor.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadTopOverlay.kt` → `ThreadTopOverlay`: right-aligned notices that overlap the message region.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MessageBubble`: row types already apply their own 20 dp outer gutter, so the list must not gain another inset.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadOverflowMenu.kt` → `ThreadOverflowMenu`: current reachable actions; no workspace action in the overflow menu.
- `app/src/main/java/de/pyryco/mobile/ui/theme/ThreadColors.kt` and `Theme.kt` → `threadColors`: existing static dark canvas, composer surface and rule roles.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadFrameTest.kt` → `ThreadFrameTest`: header action and long-title regression coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/TaskCountPillTest.kt` → `TaskCountPillTest`: pill position and callback coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThreadCanvasPaletteTest.kt` → `ThreadCanvasPaletteTest`: canvas pixel checks, including the divider.
- `app/src/androidTest/java/de/pyryco/mobile/MainActivityInsetsDeviceTest.kt` → `populatedThreadKeyboardAt412By892`, `populatedThreadKeyboardAt360By640`: real IME, scroll and capture fixture.
- `docs/knowledge/features/thread-screen.md` and `thread-screen-how-it-works-overlays-and-app-bar.md` → thread frame history; #643 already established the current composer and bubble ownership seams.
- `docs/knowledge/features/development-verification.md` → Compose evidence and real-system-bar capture guidance.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

Inspected on 2026-09-29: `16:8`, `533:1946`, `533:1948`, `533:1956`, `533:1957`, `568:3139`, and `568:3162`. The dark 412 × 892 frame has a 20 dp content gutter, a 61 dp top-bar frame at (20, 24), a thin inset rule at y=68, a message region starting at (20, 97), and an input-area frame at (20, 696). The bar uses M3 titleLarge and theme roles for text and rule; its 24 dp back-arrow and 6 × 24 dp ellipsis are supplied SVG paths. The task pill's right edge is at x=392 and its top is y=696 in the populated, attachment-bearing variant.

## Context

The present header's rule and the transcript's top edge sit above the live design, and Material stock glyphs differ from the Figma vectors. Frame geometry must change without moving the existing message internals, footer treatment, or the reverse-layout list's scroll state. Figma supplies a populated attachment/thinking state; it does not supply an empty, keyboard-open, compact-width, enlarged-text or menu-open reference. Those states use the same responsive frame contract and are verified against clipping and reachability rather than an invented pixel target.

## Design

- Keep `ThreadScreen`'s Scaffold, `topBar`, `bottomBar`, `LazyColumn` state and callbacks. Raise the top bar's geometry to the Figma rule and create the 12 dp message-area separation below the completed bar. Retain the message rows' existing 20 dp gutters. The top overlay remains pinned over the scrolling region and ends at the same x=392 right gutter.
- In `ThreadTopAppBar`, preserve 48 dp invisible targets while positioning the visible back vector, title and overflow vector at Figma's locations. Use the downloaded Figma vectors as Android vector drawables, tinting through Material theme roles. Keep text truncation inside the middle slot, the menu anchored to its existing `Box`, and the theme's `threadColors.headerRule` with 60% opacity.
- `ThreadStatusArea` stays in the composer column. Its task pill uses the existing 24 dp band and x=392 right anchor. The baseline y=696 is conditional on the Figma attachment/thinking state; shorter composers naturally rise from the screen bottom. No task-pill styling changes.
- Keep workspace chrome absent from the frame and overflow. Do not touch Channel Info's separate content or the existing callback/data contracts in this pass.

## State and concurrency model

No new state, flow or job. The existing remembered `LazyListState`, draft binding and `imePadding` in the bottom bar remain responsible for reader position and keyboard lift. Only stable layout parameters and the two visible vector resources change.

## Error handling

No new I/O or failure path. Existing connection notices, snackbar paths and keyboard behavior retain their current owners. Figma asset availability is resolved before implementation by checking both SVG paths locally.

## Testing strategy

- Add failing `ThreadFrameTest` geometry assertions for the bar and message-area relationship at a forced 412 × 892 logical viewport, plus compact width and enlarged text checks. Keep its action and long-title tests.
- Update `TaskCountPillTest` with the Figma variant's right/top anchor while preserving its interaction test. Update `ThreadCanvasPaletteTest` rule sampling to the live y=68 location.
- Run scoped Robolectric screen tests, Android lint, debug assembly and Android-test compilation. Run focused real-device `MainActivityInsetsDeviceTest` methods for populated keyboard reopen and frame captures, checking nonzero real bars on the full emulator image for visual evidence. Keep the existing 360 dp keyboard regression.
- Commit 412 × 892 emulator renders beside the current Figma renders and a labelled scope overlay or difference image under `app/src/androidTest/assets/frame-1206/`; record fixture differences, inspected nodes and capture date in the PR. These captures also serve the visual comparison for empty/populated, compact, enlarged-text, keyboard and open-menu checks where a matching Figma state is missing.
- No rung-3 scenario: this is a static frame correction without a new operator-to-daemon flow.

## Open questions

- Confirm the exact header glyph tint from the dark node against the project's Material roles while implementing; use the role that reproduces the live render.
- The Figma task-pill anchor is shown with attachments and a live reading. Confirm the existing composer column reproduces y=696 for that state before changing its placement.

## Documentation handoff

No documentation-only acceptance criterion or `Documentation handoff` section was supplied. The later documentation stage owns any update to `docs/knowledge/features/thread-screen.md` about the final frame geometry.

## Revisions

- 2026-09-29: The new `TaskCountPillTest` showed the Figma variant's pill 19 dp above its y=696 anchor. `ComposerAttachmentStrip` reserves 5 dp above each tile for the remove control, and `ThreadComposerFooter` lays out 32 dp touch targets for a 20 dp visible footer. The frame now reports their visible band heights while placing those existing controls into the adjacent gaps; the controls and their callbacks remain unchanged. This closes the open task-anchor question without restyling either owned component.
- 2026-09-29: The back and overflow SVG paths use `onSurface` and `primary`, respectively, matching their static dark source colors. This closes the glyph-tint question.
- 2026-09-29: Full-emulator capture revealed a flat thread canvas while the live `16:8` root has a radial `primaryContainer` glow under its 30% scrim. `ThreadColors` now exposes an optional glow for static dark; `ThreadScreen` draws the Figma's radial background behind a transparent Scaffold in that mode. Other theme modes retain their existing flat canvas.
- 2026-09-29 verifier rework: Native-graphics bounds in `ThreadFrameTest` confirmed that the 32 dp footer target entered the input row by about 3 dp at the reference viewport. `ThreadScreen` now requests 28 dp footer target height and reserves a matching 8 dp overlap, preserving the footer's bottom-aligned glyph and label positions and the task-pill anchor while clearing the input surface and send target. `ThreadComposerFooter` keeps its 32 dp default outside this frame. The reader's separate bar retains its prior `BarTopGap`; only `ThreadTopAppBar` uses the raised thread gap. `ThreadCanvasPaletteTest` samples each bar's own divider position.
- 2026-09-29 device-gate rework: The full UI gate reported one `ChannelFormFieldsCaptureTest` failure with no Compose hierarchy, while a focused run and a subsequent full run passed. Its viewport override ran in `@Before`, after the Compose rule could launch the host activity; changing display metrics could dispose that host. The capture test now applies the viewport in an ordered outer rule before Compose setup, matching `MobileModalCaptureTest`, and restores the display after Compose teardown. This is a test-harness repair; the thread-frame contract is unchanged.
- 2026-09-29 verifier rework: Real pointer taps confirmed that Compose's automatic minimum touch expansion let the 28 dp footer boxes intercept the input and send controls even though their measured layout bounds cleared them. The thread frame now starts 32 dp footer boxes after the 8 dp input gap, lets their expanded touch area use that gap, and pads their visible content 12 dp upward while the layout reports the same 20 dp design band. `ThreadFrameTest` taps the lower send and field edges to prove the input controls receive those events. The default footer outside this frame is unchanged.
- 2026-09-29 keyboard-gate repair: The keyboard test still measured the merged clickable footer box as though it were the visible icon. The pointer-boundary repair extends that box 12 dp below the icon, leaving a 4 dp box gap while preserving the design's 16 dp visible gap. The test now measures the unmerged icon for visual spacing and separately checks that its clickable parent stays above the navigation bar and keyboard, including after reopening. Production geometry and the real pointer-boundary test remain unchanged.

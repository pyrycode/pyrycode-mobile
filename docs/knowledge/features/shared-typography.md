# Shared typography

[`AppTypography`](../../../app/src/main/java/de/pyryco/mobile/ui/theme/Type.kt)
is the Material type ramp supplied by `PyrycodeMobileTheme`. The six roles below
match the shared text in the [sidebar](channel-list-screen.md),
[thread](thread-screen.md) and [Edit host modal](mobile-modal.md). They were checked
against Figma [sidebar `15:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=15-8),
[thread `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8) and
[modal `533:2369`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-2369)
on 2026-09-28. Other typography roles retain their Material defaults.

## Roles

All six styles set `FontFamily.SansSerif` explicitly, which maps to Roboto on
Android. Sizes, line heights and tracking below use `sp`; weights are numeric.

| Role | Weight | Size / line height | Tracking |
| --- | ---: | ---: | ---: |
| `bodySmall` | 400 | 12 / 16 | 0.4 |
| `bodyMedium` | 400 | 14 / 20 | 0.25 |
| `bodyLarge` | 400 | 16 / 24 | 0.5 |
| `titleSmall` | 500 | 14 / 20 | 0.1 |
| `titleLarge` | 400 | 22 / 28 | 0 |
| `labelLarge` | 500 | 14 / 20 | 0.1 |

Call sites change only the weight for the emphasized variants: `bodySmall` and
`bodyLarge` use 500; `labelLarge` uses 600. They inherit the base role's family,
size, line height and tracking. Component widths and spacing belong to their
owning screens; the shared ramp does not fix text height, so Android font scaling
continues to apply.

## Verification and limits

[`AppTypographyTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/theme/AppTypographyTest.kt)
checks all five metrics for the six base roles through `PyrycodeMobileTheme`
and for the three emphasized variants. The Material default `bodyMedium` tracking
is 0.2 sp, close to Figma's 0.25 sp; visual inspection alone missed that difference.

The labelled [412 × 892 sidebar comparison](../../../app/src/androidTest/assets/typography-1226/sidebar-comparison.png)
uses a nonblank emulator capture. The app's fake names, row count, control
placement and backdrop differ from Figma, so the image is visual evidence for
the shared type treatment, while the metric test establishes exact values.
Typography also changes measured geometry without changing a component's spacing:
task-list text gaps include a taller task mark, and a status pill's height follows
its text. Geometry assertions should measure those rendered elements rather than
assume a fixed text-node gap or pill height.

At 320 dp width and 1.5× Android text, Edit host's bounded identity labels
wrap fully while their values remain one-line ellipsized. The enabled
[`EditHostModalTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt)
checks both labels' text layout for visual overflow, their bounds against the
values, and the reachability of the name field and actions under Robolectric
native graphics and on the managed API 33 device. A node can be displayed
while its glyphs overflow its measured bounds, so visibility and bounds checks
alone would miss the original clipping. At compact width Edit host's identity
row weights label and value 2:3, not 1:2 or 1:3 — see below.

## Density-1.0 hinting and the trimmed line box (#1489)

None of the six roles above set `lineHeightStyle`, so a single-line `Text` lays
out at the font's own height (≈16.4 dp for 14 sp), not the role's 20 sp
`lineHeight` — Compose trims the untouched space. Anything spaced from such a
label in a `Column`, such as Edit host's "Host name:" to its field, sits about
3.6 dp closer than the Figma frame, which measures the label's full line box.
`EditHostModal`'s labels, values and the unpair confirmation message now carry
a local `LineHeightStyle(Center, Trim.None)` (named `FrameLineBox`, matching
`QuestionBatchModal.FrameLineBox` and `PairingHeader.TitleLineBox` — a third
copy, not yet worth extracting into this shared ramp) to take the frame's
untrimmed line box instead.

Separately, at density 1.0 Android hints text layout by rounding each glyph's
advance to a whole device pixel. A label-large emphasized (600-weight) run of
16 glyphs can come out 4 px wider than Figma's unhinted metrics as a result —
measured directly on the device, a `TextMeasurer` call against the same string
gives Figma's width, while a hinted `Paint` at 14 px gives the wider one. The
Medium-weight (500) text in the same screen matched Figma exactly, so the gap
is hinting, not a device Roboto metrics difference, and not weight-specific in
a way that points at the font file. Giving the label (and, for the same
reason, the body text drawn with it) `textMotion = TextMotion.Animated` turns
off that hinting and restores Figma's unhinted advances at every density.
`TextMotion.Animated` is stable in the pinned Compose BOM; no opt-in is
needed. Robolectric's font metrics do not reproduce either the trim or the
hinting gap, so both are proven on the device capture, not a shared test.

The unhinted label is also wider than Robolectric predicts, which moves where
a compact-width label wraps: a shared test asserting "the label breaks only
between words" can pass under Robolectric while the real device breaks a word
like "address:" mid-word, because Robolectric's narrower, hinted estimate still
fits the word on one line. That check belongs in the device-only design
capture walk (`ListDesignCaptureTest.assertIdentityLabelsWrapOnlyBetweenWords`),
not a shared test — and is also why Edit host's compact identity row weights
label and value 2:3 rather than the narrower 1:2 split an earlier ticket used:
the unhinted label needs the extra room.

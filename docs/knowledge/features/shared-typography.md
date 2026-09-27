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

At 320 dp width and 1.5× Android text, the Edit host `IdentityRow` clips
“Server identity:”. The ignored
[`EditHostModalTest`](../../../app/src/sharedTest/java/de/pyryco/mobile/ui/components/EditHostModalTest.kt)
retains the failing reachability assertion for [#1229](https://github.com/pyrycode/pyrycode-mobile/issues/1229).
The shared metrics and compact modal behavior are covered, but enlarged-label
readability remains open until that component's layout is fixed and the test
is enabled.

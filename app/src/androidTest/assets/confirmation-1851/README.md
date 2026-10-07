# Default confirmation evidence (#1851)

Three production-backed states, captured on 2026-10-07 through MainActivity on full
`pixel8Api35`, with `requireRealSystemBars=true`: 3 executed/passed, 0 failed/errors/skipped.
See `capture-results.xml`, `commands-and-results.txt`, the PNGs and their configuration/geometry sidecars.
All are 412 × 892, density 1, font scale 1, hardware accelerated, `syntheticBars=false`,
with real 24px status and navigation bars and no IME.

The thread Saved fixture reaches the actual attachment load/save path through the thread ViewModel's
save action; the reader uses its visible Save to device action. An instrumentation intent stub answers
only the system document picker, writing to a private temporary destination through the production saver.
The dismissal comes from the host modal projection. Each method asserts inert pill semantics, absence
of both old snackbar hosts, geometry/padding and expiry after capture. Reader content bounds stay fixed.

## Notice-only comparison

Figma exports `figma-thread.png` (696:5065) and `figma-reader.png` (696:5101) were fetched fresh on
2026-10-07. Compare only the notice surface; seeded messages, reader prose and other frame chrome are
outside this ticket's scope. The Figma reader shows an Error save-failed arm, so its reference supplies
placement while the thread's Default arm supplies colors, shape and typography. Saved copy has no
separate Figma arm; its hug width follows the measured File saved text plus the specified 16dp padding.

Coordinates in the geometry sidecars include the real status bar: bar bottom 93, pill top 121.
Subtract that 24px bar to compare with Figma's screen-area coordinates: bar bottom 69, pill top 97.
Thus the top clearance is exactly 28dp in all three states, with right edge 392 (20dp gutter).

| State | Painted bounds in capture | Size | Verdict |
| --- | --- | --- | --- |
| prompt-resolved-elsewhere | (218,121)–(392,145) | 174 × 24dp | MATCH: Figma (219,97)–(392,121) is 173 × 24dp; after inset alignment, left/width delta 1dp and every other edge delta 0dp. |
| thread-saved | (317,121)–(392,145) | 75 × 24dp | MATCH: placement/height exact, hug width is 59dp text + 8dp padding per side, Default surface tokens exact. |
| reader-saved | (317,121)–(392,145) | 75 × 24dp | MATCH: reader overlay placement exact with the required Default treatment and existing File saved text; opaque surface/text pixels match thread Saved. |

Each text box is 16dp high, centered with 4dp above/below; horizontal padding is exactly 8dp.
`notice-surfaces.png` compares the cropped Default Figma surface and the three app surfaces at 2×.
All crops contain exact container RGB #134A74 and text RGB #CFE4FF. BodySmall is the existing
12sp/16sp/0.4sp token. The unchanged shared NoticePill supplies 6dp corners; the top-edge fill begins
at 6px in the app versus 5px in Figma due to rasterization, a 1px difference within tolerance.
The two Saved crops differ only at 48 corner pixels where antialiasing blends with their different
underlying screen content; the center surface/text rectangle is pixel-identical.
Shared native-graphics tests independently prove 12dp spacing below errors, including Offline,
stopped-turn and navigation-error seams. These three alone are the requested pixel-capture scope.

## Live and documentation handoff

The dispatcher’s full live run `2026-10-07T08-13-23-504Z` on `4cc214941f08`
merged with main `bcb3fa9af9c1` explicitly records
`InteractiveStreamE2ETest#interactiveTurn_peerAttachment_opensAndSavesAfterHistoryReload`
as passed: 63 executed, 62 passed, 1 failed, 0 skipped. The unrelated archive/restore
failure passed on the same-tree rerun; the dispatcher accepted the gate afterward.
This is full-suite evidence, not a focused live run. See
[gate evidence](https://github.com/pyrycode/pyrycode-mobile/issues/1851#issuecomment-6034164819)
and the [ladder](../../../../../docs/e2e-interactive-stream.md#verification-status).
The three notice-only MATCH verdicts are folded into
[the thread audit index](../design-1220/thread/index.md#prompt-resolved-elsewhere-dismissal-notice--6965065).

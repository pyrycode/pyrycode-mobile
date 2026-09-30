# 1295 — Background-task panel Figma states

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt` → `BackgroundTaskPanel`, `TaskRow`, `TaskTag`, `EmptyReading`, `boundedText` — existing roster semantics, text boundary, and panel content.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/TaskStatusTag.kt` → `TaskStatusTag` — four status appearances and the width cap.
- `app/src/main/java/de/pyryco/mobile/ui/components/MobileModal.kt` → `MobileReadOnlyModal` — shared header, scroll area, close routes, and footer remain owned here.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt`, `Type.kt`, `Color.kt` → `PyrycodeMobileTheme`, `AppTypography`, dark scheme — existing role colors and the shared type ramp.
- `app/src/main/res/values/strings.xml` → `background_tasks_*` — existing state copy already matches the reference.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt` → `BackgroundTaskPanelTest` — existing behavior, trust-boundary, and dismissal assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelLayoutTest.kt` → `BackgroundTaskPanelLayoutTest` — empty offset and compact-height reachability.
- `app/src/androidTest/java/de/pyryco/mobile/ui/components/MobileModalCaptureTest.kt` → `MobileModalCaptureTest` — device viewport and capture pattern.
- `docs/knowledge/features/mobile-modal-callers.md` § Background-task panel — preserve the distinct roster readings, terminal-status honesty, and 4096-character plain-text path.
- `docs/knowledge/features/development-verification.md` § Compose evidence — capture actual pixels; ATD geometry alone cannot prove colors.

## Design source

**Figma (inspected 2026-09-30):** [populated `568:877`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-877), [capped `568:932`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-932), [empty `568:981`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-981), [never reported `568:997`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-997), [task status tags `563:1054`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=563-1054).

The 412 × 892 dark panels use the existing navy read-only shell, a 28 dp title close control and divider, 10 dp content gaps, compact 13 dp group labels, 14 × 12 dp padded blue task cards, 12–13 dp row typography, colored dot tags, and a separate dashed tertiary cut marker. Empty states place distinct solid/dashed 32 dp rings and centered copy 160 dp below the content start. `568:997` alone depicts a 20 dp close glyph, in conflict with the shared 28 dp shell and the other three panel states; retain the shared control.

## Context

The current panel already consumes the live roster and implements the product meanings. The change aligns content and tag metrics with these newer Figma states while preserving the shell, thread entry, state logic, and bounded inert daemon text. No new architecture decision is needed.

## Design

- Keep `BackgroundTaskPanel(roster, onDismiss)` and `MobileReadOnlyModal` wiring. Adjust only content styles in `GroupLabel`, `TaskRow`, `TaskProgress`, `LatestUpdate`, `PartialNotice`, `CutMarker`, and `EmptyReading` where the Figma metrics differ. Use the existing dark color roles; derive local text styles from `MaterialTheme.typography` rather than changing the shared ramp.
- Keep `TaskTag`'s mapping. Refine `TaskStatusTag` to the component's 10 dp corner shape and 11/16 medium label. Preserve the 160 dp cap and ellipsis for unknown terminal words at compact width.
- Keep the existing copy, running/finished grouping, shown counts, omitted empty groups, no-change reading, and independent daemon cut markers. Preserve `boundedText` and `printableText` on every daemon string, with no markup or click path.
- Use a device capture test with static, synthetic rosters matching the visual states. Capture populated, capped, empty, and never-reported panels at 412 × 892 in fixed dark. Save the actual emulator PNGs and matching Figma renders under `app/src/androidTest/assets/`, plus a labeled comparison/overlay. The fixture must assert nonblank pixels and the intended viewport. Add compact 320 dp / enlarged-text interaction coverage and both close routes if existing tests do not already prove the final design.

## State and concurrency model

No state owner, flow, dispatcher, or job changes. The panel recomposes from `BackgroundTaskRoster?`; the existing debug-only open log remains content-free. The shared shell owns Back, close glyph, footer dismissal, and scrolling.

## Error handling

No new I/O occurs in production. A missing roster keeps the never-reported reading; an empty reported roster keeps the empty reading; dropped tasks keep a partial-list notice. Unknown terminal values remain bounded plain text in the Stopped style, with blank/running-looking terminal values reading Finished. Daemon-truncated and client-bounded fields retain separate visible cut markers.

## Testing strategy

- Extend focused `BackgroundTaskPanelTest` and `BackgroundTaskPanelLayoutTest` only for changed visual metrics and compact/enlarged-text reachability; retain their existing roster, status, cut, and dismissal coverage. Run RED first, then GREEN through `testDebugUnitTest --tests`.
- Add one focused device-only capture class because Robolectric's dialog framebuffer can be blank. Run that class on the managed device; use a full emulator for nonblank pixel evidence if the ATD framebuffer is blank. Verify fresh XML with executed tests and copy captures into checked-in assets.
- Run focused `lint`, `assembleDebug`, `compileDebugAndroidTestKotlin`, and forced Spotless check. The dispatcher owns the complete suite. This is a visual read-only panel change, so no new real-Claude stream scenario is needed.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/mobile-modal-callers.md` § Background-task panel with the current Figma node IDs, local typography/tag metrics, and checked-in comparison evidence. Keep `docs/knowledge/features/development-verification.md`'s capture guidance current if the device run reveals a new caveat. No shared documentation is edited by the builder.

## Open questions

- Whether the provided empty-state icon assets are visually identical to the current drawn rings; inspect their dimensions and pixels before deciding whether a distinct drawable is needed.
- Whether the managed ATD image supplies valid panel pixels; if blank, use the configured full emulator for the required visual captures while keeping a focused device test for geometry.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] The daemon frame has already become `BackgroundTaskRoster`; this ticket must keep `TaskField`, `boundedText`, and `TaskTag` as the sole display path for its strings. A raw word that visually says Running on a finished task must continue to fall back to Finished.
- [Tokens and secrets] No token generation, storage, or lifecycle changes. Captures use synthetic task text, never live conversation or credential contents.
- [File and storage] Production adds no path or storage operation. Device test artifacts use instrumentation's test-output directory and checked-in synthetic assets.
- [Android attack surface] No exported component, intent, WebView, or permission change. Text stays in Compose `Text` without clicks, URLs, or clipboard actions.
- [Cryptography] No crypto or key-handling change; the existing Noise boundary is untouched.
- [Network and I/O] No wire or connection change. The panel only reads the existing roster and does not weaken frame caps.
- [Errors, logs, telemetry] Keep the content-free, debug-only panel-open log. Never log captured daemon fields or raw status text.
- [Concurrency] No new jobs or shared mutable state; composition follows the held roster and shell lifecycle.
- [Threat alignment] A hostile daemon can supply unusual strings, but `printableText`, the 4096-character bound, plain rendering, and distinct cut marker remain. Screen captures containing real user content are avoided with synthetic fixtures. Relay flooding, device storage theft, and screenshot policy are outside this visual ticket and remain owned by their existing transport/storage/UI policies.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30

## Revisions

- 2026-09-30: The solid empty ring's Figma SVG is a 32 dp circle with a 2 dp outline, so `EmptyReading` can draw it exactly. The never-reported ring is supplied as a 64 px PNG displayed at 32 dp; use that asset directly. This resolves the icon question without changing the state contract.
- 2026-09-30: The API 33 ATD executed both capture-class tests but returned one-color black PNGs. The full Pixel 8 API 35 emulator yielded nonblank 412 × 892 captures and executed both tests without failure or skip. Use its checked-in captures for the comparison; retain the ATD run only as behavior/reachability proof.
- 2026-09-30: On the full emulator, Compose's native text boxes made populated rows 7–16 dp shorter than the Figma render despite matching nominal type sizes. Increase local row and progress gaps to match the visible card rhythm; the shared shell and shared type ramp remain unchanged.
- 2026-09-30: The capped and empty PNG exports omit visible header text/close artwork although their design-context trees include them. The capped export says `Running · 8 shown` but draws only three rows. Keep the shared header and honest live roster, and identify these reference inconsistencies beside the capture evidence.

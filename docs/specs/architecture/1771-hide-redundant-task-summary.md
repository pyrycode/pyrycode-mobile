# Hide redundant background-task finish summaries (#1771)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanel.kt`: `TaskRow` gates the finish field; `TaskField` retains printable filtering, bounds and cut markers.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelTest.kt`: panel fixtures and terminal-summary assertions, including the truncation fixture that needs a distinct summary.
- `BackgroundTaskPanelLayoutTest`, `BackgroundTaskPanelSpacingTest`, `BackgroundTaskPanelInsetsTest` and `BackgroundTaskStopPanelTest`: existing layout and interaction coverage.
- `docs/knowledge/features/thread-screen.md` and `mobile-modal-callers.md`: the panel renders held task fields without changing the roster; useful summaries retain the existing theme and spacing.
- `docs/knowledge/features/development-verification-gates.md`: shared Compose tests run as unit tests under Robolectric; this text-presence change needs no device-only test.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/BackgroundTaskPanelCaptureTest.kt`: existing reference capture coverage.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-877 and https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=568-932

Inspected both contexts and screenshots, plus finished-row node `568:916`. The populated modal groups running and finished cards with type/status headers, 14dp horizontal and 12dp vertical padding and 8dp gaps. Useful summaries remain below descriptions in 13sp `bodyMedium` / `onSurfaceVariant`; the capped reference contains running tasks. This ticket only removes redundant finish fields, preserving existing components, assets and tokens.

## Change

Replace `TaskRow`'s non-empty-summary guard with a display-only predicate: trim the description and summary for comparison, show a non-empty trimmed summary unless it contains a non-empty trimmed description, using case-sensitive substring matching. Keep the original summary passed to `TaskField` so its rendering and truncation marker remain intact whenever visible. Hidden summaries also hide their own marker. Task data, status, updates and stored state remain untouched. No new types, state, errors, dependencies or call-site changes. No overlapping remote feature branches touch the implementation or test file. Forecast: roughly 140 written lines including this plan and tests, one deliverable and two acceptance criteria; within all sizing boundaries.

## Testing strategy

Write failing shared panel tests first and run `BackgroundTaskPanelTest` under `testDebugUnitTest`: template/equal summary hidden, different summary shown, empty/whitespace description permits a summary, empty/whitespace summary hidden and case-sensitive comparison. Assert descriptions and held task data survive, and a hidden summary leaves no cut marker. Update the existing truncation fixture to an informative distinct summary, retaining its marker assertion. Run the panel's existing layout, spacing, insets and stop-interaction unit coverage, then the existing device reference capture method. This adds no live action or transport flow; existing real-Claude background-task scenarios remain unchanged, and synthetic held finished rows provide deterministic coverage of this display guard. Finish with lint, assembly, shared-test instrumentation compilation, formatting and the required whole unit suite / pre-verify checks after merging main.

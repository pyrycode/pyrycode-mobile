# Thread activity indicators (#1209)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt` → `ThreadStatusArea`, `StatusReading` — owns the input status band, gutter and existing priority; keep its selection contract.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ThinkingIndicator.kt` → `ThinkingIndicator`, `isRenderableReading` — live progress and running-tool labels, bounded readings and stable composition identity.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ApiRetryIndicator.kt` → `ApiRetryIndicator`, `isRenderableCounter` — counter and unknown-attempt fallback.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/CompactingIndicator.kt` → `CompactingIndicator` — indeterminate compaction reading.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/ResettingIndicator.kt` → `ResettingIndicator`, `resettingLabelRes` — Reset session phase wording.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/TurnOutcomeIndicator.kt` → `TurnOutcomeIndicator`, `turnOutcomeReport`, `inertOutcomeToken` — bounded inert daemon-authored details and outcome labels.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/NoticePill.kt` → `NoticePill` — shared default/error pill styling.
- `app/src/main/java/de/pyryco/mobile/ui/theme/Theme.kt` → `PyrycodeMobileTheme` — fixed dark colour roles.
- `docs/knowledge/features/thread-screen-how-it-works-overlays-and-app-bar.md` § Thinking-indicator placement — status band is intentionally part of the composer.
- `docs/knowledge/features/shared-typography.md` § Roles — `bodySmall` matches the Figma 12/16 role and scales with Android text size.
- `docs/knowledge/features/development-verification.md` § Compose evidence — device pixels, compact widths and keyboard checks need distinct evidence.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ThinkingIndicatorTest.kt` → `ThinkingIndicatorTest` — existing lifecycle and progress assertions.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/AgentStatusLabelsTest.kt` → `AgentStatusLabelsTest` — existing agent-specific accessibility assertions.

## Design source

**Figma:** [Input status area `533:1957`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=533-1957), [Pill components `347:6618`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=347-6618), [Conversation thread `16:8`](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8); inspected 2026-09-29.

The input status row sits immediately above the composer, aligned with its 20 dp left gutter. Its thinking variant uses a fixed 14 × 16 snowflake glyph, an 8 dp gap and `M3/body/small` in `Schemes/Primary`, with 4 dp vertical padding. The shared 6 dp rounded pill uses 8 × 4 dp padding and default or error container roles; the full thread reference is 412 × 892. The Figma file has no dedicated frames for API retry, compacting, Reset phases or turn outcomes, so those use the input status component and, for a final outcome, the shared error pill as component references rather than claiming a full-screen pixel match for those states.

## Context

The five existing readings are visually inconsistent with the current input status area: their 8 dp vertical padding makes a 32 dp band, their text and progress use `onSurfaceVariant`, and thinking has a circular spinner where Figma shows a snowflake. The selector and state projections already work. #1206 owns the surrounding frame; #1225 and #1226 own shared palette and typography. This ticket changes only the readings and their immediate band treatment. No in-flight feature branch overlaps the proposed production or test files at planning time.

## Design

- Keep `ThreadStatusArea` and `StatusReading` priority and placement unchanged. Reuse the existing gutter, with each active reading's row at 4 dp vertical padding, 16 dp horizontal padding and an 8 dp content gap; the 16 dp high icon/text produces the 24 dp status band in the reference.
- Convert the supplied snowflake SVG path to an Android vector resource without redrawing it. `ThinkingIndicator` uses it at the supplied 14 × 16 geometry. Animate only a visual property that leaves that geometry and layout bounds fixed across frames. Keep one icon instance while the label changes among plain thinking, token progress and running tool.
- `ApiRetryIndicator`, `CompactingIndicator` and `ResettingIndicator` retain indeterminate progress but use a fixed 16 dp visual box, the shared 8 dp gap, `bodySmall` and `colorScheme.primary`. Preserve their current labels, content descriptions and null/false early returns.
- `TurnOutcomeIndicator` reuses `NoticePill`'s error-container shape, padding and colour. Extend the shared pill only as needed for a leading outcome icon and a two-line ellipsized label. Keep the existing outcome icon distinction, client-owned lead, merged description and `turnOutcomeReport` sanitization. The pill has no action or dismiss control.
- Keep all state derivation, event handling, network/daemon models and string resources intact. No new dependency or logging is needed for a rendering-only change; existing data-layer lifecycle logs remain its source of operational diagnostics.

## State and concurrency model

All five components remain stateless functions of their parameters. `ThreadStatusArea` selects one reading from the existing hoisted state. Only the thinking glyph's visual animation has composition-local state, tied to the icon while it is composed; it stops when the reading disappears or the screen exits. The existing `viewModelScope`/`StateFlow` ownership and connection-background cancellation path are unchanged.

## Error handling

No I/O or parsing is added. Malformed thinking counters continue to fall back through `isRenderableReading`; malformed retry counters continue through `isRenderableCounter`. `turnOutcomeReport` continues to replace controls and format characters and bound daemon-authored tokens; the UI renders them only in Compose `Text`, with a two-line visual limit. No error data becomes a URL, path, action, asset name or log value.

## Testing strategy

- Add a shared Compose test for exact status-row geometry/colour/icon and reading bounds at 412 dp and compact width, including an advanced animation frame and enlarged text. Make the new assertion fail before production edits.
- Exercise the existing `ThinkingIndicatorTest`, `AgentStatusLabelsTest`, API retry, Reset and outcome screen tests for labels, selection priority and appearance/disappearance. Add only missing focused assertions for the pill's semantics and bound.
- Capture real dark-theme emulator pixels at 412 × 892 and a compact width with the thread and relevant transient fixtures; retain current Figma 412 × 892 and component renders beside them under `app/src/androidTest/assets/`. Produce a labelled overlay or difference image and record observed in-scope corrections. Check a populated thread with keyboard and overflow menu for clipping or blocked touch targets. Pixel capture needs `app/src/androidTest` because Robolectric cannot prove real display output; focused device execution and its XML must show executed cases.
- Run focused `testDebugUnitTest` classes, Android Lint, debug assembly and androidTest Kotlin compile. The dispatcher owns the whole-suite and live gates. This visual-only change creates no new operator-facing daemon flow or scripted scenario.

## Open questions

- Does the actual emulator render of the supplied glyph need a static tint or the exact baked asset fill to match the current Figma dark render? Decide from the comparison and record any design change below.
- Is a two-line outcome pill readable at 320 dp/1.5× text without masking the task-count pill? Prove on the compact device fixture; adjust local layout if needed.

## Documentation handoff

The ticket has no documentation-only acceptance criterion or named reference-document change. The documentation stage may update the owning thread feature overview with the inspected node IDs and any visual-evidence lesson; this is pending for that stage.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary. `turnOutcomeReport` and `inertOutcomeToken` remain the only conversion from daemon-authored turn-end details to display text; `ThinkingIndicator.isRenderableReading` and `ApiRetryIndicator.isRenderableCounter` keep their bounds. The visual components receive already typed state and render text only.
- [Tokens, files and storage] No credential, persistence, path or backup handling changes. The screenshots use deterministic test content, with no pairing secret or real daemon payload. The only added file read is a static checked-in vector resource.
- [Inter-process and cryptography] No exported component, intent, WebView, pending intent, key or Noise operation changes.
- [Network and I/O] No frame, relay, timeout or reconnect code changes; retry remains a view of existing state, not a retry action.
- [Messages, logs and telemetry] MUST KEEP — the outcome pill must show `turnOutcomeReport`'s bounded inert strings through Compose `Text` only. It must not log or use them as asset names or interaction targets. Existing status descriptions retain their attribution to the agent.
- [Concurrency] The optional icon animation is composition-scoped and has no coroutine, timer or transport ownership. It stops with disposal; state priority and foreground/background close remain owned by `ThreadStatusArea` and the existing ViewModel/driver.
- [Threat alignment] A compromised relay cannot read the Noise plaintext through this UI change. Hostile daemon details stay bounded and inert. Screenshot/accessibility exposure is inherent in the existing status display and adds no secret; UI-side eavesdropping mitigation is outside this visual ticket, with no new sensitive field introduced.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-29

## Revisions

- Implementation resolution, 2026-09-29: the vector keeps the supplied SVG fill. At the 412 × 892 dark-theme viewport, the aligned Figma and emulator status backgrounds both sample RGB (11,14,17), and the error pill containers both sample RGB (147,0,10). The compact 320 × 692 / 1.5× fixture keeps the full outcome accessibility description and a bounded two-line visible label beside the task pill; no layout contract or priority change was needed.
- Device-capture revision, 2026-09-29: the compact Reset capture exposed the Material indeterminate spinner shrinking to a tiny stroke during its animation. `ThreadStatusSpinner` now draws one fixed 270° arc that rotates within the existing 16 dp slot for retry, compacting and Reset. A native-graphics frame test first observed 13 to 35 visible blue pixels over eight frames and now bounds the variation to a rasterization tolerance. This keeps the status mark visibly present throughout the cycle without changing labels or state ownership. This supersedes the State and concurrency sentence that mentioned only the thinking glyph: all four active-reading animations are composition-scoped and disposed with their reading, with no explicit coroutine job or transport lifetime.

# Queued message actions (#1918)

## Files read

- `QueuedMessageRow.kt`: `QueuedMessageRow` owns the dimmed bubble, waiting glyph and optional Send now callback.
- `MessageBubble.kt`: `MessageContainer` and `MessageActions` provide the narrow visual column with adjoining independent pointer targets; shared bubble padding and shape remain the source of truth.
- `ThreadScreen.kt`: the queued-row call binds each queued id and supplies Send now only for fresh supported capability.
- `QueuedMessageRowGeometryTest.kt`: existing width, padding and pointer checks need the new left-column geometry.
- `QueuedBacklogTest.kt`: callback-id and fresh capability assertions remain; the horizontal target assertion becomes vertical.
- `ThreadDesignCaptureTest.kt`: `queuedAndToolRowFramesAt412By892` needs explicit capability; add light/dark enabled and Cancel-only capture coverage.
- `docs/knowledge/features/queued-backlog-section.md`: preserve inert plain-text rendering, queue ownership and the 200dp width cap.
- `docs/knowledge/features/message-bubble.md`: independent targets must stay within the row; the sent controls use a 96dp minimum bubble for a pair of 48dp targets.
- `docs/knowledge/features/development-verification-gates.md`: shared geometry runs with ForcedSize and native graphics; screenshot capture remains device-only.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=847-14138

Placement: https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=848-9517 (Mobile). The screenshot shows the waiting clock followed by a narrow Send now/Cancel column, then the dimmed bubble. Use 12×12dp composer-send and exported uncircled X glyphs, full-opacity `inversePrimary`, a 13dp visual column, a 12dp bubble gap and 25dp glyph-centre spacing; only clock and bubble retain 60% opacity. The waiting-to-column gap follows the Mobile reference's 12dp spacing.

## Change

Replace trailing IconButtons with a private layout following `MessageActions`: reserve the waiting glyph and visual column before measuring the right-aligned, at-most-200dp bubble. Measure the actions after the bubble and place them last, so their 48dp-wide pointer regions win the small horizontal overlap with the bubble. Each action is a separate clickable Button semantics node; two adjoining 48×48dp targets meet at the bubble midpoint with glyphs offset 12.5dp on either side. A supported pair gives the bubble a 96dp minimum height, following the sent-message accessibility precedent, preserving 16dp inter-bubble spacing and preventing short neighbouring rows from sharing touch areas. Cancel-only uses one centred 48dp target. Keep shared 20dp/16dp padding, user bubble shape/colours and text wrapping; no callback, capability, payload or failure-path changes. Reuse `ic_composer_send`, convert the downloaded Figma X path to a theme-tinted vector, and keep the existing Schedule glyph. No new dependency or exported API.

Overlap: remote #1619 touches `ThreadDesignCaptureTest`; its capture fixture is already in this tree. Changes here stay local and additive.

Sizing: one visual deliverable, five criteria, zero exported types or signature migrations, no new failure branches; approximately 550 written lines including plan, tests and resources, below 1600.

## Testing strategy

Write failing shared geometry assertions first. Cover exact glyph bounds/spacing, left-column placement, bubble cap and padding; test both actions' centres, outer edges and midpoint routing for short, wrapped and long unbroken text at 320dp and 412dp, with neighbouring rows and Cancel-only. Retain capability true/false/unknown/held and queued-id routing tests. Run affected geometry/backlog, ViewModel send/drop and repository send/drop coverage, plus existing sent-message geometry tests. Add device capture coverage for static light/dark pair and Cancel-only states (device-only because screenshots require real pixels) and run the focused capture method. Run lint, assemble, Android-test compilation and forced Spotless. After the final main merge/push, run the whole unit/shared suite, assemble and `scripts/pre-verify.py --gradle`.

The dispatcher owns the full live gate: request `all` and require executed/failed/skipped counts plus confirmation that `InteractiveStreamE2ETest.interactiveTurn_sendQueuedNow_reachesRunningTurn` and `interactiveTurn_peerQueue_staysConsistentAcrossClients` both ran and passed. These existing rung-3 scenarios cover the unchanged actions; no new operator flow is introduced.

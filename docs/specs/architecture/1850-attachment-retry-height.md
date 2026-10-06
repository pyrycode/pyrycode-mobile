# #1850 — Attachment Retry height

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt`: `AttachmentFileRow` puts the default `TextButton` after two full body-small line boxes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageAttachmentsTest.kt`: existing Retry routing, enlarged-text and attachment geometry coverage.
- `app/src/androidTest/java/de/pyryco/mobile/design/ThreadDesignCaptureTest.kt`: `attachmentAndEmptyFramesAt412By892` reaches the failed-file state and captures real hardware pixels.
- `app/src/androidTest/java/de/pyryco/mobile/design/DesignCapture.kt`: `capture` records viewport, density and real-bar evidence.
- `docs/knowledge/features/thread-screen.md` and `message-bubble-attachment-slot.md`: attachment layout and existing interactions; semantic text alone cannot establish drawn geometry.
- `app/src/androidTest/assets/design-1220/thread/index.md`: attachment-state verdict identifies the residual 8px pitch mismatch.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=696-4913

Fresh design context and screenshot inspected 2026-10-06. The failed-file name and state sit above a 40px Retry row with primary-colour medium text. Keep the existing Material 3 TextButton, theme colours, file artwork and typography; only its layout height changes.

## Change

Constrain `AttachmentFileRow`'s Retry TextButton to 40dp instead of its default 48dp layout allocation. Compose's minimum touch-target expansion remains enabled, giving a 48dp hit area beyond the visual bounds. No signature, state, retrieval or callback changes. One deliverable, two acceptance criteria, no new exported types or consumer migrations; forecast about 100 written lines including tests, plan and capture verdict, below all size limits. #1619 overlaps the evidence index; keep the edit local to the attachment-state verdict.

## Testing strategy

First add a failing shared test beside existing Retry tests: require a 40dp button, the corresponding failed-row/next-row geometry, a minimum 48dp touch area and real pointer activation above and below the visual button. Existing enlarged-text, open/save and non-actionable-state tests remain green. Run `MessageAttachmentsTest` and affected `MessageBubbleTest` coverage, lint, assemble, androidTest compile and forced Spotless. Recapture through `ThreadDesignCaptureTest#attachmentAndEmptyFramesAt412By892` on full pixel8Api35 with real bars; retained PNG and sidecar prove the 412x892 density-1 visual spacing. This device test is needed for real hardware screenshots, not JVM layout. Compare with the fresh Figma export and update only the attachment-state evidence verdict. Final whole unit/shared suite, assemble and pre-verify follow the last merge of main. This existing control receives no new operator flow, so no new real-Claude scenario is needed.

## Revisions

- 2026-10-06: The initial compact-bubble regression wrapped the failure label and measured an unrelated extra text line. Render `MessageAttachments` directly for the single-line geometry contract; the existing enlarged-text bubble test still covers containment. The corrected test failed with an 80dp row before implementation and passes with 72dp afterward. Hardware comparison uses the same glyph-top edge for every tile: 78px pitch in both app and Figma, superseding the issue's 58/106/98px figures. The design contract remains a 40dp layout and at least 48dp touch area.
- 2026-10-06 (rework, finding 1): Fixed height clips the Retry paragraph at font scales 1.5 and 2. Replace it with a 40dp minimum and scope `LocalMinimumInteractiveComponentSize` to `Dp.Unspecified` for this button's layout reservation. Foundation's pointer-target expansion remains enabled. Normal geometry remains unchanged, while enlarged text receives its full paragraph height plus button padding. Extend the existing compact-bubble test to reject visual overflow at both scales and assert the touch target remains at least 48dp; retain normal-size geometry and pointer-edge assertions. Refresh hardware capture evidence after repair.
- 2026-10-07 (rework, finding 1): The full overflow assertion also exposed a 1.5-scale horizontal allocation of 54px for a 59px paragraph. Give the Retry button its maximum intrinsic width so both dimensions fit the label; normal height and next-row spacing remain unchanged. Both enlarged-text probes and all 35 attachment/bubble tests pass.

## Documentation handoff

- Pending for the documentation stage: `docs/knowledge/features/message-bubble-attachment-slot.md`, Attachment slot — final normal-size Retry allocation, expanded touch target, enlarged-text behavior, retained capture evidence and corrected 78px glyph-top pitch (verifier handoff).

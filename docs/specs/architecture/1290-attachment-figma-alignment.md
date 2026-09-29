# 1290 — Attachment visual alignment

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStrip.kt` → `ComposerAttachmentStrip`, `AttachmentItem`, `FileTile`, `RemoveControl`, `rememberThumbnail` — pending tile geometry, artwork and the foreign-content URI guard.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageAttachments.kt` → `MessageAttachments`, `ImageAttachment`, `AttachmentFileRow`, `FileGlyph`, `PlatformThumbnailDecoder` — sent slots, file rows, actions and bounded decoding.
- `app/src/main/res/drawable/ic_attachment_file.xml` and `ic_modal_close.xml` — the page and close paths compared against current Figma exports.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerAttachmentStripTest.kt` → `ComposerAttachmentStripTest` — picker, remove and send routing coverage.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MessageAttachmentsTest.kt` → `MessageAttachmentsTest` — layout, open/save and retry coverage.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/ComposerFieldCaptureTest.kt` → `capture` — 412 × 892 device capture and output convention.
- `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md` § Composer pending attachments — picker and paste converge on the same strip, with URI validation before thumbnails.
- `docs/knowledge/features/message-bubble-attachment-slot.md` § Attachment slot — the source precedence, bounded image decode, compact lane and action contract.
- `docs/knowledge/features/development-verification.md` § Where a screen test goes — shared Robolectric layout tests; device pixels require `androidTest`.

## Design source

**Figma:** [Thread attachments, 16:8](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8), [File field, 132:4605](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4605), [Input attachment, 390:7181](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=390-7181), [Image preview, 390:7159](https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=390-7159). Inspected 2026-09-30.

The fixed dark thread shows 45 × 60 pending thumbnails and outlined file pages with 6 dp corners, 12 dp tile-to-tile spacing and a 20 dp blue remove disc overlapping the upper right by 5 dp. Sent images occupy a 160 dp square; file rows place the same 45 × 60 page 12 dp before a small, inverse-primary filename. The file page export matches `ic_attachment_file`'s path and viewport; the remove export is the same path as `ic_modal_close` scaled from 28 to 20, with its dark center already drawn by `RemoveControl`, so no asset replacement is needed. Current design does not show loading, failure, retry, open or save states; retain their product behavior.

## Context

The attachment renderers already implement most dimensions and actions, but their actual pixels have not been compared to the current design. In particular, `AttachmentFileRow` derives a light tint from bubble content rather than the design's inverse-primary role. This ticket aligns only these attachment surfaces and records device pixel evidence.

## Design

- Keep `ComposerAttachmentStrip`'s dynamic thumbnail crop, fallback page, key order, send opacity, and remove routing. Match the 20 dp remove disc's design shadow without enlarging its visible geometry or crossing the next control's hit area.
- Use `MaterialTheme.colorScheme.inversePrimary` for the sent file page, its type label and filename. Retain the 45 × 60 page, 12 dp gap, body-small name, middle ellipsis and bounded row width. Keep loading, failure and retry readable in the same row. If device captures reveal another in-scope mismatch, revise this plan before changing its contract.
- Keep `ImageAttachment`'s 160 dp crop and compact square fallback. Open and save stay on ready slots only; failed rows keep Retry. No ViewModel, repository, model or navigation contract changes.
- Add focused screen assertions for visible pending geometry, compact and enlarged-text sent rows, and existing actions. Add a device-only capture fixture with matching image and file content to compare the 412 × 892 dark thread with the inspected Figma render. Store comparison images with the test evidence under `app/src/androidTest/` and link them from the PR.

## State and concurrency model

No new state or job. `rememberThumbnail` remains a `produceState` tied to the tile and cancels its provider request on disposal. `ImageAttachment` remains a `produceState` tied to source and decoder; `PlatformThumbnailDecoder` continues on IO with a bounded target. Existing retrieval and send jobs remain owned by `ThreadViewModel`.

## Error handling

An unreadable pending thumbnail still falls back to the file page. A sent image decode failure still falls back to the file row; loading, not found and failed states retain their text and Retry only where already available. No exception text, filename or URI enters a log.

## Testing strategy

- RED then GREEN: focused `ComposerAttachmentStripTest` geometry and `MessageAttachmentsTest` compact and enlarged-text assertions. Run their existing picker/remove/send/open/save/retry coverage.
- Run a focused device capture test at 412 × 892 in the fixed dark theme and inspect its fresh XML and PNG. Repeat compact width, font scale and keyboard cases for clipping. Use the matching Figma render, a labelled side-by-side and an overlay or difference artifact in the PR.
- Run `spotlessApply`, forced `spotlessCheck`, focused `testDebugUnitTest`, `lint`, `assembleDebug` and `compileDebugAndroidTestKotlin`. The dispatcher owns full suites and live acceptance. No new real-Claude scenario: this retunes existing rendering and actions, and the attachment flows already have their scenarios.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/thread-screen-composer-drafts-and-attachments.md` § Composer pending attachments and `docs/knowledge/features/message-bubble-attachment-slot.md` § Attachment slot with the final visual geometry, tint and comparison evidence. No shared documentation is edited in this stage.

## Open questions

- Does a 412 × 892 emulator capture confirm the remove icon's overlap and shadow against node 390:7181? Resolve from pixels, preserving non-overlapping touch bounds.
- Does the sent filename remain inside the bubble at compact width and enlarged font scale? Resolve with bounds assertions and device capture.

## Security review

**Verdict:** PASS

**Findings:**

- [Trust boundaries] No new boundary: `isForeignContentUri` remains before `rememberThumbnail` and `PlatformThumbnailDecoder` opens a URI; `MessageAttachmentItem` uses reference text with retrieval hints only as display data. This plan changes presentation only and keeps names as `Text`.
- [Tokens and cryptography] No token, key, handshake or random value is introduced or read by the affected renderers.
- [File and Android surface] No path, provider, intent or storage change. `AttachmentTarget` and existing open/save routing stay intact; filename text is never promoted to a path or URI by this change.
- [Network and I/O] No wire or socket change. Existing cancellation and decode-size limits in `rememberThumbnail`, `PlatformThumbnailDecoder` and `thumbnailTargetSize` remain load-bearing.
- [Errors and logs] Existing failure text is static and the visual tests use fixture names only; no new production logs contain names, URIs or bytes.
- [Concurrency] The existing composition-owned `produceState` jobs retain their cancellation path. No new coroutine or shared mutable state.
- [Threat model] Hostile filename and thumbnail metadata can still only affect bounded text/crop; image decode and URI validation remain unchanged. Screenshot and accessibility exposure are pre-existing screen-level properties, outside this visual ticket.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-30

## Revisions

- 2026-09-30: The component capture host did not reproduce `MainActivity`'s IME inset behavior. The keyboard check runs in `MainActivityInsetsDeviceTest` with a real test IME and an assertion that the pending strip clears its measured top. `AttachmentVisualCaptureTest` remains the 412 × 892 and compact pixel fixture; it waits for the provider's asynchronous thumbnails before recording evidence.

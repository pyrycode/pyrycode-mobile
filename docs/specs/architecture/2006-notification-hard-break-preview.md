# Notification hard-break preview (#2006)

## Files read

- `app/src/main/java/de/pyryco/mobile/notifications/AttentionPreview.kt`: `previewPlainText` walks the original Markdown tree; `notificationPreview` collapses whitespace and bounds the result.
- `app/src/test/java/de/pyryco/mobile/notifications/AttentionPreviewTest.kt`: deterministic coverage through `notificationPreview` protects formatting and literal code.
- `docs/knowledge/features/push-messaging-service.md`: Private reply and action previews records the hard-break limitation and requires literal code backslashes to survive.

## Design source

Figma: N/A — plain-text Markdown normalization only; notification layout and styling remain unchanged.

## Change

Render `MarkdownTokenTypes.HARD_LINE_BREAK` as a space in `previewPlainText`, rather than copying its source backslash. The parser distinguishes a Markdown break from literal backslashes, and the existing code-span/code-block branches preserve code. The final whitespace collapse produces one space. No signatures, state, dependencies, notification delivery or logging change. No in-flight branch overlaps either implementation file. Expected total written work is approximately 45 lines across production, tests and this plan, with zero new exported declarations or consumer updates and two acceptance criteria.

## Testing strategy

Write tests through `notificationPreview` first and observe the hard-break assertion fail. Cover LF/CRLF hard breaks and the existing two-space break, escaped prose backslashes including before a newline, and literal backslashes in inline, fenced and indented code. Run the whole `AttentionPreviewTest` class plus existing notification publisher/source tests, lint, assembly and the final pre-verification gate. This is pure normalization within an existing notification flow; no new live scenario or screen/device test is needed.

## Documentation handoff

Pending for the documentation stage: update `docs/knowledge/features/push-messaging-service.md`, “Private reply and action previews (#1725)”, to replace the known hard-break limitation with the repaired behavior and preserved literal-backslash contract.

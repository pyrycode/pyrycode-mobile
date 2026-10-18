# 2273 — Thread markdown block spacing

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownTextStyle`, `MarkdownText`, `StreamingMarkdownText`, `ListBlock`, `BlockQuoteBlock` and `CodeBlock` share block spacing independently of sibling-item spacing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `MessageContainer` and the assistant render paths separate bubble padding, attachment/metadata gaps and user paragraphs from markdown spacing.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen` supplies explicit reader spacing.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt`: `assertBody` already measures recursive spacing and both reader routes.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt`: the existing paragraph-gap assertion must follow the new design.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/StreamingMarkdownTextTest.kt`: settlement parity and bubble streaming coverage.
- `docs/knowledge/features/markdown-text.md`, `markdown-text-internals.md`, `message-bubble.md`, `message-bubble-testing.md`, `markdown-reader-screen.md` and `development-verification-gates.md`: use column gaps rather than element padding; reader overrides and native-graphics bounds coverage remain independent.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=132-4443

The inspected assistant screenshot has a rounded dark message surface containing body text, an image slot and another paragraph in a vertical stack. Structured context specifies a 16 px gap and M3 body/medium. Apply the gap to markdown blocks only, as the refined ticket specifies; attachment/metadata gaps and bubble padding retain their existing values.

## Change

Change `MarkdownTextStyle.blockSpacing` from 12 dp to 16 dp. Settled and streaming thread messages inherit it, including blocks within list items and quotes; sibling list items stay at 4 dp. Reader overrides stay at 12 dp, and user paragraphs, attachments, metadata and bubble padding stay unchanged. This is one observable behaviour with settled/streaming checks, not separate deliverables. No new types, state, signatures or error branches are needed. Forecast: under 160 written lines across one production file, two existing test files and this plan, within all sizing ceilings. Remote branch #1766 overlaps the renderer through streaming additions already present in this checkout; its diff does not change the spacing default, so the local one-property edit can merge independently.

## Testing strategy

First update existing thread paragraph/nested-spacing expectations to 16 dp and run them red against the 12 dp default. Extend `MarkdownTypographyTest` with settled and streaming bounds fixtures covering paragraph-to-paragraph, paragraph-to-list, list-to-code and code-to-paragraph gaps, plus first/last block alignment with the renderer bounds. Use the existing native graphics setting and unclipped dp bounds; inspect the code surface rather than its padded text. Existing reader assertions retain 12 dp and sibling-item assertions retain 4 dp. Run focused Markdown and MessageBubble classes on Linux, lint, debug assembly, shared-test instrumented compilation and required formatting/pre-verification checks. No device-only test or live evidence is needed for this layout-only change.

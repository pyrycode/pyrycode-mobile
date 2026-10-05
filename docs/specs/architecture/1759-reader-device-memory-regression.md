# Reader memory regression on the native device (#1759)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt`: `MarkdownReaderScreen`, `MarkdownDocument`, and `markdownClip` render the original document and copy from its source.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `withBreaksInLongRuns` supplies display-only line breaks; the landed repair stays unchanged.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/LongRunBreaksTest.kt`: existing pure-function coverage of the repair.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreenTest.kt`: menu, Back and clipboard patterns, including the enabled `copiesOfANoteAtTheReadersBound_areBounded`.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderCaptureTest.kt`: real reader composition on the device.
- `scripts/android-test-gate.py`: `device_only_classes` automatically selects new non-e2e device classes.
- `docs/knowledge/features/markdown-reader-screen.md`: Load and navigate from the thread records native allocation growth for whitespace-free runs and why Robolectric is insufficient.

## Change

Add `MarkdownReaderMemoryTest` under `app/src/androidTest`, exercising the real `MarkdownReaderScreen` with `MarkdownDocument("Big.md", "a".repeat(MAX_MARKDOWN_READER_BYTES))`. Assert the supported bound remains 262,144 bytes. Scroll the actual body and verify its scroll offset advances while the fixed title and menu remain usable. Copy as Markdown and HTML through the real menu and Android clipboard; both plain texts must equal exactly `MAX_CLIPBOARD_CHARS` original `a` characters, and HTML must be present and at most that bound. Use Back to leave the reader, unmount it and assert the return surface is displayed. Production rendering, clipboard code and limits remain unchanged. This is one regression deliverable, roughly 150 written lines including the plan, with no production API changes or consumer updates. No in-flight feature branch overlaps the new test file.

## Testing strategy

The test is device-only because Android's native text layout allocation failure does not reproduce under Robolectric. For the red control, temporarily use the pre-`ef587984` renderer and run the new class on `pixel2Api33Atd`; observe failure/termination, then restore the landed renderer before green checks. No temporary production change will be committed.

Run the new class focused on the device, then `python3 scripts/android-test-gate.py ui` (explicitly required by this ticket) and inspect fresh XML counts and the new method's passing case. Separately run the entire `MarkdownReaderScreenTest` device class, inspect its executed/failed/skipped counts, and confirm `copiesOfANoteAtTheReadersBound_areBounded` ran without an ignore. Keep counted evidence in the PR; no screenshot infrastructure or live-Claude scenario is needed for this local regression of existing behavior.

Run focused JVM `LongRunBreaksTest` and `MarkdownReaderScreenTest`, lint, debug assembly, Android-test Kotlin compilation and forced Spotless. After the last merge of main and push, run the whole debug unit/shared suite, debug assembly and `scripts/pre-verify.py --gradle` with the PR body.

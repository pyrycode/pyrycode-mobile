# #1533 — Reader list items wrap to the gutter

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` — `ListBlock`, the only list renderer; `buildInline`; `MarkdownPresentation.Reader`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderScreen.kt` — passes `MarkdownPresentation.Reader`; untouched.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTypographyTest.kt` — `assertBody` matches item text and markers as separate nodes in the reader cases.
- `app/src/sharedTest/java/de/pyryco/mobile/ui/conversations/thread/MarkdownReaderDesignTest.kt` — the reader's Figma-coordinate tests; the new test goes here.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=553-2574

`List` (`553:2774`) is a column of single text paragraphs with a 6px gap, each item written as `•  <text>` in `material-theme/body/large` (16/24, 0.5 tracking) on `Schemes/On Surface`. The marker is part of the paragraph, so the first item's wrapped line ("version") starts at the 20px gutter, not under the item text.

## Change

Today `ListBlock` draws every item as a `Row` of the marker, an 8dp spacer and a content `Column`, so a wrapped line hangs under the item text (about 34px). In the `Reader` presentation only, a non-task item whose first block is a paragraph now renders that paragraph as one `Text` whose content is the marker, two spaces, then the paragraph's inline content, so continuation lines return to the list's start edge as the frame draws them. The item's later blocks (a nested list, a second paragraph) stay indented under the item by a fixed reader continuation indent, since the frame has no nested content to follow and nesting must still read as nesting. Ordered items take the same path with their `N.` marker. Task items keep their `TaskMark` row, and the `Thread` presentation keeps today's hanging row unchanged, because the ticket and its frame cover only the reader.

## Testing strategy

New Robolectric test in `MarkdownReaderDesignTest`: a reader bullet long enough to wrap at 412dp renders as one text node that starts with `•`, has more than one line, sits at the 20dp gutter, and whose second line's left offset is 0 — so the continuation starts where the marker starts. `MarkdownTypographyTest`'s reader cases change their expectation: item text is matched as a substring and markers are counted as `•  `-prefixed item nodes rather than standalone nodes, citing `553:2574`; the thread cases keep their standalone-marker assertions. `referencePlacesBarRuleAndBodyAtFigmaCoordinates` already pins the first item's top at 265.

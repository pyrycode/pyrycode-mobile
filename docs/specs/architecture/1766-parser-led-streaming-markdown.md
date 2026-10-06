# Parser-led streaming markdown (#1766)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt`: `StreamingAssistantBody`, `StreamingAssistantBodyView`, `nextStreamingRevealLength`.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`: `MarkdownText`, `MarkdownBlock`, `appendInline`, `appendInlineChildren`, `singleTildeRuns`, `routeMarkdownLink`.
- `org.jetbrains:markdown` 0.7.3 sources: `MarkdownParser.parseInline`, `LinkReferenceDefinitionProvider`, `GitHubTableMarkerProvider`, `GitHubTableMarkerBlock.splitByPipes`, `DelimiterParser`.
- PR #1810 and its six verdicts, whose probe inputs became fixtures here. Its branch is left as it is.

## Context

Until now the streaming body parsed the whole revealed prefix with the caret glyph appended, every 33 ms tick. Half-typed constructs showed their punctuation and changed meaning as source arrived, and the caret could land inside code or a link target. PR #1810 froze blocks with hand-written rules and scanned pending inline syntax by hand; six review rounds each found the next case those rules missed. The redesign on the ticket (2026-10-06) lets the parser make those decisions, following the method of mature streaming renderers. No library is added, since no mature Kotlin one exists.

## Design

`StreamingMarkdown.kt` is new and holds no Compose code beyond `@Immutable`.

- **Freeze rule.** `StreamingMarkdownCache.update` parses only the tail, from the line holding the first mutable top-level block. Block N freezes, in order, once block N+1 exists and its first line ends with a newline. In 0.7.3 no block-closing decision reads past the next non-blank line.
- **Definition exception.** `holdsDefinition` keeps a block, and everything after it, mutable when one of its lines opens `[` after indentation, `>` or a list marker and that label is still open within 999 characters, or is closed as `]:` with no complete blank line after the `]:` yet. The seeded fuzz showed the blank line must follow the `]:`, not the block: the label itself crosses blank lines, so `[b\n…\n\n\nc]: /u` swallows a block that a block-end check had already frozen.
- **Table after a paragraph.** In 0.7.3 `GitHubTableMarkerProvider.interruptsParagraph` is false, so pipe lines after a paragraph, list item or quote stay in that block. Desktop's two-line rule is not needed; the requested replay cases pass with the plain rule.
- **Reuse.** A frozen block keeps one `StreamingMarkdownBlock` instance, keyed by absolute offset. `StreamingMarkdownText` draws each through the existing `MarkdownBlock`, so Compose skips frozen blocks when the tail grows or the caret blinks.
- **Pending inline.** `probeInline` works on the trailing paragraph, heading or table cell while it can still grow. It appends closers for unpaired openers, innermost first, and re-runs `MarkdownParser.parseInline` until nothing new pairs, capped at `MAX_PROBE_PARSES` (12). Openers are emphasis and tilde runs that pass the left-flanking check, backtick runs, and `](` pairs; link closers try `)`, `>)`, `")` and `')`. The renderer takes a synthetic range: characters in it never render, and a construct closed inside it renders plain, without style or link.
- **Pending header.** A trailing paragraph whose first line has an unescaped pipe and at most a partial delimiter row is probed with a delimiter row sized by `splitByPipes`; a growing `---` under a pipe line, which 0.7.3 reads as a setext heading, is probed the same way. If the probe parses as a table, the header cells show as plain text joined by spaces.
- **Caret.** Appended to the trailing leaf's built text, transparent when blinked off, so a blink never re-wraps. When the reply ends in a fence, rule or other non-inline block, it takes its own line, blinking by alpha.
- **Settlement.** `AssistantMessage` still switches to `MarkdownText(message.content)` when `isStreaming` clears, so early completion shows all source at once and malformed source keeps the existing fallback.

## Testing strategy

- `StreamingMarkdownTest` (JVM): every prefix of every named case replayed through one cache, requiring reused blocks plus the re-parsed tail to equal a full parse node for node at absolute offsets, and frozen blocks to stay the same instances. A seeded fuzz of block-starter lines runs the same check over 2000 documents; 90000 documents across three seeds passed during development. Probe and header unit cases.
- `StreamingMarkdownTextTest` (Robolectric, `sharedTest`): reuse counters through the optional `StreamingMarkdownObserver` parameter; equal-width settlement for each named case; the pinned `see ~/path` change; pending inline, header and caret rendering; clock-controlled pacing; early completion and malformed fallback; settlement through `MessageBubble`.
- Rung 3: `InteractiveStreamE2ETest.interactiveTurn_markdownReply_rendersFormattedBody`, registered in the curated live list.

## Security review

**Verdict:** PASS

- Links: a link completed only by a synthetic closer renders as plain text with no `LinkAnnotation`, so no unfinished or invented destination can be tapped. Links closed in the original source keep `routeMarkdownLink` and the `http`, `https`, `mailto` and markdown-path allowlist unchanged.
- Synthetic text: probe closers and the probe delimiter row are never drawn, copied or logged. Copy still takes `Message.content`, the original source.
- Denial of service: probing is bounded by `MAX_PROBE_PARSES` extra inline parses of one leaf per update, the definition label scan by 999 characters, and the pending header by one parse of the tail. Completed blocks are not re-parsed, so per-tick work is lower than before for multi-block replies. Table fan-out bounds are unchanged.
- Trust boundaries, storage, credentials, network, cryptography and Android components: unchanged. The streaming text is the same daemon-authored reply the settled renderer already draws.
- Logging: no new log lines. The test observer is an optional parameter that production never passes.

## Revisions

- 2026-10-06: built by hand after PR #1810 hit the rework limit. The definition settle point moved from the block end to the label's `]:` after the fuzz found a counterexample.

## Documentation handoff

Done in the same change: ADR 0002's streaming consequence, the streaming sections of `message-bubble.md` and `markdown-text.md`, and the live scenario in `docs/e2e-interactive-stream.md`. The live result is still owed.

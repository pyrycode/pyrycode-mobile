# MarkdownText

GFM renderer for assistant-message content (#129; fenced-code styling extended in #130; tables, task lists and strikethrough added in #681 by switching the parser flavour from CommonMark to GFM; code-block chrome restyled and given a per-block copy control in #657). Single public composable that takes a markdown source string and renders it into native Compose primitives — body styling supplied by the caller or default theme token, every link routed through `LocalUriHandler`, every container colour pulled from `MaterialTheme.colorScheme`. No Android-View interop seam; the renderer is pure Compose end-to-end.

Package: `de.pyryco.mobile.ui.conversations.components` (`app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt`). Sibling of [`MessageBubble`](./message-bubble.md). Library choice rationale: [ADR 0002](../decisions/0002-markdown-renderer-library.md) — the parser library choice (`org.jetbrains:markdown`) and the "own the renderer" decision still stand; the *flavour* it names (`CommonMarkFlavourDescriptor`) does not — #681 switched to `GFMFlavourDescriptor` and the ADR predicted exactly that move in its Consequences section. [ADR 0003](../decisions/0003-syntax-highlighter-library.md) covers the code-block syntax highlighter, unaffected by the flavour change.

## What it does

Parses an input string with `org.jetbrains:markdown`'s `MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(...)` and walks the resulting AST, dispatching each top-level block to a Compose composable. Block kinds supported: ATX headings (H1–H6), paragraphs, ordered and unordered lists, task lists, blockquotes, fenced code blocks, indented code blocks, pipe tables. Inline kinds supported: bold (`STRONG`), italic (`EMPH`), inline code (`CODE_SPAN`), inline links (`INLINE_LINK`), strikethrough (both `~~double~~` and `~single~` tilde forms). `org.jetbrains:markdown` offers no per-construct registration, so the single flavour switch that unlocks these three constructs also changes what two *unwanted* node kinds parse as — bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) — see [Edge cases / limitations](#edge-cases--limitations) for what they render as and why. Unsupported AST node kinds (HTML, horizontal rules) fall back to rendering the raw source text of that node using the selected body style — the renderer never throws. Images are a separate case, not a fallback one — see the corrected claim in [Link safety](markdown-text-internals.md#link-safety--scheme-allowlist).

## Public surface

```kotlin
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    onOpenMarkdownPath: ((String) -> Unit)? = null,
    style: MarkdownTextStyle = MarkdownTextStyle(MaterialTheme.typography.bodyMedium),
)
```

`MarkdownTextStyle` carries `body`, `blockSpacing`, `listItemSpacing`, optional `code`, and
`presentation` (default `MarkdownPresentation.Thread`). Finished and streaming
[`MessageBubble`](message-bubble.md) use M3 `bodyMedium` (14sp/20sp), 16dp block gaps and
4dp sibling-item gaps. The [reader](markdown-reader-screen.md#what-it-does) selects
`MarkdownPresentation.Reader`, `bodyMedium` (14sp/20sp, 0.25sp tracking) with
`onSecondaryContainer` prose and table cells (#2283), 16dp ordinary block gaps,
6dp sibling-item gaps (#2285) and 12sp/20sp monospace code (#2284).

The ordinary 16dp gap also separates non-heading blocks inside list items and quotes in Thread (#2273)
and Reader (#2285). Reader outer padding stays measured-bar-plus-28dp at the top,
20dp at the sides and 16dp at the bottom.
Shared ATX heading spacing (#2278) overrides the ordinary gap between rendered
siblings: 20dp before a heading, otherwise 12dp after a heading. Consecutive
headings have one 20dp gap, with the next heading taking precedence. This applies
at settled/streaming roots and inside list items and quotes, after syntax-only
children are filtered. First and last blocks have no outer heading spacing,
including a single-heading container. SETEXT retains raw-body fallback and ordinary
gaps; sibling-item gaps remain configured. Text scales with font size while these
gaps stay in dp. User paragraphs, attachment/metadata gaps and bubble padding belong
to [MessageBubble](message-bubble.md) and retain their independent values.
See the [bounds-test guidance](message-bubble-testing.md#testing) for settled and streaming coverage.

The reader uses untrimmed line-height boxes for headings and prose, including nested list and quote
text; Android's default trimmed boxes otherwise shorten the Figma paragraph and list. Thread line
boxes stay as before. Headings and tables keep their element-specific typography. The presentation
choice changes quote and list appearance; code panels, parser, inline links and routing stay shared.

**No `color` parameter** — body text inherits `LocalContentColor.current`; assistant bubbles and the reader
supply their body colours: assistant bubbles inherit `onSurface`, while reader prose
uses `onSecondaryContainer` and quotes use `onSurfaceVariant`. Plain ATX heading text explicitly uses the active theme’s
`onSecondaryContainer`; inline links and code retain their existing span treatments.
All six levels share the [markdown-only heading scale](shared-typography.md#markdown-headings)
in settled and streaming Thread/Reader rendering. SETEXT headings retain the raw body fallback,
including their source underline, except for the streaming pending-table-header probe below.

**`onOpenMarkdownPath` is opt-in.** Only the assistant reply paths pass this callback to open workspace
notes. With the default `null`, including inside the reader, workspace paths stay inert. Allowed
`http`, `https` and `mailto` links use the ambient `LocalUriHandler` in either case. See
[Markdown-path links](markdown-text-internals.md#markdown-path-links-since-1050).

## How it works

Parse memoisation, block and inline dispatch, tables, task lists, strikethrough, code blocks, the link-safety allowlist, markdown-path links and the file-private spacing constants moved to [MarkdownText — how it works](markdown-text-internals.md) under the docs guard's size cap (#1533).

Since #2270, an explicitly completed inline code span becomes tappable when its
whole trimmed content is one nonempty, whitespace-free target admitted by the
shared external-link check: http, https, mailto or an allowed Obsidian open link.
The display keeps edge whitespace, monospace font and code background, adding the
authored-link colour and underline. One annotation covers the entire displayed
code content and opens the exact trimmed target, including its case and escapes,
through the existing routing and failure handling. Workspace paths inside code,
ordinary code, mixed text, refused targets, bare web/email prose and fenced blocks keep
their existing behavior. See [inline dispatch](markdown-text-internals.md#inline-dispatch)
for the admission boundary.

Since #2272, admitted bare Obsidian open URLs in ordinary prose also use authored-link
styling and Android routing. Bare web/email URLs remain plain. Exact source ranges,
punctuation fallback and protected code/label containers are described in
[inline dispatch and link safety](markdown-text-internals.md#inline-dispatch).

## Configuration

- **Library dependencies:** `implementation(libs.jetbrains.markdown)` (parser; catalog pin `jetbrainsMarkdown = "0.7.3"`) and `implementation(libs.snipme.highlights)` (code-block tokeniser since #130; catalog pin `snipmeHighlights = "1.1.0"`) in `app/build.gradle.kts`. No KSP, no kapt, no proguard rules for either.
- **Two strings since #681, plus one since #657:** `markdown_task_mark_checked` / `markdown_task_mark_unchecked` — static TalkBack labels for the task-mark, chosen by a boolean, carrying no format argument and no daemon-authored text. `cd_thread_copy_code` ("Copy this code block") is the accessible name for each code block's copy control, in the same `cd_thread_*` family as `MessageMetaRow`'s `cd_thread_copy_message`. Otherwise renders the input markdown verbatim; no other resource lookup.
- **No renderer-local theme overrides.** Block colours come from `MaterialTheme`; prose comes from `MarkdownTextStyle.body`, and ATX headings use `MarkdownTypography` without changing shared Material roles. The reader quote rule uses `outlineVariant`; thread quotes use `onSurfaceVariant` for contrast against their bubble. Shared text roles come from the [app type ramp](shared-typography.md). The code copy glyph uses `LocalContentColor` at `CODE_COPY_ALPHA`.
- **No DI.** Pure leaf composable; no Koin module touched.

## Previews

`MessageBubble.kt` carries the canonical preview pair for this renderer (for thread defaults), not `MarkdownText.kt` itself. `MessageBubbleMarkdownLightPreview` and `MessageBubbleMarkdownDarkPreview` both render a file-private `MARKDOWN_PREVIEW_FIXTURE` that exercises every supported element (h1/h2/h3, bold, italic, inline code, link, unordered list, ordered list, a mixed task list, both strikethrough forms beside a home-relative-paths line that must *not* strike, a three-column table with all three alignments, blockquote, fenced code blocks for Kotlin / JSON / Bash / Markdown) inside the `MessageBubble` host. Satisfies #129's AC6, #130's AC7 and #681's AC5.

## Streaming (since #1766)

`StreamingMarkdownText(source, caretVisible, …)` draws a reply while it streams, through the same block and inline walkers as `MarkdownText`. `StreamingMarkdown.kt` decides two things the parser does not: when a top-level block can no longer change, and how the one growing inline leaf at the end looks while a construct in it is open. The method follows mature streaming renderers, Vercel's Streamdown and Microsoft's SwiftStreamingMarkdown, without adding a library, since no mature Kotlin one exists. Plan and security review: `docs/specs/architecture/1766-parser-led-streaming-markdown.md`.

- **The parser decides block boundaries.** `StreamingMarkdownCache` parses only the tail, from the line holding the first mutable top-level block. Block N freezes, in order, once block N+1 exists and its first line has ended: in `org.jetbrains:markdown` 0.7.3 no decision that closes a block reads past the next non-blank line. A frozen block keeps one `StreamingMarkdownBlock` instance, keyed by absolute offset, so Compose skips it on every later tick and caret blink.
- **Definitions are the exception.** The 0.7.3 definition matcher reads a label across lines, blank ones included, for up to 999 characters, and a title up to the next blank line. A block with a line opening `[` after indentation, `>` or a list marker stays mutable, with every block after it, while that label is open, or once closed as `]:` until a complete blank line follows the `]:`. The blank line must follow the `]:` and not the block, because the label itself can cross blank lines; the seeded fuzz found that case.
- **A table cannot interrupt a paragraph in 0.7.3.** Pipe lines after a paragraph, list item or quote stay in that block, so desktop's two-line rule (pyrycode-desktop#1751) is not needed here. `StreamingMarkdownTest` replays all three cases.
- **Pending inline constructs show their text plain.** For the trailing paragraph, heading or table cell while it can still grow, `probeInline` appends closers for the openers the parse left unpaired, innermost first, and re-runs the parser's own `parseInline` until nothing new pairs, at most `MAX_PROBE_PARSES` (12) extra parses. What a synthetic closer completes renders without its punctuation, style or link, and the synthetic characters never render, so nothing unfinished can be tapped. Code, autolinks and link targets are opaque to the probe. Single tildes use this renderer's own pairing.
- **URL-only code waits for real completion (#2270).** A synthetic backtick closer
  leaves it plain and inert. Its real single- or multiple-backtick closer enables
  the same eligibility rule as settled rendering. Caret blinks stay outside its
  annotation; freezing the block and settling the reply preserve its link and styles.
- **Bare Obsidian prose waits for the real source end (#2272).** `appendLeaf`
  carries the arriving source-end offset into inline rendering, including probes.
  A candidate touching that end stays plain even when successive prefixes such as
  `file=No` and `file=Note` independently pass admission. A later unsafe suffix
  must never inherit an earlier link. Real whitespace or a containing Markdown
  closer/table-cell separator establishes completion; lexer-token boundaries,
  sentence punctuation, synthetic closers and caret blinks do not. Punctuation
  fallback runs only after this completion check. This applies to paragraph, list
  and table tails; settling removes the pending-end guard and admits only the
  complete final target. Existing probe presentation and caret placement remain.
- **Pending table header.** A trailing paragraph whose first line has an unescaped pipe, followed by nothing or a partial delimiter row, is probed with a delimiter row sized by `GitHubTableMarkerBlock.splitByPipes`. If that parses as a table, its header cells show as plain text with spaces between them. `| A | B |` over a growing `---` reads as a setext heading until the row gains its pipes, so it is probed the same way. The probe row is never drawn.
- **Caret.** Appended to the trailing leaf's built text, never to the parsed source, so it cannot become code content or part of a link target. It is drawn only while the blink is on, as before #1766, so a blink-off frame shows exactly the arrived text. When the reply ends in something else, such as an open fence, it takes its own line and blinks by alpha.
- **Settlement.** For complete markdown the probe pairs nothing, so `MarkdownText` draws the same text and line breaks at the same width, apart from the caret. A bare Obsidian candidate at the arriving source end becomes eligible on settlement; other completed spans and links stay the same. One ambiguity is inherent and pinned by a test: a final `see ~/path` looks like an open strikethrough while streaming, so it shows `see /path` until the reply ends.
- **Test seam.** An optional `StreamingMarkdownObserver` parameter reports each parsed tail and each composed block. Production passes none.

## Testing

`MarkdownTextTest` checks URL-only code admission/rejection, the exact target and
full displayed annotation range including edge spaces, retained code styles and
authored-link colour/underline, workspace-path exclusion and unchanged fences.
`MarkdownLinkTapTest.finishedCodeSpanUrls_useAuthoredLinkRouting` and
`streamingCodeSpanUrls_useAuthoredLinkRouting` cover repeated activation and
Obsidian failure notices. Native-graphics
`StreamingMarkdownTextTest.codeSpanUrl_waitsForItsRealCloser_andSettlesUnchanged`
checks single/multiple backticks, synthetic-to-real completion, caret exclusion,
frozen-block reuse and settlement. The unchanged
`a_bare_url_renders_as_text_and_is_not_a_link` remains the bare-web regression.
All four named methods are present and passed in retained full-gate XML and in
the fresh focused Linux reports at `2dca08a77de10a46ac078b1000ad87d2df0978ee`.
See [accepted #2270 counts and evidence](development-verification-gates.md#managed-build-heap-and-linux-acceptance-2270)
for the cached full-suite result, 124 freshly executed focused checks and device gates.

Bare-Obsidian coverage combines `BareObsidianLinksTest`, `MarkdownTextParsingTest`,
`MarkdownLinkRoutingTest`, `StreamingMarkdownTest` and native-graphics shared
`MarkdownTextTest`, `MarkdownLinkTapTest`, `StreamingMarkdownTextTest`, alongside
`MarkdownConversionsTest`. Tests pin exact ranges/targets, mixed scheme case,
escapes, punctuation fallback, refused tokens, protected containers and Android
ACTION_VIEW interception with workspace routing enabled. The composite-endpoint
regressions are `admitted bare targets can end in composite inline nodes`,
`bareRanges_endingInCompositeNodes_preserveExactTextAndAndroidTargets` and
`bareCompositeEndpoints_waitForCompletion_andSettleToExactTargets`.
`finishedReferenceLabels_neverActivateEmbeddedBareCandidates` and
`streamingReferenceLabels_neverActivateEmbeddedBareCandidates` cover all reference
forms. The three streaming methods `bareObsidianPrefixes_waitForRealCompletion`,
`bareObsidianBoundaries_completeParagraphListAndTableTails` and
`bareObsidianSettlement_linksTheCompleteTarget` replace source on one composition
and check complete targets through real termination and settlement.

Accepted Linux #2272 verification at `07566472b2eac2574ae4f0e68eed6ddaa5724d67`
(verifier run `00956605aa3ca954c0dbf35d91096e280ec73f9f84a2d7c6bf0b01fc49931603`,
[PR #2304](https://github.com/pyrycode/pyrycode-mobile/pull/2304)) records:

| Configured dispatcher gate | Executed / passed | Failed / errors / skipped |
| --- | ---: | ---: |
| `python3 -m unittest discover -s scripts` | 183 / 183 | 0 / 0 / 0 |
| `python3 scripts/jvm-test-gate.py --with-build-checks` | 6086 / 6086 | 0 / 0 / 0 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py ui` | 335 / 335 | 0 / 0 / 1 |
| `ANDROID_GATE_WAIT_SECONDS=2700 python3 scripts/android-test-gate.py scripted-all` | 22 / 22 | 0 / 0 / 0 |

All named regressions above and the unchanged bare-web test executed and passed
in the complete JVM gate; these are full-suite results, not separate focused runs.
Lint, Spotless, assembly, instrumented Kotlin compilation, pre-verification and
the docs guard also passed. UI used Linux managed Android 13 `pixel2Api33Atd`;
`RenameDialogCaptureTest.renameAtFigmaViewport` was skipped and supplies no visual
pass. Native Compose interception proves exact Android targets; installed Obsidian
delivery was not exercised or required. macOS was outside verification scope.

The accepted verifier bundle retains the verdict and gate logs via
`fleet-task.py evidence 2272 --run <run-id>` (the run above); removed gate-worktree
build paths are not durable artifacts. Supplementary focused Linux reports and
identity (Ubuntu 24.04.5 x86_64, JDK 21, Robolectric API 33) remain in
[recovery-evidence.txt and sibling XML](../../../app/src/test/resources/markdown-2272/recovery-evidence.txt):
150 markdown executions passed at source-equivalent `86dc2f098698297ef9672630bdc73b6b4ec331d2`,
with zero failures/errors/skips. Earlier failing parser/web-container probes remain
separate red XML; subsequent passes do not erase them. See the
[cache timer recovery](conversation-cache-testing.md#owned-coverage-snapshot-acceptance-2291)
for the independently diagnosed row-failure probe.

[Shared reader code coverage and counted Linux evidence](markdown-reader-screen-testing.md#shared-code-panel-coverage-2284)
cover every code form, original-source bounded copying, stationary copy rails,
horizontal scrolling and physical header interception.

[Reader bounds coverage and counted Linux acceptance](markdown-reader-screen-testing.md#ordinary-block-bounds-coverage-2285)
pin 16dp ordinary gaps for both note sources, recursive list/quote content, panel
boundaries, 6dp sibling gaps and unchanged outer padding. Reader heading-specific
20/12dp screen integration remains with #2276.

`MarkdownHeadingSpacingTest` adds 13 native-graphics layout-box tests for settled
and streaming H1–H6, consecutive headings, nested lists/quotes, syntax-only
children, single/first/last headings, preserved indentation and quote bounds,
ordinary/item gaps and font scaling. Reader-presentation fixtures explicitly use
12dp ordinary and 6dp item gaps, including the inline-marker path; that override
proves configurability while the current Reader screen uses 16dp ordinary gaps.
`headingTailKeepsSpacingThroughCompletionAndSettlement` checks each level from a
growing heading through completed-block reuse and settled rendering, apart from
the existing caret behavior.

The [final #2278 verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2288#issuecomment-6257269879)
records Linux acceptance on 2026-10-19 at
`3d23f64b96f327991a6883a3f7e4e9a278a077cb`. The full JVM/build gate executed
**6,012 tests, 6,012 passed, 0 failed, 0 skipped**; its retained XML includes all
13 heading-spacing methods, 17 Reader-design methods, 29 `MarkdownTextTest`
methods and 12 `StreamingMarkdownTextTest` methods, all passed.
Both `headingTailKeepsSpacingThroughCompletionAndSettlement` and
`referencePlacesBarRuleAndBodyAtFigmaCoordinates` are present and passed in that
full gate, rather than a separate focused verifier run. The UI gate executed
334 tests, all passed, with 0 failed and 1 skipped
(`RenameDialogCaptureTest.renameAtFigmaViewport`, unrelated to this change).
Scripted-all executed 22, all passed, with 0 failed or skipped. Lint, Spotless,
debug assembly and instrumented Kotlin compilation were included in the green
JVM/build gate. macOS was outside this Linux ticket's verification scope.
Counted XML was retained in
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2278_{4,5,6}.log`
at documentation time.

The earlier red gate remains historical: the Reader reference expected 16dp
between paragraph and H2 although the renderer and coordinate table used 20dp.
The repaired assertion passed in final acceptance. Keep the independent 16dp
list-to-code assertion: changing all gap expectations together would hide a
regression. The reference deliberately adds 8dp before H2 compared with the earlier 12dp
fixture gap and moves subsequent coordinates with the heading policy; the quote position also reflects the shorter shared code panel
from #2284. Main's bodyMedium typography, ordinary/item gaps and Reader outer
padding remain intact. Reader-screen integration remains assigned to #2276.

`MarkdownHeadingScaleTest` inspects rendered `TextLayoutResult` styles under native graphics,
with dark/light × Thread/Reader cases. Each case checks H1–H6 size, line height, tracking,
weight, sans-serif family and semantic colour while a trailing heading grows, the caret
blinks, a completed heading precedes a growing paragraph, and the reply settles. Reader
cases also assert `Trim.None`; the fifth method pins SETEXT raw-body fallback.

`MarkdownTextTest` checks exact heading text, interior spaces, inline spans and marker-only
headings across six levels. `StreamingMarkdownTextTest` extends pending-inline/caret and
settlement coverage to all levels; `MarkdownLinkTapTest` taps rendered heading links to
exercise platform and opt-in workspace-path routing. Parsing, typography and existing
Reader geometry regressions complete the affected coverage. New Reader-screen integration
coverage belongs to #2276.

The [#2277 verifier verdict](https://github.com/pyrycode/pyrycode-mobile/pull/2282#issuecomment-6251474355)
records Linux acceptance on 2026-10-18 at `5f300ed7ddf321706a849dc33f2da70b33481b55`.
The retained gate-4 JUnit report contains **115 executed, 115 passed, 0 failed, 0 skipped**
across `MarkdownHeadingScaleTest` (5), `MarkdownTextTest` (25), `StreamingMarkdownTextTest`
(12), `MarkdownTypographyTest` (5), `MarkdownLinkTapTest` (9), `MarkdownReaderDesignTest`
(16), `MarkdownTextParsingTest` (28) and `StreamingMarkdownTest` (15). All five heading-scale
methods are present and passed. These are results within the full JVM gate, not a separate
focused verifier run. The full gate executed 5,965 tests: 5,964 passed, 1 failed, 0 skipped.
The failure, `OwnedLegacyCoverageTest.anchorInvariant_subtractingProducerKeepsValidReceivedGapWalk`,
also failed on main `fedb43e1f1593f8b00e966ce480d083a3344f441` and was accepted as inherited,
tracked in [#2269](https://github.com/pyrycode/pyrycode-mobile/issues/2269); it remains a failure.
The counted XML was retained at
`/work/Projects/pyrycode-mobile-agents/logs/verifier-gate_#2277_4.log` at documentation time.
macOS is outside this Linux ticket’s verification scope; no real-Claude acceptance was required.

## Edge cases / limitations

- **Inline-code background paints at glyph-rect bounds, not at a padded rectangle.** `SpanStyle(background = …)` on an `AnnotatedString` span has no horizontal padding option in Compose's text API — the background tints exactly the glyph rect. Short identifiers (`getUserId()`) read fine; longer code spans look tight. **Acceptable for #129.** If the tightness becomes a complaint, the replacement shape is `InlineTextContent` per code span — don't pre-build that now.
- **Soft line breaks collapse to a single space.** `EOL` tokens inside a paragraph map to `append(" ")` rather than `append("\n")`. Matches CommonMark's "soft-break = space" rendering rule. Hard breaks (two trailing spaces or a backslash before the newline) are not yet specially handled — they fall through to the same single-space behaviour. Not in AC.
- **Thread and reader code share bordered panels with visible copy controls** — `Surface(background fill, primaryContainer border, 6dp corners)`, a header bar with a divider above the body when the fence carries a language, monospace text with token colours bound to `MaterialTheme.colorScheme`, horizontal scroll for long lines (no wrap), and a per-block copy control. See [Code blocks](markdown-text-internals.md#code-blocks) for the full mapping; library choice in [ADR 0003](../decisions/0003-syntax-highlighter-library.md).
- **Highlighter language coverage is bounded.** Kotlin / Bash / JSON (via the JavaScript lexer — JSON's grammar is a subset) tokenise; Markdown and any other language fall through to plain monospace but still show the language label. Adding a language is a one-line addition to `resolveSyntaxLanguage` if the library exposes it.
- **Unlabelled fences and indented blocks use `CodeBlock` without a header in both presentations.** Without an info string, the shared panel suppresses its language header and divider.
- **Lists are flat-bulleted.** Unordered lists use `"•"`; ordered lists use `"${index + 1}."` from the 1-based item position within the list. Nested-list indentation depth comes from the recursive `MarkdownBlock` call inside the list item's `Column` — no per-level indent multiplier. **Reader exception (#1533):** a reader item's own wrapped text follows the Figma frame's hanging-free layout (continuation returns to the list edge, not the item text), while a nested block still gets the old fixed hang (`ReaderListContinuationIndent`, 14dp) because the frame draws no nested content to measure against. The thread presentation is untouched and keeps the marker-row hang for every item, wrapped or not.
- **Blockquote appearance is presentation-specific.** Thread paragraphs remain italic with an `onSurfaceVariant` bar; reader paragraphs are regular with an `outlineVariant` bar. Nested non-paragraph blocks recurse through `MarkdownBlock`.
- **No free-text selection.** Prose, tables, lists and quotes use bare `Text`, while code blocks expose block-only copying: thread and reader code panels show `CopyTextControl`. The reader menu separately copies the whole note.
- **`MarkdownText` itself treats `markdown` as final.** Partial markdown still parses, since the JetBrains parser is total, and a construct never closed renders literally. Streaming replies go through `StreamingMarkdownText` instead; see [Streaming](#streaming-since-1766). Streaming uses the default body style.
- **Renderer is total.** The JetBrains parser produces an AST for any input string — there is no exception path. Unsupported element kinds hit the `else` fallback (raw text using the selected body style), so the message is never blank. #681 leaned on exactly this property to switch parser flavours without suppressing anything — see the next two points.
- **Parser flavour is GFM since #681, and that changes more than the three constructs it was switched on for.** `org.jetbrains:markdown` has no per-construct registration — `GFMFlavourDescriptor` is the only off-the-shelf way to reach tables, task lists and strikethrough, and it necessarily also brings bare-URL autolinks (`GFM_AUTOLINK`) and `$…$` inline maths (`INLINE_MATH`) into every message this renderer sees, whether or not that message uses any of the three wanted constructs.
- **Bare web/email URLs and `$…$` render as their own literal characters.** Admitted bare Obsidian open prose is the narrow #2272 exception described in [inline dispatch](markdown-text-internals.md#inline-dispatch). `GFM_AUTOLINK` is a childless leaf with no dispatcher arm, so `appendInline`'s `else` appends its raw text; there is no link annotation to tap. `INLINE_MATH` has children with no arm either, so the `else` recurses and its `DOLLAR` / `TEXT` / `WHITE_SPACE` leaves rebuild the `$…$` span verbatim. Block `$$…$$` reaches `MarkdownBlock`'s own `else` and renders as raw text the same way. `MarkdownTextParsingTest` pins the AST shape each depends on, because the property here rests on an *absent* branch, and an absent branch reddens nothing on its own if the library ever adds one.
- **Task marks are inert by construction.** See [Task lists](markdown-text-internals.md#task-lists) — no `clickable`, `toggleable`, `ToggleableState` or `focusable`, on purpose; a bare `contentDescription` is announced but offers nothing to activate.
- **Table cells never wrap, and a too-wide table scrolls rather than reflows.** See [Tables](markdown-text-internals.md#tables). A short row pads with empty cells; a long row's overflow is dropped with nothing addressable left to recover; `MAX_TABLE_COLUMNS` (32) / `MAX_TABLE_ROWS` (256) truncate a pathologically large table with no visible signal that truncation happened.
- **Table layout is column-major** (a `Row` of per-column `Column`s), where an HTML table is naturally row-major. No problem has been observed from this — Compose's accessibility node sort bands by vertical overlap, which recovers row-major reading order for a TalkBack pass without any code here arranging for it — but it's a consequence of a deliberate layout choice (see [Tables](markdown-text-internals.md#tables)'s column-width rationale) rather than an oversight, and worth knowing before "make the table row-major" is proposed as a simplification.
- **Image sources are not a raw-text fallback, on either flavour — a claim this document previously stated wrong.** `IMAGE` has children under CommonMark and under GFM, so `![alt](url)` renders `!` plus the alt text as a tappable, scheme-gated link. Unaffected by the #681 flavour switch; see [Link safety](markdown-text-internals.md#link-safety--scheme-allowlist) for the corrected entry.

## Related

- [MarkdownText — how it works](markdown-text-internals.md) — parse memoisation, block and inline dispatch, tables, task lists, strikethrough, code blocks, the link-safety allowlist, markdown-path links and the file-private spacing constants; split out under the docs guard's size cap (#1533).
- Ticket notes: [`../codebase/129.md`](../codebase/129.md), [`../codebase/130.md`](../codebase/130.md), [`../codebase/131.md`](../codebase/131.md) (`docs/knowledge/codebase/` is frozen since 2026-09-05; #681 has no per-ticket file — its record is the spec below)
- Specs: `docs/specs/architecture/129-markdown-rendering-assistant-messages.md`, `docs/specs/architecture/130-code-block-rendering-syntax-highlighting.md`, `docs/specs/architecture/131-tool-call-collapsed-expanded-component.md`, `docs/specs/architecture/681-gfm-tables-task-lists-strikethrough.md` (measured AST facts per node kind, the security review, and the `## Revisions` history behind the table-border and cell-trimming fixes), `docs/specs/architecture/768-atx-heading-leading-space.md` (the heading leading-space fix; measured AST facts for `ATX_CONTENT`, security review), `docs/specs/architecture/657-code-block-chrome-and-copy.md` (the design's header/body chrome, the exact-source extraction helpers, and the `inversePrimary`-illegibility rationale for `CODE_COPY_ALPHA`), `docs/specs/architecture/1533-reader-list-wrap-to-gutter.md` (the reader's inline-marker list path and `553:2574`)
- Decisions: [ADR 0002 — markdown renderer library](../decisions/0002-markdown-renderer-library.md) (parser library choice; predicted the #681 flavour swap in its own Consequences section), [ADR 0003 — syntax highlighter library](../decisions/0003-syntax-highlighter-library.md)
- Consumers: [`MessageBubble`](./message-bubble.md) assistant variant (user messages stay plain text); [`ToolCallRow`](./tool-call-row.md) reuses the `internal CodeBlock` for code-ish tool output since #131, with the opt-in `copyable` parameter (#657) left at its `false` default — see [ToolCallRow](./tool-call-row.md)
- Sibling component pattern: [`MessageBubble`](./message-bubble.md) (file-private spacing constants; preview pairing shape)
- Local precedent for `buildAnnotatedString` / `SpanStyle` idioms: `ScannerScreen.kt:252-260`
- Downstream:
  - #184 — streaming caret + animation (landed). Operates at the `MessageBubble` layer. Until #1766 it passed the revealed prefix plus caret through this renderer each tick; see [Streaming](#streaming-since-1766). Partial code fences flow through `CodeBlock` unchanged; the `remember(content, syntaxLanguage)` re-tokenises per reveal tick (sub-millisecond on typical sizes). Tables and task lists flow through the same unchanged-signature path; `singleTildeRuns` re-runs per reveal tick behind its `children.none { TILDE }` early-out rather than being memoised, since `appendInlineChildren` builds outside composition where `remember` is unavailable
  - #657 — code block header/body chrome and per-block copy control (landed). See [Code blocks](markdown-text-internals.md#code-blocks); an unterminated (streaming) fence's partial content still extracts correctly, so #184's per-tick re-render carries no new edge case
  - #680 — live desktop/mobile comparison of the same replies, covering the three constructs this ticket added
  - #1050 — `onOpenMarkdownPath` routes an assistant-reply markdown-path link to [the live linked-note reader](markdown-reader-screen.md#linked-note-live-since-1050) instead of leaving it inert; see [Markdown-path links](markdown-text-internals.md#markdown-path-links-since-1050)
  - #1067 — the reader's [copy and refresh menu](markdown-reader-screen.md#copy-and-refresh-menu-since-1067) reuses this file's own parse: `inlineText`, `MarkdownFlavour`, `isSafeLinkScheme`, `trimmedContent`, `MAX_TABLE_COLUMNS` and `MAX_TABLE_ROWS` all went from `private`/file-private to `internal` for it, visibility-only

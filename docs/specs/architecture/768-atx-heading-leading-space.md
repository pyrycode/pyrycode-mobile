# 768 — ATX headings render with a leading space

Ticket: https://github.com/pyrycode/pyrycode-mobile/issues/768 (`bug`, `security-sensitive`)

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `HeadingBlock` — the defect: it filters `ATX_HEADER` / `WHITE_SPACE` / `EOL` out of the heading node's *direct* children, and the marker whitespace is not there. `trimmedContent` — the helper #681 added for the identical defect in table cells, reused here. `appendInlineChildren` / `appendInline` — the appender whose `else` arm currently recurses into `ATX_CONTENT` and emits its edge whitespace. `isSafeLinkScheme` — the scheme gate the security review has to confirm this change does not widen.
- `app/src/androidTest/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextTest.kt` → `a_message_mixing_every_construct_renders_each_part` — carries #681's `substring = true` heading assertion and the comment that documents this defect; both come out here. `annotatedTextOf`, `render` — the fixtures the new rendered assertions reuse.
- `app/src/test/java/de/pyryco/mobile/ui/conversations/components/MarkdownTextParsingTest.kt` → `firstOfType`, `descendants`, `parse` — the walk helpers the new AST pins reuse, and the file's own convention that a parser-contract test exists so a library bump reddens here rather than silently changing what a reply looks like.
- `docs/knowledge/features/markdown-text.md` § "Heading inline content", § "Tables" (cell trimming), § "Edge cases / limitations" — the overview states the direct-child filter means "the marker tokens never appear in the rendered string", which is the claim this ticket makes true; the table-cell entry records that the same defect class was found by a device run's exact-text assertion and not by preview inspection, which is why the proof here is a rendered exact match rather than a preview.
- `docs/specs/architecture/681-gfm-tables-task-lists-strikethrough.md` — the precedent for the device / no-device test split and for `trimmedContent`'s token-level (not string-level) trimming rationale.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The `Message area` frame #681 anchored to, unchanged by this ticket: a dark thread column of assistant and user bubbles whose body text sits flush against one left edge inside each bubble, with inter-block spacing carried by the column rather than by per-element indent. The frame specifies no heading sample and no per-line indent, so the leading space this fix removes is a character the design never called for; no spacing, container or type token moves.

## Change

`HeadingBlock` stops filtering the heading node's direct children and instead takes the single `MarkdownTokenTypes.ATX_CONTENT` child, walking `trimmedContent()` of it. That drops the leading `WHITE_SPACE` the parser nests inside `ATX_CONTENT` after the `#` marker, and the trailing one the closed form `## Trailing ##` leaves there, while keeping `ATX_CONTENT`'s *interior* `WHITE_SPACE` tokens — which are the real spaces between words, so `` ## `code` and **bold** `` keeps its spacing rather than rendering `` `code`andbold ``. The direct-child filter goes away rather than being extended one level down: once the walk starts at `ATX_CONTENT`, the `ATX_HEADER` marker (opening and, in the closed form, closing) and any direct-child `EOL` are outside the walk by construction.

A marker-only `##` has no `ATX_CONTENT` child at all, so the lookup is `firstOrNull` and a null result contributes nothing to the `AnnotatedString` — the heading renders empty, exactly as it does today, in a renderer this repo documents as total. Nothing else in the file moves; `trimmedContent`'s KDoc gains a line naming its second caller, since it is no longer a table-cell-only helper.

## Testing strategy

Device / no-device split as #681 established.

`MarkdownTextTest` (`androidTest`, the RED test — these fail on `main`):

- Each rendered heading level and the closed form: `# One` / `## Two` / `### Three` / `## Trailing ##` each match `onNodeWithText` on its own text at the default exact comparison.
- A heading mixing a code span, bold and a link renders exactly `code and bold and link` — the exact match is what proves interior spacing survived — with the monospace span over `code`, the bold span over `bold`, and one link annotation.
- A marker-only `##` beside a following paragraph: the paragraph renders, which it cannot do if `HeadingBlock` threw.
- `a_message_mixing_every_construct_renders_each_part` tightens to an exact `onNodeWithText("Release check")`, dropping `substring = true` and the comment above it, which document a defect that no longer exists.

`MarkdownTextParsingTest` (unit, no device) pins the AST facts the fix rests on, so a library bump that moves one reddens here: that an ATX heading nests its content in `ATX_CONTENT` with the marker whitespace inside it; that `ATX_CONTENT`'s interior `WHITE_SPACE` tokens sit between real words (the fact that forbids "filter one level deeper" as the fix); that the closed form's trailing whitespace is inside `ATX_CONTENT` while its closing marker is not; and that a marker-only heading has no `ATX_CONTENT` child.

Touched-scope gate: `./gradlew testDebugUnitTest --tests "…MarkdownTextParsingTest"`, `./gradlew lint`, `./gradlew assembleDebug`, `./gradlew compileDebugAndroidTestKotlin`, and the focused managed-device run of `MarkdownTextTest`. Not an operator-facing daemon flow, so no rung-3 scenario.

## Documentation handoff

Pending for the documentation stage; not written here.

- `docs/knowledge/features/markdown-text.md` § "Heading inline content": rewrite it. It states that the direct-child filter means "the marker tokens never appear in the rendered string", which only becomes true once this lands, and the filter it describes no longer exists — describe the `ATX_CONTENT` nesting and that the edge whitespace is dropped by `trimmedContent`, the same helper the table cells use.
- Same file, § "Edge cases / limitations": remove #768 from the "Two pre-existing rendering defects" bullet, leaving #770.
- Same file, § "Related": remove #768 from the "Filed, not fixed here" line, leaving #770.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** No findings. This file *is* the boundary for daemon-authored text, and the change strictly narrows what crosses it: the token list reaching `appendInlineChildren` goes from "`ATX_CONTENT` recursed through `appendInline`'s `else` arm, plus every unfiltered sibling" to "`ATX_CONTENT`'s own children minus the edge `WHITE_SPACE`" — a subset, with no node kind gaining a dispatcher arm. `trimmedContent` drops list elements by `type == WHITE_SPACE` only; it never rewrites a node, never reaches inside an `INLINE_LINK` or `CODE_SPAN`, and cannot turn a non-link token into a link. One shape worth naming rather than assuming: the design takes `firstOrNull { ATX_CONTENT }`, so a future library version emitting two content children would render only the first. That is content loss, not a security defect, and the direct-children pin in `MarkdownTextParsingTest` is what reddens if it ever happens.
- **[Link safety / `withLink`]** No findings. `isSafeLinkScheme` and the `INLINE_LINK` arm are untouched, and the URL is read from the `LINK_DESTINATION` node's own source span rather than from the child list this change edits — so trimming cannot shift, extend or re-point a destination. A heading carrying `[x](javascript:…)` reaches the same gate it reaches today, and the gate still no-ops the tap while leaving the span drawn.
- **[Tokens, secrets, credentials]** Not applicable, by design: this file holds no credential path. It reads a markdown string and `MaterialTheme` colours, and the change adds no storage, no resource lookup and no string that carries content.
- **[File / storage operations]** Not applicable — no filesystem path is constructed, read or written anywhere in `MarkdownText.kt`, and the change adds none.
- **[Inter-process / Android attack surface]** No findings. Heading content renders into an `AnnotatedString` inside a Compose `Text` — no WebView, no `Intent`, no `PendingIntent`, no exported component, and no path where heading text becomes markup, a URL, a filename or a log line. The change introduces no `contentDescription` and no other announcement path, so no daemon-authored text reaches a semantics string that did not already carry it.
- **[Cryptographic primitives]** Not applicable — no randomness, hashing, comparison-against-a-secret or key material in this file.
- **[Network & I/O]** Not applicable — the renderer performs no I/O; in particular there is still no image loader, so an `IMAGE` node inside a heading fetches nothing.
- **[Error messages, logs, telemetry]** No findings, and deliberately so: the change adds no `Log` / `Timber` call. Heading text is content this device did not write, so logging it — even at debug — would be the leak, and nothing here does.
- **[Concurrency]** Not applicable. `HeadingBlock` is a composable that builds a value during composition and `trimmedContent` is a pure list operation; no coroutine is launched, no scope is owned, no shared state is read or mutated.
- **[Threat model — hostile daemon frame]** No findings, with the amplification question answered rather than waved past: `dropWhile` / `dropLastWhile` are single linear passes over one node's children, so a heading made of thousands of whitespace tokens costs one pass, not a quadratic one, and it still renders as a single `Text` rather than as N composables — this is not the table's fan-out shape and needs no `MAX_*` cap of its own. The heading's cost is bounded by the source length exactly as a paragraph's already is, on every streaming reveal tick alike. An unterminated or pathological heading still parses (the library is total) and still renders as text.
- **[Threat model — UI-side leakage]** Out of scope here and unchanged by this ticket: screenshot, accessibility-eavesdropping and overlay exposure of rendered message content are properties of the thread surface, not of this one-token trim. No ticket filed; nothing in this change alters the exposure.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22

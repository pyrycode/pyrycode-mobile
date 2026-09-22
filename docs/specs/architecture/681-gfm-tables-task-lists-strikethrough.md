# 681 — Render tables, task lists and strikethrough in mobile replies

## Files read

- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MarkdownText.kt` → `MarkdownText`, `MarkdownBlock`, `ListBlock`, `appendInline`, `HeadingBlock`, `isSafeLinkScheme` — the whole renderer; every change in this ticket lands here.
- `app/src/main/java/de/pyryco/mobile/ui/conversations/components/MessageBubble.kt` → `MARKDOWN_PREVIEW_FIXTURE`, `MessageBubbleMarkdownLightPreview` — the canonical preview pair the three new constructs extend.
- `docs/knowledge/features/markdown-text.md` § "Block dispatch", § "Inline dispatch", § "Link safety — scheme allowlist", § "Edge cases / limitations" — the renderer's stated contract. Two of its claims are measured wrong below; the documentation stage owns the correction.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/AssistantMarkdown.tsx` → the file header's contract block and `remarkGfmSubset`'s docstring — desktop's stated posture, including why `gfmStrikethrough()` keeps its `singleTilde` default and why the count of registered constructs is itself part of the contract.
- `../pyrycode-desktop/src/renderer/src/screens/conversation/conversation.css` → `.bubble__markdown table`, `.bubble__markdown th, td`, `.task-mark`, `.task-mark--checked::after`, `.bubble__markdown del` — the visual source for all three constructs, since the Figma file has no counterpart.
- `app/src/main/res/values/strings.xml` — the string-resource convention the two new accessibility labels follow.
- `app/build.gradle.kts` / `gradle/libs.versions.toml` → `jetbrains-markdown` (0.7.3), `androidx-compose-material-icons-extended` — confirms no new dependency is needed: the GFM flavour ships in the parser already on the classpath, and the tick glyph comes from an icon set already declared.

### Parser facts, measured not assumed

The `org.jetbrains:markdown` 0.7.3 jar ships no sources, so every AST claim below was measured by parsing the sample against both flavours out of that exact jar and dumping the tree. The measurements are what the design rests on; where one contradicts the feature overview, the measurement wins.

| Source | CommonMark AST | GFM AST |
|---|---|---|
| `\| A \| B \|` + delimiter + rows | one `PARAGRAPH` of `TEXT` pipes | `TABLE` → `HEADER`, `EOL`, `TABLE_SEPARATOR` (whole delimiter line, one token, direct child of `TABLE`), `EOL`, `ROW`* |
| `- [ ] x` | `LIST_ITEM` → `LIST_BULLET`, `PARAGRAPH("[ ] x")` | `LIST_ITEM` → `LIST_BULLET`, `CHECK_BOX("[ ] ")`, `PARAGRAPH("x")` |
| `~~x~~` | `TEXT("~~x~~")` | `STRIKETHROUGH` → `TILDE`, `TILDE`, `TEXT`, `TILDE`, `TILDE` |
| `~x~` | `TEXT("~x~")` | `TILDE`, `TEXT`, `TILDE` — **no `STRIKETHROUGH` node** |
| `https://ex.com` bare | `TEXT`, `:`, `TEXT` | `GFM_AUTOLINK` — a childless leaf |
| `$20 and $30` | three `TEXT` leaves | `INLINE_MATH` → `DOLLAR`, `TEXT`, `WHITE_SPACE`, `DOLLAR`, then `TEXT` |
| `<b>x</b>` | `HTML_TAG`, `TEXT`, `HTML_TAG` | identical |
| `![a](u)` | `IMAGE` → `!`, `INLINE_LINK` | identical but for `LINK_DESTINATION`'s child token kind |

Inside a `HEADER` / `ROW`, children alternate `TABLE_SEPARATOR` (`\|`) and `CELL`; a `CELL`'s own children are ordinary inline nodes, so the existing inline walker works on them unchanged.

## Design source

**Figma:** https://www.figma.com/design/g2HIq2UyPhslEoHRokQmHG?node-id=16-8

The node is the conversation screen: a dark thread of rounded message bubbles, assistant bubbles start-aligned in `secondaryContainer` and user bubbles end-aligned, each holding body-medium paragraphs above a muted timestamp/copy meta row. It supplies only the **container** for this ticket's content — that container is unchanged here, #644 landed it. Confirmed against the ticket's whole-file component search: the file has **no** table, task-mark or struck-text counterpart, so the three constructs take their treatment from desktop's `conversation.css` mapped onto this app's M3 tokens, per the table in § Design below. The visual-fidelity check for this ticket is therefore against desktop plus the extended preview pair, not against a Figma frame.

## Context

Desktop #1079/#1080 shipped these three constructs; mobile renders all three as their raw source characters, so a reply composed on desktop degrades when the same conversation is continued on the phone. `org.jetbrains:markdown` exposes no per-construct registration — the only off-the-shelf GFM entry point is `GFMFlavourDescriptor`, which brings bare-URL autolinks and `$…$` maths along with the three wanted constructs. Settling what those two render as is part of this ticket (AC4), not a follow-up.

No ADR is warranted. The parser library choice is already ADR 0002's; this changes a flavour within that choice, and the reasoning belongs in the feature overview the documentation stage updates.

## Design

All production changes land in `MarkdownText.kt`, plus the preview fixture in `MessageBubble.kt` and two strings. `MarkdownText`'s public signature does not change; `MessageBubble` remains its only caller.

### Flavour

`MarkdownFlavour` becomes `GFMFlavourDescriptor()`. Its constructor flags configure only the HTML generating providers, which this renderer never uses — it walks the AST itself — so the defaults are inert here.

### Token-to-M3 mapping

| Desktop CSS | This renderer |
|---|---|
| `table { display: block; overflow-x: auto }` | a `Box` carrying `horizontalScroll`, wrapping the table content |
| `th, td { border: 1px solid var(--color-primary-container) }`, collapsed | `colorScheme.primaryContainer`, drawn as collapsed edges (below) |
| `th, td { padding: var(--space-1) var(--space-3) }` | `TableCellPadding`, a new file-private constant |
| `th`'s UA bold | `FontWeight.Bold` on header cells |
| `th, td { text-align: start }` unless the delimiter row declares otherwise | `TextAlign.Start` default, overridden per column |
| `.task-mark { width/height: 14px; border: 1px solid var(--color-on-surface-variant); border-radius: 3px }` | `TaskMarkSize`, `TaskMarkBorderWidth`, `TaskMarkCornerRadius`; `colorScheme.onSurfaceVariant` |
| `.task-mark--checked::after` — a geometric tick in the same colour | `Icons.Filled.Check` tinted `onSurfaceVariant`, sized `TaskMarkTickSize` |
| `del { color: var(--color-on-surface-variant) }` + the UA line-through | `SpanStyle(textDecoration = LineThrough, color = onSurfaceVariant)` |

Every size is a named file-private constant in the existing block at the top of the file, per the ticket and the file's own precedent. No `.sp` literal is introduced; text sizes stay on `MaterialTheme.typography`.

### Tables

`MarkdownBlock` gains a `GFMElementTypes.TABLE` arm dispatching to a new private `TableBlock`.

`TableBlock` reads the node into a rectangular grid before laying anything out: the `HEADER`'s `CELL` children are row 0, each `ROW`'s `CELL` children a further row, and the column count is row 0's size, bounded by `MaxTableColumns` and `MaxTableRows` (see the security review — the grid is one composable per cell, with no lazy layout, so its extent is a fan-out budget over untrusted text). A short row reads as empty cells and a long row's overflow is dropped — the library's own generator truncates the same way, and the dropped text is already fused into a trailing separator token by the lexer, so there is nothing addressable to render. Column alignments come from the delimiter row via the pure helper below, defaulting to `Start` for any column the delimiter row does not describe.

Layout is **column-major** — a `Row` of per-column `Column`s, each `Column` at `IntrinsicSize.Max` width with its cells at `fillMaxWidth` — so a column's width is its widest cell and `textAlign` has a box to align within. Row heights stay in step across columns without a measuring pass because **cells do not wrap**: each cell is one line of `bodyMedium`, whose `lineHeight` is fixed by the type style rather than by the glyphs, so a monospace code span or a bold header sits at the same height as plain text. Non-wrapping is also the behaviour AC1 asks for — a table too wide for the bubble scrolls rather than reflowing.

Collapsed borders: each cell draws its **top and start** edges in a `drawBehind`, and the table content `Row` draws its own **end and bottom** edges the same way. Every internal line is therefore drawn once, at `TableBorderWidth`, instead of the doubled seam a per-cell four-sided `Modifier.border` would leave. The outer edges sit on the scrolling content rather than on the viewport, so the table's right edge travels with the scroll as desktop's does.

Contracts:

```kotlin
internal enum class TableColumnAlignment { Start, Center, End }
internal fun parseTableAlignments(delimiterRow: String): List<TableColumnAlignment>
```

`parseTableAlignments` splits the delimiter row on `|`, drops a blank leading and trailing segment (GFM permits the row with or without outer pipes), and reads each spec: leading and trailing `:` → `Center`, trailing `:` only → `End`, anything else → `Start`. Pure, no Android types, unit-tested.

### Task lists

`ListBlock` looks for a `GFMTokenTypes.CHECK_BOX` child on each `LIST_ITEM`. When present it renders a new private `TaskMark(checked)` in the marker slot **instead of** the `•` / `n.` marker — GFM replaces the bullet rather than adding to it — and `CHECK_BOX` joins the existing filter set so the token never reaches the block dispatcher as a stray `[ ]`. Items without a checkbox in the same list keep their bullet, so a mixed list renders correctly.

`TaskMark` is a `Box` sized `TaskMarkSize` with a rounded border, holding a `Check` icon only when checked. It is drawn inside a `Box` carrying `TaskMarkTopInset` so the mark sits on the first text line rather than at the item's top edge (desktop's `vertical-align: -3px`).

Accessibility, and the inertness AC2 demands: the mark carries `Modifier.semantics { contentDescription = … }` reading a new string — `task_mark_checked` / `task_mark_unchecked` — and **nothing else**. No `Modifier.clickable`, no `toggleable`, no `ToggleableState` semantics, no `focusable`. A bare `contentDescription` on a non-clickable node is announced by TalkBack and offers no action, which is exactly "exposes state, is not actionable". The test asserts the absence of a click action, not merely the presence of the description.

```kotlin
internal fun isCheckedTaskMark(rawCheckBox: String): Boolean
```

reads the token's raw text (`"[x] "`, `"[X] "`, `"[ ] "`) and is unit-tested.

### Strikethrough

Two paths, because the parser only recognises one of the two forms AC3 names.

**Double tilde** is a node: `appendInline` gains a `GFMElementTypes.STRIKETHROUGH` arm that filters out the `TILDE` children and recurses into the rest inside `SpanStyle(textDecoration = LineThrough, color = struck)`.

**Single tilde is not a node.** `~x~` leaves bare `TILDE` tokens as siblings (measured above; the library's `StrikeThroughDelimiterParser` takes no single-tilde option, and its no-arg constructor is the only one). Desktop strikes it, because micromark's `singleTilde` default is on, so parity requires pairing the bare tokens at render time. That pairing is **flanking-aware**, not positional, and the reason is the hazard desktop's docstring names and reports as non-reproducing: a line carrying two home-relative paths (`~/a ~/b`). Under naive pairing that line strikes `/a `; under GFM's rule the second tilde is preceded by whitespace, so it cannot close, the first never finds a closer, and both render literally. A naive implementation would therefore ship a divergence the criterion exists to prevent.

Walking children one at a time cannot pair siblings, so the recursion gains one seam: every site that currently iterates an inline node's children routes through a new `appendInlineChildren(children, …)`, which pairs first and then walks. Those sites are `appendInline`'s `else` branch, its `EMPH` and `STRONG` arms, the new `STRIKETHROUGH` arm, and `HeadingBlock` — each already passing a filtered list, and filtering does not disturb pairing because the flanking test reads the source string at the token's own offsets, not its neighbours in the list.

```kotlin
internal fun tildeCanOpen(source: CharSequence, start: Int, end: Int): Boolean
internal fun tildeCanClose(source: CharSequence, start: Int, end: Int): Boolean
```

Left-flanking: the following character is not whitespace, and either it is not punctuation or the preceding character is whitespace or punctuation. Right-flanking is the mirror. Start-of-input and end-of-input count as whitespace. For `~` both of GFM's delimiter kinds collapse to these two, so `canOpen` is left-flanking and `canClose` is right-flanking. Both are pure over a string and offsets, so both are unit-tested directly.

The private sibling scan that consumes them is a **single forward pass** holding at most one pending opener: at each tilde, close the pending opener if this token can close, otherwise make it the pending opener if it can open. Closing is tested first because a tilde may be both, and CommonMark resolves that the same way. Linear by construction — see the security review's finding on why "search forward for the next closer" is the wrong shape here. Anything left unpaired renders as its literal source characters.

The three inline colours (code-span background, link, struck) exceed what is worth passing positionally, so they travel as one private `InlineColors` holder rather than as a sixth parameter. That is caused by this change, not a drive-by tidy.

### What the flavour change must not introduce (AC4)

No production code, and that is the design decision rather than an omission: the renderer's existing `else` branches already render every one of these as its literal source characters, and the property is reached by keeping them rather than by adding suppression.

- **Bare URL** — `GFM_AUTOLINK` is a childless leaf, so `appendInline`'s `else` appends its raw text. No link annotation exists to be tapped.
- **`$…$`** — `INLINE_MATH` has children, so the `else` recurses; `DOLLAR`, `TEXT` and `WHITE_SPACE` are all leaves that append their own source text, reconstructing the span exactly. Block `$$…$$` reaches `MarkdownBlock`'s `else` and renders as raw text.
- **Raw HTML** — `HTML_TAG` / `HTML_BLOCK` are unchanged by the flavour and still render as visible characters.
- **Images** — unchanged by the flavour; no fetch on either flavour. See Open questions for a measurement that contradicts the feature overview here.
- **Headings, lists, inline links, blockquotes, fenced code** — untouched dispatcher arms.

Each of these is pinned by a test rather than left to rest on an absent branch.

## State + concurrency model

None added. `MarkdownText` is a leaf composable with no coroutine, no flow and no scope; the only state is the existing `remember(markdown)` around the parse and per-`CodeBlock` scroll state. The table adds one `rememberScrollState()` per table, so tables scroll independently of each other and of code blocks. `parseTableAlignments` and the tilde pairing are memoised on the inputs that determine them, keeping the per-tick cost of #184's streaming re-parse where it already is.

## Error handling

No new failure modes and no new result type. The parser is total and the renderer stays total: every arm added here is a `when` branch over a node kind that the existing `else` would otherwise have rendered as raw text, so the worst outcome of a malformed construct is the pre-change output. Specifically — a table with no body rows renders its header alone; a ragged row is padded or truncated against the header's column count; a delimiter row shorter than the header leaves the remaining columns `Start`; an unpaired tilde renders as a literal `~`. None throws, none logs. Nothing here is a classified error, so no log call is added; the renderer's diagnostics posture (content-free, and message text never logged) is unchanged.

## Testing strategy

Red first for each criterion, against real parsed markdown per AC5.

**Unit — `app/src/test/…/components/MarkdownTextParsingTest.kt`.** The parser is pure Kotlin with no Android types, so both the helpers and the parser contract the renderer rests on are provable here, with no Robolectric (the project has none, per the ticket):

- `parseTableAlignments` — `:---`/`:-:`/`---:`/`---`; a row without outer pipes; a row with fewer specs than the header.
- `isCheckedTaskMark` — `[x] `, `[X] `, `[ ] `.
- `tildeCanOpen` / `tildeCanClose` — `~single~` pairs; `~/a ~/b` does not (the desktop hazard, pinned here as non-reproducing exactly as `AssistantMarkdown.test.tsx` pins it); `~~unclosed` does not.
- Parser-contract pins for AC4, each parsing real markdown with `GFMFlavourDescriptor` and asserting the node kind the renderer's fallback depends on: bare URL → a childless `GFM_AUTOLINK`; `$20 and $30` → an `INLINE_MATH` whose leaf text concatenates back to the source verbatim; `~x~` → bare `TILDE` siblings (the pin that explains why the render-time pairing exists, and the one that turns red the day the library starts parsing single tildes itself).

**Compose — `app/src/androidTest/…/components/MarkdownTextTest.kt`.** Behaviour only visible once rendered:

- Table — header and body cell text displayed; a table wider than its container leaves the container's own width unchanged (rendered in a fixed-width box, asserted on the box's bounds) and still exposes its far-column text in the tree.
- Task list — the checked and unchecked content descriptions are present, each node `assertHasNoClickAction()`; the literal `[ ]` characters are not displayed; a plain item in the same list keeps its bullet.
- Strikethrough — for `~~struck~~` and for `~struck~`, read `SemanticsProperties.Text` off the node and assert a `LineThrough` span covering the struck range in `onSurfaceVariant`; assert the tildes are not in the rendered text. This is what makes "struck and de-emphasised" an assertion rather than a preview claim.
- AC4 — a bare URL renders its literal characters and carries no `LinkAnnotation` in the rendered `AnnotatedString`; `$20 and $30` renders verbatim; `<b>bold</b>` renders as visible characters.
- One mixed message — table + task list + struck text + an existing construct (fenced code and a heading) in a single source, asserting each part, per AC5.

**Previews.** `MARKDOWN_PREVIEW_FIXTURE` gains a table with three alignments, a mixed task list and a struck span, so the light/dark pair covers the three constructs as #130 did for fenced code.

No rung-3 or rung-4 emulator scenario: this is a pure renderer change with no daemon verb, no operator-facing flow and no wire contact. #680 owns the live desktop/mobile comparison of the same replies.

## Open questions

1. **Does the mixed-content table cell keep row heights in step?** Resolved during implementation by the preview pair and the Compose test — if a monospace code span in one cell desyncs its row, the fallback is an explicit `Modifier.height(IntrinsicSize.Min)` per row band, recorded as a `## Revisions` entry.
2. **Is the feature overview's image claim right?** Measured: no. It states `![alt](url)` falls through to the raw-text fallback; in fact `IMAGE` has children under **both** flavours, so the walker recurses and renders `!` followed by the alt text as a tappable link. This is pre-existing and identical before and after the flavour change, so it is out of scope here per § Scope Discipline — no production change. The correction is carried to the documentation stage in the PR body, and the AC4 property that actually matters (no image is fetched) holds on both flavours and is pinned.

## Documentation handoff

Pending, owned by the documentation stage — no file under `docs/knowledge/` is touched by this ticket.

`docs/knowledge/features/markdown-text.md`:

- `## What it does` — the parser is `GFMFlavourDescriptor`, not `CommonMarkFlavourDescriptor`; tables, task lists and strikethrough move out of the unsupported list.
- `### Block dispatch` — add the `TABLE` row.
- `### Inline dispatch` — add the `STRIKETHROUGH` row and the bare-`TILDE` pairing.
- `## Edge cases / limitations` — record the flavour change, the inert task mark, what the bundled `GFM_AUTOLINK` and `INLINE_MATH` constructs render as and why (absence of a handler, not suppression), the non-wrapping/scrolling table cells, ragged-row truncation, the table fan-out caps from the security review, and the correction to the image claim measured in Open question 2.

## Security review

**Verdict:** PASS

The subject of this review is not the three constructs — it is what else the flavour switch lets untrusted daemon text reach. The parser now emits four node kinds from the same message string that it never emitted before (`GFM_AUTOLINK`, `INLINE_MATH`, `CHECK_BOX`, `TABLE`), and each is a new decision about text nobody on this device wrote.

**Findings:**

- **[Trust boundaries] No finding, and the reason is worth naming.** `MarkdownText` is the whole markdown boundary; text arrives untrusted as a `String` and leaves as Compose primitives. The sharp edge is `GFM_AUTOLINK`: under CommonMark a bare URL in daemon text was inert `TEXT`, and under GFM it is a *dedicated node kind that an arm could render as a link*. This design adds no such arm — the childless-leaf fallback appends its raw characters — so a daemon cannot manufacture a tappable link out of prose that never contained link markup. The test pinning this is the control, not the absent branch.

- **[Trust boundaries] No finding — the link allowlist survives the flavour change intact, verified rather than assumed.** GFM also changes `INLINE_LINK`'s shape: `LINK_DESTINATION` now holds a `GFM_AUTOLINK` child where CommonMark held `TEXT`/`:`/`TEXT`. `appendInline` reads the URL from the `LINK_DESTINATION` node's own source span and not from its children, so the string handed to `isSafeLinkScheme` is byte-identical before and after. `withLink` is still reached from exactly one arm, and `isSafeLinkScheme` is still the only path to `uriHandler.openUri`. Table cells and task-list items route through the same `appendInline`, so they inherit that gate rather than opening a second door.

- **[Trust boundaries] No finding — the accessibility channel carries no daemon text.** The task mark's `contentDescription` is a static string resource chosen by a boolean. Interpolating the item's text into it would have pushed untrusted content into a new announcement path; it is not done, and the two resources take no format argument.

- **[Network & I/O / Android attack surface] SHOULD FIX — bound the table's composable fan-out.** The grid is one `Text` per cell with no lazy layout, where the same source previously produced a single paragraph. A cell costs roughly two source characters, so a hostile or merely broken reply amplifies by a large constant into composition, measure and layout, re-paid on every streaming reveal tick (#184 re-parses ~50×/sec). Mitigation, taken in Phase B: `MaxTableColumns` / `MaxTableRows` constants applied with `take` when the grid is built, set generously above any real reply so no legitimate table is affected, with the truncation recorded in the feature overview. Two constants and two calls — deterministic, not a heuristic.

- **[Network & I/O] SHOULD FIX — keep the tilde scan single-pass.** The obvious shape for the single-tilde pairing is "for each opener, search forward for a closer", which is quadratic in the number of tildes in one paragraph and is reached by a line of lone `~` characters — cheap to author, and again re-paid per reveal tick. Folded back into § Design above before this review was written: one forward pass, one pending opener. Recorded here because the quadratic version is the one a later edit would naturally reintroduce.

- **[Network & I/O] No finding — nothing here fetches.** No construct added by the flavour initiates a network request; the renderer holds no image loader, and images render as text plus a scheme-gated link on both flavours (Open question 2). `GFMFlavourDescriptor`'s `useSafeLinks` / `makeHttpsAutoLinks` flags configure only the HTML generating providers, which this renderer never constructs — so they are inert here rather than relied upon, which is the safer of the two.

- **[Android attack surface] No finding, by construction.** No `WebView`, and that is the load-bearing decision rather than an absence: the renderer maps markdown onto Compose primitives, so daemon text has no interpreter to reach. It matches desktop's posture (no `rehypePlugins`, HTML escaped not stripped) arrived at independently. No Activity, Service, Receiver, deep link, `PendingIntent`, push path or content provider is touched.

- **[Errors, logs, telemetry] No finding — and the temptation is named so it is not taken later.** A malformed table, a ragged row or an unpaired tilde would all be natural things to log; every one of those log lines would carry untrusted daemon text into Logcat. No log call is added on any path. The renderer's diagnostics posture (content-free, message text never logged, #126) is unchanged.

- **[Concurrency] No finding.** No coroutine, scope, flow or mutable shared state is introduced. The added state is `rememberScrollState()` per table — per-composition, no cross-message sharing — and `remember` keys that are pure functions of the source string.

- **[Tokens, secrets, credentials] Not applicable, with a reason.** This ticket reads no credential and writes no storage. Nothing here can reach the device static key or the paired-server store under `data/crypto/`; the markdown string arrives already decrypted and already scoped to a message.

- **[File / storage operations] Not applicable.** No path is constructed, parsed, opened or written on any path in this design.

- **[Cryptographic primitives] Not applicable.** No RNG, no hashing, no comparison against a secret, no contact with `NoiseIkSession` or the handshake.

- **[Threat model alignment] Hostile daemon frame — addressed above** by the total renderer (every unhandled kind degrades to visible characters), the unchanged scheme allowlist, the fan-out bound, and the linear tilde scan. **Malicious relay and token theft — unchanged by this ticket**, which touches no transport and no storage. **UI-side leakage — the one new surface is the task mark's TalkBack announcement**, and it carries a static resource; the message text itself already reached accessibility services through the existing `Text` calls, so no new class of content is exposed.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-09-22
